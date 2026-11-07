package com.relayaegis.recovery;

import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.NetworkPartitions;
import com.relayaegis.runtime.RuntimeInspector;
import com.relayaegis.runtime.kubernetes.KubernetesControlService;
import com.relayaegis.runtime.kubernetes.KubernetesTarget;
import com.relayaegis.service.DockerControlService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Executes an authorized decision through the allowlisted Docker operations only. Idempotent where Docker allows:
 * starting a container that is already running is recorded as a no-op instead of being repeated. Executing an
 * action proves nothing about recovery; {@link VerificationEngine} decides that.
 */
@Component
public class RecoveryExecutor {
    private final DockerControlService docker;
    private final RuntimeInspector inspector;
    private final KubernetesControlService kubernetes;

    public RecoveryExecutor(DockerControlService docker, RuntimeInspector inspector) {
        this(docker, inspector, KubernetesControlService.disabled());
    }

    @Autowired
    public RecoveryExecutor(DockerControlService docker, RuntimeInspector inspector, KubernetesControlService kubernetes) {
        this.docker = docker;
        this.inspector = inspector;
        this.kubernetes = kubernetes;
    }

    public record ExecutionResult(boolean commandSucceeded, boolean noOp, String detail) {}

    public ExecutionResult execute(RecoveryDecision decision) { return execute(decision, null); }

    /** {@code appContainer} is the application's container; RECONNECT_NETWORK re-derives the network from it. */
    public ExecutionResult execute(RecoveryDecision decision, String appContainer) {
        if (!decision.authorized() || !decision.action().mutates()) {
            return new ExecutionResult(false, true, "Not executed: " + (decision.blockedReason() == null ? "action is not authorized." : decision.blockedReason()));
        }
        String target = decision.target();
        if (KubernetesTarget.is(target)) return executeOnKubernetes(decision.action(), target);
        if (decision.action() == RecoveryActionType.ROLLOUT_UNDO || decision.action() == RecoveryActionType.SCALE_WORKLOAD || decision.action() == RecoveryActionType.DELETE_POD) {
            return new ExecutionResult(false, false, decision.action() + " exists only on Kubernetes; " + target + " is not a Kubernetes target.");
        }
        try {
            if (decision.action() == RecoveryActionType.START_CONTAINER) {
                ContainerSnapshot before = inspector.inspect(target).orElse(null);
                if (before != null && before.exists() && before.running()) {
                    return new ExecutionResult(true, true, "Container " + target + " was already running; start skipped (idempotent).");
                }
                docker.startContainer(target);
                return new ExecutionResult(true, false, "Started container " + target + ".");
            }
            if (decision.action() == RecoveryActionType.RECONNECT_NETWORK) return reconnect(target, appContainer);
            docker.restartContainer(target);
            return new ExecutionResult(true, false, "Restarted " + (decision.action() == RecoveryActionType.RESTART_DEPENDENCY ? "dependency " : "container ") + target + ".");
        } catch (ResponseStatusException ex) {
            return new ExecutionResult(false, false, ex.getReason() == null ? "Docker operation failed." : ex.getReason());
        } catch (RuntimeException ex) {
            return new ExecutionResult(false, false, "Docker operation failed (" + ex.getClass().getSimpleName() + ").");
        }
    }

    /** Every action reads fresh state first, so a resumed or late action never repeats what has already happened. */
    private ExecutionResult executeOnKubernetes(RecoveryActionType action, String target) {
        try {
            var state = kubernetes.state(target).orElse(null);
            if (state == null) return new ExecutionResult(false, false, "The Deployment " + target + " does not exist; nothing was changed.");
            switch (action) {
                case ROLLOUT_UNDO -> {
                    if (!state.previousRevision()) return new ExecutionResult(false, false, "No earlier revision of " + target + " exists; nothing was changed.");
                    kubernetes.rolloutUndo(target);
                    return new ExecutionResult(true, false, "Rolled " + target + " back from revision " + state.revision() + " to the previous one.");
                }
                case DELETE_POD -> {
                    String pod = kubernetes.deleteWorstPod(target);
                    if (pod == null) return new ExecutionResult(false, false, "No unready pod with a ready sibling in " + target + "; nothing was changed.");
                    return new ExecutionResult(true, false, "Deleted pod " + pod + " of " + target + "; the Deployment replaces it.");
                }
                case SCALE_WORKLOAD -> { return scaleUp(target, state.desiredReplicas()); }
                case START_CONTAINER, RESTART_CONTAINER, RESTART_DEPENDENCY -> {
                    // A rollout restart of zero replicas creates nothing, so a dependency found scaled down is scaled up instead.
                    if (state.desiredReplicas() == 0) return scaleUp(target, 0);
                    kubernetes.rolloutRestart(target);
                    return new ExecutionResult(true, false, "Started a rollout restart of " + target + ".");
                }
                default -> { return new ExecutionResult(false, false, action + " is not available on Kubernetes; nothing was changed."); }
            }
        } catch (ResponseStatusException ex) {
            return new ExecutionResult(false, false, ex.getReason() == null ? "Kubernetes operation failed." : ex.getReason());
        } catch (RuntimeException ex) {
            return new ExecutionResult(false, false, "Kubernetes operation failed (" + ex.getClass().getSimpleName() + ").");
        }
    }

    private ExecutionResult scaleUp(String target, int current) {
        if (current > 0) return new ExecutionResult(true, true, target + " already has " + current + " replica(s); scale skipped (idempotent).");
        kubernetes.scale(target, kubernetes.restoreReplicas());
        return new ExecutionResult(true, false, "Scaled " + target + " from 0 to " + kubernetes.restoreReplicas() + " replica(s).");
    }

    /**
     * Re-derives the reattachment from fresh inspection immediately before acting (never from the possibly stale
     * diagnosis), confirms the network belongs to the same Compose project, then reconnects under the service alias.
     */
    private ExecutionResult reconnect(String dependency, String appContainer) {
        if (appContainer == null) return new ExecutionResult(false, false, "No application container is linked; the network to reattach cannot be determined.");
        ContainerSnapshot app = inspector.inspect(appContainer).orElse(null);
        ContainerSnapshot dep = inspector.inspect(dependency).orElse(null);
        if (dep != null && app != null && dep.exists() && app.exists() && app.composeProject() != null
                && app.networks().stream().filter(n -> n.startsWith(app.composeProject() + "_")).anyMatch(dep.networks()::contains)) {
            return new ExecutionResult(true, true, "Dependency " + dependency + " is already attached to its project network; reconnect skipped (idempotent).");
        }
        NetworkPartitions.Reattachment reattach = NetworkPartitions.reattachment(app, dep).orElse(null);
        if (reattach == null) return new ExecutionResult(false, false, "Fresh inspection no longer shows a detached dependency on a shared Compose network; nothing was changed.");
        String labels = docker.networkLabels(reattach.network());
        if (!labels.contains("\"com.docker.compose.project\":\"" + app.composeProject() + "\"")) {
            return new ExecutionResult(false, false, "Network " + reattach.network() + " is not labelled as part of Compose project " + app.composeProject() + "; nothing was changed.");
        }
        docker.reconnectNetwork(dependency, reattach.network(), reattach.alias());
        return new ExecutionResult(true, false, "Reconnected dependency " + dependency + " to " + reattach.network() + " with alias '" + reattach.alias() + "'.");
    }
}

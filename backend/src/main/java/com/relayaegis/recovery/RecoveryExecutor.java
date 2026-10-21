package com.relayaegis.recovery;

import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.NetworkPartitions;
import com.relayaegis.runtime.DockerRuntimeInspector;
import com.relayaegis.service.DockerControlService;
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
    private final DockerRuntimeInspector inspector;

    public RecoveryExecutor(DockerControlService docker, DockerRuntimeInspector inspector) {
        this.docker = docker;
        this.inspector = inspector;
    }

    public record ExecutionResult(boolean commandSucceeded, boolean noOp, String detail) {}

    public ExecutionResult execute(RecoveryDecision decision) { return execute(decision, null); }

    /** {@code appContainer} is the application's container; RECONNECT_NETWORK re-derives the network from it. */
    public ExecutionResult execute(RecoveryDecision decision, String appContainer) {
        if (!decision.authorized() || !decision.action().mutates()) {
            return new ExecutionResult(false, true, "Not executed: " + (decision.blockedReason() == null ? "action is not authorized." : decision.blockedReason()));
        }
        String target = decision.target();
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

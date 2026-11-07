package com.relayaegis.runtime.kubernetes;

import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.DockerRuntimeInspector;
import com.relayaegis.runtime.LogExcerpt;
import com.relayaegis.runtime.RuntimeInspector;
import com.relayaegis.runtime.kubernetes.KubernetesGateway.DeploymentState;
import com.relayaegis.runtime.kubernetes.KubernetesGateway.PodState;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Describes a Deployment as a {@link ContainerSnapshot}, so verification and the existing patterns work unchanged:
 * "running" means a pod is running, "health" reflects readiness, and "restarting" means a pod is in CrashLoopBackOff.
 * Restarts are counted only on unready pods, so old restarts of pods now healthy never look like a crash loop.
 * Kubernetes-only facts travel in the snapshot's labels under {@code relay.k8s/}.
 */
@Component
public class KubernetesRuntimeInspector implements RuntimeInspector {
    public static final String WAITING_REASON = "relay.k8s/waiting-reason";
    public static final String DESIRED = "relay.k8s/desired";
    public static final String READY = "relay.k8s/ready";
    public static final String UPDATED = "relay.k8s/updated";
    public static final String REVISION = "relay.k8s/revision";
    public static final String PREVIOUS_REVISION = "relay.k8s/previous-revision";
    public static final String ROLLOUT_STARTED_AT = "relay.k8s/rollout-started-at";

    private static final int LOG_TAIL = 200;
    /**
     * Most to least informative, when pods of one workload wait for different reasons. Only failures are listed:
     * ContainerCreating and PodInitializing are how every healthy pod starts.
     */
    private static final List<String> REASON_ORDER = List.of("InvalidImageName", "ImagePullBackOff", "ErrImagePull", "ErrImageNeverPull", "CreateContainerConfigError",
            "CreateContainerError", "RunContainerError", "CrashLoopBackOff", "Unschedulable");

    private final KubernetesControlService kubernetes;

    public KubernetesRuntimeInspector(KubernetesControlService kubernetes) { this.kubernetes = kubernetes; }

    @Override public boolean canInspect(String target) { return kubernetes.canInspect(target); }

    @Override
    public Optional<ContainerSnapshot> inspect(String target) {
        if (!canInspect(target)) return Optional.empty();
        Instant now = Instant.now();
        try {
            return Optional.of(kubernetes.state(target).map(state -> snapshot(target, state, now)).orElseGet(() -> ContainerSnapshot.missing(target, now)));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    static ContainerSnapshot snapshot(String target, DeploymentState state, Instant now) {
        String reason = waitingReason(state.pods());
        boolean running = state.pods().stream().anyMatch(p -> "Running".equals(p.phase()));
        boolean allReady = state.desiredReplicas() > 0 && state.readyReplicas() >= state.desiredReplicas();
        // "Healthy" must mean the latest revision finished, so verification cannot pass on pods that are on their way out.
        boolean settled = allReady && state.rolloutComplete() && state.pods().stream().filter(PodState::ready).count() >= state.desiredReplicas();
        boolean oom = state.pods().stream().anyMatch(p -> !p.ready() && "OOMKilled".equals(p.lastTerminatedReason()));
        Integer exit = state.pods().stream().filter(p -> !p.ready() && p.lastExitCode() != null).map(PodState::lastExitCode).findFirst().orElse(null);
        int restarts = state.pods().stream().filter(p -> !p.ready()).mapToInt(PodState::restarts).sum();
        String status = state.desiredReplicas() == 0 ? "scaled-to-zero" : allReady ? "available" : state.readyReplicas() > 0 ? "degraded" : "unavailable";
        String health = state.desiredReplicas() == 0 ? "unhealthy" : settled ? "healthy" : state.updatedReplicas() < state.desiredReplicas() || running ? "starting" : "unhealthy";
        if (reason != null) health = "unhealthy";

        Map<String, String> labels = new HashMap<>();
        labels.put(DESIRED, String.valueOf(state.desiredReplicas()));
        labels.put(READY, String.valueOf(state.readyReplicas()));
        labels.put(UPDATED, String.valueOf(state.updatedReplicas()));
        labels.put(REVISION, String.valueOf(state.revision()));
        labels.put(PREVIOUS_REVISION, String.valueOf(state.previousRevision()));
        if (reason != null) labels.put(WAITING_REASON, reason);
        if (state.rolloutStartedAt() != null) labels.put(ROLLOUT_STARTED_AT, state.rolloutStartedAt().toString());

        return new ContainerSnapshot(target, true, status, running, "CrashLoopBackOff".equals(reason), oom, exit, restarts, null, null, health, null,
                state.memoryLimitBytes(), null, null, null, List.of(), List.of(), List.of(), state.image(), "kubernetes", Map.copyOf(labels), now);
    }

    static String waitingReason(List<PodState> pods) {
        Set<String> reasons = new java.util.HashSet<>();
        pods.stream().filter(p -> !p.ready() && p.waitingReason() != null).forEach(p -> reasons.add(p.waitingReason()));
        return REASON_ORDER.stream().filter(reasons::contains).findFirst().orElse(null);
    }

    @Override
    public LogExcerpt logs(String target) {
        if (!canInspect(target)) return LogExcerpt.unavailable(target, "Log access is not permitted for this workload (not in the inspection allowlist).");
        try {
            DeploymentState state = kubernetes.state(target).orElse(null);
            if (state == null) return LogExcerpt.unavailable(target, "The Deployment does not exist.");
            StringBuilder text = new StringBuilder();
            state.warnings().forEach(warning -> text.append("Kubernetes event: ").append(warning).append('\n'));
            PodState pod = state.pods().stream().filter(p -> p.container() != null)
                    .max(Comparator.comparing((PodState p) -> !p.ready()).thenComparingInt(PodState::restarts)).orElse(null);
            if (pod != null && !isWaitingForImage(pod)) {
                String logs = readLogs(target, pod);
                if (logs != null) text.append(logs);
            }
            if (text.isEmpty()) return LogExcerpt.unavailable(target, "No pod of this workload has logs yet.");
            return DockerRuntimeInspector.parseLogs(target, text.toString());
        } catch (RuntimeException ex) {
            return LogExcerpt.unavailable(target, "Kubernetes could not return logs for this workload.");
        }
    }

    /** A crash-looping container has nothing current to read, so its previous instance holds the error. */
    private String readLogs(String target, PodState pod) {
        boolean previous = pod.restarts() > 0 && !pod.ready();
        try { return kubernetes.logs(target, pod, previous, LOG_TAIL); }
        catch (RuntimeException ex) { return previous ? kubernetes.logs(target, pod, false, LOG_TAIL) : null; }
    }

    private static boolean isWaitingForImage(PodState pod) {
        return pod.waitingReason() != null && (pod.waitingReason().contains("Image") || "Unschedulable".equals(pod.waitingReason()));
    }
}

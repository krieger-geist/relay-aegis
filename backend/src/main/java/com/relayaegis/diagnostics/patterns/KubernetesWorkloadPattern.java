package com.relayaegis.diagnostics.patterns;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.FailurePattern;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.LogExcerpt;
import com.relayaegis.runtime.kubernetes.KubernetesRuntimeInspector;
import com.relayaegis.runtime.kubernetes.KubernetesTarget;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Failures only a Kubernetes workload has. The key question is whether the current revision is what broke it: a pod
 * crashing or unable to pull its image right after a rollout is fixed by going back, not by restarting. Without a
 * recent rollout or an earlier revision, the same symptoms are left to a human or to the general crash-loop pattern.
 */
public class KubernetesWorkloadPattern implements FailurePattern {
    private static final Set<String> IMAGE_REASONS = Set.of("ImagePullBackOff", "ErrImagePull", "InvalidImageName");
    /** A crash this long after a rollout is unlikely to be the rollout's doing. */
    static final Duration RECENT_ROLLOUT = Duration.ofMinutes(30);

    public String id() { return "kubernetes-workload"; }

    public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
        ContainerSnapshot runtime = context.runtime();
        if (runtime == null || !runtime.exists() || !KubernetesTarget.is(runtime.name())) return Optional.empty();
        Map<String, String> labels = runtime.composeLabels();
        int desired = number(labels.get(KubernetesRuntimeInspector.DESIRED));
        int ready = number(labels.get(KubernetesRuntimeInspector.READY));
        String reason = labels.get(KubernetesRuntimeInspector.WAITING_REASON);
        boolean canGoBack = Boolean.parseBoolean(labels.get(KubernetesRuntimeInspector.PREVIOUS_REVISION));

        DiagnosticFinding.Builder finding = null;
        if (desired == 0) {
            finding = DiagnosticFinding.builder("kubernetes-scaled-to-zero", "Workload is scaled to zero", "runtime")
                    .cause("The Deployment " + runtime.name() + " has 0 replicas, so no pod is serving it.")
                    .explanation("Kubernetes is doing what it was told: a replica count of 0 will not heal by itself.")
                    .confidence(context.failing() ? 0.95 : 0.7)
                    .action(RecoveryActionType.SCALE_WORKLOAD, runtime.name(), "Scale the Deployment back up, then verify that its pods become ready and the health checks pass.");
        } else if (reason != null && IMAGE_REASONS.contains(reason)) {
            finding = DiagnosticFinding.builder("kubernetes-image-pull", "Pods cannot pull their image", "runtime")
                    .cause("Pods of " + runtime.name() + " are waiting with " + reason + " for image " + runtime.image() + ".")
                    .explanation("The image name or tag is wrong, or the registry refuses it. Restarting cannot help because every new pod needs the same image.")
                    .confidence(canGoBack ? 0.95 : 0.9);
            if (canGoBack) finding.action(RecoveryActionType.ROLLOUT_UNDO, runtime.name(), "Roll back to the previous revision, whose image is known to exist, then fix the image in the deployment.");
            else finding.action(RecoveryActionType.ESCALATE, runtime.name(), "There is no earlier revision to go back to. Correct the image name or registry credentials.");
        } else if ("CrashLoopBackOff".equals(reason) && canGoBack && recentRollout(labels)) {
            finding = DiagnosticFinding.builder("kubernetes-bad-rollout", "New revision is crash looping", "runtime")
                    .cause("Pods of " + runtime.name() + " are in CrashLoopBackOff, and the current revision was rolled out recently.")
                    .explanation("A crash loop that starts right after a rollout points at the rollout. Restarting would repeat it; the previous revision is known to have worked.")
                    .confidence(0.93)
                    .action(RecoveryActionType.ROLLOUT_UNDO, runtime.name(), "Roll back to the previous revision, then find out why the new version crashes on start-up.");
            PatternSupport.citeLogs(finding, context, 2, LogExcerpt.LogSignal.Kind.FATAL, LogExcerpt.LogSignal.Kind.EXCEPTION);
        } else if ("Unschedulable".equals(reason)) {
            finding = DiagnosticFinding.builder("kubernetes-unschedulable", "Pods cannot be scheduled", "runtime")
                    .cause("Pods of " + runtime.name() + " are pending because no node can take them.")
                    .explanation("The cluster lacks the CPU or memory the pods request, or no node matches their constraints. Nothing RELAY may do can add capacity.")
                    .confidence(0.9)
                    .action(RecoveryActionType.ESCALATE, runtime.name(), "Add capacity to the cluster or lower the pods' resource requests.");
        } else if (reason == null && !runtime.oomKilled() && desired > 1 && ready == desired - 1) {
            finding = DiagnosticFinding.builder("kubernetes-pod-unready", "One pod is not ready", "runtime")
                    .cause("One of the " + desired + " pods of " + runtime.name() + " is not ready, while the others are.")
                    .explanation("The others show the current version works, so the fault is local to that pod. Replacing it is the smallest possible action.")
                    .confidence(0.84)
                    .action(RecoveryActionType.DELETE_POD, runtime.name(), "Delete the unready pod so the Deployment replaces it, then verify all pods are ready.");
        } else if (reason == null && !runtime.oomKilled() && context.failing() && runtime.running() && ready == 0) {
            finding = DiagnosticFinding.builder("kubernetes-pods-unready", "Pods are running but not ready", "runtime")
                    .cause("Pods of " + runtime.name() + " are running, yet none passes its readiness probe.")
                    .explanation("The processes are alive but not serving: typically frozen or stuck. Kubernetes keeps unready pods out of the Service but will not replace them on readiness alone.")
                    .confidence(0.86)
                    .action(RecoveryActionType.RESTART_CONTAINER, runtime.name(), "Run a rollout restart to replace the pods one at a time, then verify they become ready.");
        }
        if (finding == null) return Optional.empty();
        PatternSupport.citeRuntime(finding, context);
        PatternSupport.citeFailures(finding, context);
        return Optional.of(finding.build());
    }

    private static boolean recentRollout(Map<String, String> labels) {
        String startedAt = labels.get(KubernetesRuntimeInspector.ROLLOUT_STARTED_AT);
        if (startedAt == null) return false;
        return Duration.between(Instant.parse(startedAt), Instant.now()).compareTo(RECENT_ROLLOUT) < 0;
    }

    private static int number(String value) {
        if (value == null) return -1;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) { return -1; }
    }
}

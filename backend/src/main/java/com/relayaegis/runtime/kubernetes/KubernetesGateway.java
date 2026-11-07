package com.relayaegis.runtime.kubernetes;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The fixed set of Kubernetes calls RELAY makes. There is no generic "apply" or "exec": nothing else can be expressed. */
public interface KubernetesGateway {
    /** State of one pod, reduced to the facts diagnosis uses. Environment, volumes and commands are never read. */
    record PodState(String name, String phase, boolean ready, int restarts, String waitingReason, String lastTerminatedReason,
                    Integer lastExitCode, String container) {}

    /**
     * {@code rolloutStartedAt} is when the current revision was created; {@code previousRevision} means an undo has somewhere to go;
     * {@code rolloutComplete} is the test {@code kubectl rollout status} uses. {@code pods} leaves out pods being deleted.
     */
    record DeploymentState(int desiredReplicas, int readyReplicas, int updatedReplicas, long revision, boolean previousRevision,
                           Instant rolloutStartedAt, String image, Long memoryLimitBytes, List<PodState> pods, List<String> warnings, boolean rolloutComplete) {}

    /** Empty when the Deployment does not exist. */
    Optional<DeploymentState> state(KubernetesTarget target);

    /** The last lines of one pod's container; {@code previous} reads the instance that just crashed. */
    String logs(KubernetesTarget target, String pod, String container, boolean previous, int tailLines);

    void rolloutRestart(KubernetesTarget target);

    void rolloutUndo(KubernetesTarget target);

    void scale(KubernetesTarget target, int replicas);

    void deletePod(KubernetesTarget target, String pod);

    /** True when the API server answers; used by the integration status page. */
    boolean reachable();
}

package com.relayaegis.recovery;

/**
 * The closed set of actions a diagnosis may recommend. Only the actions listed in {@link #mutates()} change anything,
 * and only through the allowlisted, policy-checked recovery path. On a Kubernetes target, the container actions mean a
 * rollout restart of the Deployment.
 * There is deliberately no free-form command action: neither rules nor AI can express arbitrary commands.
 */
public enum RecoveryActionType {
    /** The application is healthy; nothing to do. */
    NONE,
    /** Start a stopped (exited) application container. Idempotent: a running container is left alone. */
    START_CONTAINER,
    /** Restart the application container. */
    RESTART_CONTAINER,
    /** Restart an unhealthy dependency container (separate, stricter policy). */
    RESTART_DEPENDENCY,
    /** Reattach a healthy dependency to its own Compose project network, under its service alias (separate policy). */
    RECONNECT_NETWORK,
    /** Kubernetes: go back to the previous revision of a Deployment whose current revision is what broke it (separate policy). */
    ROLLOUT_UNDO,
    /** Kubernetes: scale a Deployment found at zero replicas back up (separate policy). */
    SCALE_WORKLOAD,
    /** Kubernetes: delete one bad pod while its siblings stay ready, so the Deployment replaces it (separate policy). */
    DELETE_POD,
    /** No safe automatic action: collect more evidence (logs, configuration) before acting. */
    INVESTIGATE,
    /** A restart would not help or is unsafe (crash loop, configuration error, external failure): needs a human. */
    ESCALATE;

    public boolean mutates() {
        return this == START_CONTAINER || this == RESTART_CONTAINER || this == RESTART_DEPENDENCY || this == RECONNECT_NETWORK
                || this == ROLLOUT_UNDO || this == SCALE_WORKLOAD || this == DELETE_POD;
    }
}

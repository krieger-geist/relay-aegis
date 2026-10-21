package com.relayaegis.recovery;

/**
 * The closed set of actions a diagnosis may recommend. Only START_CONTAINER, RESTART_CONTAINER, RESTART_DEPENDENCY
 * and RECONNECT_NETWORK mutate anything, and only through the allowlisted, policy-checked recovery path.
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
    /** No safe automatic action: collect more evidence (logs, configuration) before acting. */
    INVESTIGATE,
    /** A restart would not help or is unsafe (crash loop, configuration error, external failure): needs a human. */
    ESCALATE;

    public boolean mutates() {
        return this == START_CONTAINER || this == RESTART_CONTAINER || this == RESTART_DEPENDENCY || this == RECONNECT_NETWORK;
    }
}

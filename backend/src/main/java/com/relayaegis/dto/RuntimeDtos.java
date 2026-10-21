package com.relayaegis.dto;

import java.time.Instant;
import java.util.List;

/** Read-only Level 1 runtime view of a System: its container, its dependencies and the failure signals in its logs. */
public final class RuntimeDtos {
    private RuntimeDtos() {}

    /**
     * One runtime component. {@code state} is healthy, unhealthy, starting, stopped, restarting, missing,
     * detached (running but cut off from the application's network) or not_observable (outside the inspection allowlist or Docker unavailable); it is never inferred.
     */
    public record RuntimeComponentDto(String role, String name, String container, String source, boolean inspectable,
                                      boolean recoverable, String state, String detail, Integer restartCount, Instant startedAt) {}

    /** Deterministic log signal kinds found in the bounded, sanitized log tail. */
    public record LogSignalSummaryDto(String kind, int count, String latest) {}

    public record SystemRuntimeDto(String applicationId, boolean dockerEnabled, RuntimeComponentDto application,
                                   List<RuntimeComponentDto> dependencies, boolean logsAvailable, String logsUnavailableReason,
                                   List<LogSignalSummaryDto> logSignals, List<String> unavailable, Instant observedAt) {}
}

package com.relayaegis.runtime;

import java.util.List;

/**
 * Bounded, sanitized tail of a container's logs plus the deterministic signals extracted from it.
 * Lines are redacted by EvidenceSanitizer before they are stored, shown, or sent anywhere.
 */
public record LogExcerpt(String container, boolean available, String unavailableReason, List<String> lines, List<LogSignal> signals,
                         LogRate rate) {

    public LogExcerpt(String container, boolean available, String unavailableReason, List<String> lines, List<LogSignal> signals) {
        this(container, available, unavailableReason, lines, signals, null);
    }

    /**
     * Timing of the whole log tail that was read (not only the stored lines): when it starts and ends, and when each
     * error-like line (fatal, exception, timeout, refused) was written. Used to compare the error rate before and
     * after a failure began. Null when the lines carried no timestamps.
     */
    public record LogRate(java.time.Instant from, java.time.Instant to, int lines, List<java.time.Instant> errorTimes) {
        public long errorsBetween(java.time.Instant start, java.time.Instant end) {
            return errorTimes.stream().filter(t -> !t.isBefore(start) && t.isBefore(end)).count();
        }
    }

    public static LogExcerpt unavailable(String container, String reason) {
        return new LogExcerpt(container, false, reason, List.of(), List.of());
    }

    public boolean has(LogSignal.Kind kind) {
        return signals.stream().anyMatch(signal -> signal.kind() == kind);
    }

    public List<LogSignal> of(LogSignal.Kind kind) {
        return signals.stream().filter(signal -> signal.kind() == kind).toList();
    }

    /** A log line that matched a known failure signature. {@code target} is e.g. "db:5432" for connection errors. */
    public record LogSignal(Kind kind, int lineIndex, String line, String target) {
        public enum Kind { OOM, CONNECTION_REFUSED, DNS_FAILURE, TIMEOUT, PORT_IN_USE, FATAL, EXCEPTION, LISTENING, AUTH_FAILURE }
    }
}

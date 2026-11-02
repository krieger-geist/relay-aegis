package com.relayaegis.notify;

import java.time.Instant;

/**
 * Something about an incident that people should hear about outside RELAY (Slack, a webhook). Published as a Spring
 * application event; {@link NotificationDispatcher} delivers it off the caller's thread.
 */
public record RelayNotification(Kind kind, String incidentId, String application, String environment, String severity,
                                String title, String summary, String action, String target, String detail, Instant at) {
    public enum Kind {
        INCIDENT_OPENED("incident.opened"),
        APPROVAL_REQUIRED("recovery.approval_required"),
        RECOVERY_VERIFIED("recovery.verified"),
        RECOVERY_FAILED("recovery.not_verified");

        public final String wire;
        Kind(String wire) { this.wire = wire; }
    }
}

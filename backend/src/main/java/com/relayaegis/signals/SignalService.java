package com.relayaegis.signals;

import com.relayaegis.dto.ApiDtos.EvidenceItemDto;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.event.IncidentDetectedEvent;
import com.relayaegis.observability.RelayMetrics;
import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.service.IncidentService;
import com.relayaegis.service.MonitoringService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns alerts from other tools (Alertmanager, Sentry, any webhook) into RELAY incidents. A firing alert opens an
 * incident for the application, or is added as evidence to the one already open; the normal pipeline then diagnoses
 * it from RELAY's own observations and the recovery policy decides. An alert never runs an action by itself, and a
 * "resolved" alert is only recorded: RELAY closes an incident after its own checks see the application healthy.
 * Repeated deliveries of the same alert state are recorded once.
 */
@Service
public class SignalService {
    /** One alert, normalised from any source. {@code status} is {@code firing} or {@code resolved}. */
    public record Signal(String source, String fingerprint, String status, String title, String detail, String severity, String link,
                         Instant startsAt) {}

    public record Outcome(String applicationId, String incidentId, String result) {}

    private final ApplicationRepository applications;
    private final IncidentService incidents;
    private final ApplicationEventPublisher events;
    private final RelayMetrics metrics;

    public SignalService(ApplicationRepository applications, IncidentService incidents, ApplicationEventPublisher events, RelayMetrics metrics) {
        this.applications = applications;
        this.incidents = incidents;
        this.events = events;
        this.metrics = metrics;
    }

    /** The registered application an alert refers to: by id, name (case-insensitive) or Docker container name. */
    public Optional<ApplicationEntity> resolve(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        String k = key.trim();
        return applications.findById(k).or(() -> applications.findAll().stream()
                .filter(a -> k.equalsIgnoreCase(a.name) || k.equals(a.dockerContainer)).findFirst());
    }

    @Transactional
    public Outcome receive(ApplicationEntity app, Signal signal) {
        boolean firing = !"resolved".equals(signal.status());
        String evidenceId = "sig-" + digest(signal.source() + "|" + signal.fingerprint() + "|" + signal.status());
        String component = MonitoringService.componentFor(app);
        Optional<IncidentEntity> open = incidents.findOpenIncident(app.id, component);
        metrics.signalReceived(signal.source(), signal.status());

        if (open.isPresent()) {
            IncidentEntity incident = open.get();
            if (incidents.evidence(incident).stream().anyMatch(e -> evidenceId.equals(e.id()))) return new Outcome(app.id, incident.id, "duplicate");
            incidents.appendEvidence(incident, evidence(evidenceId, signal, firing ? "Alert from " + signal.source() + ": " + signal.title()
                    : "Alert resolved in " + signal.source() + ": " + signal.title()));
            incidents.save(incident);
            return new Outcome(app.id, incident.id, firing ? "attached" : "resolved_noted");
        }
        if (!firing) return new Outcome(app.id, null, "no_open_incident");

        Instant now = Instant.now();
        String summary = signal.source() + " reported: " + signal.title() + (signal.detail() == null ? "" : ". " + signal.detail());
        var created = incidents.create(app, clip(signal.source() + " alert: " + signal.title(), 240), severity(signal.severity()), component,
                clip(summary, 2000), "live", "external_alert", signal.source());
        Instant startedAt = signal.startsAt() != null && signal.startsAt().isBefore(now) ? signal.startsAt() : now;
        long detectionMs = Math.max(1, Duration.between(startedAt, now).toMillis());
        incidents.recordDetectionTiming(created.id(), startedAt, detectionMs);
        metrics.incidentOpened(app.id, detectionMs);
        IncidentEntity incident = incidents.findEntity(created.id());
        incidents.appendEvidence(incident, evidence(evidenceId, signal, "Alert from " + signal.source() + ": " + signal.title()));
        incidents.save(incident);
        events.publishEvent(new IncidentDetectedEvent(created.id(), app.id));
        return new Outcome(app.id, created.id(), "opened");
    }

    private static EvidenceItemDto evidence(String id, Signal signal, String title) {
        String detail = (signal.detail() == null ? "" : signal.detail() + " ")
                + (signal.severity() == null ? "" : "Severity " + signal.severity() + ". ")
                + (signal.link() == null ? "" : "Source: " + signal.link() + ". ")
                + "Reported by " + signal.source() + "; RELAY verifies the failure with its own checks before acting.";
        return new EvidenceItemDto(id, "external_alert", clip(title, 240), clip(detail, 2000), signal.source(), Instant.now(), true);
    }

    static String severity(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        return switch (s) {
            case "critical", "fatal", "page", "p1", "sev-1", "sev1" -> "Sev-1";
            case "info", "debug", "low", "p4", "p5" -> "Sev-3";
            default -> "Sev-2";
        };
    }

    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16); }
        catch (Exception ex) { return Integer.toHexString(text.hashCode()); }
    }

    static String clip(String text, int max) { return text == null || text.length() <= max ? text : text.substring(0, max - 3) + "..."; }

    /** First non-blank value among the keys of a label/tag map. */
    static String first(Map<String, String> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) { String v = map.get(key); if (v != null && !v.isBlank()) return v; }
        return null;
    }
}

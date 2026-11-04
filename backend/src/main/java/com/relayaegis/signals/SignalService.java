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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns alerts from other tools (Alertmanager, Sentry, any webhook) into RELAY incidents. A firing alert opens an
 * incident for the application, or is added as evidence to the one already open; the normal pipeline then diagnoses
 * it from RELAY's own observations and the recovery policy decides. An alert never runs an action by itself, and a
 * "resolved" alert is only recorded: RELAY closes an incident after its own checks see the application healthy.
 * While an alert is still firing, the incident stays open even if RELAY's own checks pass (the alert may watch
 * something the health check does not); a firing alert not repeated for {@link #ALERT_TTL} no longer holds it open.
 * Repeated deliveries of the same alert state are recorded once (a still-firing alert at most once an hour).
 */
@Service
public class SignalService {
    /** A firing alert keeps its incident open this long after it was last reported (Alertmanager repeats every 4h). */
    public static final Duration ALERT_TTL = Duration.ofHours(6);
    private static final Duration REFIRE_NOTE = Duration.ofHours(1);
    private static final String PREFIX = "sig-";

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
        String alertKey = digest(signal.source() + "|" + signal.fingerprint());
        // The id carries the alert and its state, so the latest state of each alert can be read back from the evidence.
        String evidenceId = PREFIX + alertKey + "-" + (firing ? "f" : "r") + "-" + UUID.randomUUID().toString().substring(0, 6);
        String component = MonitoringService.componentFor(app);
        Optional<IncidentEntity> open = incidents.findOpenIncident(app.id, component);
        metrics.signalReceived(signal.source(), signal.status());

        if (open.isPresent()) {
            IncidentEntity incident = open.get();
            AlertState last = latestStates(incidents.evidence(incident)).get(alertKey);
            if (last != null && last.firing() == firing && (!firing || last.at().isAfter(Instant.now().minus(REFIRE_NOTE)))) {
                return new Outcome(app.id, incident.id, "duplicate");
            }
            String verb = !firing ? "Alert resolved in " : last != null && last.firing() ? "Alert still firing in " : "Alert from ";
            incidents.appendEvidence(incident, evidence(evidenceId, signal, verb + signal.source() + ": " + signal.title()));
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

    private record AlertState(boolean firing, Instant at, String title) {}

    /** Titles of the external alerts on this incident whose latest state is firing, reported within {@link #ALERT_TTL}. */
    public static List<String> firingAlerts(List<EvidenceItemDto> evidence, Instant now) {
        return latestStates(evidence).values().stream()
                .filter(s -> s.firing() && s.at() != null && s.at().isAfter(now.minus(ALERT_TTL))).map(AlertState::title).toList();
    }

    private static Map<String, AlertState> latestStates(List<EvidenceItemDto> evidence) {
        Map<String, AlertState> latest = new HashMap<>();
        for (EvidenceItemDto item : evidence) {
            String id = item.id();
            // sig-<16 hex alert key>-<f|r>-<suffix>
            if (id == null || !id.startsWith(PREFIX) || id.length() < PREFIX.length() + 19 || id.charAt(PREFIX.length() + 16) != '-') continue;
            String key = id.substring(PREFIX.length(), PREFIX.length() + 16);
            AlertState state = new AlertState(id.charAt(PREFIX.length() + 17) == 'f', item.timestamp(), item.title());
            AlertState previous = latest.get(key);
            if (previous == null || previous.at() == null || (state.at() != null && !state.at().isBefore(previous.at()))) latest.put(key, state);
        }
        return latest;
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

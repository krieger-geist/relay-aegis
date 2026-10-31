package com.relayaegis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.relayaegis.dto.ApiDtos.EvidenceItemDto;
import com.relayaegis.dto.ApiDtos.IncidentDto;
import com.relayaegis.dto.ApiDtos.RecoveryStepDto;
import com.relayaegis.dto.ApiDtos.VerificationDto;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.observability.RelayMetrics;
import com.relayaegis.repository.IncidentRepository;
import com.relayaegis.util.JsonSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IncidentService {
    private final IncidentRepository incidents;
    private final JsonSupport json;
    private final RelayMetrics metrics;

    public IncidentService(IncidentRepository incidents, JsonSupport json, RelayMetrics metrics) {
        this.incidents = incidents; this.json = json; this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public List<IncidentDto> list() { return incidents.findAllByOrderByDetectedAtDesc().stream().map(this::toDto).toList(); }

    @Transactional(readOnly = true)
    public IncidentDto get(String id) { return toDto(findEntity(id)); }

    @Transactional
    public IncidentDto create(ApplicationEntity app, String title, String severity, String component,
                              String summary, String provenance) {
        Instant now = Instant.now();
        IncidentEntity e = new IncidentEntity();
        e.id = "INC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        e.title = title;
        e.applicationId = app.id;
        e.applicationName = app.name;
        e.severity = severity;
        e.state = "detected";
        e.status = "active_incident";
        e.detectedAt = now;
        e.component = component;
        e.summary = summary;
        e.likelyCause = "Diagnosis has not completed. Review observed evidence before selecting an action.";
        e.confidence = null;
        e.supportingEvidenceJson = json.write(List.of());
        e.evidenceJson = json.write(List.of(new EvidenceItemDto("ev-" + UUID.randomUUID().toString().substring(0, 8),
                "health_check", "Failure observed", summary, "RELAY monitoring", now, true)));
        e.verificationStatus = "not_run";
        e.verificationDetail = "No recovery action has been executed.";
        e.recoveryDurationMs = null;
        e.timelineJson = json.write(List.of(
                new RecoveryStepDto("step-1", "Failure detected", summary, now, "completed", "system"),
                new RecoveryStepDto("step-2", "Evidence collected", "Observed incident evidence recorded.", now, "completed", "system"),
                new RecoveryStepDto("step-3", "Diagnosis", "Diagnosis has not completed.", null, "current", "ai"),
                new RecoveryStepDto("step-4", "Action taken", "No recovery action executed.", null, "pending", "system"),
                new RecoveryStepDto("step-5", "Verification", "Awaiting an authorized recovery action.", null, "pending", "system"),
                new RecoveryStepDto("step-6", "Recovery verified", "Requires a passing post-action check.", null, "pending", "system")));
        e.provenance = provenance;
        incidents.save(e);
        return toDto(e);
    }

    /** The open (not yet recovered) incident for this application component, newest first. */
    @Transactional(readOnly = true)
    public java.util.Optional<IncidentEntity> findOpenIncident(String applicationId, String component) {
        return incidents.findByApplicationIdOrderByDetectedAtDesc(applicationId).stream()
                .filter(i -> java.util.Objects.equals(i.component, component) && !"recovered".equals(i.state))
                .findFirst();
    }

    /**
     * Closes an incident because monitoring observed the application healthy again. This is NOT a
     * verified recovery: no action is credited, verification status is left as recorded, and the
     * status is "resolved" rather than "recovery_verified".
     */
    @Transactional
    public IncidentEntity resolveAfterObservedRecovery(IncidentEntity incident, Instant observedAt, String detail) {
        incident.state = "recovered";
        incident.status = "resolved";
        incident.recoveredAt = observedAt;
        cancelPendingAiRetry(incident);
        boolean actionAttempted = !"not_run".equals(incident.verificationStatus);
        if (!actionAttempted) {
            incident.verificationDetail = "No recovery action was executed. " + detail;
            updateTimelineStep(incident, 3, "No action taken", "The incident closed without a recovery action.", "completed", "system", observedAt);
            updateTimelineStep(incident, 4, "Verification", "No recovery action to verify.", "completed", "system", observedAt);
        }
        updateTimelineStep(incident, 5, "Observed recovery", detail, "completed", "system", observedAt);
        metrics.incidentResolved(incident.applicationId, "observed", incident.detectedAt, observedAt);
        appendEvidence(incident, new EvidenceItemDto("ev-" + UUID.randomUUID().toString().substring(0, 8),
                "health_check", "Recovery observed", detail, "URL monitor", observedAt, true));
        return incidents.save(incident);
    }

    /**
     * A closed incident has nothing left for AI to reason about: any scheduled background AI retry is cleared from
     * the stored diagnosis (the attempt history is kept). The scheduler also skips recovered incidents.
     */
    public void cancelPendingAiRetry(IncidentEntity incident) {
        if (incident.diagnosisJson == null || incident.diagnosisJson.isBlank()) return;
        Map<String, Object> diagnosis = json.read(incident.diagnosisJson, new TypeReference<Map<String, Object>>() {}, null);
        if (diagnosis == null || diagnosis.get("nextAiRetryAt") == null) return;
        diagnosis.put("nextAiRetryAt", null);
        incident.diagnosisJson = json.write(diagnosis);
    }

    public IncidentEntity findEntity(String id) {
        return incidents.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found."));
    }

    /** Incidents whose recovery is marked as running ("action_taken" or "verifying"). */
    public List<IncidentEntity> findInRecovery() { return incidents.findByStateIn(List.of("action_taken", "verifying")); }

    @Transactional
    public IncidentEntity save(IncidentEntity incident) { return incidents.save(incident); }

    public IncidentDto toDto(IncidentEntity e) {
        List<String> supporting = json.read(e.supportingEvidenceJson, new TypeReference<List<String>>() {}, List.of());
        List<EvidenceItemDto> evidence = json.read(e.evidenceJson, new TypeReference<List<EvidenceItemDto>>() {}, List.of());
        List<RecoveryStepDto> timeline = json.read(e.timelineJson, new TypeReference<List<RecoveryStepDto>>() {}, List.of());
        return new IncidentDto(e.id, e.title, e.applicationId, e.applicationName, e.severity, e.state, e.status,
                e.detectedAt, e.detectionDurationMs, relativeLabel(e.detectedAt), e.component, e.summary, e.likelyCause, e.confidence,
                supporting, evidence, new VerificationDto(e.verificationStatus, e.verificationDetail),
                e.recoveryDurationMs, timeline, e.provenance,
                json.read(e.diagnosisJson, new TypeReference<com.relayaegis.dto.ApiDtos.DiagnosisDto>() {}, null));
    }

    public void updateTimelineStep(IncidentEntity incident, int stepIndex, String label, String detail,
                                   String state, String actor, Instant timestamp) {
        List<RecoveryStepDto> steps = new ArrayList<>(json.read(incident.timelineJson, new TypeReference<List<RecoveryStepDto>>() {}, List.of()));
        while (steps.size() <= stepIndex) steps.add(new RecoveryStepDto("step-" + (steps.size() + 1), "Pending", "", null, "pending", "system"));
        RecoveryStepDto old = steps.get(stepIndex);
        steps.set(stepIndex, new RecoveryStepDto(old.id(), label == null ? old.label() : label,
                detail == null ? old.detail() : detail, timestamp == null ? old.timestamp() : timestamp, state, actor));
        incident.timelineJson = json.write(steps);
    }

    @Transactional
    public void recordDetectionTiming(String incidentId, Instant failureStartedAt, long detectionDurationMs) {
        IncidentEntity incident = findEntity(incidentId);
        incident.detectedAt = failureStartedAt;
        incident.detectionDurationMs = Math.max(0, detectionDurationMs);
        updateTimelineStep(incident, 0, "Failure detected", incident.summary, "completed", "system", failureStartedAt);
        save(incident);
    }

    public void appendEvidence(IncidentEntity incident, EvidenceItemDto item) {
        List<EvidenceItemDto> items = new ArrayList<>(json.read(incident.evidenceJson, new TypeReference<List<EvidenceItemDto>>() {}, List.of()));
        items.add(item);
        incident.evidenceJson = json.write(items);
    }

    private String relativeLabel(Instant time) {
        if (time == null) return "Not checked";
        long seconds = Math.max(0, Duration.between(time, Instant.now()).getSeconds());
        if (seconds < 60) return seconds + " sec ago";
        if (seconds < 3600) return (seconds / 60) + " min ago";
        if (seconds < 86400) return (seconds / 3600) + " hr ago";
        return (seconds / 86400) + " days ago";
    }
}

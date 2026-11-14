package com.relayaegis.service;

import com.relayaegis.dto.ApiDtos.DiagnosisDto;
import com.relayaegis.dto.ApiDtos.EvidenceItemDto;
import com.relayaegis.dto.ApiDtos.HypothesisDto;
import com.relayaegis.dto.ApiDtos.IncidentDto;
import com.relayaegis.dto.ApiDtos.RecoveryStepDto;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.entity.RecoveryActionEntity;
import com.relayaegis.repository.IncidentRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds a blameless post-incident report from what RELAY already stored: the incident, its evidence, the deterministic
 * diagnosis, every journaled recovery attempt and the verification result. Nothing is invented or sent to AI; a field
 * RELAY did not observe is reported as not recorded. The same report is returned as structured data (for the page)
 * and as Markdown (to paste into a wiki or ticket).
 */
@Service
public class PostIncidentReportService {
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    public record Entry(Instant at, String label, String detail) {}

    public record Action(int attempt, String action, String target, boolean automatic, String policy, String phase,
                         String verification, String detail, Instant at) {}

    public record Report(String incidentId, String title, String application, String component, String severity,
                         String outcome, Instant detectedAt, Instant recoveredAt, Long detectionMs, Long recoveryMs,
                         String summary, String rootCause, String failureType, String confidence, String diagnosisSource,
                         List<Entry> timeline, List<Action> actions, String verification, List<String> evidence,
                         List<String> followUps, int similarIncidents30d, Instant generatedAt, String markdown) {}

    private final IncidentService incidents;
    private final RecoveryOrchestrationService recovery;
    private final IncidentRepository repository;

    public PostIncidentReportService(IncidentService incidents, RecoveryOrchestrationService recovery, IncidentRepository repository) {
        this.incidents = incidents;
        this.recovery = recovery;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Report report(String incidentId) {
        IncidentEntity entity = incidents.findEntity(incidentId);
        IncidentDto dto = incidents.toDto(entity);
        List<RecoveryActionEntity> journal = recovery.actions(incidentId);
        int similar = (int) repository.findByApplicationIdOrderByDetectedAtDesc(entity.applicationId).stream()
                .filter(i -> !i.id.equals(entity.id) && i.detectedAt != null && entity.detectedAt != null
                        && i.detectedAt.isAfter(entity.detectedAt.minus(Duration.ofDays(30))) && sameComponent(i.component, entity.component))
                .count();
        return build(dto, journal, similar, Instant.now());
    }

    private static boolean sameComponent(String a, String b) { return a == null ? b == null : a.equals(b); }

    /** Pure: the report from stored records. Package-private for tests. */
    static Report build(IncidentDto incident, List<RecoveryActionEntity> journal, int similar, Instant now) {
        DiagnosisDto diagnosis = incident.diagnosis();
        HypothesisDto top = diagnosis == null || diagnosis.engine() == null || diagnosis.engine().hypotheses() == null
                || diagnosis.engine().hypotheses().isEmpty() ? null : diagnosis.engine().hypotheses().get(0);
        String failureType = failureType(incident);
        String rootCause = top != null ? top.cause() : diagnosis != null && diagnosis.probableCause() != null ? diagnosis.probableCause()
                : incident.likelyCause() != null ? incident.likelyCause() : "Not established: no diagnosis was recorded.";
        String confidence = top != null ? String.format(Locale.ROOT, "%.0f%% (%s)", top.confidence() * 100, top.confidenceLabel())
                : diagnosis != null ? diagnosis.confidence() : "not recorded";
        String source = diagnosis == null ? "none"
                : top != null && (diagnosis.aiSelectedHypothesis() == null || "not_required".equals(diagnosis.engine().aiEscalation()))
                        ? "deterministic engine (no AI)"
                        : diagnosis.provider() != null ? "AI-assisted (" + diagnosis.provider() + "), checked against the engine" : "rule-based";

        List<Entry> timeline = new ArrayList<>();
        timeline.add(new Entry(incident.detectedAt(), "Incident opened", incident.summary()));
        for (RecoveryStepDto step : incident.timeline()) {
            if (step.timestamp() != null && "completed".equals(step.state())) timeline.add(new Entry(step.timestamp(), step.label(), step.detail()));
        }
        for (RecoveryActionEntity a : journal) {
            if (a.executedAt != null) timeline.add(new Entry(a.executedAt, "Action executed: " + a.action, a.executionDetail));
            if (a.finishedAt != null && a.verificationStatus != null) timeline.add(new Entry(a.finishedAt, "Verification " + a.verificationStatus, a.verificationDetail));
        }
        if (incident.verification() != null && "passed".equals(incident.verification().status()) && incident.recoveryDurationMs() != null) {
            timeline.add(new Entry(incident.detectedAt().plusMillis(incident.recoveryDurationMs()), "Recovered", incident.verification().detail()));
        }
        timeline.sort(Comparator.comparing(Entry::at, Comparator.nullsLast(Comparator.naturalOrder())));
        List<Entry> deduped = new ArrayList<>();
        for (Entry e : timeline) {
            if (deduped.stream().noneMatch(d -> d.label().equals(e.label()) && java.util.Objects.equals(d.at(), e.at()))) deduped.add(e);
        }

        List<Action> actions = journal.stream().map(a -> new Action(a.attempt, a.action, a.target, a.automatic, a.policyId, a.phase,
                a.verificationStatus, a.executionDetail, a.executedAt != null ? a.executedAt : a.intendedAt)).toList();
        List<String> evidence = incident.evidence().stream().filter(EvidenceItemDto::observed)
                .filter(e -> !"recovery".equals(e.type()) && !"verification".equals(e.type()))
                .map(e -> e.title() + (e.detail() == null || e.detail().isBlank() ? "" : ": " + e.detail())).limit(12).toList();
        if (top != null && top.supportingEvidence() != null) {
            List<String> refs = new ArrayList<>(evidence);
            top.supportingEvidence().forEach(ref -> { if (refs.size() < 16) refs.add("[" + ref.source() + "] " + ref.summary()); });
            evidence = refs;
        }
        String outcome = outcome(incident);
        Instant recoveredAt = incident.recoveryDurationMs() == null ? null : incident.detectedAt().plusMillis(incident.recoveryDurationMs());
        List<String> followUps = followUps(incident, journal, top, similar);
        String verification = incident.verification() == null ? "not recorded"
                : incident.verification().status() + (incident.verification().detail() == null ? "" : ": " + incident.verification().detail());

        Report draft = new Report(incident.id(), incident.title(), incident.applicationName(), incident.component(), incident.severity(),
                outcome, incident.detectedAt(), recoveredAt, incident.detectionDurationMs(), incident.recoveryDurationMs(), incident.summary(),
                rootCause, failureType, confidence, source, deduped, actions, verification, evidence, followUps, similar, now, null);
        return new Report(draft.incidentId(), draft.title(), draft.application(), draft.component(), draft.severity(), draft.outcome(),
                draft.detectedAt(), draft.recoveredAt(), draft.detectionMs(), draft.recoveryMs(), draft.summary(), draft.rootCause(),
                draft.failureType(), draft.confidence(), draft.diagnosisSource(), draft.timeline(), draft.actions(), draft.verification(),
                draft.evidence(), draft.followUps(), draft.similarIncidents30d(), draft.generatedAt(), markdown(draft));
    }

    /** The deterministic pattern that explained the incident, or "unclassified". Shared with the reliability scorecard. */
    public static String failureType(IncidentDto incident) {
        DiagnosisDto d = incident.diagnosis();
        if (d != null && d.engine() != null && d.engine().topPatternId() != null && !d.engine().topPatternId().isBlank()) return d.engine().topPatternId();
        if (incident.component() != null && incident.component().equals(SloService.COMPONENT)) return "error-budget-burn";
        return "unclassified";
    }

    static String outcome(IncidentDto incident) {
        String v = incident.verification() == null ? null : incident.verification().status();
        if ("passed".equals(v)) return "Recovered and verified";
        if ("recovered".equals(incident.state())) return "Resolved on its own (observed healthy checks, no verified action)";
        if ("failed".equals(v) || "timed_out".equals(v)) return "Recovery attempted but not verified; still open";
        return "Open";
    }

    static List<String> followUps(IncidentDto incident, List<RecoveryActionEntity> journal, HypothesisDto top, int similar) {
        List<String> out = new ArrayList<>();
        boolean verified = incident.verification() != null && "passed".equals(incident.verification().status());
        boolean anyAutomatic = journal.stream().anyMatch(a -> a.automatic);
        boolean failedAttempt = journal.stream().anyMatch(a -> "not_verified".equals(a.phase) || "execution_failed".equals(a.phase));
        if (similar > 0) {
            out.add("This failure recurred " + similar + " other time(s) on the same component in 30 days. Recovery treats the symptom; "
                    + "fix the root cause in code or configuration (see the Code root cause panel).");
        }
        if (journal.isEmpty() && !"recovered".equals(incident.state())) {
            out.add(top == null || top.recommendedAction() == null || "NONE".equals(top.recommendedAction())
                    ? "No runbook covers this failure. Write one (runbooks/*.yaml) so the next occurrence has a reviewed action."
                    : "The recommended action (" + top.recommendedAction() + ") was not run. Review whether its policy should allow it.");
        }
        if (verified && !anyAutomatic && !journal.isEmpty()) {
            out.add("Recovery needed an operator's approval. If this action is safe and recurring, consider allowing it automatically in its policy.");
        }
        if (failedAttempt) out.add("At least one attempt did not verify. Check why the action did not restore service before relying on it again.");
        if (top != null && top.missingEvidence() != null && !top.missingEvidence().isEmpty()) {
            out.add("Evidence that was missing and would raise confidence: " + String.join("; ", top.missingEvidence().stream().limit(3).toList()) + ".");
        }
        if (incident.detectionDurationMs() != null && incident.detectionDurationMs() > 120_000) {
            out.add("Detection took " + seconds(incident.detectionDurationMs()) + ". A shorter check interval or a journey check would catch this sooner.");
        }
        if (out.isEmpty()) out.add("No follow-up needed: detected, diagnosed, recovered and verified within policy.");
        return out;
    }

    static String seconds(Long ms) {
        if (ms == null) return "not measured";
        if (ms < 1000) return ms + " ms";
        long s = Math.round(ms / 1000.0);
        return s < 120 ? s + " s" : (s / 60) + " min " + (s % 60) + " s";
    }

    private static String t(Instant at) { return at == null ? "not recorded" : TIME.format(at); }

    private static String cell(String value) { return value == null ? "" : value.replace("|", "\\|").replace("\n", " "); }

    static String markdown(Report r) {
        StringBuilder md = new StringBuilder();
        md.append("# Post-incident report: ").append(r.title()).append("\n\n");
        md.append("_Blameless report generated by RELAY AEGIS from stored evidence on ").append(t(r.generatedAt())).append("._\n\n");
        md.append("| | |\n|---|---|\n");
        md.append("| Incident | ").append(r.incidentId()).append(" |\n");
        md.append("| System | ").append(cell(r.application())).append(" (`").append(cell(r.component())).append("`) |\n");
        md.append("| Severity | ").append(r.severity()).append(" |\n");
        md.append("| Outcome | ").append(r.outcome()).append(" |\n");
        md.append("| Detected | ").append(t(r.detectedAt())).append(" |\n");
        md.append("| Time to detect | ").append(seconds(r.detectionMs())).append(" |\n");
        md.append("| Time to recover | ").append(seconds(r.recoveryMs())).append(" |\n");
        md.append("| Failure type | `").append(r.failureType()).append("` |\n\n");
        md.append("## Summary\n\n").append(r.summary() == null ? "Not recorded." : r.summary()).append("\n\n");
        md.append("## Root cause\n\n").append(r.rootCause()).append("\n\n");
        md.append("Confidence: ").append(r.confidence()).append(". Diagnosed by: ").append(r.diagnosisSource()).append(".\n\n");
        md.append("## Timeline (UTC)\n\n| Time | Event | Detail |\n|---|---|---|\n");
        for (Entry e : r.timeline()) md.append("| ").append(t(e.at())).append(" | ").append(cell(e.label())).append(" | ").append(cell(e.detail())).append(" |\n");
        md.append("\n## Actions taken\n\n");
        if (r.actions().isEmpty()) md.append("No recovery action was run.\n\n");
        else {
            md.append("| # | Action | Target | Started by | Policy | Result |\n|---|---|---|---|---|---|\n");
            for (Action a : r.actions()) {
                md.append("| ").append(a.attempt()).append(" | ").append(a.action()).append(" | ").append(cell(a.target())).append(" | ")
                        .append(a.automatic() ? "policy (automatic)" : "operator approval").append(" | ").append(cell(a.policy())).append(" | ")
                        .append(cell(a.verification() != null ? a.verification() : a.phase())).append(" |\n");
            }
            md.append('\n');
        }
        md.append("## Verification\n\n").append(r.verification()).append("\n\n");
        md.append("## Evidence\n\n");
        if (r.evidence().isEmpty()) md.append("No observed evidence was stored.\n");
        r.evidence().forEach(e -> md.append("- ").append(e.replace("\n", " ")).append('\n'));
        md.append("\n## Follow-ups\n\n");
        r.followUps().forEach(f -> md.append("- [ ] ").append(f).append('\n'));
        return md.toString();
    }
}

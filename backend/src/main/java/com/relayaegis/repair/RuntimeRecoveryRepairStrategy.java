package com.relayaegis.repair;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.auth.AuthenticatedUser;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.DiagnosticResult;
import com.relayaegis.diagnostics.EvidenceRef;
import com.relayaegis.dto.ApiDtos.DiagnosisDto;
import com.relayaegis.dto.ApiDtos.EvidenceItemDto;
import com.relayaegis.dto.ApiDtos.HypothesisDto;
import com.relayaegis.dto.FindingDtos.RepairProgressDto;
import com.relayaegis.dto.FindingDtos.RepairState;
import com.relayaegis.dto.FindingDtos.RepairStepDto;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.recovery.RecoveryDecision;
import com.relayaegis.recovery.RecoveryPolicyEngine;
import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.service.IncidentService;
import com.relayaegis.service.RecoveryOrchestrationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Runtime recovery through the existing engine: the deterministic diagnosis selects the action, the existing
 * {@link RecoveryPolicyEngine} authorizes it (production, allowlist, policy mode, cooldown, circuit breaker, attempt
 * limit), {@link RecoveryOrchestrationService} executes it through allowlisted Docker operations, and the existing
 * verification decides the outcome. An accepted Docker command alone is never reported as resolved.
 */
@Component
public class RuntimeRecoveryRepairStrategy implements RepairStrategy {
    public static final String ID = "runtime-recovery";

    private final IncidentService incidents;
    private final ApplicationRepository applications;
    private final RecoveryPolicyEngine policy;
    private final RecoveryOrchestrationService recovery;
    private final ObjectMapper mapper;

    public RuntimeRecoveryRepairStrategy(IncidentService incidents, ApplicationRepository applications, RecoveryPolicyEngine policy,
                                         RecoveryOrchestrationService recovery, ObjectMapper mapper) {
        this.incidents = incidents;
        this.applications = applications;
        this.policy = policy;
        this.recovery = recovery;
        this.mapper = mapper;
    }

    @Override public String id() { return ID; }

    @Override public boolean supports(Target target) { return target.incidentId() != null; }

    /** The same read-only policy verdict "Run recovery" uses (operator approval mode). Nothing is recorded or executed. */
    @Override public Assessment assess(Target target, AuthenticatedUser user) {
        if (user == null) return Assessment.block("Sign in to start a repair.");
        IncidentEntity incident = incidents.findEntity(target.incidentId());
        return assess(incident);
    }

    public Assessment assess(IncidentEntity incident) {
        Optional<HypothesisDto> top = topHypothesis(incident);
        if (top.isEmpty()) return Assessment.block("The incident has no deterministic diagnosis yet.");
        RecoveryActionType action;
        try { action = RecoveryActionType.valueOf(top.get().recommendedAction()); }
        catch (Exception ex) { return Assessment.block("The diagnosis does not name a supported recovery action."); }
        if (!action.mutates()) return Assessment.block("No safe automated recovery applies: " + top.get().actionText());
        ApplicationEntity app = applications.findById(incident.applicationId).orElse(null);
        if (app == null) return Assessment.block("The application for this incident no longer exists.");
        RecoveryDecision decision = policy.decide(incident, app, result(top.get()), false);
        return decision.authorized() ? Assessment.allow() : Assessment.block(decision.blockedReason());
    }

    @Override public List<String> plan(Target target) {
        return List.of("Policy check", "Recovery action executed", "Health verification");
    }

    @Override public RepairProgressDto execute(Target target, AuthenticatedUser user) {
        recovery.beginRecovery(target.incidentId()); // existing path: re-checks policy, records the decision, runs and verifies
        return verify(target);
    }

    @Override public RepairProgressDto verify(Target target) {
        return verify(incidents.findEntity(target.incidentId()));
    }

    public RepairProgressDto verify(IncidentEntity incident) {
        List<EvidenceItemDto> evidence = incidents.toDto(incident).evidence().stream()
                .filter(e -> "recovery".equals(e.type()) || "verification".equals(e.type()))
                .sorted(Comparator.comparing(EvidenceItemDto::timestamp, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        Optional<EvidenceItemDto> decision = last(evidence, e -> e.title().endsWith("recovery policy decision"));
        Optional<EvidenceItemDto> attempt = last(evidence, e -> e.title().startsWith("Recovery attempt"));
        Optional<EvidenceItemDto> check = last(evidence, e -> e.title().startsWith("Verification after attempt"));
        String link = "/incidents/" + incident.id;
        String modified = attempt.map(a -> a.detail()).orElse(null);

        boolean running = "action_taken".equals(incident.state) || "verifying".equals(incident.state);
        if (running || "passed".equals(incident.verificationStatus) || "failed".equals(incident.verificationStatus) || "timed_out".equals(incident.verificationStatus)) {
            List<RepairStepDto> steps = new ArrayList<>();
            steps.add(new RepairStepDto("Policy check", decision.map(d -> d.detail().startsWith("Authorized") ? "passed" : "failed").orElse("passed"),
                    decision.map(EvidenceItemDto::detail).orElse(null)));
            steps.add(new RepairStepDto("Recovery action executed", attempt.isPresent() ? "passed" : running ? "running" : "skipped", modified));
            String verification = "passed".equals(incident.verificationStatus) ? "passed" : running ? "running" : "failed";
            steps.add(new RepairStepDto("Health verification", verification, check.map(EvidenceItemDto::detail).orElse(incident.verificationDetail)));
            if (running) return progress(RepairState.REPAIRING, "Repairing…", "The authorized recovery is running; verification decides the outcome.", steps, modified, link);
            if ("passed".equals(incident.verificationStatus)) {
                return progress(RepairState.RESOLVED, "Repair completed · verification passed", incident.verificationDetail, steps, modified, link);
            }
            return progress(RepairState.VERIFICATION_FAILED, "Repair attempted · verification failed", incident.verificationDetail, steps, modified, link);
        }
        Assessment assessment = assess(incident);
        List<RepairStepDto> steps = new ArrayList<>(List.of(
                new RepairStepDto("Policy check", assessment.allowed() ? "passed" : "failed", assessment.allowed() ? "Authorized by the recovery policy." : assessment.blockedReason()),
                new RepairStepDto("Recovery action executed", "pending", null),
                new RepairStepDto("Health verification", "pending", null)));
        if (assessment.allowed()) return progress(RepairState.REPAIRABLE, "Repairable", "An allowlisted recovery is authorized and will be verified by health checks.", steps, null, link);
        boolean manual = assessment.blockedReason() != null && (assessment.blockedReason().startsWith("No safe automated recovery")
                || assessment.blockedReason().startsWith("The incident has no deterministic diagnosis"));
        return progress(manual ? RepairState.MANUAL_ACTION_REQUIRED : RepairState.BLOCKED, manual ? "Manual action required" : "Repair blocked",
                assessment.blockedReason(), steps, null, link);
    }

    private static Optional<EvidenceItemDto> last(List<EvidenceItemDto> evidence, java.util.function.Predicate<EvidenceItemDto> match) {
        EvidenceItemDto found = null;
        for (EvidenceItemDto item : evidence) if (match.test(item)) found = item;
        return Optional.ofNullable(found);
    }

    private static RepairProgressDto progress(RepairState state, String headline, String detail, List<RepairStepDto> steps, String modified, String link) {
        return new RepairProgressDto(ID, "RUNTIME_RECOVERY", state, headline, detail, List.copyOf(steps), modified, link);
    }

    public Optional<HypothesisDto> topHypothesis(IncidentEntity incident) {
        if (incident.diagnosisJson == null || incident.diagnosisJson.isBlank()) return Optional.empty();
        try {
            DiagnosisDto diagnosis = mapper.readValue(incident.diagnosisJson, DiagnosisDto.class);
            return diagnosis.engine() == null || diagnosis.engine().hypotheses().isEmpty() ? Optional.empty() : Optional.of(diagnosis.engine().hypotheses().get(0));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /** Rebuilds the deterministic result the policy engine expects from the stored top hypothesis. */
    static DiagnosticResult result(HypothesisDto top) {
        DiagnosticFinding finding = new DiagnosticFinding(top.patternId(), top.title(), top.category(), top.cause(), top.explanation(), top.confidence(),
                top.supportingEvidence().stream().map(ref -> new EvidenceRef(ref.id(), ref.source(), ref.summary())).toList(),
                top.missingEvidence(), top.contradictoryEvidence(), RecoveryActionType.valueOf(top.recommendedAction()), top.actionTarget(), top.actionText());
        return new DiagnosticResult(List.of(finding), false, null, List.of(), List.of(), Instant.now());
    }
}

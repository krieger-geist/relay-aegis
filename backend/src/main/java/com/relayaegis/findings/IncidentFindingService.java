package com.relayaegis.findings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.dto.ApiDtos.DiagnosisDto;
import com.relayaegis.dto.ApiDtos.HypothesisDto;
import com.relayaegis.dto.FindingDtos.Actionability;
import com.relayaegis.dto.FindingDtos.CanonicalFindingDto;
import com.relayaegis.dto.FindingDtos.Certainty;
import com.relayaegis.dto.FindingDtos.EvidenceStatementDto;
import com.relayaegis.dto.FindingDtos.RepairActionDto;
import com.relayaegis.dto.FindingDtos.Sufficiency;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.repair.RuntimeRecoveryRepairStrategy;
import com.relayaegis.repository.ApplicationRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Maps an incident's stored deterministic diagnosis into the canonical model. Repairability comes from the existing
 * {@link RuntimeRecoveryRepairStrategy} (the existing policy engine in operator-approval mode, as "Run recovery" uses); verification state comes
 * from the incident's own verification record. Nothing is executed here.
 */
@Service
public class IncidentFindingService {
    private final ApplicationRepository applications;
    private final RuntimeRecoveryRepairStrategy runtime;
    private final ObjectMapper mapper;

    public IncidentFindingService(ApplicationRepository applications, RuntimeRecoveryRepairStrategy runtime, ObjectMapper mapper) {
        this.applications = applications;
        this.runtime = runtime;
        this.mapper = mapper;
    }

    public List<CanonicalFindingDto> findings(IncidentEntity incident) {
        DiagnosisDto diagnosis = stored(incident);
        ApplicationEntity app = applications.findById(incident.applicationId).orElse(null);
        List<HypothesisDto> hypotheses = diagnosis == null || diagnosis.engine() == null ? List.of() : diagnosis.engine().hypotheses();
        List<CanonicalFindingDto> out = new ArrayList<>();
        if (hypotheses.isEmpty()) {
            out.add(unclassified(incident));
        } else {
            RepairActionDto repair = repair(incident, app, hypotheses.get(0));
            for (int i = 0; i < hypotheses.size(); i++) out.add(map(incident, hypotheses.get(i), i == 0 ? repair : none(), i == 0 ? diagnosis : null));
        }
        return CanonicalFindings.rank(out);
    }

    private CanonicalFindingDto map(IncidentEntity incident, HypothesisDto h, RepairActionDto repair, DiagnosisDto diagnosis) {
        List<EvidenceStatementDto> statements = new ArrayList<>();
        h.supportingEvidence().forEach(ref -> statements.add(new EvidenceStatementDto(Certainty.OBSERVED, ref.summary(), ref.source())));
        statements.add(new EvidenceStatementDto(Certainty.INFERRED, h.cause(), "deterministic pattern " + h.patternId()));
        h.contradictoryEvidence().forEach(text -> statements.add(new EvidenceStatementDto(Certainty.INFERRED, "Contradicting evidence: " + text, "correlation")));
        h.missingEvidence().forEach(text -> statements.add(new EvidenceStatementDto(Certainty.UNKNOWN, "Not observed: " + text, "deterministic pattern " + h.patternId())));
        if (diagnosis != null && "gemini".equals(diagnosis.provider())) {
            statements.add(new EvidenceStatementDto(Certainty.HYPOTHESIS, diagnosis.probableCause(), "AI (" + diagnosis.model() + "), not verified"));
        }
        boolean strong = h.confidence() >= 0.75 && h.missingEvidence().isEmpty() && h.contradictoryEvidence().isEmpty();
        Sufficiency sufficiency = h.supportingEvidence().isEmpty() ? Sufficiency.INSUFFICIENT : strong ? Sufficiency.SUFFICIENT : Sufficiency.PARTIAL;
        String reason = switch (sufficiency) {
            case SUFFICIENT -> "Supported by " + h.supportingEvidence().size() + " observation(s) with no missing or contradicting evidence.";
            case PARTIAL -> h.missingEvidence().isEmpty() && h.contradictoryEvidence().isEmpty()
                    ? "Supported, but the deterministic confidence is below the 0.75 threshold."
                    : "Supported, but some evidence is missing or contradicts this hypothesis.";
            case INSUFFICIENT -> "No supporting observation was recorded.";
        };
        boolean auto = RepairStates.automated(repair);
        Actionability actionability = auto ? Actionability.AUTO_REPAIRABLE
                : "application-healthy".equals(h.patternId()) ? Actionability.INFORMATIONAL : Actionability.ACTION_REQUIRED;
        String severity = "application-healthy".equals(h.patternId()) ? "info" : incident.severity == null ? "high"
                : switch (incident.severity) { case "Sev-1" -> "critical"; case "Sev-2" -> "high"; default -> "medium"; };
        return new CanonicalFindingDto(incident.id + ":" + h.patternId(), CanonicalFindings.SOURCE_INCIDENT, h.title(), h.explanation(),
                severity, h.confidenceLabel().toLowerCase(Locale.ROOT), incident.component, List.copyOf(statements), sufficiency, reason,
                CanonicalFindings.impact(severity), actionability,
                auto ? "The existing recovery policy authorizes " + h.recommendedAction() + "; the result is verified by health checks."
                        : repair.stateDetail() != null ? repair.stateDetail() : h.actionText(),
                repair, verification(incident), "deterministic", h.supportingEvidence().stream().map(ref -> ref.id()).toList(),
                h.missingEvidence(), 0, "");
    }

    private CanonicalFindingDto unclassified(IncidentEntity incident) {
        return new CanonicalFindingDto(incident.id + ":pending", CanonicalFindings.SOURCE_INCIDENT, incident.title, incident.summary, "high", "low",
                incident.component, List.of(new EvidenceStatementDto(Certainty.OBSERVED, incident.summary, "monitor"),
                        new EvidenceStatementDto(Certainty.UNKNOWN, "No deterministic diagnosis has been recorded yet.", "diagnosis")),
                Sufficiency.INSUFFICIENT, "The incident has not been diagnosed yet.", CanonicalFindings.impact("high"), Actionability.ACTION_REQUIRED,
                "Diagnosis pending.", none(), verification(incident), "deterministic", List.of(), List.of("Deterministic diagnosis of the incident"), 0, "");
    }

    /** The unified state from the same strategy Auto-Repair uses; read only (no attempt is recorded). */
    RepairActionDto repair(IncidentEntity incident, ApplicationEntity app, HypothesisDto top) {
        RecoveryActionType action;
        try { action = RecoveryActionType.valueOf(top.recommendedAction()); }
        catch (Exception ex) { return none(); }
        if (!action.mutates() || app == null) return none();
        return RepairStates.action(runtime.verify(incident), incident.id);
    }

    static String verification(IncidentEntity incident) {
        return incident.verificationStatus == null ? "not_run" : incident.verificationStatus;
    }

    private static RepairActionDto none() { return new RepairActionDto("NONE", "View details", null, "NONE", null, null); }

    private DiagnosisDto stored(IncidentEntity incident) {
        if (incident.diagnosisJson == null || incident.diagnosisJson.isBlank()) return null;
        try { return mapper.readValue(incident.diagnosisJson, DiagnosisDto.class); }
        catch (Exception ex) { return null; }
    }
}

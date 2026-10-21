package com.relayaegis.findings;

import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.dto.FindingDtos.Actionability;
import com.relayaegis.dto.FindingDtos.CanonicalFindingDto;
import com.relayaegis.dto.FindingDtos.Certainty;
import com.relayaegis.dto.FindingDtos.EvidenceStatementDto;
import com.relayaegis.dto.FindingDtos.LayerStatusDto;
import com.relayaegis.dto.FindingDtos.RepairActionDto;
import com.relayaegis.dto.FindingDtos.Sufficiency;
import com.relayaegis.runtime.ContainerSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic cross-source correlation for combined assessments. Each source keeps its provenance. A relationship is
 * stated only as far as the evidence goes: co-occurrence is an inference, and a code finding is never reported as the
 * cause of a live failure — at most as a possibly related, unverified hypothesis.
 */
public final class EvidenceCorrelation {
    static final Set<String> CODE_CATEGORIES = Set.of("reliability", "correctness", "error-handling", "database-safety");

    private EvidenceCorrelation() {}

    public record Result(List<CanonicalFindingDto> correlations, List<String> independent) {}

    /**
     * @param failedLayer the first failed live URL layer, if any
     * @param runtime     the registered container's current state, or null when it is not linked or not inspectable
     * @param runtimeRepair the existing runtime recovery action for the open incident, or null
     */
    public static Result correlate(Optional<LayerStatusDto> failedLayer, CanonicalFindingDto urlPrimary, ContainerSnapshot runtime,
                                   RepairActionDto runtimeRepair, List<AssessmentFindingDto> githubFindings, boolean urlChecked) {
        List<CanonicalFindingDto> out = new ArrayList<>();
        List<String> independent = new ArrayList<>();
        List<Lead> related = githubFindings.stream().filter(f -> f.category() != null && CODE_CATEGORIES.contains(f.category()))
                .limit(3).map(f -> new Lead(f.id(), f.title(), f.file() + (f.line() == null ? "" : ":" + f.line()))).toList();

        if (urlChecked && failedLayer.isPresent()) {
            String urlObservation = urlPrimary.title() + " (" + failedLayer.get().layer() + ").";
            if (runtime != null && runtime.exists() && !runtime.running()) {
                List<EvidenceStatementDto> s = new ArrayList<>();
                s.add(new EvidenceStatementDto(Certainty.OBSERVED, urlObservation, "live URL check"));
                s.add(new EvidenceStatementDto(Certainty.OBSERVED, "Container " + runtime.name() + ": " + runtime.describe(), "runtime inspection"));
                s.add(new EvidenceStatementDto(Certainty.INFERRED, "The container registered for this URL is not running, which accounts for the endpoint being unreachable.", "correlation"));
                boolean auto = RepairStates.automated(runtimeRepair);
                out.add(finding("CORR-RUNTIME-STOPPED", "Endpoint failure coincides with a stopped container", "high", "high", s,
                        Sufficiency.SUFFICIENT, "Both the endpoint failure and the stopped container were observed; the link comes from the application registration.",
                        auto ? Actionability.AUTO_REPAIRABLE : Actionability.ACTION_REQUIRED,
                        auto ? "The existing recovery policy authorizes recovering the container; the result is verified." : "Recover the container through the incident's recovery workflow.",
                        runtimeRepair, List.of(urlPrimary.id(), "RUNTIME:" + runtime.name()), List.of()));
            } else if (runtime != null && runtime.exists() && runtime.healthy() && "HTTP".equals(failedLayer.get().layer())) {
                List<EvidenceStatementDto> s = new ArrayList<>();
                s.add(new EvidenceStatementDto(Certainty.OBSERVED, urlObservation, "live URL check"));
                s.add(new EvidenceStatementDto(Certainty.OBSERVED, "Container " + runtime.name() + " is running and healthy (" + runtime.describe() + ").", "runtime inspection"));
                s.add(new EvidenceStatementDto(Certainty.INFERRED, "The process is up while its HTTP endpoint fails, which points to an application-level problem rather than the container lifecycle.", "correlation"));
                related.forEach(f -> s.add(new EvidenceStatementDto(Certainty.HYPOTHESIS, "Possibly related source finding (not established): " + f.title() + " at " + f.component() + ".", "repository scan")));
                s.add(new EvidenceStatementDto(Certainty.UNKNOWN, "Which code path or dependency fails is not established by the available evidence.", "correlation"));
                List<String> refs = new ArrayList<>(List.of(urlPrimary.id(), "RUNTIME:" + runtime.name()));
                related.forEach(f -> refs.add(f.id()));
                out.add(finding("CORR-APPLICATION-LEVEL", "Possible application-level failure", "high", "medium", s,
                        Sufficiency.PARTIAL, "Supports an application-level failure; insufficient to identify the failing code or dependency.",
                        Actionability.ACTION_REQUIRED, "Inspect application logs; no safe automated action applies to an application-level failure.",
                        null, refs, List.of("Application runtime logs for the failing requests", "Repository/source evidence for the failing route")));
            } else if (!related.isEmpty()) {
                List<EvidenceStatementDto> s = new ArrayList<>();
                s.add(new EvidenceStatementDto(Certainty.OBSERVED, urlObservation, "live URL check"));
                related.forEach(f -> s.add(new EvidenceStatementDto(Certainty.HYPOTHESIS, "Possibly related source finding (not established): " + f.title() + " at " + f.component() + ".", "repository scan")));
                s.add(new EvidenceStatementDto(Certainty.UNKNOWN, runtime == null ? "Runtime state is not available, so the failure cannot be localized further." : "The runtime state does not establish a link.", "correlation"));
                List<String> refs = new ArrayList<>(List.of(urlPrimary.id()));
                related.forEach(f -> refs.add(f.id()));
                out.add(finding("CORR-UNVERIFIED", "Unverified relationship between the live failure and source findings", "medium", "low", s,
                        Sufficiency.INSUFFICIENT, "Co-occurrence only; no evidence links these source findings to the live failure.",
                        Actionability.INFORMATIONAL, "Treat as a lead to investigate, not as a cause.", null, refs,
                        List.of("Runtime/container state of the application", "Application runtime logs")));
            }
        }
        if (urlChecked && failedLayer.isEmpty() && !githubFindings.isEmpty()) {
            independent.add("The live endpoint is reachable, so the repository findings are independent of its current availability.");
        }
        if (!urlChecked && !githubFindings.isEmpty()) independent.add("No live evidence was collected; repository findings stand on their own.");
        if (runtime == null) independent.add("Runtime state was not available (no registered, inspectable container for this URL).");
        return new Result(List.copyOf(out), List.copyOf(independent));
    }

    /** A repository finding in a reliability/correctness category: a lead, never a cause. */
    record Lead(String id, String title, String component) {}

    private static CanonicalFindingDto finding(String id, String title, String severity, String confidence, List<EvidenceStatementDto> statements,
                                               Sufficiency sufficiency, String reason, Actionability actionability, String why,
                                               RepairActionDto repair, List<String> related, List<String> next) {
        return new CanonicalFindingDto(id, CanonicalFindings.SOURCE_CORRELATED, title, title, severity, confidence, "Cross-source",
                List.copyOf(statements), sufficiency, reason, CanonicalFindings.impact(severity), actionability, why,
                repair == null ? new RepairActionDto("NONE", "View details", null, "NONE", null, null) : repair,
                repair == null ? "not_run" : repair.state(), "correlated", List.copyOf(related), List.copyOf(next), 0, "");
    }
}

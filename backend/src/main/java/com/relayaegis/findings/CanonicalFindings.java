package com.relayaegis.findings;

import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.dto.FindingDtos.Actionability;
import com.relayaegis.dto.FindingDtos.CanonicalFindingDto;
import com.relayaegis.dto.FindingDtos.Certainty;
import com.relayaegis.dto.FindingDtos.EvidenceStatementDto;
import com.relayaegis.dto.FindingDtos.RepairActionDto;
import com.relayaegis.dto.FindingDtos.Sufficiency;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps source-specific findings into the canonical model and ranks them deterministically. Every conclusion is
 * tagged with its certainty: what a check observed, what is inferred from it, and what cannot be determined.
 */
public final class CanonicalFindings {
    public static final String SOURCE_LIVE_URL = "LIVE_URL";
    public static final String SOURCE_GITHUB = "GITHUB";
    public static final String SOURCE_RUNTIME = "RUNTIME";
    public static final String SOURCE_INCIDENT = "INCIDENT";
    public static final String SOURCE_CORRELATED = "CORRELATED";

    /** URL categories whose internal cause is never observable from outside. */
    private static final Set<String> ROOT_CAUSE_HIDDEN = Set.of("runtime-response", "runtime-connectivity", "network", "performance");

    private static final Map<String, String> COMPONENT = Map.of(
            "network", "Network path (DNS/TCP)", "transport", "TLS / transport", "runtime-response", "HTTP endpoint",
            "runtime-connectivity", "HTTP endpoint", "security-header", "HTTP response headers", "performance", "HTTP endpoint",
            "routing", "HTTP routing");

    private CanonicalFindings() {}

    /** A live URL or repository finding. {@code repair} is the existing engine's action for it, or null. */
    public static CanonicalFindingDto fromAssessment(AssessmentFindingDto f, RepairActionDto repair) {
        boolean repository = f.file() != null && !f.file().isBlank();
        String source = repository ? SOURCE_GITHUB : SOURCE_LIVE_URL;
        String category = f.category() == null ? "" : f.category();
        boolean observedExternally = "observed_external".equals(f.validationStatus());
        List<EvidenceStatementDto> statements = new ArrayList<>();
        Sufficiency sufficiency;
        String sufficiencyReason;
        List<String> next = new ArrayList<>();
        String component;
        String verification;

        if (repository) {
            component = f.file() + (f.line() == null ? "" : ":" + f.line());
            statements.add(new EvidenceStatementDto(Certainty.OBSERVED, "Static analysis matched at " + component + ": " + f.evidence(), "repository scan"));
            statements.add(new EvidenceStatementDto(Certainty.INFERRED, f.explanation(), "repository scan rule"));
            statements.add(new EvidenceStatementDto(Certainty.UNKNOWN, "Whether this is a real defect in the running application is not established; it is a heuristic candidate.", "repository scan"));
            sufficiency = Sufficiency.PARTIAL;
            sufficiencyReason = "A source pattern was matched; no build, test or runtime evidence confirms the defect.";
            next.add("Repository owner review of " + component);
            if (repair == null) next.add("Runtime logs showing the affected code path");
            verification = "UNVERIFIED_CANDIDATE";
        } else {
            component = COMPONENT.getOrDefault(category, "Live application");
            statements.add(new EvidenceStatementDto(observedExternally ? Certainty.OBSERVED : Certainty.INFERRED, f.evidence(), "live URL check"));
            statements.add(new EvidenceStatementDto(Certainty.INFERRED, f.explanation(), "live URL check"));
            if (ROOT_CAUSE_HIDDEN.contains(category)) {
                statements.add(new EvidenceStatementDto(Certainty.UNKNOWN, "The internal root cause cannot be observed from outside the application.", "live URL check"));
                sufficiency = Sufficiency.PARTIAL;
                sufficiencyReason = "Sufficient to establish the externally observed behaviour; insufficient to establish the internal root cause.";
                next.add("Runtime logs for the request time window");
                next.add("Container / process state of the application");
                next.add("Repeated samples over time");
            } else {
                sufficiency = observedExternally ? Sufficiency.SUFFICIENT : Sufficiency.PARTIAL;
                sufficiencyReason = observedExternally ? "Directly observed in the HTTP response." : "Inferred from a single response; not independently confirmed.";
            }
            verification = observedExternally ? "OBSERVED_LIVE" : "UNVERIFIED_CANDIDATE";
        }

        Actionability actionability;
        String why;
        if (RepairStates.automated(repair)) {
            actionability = Actionability.AUTO_REPAIRABLE;
            why = "GITHUB_PATCH".equals(repair.type())
                    ? "A deterministic patch strategy exists; it is validated in the sandbox and opened as a reviewed pull request (never merged automatically)."
                    : "An allowlisted recovery action is authorized by policy and is verified afterwards.";
        } else if (Set.of("critical", "high", "medium").contains(lower(f.severity()))) {
            actionability = Actionability.ACTION_REQUIRED;
            why = "No safe automated action exists; follow the recommendation.";
        } else {
            actionability = Actionability.INFORMATIONAL;
            why = "Low-impact observation; no action is required to keep the application working.";
        }
        return new CanonicalFindingDto(f.id(), source, f.title(), f.explanation(), lower(f.severity()), lower(f.confidence()), component,
                List.copyOf(statements), sufficiency, sufficiencyReason, impact(lower(f.severity())), actionability, why,
                repair == null ? new RepairActionDto("NONE", "View details", null, "NONE", f.recommendation(), null) : repair,
                repair != null && repair.state() != null && !"REPAIRABLE".equals(repair.state()) ? repair.state() : verification,
                f.validationStatus(), List.of(), List.copyOf(next), 0, "");
    }

    static String impact(String severity) {
        return switch (severity) {
            case "critical", "high" -> "Likely user-facing outage or significant security exposure.";
            case "medium" -> "Degrades reliability or security; should be addressed.";
            case "low" -> "Hardening or quality improvement.";
            default -> "Informational.";
        };
    }

    static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }

    // ---- Ranking ------------------------------------------------------------------------------------------------

    private static int group(Actionability a) { return a == Actionability.AUTO_REPAIRABLE ? 0 : a == Actionability.ACTION_REQUIRED ? 1 : 2; }
    private static int severity(String s) { return switch (lower(s)) { case "critical" -> 0; case "high" -> 1; case "medium" -> 2; case "low" -> 3; default -> 4; }; }
    private static int evidence(CanonicalFindingDto f) {
        boolean observed = f.statements().stream().anyMatch(s -> s.certainty() == Certainty.OBSERVED);
        boolean ai = f.statements().stream().allMatch(s -> s.certainty() == Certainty.HYPOTHESIS);
        return observed ? 0 : ai ? 2 : 1;
    }
    private static int confidence(String c) { return switch (lower(c)) { case "high" -> 0; case "medium" -> 1; case "low" -> 2; default -> 3; }; }
    private static int sufficiency(Sufficiency s) { return s == Sufficiency.SUFFICIENT ? 0 : s == Sufficiency.PARTIAL ? 1 : 2; }
    private static int verifiable(CanonicalFindingDto f) { return f.repair() != null && !"NONE".equals(f.repair().type()) ? 0 : 1; }

    /** Actionability group, then impact (severity), evidence strength, confidence, sufficiency, verifiability, title. */
    public static final Comparator<CanonicalFindingDto> ORDER = Comparator
            .comparingInt((CanonicalFindingDto f) -> group(f.actionability()))
            .thenComparingInt(f -> severity(f.severity()))
            .thenComparingInt(CanonicalFindings::evidence)
            .thenComparingInt(f -> confidence(f.confidence()))
            .thenComparingInt(f -> sufficiency(f.evidenceSufficiency()))
            .thenComparingInt(CanonicalFindings::verifiable)
            .thenComparing(CanonicalFindingDto::title, Comparator.nullsLast(String::compareTo))
            .thenComparing(CanonicalFindingDto::id, Comparator.nullsLast(String::compareTo));

    public static List<CanonicalFindingDto> rank(List<CanonicalFindingDto> findings) {
        List<CanonicalFindingDto> sorted = new ArrayList<>(findings);
        sorted.sort(ORDER);
        List<CanonicalFindingDto> ranked = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            CanonicalFindingDto f = sorted.get(i);
            String reason = groupLabel(f.actionability()) + " · severity " + (f.severity().isBlank() ? "unknown" : f.severity())
                    + " · evidence " + (evidence(f) == 0 ? "observed" : evidence(f) == 2 ? "AI hypothesis" : "inferred/candidate")
                    + " · confidence " + (f.confidence().isBlank() ? "unknown" : f.confidence())
                    + " · sufficiency " + f.evidenceSufficiency().name().toLowerCase(Locale.ROOT)
                    + (verifiable(f) == 0 ? " · repair verifiable" : "");
            ranked.add(withRank(f, i + 1, reason));
        }
        return List.copyOf(ranked);
    }

    static String groupLabel(Actionability a) {
        return switch (a) { case AUTO_REPAIRABLE -> "auto-repairable"; case ACTION_REQUIRED -> "action required"; case INFORMATIONAL -> "informational"; };
    }

    public static CanonicalFindingDto withRank(CanonicalFindingDto f, int rank, String reason) {
        return new CanonicalFindingDto(f.id(), f.source(), f.title(), f.description(), f.severity(), f.confidence(), f.component(), f.statements(),
                f.evidenceSufficiency(), f.sufficiencyReason(), f.impact(), f.actionability(), f.actionabilityReason(), f.repair(),
                f.verificationState(), f.provenance(), f.relatedEvidence(), f.nextBestEvidence(), rank, reason);
    }
}

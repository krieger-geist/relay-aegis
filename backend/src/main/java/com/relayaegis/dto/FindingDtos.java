package com.relayaegis.dto;

import java.util.List;

/**
 * The canonical finding / diagnosis representation shared by live URL, GitHub, runtime, incident and combined
 * assessments. Existing source-specific DTOs stay unchanged; these are derived from them so existing APIs keep working.
 */
public final class FindingDtos {
    private FindingDtos() {}

    /** Certainty of one statement. Inference is never presented as observation; AI output is always a HYPOTHESIS. */
    public enum Certainty { OBSERVED, INFERRED, HYPOTHESIS, UNKNOWN }

    public enum Sufficiency { SUFFICIENT, PARTIAL, INSUFFICIENT }

    public enum Actionability { AUTO_REPAIRABLE, ACTION_REQUIRED, INFORMATIONAL }

    /** One statement about a finding, with its certainty and where it came from. */
    public record EvidenceStatementDto(Certainty certainty, String text, String source) {}

    /**
     * How the existing engines can act on a finding. {@code type}: GITHUB_PATCH (proposal → sandbox → reviewed PR),
     * RUNTIME_RECOVERY (policy-authorized recovery + verification) or NONE. {@code state}: NOT_STARTED, REPAIRING,
     * AWAITING_APPROVAL, PR_OPEN, RESOLVED, VERIFICATION_FAILED, BLOCKED.
     */
    public record RepairActionDto(String type, String label, String targetId, String state, String stateDetail, String link) {}

    public record CanonicalFindingDto(
            String id,
            String source,
            String title,
            String description,
            String severity,
            String confidence,
            String component,
            List<EvidenceStatementDto> statements,
            Sufficiency evidenceSufficiency,
            String sufficiencyReason,
            String impact,
            Actionability actionability,
            String actionabilityReason,
            RepairActionDto repair,
            String verificationState,
            String provenance,
            List<String> relatedEvidence,
            List<String> nextBestEvidence,
            int rank,
            String rankReason) {}

    /**
     * Unified repair states shared by every strategy. RESOLVED is only ever reported after a passing verification;
     * an executed action without verification is at most REPAIR_ATTEMPTED.
     */
    public enum RepairState {
        REPAIRABLE, REPAIRING, VALIDATION_FAILED, BLOCKED, AWAITING_APPROVAL, REPAIR_ATTEMPTED, VERIFICATION_FAILED, RESOLVED, MANUAL_ACTION_REQUIRED
    }

    /** One step of a repair. status: passed, failed, running, pending or skipped. */
    public record RepairStepDto(String name, String status, String detail) {}

    /**
     * In-context progress of one repair, derived from the records the existing engines write. {@code targetModified}
     * says what, if anything, was changed outside RELAY (null = nothing).
     */
    public record RepairProgressDto(String strategy, String type, RepairState state, String headline, String detail,
                                    List<RepairStepDto> steps, String targetModified, String detailsLink) {}

    /** status: PASSED, FAILED, NOT_TESTED or UNKNOWN. Never FAILED just because the layer was not reached. */
    public record LayerStatusDto(String layer, String status, String detail) {}

    /**
     * The diagnosis of one assessment. {@code samplingPattern}: STABLE, PERSISTENT, INTERMITTENT, RECOVERED,
     * SINGLE_OBSERVATION or NOT_SAMPLED.
     */
    public record AssessmentDiagnosisDto(
            String overallStatus,
            String whatHappened,
            CanonicalFindingDto primary,
            List<LayerStatusDto> layers,
            String samplingPattern,
            String samplingDetail,
            List<CanonicalFindingDto> findings,
            List<CanonicalFindingDto> correlations,
            List<String> independentEvidence,
            List<String> sources,
            String aiHypothesis) {}
}

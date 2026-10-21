package com.relayaegis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ApiDtos {
    private ApiDtos() {}

    public record HealthDto(String status, String service) {}
    public record IntegrationConnections(String url, String docker, String github) {}
    public record ApplicationDto(String id, String name, String url, String framework, String environment,
                                 String status, Long responseMs, Double errorRate, Instant lastChecked,
                                 String deploymentSha, String githubRepository, String dockerContainer, IntegrationConnections integrations,
                                 String description, String provenance) {}
    public record RegisterApplicationRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 1000) String url,
            @Size(max = 160) String framework,
            String environment,
            @Size(max = 1000) String description,
            @Size(max = 300) String githubRepository,
            @Size(max = 200) String dockerContainer) {}
    public record HealthCheckResult(ApplicationDto application, boolean healthy, Long responseMs,
                                    String message, String provenance, String outcome, Integer statusCode) {}
    public record HealthSampleDto(String id, String applicationId, Instant checkedAt, boolean healthy,
                                  Long responseMs, String errorMessage, String provenance,
                                  String outcome, Integer statusCode) {}
    public record EvidenceItemDto(String id, String type, String title, String detail, String source,
                                  Instant timestamp, boolean observed) {}
    public record RecoveryStepDto(String id, String label, String detail, Instant timestamp,
                                  String state, String actor) {}
    public record VerificationDto(String status, String detail) {}
    public record IncidentDto(String id, String title, String applicationId, String applicationName,
                              String severity, String state, String status, Instant detectedAt, Long detectionDurationMs,
                              String detectedLabel, String component, String summary, String likelyCause,
                              String confidence, List<String> supportingEvidence, List<EvidenceItemDto> evidence,
                              VerificationDto verification, Long recoveryDurationMs,
                              List<RecoveryStepDto> timeline, String provenance, DiagnosisDto diagnosis) {}
    public record RecoveryEventDto(String id, String incidentId, String applicationName, String event,
                                   String outcome, Instant timestamp, Long detectionDurationMs,
                                   Long recoveryDurationMs, String detail, String provenance) {}
    public record RecoveryPolicyDto(String id, String action, String description, String mode,
                                    String risk, String scope, String provenance) {}
    public record UpdatePolicyRequest(String mode, String confirmation) {}
    public record IntegrationDto(String id, String name, String description, String state,
                                 List<String> capabilities, Instant lastVerified, String provenance) {}
    public record IntegrationVerificationDto(IntegrationDto integration, String message) {}
    public record RepairCheckDto(String name, String status, String detail) {}
    public record RepairDiffDto(String file, List<String> additions, List<String> removals) {}
    public record RepairJobDto(String id, String incidentId, String applicationName, String repository,
                               String commitSha, String state, String explanation, int filesChanged,
                               List<RepairDiffDto> diff, List<RepairCheckDto> checks,
                               VerificationDto verification, String provenance,
                               String assessmentId, String findingId, String findingTitle, String filePath,
                               String strategy, String patch, Instant createdAt, Instant updatedAt) {}
    /** One GitHub apply/PR attempt for a patch proposal. Never contains credentials. */
    public record GitHubRemediationDto(String id, String patchId, String status, String repository, String baseBranch, String branch,
                                       String commitSha, Integer prNumber, String prUrl, String validationStatus,
                                       boolean preexistingFailuresAcknowledged, String failedStage, String errorCode, String errorMessage,
                                       String verificationStatus, String verificationDetail, Instant createdAt, Instant updatedAt) {}
    /** What applying would do, whether it is allowed for the current user, and the latest attempt (if any). */
    public record GitHubRemediationViewDto(String patchId, boolean available, List<String> blockedReasons, boolean configured,
                                           boolean authorized, String validation, boolean requiresAcknowledgement,
                                           String repository, String findingId, String findingTitle, String plannedBranch,
                                           String targetBranch, String action, GitHubRemediationDto latest, MergeStatusDto merge) {}
    /** Read from GitHub: NONE (no PR), OPEN, CLOSED (without merge), MERGED (by a human) or UNKNOWN. RELAY never merges. */
    public record MergeStatusDto(String state, boolean verifiedOnDefaultBranch, String detail) {}
    public record ApplyRemediationRequest(boolean acknowledgePreexistingTestFailures) {}
    public record ProposePatchRequest(@NotBlank @Size(max = 40) String assessmentId, @NotBlank @Size(max = 40) String findingId) {}
    public record DashboardSummaryDto(int connectedApplications, int healthyServices, int activeIncidents,
                                      int verifiedRecoveries30d, List<ResponseSampleDto> responseTimeSeries,
                                      String provenance) {}
    public record ResponseSampleDto(String time, double p95, double errorRate) {}
    /**
     * A diagnostic hypothesis. provider is "gemini" (AI) or "deterministic-fallback" (rules); fallbackReason says
     * why AI output was not used. Recommended actions are proposals only and always require operator approval.
     */
    /**
     * How close an open incident is to closing by observed recovery: consecutive healthy checks recorded after
     * detection versus the configured recovery threshold. Read-only; it never changes the incident.
     */
    public record ResolutionProgressDto(String incidentId, boolean open, int consecutiveHealthyChecks,
                                        int requiredHealthyChecks, String latestOutcome, Instant latestCheckAt) {}
    public record DiagnosisDto(String incidentId, String probableCause, String explanation,
                               String confidence, String confidenceReason, List<String> supportingEvidence,
                               List<String> missingEvidence, List<RecommendedActionDto> recommendedActions,
                               String provider, String model, String fallbackReason, List<String> validationNotes,
                               Instant generatedAt, String provenance,
                               /** "automatic" (incident pipeline) or "manual" (operator re-run). */
                               String trigger,
                               /** Always true: a diagnosis never authorizes an action; recovery stays policy-controlled. */
                               boolean humanApprovalRequired, String approvalReason,
                               /** Why AI output was not used (GeminiClient failure code), or null when AI answered. */
                               String aiFailureCode,
                               /** Background AI retries already made for this incident, and when the next one is due (null = none). */
                               int aiRetryAttempts, Instant nextAiRetryAt,
                               /** Append-only audit of every diagnosis attempt for this incident, oldest first. */
                               List<AiAttemptDto> aiAttempts,
                               /** Deterministic engine output; authoritative for recovery decisions. Kept separate from AI fields. */
                               DeterministicDiagnosisDto engine,
                               /** The deterministic hypothesis the AI selected, "none" for a novel (unverified) cause, or null. */
                               String aiSelectedHypothesis) {}
    /**
     * Ranked deterministic hypotheses. aiEscalation: "not_required" (deterministic evidence was sufficient) or
     * "required" (weak, ambiguous, contradicted or no match; AI reasoning was requested).
     */
    public record DeterministicDiagnosisDto(String topPatternId, Double topConfidence, String aiEscalation, String escalationReason,
                                            List<HypothesisDto> hypotheses, List<String> evidenceSources,
                                            List<String> unavailableEvidence, Instant generatedAt) {}
    public record HypothesisDto(String patternId, String title, String category, String cause, String explanation,
                                double confidence, String confidenceLabel, List<EvidenceRefDto> supportingEvidence,
                                List<String> missingEvidence, List<String> contradictoryEvidence,
                                String recommendedAction, String actionTarget, String actionText) {}
    public record EvidenceRefDto(String id, String source, String summary) {}
    /**
     * One diagnosis attempt. trigger: "automatic" or "manual"; backgroundRetry: 0 for the first/manual attempt,
     * n for the n-th background AI retry; outcome: "ai" or "rule-based".
     */
    public record AiAttemptDto(Instant at, String trigger, int backgroundRetry, String outcome, String aiFailureCode,
                               String reason, String probableCause) {}
    public record RecommendedActionDto(String action, String rationale, String risk) {}
    /** fieldErrors (field -> message) is present only for request validation failures. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record ErrorDto(String message, int status, Instant timestamp, java.util.Map<String, String> fieldErrors) {
        public ErrorDto(String message, int status, Instant timestamp) { this(message, status, timestamp, null); }
    }
}

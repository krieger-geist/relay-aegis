package com.relayaegis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AssessmentDtos {
    private AssessmentDtos() {}

    public record CreateAssessmentRequest(
            @NotBlank @Size(max = 24) String kind,
            @Size(max = 1000) String repositoryUrl,
            @Size(max = 2000) String applicationUrl,
            boolean authorized,
            boolean aiTriage) {}

    public record AssessmentFindingDto(
            String id,
            String category,
            String title,
            String severity,
            String confidence,
            String file,
            Integer line,
            String evidence,
            String explanation,
            String recommendation,
            String validationStatus) {}

    public record AssessmentCheckDto(String name, String status, String detail) {}

    public record AssessmentTimelineStepDto(
            String id,
            String stage,
            String label,
            String detail,
            String state,
            Instant timestamp) {}

    public record AssessmentDto(
            String id,
            String kind,
            String repositoryUrl,
            String applicationUrl,
            String status,
            String currentStage,
            List<AssessmentTimelineStepDto> timeline,
            String summary,
            String repositoryCommit,
            String aiTriageStatus,
            String aiSummary,
            List<AssessmentFindingDto> findings,
            List<AssessmentCheckDto> checks,
            String errorMessage,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            String provenance,
            /** Findings for which a deterministic patch proposal can be generated. */
            List<String> patchableFindingIds,
            /** Canonical diagnosis; present on the detail endpoint, null in lists. */
            FindingDtos.AssessmentDiagnosisDto diagnosis) {}

    public record AssessmentReportDto(String assessmentId, String markdown, String emailSubject, String emailBody) {}
}

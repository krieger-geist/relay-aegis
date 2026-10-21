package com.relayaegis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.assessment.AssessmentTargetPolicy;
import com.relayaegis.assessment.GitHubRepositoryPolicy;
import com.relayaegis.dto.AssessmentDtos.AssessmentCheckDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentReportDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentTimelineStepDto;
import com.relayaegis.dto.AssessmentDtos.CreateAssessmentRequest;
import com.relayaegis.entity.AssessmentEntity;
import com.relayaegis.repository.AssessmentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AssessmentService {
    private final AssessmentRepository repository;
    private final AssessmentExecutionService execution;
    private final GitHubRepositoryPolicy githubPolicy;
    private final AssessmentTargetPolicy targetPolicy;
    private final ObjectMapper mapper;
    private final com.relayaegis.findings.AssessmentDiagnosisService diagnosis;

    public AssessmentService(AssessmentRepository repository, AssessmentExecutionService execution, GitHubRepositoryPolicy githubPolicy,
            AssessmentTargetPolicy targetPolicy, ObjectMapper mapper, com.relayaegis.findings.AssessmentDiagnosisService diagnosis) {
        this.repository = repository; this.execution = execution; this.githubPolicy = githubPolicy; this.targetPolicy = targetPolicy; this.mapper = mapper;
        this.diagnosis = diagnosis;
    }

    public AssessmentDto create(CreateAssessmentRequest request) {
        if (request == null || !request.authorized()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Confirm that you own the target or have permission to assess it before starting.");
        String kind = request.kind().trim().toLowerCase(Locale.ROOT);
        if (!List.of("repository", "url", "combined").contains(kind)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Assessment kind must be repository, url, or combined.");
        String repo = null;
        String appUrl = null;
        if ("repository".equals(kind) || "combined".equals(kind)) repo = githubPolicy.validateAndNormalize(request.repositoryUrl());
        if ("url".equals(kind) || "combined".equals(kind)) appUrl = targetPolicy.validate(request.applicationUrl()).toASCIIString();

        AssessmentEntity entity = new AssessmentEntity();
        entity.setId("ASM-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT));
        entity.setKind(kind);
        entity.setRepositoryUrl(repo == null ? null : repo.replaceFirst("(?i)\\.git$", ""));
        // A pinned commit (.../commit/<sha>) is assessed exactly; otherwise the default branch head is recorded by the scan.
        if (repo != null) githubPolicy.pinnedCommit(request.repositoryUrl()).ifPresent(entity::setRepositoryCommit);
        entity.setApplicationUrl(appUrl);
        entity.setStatus("queued");
        entity.setCurrentStage("queued");
        entity.setSummary("Assessment queued. The engine has not finished running the requested checks.");
        entity.setAiTriageRequested(request.aiTriage());
        entity.setAiTriageStatus(request.aiTriage() ? "queued" : "not_requested");
        Instant createdAt = Instant.now();
        entity.setCreatedAt(createdAt);
        entity.setFindingsJson("[]");
        entity.setChecksJson("[]");
        entity.setProvenance("live");
        try {
            entity.setTimelineJson(mapper.writeValueAsString(List.of(new AssessmentTimelineStepDto(
                    "stg-queued", "queued", "Assessment queued",
                    "The source was accepted and is waiting for an assessment worker.", "current", createdAt))));
        } catch (Exception ex) {
            entity.setTimelineJson("[]");
        }
        AssessmentEntity saved = repository.saveAndFlush(entity);
        try {
            execution.execute(saved.getId());
        } catch (Exception ex) {
            saved.setStatus("failed");
            saved.setCurrentStage("failed");
            saved.setCompletedAt(Instant.now());
            saved.setErrorMessage("Assessment worker is busy. Wait for the active queue to clear, then retry.");
            saved.setTimelineJson("[{\"id\":\"stg-failed\",\"stage\":\"failed\",\"label\":\"Assessment could not start\",\"detail\":\"Assessment worker is busy. Wait for the active queue to clear, then retry.\",\"state\":\"failed\",\"timestamp\":null}]");
            repository.save(saved);
        }
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<AssessmentDto> list() {
        return repository.findTop50ByOrderByCreatedAtDesc().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public AssessmentDto get(String id) { return toDto(find(id), true); }

    @Transactional(readOnly = true)
    public AssessmentReportDto report(String id) {
        AssessmentDto dto = toDto(find(id));
        if ("queued".equals(dto.status()) || "running".equals(dto.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The assessment report is available after the run finishes.");
        }
        String markdown = buildMarkdown(dto);
        String target = dto.repositoryUrl() != null && dto.applicationUrl() != null ? dto.repositoryUrl() + " + " + dto.applicationUrl()
                : dto.repositoryUrl() != null ? dto.repositoryUrl() : dto.applicationUrl();
        String subject = "[RELAY AEGIS] Assessment findings — " + dto.id();
        StringBuilder body = new StringBuilder();
        String reportStatus = "completed".equals(dto.status())
                ? "completed an assessment of the following target within the configured scope:"
                : "attempted an assessment that did not complete successfully for the following target: ";
        body.append("Hello,\n\nRELAY AEGIS ").append(reportStatus).append("\n")
                .append(target == null ? dto.id() : target).append("\n\n")
                .append(dto.summary()).append("\n\n");
        for (var finding : dto.findings().stream().limit(15).toList()) {
            body.append("- [").append(finding.severity().toUpperCase(Locale.ROOT)).append("] ").append(finding.title())
                    .append(" (confidence: ").append(finding.confidence()).append(")");
            if (finding.file() != null) body.append(" — ").append(finding.file()).append(finding.line() == null ? "" : ":" + finding.line());
            body.append("\n  Evidence: ").append(finding.evidence()).append("\n  Recommendation: ").append(finding.recommendation()).append("\n");
        }
        body.append("\nThese findings are assessment candidates, not all confirmed defects. Please validate them in the appropriate test environment. Full Markdown report is available from the RELAY AEGIS operator.\n\nRegards,\nRELAY AEGIS");
        return new AssessmentReportDto(dto.id(), markdown, subject, body.toString());
    }

    private AssessmentEntity find(String id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assessment not found."));
    }

    private AssessmentDto toDto(AssessmentEntity entity) { return toDto(entity, false); }

    private AssessmentDto toDto(AssessmentEntity entity, boolean withDiagnosis) {
        List<AssessmentFindingDto> findings = readList(entity.getFindingsJson(), new TypeReference<List<AssessmentFindingDto>>() {});
        List<AssessmentCheckDto> checks = readList(entity.getChecksJson(), new TypeReference<List<AssessmentCheckDto>>() {});
        List<AssessmentTimelineStepDto> timeline = readList(entity.getTimelineJson(), new TypeReference<List<AssessmentTimelineStepDto>>() {});
        return new AssessmentDto(entity.getId(), entity.getKind(), entity.getRepositoryUrl(), entity.getApplicationUrl(), entity.getStatus(),
                entity.getCurrentStage(), timeline, entity.getSummary(), entity.getRepositoryCommit(), entity.getAiTriageStatus(), entity.getAiSummary(), findings, checks,
                entity.getErrorMessage(), entity.getCreatedAt(), entity.getStartedAt(), entity.getCompletedAt(), entity.getProvenance(),
                findings.stream().filter(f -> com.relayaegis.remediation.PatchProposalService.isPatchable(entity, f)).map(AssessmentFindingDto::id).toList(),
                withDiagnosis ? diagnosis.diagnose(entity, findings, checks, findings.stream()
                        .filter(f -> com.relayaegis.remediation.PatchProposalService.isPatchable(entity, f)).map(AssessmentFindingDto::id)
                        .collect(java.util.stream.Collectors.toSet())) : null);
    }

    private <T> List<T> readList(String raw, TypeReference<List<T>> type) {
        try { return mapper.readValue(raw == null || raw.isBlank() ? "[]" : raw, type); }
        catch (Exception ex) { return List.of(); }
    }

    private String buildMarkdown(AssessmentDto dto) {
        StringBuilder md = new StringBuilder();
        md.append("# RELAY AEGIS Assessment Report\n\n")
                .append("- **Assessment ID:** ").append(dto.id()).append("\n")
                .append("- **Type:** ").append(dto.kind()).append("\n")
                .append("- **Status:** ").append(dto.status()).append("\n")
                .append("- **Created:** ").append(dto.createdAt()).append("\n");
        if (dto.repositoryUrl() != null) md.append("- **Repository:** ").append(dto.repositoryUrl()).append("\n");
        if (dto.repositoryCommit() != null) md.append("- **Commit:** `").append(dto.repositoryCommit()).append("`\n");
        if (dto.applicationUrl() != null) md.append("- **Application URL:** ").append(dto.applicationUrl()).append("\n");
        md.append("\n## Executive summary\n\n").append(nullSafe(dto.summary(), "No summary available.")).append("\n\n")
                .append("## Assessment timeline\n\n");
        if (dto.timeline().isEmpty()) md.append("No stage events were recorded.\n\n");
        for (var step : dto.timeline()) md.append("- **").append(step.label()).append(" — ").append(step.state()).append("**: ")
                .append(step.detail()).append(step.timestamp() == null ? "" : " (" + step.timestamp() + ")").append("\n");
        md.append("\n## Findings\n\n");
        if (dto.findings().isEmpty()) md.append("No candidate findings were returned by the enabled checks. This does not prove that the target is defect-free.\n\n");
        for (var finding : dto.findings()) {
            md.append("### [").append(finding.severity().toUpperCase(Locale.ROOT)).append("] ").append(finding.title()).append("\n\n")
                    .append("- **ID:** ").append(finding.id()).append("\n")
                    .append("- **Category:** ").append(finding.category()).append("\n")
                    .append("- **Confidence:** ").append(finding.confidence()).append("\n")
                    .append("- **Validation:** ").append(finding.validationStatus()).append("\n");
            if (finding.file() != null) md.append("- **Location:** `").append(finding.file()).append(finding.line() == null ? "" : ":" + finding.line()).append("`\n");
            md.append("\n**Evidence:** ").append(finding.evidence()).append("\n\n")
                    .append("**Explanation:** ").append(finding.explanation()).append("\n\n")
                    .append("**Recommendation:** ").append(finding.recommendation()).append("\n\n");
        }
        md.append("## Checks\n\n");
        for (var check : dto.checks()) md.append("- **").append(check.name()).append(" — ").append(check.status()).append(":** ").append(check.detail()).append("\n");
        if (dto.aiSummary() != null && !dto.aiSummary().isBlank()) md.append("\n## AI triage\n\n").append(dto.aiSummary()).append("\n");
        md.append("\n## Scope and limitations\n\n")
                .append("- Findings are automated assessment candidates and must be validated; a low or medium confidence result may be a false positive.\n")
                .append("- Repository source code, package scripts, build tasks, tests, and Dockerfiles were not executed.\n")
                .append("- Live URL assessment issues one GET request, does not follow redirects, does not crawl the site, and does not fuzz endpoints.\n")
                .append("- The absence of findings is not proof that the target is secure or bug-free.\n")
                .append("- No external repository was modified and no email was sent by this report operation.\n");
        return md.toString();
    }

    private static String nullSafe(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
}

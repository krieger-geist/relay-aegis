package com.relayaegis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.assessment.AssessmentScanResult;
import com.relayaegis.assessment.GitHubRepositoryAnalyzer;
import com.relayaegis.assessment.LiveUrlAnalyzer;
import com.relayaegis.dto.AssessmentDtos.AssessmentCheckDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentTimelineStepDto;
import com.relayaegis.entity.AssessmentEntity;
import com.relayaegis.repository.AssessmentRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AssessmentExecutionService {
    private final AssessmentRepository repository;
    private final GitHubRepositoryAnalyzer repositoryAnalyzer;
    private final LiveUrlAnalyzer urlAnalyzer;
    private final AssessmentAiTriageService aiTriage;
    private final ObjectMapper mapper;
    private final com.relayaegis.remediation.GitSourceFetcher fetcher;

    public AssessmentExecutionService(AssessmentRepository repository, GitHubRepositoryAnalyzer repositoryAnalyzer,
            LiveUrlAnalyzer urlAnalyzer, AssessmentAiTriageService aiTriage, ObjectMapper mapper, com.relayaegis.remediation.GitSourceFetcher fetcher) {
        this.fetcher = fetcher;
        this.repository = repository;
        this.repositoryAnalyzer = repositoryAnalyzer;
        this.urlAnalyzer = urlAnalyzer;
        this.aiTriage = aiTriage;
        this.mapper = mapper;
    }

    /** Read-only scan of one exact commit, through the same shallow, hook-free fetch the sandbox uses. */
    private AssessmentScanResult scanPinned(String repositoryUrl, String commit) throws Exception {
        java.nio.file.Path workspace = java.nio.file.Files.createTempDirectory("relay-assessment-pinned-");
        try {
            fetcher.checkoutCommit(repositoryUrl + ".git", commit, workspace);
            return repositoryAnalyzer.scanDirectory(workspace, commit);
        } catch (com.relayaegis.remediation.RemediationException ex) {
            throw new IllegalStateException("The pinned commit could not be fetched. Confirm that it exists in the public repository.");
        } finally {
            com.relayaegis.remediation.GitSourceFetcher.deleteWorkspace(workspace);
        }
    }

    @Async("assessmentExecutor")
    public CompletableFuture<Void> execute(String assessmentId) {
        run(assessmentId);
        return CompletableFuture.completedFuture(null);
    }

    public void run(String assessmentId) {
        AssessmentEntity assessment = repository.findById(assessmentId).orElse(null);
        if (assessment == null) return;
        List<AssessmentTimelineStepDto> timeline = readTimeline(assessment.getTimelineJson());
        List<AssessmentFindingDto> findings = new ArrayList<>();
        List<AssessmentCheckDto> checks = new ArrayList<>();
        String commit = null;
        try {
            assessment.setStatus("running");
            assessment.setStartedAt(Instant.now());
            assessment.setErrorMessage(null);
            startStage(assessment, timeline, "collection", "Assessment started",
                    "The queued worker started. Only the selected, explicitly scoped sources will be inspected.");

            if ("repository".equals(assessment.getKind()) || "combined".equals(assessment.getKind())) {
                String pinned = assessment.getRepositoryCommit();
                startStage(assessment, timeline, "repository_scan", "Inspecting GitHub repository", pinned != null
                        ? "Fetching exactly the pinned commit " + pinned.substring(0, 12) + " into a temporary workspace; repository code will not be executed."
                        : "Cloning one shallow public commit into a temporary workspace; repository code will not be executed.");
                AssessmentScanResult repoResult = pinned != null ? scanPinned(assessment.getRepositoryUrl(), pinned) : repositoryAnalyzer.scan(assessment.getRepositoryUrl());
                commit = repoResult.repositoryCommit();
                findings.addAll(repoResult.findings());
                checks.addAll(repoResult.checks());
                completeCurrentStage(assessment, timeline, "Read-only scan completed for commit "
                        + commit.substring(0, Math.min(12, commit.length())) + "; " + repoResult.findings().size() + " candidate finding(s) collected.");
            }

            if ("url".equals(assessment.getKind()) || "combined".equals(assessment.getKind())) {
                startStage(assessment, timeline, "live_url_check", "Checking live application",
                        "Checking DNS, TCP and TLS, then a few bounded GET requests. Redirects are not followed and response bodies are not retained.");
                LiveUrlAnalyzer.UrlScanResult urlResult = urlAnalyzer.scan(assessment.getApplicationUrl());
                findings.addAll(urlResult.findings());
                checks.addAll(urlResult.checks());
                if (urlResult.observation() != null) assessment.setLiveObservationJson(mapper.writeValueAsString(urlResult.observation()));
                completeCurrentStage(assessment, timeline, "The bounded URL check completed with "
                        + urlResult.findings().size() + " candidate finding(s); no request body was stored.");
            }

            startStage(assessment, timeline, "finding_review", "Assembling findings",
                    "Combining enabled deterministic checks and preparing evidence, severity, confidence, and recommendations.");
            if (findings.size() > 200) findings = new ArrayList<>(findings.subList(0, 200));
            String summary = buildSummary(assessment.getKind(), findings, checks);
            assessment.setRepositoryCommit(commit);
            assessment.setFindingsJson(mapper.writeValueAsString(findings));
            assessment.setChecksJson(mapper.writeValueAsString(checks));
            assessment.setSummary(summary);
            completeCurrentStage(assessment, timeline, "Assembled " + findings.size() + " candidate finding(s) and "
                    + checks.size() + " check result(s). Findings remain unverified until reviewed.");

            if (assessment.isAiTriageRequested()) {
                startStage(assessment, timeline, "ai_triage", "AI triage", 
                        "Summarizing finding metadata only. Source files and live HTTP response bodies are not sent to Gemini.");
                AssessmentAiTriageService.TriageOutcome outcome = aiTriage.triage(assessment.getKind(),
                        assessment.getRepositoryUrl(), assessment.getApplicationUrl(), findings);
                assessment.setAiTriageStatus(outcome.status());
                assessment.setAiSummary(outcome.summary());
                completeCurrentStage(assessment, timeline, "Optional AI triage ended with status '" + outcome.status()
                        + "'. Its output is a hypothesis, not independent verification.");
            } else {
                assessment.setAiTriageStatus("not_requested");
                assessment.setAiSummary("AI triage was not requested. Deterministic rules were used; no source files were sent to an AI provider.");
                startStage(assessment, timeline, "ai_triage_skipped", "AI triage skipped", 
                        "AI triage was not requested. The deterministic assessment results are retained.");
                completeCurrentStage(assessment, timeline, "Skipped at the operator's choice.");
            }

            startStage(assessment, timeline, "report_ready", "Report ready",
                    "Markdown export and a reviewed email draft are available. No external repository was changed and no email was sent.");
            completeCurrentStage(assessment, timeline, "Assessment complete. No code change or remote action was performed.");
            assessment.setStatus("completed");
            assessment.setCurrentStage("completed");
            assessment.setCompletedAt(Instant.now());
            persistProgress(assessment, timeline);
        } catch (Exception ex) {
            String safeMessage = safeFailure(ex);
            assessment.setStatus("failed");
            assessment.setCurrentStage("failed");
            assessment.setCompletedAt(Instant.now());
            assessment.setErrorMessage(safeMessage);
            assessment.setSummary("Assessment did not complete. Review the recorded stage and target configuration before retrying.");
            try {
                assessment.setFindingsJson(mapper.writeValueAsString(findings));
                assessment.setChecksJson(mapper.writeValueAsString(checks));
            } catch (Exception ignored) {
                assessment.setFindingsJson("[]");
                assessment.setChecksJson("[]");
            }
            failCurrentStage(assessment, timeline, safeMessage);
            try { repository.save(assessment); } catch (Exception ignored) { /* Preserve the original failure if storage itself is unavailable. */ }
        }
    }

    private void startStage(AssessmentEntity assessment, List<AssessmentTimelineStepDto> timeline,
            String stage, String label, String detail) throws Exception {
        for (int i = 0; i < timeline.size(); i++) {
            AssessmentTimelineStepDto item = timeline.get(i);
            if ("current".equals(item.state())) {
                timeline.set(i, new AssessmentTimelineStepDto(item.id(), item.stage(), item.label(), item.detail(), "completed", item.timestamp()));
            }
        }
        timeline.add(new AssessmentTimelineStepDto("stg-" + UUID.randomUUID().toString().substring(0, 8),
                stage, label, detail, "current", Instant.now()));
        assessment.setCurrentStage(stage);
        persistProgress(assessment, timeline);
    }

    private void completeCurrentStage(AssessmentEntity assessment, List<AssessmentTimelineStepDto> timeline, String detail) throws Exception {
        for (int i = timeline.size() - 1; i >= 0; i--) {
            AssessmentTimelineStepDto item = timeline.get(i);
            if ("current".equals(item.state())) {
                timeline.set(i, new AssessmentTimelineStepDto(item.id(), item.stage(), item.label(), detail, "completed", item.timestamp()));
                persistProgress(assessment, timeline);
                return;
            }
        }
        persistProgress(assessment, timeline);
    }

    private void failCurrentStage(AssessmentEntity assessment, List<AssessmentTimelineStepDto> timeline, String detail) {
        boolean changed = false;
        for (int i = timeline.size() - 1; i >= 0; i--) {
            AssessmentTimelineStepDto item = timeline.get(i);
            if ("current".equals(item.state())) {
                timeline.set(i, new AssessmentTimelineStepDto(item.id(), item.stage(), item.label(), detail, "failed", item.timestamp()));
                changed = true;
                break;
            }
        }
        if (!changed) timeline.add(new AssessmentTimelineStepDto("stg-" + UUID.randomUUID().toString().substring(0, 8),
                "failed", "Assessment failed", detail, "failed", Instant.now()));
        try { persistProgress(assessment, timeline); } catch (Exception ignored) { /* Failure reporting must not mask the assessment error. */ }
    }

    private void persistProgress(AssessmentEntity assessment, List<AssessmentTimelineStepDto> timeline) throws Exception {
        assessment.setTimelineJson(mapper.writeValueAsString(timeline));
        repository.save(assessment);
    }

    private List<AssessmentTimelineStepDto> readTimeline(String raw) {
        try {
            return new ArrayList<>(mapper.readValue(raw == null || raw.isBlank() ? "[]" : raw,
                    new TypeReference<List<AssessmentTimelineStepDto>>() {}));
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private static String buildSummary(String kind, List<AssessmentFindingDto> findings, List<AssessmentCheckDto> checks) {
        long high = findings.stream().filter(f -> "high".equals(f.severity()) || "critical".equals(f.severity())).count();
        long medium = findings.stream().filter(f -> "medium".equals(f.severity())).count();
        String source = switch (kind) { case "repository" -> "repository"; case "url" -> "live URL"; default -> "combined"; };
        String conclusion = findings.isEmpty() ? "No candidate findings were raised by the enabled checks." : "The checks raised " + findings.size() + " candidate finding(s), including " + high + " high/critical and " + medium + " medium severity item(s).";
        return "Completed " + source + " assessment. " + conclusion + " Candidate findings are not all confirmed defects; review evidence and validate before remediation.";
    }

    private static String safeFailure(Exception ex) {
        if (ex instanceof org.springframework.web.server.ResponseStatusException response && response.getReason() != null) return response.getReason();
        String message = ex.getMessage();
        if (message == null || message.isBlank()) return "The assessment could not complete. Check backend logs and target configuration.";
        // Never return git stderr, process output, or secrets to the UI.
        if (message.contains("Repository could not be cloned") || message.contains("deadline") || message.contains("allowlisted")) return message;
        return "The assessment could not complete. Check that the target is reachable and allowed, then review backend logs.";
    }
}

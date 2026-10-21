package com.relayaegis.remediation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.assessment.GitHubRepositoryAnalyzer;
import com.relayaegis.dto.ApiDtos.RepairCheckDto;
import com.relayaegis.dto.ApiDtos.RepairDiffDto;
import com.relayaegis.dto.ApiDtos.RepairJobDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.entity.AssessmentEntity;
import com.relayaegis.entity.RepairJobEntity;
import com.relayaegis.repository.AssessmentRepository;
import com.relayaegis.repository.RepairJobRepository;
import com.relayaegis.remediation.sandbox.SandboxValidationService;
import com.relayaegis.service.RepairJobService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Generates a reviewable, unvalidated patch for one assessment finding. The patch is produced against the
 * exact commit that was assessed and stored only as a RELAY record: the GitHub repository is never modified,
 * no branch or pull request is created, and nothing from the repository is built, run, or tested.
 */
@Service
public class PatchProposalService {
    private static final Logger log = LoggerFactory.getLogger(PatchProposalService.class);
    static final String GENERATING = "generating_patch";
    static final String PROPOSED = "candidate_patch_created";
    static final String FAILED = "patch_failed";
    static final String UNVALIDATED = "Unvalidated proposal: it has not been built, tested, or applied. Review it manually before use.";

    private final AssessmentRepository assessments;
    private final RepairJobRepository repairs;
    private final RepairJobService repairService;
    private final GitSourceFetcher fetcher;
    private final ObjectMapper mapper;
    private final Executor executor;
    private final SandboxValidationService sandbox;

    public PatchProposalService(AssessmentRepository assessments, RepairJobRepository repairs, RepairJobService repairService,
            GitSourceFetcher fetcher, ObjectMapper mapper, @Qualifier("assessmentExecutor") Executor executor,
            SandboxValidationService sandbox) {
        this.sandbox = sandbox;
        this.assessments = assessments;
        this.repairs = repairs;
        this.repairService = repairService;
        this.fetcher = fetcher;
        this.mapper = mapper;
        this.executor = executor;
    }

    public static final String LIVE_INVESTIGATION_NO_REPAIR = "Live URL investigations report what they observe; they never change code. "
            + "Run a Codebase investigation of this repository to repair it.";

    /** Only a Codebase investigation (kind "repository") may change source code; Live URL investigations, with or without a repository, only report. */
    public static boolean isCodebaseInvestigation(AssessmentEntity assessment) {
        return "repository".equals(assessment.getKind());
    }

    /** True when a patch can be proposed for this finding of this assessment. */
    public static boolean isPatchable(AssessmentEntity assessment, AssessmentFindingDto finding) {
        return isCodebaseInvestigation(assessment) && "completed".equals(assessment.getStatus()) && assessment.getRepositoryUrl() != null
                && assessment.getRepositoryCommit() != null && assessment.getRepositoryCommit().matches("[0-9a-f]{40}")
                && PatchStrategies.forFinding(finding).isPresent();
    }

    public RepairJobDto propose(String assessmentId, String findingId) {
        AssessmentEntity assessment = assessments.findById(assessmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assessment not found."));
        AssessmentFindingDto finding = findings(assessment).stream().filter(f -> findingId.equals(f.id())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Finding not found in this assessment."));
        if (!isCodebaseInvestigation(assessment)) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, LIVE_INVESTIGATION_NO_REPAIR);
        if (!isPatchable(assessment, finding)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, PatchStrategies.forFinding(finding).isPresent()
                    ? "Patch proposals require a completed repository assessment with a recorded commit."
                    : "No automated patch strategy exists for this finding; it needs manual remediation. " + PatchStrategies.supportedSummary());
        }
        Boolean sandboxAvailable = null;
        for (RepairJobEntity existing : repairs.findByAssessmentIdAndFindingIdOrderByUpdatedAtDesc(assessmentId, findingId)) {
            if (GENERATING.equals(existing.state)) return repairService.toDto(existing);
            if (!PROPOSED.equals(existing.state)) continue;
            // A proposal whose sandbox validation never ran (sandbox off, or it could not run a phase) is regenerated once
            // the sandbox is available; the old record is kept. Real build/test failures are returned as they are, and so
            // are tests that also fail without the patch (re-running them would only repeat the same baseline failure).
            boolean baselineFailed = existing.checksJson != null && existing.checksJson.contains("\"name\":\"Baseline tests\",\"status\":\"failed\"");
            // Test failures recorded before the baseline check existed have no "Baseline tests" result to classify them;
            // they are re-validated once (the new record always carries a baseline result, so this cannot loop).
            boolean failedWithoutBaseline = existing.checksJson != null && existing.checksJson.contains("\"name\":\"Tests\",\"status\":\"failed\"")
                    && !existing.checksJson.contains("\"name\":\"Baseline tests\"");
            if (failedWithoutBaseline || (!baselineFailed && ("not_run".equals(existing.verificationStatus)
                    || (existing.checksJson != null && existing.checksJson.contains("\"status\":\"not_available\""))))) {
                if (sandboxAvailable == null) sandboxAvailable = sandbox.available();
                if (sandboxAvailable) continue;
            }
            return repairService.toDto(existing);
        }

        Instant now = Instant.now();
        RepairJobEntity job = new RepairJobEntity();
        job.id = "PATCH-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        job.incidentId = null;
        job.assessmentId = assessmentId;
        job.findingId = findingId;
        job.findingTitle = finding.title();
        job.filePath = finding.file();
        job.strategy = PatchStrategies.forFinding(finding).orElseThrow().id();
        job.repository = assessment.getRepositoryUrl();
        job.applicationName = assessment.getRepositoryUrl().replaceFirst("^https://github\\.com/", "");
        job.commitSha = assessment.getRepositoryCommit();
        job.state = GENERATING;
        job.explanation = "Fetching the assessed commit to generate a proposed patch.";
        job.filesChanged = 0;
        job.diffJson = "[]";
        job.checksJson = "[]";
        job.verificationStatus = "not_run";
        job.verificationDetail = UNVALIDATED;
        job.provenance = "live";
        job.createdAt = now;
        job.updatedAt = now;
        repairs.save(job);
        try {
            executor.execute(() -> generate(job.id, finding));
        } catch (RejectedExecutionException ex) {
            fail(job, "The repository worker is busy with other assessments. Retry the proposal shortly.");
            repairs.save(job);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, job.explanation);
        }
        return repairService.toDto(job);
    }

    void generate(String jobId, AssessmentFindingDto finding) {
        RepairJobEntity job = repairs.findById(jobId).orElse(null);
        if (job == null) return;
        try {
            PatchStrategies.Strategy strategy = PatchStrategies.byId(job.strategy)
                    .orElseThrow(() -> new RemediationException("The recorded patch strategy is no longer available."));
            String content = fetcher.fetchFile(job.repository + ".git", job.commitSha, finding.file());
            List<String> lines = splitLines(content);
            PatchStrategies.Result proposal = strategy.propose(finding.file(), lines, finding.line() == null ? 1 : finding.line());
            UnifiedDiff.Result diff = UnifiedDiff.compute(finding.file(),
                    redact(lines, proposal.secretValue()), redact(proposal.newLines(), proposal.secretValue()), 3);
            int changed = diff.additions().size() + diff.removals().size();
            if (changed == 0) throw new RemediationException("The " + strategy.id() + " strategy produced no change for this finding; nothing was proposed.");
            if (changed > strategy.maxChangedLines()) {
                throw new RemediationException("The generated patch changes " + changed + " lines, more than the " + strategy.maxChangedLines()
                        + " the " + strategy.id() + " strategy may change. It was refused before validation; nothing was applied.");
            }
            job.patchText = diff.patch();
            job.diffJson = mapper.writeValueAsString(List.of(new RepairDiffDto(finding.file(), diff.additions(), diff.removals())));
            job.filesChanged = 1;
            job.state = PROPOSED;
            job.explanation = proposal.explanation();
            String shortCommit = job.commitSha.substring(0, 12);
            List<RepairCheckDto> base = List.of(
                    new RepairCheckDto("Original preserved", "passed", "Generated from commit " + shortCommit
                            + " without modifying the repository. No branch, commit, or pull request was created."),
                    new RepairCheckDto("Finding re-located", "passed", "The finding was located again in "
                            + finding.file() + " at the assessed commit, and the " + strategy.id() + " strategy produced a change."),
                    new RepairCheckDto("Change scope", "passed", "1 file (" + finding.file() + "), +" + diff.additions().size() + " −" + diff.removals().size()
                            + " lines, within the " + strategy.maxChangedLines() + "-line limit of the " + strategy.id() + " strategy. No other file is touched."));
            job.verificationStatus = "not_run";
            job.verificationDetail = UNVALIDATED;
            if (sandbox.available()) {
                List<RepairCheckDto> running = new ArrayList<>(base);
                running.add(new RepairCheckDto("Sandbox validation", "running", "Building and testing the patch in a disposable sandbox."));
                job.checksJson = mapper.writeValueAsString(running);
                job.verificationStatus = "pending";
                job.verificationDetail = "Sandbox validation in progress.";
                job.updatedAt = Instant.now();
                repairs.save(job);
            }
            SandboxValidationService.Outcome outcome = sandbox.validate(job.repository + ".git", job.commitSha, finding, lines, proposal.newLines());
            List<RepairCheckDto> checks = new ArrayList<>(base);
            checks.addAll(outcome.checks());
            checks.addAll(proposal.followUps());
            job.checksJson = mapper.writeValueAsString(checks);
            job.verificationStatus = outcome.verificationStatus();
            job.verificationDetail = outcome.verificationDetail() == null ? UNVALIDATED : outcome.verificationDetail();
        } catch (RemediationException ex) {
            fail(job, ex.getMessage());
        } catch (Exception ex) {
            log.warn("Patch generation failed for {}: {}", jobId, ex.getClass().getSimpleName());
            fail(job, "Patch generation failed unexpectedly. Check the backend logs.");
        }
        job.updatedAt = Instant.now();
        repairs.save(job);
    }

    private void fail(RepairJobEntity job, String reason) {
        job.state = FAILED;
        job.explanation = reason;
        job.patchText = null;
        job.diffJson = "[]";
        job.filesChanged = 0;
        try { job.checksJson = mapper.writeValueAsString(List.of(new RepairCheckDto("Patch generation", "failed", reason))); }
        catch (Exception ignored) { job.checksJson = "[]"; }
        job.verificationStatus = "not_run";
        job.verificationDetail = "No patch was produced.";
        job.updatedAt = Instant.now();
    }

    private List<AssessmentFindingDto> findings(AssessmentEntity assessment) {
        try { return mapper.readValue(assessment.getFindingsJson(), new TypeReference<List<AssessmentFindingDto>>() {}); }
        catch (Exception ex) { return List.of(); }
    }

    static List<String> splitLines(String content) {
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\r?\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        return lines;
    }

    /** Keeps credential values out of the stored diff: the known value plus any other credential-like literal in context. */
    static List<String> redact(List<String> lines, String secret) {
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            String value = secret == null || secret.isEmpty() ? line : line.replace(secret, "[REDACTED]");
            Matcher match = GitHubRepositoryAnalyzer.SECRET_PATTERN.matcher(value);
            StringBuilder redacted = new StringBuilder();
            int last = 0;
            while (match.find()) {
                redacted.append(value, last, match.start(3)).append("[REDACTED]");
                last = match.end(3);
            }
            out.add(redacted.append(value.substring(last)).toString());
        }
        return out;
    }

    /** Wider net for display only: any quoted literal assigned to a name containing KEY, SECRET, TOKEN or PASSWORD. */
    private static final Pattern DISPLAY_SECRET_PATTERN = Pattern.compile(
            "(?i)\\b([A-Za-z0-9_]*(?:key|secret|token|password|passwd)[A-Za-z0-9_]*)\\s*[:=]\\s*(['\"])([^'\"\\r\\n]{8,})\\2");

    /**
     * Redacts credential-like literals in every line of a stored diff (context, removed and added) before it is shown
     * through the API/UI. The stored patch is left untouched: apply regenerates the change and compares it with it.
     */
    public static String redactForDisplay(String patch) {
        if (patch == null || patch.isEmpty()) return patch;
        String redacted = redactGroup3(GitHubRepositoryAnalyzer.SECRET_PATTERN, patch);
        return redactGroup3(DISPLAY_SECRET_PATTERN, redacted);
    }

    private static String redactGroup3(Pattern pattern, String text) {
        Matcher match = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (match.find()) {
            out.append(text, last, match.start(3)).append("[REDACTED]");
            last = match.end(3);
        }
        return out.append(text.substring(last)).toString();
    }
}

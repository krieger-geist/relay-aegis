package com.relayaegis.repair;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.dto.ApiDtos.MergeStatusDto;
import com.relayaegis.auth.AuthenticatedUser;
import com.relayaegis.dto.ApiDtos.RepairCheckDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.dto.FindingDtos.RepairProgressDto;
import com.relayaegis.dto.FindingDtos.RepairState;
import com.relayaegis.dto.FindingDtos.RepairStepDto;
import com.relayaegis.entity.AssessmentEntity;
import com.relayaegis.entity.GitHubRemediationEntity;
import com.relayaegis.entity.RepairJobEntity;
import com.relayaegis.remediation.GitHubRemediationService;
import com.relayaegis.remediation.PatchProposalService;
import com.relayaegis.remediation.PatchStrategies;
import com.relayaegis.repository.AssessmentRepository;
import com.relayaegis.repository.GitHubRemediationRepository;
import com.relayaegis.repository.RepairJobRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Repository fixes through the existing flow: deterministic patch proposal → sandbox validation (build, tests, baseline,
 * security re-scan) → human-approved Apply &amp; Create PR → verification on the branch. Starting a repair only
 * requests the proposal; opening the pull request stays an explicit, authorized human action, and so does merging it.
 * An open pull request is REPAIR_ATTEMPTED; only after a human merged it and the targeted finding is gone from the
 * default branch is the repair RESOLVED (read from GitHub; RELAY never merges).
 */
@Component
public class GitHubPatchRepairStrategy implements RepairStrategy {
    public static final String ID = "github-patch";

    private final AssessmentRepository assessments;
    private final PatchProposalService proposals;
    private final RepairJobRepository repairs;
    private final GitHubRemediationRepository remediations;
    private final GitHubRemediationService github;
    private final ObjectMapper mapper;

    public GitHubPatchRepairStrategy(AssessmentRepository assessments, PatchProposalService proposals, RepairJobRepository repairs,
                                     GitHubRemediationRepository remediations, GitHubRemediationService github, ObjectMapper mapper) {
        this.assessments = assessments;
        this.proposals = proposals;
        this.repairs = repairs;
        this.remediations = remediations;
        this.github = github;
        this.mapper = mapper;
    }

    @Override public String id() { return ID; }

    @Override public boolean supports(Target target) {
        return target.assessmentId() != null && target.findingId() != null && target.incidentId() == null;
    }

    @Override public Assessment assess(Target target, AuthenticatedUser user) {
        if (user == null) return Assessment.block("Sign in to start a repair.");
        AssessmentEntity assessment = assessments.findById(target.assessmentId()).orElse(null);
        if (assessment == null) return Assessment.block("The assessment no longer exists.");
        if (!PatchProposalService.isCodebaseInvestigation(assessment)) return Assessment.block(PatchProposalService.LIVE_INVESTIGATION_NO_REPAIR);
        if (!"completed".equals(assessment.getStatus())) return Assessment.block("The assessment has not completed.");
        Optional<AssessmentFindingDto> finding = finding(assessment, target.findingId());
        if (finding.isEmpty()) return Assessment.block("The finding does not belong to this assessment.");
        if (PatchStrategies.forFinding(finding.get()).isEmpty()) {
            return Assessment.block("No deterministic, safe repair strategy exists for this finding; manual action is required.");
        }
        if (!PatchProposalService.isPatchable(assessment, finding.get())) return Assessment.block("The assessment did not record an exact commit to patch.");
        return Assessment.allow();
    }

    @Override public List<String> plan(Target target) {
        return List.of("Safety checks", "Repair strategy selected", "Sandbox prepared", "Patch applied in sandbox", "Build", "Tests",
                "Security re-scan", "Approval & pull request", "Verification on branch", "Human merge");
    }

    @Override public RepairProgressDto execute(Target target, AuthenticatedUser user) {
        proposals.propose(target.assessmentId(), target.findingId()); // existing flow; returns an existing proposal when one is current
        return verify(target);
    }

    @Override public RepairProgressDto verify(Target target) {
        List<RepairJobEntity> jobs = repairs.findByAssessmentIdAndFindingIdOrderByUpdatedAtDesc(target.assessmentId(), target.findingId());
        if (jobs.isEmpty()) {
            return progress(RepairState.REPAIRABLE, "Repairable", "A deterministic patch can be generated and validated in a disposable sandbox.",
                    pending(plan(target)), null, null, null);
        }
        RepairJobEntity job = jobs.get(0);
        String link = "/repairs/" + job.id;
        Map<String, RepairCheckDto> checks = checks(job);
        GitHubRemediationEntity pr = remediations.findByRepairJobIdOrderByCreatedAtDesc(job.id).stream().findFirst().orElse(null);

        List<RepairStepDto> steps = new ArrayList<>();
        steps.add(new RepairStepDto("Safety checks", "passed", "Finding, commit and strategy verified."));
        steps.add(new RepairStepDto("Repair strategy selected", "passed", job.strategy));
        steps.add(step("Sandbox prepared", checks.get("Sandbox checkout"), job));
        steps.add(step("Patch applied in sandbox", checks.get("Patch applied"), job));
        steps.add(step("Build", checks.get("Build"), job));
        RepairCheckDto tests = checks.get("Tests");
        RepairCheckDto baseline = checks.get("Baseline tests");
        steps.add(baseline != null && "failed".equals(baseline.status()) && tests != null && "failed".equals(tests.status())
                ? new RepairStepDto("Tests", "failed", "Tests fail with and without the patch (pre-existing or environment failure).")
                : step("Tests", tests, job));
        steps.add(step("Security re-scan", checks.get("Security re-scan"), job));
        steps.add(pr == null ? new RepairStepDto("Approval & pull request", "pending", "Requires an authorized approver.")
                : pr.prNumber != null ? new RepairStepDto("Approval & pull request", "passed", "Pull request #" + pr.prNumber + " on " + pr.branch + ".")
                : new RepairStepDto("Approval & pull request", GitHubRemediationEntity.FAILED.equals(pr.status) ? "failed" : "running",
                        pr.errorMessage == null ? pr.status : pr.errorMessage));
        steps.add(pr == null || pr.prNumber == null ? new RepairStepDto("Verification on branch", "pending", null)
                : new RepairStepDto("Verification on branch", "passed".equals(pr.verificationStatus) ? "passed" : "failed".equals(pr.verificationStatus) ? "failed" : "pending",
                        pr.verificationDetail));

        MergeStatusDto merge = pr != null && pr.prNumber != null ? github.mergeStatus(pr) : null;
        steps.add(merge == null ? new RepairStepDto("Human merge", "pending", "RELAY never merges; a human decides.")
                : new RepairStepDto("Human merge", "MERGED".equals(merge.state()) ? (merge.verifiedOnDefaultBranch() ? "passed" : "failed")
                        : "CLOSED".equals(merge.state()) ? "failed" : "pending", merge.detail()));

        if ("generating_patch".equals(job.state) || "pending".equals(job.verificationStatus)) {
            return progress(RepairState.REPAIRING, "Repairing…", "Generating the patch and validating it in the sandbox.", steps, null, link, job.id);
        }
        if ("patch_failed".equals(job.state)) {
            return progress(RepairState.MANUAL_ACTION_REQUIRED, "Repair could not be prepared", job.explanation, steps, null, link, job.id);
        }
        if (pr != null && pr.prNumber != null && merge != null && "MERGED".equals(merge.state())) {
            String where = "Branch " + pr.branch + " and pull request #" + pr.prNumber + ", merged by a human.";
            return merge.verifiedOnDefaultBranch()
                    ? progress(RepairState.RESOLVED, "Merged by a human · verified on the default branch", merge.detail(), steps, where, pr.prUrl, job.id)
                    : progress(RepairState.VERIFICATION_FAILED, "Merged · finding still present", merge.detail(), steps, where, pr.prUrl, job.id);
        }
        if (pr != null && pr.prNumber != null && merge != null && "CLOSED".equals(merge.state())) {
            return progress(RepairState.MANUAL_ACTION_REQUIRED, "Pull request closed without merging", merge.detail(), steps,
                    "Branch " + pr.branch + " and pull request #" + pr.prNumber + " (closed); the default branch was not modified.", pr.prUrl, job.id);
        }
        if (pr != null && pr.prNumber != null) {
            boolean verified = "passed".equals(pr.verificationStatus);
            return progress(verified ? RepairState.REPAIR_ATTEMPTED : RepairState.VERIFICATION_FAILED,
                    verified ? "Pull request opened · verified on its branch" : "Pull request opened · branch verification did not pass",
                    "The fix is on " + pr.branch + " (PR #" + pr.prNumber + "). It is not merged; merging and deployment remain human decisions, so the issue is not marked resolved.",
                    steps, "Branch " + pr.branch + " and pull request #" + pr.prNumber + "; the default branch was not modified.", pr.prUrl, job.id);
        }
        if (pr != null && GitHubRemediationEntity.FAILED.equals(pr.status)) {
            return progress(RepairState.MANUAL_ACTION_REQUIRED, "Pull request not created", pr.errorMessage, steps,
                    pr.branch == null ? null : "Branch " + pr.branch + (pr.commitSha == null ? "" : " (commit " + pr.commitSha.substring(0, 12) + ")") + "; the default branch was not modified.",
                    link, job.id);
        }
        if (github.validation(job) != null) {
            return progress(RepairState.AWAITING_APPROVAL, "Patch validated · awaiting approval",
                    "Sandbox validation passed" + ("passed".equals(job.verificationStatus) ? "." : " except tests that also fail without the patch.")
                            + " Review it and use Apply & Create PR. The repository has not been modified.", steps, null, link, job.id);
        }
        if ("failed".equals(job.verificationStatus)) {
            return progress(RepairState.VALIDATION_FAILED, "Repair not applied", validationFailedDetail(job.verificationDetail), steps, null, link, job.id);
        }
        return progress(RepairState.BLOCKED, "Repair not applied", "The patch was not validated (" + job.verificationDetail
                + "). Unvalidated patches cannot be applied; the repository was not modified.", steps, null, link, job.id);
    }

    private static RepairStepDto step(String name, RepairCheckDto check, RepairJobEntity job) {
        if (check == null) return new RepairStepDto(name, "generating_patch".equals(job.state) || "pending".equals(job.verificationStatus) ? "running" : "skipped", null);
        String status = switch (check.status()) { case "passed" -> "passed"; case "failed" -> "failed"; case "running" -> "running"; default -> "skipped"; };
        return new RepairStepDto(name, status, check.detail());
    }

    private static List<RepairStepDto> pending(List<String> names) {
        return names.stream().map(n -> new RepairStepDto(n, "pending", null)).toList();
    }

    private RepairProgressDto progress(RepairState state, String headline, String detail, List<RepairStepDto> steps, String modified, String link, String jobId) {
        return new RepairProgressDto(ID, "GITHUB_PATCH", state, headline, detail, List.copyOf(steps), modified, link);
    }

    private Map<String, RepairCheckDto> checks(RepairJobEntity job) {
        Map<String, RepairCheckDto> out = new LinkedHashMap<>();
        try {
            for (RepairCheckDto check : mapper.readValue(job.checksJson == null ? "[]" : job.checksJson, new TypeReference<List<RepairCheckDto>>() {})) {
                out.putIfAbsent(check.name(), check);
            }
        } catch (Exception ignored) { /* unreadable checks: shown as skipped */ }
        return out;
    }

    private Optional<AssessmentFindingDto> finding(AssessmentEntity assessment, String findingId) {
        try {
            return mapper.readValue(assessment.getFindingsJson(), new TypeReference<List<AssessmentFindingDto>>() {}).stream()
                    .filter(f -> findingId.equals(f.id())).findFirst();
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /** The sandbox's own explanation, prefixed and closed only where it does not already say so. */
    static String validationFailedDetail(String detail) {
        String text = detail == null ? "" : detail;
        if (!text.startsWith("Sandbox validation")) text = "Sandbox validation failed: " + text;
        return text.contains("The repository was not modified.") ? text : text + " The repository was not modified.";
    }
}

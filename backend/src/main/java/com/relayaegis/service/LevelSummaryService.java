package com.relayaegis.service;

import com.relayaegis.entity.AssessmentEntity;
import com.relayaegis.entity.GitHubRemediationEntity;
import com.relayaegis.repository.AssessmentRepository;
import com.relayaegis.repository.GitHubRemediationRepository;
import com.relayaegis.repository.RepairJobRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts for Level 2 and Level 3 from stored records only (Level 1 is the existing dashboard summary). Nothing is
 * estimated: a pull request counts only once GitHub returned it, and merges are not counted because merging is a human
 * decision RELAY reads per repair, not a number it stores.
 */
@Service
public class LevelSummaryService {
    /** {@code pullRequestsOnAcknowledgedFailures}: opened after the approver acknowledged tests failing with and without the patch. */
    public record CodebaseLevel(int assessments, int proposals, int validated, int validationFailed, int pullRequests, int pullRequestsOnAcknowledgedFailures) {}
    public record LiveUrlLevel(int investigations, int withRepository, int failed, Instant latestAt, String latestTarget, String latestStatus) {}
    public record LevelSummary(CodebaseLevel codebase, LiveUrlLevel liveUrl) {}

    private final AssessmentRepository assessments;
    private final RepairJobRepository repairs;
    private final GitHubRemediationRepository remediations;

    public LevelSummaryService(AssessmentRepository assessments, RepairJobRepository repairs, GitHubRemediationRepository remediations) {
        this.assessments = assessments;
        this.repairs = repairs;
        this.remediations = remediations;
    }

    @Transactional(readOnly = true)
    public LevelSummary summary() {
        List<AssessmentEntity> all = assessments.findAll();
        var jobs = repairs.findAll();
        var opened = remediations.findAll().stream().filter(r -> GitHubRemediationEntity.PR_CREATED.equals(r.status) && r.prNumber != null).toList();
        int prs = (int) opened.stream().map(r -> r.repairJobId).distinct().count();
        int acknowledged = (int) opened.stream().filter(r -> !"passed".equals(r.validationStatus)).map(r -> r.repairJobId).distinct().count();
        CodebaseLevel codebase = new CodebaseLevel(
                (int) all.stream().filter(a -> "repository".equals(a.getKind())).count(),
                jobs.size(),
                (int) jobs.stream().filter(j -> "passed".equals(j.verificationStatus)).count(),
                (int) jobs.stream().filter(j -> "failed".equals(j.verificationStatus) || "timed_out".equals(j.verificationStatus)).count(),
                prs, acknowledged);
        List<AssessmentEntity> live = all.stream().filter(a -> "url".equals(a.getKind()) || "combined".equals(a.getKind())).toList();
        Optional<AssessmentEntity> latest = live.stream().filter(a -> a.getCreatedAt() != null).max(Comparator.comparing(AssessmentEntity::getCreatedAt));
        LiveUrlLevel liveUrl = new LiveUrlLevel(live.size(), (int) live.stream().filter(a -> "combined".equals(a.getKind())).count(),
                (int) live.stream().filter(a -> "failed".equals(a.getStatus())).count(),
                latest.map(AssessmentEntity::getCreatedAt).orElse(null), latest.map(AssessmentEntity::getApplicationUrl).orElse(null),
                latest.map(AssessmentEntity::getStatus).orElse(null));
        return new LevelSummary(codebase, liveUrl);
    }
}

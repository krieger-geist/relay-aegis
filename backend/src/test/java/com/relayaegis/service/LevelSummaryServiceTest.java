package com.relayaegis.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.relayaegis.entity.AssessmentEntity;
import com.relayaegis.entity.GitHubRemediationEntity;
import com.relayaegis.entity.RepairJobEntity;
import com.relayaegis.repository.AssessmentRepository;
import com.relayaegis.repository.GitHubRemediationRepository;
import com.relayaegis.repository.RepairJobRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LevelSummaryServiceTest {
    private static AssessmentEntity assessment(String kind, String status, Instant at, String url) {
        AssessmentEntity a = new AssessmentEntity();
        a.setKind(kind); a.setStatus(status); a.setCreatedAt(at); a.setApplicationUrl(url);
        return a;
    }

    private static RepairJobEntity job(String id, String verification) {
        RepairJobEntity j = new RepairJobEntity();
        j.id = id; j.verificationStatus = verification;
        return j;
    }

    private static GitHubRemediationEntity remediation(String jobId, String status, Integer pr) {
        GitHubRemediationEntity r = new GitHubRemediationEntity();
        r.repairJobId = jobId; r.status = status; r.prNumber = pr; r.validationStatus = "passed";
        return r;
    }

    private static GitHubRemediationEntity acknowledgedFailures(String jobId, int pr) {
        GitHubRemediationEntity r = remediation(jobId, GitHubRemediationEntity.PR_CREATED, pr);
        r.validationStatus = "preexisting_test_failures";
        return r;
    }

    private static LevelSummaryService service(List<AssessmentEntity> a, List<RepairJobEntity> j, List<GitHubRemediationEntity> r) {
        AssessmentRepository assessments = mock(AssessmentRepository.class);
        RepairJobRepository repairs = mock(RepairJobRepository.class);
        GitHubRemediationRepository remediations = mock(GitHubRemediationRepository.class);
        when(assessments.findAll()).thenReturn(a);
        when(repairs.findAll()).thenReturn(j);
        when(remediations.findAll()).thenReturn(r);
        return new LevelSummaryService(assessments, repairs, remediations);
    }

    @Test
    void countsOnlyWhatWasRecorded() {
        Instant t = Instant.parse("2026-10-21T08:00:00Z");
        var summary = service(
                List.of(assessment("repository", "completed", t, null), assessment("url", "completed", t.minusSeconds(60), "http://a.test"),
                        assessment("combined", "failed", t.plusSeconds(60), "http://b.test")),
                List.of(job("P1", "passed"), job("P2", "failed"), job("P3", "not_run")),
                List.of(remediation("P1", GitHubRemediationEntity.PR_CREATED, 3), remediation("P1", GitHubRemediationEntity.PR_CREATED, 3),
                        remediation("P2", GitHubRemediationEntity.FAILED, null), acknowledgedFailures("P3", 1))).summary();
        assertEquals(new LevelSummaryService.CodebaseLevel(1, 3, 1, 1, 2, 1), summary.codebase());
        assertEquals(2, summary.liveUrl().investigations());
        assertEquals(1, summary.liveUrl().withRepository());
        assertEquals(1, summary.liveUrl().failed());
        assertEquals("http://b.test", summary.liveUrl().latestTarget());
        assertEquals("failed", summary.liveUrl().latestStatus());
    }

    @Test
    void emptyWhenNothingRan() {
        var summary = service(List.of(), List.of(), List.of()).summary();
        assertEquals(new LevelSummaryService.CodebaseLevel(0, 0, 0, 0, 0, 0), summary.codebase());
        assertEquals(0, summary.liveUrl().investigations());
        assertNull(summary.liveUrl().latestAt());
    }
}

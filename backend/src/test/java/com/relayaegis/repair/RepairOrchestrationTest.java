package com.relayaegis.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.relayaegis.auth.AuthenticatedUser;
import com.relayaegis.auth.Role;
import com.relayaegis.dto.ApiDtos.DeterministicDiagnosisDto;
import com.relayaegis.dto.ApiDtos.DiagnosisDto;
import com.relayaegis.dto.ApiDtos.EvidenceItemDto;
import com.relayaegis.dto.ApiDtos.EvidenceRefDto;
import com.relayaegis.dto.ApiDtos.HypothesisDto;
import com.relayaegis.dto.ApiDtos.IncidentDto;
import com.relayaegis.dto.ApiDtos.RepairCheckDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.dto.FindingDtos.RepairProgressDto;
import com.relayaegis.dto.FindingDtos.RepairState;
import com.relayaegis.dto.FindingDtos.RepairStepDto;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.AssessmentEntity;
import com.relayaegis.entity.GitHubRemediationEntity;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.entity.RepairJobEntity;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.recovery.RecoveryDecision;
import com.relayaegis.recovery.RecoveryPolicyEngine;
import com.relayaegis.remediation.GitHubRemediationService;
import com.relayaegis.remediation.PatchProposalService;
import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.repository.AssessmentRepository;
import com.relayaegis.repository.GitHubRemediationRepository;
import com.relayaegis.repository.RepairJobRepository;
import com.relayaegis.service.IncidentService;
import com.relayaegis.service.RecoveryOrchestrationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RepairOrchestrationTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AuthenticatedUser user = new AuthenticatedUser("USR-1", "user@relay.test", "User", Role.USER);
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    // GitHub strategy collaborators
    private AssessmentRepository assessments;
    private PatchProposalService proposals;
    private RepairJobRepository repairs;
    private GitHubRemediationRepository remediations;
    private GitHubRemediationService github;
    private AssessmentEntity assessment;
    private final List<RepairJobEntity> jobs = new ArrayList<>();

    // Runtime strategy collaborators
    private IncidentService incidents;
    private RecoveryPolicyEngine policy;
    private RecoveryOrchestrationService recovery;
    private IncidentEntity incident;
    private final List<EvidenceItemDto> evidence = new ArrayList<>();

    private RepairOrchestrator orchestrator;

    @BeforeEach
    void setUp() throws Exception {
        assessments = mock(AssessmentRepository.class);
        proposals = mock(PatchProposalService.class);
        repairs = mock(RepairJobRepository.class);
        remediations = mock(GitHubRemediationRepository.class);
        github = mock(GitHubRemediationService.class);
        assessment = new AssessmentEntity();
        assessment.setId("ASM-1"); assessment.setKind("repository"); assessment.setStatus("completed"); assessment.setRepositoryUrl("https://github.com/o/r"); assessment.setRepositoryCommit(SHA);
        assessment.setFindingsJson(mapper.writeValueAsString(List.of(
                new AssessmentFindingDto("F-CRED", "secret-hygiene", "Possible hard-coded credential", "medium", "medium", "src/A.java", 3, "e", "x", "r", "unverified_candidate"),
                new AssessmentFindingDto("F-SQL", "database-safety", "Possible SQL query concatenation", "medium", "medium", "src/B.java", 9, "e", "x", "r", "unverified_candidate"))));
        when(assessments.findById("ASM-1")).thenReturn(Optional.of(assessment));
        when(repairs.findByAssessmentIdAndFindingIdOrderByUpdatedAtDesc(anyString(), anyString())).thenAnswer(inv -> List.copyOf(jobs));
        when(remediations.findByRepairJobIdOrderByCreatedAtDesc(anyString())).thenReturn(List.of());

        incidents = mock(IncidentService.class);
        policy = mock(RecoveryPolicyEngine.class);
        recovery = mock(RecoveryOrchestrationService.class);
        incident = new IncidentEntity();
        incident.id = "INC-1"; incident.applicationId = "app-1"; incident.state = "unresolved"; incident.verificationStatus = "not_run";
        incident.diagnosisJson = mapper.writeValueAsString(diagnosis("START_CONTAINER"));
        when(incidents.findEntity("INC-1")).thenAnswer(inv -> incident);
        when(incidents.toDto(any())).thenAnswer(inv -> new IncidentDto("INC-1", null, "app-1", null, null, null, null, null, null, null, null, null, null, null,
                List.of(), List.copyOf(evidence), null, null, List.of(), "live", null));
        ApplicationRepository applications = mock(ApplicationRepository.class);
        ApplicationEntity app = new ApplicationEntity(); app.id = "app-1"; app.environment = "Development"; app.dockerContainer = "target";
        when(applications.findById("app-1")).thenReturn(Optional.of(app));
        when(policy.decide(any(), any(), any(), anyBoolean())).thenReturn(new RecoveryDecision(RecoveryActionType.START_CONTAINER, "target", true, false,
                "policy-restart", "require_approval", "container-stopped", 0.98, List.of(), null));

        orchestrator = new RepairOrchestrator(List.of(
                new GitHubPatchRepairStrategy(assessments, proposals, repairs, remediations, github, mapper),
                new RuntimeRecoveryRepairStrategy(incidents, applications, policy, recovery, mapper)));
    }

    private DiagnosisDto diagnosis(String action) {
        HypothesisDto top = new HypothesisDto("container-stopped", "Application container is stopped", "runtime", "Stopped.", "x", 0.98, "High",
                List.of(new EvidenceRefDto("RT", "runtime", "status=exited")), List.of(), List.of(), action, "target", "Start the container.");
        return new DiagnosisDto("INC-1", "c", "x", "High", "", List.of(), List.of(), List.of(), "deterministic", null, null, List.of(), Instant.now(), "live",
                "automatic", true, "p", null, 0, null, List.of(),
                new DeterministicDiagnosisDto("container-stopped", 0.98, "not_required", null, List.of(top), List.of(), List.of(), Instant.now()), null);
    }

    private RepairJobEntity job(String state, String verification, List<RepairCheckDto> checks) throws Exception {
        RepairJobEntity job = new RepairJobEntity();
        job.id = "PATCH-1"; job.state = state; job.verificationStatus = verification; job.verificationDetail = "detail";
        job.strategy = "credential-literal-to-env"; job.explanation = "explanation";
        job.checksJson = mapper.writeValueAsString(checks);
        jobs.add(job);
        return job;
    }

    private static List<RepairCheckDto> sandbox(String tests, String security) {
        return List.of(new RepairCheckDto("Sandbox checkout", "passed", "ok"), new RepairCheckDto("Patch applied", "passed", "ok"),
                new RepairCheckDto("Build", "passed", "ok"), new RepairCheckDto("Tests", tests, "t"), new RepairCheckDto("Security re-scan", security, "s"));
    }

    private static String step(RepairProgressDto progress, String name) {
        return progress.steps().stream().filter(s -> s.name().equals(name)).findFirst().map(RepairStepDto::status).orElse("absent");
    }

    // ---- GitHub patch strategy -------------------------------------------------------------------------------

    @Test
    void findingsWithoutASafeStrategyNeedManualActionAndNothingRuns() {
        RepairProgressDto result = orchestrator.start(RepairStrategy.Target.finding("ASM-1", "F-SQL"), user);
        assertEquals(RepairState.MANUAL_ACTION_REQUIRED, result.state());
        verify(proposals, never()).propose(anyString(), anyString());
    }

    @Test
    void safetyChecksBlockUnauthenticatedOrIncompleteRequestsBeforeExecution() {
        assertEquals(RepairState.BLOCKED, orchestrator.start(RepairStrategy.Target.finding("ASM-1", "F-CRED"), null).state());
        assessment.setStatus("running");
        RepairProgressDto incomplete = orchestrator.start(RepairStrategy.Target.finding("ASM-1", "F-CRED"), user);
        assertEquals(RepairState.BLOCKED, incomplete.state());
        assertEquals("failed", step(incomplete, "Safety checks"));
        assertEquals("skipped", step(incomplete, "Build"));
        verify(proposals, never()).propose(anyString(), anyString());
    }

    @Test
    void autoRepairStartsTheExistingProposalAndReportsRepairing() throws Exception {
        when(proposals.propose("ASM-1", "F-CRED")).thenAnswer(inv -> { job("generating_patch", "not_run", List.of()); return null; });
        RepairProgressDto result = orchestrator.start(RepairStrategy.Target.finding("ASM-1", "F-CRED"), user);
        verify(proposals).propose("ASM-1", "F-CRED");
        assertEquals(RepairState.REPAIRING, result.state());
        assertEquals("running", step(result, "Build"));
    }

    @Test
    void validationFailureIsNotAppliedAndSaysTheRepositoryWasNotModified() throws Exception {
        job("candidate_patch_created", "failed", sandbox("failed", "passed"));
        RepairProgressDto result = orchestrator.progress(RepairStrategy.Target.finding("ASM-1", "F-CRED"));
        assertEquals(RepairState.VALIDATION_FAILED, result.state());
        assertEquals("Repair not applied", result.headline());
        assertTrue(result.detail().contains("The repository was not modified."));
        assertNull(result.targetModified());
        assertEquals("failed", step(result, "Tests"));
        assertEquals("passed", step(result, "Patch applied in sandbox"));
    }

    @Test
    void anUnvalidatedPatchIsBlockedNotApplied() throws Exception {
        job("candidate_patch_created", "not_run", List.of(new RepairCheckDto("Build", "not_available", "sandbox disabled")));
        RepairProgressDto result = orchestrator.progress(RepairStrategy.Target.finding("ASM-1", "F-CRED"));
        assertEquals(RepairState.BLOCKED, result.state());
        assertTrue(result.detail().contains("Unvalidated patches cannot be applied"));
    }

    @Test
    void aValidatedPatchAwaitsHumanApprovalAndIsNotStartedTwice() throws Exception {
        RepairJobEntity job = job("candidate_patch_created", "passed", sandbox("passed", "passed"));
        when(github.validation(job)).thenReturn("passed");
        RepairProgressDto result = orchestrator.start(RepairStrategy.Target.finding("ASM-1", "F-CRED"), user);
        assertEquals(RepairState.AWAITING_APPROVAL, result.state());
        assertEquals("pending", step(result, "Approval & pull request"));
        assertEquals("/repairs/PATCH-1", result.detailsLink());
        verify(proposals, never()).propose(anyString(), anyString());
    }

    @Test
    void aVerifiedPullRequestIsARepairAttemptNeverResolved() throws Exception {
        RepairJobEntity job = job("candidate_patch_created", "passed", sandbox("passed", "passed"));
        GitHubRemediationEntity pr = new GitHubRemediationEntity();
        pr.prNumber = 4; pr.prUrl = "https://github.com/o/r/pull/4"; pr.branch = "relay-aegis/fix/F-CRED"; pr.status = GitHubRemediationEntity.PR_CREATED;
        pr.verificationStatus = "passed"; pr.verificationDetail = "main is unchanged.";
        when(remediations.findByRepairJobIdOrderByCreatedAtDesc(job.id)).thenReturn(List.of(pr));
        RepairProgressDto result = orchestrator.progress(RepairStrategy.Target.finding("ASM-1", "F-CRED"));
        assertEquals(RepairState.REPAIR_ATTEMPTED, result.state());
        assertTrue(result.detail().contains("not marked resolved"));
        assertTrue(result.targetModified().contains("default branch was not modified"));
        assertEquals("https://github.com/o/r/pull/4", result.detailsLink());
        pr.verificationStatus = "failed";
        assertEquals(RepairState.VERIFICATION_FAILED, orchestrator.progress(RepairStrategy.Target.finding("ASM-1", "F-CRED")).state());
    }

    @Test
    void aLiveUrlInvestigationNeverStartsACodeRepairEvenWithARepository() {
        assessment.setKind("combined");
        RepairProgressDto result = orchestrator.start(RepairStrategy.Target.finding("ASM-1", "F-CRED"), user);
        assertEquals(RepairState.BLOCKED, result.state());
        assertTrue(result.detail().contains("never change code"));
        verify(proposals, never()).propose(anyString(), anyString());
    }

    @Test
    void aGitHubRepairIsResolvedOnlyAfterAHumanMergeVerifiedOnTheDefaultBranch() throws Exception {
        RepairJobEntity job = job("candidate_patch_created", "passed", sandbox("passed", "passed"));
        GitHubRemediationEntity pr = new GitHubRemediationEntity();
        pr.prNumber = 4; pr.prUrl = "https://github.com/o/r/pull/4"; pr.branch = "relay-aegis/fix/F-CRED"; pr.status = GitHubRemediationEntity.PR_CREATED;
        pr.verificationStatus = "passed"; pr.verificationDetail = "Branch verified.";
        when(remediations.findByRepairJobIdOrderByCreatedAtDesc(job.id)).thenReturn(List.of(pr));
        var target = RepairStrategy.Target.finding("ASM-1", "F-CRED");

        when(github.mergeStatus(pr)).thenReturn(new com.relayaegis.dto.ApiDtos.MergeStatusDto("OPEN", false, "Open; RELAY never merges."));
        RepairProgressDto open = orchestrator.progress(target);
        assertEquals(RepairState.REPAIR_ATTEMPTED, open.state(), "an open pull request is not a repair");
        assertEquals("pending", step(open, "Human merge"));

        when(github.mergeStatus(pr)).thenReturn(new com.relayaegis.dto.ApiDtos.MergeStatusDto("MERGED", false, "Merged, but main still reports the finding."));
        assertEquals(RepairState.VERIFICATION_FAILED, orchestrator.progress(target).state());

        when(github.mergeStatus(pr)).thenReturn(new com.relayaegis.dto.ApiDtos.MergeStatusDto("CLOSED", false, "Closed without merging."));
        assertEquals(RepairState.MANUAL_ACTION_REQUIRED, orchestrator.progress(target).state());

        when(github.mergeStatus(pr)).thenReturn(new com.relayaegis.dto.ApiDtos.MergeStatusDto("MERGED", true, "Merged by a human; finding gone from main."));
        RepairProgressDto resolved = orchestrator.progress(target);
        assertEquals(RepairState.RESOLVED, resolved.state());
        assertEquals("passed", step(resolved, "Human merge"));
    }

    // ---- Runtime recovery strategy ---------------------------------------------------------------------------

    @Test
    void policyBlocksAreReportedAndNothingExecutes() {
        when(policy.decide(any(), any(), any(), anyBoolean())).thenReturn(RecoveryDecision.blocked(RecoveryActionType.START_CONTAINER, "target", null, null,
                "container-stopped", 0.98, List.of(), "Recovery actions against Production are disabled.", false));
        RepairProgressDto result = orchestrator.start(RepairStrategy.Target.incident("INC-1"), user);
        assertEquals(RepairState.BLOCKED, result.state());
        assertTrue(result.detail().contains("Production"));
        verify(recovery, never()).beginRecovery(anyString());
    }

    @Test
    void anAuthorizedRecoveryRunsThroughTheExistingEngine() {
        when(recovery.beginRecovery("INC-1")).thenAnswer(inv -> { incident.state = "action_taken"; incident.verificationStatus = "pending"; return null; });
        RepairProgressDto result = orchestrator.start(RepairStrategy.Target.incident("INC-1"), user);
        verify(recovery).beginRecovery("INC-1");
        assertEquals(RepairState.REPAIRING, result.state());
        assertEquals("running", step(result, "Health verification"));
    }

    @Test
    void theExistingEnginesRefusalAtExecutionIsReportedAsBlocked() {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "A recovery operation is already in progress for this incident.")).when(recovery).beginRecovery("INC-1");
        RepairProgressDto result = orchestrator.start(RepairStrategy.Target.incident("INC-1"), user);
        assertEquals(RepairState.BLOCKED, result.state());
        assertTrue(result.detail().contains("already in progress"));
    }

    @Test
    void resolvedOnlyAfterVerificationPassesAndAFailedVerificationIsARepairAttempt() {
        evidence.add(new EvidenceItemDto("e1", "recovery", "Operator recovery policy decision", "Authorized: START_CONTAINER on target", "s", Instant.now(), true));
        evidence.add(new EvidenceItemDto("e2", "recovery", "Recovery attempt 1: START_CONTAINER", "Started container target.", "s", Instant.now(), true));
        incident.verificationStatus = "failed"; incident.verificationDetail = "The container stopped again.";
        RepairProgressDto failed = orchestrator.progress(RepairStrategy.Target.incident("INC-1"));
        assertEquals(RepairState.VERIFICATION_FAILED, failed.state());
        assertEquals("Repair attempted · verification failed", failed.headline());
        assertEquals("passed", step(failed, "Recovery action executed"));
        assertEquals("failed", step(failed, "Health verification"));
        assertEquals("Started container target.", failed.targetModified());
        incident.verificationStatus = "passed"; incident.state = "recovered";
        RepairProgressDto resolved = orchestrator.progress(RepairStrategy.Target.incident("INC-1"));
        assertEquals(RepairState.RESOLVED, resolved.state());
        assertEquals("passed", step(resolved, "Health verification"));
        assertEquals(RepairState.RESOLVED, orchestrator.start(RepairStrategy.Target.incident("INC-1"), user).state(), "never restarted once resolved");
        verify(recovery, never()).beginRecovery(anyString());
    }

    @Test
    void nonMutatingDiagnosesNeedManualAction() throws Exception {
        incident.diagnosisJson = mapper.writeValueAsString(diagnosis("ESCALATE"));
        RepairProgressDto result = orchestrator.start(RepairStrategy.Target.incident("INC-1"), user);
        assertEquals(RepairState.MANUAL_ACTION_REQUIRED, result.state());
        verify(recovery, never()).beginRecovery(anyString());
    }
}

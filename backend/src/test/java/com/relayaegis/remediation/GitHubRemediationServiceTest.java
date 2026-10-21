package com.relayaegis.remediation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.relayaegis.assessment.GitHubRepositoryAnalyzer;
import com.relayaegis.assessment.GitHubRepositoryPolicy;
import com.relayaegis.auth.AuthenticatedUser;
import com.relayaegis.auth.Role;
import com.relayaegis.dto.ApiDtos.GitHubRemediationDto;
import com.relayaegis.dto.ApiDtos.GitHubRemediationViewDto;
import com.relayaegis.dto.ApiDtos.RepairCheckDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.entity.AssessmentEntity;
import com.relayaegis.entity.GitHubRemediationEntity;
import com.relayaegis.entity.RepairJobEntity;
import com.relayaegis.remediation.github.FakeGitHub;
import com.relayaegis.remediation.github.GitHubApiClient;
import com.relayaegis.repository.AssessmentRepository;
import com.relayaegis.repository.GitHubRemediationRepository;
import com.relayaegis.repository.RepairJobRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class GitHubRemediationServiceTest {
    static final String TOKEN = "github_pat_TEST_ONLY_must_never_leak_0123456789";
    static final String OWNER = "krieger-geist";
    static final String REPO = "relay-aegis-remediation-test";
    static final String URL = "https://github.com/" + OWNER + "/" + REPO;
    static final String FILE = "src/main/java/com/example/todo/Config.java";
    static final String SECRET = "RELAY_TEST_KEY_12345";
    static final String SOURCE = """
            package com.example.todo;

            public class Config {
                private static final String TEST_API_KEY = "RELAY_TEST_KEY_12345";
            }
            """;
    static final String COMMIT = "5b8e3640c1f4aaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Map<String, GitHubRemediationEntity> records = new LinkedHashMap<>();
    private FakeGitHub fake;
    private RepairJobEntity job;
    private AssessmentEntity assessment;
    private GitSourceFetcher fetcher;
    private final AuthenticatedUser admin = new AuthenticatedUser("USR-ADMIN", "admin@relay.test", "Admin", Role.ADMIN);
    private final AuthenticatedUser user = new AuthenticatedUser("USR-USER", "user@relay.test", "User", Role.USER);

    @BeforeEach
    void setUp() throws Exception {
        fake = new FakeGitHub(OWNER, REPO, FILE, SOURCE);
        assessment = new AssessmentEntity();
        assessment.setId("ASM-1");
        assessment.setStatus("completed");
        assessment.setRepositoryUrl(URL);
        assessment.setRepositoryCommit(COMMIT);
        assessment.setFindingsJson(mapper.writeValueAsString(List.of(
                new AssessmentFindingDto("F-0765a1f2", "secret-hygiene", "Possible hard-coded credential", "medium", "medium", FILE, 4,
                        "e", "x", "r", "unverified_candidate"))));
        job = validatedJob();
        fetcher = mock(GitSourceFetcher.class);
        when(fetcher.fetchFile(URL + ".git", COMMIT, FILE)).thenReturn(SOURCE);
    }

    @AfterEach
    void tearDown() { fake.close(); }

    private RepairJobEntity validatedJob() throws Exception {
        List<String> lines = PatchProposalService.splitLines(SOURCE);
        CredentialLiteralStrategy.Proposal proposal = CredentialLiteralStrategy.propose(FILE, lines, 4);
        RepairJobEntity entity = new RepairJobEntity();
        entity.id = "PATCH-1";
        entity.assessmentId = "ASM-1";
        entity.findingId = "F-0765a1f2";
        entity.findingTitle = "Possible hard-coded credential";
        entity.filePath = FILE;
        entity.strategy = CredentialLiteralStrategy.ID;
        entity.repository = URL;
        entity.applicationName = OWNER + "/" + REPO;
        entity.commitSha = COMMIT;
        entity.state = PatchProposalService.PROPOSED;
        entity.patchText = UnifiedDiff.compute(FILE, PatchProposalService.redact(lines, proposal.secretValue()),
                PatchProposalService.redact(proposal.newLines(), proposal.secretValue()), 3).patch();
        entity.verificationStatus = "passed";
        entity.verificationDetail = "Sandbox-validated.";
        entity.checksJson = mapper.writeValueAsString(List.of(new RepairCheckDto("Sandbox checkout", "passed", ""),
                new RepairCheckDto("Patch applied", "passed", ""), new RepairCheckDto("Build", "passed", ""),
                new RepairCheckDto("Tests", "passed", ""), new RepairCheckDto("Security re-scan", "passed", "")));
        entity.provenance = "live";
        entity.updatedAt = Instant.now();
        return entity;
    }

    private GitHubRemediationService service(String token, String repositories, String approvers) {
        RepairJobRepository repairs = mock(RepairJobRepository.class);
        when(repairs.findById(anyString())).thenAnswer(inv -> job.id.equals(inv.getArgument(0)) ? Optional.of(job) : Optional.empty());
        AssessmentRepository assessments = mock(AssessmentRepository.class);
        when(assessments.findById("ASM-1")).thenReturn(Optional.of(assessment));
        GitHubRemediationRepository remediations = mock(GitHubRemediationRepository.class);
        when(remediations.save(any())).thenAnswer(inv -> { GitHubRemediationEntity e = inv.getArgument(0); records.put(e.id, e); return e; });
        when(remediations.findByRepairJobIdOrderByCreatedAtDesc(anyString())).thenAnswer(inv -> records.values().stream()
                .filter(e -> e.repairJobId.equals(inv.getArgument(0))).sorted(Comparator.comparing((GitHubRemediationEntity e) -> e.createdAt).reversed()).toList());
        GitHubApiClient client = new GitHubApiClient(token, fake.baseUrl(), 10, mapper);
        return new GitHubRemediationService(repairs, assessments, remediations, fetcher, client,
                new GitHubRepositoryAnalyzer(mock(GitHubRepositoryPolicy.class)), mapper, repositories, approvers);
    }

    private GitHubRemediationService service() { return service(TOKEN, OWNER + "/" + REPO, ""); }

    private void assertNoGitHubWrites() {
        assertEquals(0, fake.count("POST", "") + fake.count("PUT", "") + fake.count("PATCH", "") + fake.count("DELETE", ""), fake.requests.toString());
    }

    // ---- scope and merge ----------------------------------------------------------------------------------------

    @Test
    void aBranchThatChangesAnyOtherFileFailsVerification() throws Exception {
        fake.sneakExtraFile = "README.md";
        GitHubRemediationDto result = service().apply("PATCH-1", admin, false);
        assertEquals(GitHubRemediationEntity.PR_CREATED, result.status());
        assertEquals("failed", result.verificationStatus(), result.verificationDetail());
        assertTrue(result.verificationDetail().contains("changes 2 file(s) instead of only " + FILE), result.verificationDetail());
    }

    @Test
    void anOpenPullRequestIsNotAMergedRepair() throws Exception {
        service().apply("PATCH-1", admin, false);
        var status = service().mergeStatus(records.values().iterator().next());
        assertEquals("OPEN", status.state());
        assertFalse(status.verifiedOnDefaultBranch());
        assertTrue(status.detail().contains("RELAY never merges"));
        assertNoMergeRequests();
    }

    @Test
    void aHumanMergeIsVerifiedByReScanningTheDefaultBranch() throws Exception {
        service().apply("PATCH-1", admin, false);
        fake.pulls.get(0).put("merged", true).put("state", "closed");
        fake.refs.put("main", fake.refs.get("relay-aegis/fix/F-0765a1f2")); // what a human merge does to main
        var status = service().mergeStatus(records.values().iterator().next());
        assertEquals("MERGED", status.state());
        assertTrue(status.verifiedOnDefaultBranch(), status.detail());
        assertNoMergeRequests();
    }

    @Test
    void aMergeThatDidNotRemoveTheFindingFromMainIsNotVerified() throws Exception {
        service().apply("PATCH-1", admin, false);
        fake.pulls.get(0).put("merged", true).put("state", "closed"); // main still holds the credential
        var status = service().mergeStatus(records.values().iterator().next());
        assertEquals("MERGED", status.state());
        assertFalse(status.verifiedOnDefaultBranch());
        assertTrue(status.detail().contains("still reports"), status.detail());
    }

    @Test
    void aPullRequestClosedWithoutMergingIsReportedAsSuch() throws Exception {
        service().apply("PATCH-1", admin, false);
        fake.pulls.get(0).put("state", "closed");
        assertEquals("CLOSED", service().mergeStatus(records.values().iterator().next()).state());
    }

    private void assertNoMergeRequests() {
        assertTrue(fake.requests.stream().noneMatch(r -> r.path().contains("/merge")), "RELAY never calls a merge endpoint");
    }

    // ---- successful workflow -----------------------------------------------------------------------------------

    @Test
    void createsBranchCommitsTheReviewedPatchOpensAPullRequestAndVerifies() throws Exception {
        String mainBefore = fake.refs.get("main");

        GitHubRemediationDto result = service().apply("PATCH-1", admin, false);

        assertEquals(GitHubRemediationEntity.PR_CREATED, result.status(), result.toString());
        assertEquals("relay-aegis/fix/F-0765a1f2", result.branch());
        assertEquals("main", result.baseBranch());
        assertEquals(1, result.prNumber());
        assertEquals("https://github.com/" + OWNER + "/" + REPO + "/pull/1", result.prUrl());
        assertTrue(result.commitSha().matches("[0-9a-f]{40}"));
        assertEquals("passed", result.verificationStatus(), result.verificationDetail());
        assertTrue(result.verificationDetail().contains("main is unchanged"));
        assertTrue(result.verificationDetail().contains("1 → 0"));
        // GitHub state: only the remediation branch changed, with exactly the reviewed change.
        assertEquals(mainBefore, fake.refs.get("main"), "the default branch is never written");
        assertEquals(SOURCE, fake.fileOnBranch("main", FILE));
        String patched = fake.fileOnBranch("relay-aegis/fix/F-0765a1f2", FILE);
        assertTrue(patched.contains("private static final String TEST_API_KEY = System.getenv(\"TEST_API_KEY\");"), patched);
        assertFalse(patched.contains(SECRET));
        assertEquals(1, fake.count("PUT", "/contents/"));
        assertTrue(fake.requests.stream().filter(r -> r.method().equals("PUT")).allMatch(r -> r.body().contains("\"branch\":\"relay-aegis/fix/F-0765a1f2\"")));
        assertTrue(fake.requests.stream().allMatch(r -> ("Bearer " + TOKEN).equals(r.authorization())));
        // Commit message and PR are informative and secret-free.
        String put = fake.requests.stream().filter(r -> r.method().equals("PUT")).findFirst().orElseThrow().body();
        assertTrue(put.contains("fix: remediate hard-coded credential detected by RELAY AEGIS"));
        assertTrue(put.contains("Finding: F-0765a1f2"));
        String prBody = fake.pulls.get(0).path("body").asText();
        assertEquals("fix: remediate hard-coded credential [RELAY AEGIS]", fake.pulls.get(0).path("title").asText());
        assertTrue(prBody.startsWith("## RELAY AEGIS Remediation"));
        assertTrue(prBody.contains("- Build: passed") && prBody.contains("- Security re-scan: passed") && prBody.contains("PATCH-1"));
        assertFalse(prBody.contains(SECRET) || put.contains(SECRET) || prBody.contains(TOKEN));
        assertEquals("main", fake.pulls.get(0).path("base").path("ref").asText());
        // Persisted audit record and the API response never expose the token.
        GitHubRemediationEntity stored = records.values().iterator().next();
        assertEquals("USR-ADMIN", stored.userId);
        assertEquals("F-0765a1f2", stored.findingId);
        assertEquals("ASM-1", stored.assessmentId);
        assertFalse(mapper.writeValueAsString(result).contains(TOKEN));
        assertFalse(mapper.writeValueAsString(stored).contains(TOKEN));
    }

    @Test
    void repeatingTheRequestReturnsTheExistingPullRequestWithoutNewWrites() {
        GitHubRemediationService service = service();
        GitHubRemediationDto first = service.apply("PATCH-1", admin, false);
        long writes = fake.count("POST", "") + fake.count("PUT", "");

        GitHubRemediationDto second = service.apply("PATCH-1", admin, false);

        assertEquals(first.id(), second.id());
        assertEquals(first.prUrl(), second.prUrl());
        assertEquals(writes, fake.count("POST", "") + fake.count("PUT", ""), "no second branch, commit or pull request");
        assertEquals(1, fake.pulls.size());
        assertFalse(service.view("PATCH-1", admin).available(), "an applied patch offers no Apply action");
    }

    @Test
    void anExistingRemediationBranchIsNeverOverwrittenAndASuffixIsUsed() {
        fake.refs.put("relay-aegis/fix/F-0765a1f2", fake.refs.get("main"));
        String untouched = fake.refs.get("relay-aegis/fix/F-0765a1f2");

        GitHubRemediationDto result = service().apply("PATCH-1", admin, false);

        assertEquals("relay-aegis/fix/F-0765a1f2-2", result.branch());
        assertEquals(untouched, fake.refs.get("relay-aegis/fix/F-0765a1f2"));
        assertEquals(GitHubRemediationEntity.PR_CREATED, result.status());
    }

    // ---- prerequisites: rejected before any GitHub write -------------------------------------------------------

    @Test
    void unvalidatedPatchesAreNotApplicable() {
        job.verificationStatus = "not_run";
        job.checksJson = "[{\"name\":\"Build\",\"status\":\"not_available\",\"detail\":\"\"}]";
        var ex = assertThrows(ResponseStatusException.class, () -> service().apply("PATCH-1", admin, false));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertTrue(ex.getReason().contains("sandbox-validated"));
        job.verificationStatus = "failed";
        assertThrows(ResponseStatusException.class, () -> service().apply("PATCH-1", admin, false));
        assertNoGitHubWrites();
        assertTrue(records.isEmpty());
    }

    @Test
    void missingGitHubConfigurationMakesApplyUnavailable() {
        GitHubRemediationService unconfigured = service("", OWNER + "/" + REPO, "");
        var ex = assertThrows(ResponseStatusException.class, () -> unconfigured.apply("PATCH-1", admin, false));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
        assertEquals("GitHub remediation is not configured.", ex.getReason());
        GitHubRemediationViewDto view = unconfigured.view("PATCH-1", admin);
        assertFalse(view.available());
        assertFalse(view.configured());
        assertTrue(fake.requests.isEmpty());
    }

    @Test
    void onlyAdminsOrConfiguredApproversMayApply() {
        var ex = assertThrows(ResponseStatusException.class, () -> service().apply("PATCH-1", user, false));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertFalse(service().view("PATCH-1", user).authorized());
        assertNoGitHubWrites();
        GitHubRemediationDto approved = service(TOKEN, OWNER + "/" + REPO, "USER@relay.test").apply("PATCH-1", user, false);
        assertEquals(GitHubRemediationEntity.PR_CREATED, approved.status());
    }

    @Test
    void repositoriesMustBeExplicitlyEnabled() {
        var ex = assertThrows(ResponseStatusException.class, () -> service(TOKEN, "someone/else", "").apply("PATCH-1", admin, false));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertTrue(ex.getReason().contains("not enabled for GitHub remediation"));
        assertNoGitHubWrites();
    }

    @Test
    void unsupportedOrMismatchedRepositoryUrlsAreRejected() {
        job.repository = "https://gitlab.com/krieger-geist/relay-aegis-remediation-test";
        assertThrows(ResponseStatusException.class, () -> service().apply("PATCH-1", admin, false));
        job.repository = "https://github.com/krieger-geist/other-repo";
        var ex = assertThrows(ResponseStatusException.class, () -> service(TOKEN, OWNER + "/other-repo", "").apply("PATCH-1", admin, false));
        assertTrue(ex.getReason().contains("does not belong to the repository and commit of its assessment"));
        assertNoGitHubWrites();
    }

    @Test
    void aPatchThatDoesNotBelongToItsFindingIsRejected() {
        job.findingId = "F-unknown";
        var ex = assertThrows(ResponseStatusException.class, () -> service().apply("PATCH-1", admin, false));
        assertTrue(ex.getReason().contains("does not belong to a finding"));
        job.findingId = "F-0765a1f2";
        job.filePath = "src/main/java/Other.java";
        assertThrows(ResponseStatusException.class, () -> service().apply("PATCH-1", admin, false));
        assertNoGitHubWrites();
    }

    @Test
    void preexistingTestFailuresRequireExplicitAcknowledgement() throws Exception {
        job.verificationStatus = "not_run";
        job.checksJson = mapper.writeValueAsString(List.of(new RepairCheckDto("Patch applied", "passed", ""), new RepairCheckDto("Build", "passed", ""),
                new RepairCheckDto("Tests", "failed", ""), new RepairCheckDto("Baseline tests", "failed", ""), new RepairCheckDto("Security re-scan", "passed", "")));
        GitHubRemediationViewDto view = service().view("PATCH-1", admin);
        assertTrue(view.available());
        assertTrue(view.requiresAcknowledgement());
        assertThrows(ResponseStatusException.class, () -> service().apply("PATCH-1", admin, false));
        assertNoGitHubWrites();

        GitHubRemediationDto result = service().apply("PATCH-1", admin, true);
        assertEquals(GitHubRemediationEntity.PR_CREATED, result.status());
        assertTrue(result.preexistingFailuresAcknowledged());
        assertTrue(fake.pulls.get(0).path("body").asText().contains("fail both with and without this patch"));
    }

    // ---- failures during the workflow --------------------------------------------------------------------------

    @Test
    void aFileChangedOnTheDefaultBranchAbortsBeforeAnyWrite() throws Exception {
        fake.commits.get(fake.refs.get("main")).put(FILE, (SOURCE + "// changed\n").getBytes());
        GitHubRemediationDto result = service().apply("PATCH-1", admin, false);
        assertEquals(GitHubRemediationEntity.FAILED, result.status());
        assertEquals("patch", result.failedStage());
        assertEquals("stale_patch", result.errorCode());
        assertNull(result.branch());
        assertNoGitHubWrites();
    }

    @Test
    void aPatchThatNoLongerMatchesTheReviewedDiffIsNotApplied() {
        job.patchText = job.patchText.replace("System.getenv", "System.getProperty");
        GitHubRemediationDto result = service().apply("PATCH-1", admin, false);
        assertEquals(GitHubRemediationEntity.FAILED, result.status());
        assertEquals("patch_failed", result.errorCode());
        assertTrue(result.errorMessage().contains("does not match the reviewed patch"));
        assertNoGitHubWrites();
    }

    @Test
    void invalidTokenAndMissingRepositoryFailSafelyWithoutLeakingTheToken() throws Exception {
        fake.failures.put("GET ", 401);
        GitHubRemediationDto invalid = service().apply("PATCH-1", admin, false);
        assertEquals("invalid_token", invalid.errorCode());
        assertFalse(mapper.writeValueAsString(invalid).contains(TOKEN));
        fake.failures.put("GET ", 404);
        GitHubRemediationDto missing = service().apply("PATCH-1", admin, false);
        assertEquals("not_found", missing.errorCode());
        fake.failures.clear();
        fake.canPush = false;
        GitHubRemediationDto readOnly = service().apply("PATCH-1", admin, false);
        assertEquals("insufficient_permissions", readOnly.errorCode());
        assertNoGitHubWrites();
    }

    @Test
    void commitFailureKeepsTheBranchRecordedAndCreatesNoPullRequest() {
        fake.failures.put("PUT /contents/" + FILE, 409);
        GitHubRemediationDto result = service().apply("PATCH-1", admin, false);
        assertEquals(GitHubRemediationEntity.FAILED, result.status());
        assertEquals("commit", result.failedStage());
        assertEquals("relay-aegis/fix/F-0765a1f2", result.branch());
        assertNull(result.commitSha());
        assertTrue(fake.pulls.isEmpty());
    }

    @Test
    void aPullRequestFailureAfterCommitIsRecordedAccuratelyAndRetriedWithoutASecondCommit() {
        GitHubRemediationService service = service();
        fake.failures.put("POST /pulls", 500);

        GitHubRemediationDto failed = service.apply("PATCH-1", admin, false);

        assertEquals(GitHubRemediationEntity.FAILED, failed.status());
        assertEquals("pull_request", failed.failedStage());
        assertNotNull(failed.commitSha(), "the commit that did happen is preserved");
        assertNull(failed.prNumber(), "PR_CREATED is never claimed without GitHub confirming the pull request");

        fake.failures.clear();
        GitHubRemediationDto retried = service.apply("PATCH-1", admin, false);

        assertEquals(failed.id(), retried.id());
        assertEquals(GitHubRemediationEntity.PR_CREATED, retried.status());
        assertEquals(failed.commitSha(), retried.commitSha());
        assertEquals(1, fake.count("PUT", "/contents/"), "the patch is committed exactly once");
        assertEquals(1, fake.pulls.size());
    }

    /** Security review regression: a failed post-PR check must not mark the attempt FAILED and invite a duplicate PR. */
    @Test
    void aVerificationErrorAfterThePullRequestNeverLeadsToADuplicate() {
        GitHubRemediationService service = service();
        fake.failuresAfterPullRequest.put("GET /contents/" + FILE, 502);

        GitHubRemediationDto result = service.apply("PATCH-1", admin, false);

        assertEquals(GitHubRemediationEntity.PR_CREATED, result.status());
        assertEquals("not_run", result.verificationStatus());
        assertTrue(result.verificationDetail().startsWith("Post-PR verification could not complete"));
        fake.failuresAfterPullRequest.clear();
        assertEquals(result.id(), service.apply("PATCH-1", admin, false).id());
        assertEquals(1, fake.pulls.size());
        assertEquals(1, fake.count("POST", "/git/refs"));
    }

    @Test
    void timeoutsAndNetworkErrorsAreReportedAsSafeMessages() {
        GitHubApiClient unreachable = new GitHubApiClient(TOKEN, "http://127.0.0.1:1", 5, mapper);
        var ex = assertThrows(com.relayaegis.remediation.github.GitHubApiException.class,
                () -> unreachable.repository(new com.relayaegis.remediation.github.GitHubRepoRef(OWNER, REPO)));
        assertEquals("network_error", ex.code());
        assertFalse(ex.getMessage().contains(TOKEN));
    }

    @Test
    void viewDescribesThePlanForAnApplicablePatch() {
        GitHubRemediationViewDto view = service().view("PATCH-1", admin);
        assertTrue(view.available(), view.blockedReasons().toString());
        assertEquals("passed", view.validation());
        assertEquals(OWNER + "/" + REPO, view.repository());
        assertEquals("relay-aegis/fix/F-0765a1f2", view.plannedBranch());
        assertEquals("main", view.targetBranch());
        assertNull(view.latest());
        assertNoGitHubWrites();
    }

    @Test
    void textNeverContainsTheSecretValue() {
        assertFalse(GitHubRemediationService.commitMessage(job).contains(SECRET));
        assertEquals("hard-coded credential", GitHubRemediationService.shortTitle("Possible hard-coded credential"));
        assertEquals(new ArrayList<>(), new ArrayList<>(fake.pulls));
    }
}

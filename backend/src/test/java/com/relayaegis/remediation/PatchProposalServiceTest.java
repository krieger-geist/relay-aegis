package com.relayaegis.remediation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.dto.ApiDtos.RepairJobDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.entity.AssessmentEntity;
import com.relayaegis.entity.RepairJobEntity;
import com.relayaegis.repository.AssessmentRepository;
import com.relayaegis.repository.RepairJobRepository;
import com.relayaegis.service.RepairJobService;
import com.relayaegis.util.JsonSupport;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PatchProposalServiceTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String SOURCE = """
            class Config {
                String apiKey = "notasecret";
                String password = "Sup3rS3cretValue";
            }
            """;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, RepairJobEntity> stored = new LinkedHashMap<>();
    private AssessmentEntity assessment;
    private GitSourceFetcher fetcher;
    private PatchProposalService service;
    private AssessmentRepository assessments;
    private RepairJobRepository repairs;
    private static final String DOCKERFILE = """
            FROM node:20-alpine
            WORKDIR /app
            COPY . .
            RUN npm ci --omit=dev
            CMD ["node", "server.js"]
            """;

    @Test
    void proposesAReviewableNonRootPatchForADockerfileFinding() throws Exception {
        assertTrue(PatchProposalService.isPatchable(assessment, CredentialLiteralStrategyTest.finding("secret-hygiene", "src/Config.java", 3)));
        RepairJobEntity saved = stored.get(service.propose("ASM-1", "F-3").id());

        assertEquals("candidate_patch_created", saved.state);
        assertEquals(DockerfileNonRootStrategy.ID, saved.strategy);
        assertTrue(saved.patchText.contains("+USER node"), saved.patchText);
        assertTrue(saved.patchText.contains("--- a/Dockerfile"), saved.patchText);
        assertTrue(saved.checksJson.contains("\"Image build\",\"status\":\"not_run\""));
        assertFalse(saved.checksJson.contains("Credential rotation"), "follow-up checks belong to the strategy");
        assertEquals("not_run", saved.verificationStatus, "sandbox disabled: never reported as validated");
    }

    /** Finding -> Propose patch -> diff -> sandbox (build, tests, security re-scan) -> result; nothing is applied. */
    @Test
    void sandboxValidatesTheDockerfilePatchEndToEndWithoutApplyingIt() throws Exception {
        org.mockito.Mockito.doAnswer(inv -> {
            java.nio.file.Path target = inv.getArgument(2);
            java.nio.file.Files.writeString(target.resolve("Dockerfile"), DOCKERFILE);
            java.nio.file.Files.writeString(target.resolve("package.json"), "{\"name\":\"app\",\"scripts\":{\"test\":\"node -e 0\"}}");
            return null;
        }).when(fetcher).checkoutCommit(anyString(), anyString(), any());
        com.relayaegis.remediation.sandbox.FakeSandboxRunner runner = new com.relayaegis.remediation.sandbox.FakeSandboxRunner(true);
        runner.observedPath = "Dockerfile";
        PatchProposalService withSandbox = new PatchProposalService(assessments, repairs, new RepairJobService(repairs, new JsonSupport(mapper)), fetcher, mapper, Runnable::run,
                new com.relayaegis.remediation.sandbox.SandboxValidationService(runner, fetcher,
                        new com.relayaegis.assessment.GitHubRepositoryAnalyzer(mock(com.relayaegis.assessment.GitHubRepositoryPolicy.class)), 60));

        RepairJobEntity saved = stored.get(withSandbox.propose("ASM-1", "F-3").id());

        assertEquals(List.of("NPM:BUILD", "NPM:TEST"), runner.calls);
        assertTrue(runner.observedFile.contains("USER node"), "the sandbox built the patched copy");
        assertEquals("passed", saved.verificationStatus, saved.checksJson);
        assertTrue(saved.checksJson.contains("\"Security re-scan\",\"status\":\"passed\""));
        assertTrue(saved.verificationDetail.contains("Review it before applying"), "validation never applies the patch");
        verify(fetcher, org.mockito.Mockito.never()).fetchFile(anyString(), anyString(), org.mockito.ArgumentMatchers.eq("package.json"));
    }

    /** Regression: proposals made while the sandbox was off were returned forever, even after enabling the sandbox. */
    @Test
    void aProposalNeverValidatedIsRegeneratedOnceTheSandboxIsAvailable() throws Exception {
        String stale = service.propose("ASM-1", "F-3").id();
        assertEquals("not_run", stored.get(stale).verificationStatus);
        assertEquals(stale, service.propose("ASM-1", "F-3").id(), "sandbox still off: the existing proposal is returned");

        org.mockito.Mockito.doAnswer(inv -> {
            java.nio.file.Path target = inv.getArgument(2);
            java.nio.file.Files.writeString(target.resolve("Dockerfile"), DOCKERFILE);
            java.nio.file.Files.writeString(target.resolve("package.json"), "{\"name\":\"app\"}");
            return null;
        }).when(fetcher).checkoutCommit(anyString(), anyString(), any());
        PatchProposalService enabled = new PatchProposalService(assessments, repairs, new RepairJobService(repairs, new JsonSupport(mapper)), fetcher, mapper, Runnable::run,
                new com.relayaegis.remediation.sandbox.SandboxValidationService(new com.relayaegis.remediation.sandbox.FakeSandboxRunner(true), fetcher,
                        new com.relayaegis.assessment.GitHubRepositoryAnalyzer(mock(com.relayaegis.assessment.GitHubRepositoryPolicy.class)), 60));

        String fresh = enabled.propose("ASM-1", "F-3").id();

        assertFalse(fresh.equals(stale), "a new proposal is generated instead of the stale one");
        assertEquals("passed", stored.get(fresh).verificationStatus);
        assertEquals("not_run", stored.get(stale).verificationStatus, "the earlier record is kept unchanged");
        assertEquals(fresh, enabled.propose("ASM-1", "F-3").id(), "a validated proposal is returned, not regenerated");
    }

    /** Live regression: a validation that "failed" only because the sandbox could not run was returned forever. */
    @Test
    void aValidationTheSandboxCouldNotRunIsRegeneratedButARealFailureIsKept() throws Exception {
        String stale = service.propose("ASM-1", "F-3").id();
        RepairJobEntity environmentFailure = stored.get(stale);
        environmentFailure.verificationStatus = "failed";
        environmentFailure.checksJson = "[{\"name\":\"Build\",\"status\":\"not_available\",\"detail\":\"The sandbox container could not be created.\"}]";
        org.mockito.Mockito.doAnswer(inv -> {
            java.nio.file.Path target = inv.getArgument(2);
            java.nio.file.Files.writeString(target.resolve("Dockerfile"), DOCKERFILE);
            java.nio.file.Files.writeString(target.resolve("package.json"), "{\"name\":\"app\"}");
            return null;
        }).when(fetcher).checkoutCommit(anyString(), anyString(), any());
        com.relayaegis.remediation.sandbox.FakeSandboxRunner runner = new com.relayaegis.remediation.sandbox.FakeSandboxRunner(true)
                .result(com.relayaegis.remediation.sandbox.SandboxRunner.Phase.TEST,
                        new com.relayaegis.remediation.sandbox.SandboxRunner.PhaseResult("failed", 1, "1 test failed", 10))
                .baselineTest(new com.relayaegis.remediation.sandbox.SandboxRunner.PhaseResult("passed", 0, "", 10));
        PatchProposalService enabled = new PatchProposalService(assessments, repairs, new RepairJobService(repairs, new JsonSupport(mapper)), fetcher, mapper, Runnable::run,
                new com.relayaegis.remediation.sandbox.SandboxValidationService(runner, fetcher,
                        new com.relayaegis.assessment.GitHubRepositoryAnalyzer(mock(com.relayaegis.assessment.GitHubRepositoryPolicy.class)), 60));

        String fresh = enabled.propose("ASM-1", "F-3").id();

        assertFalse(fresh.equals(stale), "the environment failure is re-validated");
        assertEquals("failed", stored.get(fresh).verificationStatus, "the tests really failed this time");
        assertEquals(fresh, enabled.propose("ASM-1", "F-3").id(), "a genuine test failure is returned, not regenerated in a loop");
    }

    /** Live regression: a test failure recorded before the baseline check existed was returned forever, unclassified. */
    @Test
    void aTestFailureRecordedWithoutABaselineIsRevalidatedOnce() throws Exception {
        String legacy = service.propose("ASM-1", "F-3").id();
        RepairJobEntity old = stored.get(legacy);
        old.verificationStatus = "failed";
        old.checksJson = "[{\"name\":\"Build\",\"status\":\"passed\",\"detail\":\"ok\"},{\"name\":\"Tests\",\"status\":\"failed\",\"detail\":\"Errors: 1\"}]";
        org.mockito.Mockito.doAnswer(inv -> {
            java.nio.file.Path target = inv.getArgument(2);
            java.nio.file.Files.writeString(target.resolve("Dockerfile"), DOCKERFILE);
            java.nio.file.Files.writeString(target.resolve("package.json"), "{\"name\":\"app\"}");
            return null;
        }).when(fetcher).checkoutCommit(anyString(), anyString(), any());
        com.relayaegis.remediation.sandbox.FakeSandboxRunner runner = new com.relayaegis.remediation.sandbox.FakeSandboxRunner(true)
                .result(com.relayaegis.remediation.sandbox.SandboxRunner.Phase.TEST,
                        new com.relayaegis.remediation.sandbox.SandboxRunner.PhaseResult("failed", 1, "Connection to localhost:5432 refused", 10));
        PatchProposalService enabled = new PatchProposalService(assessments, repairs, new RepairJobService(repairs, new JsonSupport(mapper)), fetcher, mapper, Runnable::run,
                new com.relayaegis.remediation.sandbox.SandboxValidationService(runner, fetcher,
                        new com.relayaegis.assessment.GitHubRepositoryAnalyzer(mock(com.relayaegis.assessment.GitHubRepositoryPolicy.class)), 60));

        String fresh = enabled.propose("ASM-1", "F-3").id();

        assertFalse(fresh.equals(legacy), "the unclassified legacy failure is re-validated with the baseline check");
        assertTrue(stored.get(fresh).checksJson.contains("\"name\":\"Baseline tests\",\"status\":\"failed\""), stored.get(fresh).checksJson);
        assertEquals("not_run", stored.get(fresh).verificationStatus, "pre-existing failure: not validated, not blamed on the patch");
        assertEquals(fresh, enabled.propose("ASM-1", "F-3").id(), "no loop: the classified record is returned");
    }

    /** Live regression: tests that need an external database failed in the sandbox and were reported as a patch failure. */
    @Test
    void testsThatAlsoFailWithoutThePatchAreNotBlamedOnItAndNotRevalidatedInALoop() throws Exception {
        org.mockito.Mockito.doAnswer(inv -> {
            java.nio.file.Path target = inv.getArgument(2);
            java.nio.file.Files.writeString(target.resolve("Dockerfile"), DOCKERFILE);
            java.nio.file.Files.writeString(target.resolve("package.json"), "{\"name\":\"app\"}");
            return null;
        }).when(fetcher).checkoutCommit(anyString(), anyString(), any());
        com.relayaegis.remediation.sandbox.FakeSandboxRunner runner = new com.relayaegis.remediation.sandbox.FakeSandboxRunner(true)
                .result(com.relayaegis.remediation.sandbox.SandboxRunner.Phase.TEST,
                        new com.relayaegis.remediation.sandbox.SandboxRunner.PhaseResult("failed", 1, "Connection to localhost:5432 refused", 10));
        PatchProposalService enabled = new PatchProposalService(assessments, repairs, new RepairJobService(repairs, new JsonSupport(mapper)), fetcher, mapper, Runnable::run,
                new com.relayaegis.remediation.sandbox.SandboxValidationService(runner, fetcher,
                        new com.relayaegis.assessment.GitHubRepositoryAnalyzer(mock(com.relayaegis.assessment.GitHubRepositoryPolicy.class)), 60));

        String id = enabled.propose("ASM-1", "F-3").id();
        RepairJobEntity saved = stored.get(id);

        assertEquals("not_run", saved.verificationStatus, saved.checksJson);
        assertTrue(saved.checksJson.contains("\"Baseline tests\",\"status\":\"failed\""), saved.checksJson);
        assertTrue(saved.verificationDetail.contains("also fail without the patch"), saved.verificationDetail);
        assertEquals(List.of("NPM:BUILD", "NPM:TEST", "NPM:TEST"), runner.calls);
        assertEquals(id, enabled.propose("ASM-1", "F-3").id(), "a known baseline failure is returned, not re-validated on every request");
        assertEquals(3, runner.calls.size());
    }

    @Test
    void unsupportedFindingsAreExplicitlyManual() {
        var manual = assertThrows(ResponseStatusException.class, () -> service.propose("ASM-1", "F-2"));
        assertTrue(manual.getReason().contains("manual remediation"));
        assertTrue(manual.getReason().contains("Dockerfiles whose container runs as root"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"url", "combined"})
    void liveUrlInvestigationsAreReportsAndNeverProposeCodeChanges(String kind) {
        assessment.setKind(kind);
        var refused = assertThrows(ResponseStatusException.class, () -> service.propose("ASM-1", "F-1"));
        assertTrue(refused.getReason().contains("never change code"));
        assertFalse(PatchProposalService.isPatchable(assessment, new AssessmentFindingDto("F-1", "secret-hygiene", "Possible hard-coded credential",
                "medium", "medium", "src/A.java", 3, "e", "x", "r", "unverified_candidate")));
    }

    @BeforeEach
    void setUp() throws Exception {
        assessment = new AssessmentEntity();
        assessment.setId("ASM-1");
        assessment.setStatus("completed");
        assessment.setKind("repository"); assessment.setRepositoryUrl("https://github.com/owner/repo");
        assessment.setRepositoryCommit(SHA);
        assessment.setFindingsJson(mapper.writeValueAsString(List.of(
                CredentialLiteralStrategyTest.finding("secret-hygiene", "src/Config.java", 3),
                new AssessmentFindingDto("F-2", "database-safety", "SQL", "medium", "medium", "src/Dao.java", 9, "e", "x", "r", "unverified_candidate"),
                new AssessmentFindingDto("F-3", "security", "Container runs as root", "medium", "medium", "Dockerfile", 1, "e", "x", "r", "unverified_candidate"))));
        AssessmentRepository assessments = mock(AssessmentRepository.class);
        when(assessments.findById("ASM-1")).thenReturn(Optional.of(assessment));

        RepairJobRepository repairs = mock(RepairJobRepository.class);
        when(repairs.save(any())).thenAnswer(inv -> { RepairJobEntity job = inv.getArgument(0); stored.put(job.id, job); return job; });
        when(repairs.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(stored.get(inv.getArgument(0))));
        when(repairs.findByAssessmentIdAndFindingIdOrderByUpdatedAtDesc(anyString(), anyString())).thenAnswer(inv -> stored.values().stream()
                .filter(j -> inv.getArgument(0).equals(j.assessmentId) && inv.getArgument(1).equals(j.findingId))
                .sorted(Comparator.comparing((RepairJobEntity j) -> j.updatedAt).reversed()).toList());

        fetcher = mock(GitSourceFetcher.class);
        when(fetcher.fetchFile("https://github.com/owner/repo.git", SHA, "src/Config.java")).thenReturn(SOURCE);
        when(fetcher.fetchFile("https://github.com/owner/repo.git", SHA, "Dockerfile")).thenReturn(DOCKERFILE);
        this.assessments = assessments; this.repairs = repairs;
        service = new PatchProposalService(assessments, repairs, new RepairJobService(repairs, new JsonSupport(mapper)), fetcher, mapper, Runnable::run,
                new com.relayaegis.remediation.sandbox.SandboxValidationService(new com.relayaegis.remediation.sandbox.FakeSandboxRunner(false), fetcher,
                        new com.relayaegis.assessment.GitHubRepositoryAnalyzer(mock(com.relayaegis.assessment.GitHubRepositoryPolicy.class)), 60));
    }

    @Test
    void generatesAnUnvalidatedRedactedPatchLinkedToTheFinding() {
        RepairJobDto job = service.propose("ASM-1", "F-1");

        RepairJobEntity saved = stored.get(job.id());
        assertEquals("candidate_patch_created", saved.state);
        assertEquals("ASM-1", saved.assessmentId);
        assertEquals("F-1", saved.findingId);
        assertEquals("src/Config.java", saved.filePath);
        assertNull(saved.incidentId);
        assertTrue(saved.patchText.contains("+    String password = System.getenv(\"PASSWORD\");"), saved.patchText);
        assertTrue(saved.patchText.contains("-    String password = \"[REDACTED]\";"), saved.patchText);
        assertFalse(saved.patchText.contains("Sup3rS3cretValue"));
        assertFalse(saved.patchText.contains("notasecret"), "credential literals in context lines are redacted too");
        assertEquals("not_run", saved.verificationStatus);
        assertTrue(saved.verificationDetail.startsWith("Unvalidated"));
        assertTrue(saved.checksJson.contains("\"Build\",\"status\":\"not_available\""));
        assertTrue(saved.checksJson.contains("\"Tests\",\"status\":\"not_available\""));
        assertTrue(saved.checksJson.contains("\"name\":\"Change scope\",\"status\":\"passed\""), saved.checksJson);
        assertTrue(saved.checksJson.contains("1 file (src/Config.java), +1 −1 lines, within the 2-line limit"), saved.checksJson);
    }

    @Test
    void everyStrategyDeclaresATightChangeBudget() {
        assertEquals(2, PatchStrategies.byId("credential-literal-to-env").orElseThrow().maxChangedLines());
        assertEquals(4, PatchStrategies.byId("dockerfile-non-root-user").orElseThrow().maxChangedLines());
    }

    @Test
    void returnsTheExistingProposalInsteadOfGeneratingADuplicate() throws Exception {
        String first = service.propose("ASM-1", "F-1").id();
        String second = service.propose("ASM-1", "F-1").id();

        assertEquals(first, second);
        verify(fetcher, times(1)).fetchFile(anyString(), anyString(), anyString());
    }

    @Test
    void recordsAnExplicitReasonWhenTheSourceCannotBeFetched() throws Exception {
        when(fetcher.fetchFile(anyString(), anyString(), anyString()))
                .thenThrow(new RemediationException("The recorded commit could not be fetched."));

        RepairJobEntity saved = stored.get(service.propose("ASM-1", "F-1").id());

        assertEquals("patch_failed", saved.state);
        assertEquals("The recorded commit could not be fetched.", saved.explanation);
        assertNull(saved.patchText);
    }

    @Test
    void refusesUnsupportedFindingsAndIncompleteAssessments() {
        var unsupported = assertThrows(ResponseStatusException.class, () -> service.propose("ASM-1", "F-2"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, unsupported.getStatusCode());

        assessment.setStatus("failed");
        var incomplete = assertThrows(ResponseStatusException.class, () -> service.propose("ASM-1", "F-1"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, incomplete.getStatusCode());

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> service.propose("ASM-1", "F-404")).getStatusCode());
        assertTrue(stored.isEmpty());
    }
}

package com.relayaegis.remediation.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.relayaegis.assessment.GitHubRepositoryAnalyzer;
import com.relayaegis.assessment.GitHubRepositoryPolicy;
import com.relayaegis.dto.ApiDtos.RepairCheckDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.remediation.CredentialLiteralStrategy;
import com.relayaegis.remediation.GitSourceFetcher;
import com.relayaegis.remediation.RemediationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SandboxValidationServiceTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String FILE = "src/main/java/Config.java";
    private static final List<String> ORIGINAL = List.of("class Config {", "    String password = \"Sup3rS3cretValue\";", "}");
    private final AssessmentFindingDto finding = new AssessmentFindingDto("F-1", "secret-hygiene", "Possible hard-coded credential", "medium", "medium",
            FILE, 2, "e", "x", "r", "unverified_candidate");
    private GitSourceFetcher fetcher;
    private final GitHubRepositoryAnalyzer analyzer = new GitHubRepositoryAnalyzer(mock(GitHubRepositoryPolicy.class));
    private List<String> patched;
    private Path lastWorkspace;

    @BeforeEach
    void setUp() throws Exception {
        fetcher = mock(GitSourceFetcher.class);
        // Simulates a checkout of the assessed commit: a Maven project containing the vulnerable file.
        doAnswer(inv -> {
            Path target = inv.getArgument(2);
            lastWorkspace = target;
            Files.createDirectories(target.resolve("src/main/java"));
            Files.writeString(target.resolve("pom.xml"), "<project><artifactId>demo</artifactId></project>");
            Files.writeString(target.resolve(FILE), String.join("\n", ORIGINAL) + "\n");
            return null;
        }).when(fetcher).checkoutCommit(anyString(), anyString(), any());
        patched = CredentialLiteralStrategy.propose(FILE, ORIGINAL, 2).newLines();
    }

    private SandboxValidationService service(FakeSandboxRunner runner) {
        return new SandboxValidationService(runner, fetcher, analyzer, 60);
    }

    private static String status(List<RepairCheckDto> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst().map(RepairCheckDto::status).orElse("absent");
    }

    @Test
    void verifiesOnlyWhenPatchBuildTestsAndSecurityRescanAllPass() {
        FakeSandboxRunner runner = new FakeSandboxRunner(true);
        runner.observedPath = FILE;

        SandboxValidationService.Outcome outcome = service(runner).validate("https://github.com/o/r.git", SHA, finding, ORIGINAL, patched);

        assertEquals("passed", outcome.verificationStatus());
        assertEquals(List.of("MAVEN:BUILD", "MAVEN:TEST"), runner.calls);
        assertFalse(runner.observedFile.contains("Sup3rS3cretValue"), "the build must see the patched file");
        assertEquals("passed", status(outcome.checks(), "Patch applied"));
        assertEquals("passed", status(outcome.checks(), "Security re-scan"));
        assertFalse(Files.exists(lastWorkspace), "the sandbox workspace is deleted afterwards");
    }

    @Test
    void aBuildThePatchBreaksFailsValidationAndIsAttributedToThePatch() {
        FakeSandboxRunner runner = new FakeSandboxRunner(true).result(SandboxRunner.Phase.BUILD,
                new SandboxRunner.PhaseResult("failed", 1, "[ERROR] cannot find symbol", 10)).baselineBuild(new SandboxRunner.PhaseResult("passed", 0, "", 10));

        SandboxValidationService.Outcome outcome = service(runner).validate("u", SHA, finding, ORIGINAL, patched);

        assertEquals("failed", outcome.verificationStatus());
        assertEquals(List.of("MAVEN:BUILD", "MAVEN:BUILD"), runner.calls, "the unpatched commit is built once to attribute the failure");
        assertEquals("failed", status(outcome.checks(), "Build"));
        assertEquals("passed", status(outcome.checks(), "Baseline build"));
        assertEquals("not_run", status(outcome.checks(), "Tests"));
        assertTrue(outcome.verificationDetail().contains("build failed"));
    }

    @Test
    void aBuildThatAlsoFailsWithoutThePatchIsAPreexistingFailureAndStillBlocksPublication() {
        FakeSandboxRunner runner = new FakeSandboxRunner(true).result(SandboxRunner.Phase.BUILD,
                new SandboxRunner.PhaseResult("failed", 1, "[ERROR] interface abstract methods cannot have body", 10));

        SandboxValidationService.Outcome outcome = service(runner).validate("u", SHA, finding, ORIGINAL, patched);

        assertEquals("failed", outcome.verificationStatus(), "never validated, never acknowledgeable");
        assertEquals("failed", status(outcome.checks(), "Build"));
        assertEquals("failed", status(outcome.checks(), "Baseline build"));
        assertEquals("not_run", status(outcome.checks(), "Tests"));
        assertTrue(outcome.verificationDetail().contains("fails on the unpatched commit as well"));
        assertTrue(outcome.verificationDetail().contains("not caused by the patch"));
        assertTrue(outcome.verificationDetail().contains("The repository was not modified."));
    }

    @Test
    void failedTestsFailValidationEvenThoughCommandsRan() {
        FakeSandboxRunner runner = new FakeSandboxRunner(true).result(SandboxRunner.Phase.TEST,
                new SandboxRunner.PhaseResult("failed", 1, "Tests run: 3, Failures: 1", 10))
                .baselineTest(new SandboxRunner.PhaseResult("passed", 0, "", 10));

        SandboxValidationService.Outcome outcome = service(runner).validate("u", SHA, finding, ORIGINAL, patched);

        assertEquals("failed", outcome.verificationStatus());
        assertEquals("failed", status(outcome.checks(), "Tests"));
        assertTrue(outcome.verificationDetail().contains("tests failed"));
    }

    @Test
    void testFailureCausedByThePatchStandsWhenTheBaselinePasses() {
        FakeSandboxRunner runner = new FakeSandboxRunner(true)
                .result(SandboxRunner.Phase.TEST, new SandboxRunner.PhaseResult("failed", 1, "Tests run: 3, Failures: 1", 10))
                .baselineTest(new SandboxRunner.PhaseResult("passed", 0, "", 10));
        runner.observedPath = FILE;

        SandboxValidationService.Outcome outcome = service(runner).validate("u", SHA, finding, ORIGINAL, patched);

        assertEquals("failed", outcome.verificationStatus());
        assertEquals(List.of("MAVEN:BUILD", "MAVEN:TEST", "MAVEN:TEST"), runner.calls);
        assertFalse(runner.observedTestFiles.get(0).contains("Sup3rS3cretValue"), "the first test run sees the patched file");
        assertTrue(runner.observedTestFiles.get(1).contains("Sup3rS3cretValue"), "the baseline test run sees the unpatched file");
        assertEquals("failed", status(outcome.checks(), "Tests"));
        assertEquals("passed", status(outcome.checks(), "Baseline tests"));
        assertTrue(outcome.verificationDetail().contains("tests failed"));
    }

    /** Live regression: a @SpringBootTest needing PostgreSQL failed in the sandbox and was blamed on the patch. */
    @Test
    void testsThatAlsoFailWithoutThePatchLeaveItUnvalidatedNotFailed() {
        FakeSandboxRunner runner = new FakeSandboxRunner(true)
                .result(SandboxRunner.Phase.TEST, new SandboxRunner.PhaseResult("failed", 1, "Connection to localhost:5432 refused", 10));

        SandboxValidationService.Outcome outcome = service(runner).validate("u", SHA, finding, ORIGINAL, patched);

        assertEquals("not_run", outcome.verificationStatus(), "never validated, but not a patch failure either");
        assertEquals(List.of("MAVEN:BUILD", "MAVEN:TEST", "MAVEN:TEST"), runner.calls);
        assertEquals("failed", status(outcome.checks(), "Tests"));
        assertEquals("failed", status(outcome.checks(), "Baseline tests"));
        assertEquals("passed", status(outcome.checks(), "Security re-scan"));
        assertTrue(outcome.verificationDetail().contains("also fail without the patch"), outcome.verificationDetail());
        assertFalse(outcome.verificationDetail().contains("tests failed"));
        assertFalse(Files.exists(lastWorkspace), "the baseline workspace is deleted afterwards");
    }

    @Test
    void passingTestsNeedNoBaselineRun() {
        FakeSandboxRunner runner = new FakeSandboxRunner(true);
        service(runner).validate("u", SHA, finding, ORIGINAL, patched);
        assertEquals(List.of("MAVEN:BUILD", "MAVEN:TEST"), runner.calls);
    }

    /** Live regression: a missing sandbox image was reported as "build failed" although nothing was built. */
    @Test
    void aSandboxThatCannotRunThePhaseLeavesThePatchUnvalidatedNotFailed() {
        FakeSandboxRunner runner = new FakeSandboxRunner(true).result(SandboxRunner.Phase.BUILD,
                new SandboxRunner.PhaseResult("not_available", -1, "The sandbox container could not be created (image missing or Docker unavailable).", 60_000));

        SandboxValidationService.Outcome outcome = service(runner).validate("u", SHA, finding, ORIGINAL, patched);

        assertEquals("not_run", outcome.verificationStatus());
        assertTrue(outcome.verificationDetail().startsWith("Not validated: the sandbox could not run the build"), outcome.verificationDetail());
        assertEquals("not_available", status(outcome.checks(), "Build"));
        assertEquals("not_run", status(outcome.checks(), "Tests"));
        assertEquals(List.of("MAVEN:BUILD"), runner.calls);
    }

    @Test
    void timedOutPhaseIsAFailure() {
        FakeSandboxRunner runner = new FakeSandboxRunner(true).result(SandboxRunner.Phase.TEST,
                new SandboxRunner.PhaseResult("timed_out", -1, "", 600_000))
                .baselineTest(new SandboxRunner.PhaseResult("passed", 0, "", 10));
        assertEquals("failed", service(runner).validate("u", SHA, finding, ORIGINAL, patched).verificationStatus());
    }

    @Test
    void securityRescanFailsWhenThePatchDoesNotRemoveTheFinding() {
        FakeSandboxRunner runner = new FakeSandboxRunner(true);

        SandboxValidationService.Outcome outcome = service(runner).validate("u", SHA, finding, ORIGINAL, ORIGINAL);

        assertEquals("failed", outcome.verificationStatus());
        assertEquals("failed", status(outcome.checks(), "Security re-scan"));
    }

    @Test
    void refusesToApplyAPatchToDifferentContent() {
        FakeSandboxRunner runner = new FakeSandboxRunner(true);

        SandboxValidationService.Outcome outcome = service(runner).validate("u", SHA, finding, List.of("other"), patched);

        assertEquals("failed", outcome.verificationStatus());
        assertEquals("failed", status(outcome.checks(), "Patch applied"));
        assertTrue(runner.calls.isEmpty());
    }

    @Test
    void checkoutFailureIsReportedAndNothingRuns() throws Exception {
        doThrow(new RemediationException("The recorded commit could not be fetched for sandbox validation."))
                .when(fetcher).checkoutCommit(anyString(), anyString(), any());
        FakeSandboxRunner runner = new FakeSandboxRunner(true);

        SandboxValidationService.Outcome outcome = service(runner).validate("u", SHA, finding, ORIGINAL, patched);

        assertEquals("failed", outcome.verificationStatus());
        assertTrue(runner.calls.isEmpty());
    }

    @Test
    void disabledSandboxLeavesThePatchUnvalidatedAndNeverChecksOut() throws Exception {
        SandboxValidationService.Outcome outcome = service(new FakeSandboxRunner(false)).validate("u", SHA, finding, ORIGINAL, patched);

        assertEquals("not_run", outcome.verificationStatus());
        assertEquals("not_available", status(outcome.checks(), "Build"));
        org.mockito.Mockito.verify(fetcher, org.mockito.Mockito.never()).checkoutCommit(anyString(), anyString(), any());
    }

    @Test
    void detectsEcosystemsFromRootManifests() throws Exception {
        Path dir = Files.createTempDirectory("relay-eco-");
        try {
            assertTrue(SandboxValidationService.detect(dir).isEmpty());
            Files.writeString(dir.resolve("requirements.txt"), "flask==3.0.0");
            assertEquals(SandboxRunner.Ecosystem.PYTHON, SandboxValidationService.detect(dir).orElseThrow());
            Files.writeString(dir.resolve("package.json"), "{}");
            assertEquals(SandboxRunner.Ecosystem.NPM, SandboxValidationService.detect(dir).orElseThrow());
        } finally {
            GitSourceFetcher.deleteWorkspace(dir);
        }
    }

    @Test
    void dockerRunnerUsesFixedCommandsAndIsolatedDefaults() {
        assertTrue(DockerSandboxRunner.commandFor(SandboxRunner.Ecosystem.NPM, SandboxRunner.Phase.BUILD).contains("--ignore-scripts"));
        assertEquals("mvn -B -q test", DockerSandboxRunner.commandFor(SandboxRunner.Ecosystem.MAVEN, SandboxRunner.Phase.TEST));
        DockerSandboxRunner runner = new DockerSandboxRunner(false, "docker", "host", "999999g", "1;rm", 1, "maven:3.9-eclipse-temurin-21", "node:20-alpine", "python:3.12-slim");
        String isolation = runner.describeIsolation();
        assertTrue(isolation.contains("network none"), "only none|bridge networks are accepted");
        assertTrue(isolation.contains("memory 1g") && isolation.contains("cpus 1") && isolation.contains("pids 32"));
        assertFalse(runner.available(), "disabled runner never reports availability");
        assertEquals("not_available", runner.run(Path.of("."), SandboxRunner.Ecosystem.MAVEN, SandboxRunner.Phase.BUILD, java.time.Duration.ofSeconds(1)).status());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                new DockerSandboxRunner(true, "docker", "none", "1g", "1", 256, "maven;rm -rf /", "node:20-alpine", "python:3.12-slim"));
    }

    @Test
    void outputTailMasksCredentialsAndControlCharacters() {
        String tail = DockerSandboxRunner.tail("line1\nAPI_KEY=abc123\u001b[31m\npassword: hunter2");
        assertFalse(tail.contains("abc123"));
        assertFalse(tail.contains("hunter2"));
        assertFalse(tail.contains("\u001b"));
    }
}

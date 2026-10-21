package com.relayaegis.remediation.sandbox;

import com.relayaegis.assessment.AssessmentScanResult;
import com.relayaegis.assessment.GitHubRepositoryAnalyzer;
import com.relayaegis.dto.ApiDtos.RepairCheckDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.relayaegis.remediation.GitSourceFetcher;
import com.relayaegis.remediation.RemediationException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Validates a proposed patch on a disposable copy of the assessed commit: checkout → apply patch → build → tests →
 * security re-scan. The original repository is never modified (nothing is pushed, no branch or pull request is
 * created). The patch counts as verified only when build, tests and the security re-scan all pass; a command that
 * merely ran is never reported as success.
 */
@Service
public class SandboxValidationService {
    private static final Logger log = LoggerFactory.getLogger(SandboxValidationService.class);

    public record Outcome(List<RepairCheckDto> checks, String verificationStatus, String verificationDetail) {}

    private final SandboxRunner runner;
    private final GitSourceFetcher fetcher;
    private final GitHubRepositoryAnalyzer analyzer;
    private final Duration phaseTimeout;

    public SandboxValidationService(SandboxRunner runner, GitSourceFetcher fetcher, GitHubRepositoryAnalyzer analyzer,
                                    @Value("${relay.sandbox.phase-timeout-seconds:600}") long phaseTimeoutSeconds) {
        this.runner = runner;
        this.fetcher = fetcher;
        this.analyzer = analyzer;
        this.phaseTimeout = Duration.ofSeconds(Math.max(30, Math.min(3600, phaseTimeoutSeconds)));
    }

    public boolean available() { return runner.available(); }

    public Outcome validate(String repositoryUrl, String commit, AssessmentFindingDto finding, List<String> originalLines, List<String> patchedLines) {
        List<RepairCheckDto> checks = new ArrayList<>();
        if (!runner.available()) {
            checks.add(new RepairCheckDto("Build", "not_available", "Sandbox validation is disabled or Docker is unreachable (relay.sandbox.enabled). The patch has not been compiled."));
            checks.add(new RepairCheckDto("Tests", "not_available", "Sandbox validation is disabled or Docker is unreachable; no tests were run."));
            checks.add(new RepairCheckDto("Security re-scan", "not_run", "Runs only as part of sandbox validation."));
            return new Outcome(checks, "not_run", null);
        }
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("relay-sandbox-ws-");
            fetcher.checkoutCommit(repositoryUrl, commit, workspace);
            checks.add(new RepairCheckDto("Sandbox checkout", "passed", "Commit " + commit.substring(0, 12) + " materialised in a disposable workspace; the original repository is untouched."));

            AssessmentScanResult before = analyzer.scanDirectory(workspace, commit);
            Path target = workspace.resolve(finding.file()).normalize();
            if (!target.startsWith(workspace) || !Files.isRegularFile(target)) throw new RemediationException("The patched file is not present in the sandbox workspace.");
            List<String> current = Files.readAllLines(target, StandardCharsets.UTF_8);
            if (!current.equals(originalLines)) {
                checks.add(new RepairCheckDto("Patch applied", "failed", "The file in the sandbox differs from the content the patch was generated from."));
                return failed(checks, "The patch could not be applied cleanly.");
            }
            Files.writeString(target, String.join("\n", patchedLines) + "\n", StandardCharsets.UTF_8);
            checks.add(new RepairCheckDto("Patch applied", "passed", "Applied to " + finding.file() + " in the sandbox copy only."));

            Optional<SandboxRunner.Ecosystem> ecosystem = detect(workspace);
            if (ecosystem.isEmpty()) {
                checks.add(new RepairCheckDto("Build", "not_available", "No supported build system (Maven pom.xml, npm package.json, Python requirements/pyproject) at the repository root."));
                checks.add(new RepairCheckDto("Tests", "not_available", "No supported test runner was detected."));
            }
            boolean buildPassed = false;
            boolean testsPassed = false;
            boolean baselineFailed = false;
            boolean baselineBuildFailed = false;
            String couldNotRun = null;
            if (ecosystem.isPresent()) {
                SandboxRunner.PhaseResult build = runner.run(workspace, ecosystem.get(), SandboxRunner.Phase.BUILD, phaseTimeout);
                checks.add(phaseCheck("Build", ecosystem.get(), build));
                buildPassed = build.passed();
                if ("not_available".equals(build.status())) couldNotRun = "the build";
                if (buildPassed) {
                    SandboxRunner.PhaseResult tests = runner.run(workspace, ecosystem.get(), SandboxRunner.Phase.TEST, phaseTimeout);
                    checks.add(phaseCheck("Tests", ecosystem.get(), tests));
                    testsPassed = tests.passed();
                    if ("not_available".equals(tests.status())) couldNotRun = "the tests";
                    else if (!testsPassed) baselineFailed = runBaseline(repositoryUrl, commit, ecosystem.get(), SandboxRunner.Phase.TEST, checks);
                } else {
                    // Attribute a build failure: does the unpatched commit build? Never acknowledgeable — a build failure blocks publication.
                    if (couldNotRun == null) baselineBuildFailed = runBaseline(repositoryUrl, commit, ecosystem.get(), SandboxRunner.Phase.BUILD, checks);
                    checks.add(new RepairCheckDto("Tests", "not_run", couldNotRun != null
                            ? "Not run because the sandbox could not run the build." : "Skipped because the build did not pass."));
                }
            }

            AssessmentScanResult after = analyzer.scanDirectory(workspace, commit);
            long beforeTarget = count(before, finding.file(), finding.title());
            long afterTarget = count(after, finding.file(), finding.title());
            long beforeFile = count(before, finding.file(), null);
            long afterFile = count(after, finding.file(), null);
            boolean securityPassed = afterTarget < beforeTarget && afterFile < beforeFile;
            checks.add(new RepairCheckDto("Security re-scan", securityPassed ? "passed" : "failed", securityPassed
                    ? "The targeted finding no longer appears (" + beforeTarget + " → " + afterTarget + ") and no new finding was introduced in " + finding.file() + "."
                    : "The re-scan still reports " + afterTarget + " matching finding(s) and " + afterFile + " total finding(s) in " + finding.file() + " (before: " + beforeFile + ")."));

            if (couldNotRun != null) {
                // The sandbox itself could not run (e.g. image missing): the patch was not tested, which is not a patch failure.
                return new Outcome(checks, "not_run", "Not validated: the sandbox could not run " + couldNotRun
                        + " (see the Build/Tests checks). The patch has not been compiled or tested; it was not applied.");
            }
            if (baselineFailed) {
                // The same tests fail on the unpatched commit: the failure is pre-existing or environmental, not caused by the patch.
                return new Outcome(checks, "not_run", "Not validated: the tests also fail without the patch (see the Baseline tests check), "
                        + "so the failure is pre-existing or caused by the sandbox environment (e.g. a @SpringBootTest that needs an external database), "
                        + "not by the patch. Build " + (buildPassed ? "passed" : "did not pass") + " and the security re-scan " + (securityPassed ? "passed" : "failed")
                        + " on the patched copy. The patch has not been verified by tests; it was not applied.");
            }
            if (baselineBuildFailed) {
                return failed(checks, "Sandbox validation failed: the build fails on the unpatched commit as well (see the Baseline build check), "
                        + "so the repository does not build at this commit. The failure is pre-existing, not caused by the patch, but the patch cannot be "
                        + "verified and must not be applied. The repository was not modified.");
            }
            if (buildPassed && testsPassed && securityPassed) {
                return new Outcome(checks, "passed", "Sandbox-validated: the patch applied cleanly, the build and tests passed, and the security re-scan confirmed the fix. "
                        + runner.describeIsolation() + " Review it before applying it to the repository.");
            }
            return failed(checks, "Sandbox validation did not pass: " + (ecosystem.isEmpty() ? "no supported build system" : !buildPassed ? "build failed"
                    : !testsPassed ? "tests failed" : "security re-scan failed") + ". The patch must not be applied as-is.");
        } catch (RemediationException ex) {
            checks.add(new RepairCheckDto("Sandbox checkout", "failed", ex.getMessage()));
            return failed(checks, ex.getMessage());
        } catch (Exception ex) {
            log.warn("Sandbox validation failed: {}", ex.getClass().getSimpleName());
            checks.add(new RepairCheckDto("Sandbox", "failed", "Sandbox validation failed unexpectedly."));
            return failed(checks, "Sandbox validation failed unexpectedly.");
        } finally {
            GitSourceFetcher.deleteWorkspace(workspace);
        }
    }

    /**
     * Runs one phase (BUILD or TEST) on a fresh, unpatched checkout of the same commit, inside the same sandbox runner.
     * Returns true when the baseline also fails (so the patched failure is not attributable to the patch).
     */
    private boolean runBaseline(String repositoryUrl, String commit, SandboxRunner.Ecosystem ecosystem, SandboxRunner.Phase phase, List<RepairCheckDto> checks) {
        boolean build = phase == SandboxRunner.Phase.BUILD;
        String name = build ? "Baseline build" : "Baseline tests";
        Path baseline = null;
        try {
            baseline = Files.createTempDirectory("relay-sandbox-baseline-");
            fetcher.checkoutCommit(repositoryUrl, commit, baseline);
            SandboxRunner.PhaseResult result = runner.run(baseline, ecosystem, phase, phaseTimeout);
            if (result.passed()) {
                checks.add(new RepairCheckDto(name, "passed", build
                        ? "The unpatched commit builds in the sandbox, so the build failure is caused by the patch."
                        : "The same tests pass on the unpatched commit in the sandbox, so the test failure is caused by the patch."));
                return false;
            }
            if ("not_available".equals(result.status())) {
                checks.add(new RepairCheckDto(name, "not_available", "The sandbox could not run the " + (build ? "build" : "tests") + " on the unpatched commit: " + result.outputTail()));
                return false;
            }
            RepairCheckDto check = phaseCheck(name, ecosystem, result);
            checks.add(new RepairCheckDto(check.name(), "failed", (build
                    ? "The build also fails on the unpatched commit: the repository does not compile at this commit, independently of the patch. "
                    : "The tests also fail on the unpatched commit (pre-existing or environment failure, e.g. a test that needs an external database the sandbox does not provide). ")
                    + check.detail()));
            return true;
        } catch (Exception ex) {
            log.warn("Baseline {} run failed: {}", build ? "build" : "test", ex.getClass().getSimpleName());
            checks.add(new RepairCheckDto(name, "not_run", "The unpatched commit could not be prepared for a baseline " + (build ? "build." : "test run.")));
            return false;
        } finally {
            GitSourceFetcher.deleteWorkspace(baseline);
        }
    }

    static Optional<SandboxRunner.Ecosystem> detect(Path root) {
        if (Files.isRegularFile(root.resolve("pom.xml"))) return Optional.of(SandboxRunner.Ecosystem.MAVEN);
        if (Files.isRegularFile(root.resolve("package.json"))) return Optional.of(SandboxRunner.Ecosystem.NPM);
        if (Files.isRegularFile(root.resolve("requirements.txt")) || Files.isRegularFile(root.resolve("pyproject.toml"))
                || Files.isRegularFile(root.resolve("setup.py"))) return Optional.of(SandboxRunner.Ecosystem.PYTHON);
        return Optional.empty();
    }

    private static RepairCheckDto phaseCheck(String name, SandboxRunner.Ecosystem ecosystem, SandboxRunner.PhaseResult result) {
        String status = switch (result.status()) { case "passed" -> "passed"; case "not_available" -> "not_available"; default -> "failed"; };
        String detail = ecosystem.name().toLowerCase() + " " + name.toLowerCase() + ": " + switch (result.status()) {
            case "passed" -> "exit code 0 in " + result.durationMs() + " ms.";
            case "timed_out" -> "exceeded the time limit and was stopped.";
            case "not_available" -> result.outputTail();
            default -> "exit code " + result.exitCode() + ".";
        };
        if (!"passed".equals(result.status()) && !"not_available".equals(result.status()) && !result.outputTail().isBlank()) {
            String tail = result.outputTail();
            detail += " Output tail: " + (tail.length() > 800 ? tail.substring(tail.length() - 800) : tail);
        }
        return new RepairCheckDto(name, status, detail);
    }

    private static long count(AssessmentScanResult scan, String file, String title) {
        return scan.findings().stream().filter(f -> file.equals(f.file()) && (title == null || title.equals(f.title()))).count();
    }

    private static Outcome failed(List<RepairCheckDto> checks, String detail) { return new Outcome(checks, "failed", detail); }
}

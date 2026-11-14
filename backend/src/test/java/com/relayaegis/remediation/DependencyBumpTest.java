package com.relayaegis.remediation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.relayaegis.assessment.DependencyVersions;
import com.relayaegis.assessment.GitHubRepositoryAnalyzer;
import com.relayaegis.assessment.GitHubRepositoryPolicy;
import com.relayaegis.assessment.OsvAdvisoryClient;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/** Phase 5: OSV advisories for pinned dependencies and the deterministic bump to the fixed version. */
class DependencyBumpTest {
    static final String PACKAGE_JSON = """
            {
              "name": "shop",
              "dependencies": {
                "lodash": "^4.17.15",
                "express": "4.18.2"
              },
              "devDependencies": {
                "@types/node": "~20.1.0"
              },
              "scripts": { "test": "node test.js" }
            }
            """;

    @Test
    void pinnedNpmAndPipDependenciesAreReadWithTheirLines() {
        List<DependencyVersions.Dependency> npm = DependencyVersions.parse("package.json", PACKAGE_JSON);
        assertEquals(3, npm.size());
        assertEquals(new DependencyVersions.Dependency("npm", "lodash", "4.17.15", "^", "package.json", 4), npm.get(0));
        assertEquals("@types/node", npm.get(2).name());
        List<DependencyVersions.Dependency> pip = DependencyVersions.parse("api/requirements.txt", "# deps\nDjango==3.2.4  # web\nrequests>=2.0\nPyYAML[ext]==5.3 ; python_version>'3'\n");
        assertEquals(2, pip.size(), "only exact pins are looked up");
        assertEquals("django", pip.get(0).name());
        assertEquals(2, pip.get(0).line());
        assertEquals("pyyaml", pip.get(1).name());
    }

    @Test
    void versionsCompareNumericallyAndBumpsKeepTheOperator() {
        assertTrue(DependencyVersions.compare("1.10.0", "1.9.2") > 0);
        assertTrue(DependencyVersions.compare("2.0.0-beta.1", "2.0.0") < 0);
        assertEquals(0, DependencyVersions.compare("3.2", "3.2.0"));
        assertEquals("    \"lodash\": \"^4.17.21\",", DependencyVersions.bump("    \"lodash\": \"^4.17.15\",", "npm", "lodash", "4.17.15", "4.17.21"));
        assertEquals("Django==3.2.25  # web", DependencyVersions.bump("Django==3.2.4  # web", "PyPI", "django", "3.2.4", "3.2.25"));
        assertEquals(null, DependencyVersions.bump("    \"lodash\": \"^4.17.16\",", "npm", "lodash", "4.17.15", "4.17.21"), "stale line is refused");
    }

    static AssessmentFindingDto finding(String evidence, int line) {
        return new AssessmentFindingDto("F-1", GitHubRepositoryAnalyzer.VULNERABLE_DEPENDENCY, GitHubRepositoryAnalyzer.VULNERABLE_DEPENDENCY_TITLE,
                "high", "high", "package.json", line, evidence, "", "", "unverified_candidate");
    }

    @Test
    void theStrategyChangesOnlyTheVulnerableLine() throws Exception {
        AssessmentFindingDto f = finding("npm package lodash 4.17.15 is affected by GHSA-35jh-r3h4-6jhm (and 2 more): Command injection in lodash. Fixed in 4.17.21.", 4);
        assertTrue(PatchStrategies.forFinding(f).isPresent());
        assertEquals(DependencyBumpStrategy.ID, PatchStrategies.forFinding(f).get().id());
        List<String> lines = List.of(PACKAGE_JSON.split("\n"));
        PatchStrategies.Result result = PatchStrategies.forFinding(f).get().propose(f, "package.json", lines, 4);
        assertEquals("    \"lodash\": \"^4.17.21\",", result.newLines().get(3));
        for (int i = 0; i < lines.size(); i++) if (i != 3) assertEquals(lines.get(i), result.newLines().get(i));
        assertTrue(result.explanation().contains("stays within the same major version"));
        assertTrue(result.followUps().stream().anyMatch(c -> c.detail().contains("package-lock.json")));

        assertFalse(DependencyBumpStrategy.supports(finding("npm package lodash 4.17.15 is affected by GHSA-x: y No fixed version is published.", 4)));
        assertThrows(RemediationException.class, () -> DependencyBumpStrategy.propose(f, lines, 5));
    }

    @Test
    void theAnalyzerLooksPinnedVersionsUpInOsvAndReportsAFixableFinding(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve("package.json"), PACKAGE_JSON);
        AtomicReference<String> batchBody = new AtomicReference<>();
        HttpServer osv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        osv.createContext("/v1/querybatch", ex -> {
            batchBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-35jh-r3h4-6jhm\"},{\"id\":\"GHSA-p6mc-m468-83gw\"}]},{},{}]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length); ex.getResponseBody().write(body); ex.close();
        });
        osv.createContext("/v1/vulns/", ex -> {
            String id = ex.getRequestURI().getPath().substring("/v1/vulns/".length());
            String fixed = id.startsWith("GHSA-35") ? "4.17.21" : "4.17.19";
            byte[] body = ("{\"id\":\"" + id + "\",\"summary\":\"Prototype pollution in lodash\",\"database_specific\":{\"severity\":\"HIGH\"},"
                    + "\"affected\":[{\"package\":{\"ecosystem\":\"npm\",\"name\":\"lodash\"},\"ranges\":[{\"type\":\"SEMVER\",\"events\":[{\"introduced\":\"0\"},{\"fixed\":\"" + fixed + "\"}]}]}]}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length); ex.getResponseBody().write(body); ex.close();
        });
        osv.start();
        try {
            GitHubRepositoryAnalyzer analyzer = new GitHubRepositoryAnalyzer(Mockito.mock(GitHubRepositoryPolicy.class));
            analyzer.setAdvisories(new OsvAdvisoryClient(true, "http://127.0.0.1:" + osv.getAddress().getPort()));
            var scan = analyzer.scanDirectory(repo, "0".repeat(40));
            AssessmentFindingDto vuln = scan.findings().stream().filter(x -> GitHubRepositoryAnalyzer.VULNERABLE_DEPENDENCY.equals(x.category())).findFirst().orElseThrow();
            assertEquals(4, vuln.line());
            assertEquals("high", vuln.severity());
            assertTrue(vuln.evidence().endsWith("Fixed in 4.17.21."), "the highest fix clears both advisories: " + vuln.evidence());
            assertTrue(DependencyBumpStrategy.supports(vuln));
            assertTrue(batchBody.get().contains("\"name\":\"lodash\"") && !batchBody.get().contains("scripts"), "only names and versions are sent");
            assertTrue(scan.checks().stream().anyMatch(c -> c.name().equals("Dependency vulnerability advisories") && c.status().equals("warning")));
        } finally {
            osv.stop(0);
        }

        GitHubRepositoryAnalyzer offline = new GitHubRepositoryAnalyzer(Mockito.mock(GitHubRepositoryPolicy.class));
        offline.setAdvisories(new OsvAdvisoryClient(true, "http://127.0.0.1:1"));
        var scan = offline.scanDirectory(repo, "0".repeat(40));
        assertTrue(scan.checks().stream().anyMatch(c -> c.name().equals("Dependency vulnerability advisories") && c.status().equals("not_run")
                && c.detail().contains("could not be reached")), "an unreachable OSV is reported, never treated as clean");
    }
}

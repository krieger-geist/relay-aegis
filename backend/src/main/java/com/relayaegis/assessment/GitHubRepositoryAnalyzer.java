package com.relayaegis.assessment;

import com.relayaegis.dto.AssessmentDtos.AssessmentCheckDto;
import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Read-only, heuristic repository scanner. It never executes repository code, package scripts,
 * build tasks, tests, git hooks, or container definitions.
 */
@Service
public class GitHubRepositoryAnalyzer {
    private static final int MAX_FILES = 8_000;
    private static final long MAX_TOTAL_BYTES = 30L * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 512L * 1024;
    private static final int MAX_FINDINGS = 100;
    private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "vendor", "target", "dist", "build", ".next", ".venv", "venv", "coverage", "packages", "bower_components", "out");
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(".java", ".kt", ".kts", ".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs", ".py", ".go", ".rs", ".rb", ".php", ".cs", ".cpp", ".cc", ".c", ".h", ".hpp", ".sql", ".sh", ".yml", ".yaml", ".json", ".xml", ".properties", ".toml", ".env", ".ini", ".tf", ".dockerfile");

    /**
     * Credential-like assignment; group 1 = key, 2 = quote, 3 = value. Shared with the remediation strategy.
     * The key may carry a prefix (TEST_API_KEY, DB_PASSWORD, stripeSecret) but must end with the credential keyword,
     * so names such as API_KEY_HEADER or passwordHint are not matched.
     */
    public static final Pattern SECRET_PATTERN = Pattern.compile("(?i)\\b([A-Za-z0-9_]*?(?:password|passwd|api[_-]?key|secret|access[_-]?token|client[_-]?secret))\\b\\s*[:=]\\s*(['\"])([^'\"\\r\\n]{8,})\\2");
    private static final Pattern SQL_CONCAT_PATTERN = Pattern.compile("(?i)\\b(?:select|update|delete|insert)\\b[^\\r\\n]{0,180}\\+\\s*[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern COMMAND_PATTERN = Pattern.compile("(?i)Runtime\\.getRuntime\\(\\)\\.exec\\s*\\(|new\\s+ProcessBuilder\\s*\\(");
    private static final Pattern EVAL_PATTERN = Pattern.compile("\\beval\\s*\\(");
    private static final Pattern EMPTY_CATCH_PATTERN = Pattern.compile("(?s)catch\\s*\\([^)]{1,200}\\)\\s*\\{\\s*(?://[^\\r\\n]*\\s*)?}");
    /** An upper-case TODO/FIXME after a comment opener; identifiers and package names such as {@code com.example.todo} are not markers. */
    private static final Pattern TODO_PATTERN = Pattern.compile("(?m)(?://|/\\*|^[ \\t]*\\*|#|<!--|--)[ \\t]*(?:TODO|FIXME)\\b[^\\r\\n]{0,180}");

    private final GitHubRepositoryPolicy policy;

    public GitHubRepositoryAnalyzer(GitHubRepositoryPolicy policy) {
        this.policy = policy;
    }

    public AssessmentScanResult scan(String repositoryUrl) throws Exception {
        String normalized = policy.validateAndNormalize(repositoryUrl);
        Path work = Files.createTempDirectory("relay-assessment-");
        Path checkout = work.resolve("source");
        Path output = work.resolve("git-output.log");
        try {
            Path isolatedHome = work.resolve("home");
            Files.createDirectories(isolatedHome);
            ProcessBuilder builder = new ProcessBuilder(
                    "git", "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false",
                    "-c", "protocol.ext.allow=never", "clone", "--depth", "1", "--single-branch",
                    "--no-tags", "--no-recurse-submodules", "--", normalized, checkout.toString());
            builder.redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("HOME", isolatedHome.toString());
            builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            Process clone = builder.start();
            boolean finished = clone.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                clone.destroyForcibly();
                throw new IllegalStateException("Repository clone exceeded the 60-second safety deadline.");
            }
            if (clone.exitValue() != 0 || !Files.isDirectory(checkout)) {
                throw new IllegalStateException("Repository could not be cloned. Confirm that the GitHub repository exists, is public, and is reachable.");
            }
            String commit = runGitCommit(checkout, work.resolve("commit.log"));
            return scanCheckout(checkout, commit);
        } finally {
            deleteTree(work);
        }
    }

    /** Scans an already-present directory (read-only). Package-private for tests and the sandbox security re-scan. */
    public AssessmentScanResult scanDirectory(Path root, String commit) throws IOException {
        return scanCheckout(root, commit);
    }

    private AssessmentScanResult scanCheckout(Path root, String commit) throws IOException {
        List<AssessmentFindingDto> findings = new ArrayList<>();
        MutableStats stats = new MutableStats();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT))) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE;
                if (++stats.filesSeen > MAX_FILES || stats.bytesRead > MAX_TOTAL_BYTES) {
                    stats.truncated = true;
                    return FileVisitResult.TERMINATE;
                }
                String filename = file.getFileName().toString();
                String relativePath = safeRelativePath(root.relativize(file).toString());
                String lowerPath = relativePath.toLowerCase(Locale.ROOT);
                if (isManifest(lowerPath)) stats.manifests.add(lowerPath);
                if (isTestPath(lowerPath)) stats.testFiles++;
                String language = CodeAnalysisRules.language(lowerPath);
                if (language != null) stats.languages.merge(language, 1, Integer::sum);
                String lowerName = filename.toLowerCase(Locale.ROOT);
                if ((lowerName.equals(".env") || (lowerName.startsWith(".env.") && !lowerName.matches("\\.env\\.(example|sample|template|dist)")))
                        && findings.size() < MAX_FINDINGS) {
                    add(findings, "configuration", "Environment file committed to the repository", "medium", "medium", relativePath, 1,
                            "A .env file is tracked in the repository; its contents are not copied into the report.",
                            "Environment files usually hold credentials and deployment-specific settings that should not be versioned.",
                            "Remove the file from version control, add it to .gitignore, and commit a .env.example with placeholder values instead.");
                }
                if (!isScanCandidate(filename, lowerPath) || attrs.size() <= 0 || attrs.size() > MAX_FILE_BYTES || attrs.size() + stats.bytesRead > MAX_TOTAL_BYTES) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    String content = Files.readString(file);
                    // Binary or unexpected encodings are ignored rather than interpreted as source.
                    if (content.indexOf('\u0000') >= 0) return FileVisitResult.CONTINUE;
                    stats.bytesRead += attrs.size();
                    stats.textFiles++;
                    if (language != null) stats.linesOfCode += content.isEmpty() ? 0 : content.split("\\r?\\n", -1).length;
                    if (isManifest(lowerPath)) {
                        CodeAnalysisRules.ManifestInsight insight = CodeAnalysisRules.manifest(relativePath, lowerPath, content);
                        stats.frameworks.addAll(insight.frameworks());
                        stats.directDependencies += insight.dependencies();
                        if (!insight.unpinned().isEmpty() && findings.size() < MAX_FINDINGS) {
                            add(findings, "dependency-management", "Unpinned dependency versions", "low", "high", relativePath, 1,
                                    insight.unpinned().size() + " dependency declaration(s) use no version, a wildcard or a floating 'latest' version: "
                                            + abbreviate(String.join(", ", insight.unpinned()), 200) + ".",
                                    "Floating versions make builds non-reproducible and can pull in breaking or compromised releases.",
                                    "Pin exact versions (or use a lock file) and update them deliberately.");
                        }
                    }
                    scanText(relativePath, content, findings);
                    if (findings.size() < MAX_FINDINGS) {
                        CodeAnalysisRules.apply(relativePath, lowerPath, content, (category, title, severity, confidence, f, line, evidence, explanation, recommendation) -> {
                            if (findings.size() < MAX_FINDINGS) add(findings, category, title, severity, confidence, f, line, evidence, explanation, recommendation);
                        });
                    }
                } catch (Exception ignored) {
                    stats.readErrors++;
                }
                return FileVisitResult.CONTINUE;
            }
        });

        List<AssessmentFindingDto> uniqueFindings = dedupe(findings);
        List<AssessmentCheckDto> checks = new ArrayList<>();
        checks.add(new AssessmentCheckDto("Repository checkout", "passed", "Fetched one shallow commit from GitHub. Commit: " + commit.substring(0, Math.min(12, commit.length())) + "."));
        checks.add(new AssessmentCheckDto("Repository inventory", stats.languages.isEmpty() ? "warning" : "passed", stats.languages.isEmpty()
                ? "No recognised source language was found in " + stats.textFiles + " readable text file(s)."
                : "Languages by file count: " + abbreviate(stats.languages.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                        .map(e -> e.getKey() + " " + e.getValue()).collect(java.util.stream.Collectors.joining(", ")), 240)
                  + ". Approximately " + stats.linesOfCode + " source line(s)."));
        checks.add(new AssessmentCheckDto("Framework detection", stats.frameworks.isEmpty() ? "warning" : "passed", stats.frameworks.isEmpty()
                ? "No known framework was identified from the dependency manifests."
                : "Detected from manifests: " + abbreviate(String.join(", ", stats.frameworks), 240) + "."));
        checks.add(new AssessmentCheckDto("Static source scan", uniqueFindings.isEmpty() ? "passed" : "warning", "Scanned " + stats.textFiles + " text/source files using built-in security, reliability, correctness and configuration rules. Findings are candidates, not confirmed defects."));
        checks.add(new AssessmentCheckDto("Dependency manifests", stats.manifests.isEmpty() ? "warning" : "passed", stats.manifests.isEmpty() ? "No common dependency manifest was detected." : "Detected " + stats.manifests.size() + " manifest(s): " + abbreviate(String.join(", ", stats.manifests), 260) + "; " + stats.directDependencies + " direct dependency declaration(s)."));
        checks.add(new AssessmentCheckDto("Dependency vulnerability advisories", "not_run", "No advisory database is queried by this read-only scanner; run an advisory tool (e.g. npm audit, pip-audit, OWASP dependency-check) in a sandbox."));
        checks.add(new AssessmentCheckDto("Test-suite presence", stats.testFiles > 0 ? "passed" : "warning", stats.testFiles > 0 ? "Found " + stats.testFiles + " test-like file/path(s). Tests were not executed." : "No common test-like file/path was detected. This heuristic may miss project-specific test conventions."));
        checks.add(new AssessmentCheckDto("Execution safety", "passed", "Build scripts, package scripts, tests, Dockerfiles, and application code were not executed. Run approved tests in a disposable sandbox after review."));
        if (stats.truncated) checks.add(new AssessmentCheckDto("Scan limits", "warning", "Scan stopped at the configured file/size limit; findings may be incomplete."));
        if (stats.readErrors > 0) checks.add(new AssessmentCheckDto("Unreadable files", "warning", stats.readErrors + " file(s) could not be read and were skipped."));
        return new AssessmentScanResult(commit, uniqueFindings, checks);
    }

    private void scanText(String relativePath, String text, List<AssessmentFindingDto> findings) {
        if (findings.size() >= MAX_FINDINGS) return;
        Matcher secret = SECRET_PATTERN.matcher(text);
        while (secret.find() && findings.size() < MAX_FINDINGS) {
            String value = secret.group(3).toLowerCase(Locale.ROOT);
            if (isPlaceholder(value)) continue;
            add(findings, "secret-hygiene", "Possible hard-coded credential", "medium", "medium", relativePath, lineAt(text, secret.start()),
                    "Credential-like assignment detected; the assigned value is intentionally redacted.",
                    "A string literal appears to be assigned to a credential-related key. This can be a false positive; confirm whether it is a real credential before acting.",
                    "If genuine, revoke/rotate the credential, remove it from source control history where appropriate, and load secrets through environment-based configuration or a secrets manager.");
        }

        Matcher sql = SQL_CONCAT_PATTERN.matcher(text);
        while (sql.find() && findings.size() < MAX_FINDINGS) {
            add(findings, "database-safety", "Possible SQL query concatenation", "medium", "medium", relativePath, lineAt(text, sql.start()),
                    "SQL-like statement contains string concatenation with a variable.",
                    "Concatenating untrusted values into SQL can create an injection risk. The source and use of the variable must be reviewed before classifying this as a vulnerability.",
                    "Prefer prepared statements or parameterized ORM queries; add a regression test using adversarial input.");
        }

        Matcher command = COMMAND_PATTERN.matcher(text);
        while (command.find() && findings.size() < MAX_FINDINGS) {
            add(findings, "process-execution", "Process execution requires input-validation review", "low", "low", relativePath, lineAt(text, command.start()),
                    "A process-launch API is used; command text and its inputs are not included in the report.",
                    "Process execution may be legitimate, but it can become unsafe when arguments contain externally controlled values.",
                    "Check whether any argument is user-controlled, avoid shell-string construction, pass arguments as a list, and allowlist executable names and arguments.");
            break;
        }

        Matcher eval = EVAL_PATTERN.matcher(text);
        if (eval.find() && findings.size() < MAX_FINDINGS) {
            add(findings, "dynamic-evaluation", "Dynamic eval call detected", "medium", "low", relativePath, lineAt(text, eval.start()),
                    "A call to eval-like dynamic evaluation was detected.",
                    "Dynamic evaluation can execute data as code. Some uses may be intentional, but externally controlled input must never be evaluated as code.",
                    "Replace dynamic evaluation with explicit parsing or a safe expression parser; trace the origin of the evaluated value.");
        }

        Matcher emptyCatch = EMPTY_CATCH_PATTERN.matcher(text);
        if (emptyCatch.find() && findings.size() < MAX_FINDINGS) {
            add(findings, "error-handling", "Possibly empty exception handler", "low", "low", relativePath, lineAt(text, emptyCatch.start()),
                    "An exception handler appears to discard an exception without handling or recording it.",
                    "Silently swallowed exceptions can hide failed operations. Comments inside the handler or unusual formatting can cause false positives.",
                    "Handle the expected exception explicitly or record safe diagnostic context; do not leak secrets into logs.");
        }

        Matcher todo = TODO_PATTERN.matcher(text);
        int todoCount = 0;
        while (todo.find() && findings.size() < MAX_FINDINGS && todoCount < 2) {
            add(findings, "maintenance", "TODO/FIXME marker in source", "info", "low", relativePath, lineAt(text, todo.start()),
                    "A TODO/FIXME marker is present; its full comment text is not copied into the report.",
                    "This is a maintenance signal, not proof of a defect.",
                    "Review the marker and convert unresolved work into a tracked issue where appropriate.");
            todoCount++;
        }
    }

    private static void add(List<AssessmentFindingDto> findings, String category, String title, String severity, String confidence,
            String file, int line, String evidence, String explanation, String recommendation) {
        findings.add(new AssessmentFindingDto("F-" + UUID.randomUUID().toString().substring(0, 8), category, title, severity, confidence,
                file, line, evidence, explanation, recommendation, "unverified_candidate"));
    }

    private static boolean isPlaceholder(String value) {
        return value.contains("example") || value.contains("placeholder") || value.contains("changeme") || value.contains("change_me")
                || value.contains("your_") || value.contains("your-") || value.contains("dummy") || value.equals("password123")
                || value.equals("secret123") || value.contains("replace_me") || value.equals("testtest") || value.contains("sample");
    }

    private static int lineAt(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) if (content.charAt(i) == '\n') line++;
        return line;
    }

    private static String safeRelativePath(String raw) {
        String safe = raw.replace('\\', '/').replaceAll("[\\p{Cntrl}]", "_");
        return abbreviate(safe, 240);
    }

    private static boolean isManifest(String path) {
        return path.endsWith("pom.xml") || path.endsWith("package.json") || path.endsWith("requirements.txt") || path.endsWith("pyproject.toml")
                || path.endsWith("go.mod") || path.endsWith("cargo.toml") || path.endsWith("gemfile") || path.endsWith("composer.json")
                || path.endsWith("package-lock.json") || path.endsWith("pnpm-lock.yaml") || path.endsWith("yarn.lock") || path.endsWith("dockerfile");
    }

    private static boolean isTestPath(String path) {
        return path.contains("/test/") || path.contains("/tests/") || path.contains("__tests__/") || path.endsWith("test.java")
                || path.endsWith("tests.py") || path.matches(".*\\.(test|spec)\\.[jt]sx?$") || path.contains("_test.go");
    }

    private static boolean isScanCandidate(String filename, String path) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".min.js") || lower.endsWith(".map") || lower.endsWith(".lock") || lower.endsWith(".svg") || lower.endsWith(".md")) return false;
        if (path.endsWith("package-lock.json") || path.endsWith("yarn.lock") || path.endsWith("pnpm-lock.yaml")) return false;
        if (lower.equals("dockerfile") || lower.equals("requirements.txt") || lower.equals("go.mod") || lower.equals("gemfile")) return true;
        int dot = lower.lastIndexOf('.');
        return dot >= 0 && SOURCE_EXTENSIONS.contains(lower.substring(dot)) || lower.equals(".env") || lower.startsWith(".env.");
    }

    private String runGitCommit(Path checkout, Path log) throws Exception {
        Process process = new ProcessBuilder("git", "-C", checkout.toString(), "rev-parse", "HEAD").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Unable to read repository revision within the deadline."); }
        if (process.exitValue() != 0) return "unknown";
        String value = Files.readString(log).trim();
        return value.matches("[0-9a-fA-F]{7,64}") ? value : "unknown";
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.deleteIfExists(file); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException { Files.deleteIfExists(dir); return FileVisitResult.CONTINUE; }
            });
        } catch (IOException ignored) { /* Temporary checkout cleanup is best-effort; no user path is ever accepted here. */ }
    }

    private static String abbreviate(String value, int max) { return value.length() <= max ? value : value.substring(0, max - 3) + "..."; }

    private static final class MutableStats {
        private int filesSeen;
        private int textFiles;
        private int testFiles;
        private int readErrors;
        private long bytesRead;
        private boolean truncated;
        private final List<String> manifests = new ArrayList<>();
        private final java.util.Map<String, Integer> languages = new java.util.TreeMap<>();
        private final Set<String> frameworks = new java.util.LinkedHashSet<>();
        private long linesOfCode;
        private int directDependencies;
    }

    /** Drops repeated findings for the same rule (title) on the same file and line. */
    static List<AssessmentFindingDto> dedupe(List<AssessmentFindingDto> findings) {
        Set<String> seen = new java.util.HashSet<>();
        List<AssessmentFindingDto> unique = new ArrayList<>();
        for (AssessmentFindingDto finding : findings) {
            if (seen.add(finding.title() + "|" + finding.file() + "|" + finding.line())) unique.add(finding);
        }
        return unique;
    }
}

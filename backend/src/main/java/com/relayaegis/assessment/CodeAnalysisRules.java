package com.relayaegis.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only static analysis rules (security, reliability, correctness, configuration) and manifest inventory.
 * Rules never execute repository content and never copy matched source text or secret values into findings:
 * a finding records the rule, file and line only. All rule matches are heuristic candidates.
 */
final class CodeAnalysisRules {
    private CodeAnalysisRules() {}

    /** One heuristic rule. {@code files} selects which repository paths it applies to. */
    record Rule(String id, String category, String title, String severity, String confidence, Pattern pattern,
                Predicate<String> files, String evidence, String explanation, String recommendation, int maxPerFile) {}

    interface Sink { void add(String category, String title, String severity, String confidence, String file, int line,
                              String evidence, String explanation, String recommendation); }

    static final Predicate<String> CODE = p -> p.matches(".*\\.(java|kt|kts|js|jsx|ts|tsx|mjs|cjs|py|go|rs|rb|php|cs)$");
    static final Predicate<String> JAVA = p -> p.endsWith(".java");
    static final Predicate<String> PYTHON = p -> p.endsWith(".py");
    static final Predicate<String> JS = p -> p.matches(".*\\.(js|jsx|ts|tsx|mjs|cjs)$");
    static final Predicate<String> SPRING_CONFIG = p -> p.matches(".*application[^/]*\\.(ya?ml|properties)$") && !p.contains("/test/");
    static final Predicate<String> COMPOSE = p -> p.matches(".*(docker-)?compose[^/]*\\.ya?ml$");
    static final Predicate<String> DJANGO_SETTINGS = p -> p.endsWith("settings.py");

    static final List<Rule> RULES = List.of(
            new Rule("tls-verification-disabled", "security", "TLS certificate verification disabled", "high", "medium",
                    Pattern.compile("(?i)(rejectUnauthorized\\s*:\\s*false|verify\\s*=\\s*False\\b|InsecureSkipVerify\\s*:\\s*true|NoopHostnameVerifier|trustAllCerts|TrustAllCerts|NODE_TLS_REJECT_UNAUTHORIZED[\"'\\s:=]+['\"]?0)"),
                    CODE, "A setting that disables TLS certificate or hostname verification is present.",
                    "Disabling verification allows man-in-the-middle interception of the affected connection. Test-only code can be a false positive.",
                    "Remove the override and trust the correct CA instead; restrict any test-only override to test code.", 3),
            new Rule("weak-hash", "security", "Weak hash algorithm (MD5/SHA-1)", "medium", "low",
                    Pattern.compile("(?i)(MessageDigest\\.getInstance\\(\\s*\"(MD5|SHA-?1)\"|hashlib\\.(md5|sha1)\\(|createHash\\(\\s*['\"](md5|sha1)['\"])"),
                    CODE, "MD5 or SHA-1 is used.", "These are unsuitable for passwords or signatures; checksums and cache keys can be acceptable uses.",
                    "Use a password hashing function (bcrypt, scrypt, Argon2) for passwords and SHA-256+ for integrity.", 3),
            new Rule("insecure-random-secret", "security", "Non-cryptographic random used for a secret", "medium", "medium",
                    Pattern.compile("(?i)(Math\\.random\\(\\)[^\\n]{0,80}(token|secret|password|session|nonce)|(token|secret|password|session|nonce)[^\\n]{0,80}Math\\.random\\(\\))"),
                    JS, "Math.random() appears next to a token/secret/session identifier.", "Math.random() is predictable and must not generate secrets.",
                    "Use crypto.randomUUID() or crypto.getRandomValues().", 3),
            new Rule("cors-wildcard", "configuration", "CORS allows every origin", "low", "medium",
                    Pattern.compile("(?i)(allowedOrigins\\(\\s*\"\\*\"\\s*\\)|Access-Control-Allow-Origin[\"']?\\s*[:,]\\s*[\"']\\*|cors\\(\\s*\\{\\s*origin\\s*:\\s*['\"]\\*['\"]|CORS_ORIGIN_ALLOW_ALL\\s*=\\s*True|allow_origins\\s*=\\s*\\[\\s*[\"']\\*[\"']\\s*\\])"),
                    p -> CODE.test(p) || p.endsWith(".yml") || p.endsWith(".yaml"), "A wildcard CORS origin is configured.",
                    "Any website can call the API from a browser; with credentials this can expose user data.", "Restrict allowed origins to known front-ends.", 2),
            new Rule("django-debug", "configuration", "Django DEBUG enabled in settings", "medium", "high",
                    Pattern.compile("(?m)^\\s*DEBUG\\s*=\\s*True\\b"), DJANGO_SETTINGS, "DEBUG = True is set in a settings module.",
                    "Debug mode exposes stack traces and settings to visitors if deployed.", "Read DEBUG from the environment and default it to False.", 1),
            new Rule("schema-auto-ddl", "configuration", "Database schema auto-generation enabled", "medium", "medium",
                    Pattern.compile("(?i)ddl-auto\\s*[:=]\\s*(create|create-drop|update)\\b"), SPRING_CONFIG,
                    "Hibernate ddl-auto is set to create, create-drop or update.",
                    "Automatic schema changes can drop or alter production data unexpectedly.", "Use managed migrations (Flyway/Liquibase) and ddl-auto=validate.", 1),
            new Rule("compose-privileged", "security", "Privileged container in Compose file", "high", "high",
                    Pattern.compile("(?m)^\\s*privileged:\\s*true\\b"), COMPOSE, "A service runs with privileged: true.",
                    "A privileged container can control the host.", "Remove privileged mode and grant only the specific capabilities needed.", 2),
            new Rule("compose-docker-socket", "security", "Docker socket mounted into a container", "high", "high",
                    Pattern.compile("/var/run/docker\\.sock"), COMPOSE, "The Docker socket is mounted into a service.",
                    "Access to the Docker socket is equivalent to root access on the host.", "Avoid mounting the socket; if unavoidable, isolate and restrict that service.", 1),
            new Rule("compose-host-network", "security", "Host network mode in Compose file", "medium", "high",
                    Pattern.compile("(?m)^\\s*network_mode:\\s*[\"']?host"), COMPOSE, "A service uses the host network.",
                    "Host networking removes network isolation between the container and the host.", "Use a bridge network and publish only required ports.", 1),
            new Rule("python-request-without-timeout", "reliability", "HTTP call without timeout", "medium", "medium",
                    Pattern.compile("(?m)requests\\.(get|post|put|patch|delete|head)\\((?![^)\\n]*timeout\\s*=)[^)\\n]*\\)"), PYTHON,
                    "A requests call has no timeout argument.", "Without a timeout a slow dependency can block the caller indefinitely and exhaust workers.",
                    "Pass an explicit timeout (e.g. timeout=5) and handle the timeout exception.", 3),
            new Rule("swallowed-promise-rejection", "reliability", "Promise rejection silently swallowed", "low", "low",
                    Pattern.compile("\\.catch\\(\\s*\\(?\\s*\\w*\\s*\\)?\\s*=>\\s*\\{\\s*\\}\\s*\\)"), JS,
                    "A promise .catch handler with an empty body was found.", "Failures disappear without logs, which hides outages.",
                    "Log or handle the error explicitly.", 3),
            new Rule("java-string-identity-compare", "correctness", "String compared with ==", "medium", "medium",
                    Pattern.compile("(?<![=!<>])==\\s*\"[^\"\\n]*\"|\"[^\"\\n]*\"\\s*==(?!=)"), JAVA,
                    "A String literal is compared with ==.", "In Java == compares references, not contents, so the comparison can be false for equal strings.",
                    "Use equals() (or Objects.equals) for string comparison.", 3),
            new Rule("python-bare-except", "correctness", "Bare except clause", "low", "medium",
                    Pattern.compile("(?m)^\\s*except\\s*:"), PYTHON, "An except clause without an exception type was found.",
                    "A bare except also catches KeyboardInterrupt/SystemExit and hides programming errors.", "Catch specific exception types.", 3),
            new Rule("python-mutable-default", "correctness", "Mutable default argument", "medium", "high",
                    Pattern.compile("(?m)^\\s*def\\s+\\w+\\([^)\\n]*=\\s*(\\[\\]|\\{\\}|set\\(\\))"), PYTHON,
                    "A function uses a list/dict/set literal as a default argument.", "The default object is shared across calls, so state leaks between invocations.",
                    "Default to None and create the object inside the function.", 3));

    static void apply(String path, String lowerPath, String text, Sink sink) {
        for (Rule rule : RULES) {
            if (!rule.files().test(lowerPath)) continue;
            Matcher match = rule.pattern().matcher(text);
            int count = 0;
            while (match.find() && count < rule.maxPerFile()) {
                sink.add(rule.category(), rule.title(), rule.severity(), rule.confidence(), path, lineAt(text, match.start()),
                        rule.evidence(), rule.explanation() + " (rule " + rule.id() + ")", rule.recommendation());
                count++;
            }
        }
        String name = lowerPath.contains("/") ? lowerPath.substring(lowerPath.lastIndexOf('/') + 1) : lowerPath;
        if (name.equals("dockerfile") || name.endsWith(".dockerfile")) dockerfile(path, text, sink);
    }

    private static void dockerfile(String path, String text, Sink sink) {
        if (!Pattern.compile("(?mi)^\\s*USER\\s+(?!root\\b)\\S+").matcher(text).find()) {
            sink.add("security", "Container runs as root", "medium", "medium", path, 1,
                    "The Dockerfile sets no non-root USER.", "A process running as root inside the container increases the impact of any compromise.",
                    "Add a USER instruction with an unprivileged user.");
        }
        Matcher from = Pattern.compile("(?mi)^\\s*FROM\\s+([^\\s]+)").matcher(text);
        while (from.find()) {
            String image = from.group(1);
            if (image.startsWith("$") || image.contains("@sha256:")) continue;
            String tag = image.contains(":") ? image.substring(image.lastIndexOf(':') + 1) : "";
            if (tag.isEmpty() || tag.equalsIgnoreCase("latest")) {
                sink.add("reliability", "Base image not pinned", "low", "high", path, lineAt(text, from.start()),
                        "A FROM instruction uses an untagged or :latest image.", "Builds can change silently when the upstream image is updated.",
                        "Pin a specific version tag or digest.");
            }
        }
    }

    static int lineAt(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) if (content.charAt(i) == '\n') line++;
        return line;
    }

    // ---- Inventory and manifests ----------------------------------------------------------------------------

    static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry(".java", "Java"), Map.entry(".kt", "Kotlin"), Map.entry(".kts", "Kotlin"), Map.entry(".js", "JavaScript"),
            Map.entry(".jsx", "JavaScript"), Map.entry(".mjs", "JavaScript"), Map.entry(".cjs", "JavaScript"), Map.entry(".ts", "TypeScript"),
            Map.entry(".tsx", "TypeScript"), Map.entry(".py", "Python"), Map.entry(".go", "Go"), Map.entry(".rs", "Rust"), Map.entry(".rb", "Ruby"),
            Map.entry(".php", "PHP"), Map.entry(".cs", "C#"), Map.entry(".c", "C"), Map.entry(".h", "C"), Map.entry(".cpp", "C++"),
            Map.entry(".cc", "C++"), Map.entry(".hpp", "C++"), Map.entry(".sql", "SQL"), Map.entry(".sh", "Shell"));

    static String language(String lowerPath) {
        int dot = lowerPath.lastIndexOf('.');
        return dot < 0 ? null : LANGUAGES.get(lowerPath.substring(dot));
    }

    /** Frameworks, direct dependency counts and unpinned versions found in one manifest. */
    record ManifestInsight(String manifest, Set<String> frameworks, int dependencies, List<String> unpinned) {}

    static ManifestInsight manifest(String path, String lowerPath, String text) {
        Set<String> frameworks = new LinkedHashSet<>();
        List<String> unpinned = new ArrayList<>();
        int count = 0;
        if (lowerPath.endsWith("package.json")) {
            try {
                JsonNode root = new ObjectMapper().readTree(text);
                for (String section : List.of("dependencies", "devDependencies")) {
                    for (Iterator<Map.Entry<String, JsonNode>> it = root.path(section).fields(); it.hasNext(); ) {
                        Map.Entry<String, JsonNode> dep = it.next();
                        if ("dependencies".equals(section)) count++;
                        String version = dep.getValue().asText("").trim();
                        if (version.isEmpty() || version.equals("*") || version.equalsIgnoreCase("latest")) unpinned.add(dep.getKey());
                        String name = dep.getKey();
                        switch (name) {
                            case "react" -> frameworks.add("React");
                            case "next" -> frameworks.add("Next.js");
                            case "vue" -> frameworks.add("Vue");
                            case "@angular/core" -> frameworks.add("Angular");
                            case "express" -> frameworks.add("Express");
                            case "@nestjs/core" -> frameworks.add("NestJS");
                            case "fastify" -> frameworks.add("Fastify");
                            case "svelte" -> frameworks.add("Svelte");
                            case "vite" -> frameworks.add("Vite");
                            default -> { }
                        }
                    }
                }
            } catch (Exception ignored) { /* malformed manifest: inventory only */ }
        } else if (lowerPath.endsWith("pom.xml")) {
            Matcher artifact = Pattern.compile("<artifactId>([^<]+)</artifactId>").matcher(text);
            while (artifact.find()) {
                String id = artifact.group(1);
                count++;
                if (id.startsWith("spring-boot")) frameworks.add("Spring Boot");
                if (id.startsWith("quarkus")) frameworks.add("Quarkus");
                if (id.startsWith("micronaut")) frameworks.add("Micronaut");
            }
            Matcher latest = Pattern.compile("<version>\\s*(LATEST|RELEASE)\\s*</version>").matcher(text);
            while (latest.find()) unpinned.add("version " + latest.group(1));
            count = Math.max(0, count - 1); // the project's own artifactId
        } else if (lowerPath.endsWith("build.gradle") || lowerPath.endsWith("build.gradle.kts")) {
            if (text.contains("org.springframework.boot")) frameworks.add("Spring Boot");
            Matcher dep = Pattern.compile("(?m)^\\s*(implementation|api|runtimeOnly|compileOnly)\\b").matcher(text);
            while (dep.find()) count++;
            if (text.contains("latest.release") || text.contains(":+\"") || text.contains(":+'")) unpinned.add("dynamic Gradle version");
        } else if (lowerPath.endsWith("requirements.txt")) {
            for (String line : text.split("\\r?\\n")) {
                String spec = line.replaceAll("#.*", "").trim();
                if (spec.isEmpty() || spec.startsWith("-")) continue;
                count++;
                String name = spec.split("[<>=!~\\[; ]")[0].toLowerCase(Locale.ROOT);
                if (!spec.contains("==")) unpinned.add(name);
                if (name.equals("django")) frameworks.add("Django");
                if (name.equals("flask")) frameworks.add("Flask");
                if (name.equals("fastapi")) frameworks.add("FastAPI");
            }
        } else if (lowerPath.endsWith("pyproject.toml")) {
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.contains("django")) frameworks.add("Django");
            if (lower.contains("flask")) frameworks.add("Flask");
            if (lower.contains("fastapi")) frameworks.add("FastAPI");
        } else if (lowerPath.endsWith("go.mod")) {
            Matcher dep = Pattern.compile("(?m)^\\s*(require\\s+)?[a-z0-9.-]+\\.[a-z]+/[^\\s]+\\s+v[0-9]").matcher(text);
            while (dep.find()) count++;
            if (text.contains("gin-gonic/gin")) frameworks.add("Gin");
            if (text.contains("labstack/echo")) frameworks.add("Echo");
            if (text.contains("gofiber/fiber")) frameworks.add("Fiber");
        } else if (lowerPath.endsWith("cargo.toml")) {
            if (text.contains("actix-web")) frameworks.add("Actix Web");
            if (text.contains("rocket")) frameworks.add("Rocket");
            if (text.contains("axum")) frameworks.add("Axum");
        }
        return new ManifestInsight(path, frameworks, count, unpinned);
    }
}

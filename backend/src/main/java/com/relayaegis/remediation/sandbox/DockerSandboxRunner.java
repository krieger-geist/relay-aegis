package com.relayaegis.remediation.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs fixed per-ecosystem build/test commands in a throwaway Docker container: no capabilities, no new privileges,
 * bounded memory/CPU/PIDs, no network by default, a generated "relay-sandbox-*" name, and always removed afterwards.
 * Only five Docker operations are used (create, cp, start, wait, rm -f); the commands are constants of this class and
 * never come from the repository, a request or AI output. Disabled unless {@code relay.sandbox.enabled=true}.
 *
 * <p>{@code relay.sandbox.docker.host} points the CLI at its own socket proxy (Compose: {@code sandbox-proxy}), separate
 * from the recovery proxy: it admits only these operations on {@code relay-sandbox-*} containers and refuses host
 * bind mounts, so recovery access never includes container creation.
 */
@Component
public class DockerSandboxRunner implements SandboxRunner {
    static final Pattern IMAGE = Pattern.compile("[a-z0-9][a-z0-9._/-]{0,200}(:[A-Za-z0-9._-]{1,128})?(@sha256:[a-f0-9]{64})?");
    static final Pattern NETWORK = Pattern.compile("none|bridge");
    static final int MAX_OUTPUT_BYTES = 256 * 1024;
    static final String OUTPUT_FILE = "/tmp/relay-output.log";

    private final boolean enabled;
    private final String command;
    private final String dockerHost;
    private final String network;
    private final String memory;
    private final String cpus;
    private final int pidsLimit;
    private final String mavenImage;
    private final String nodeImage;
    private final String pythonImage;

    public DockerSandboxRunner(@Value("${relay.sandbox.enabled:false}") boolean enabled,
                               @Value("${relay.recovery.docker.command:docker}") String command,
                               @Value("${relay.sandbox.docker.host:}") String dockerHost,
                               @Value("${relay.sandbox.network:none}") String network,
                               @Value("${relay.sandbox.memory:1g}") String memory,
                               @Value("${relay.sandbox.cpus:1}") String cpus,
                               @Value("${relay.sandbox.pids-limit:256}") int pidsLimit,
                               @Value("${relay.sandbox.images.maven:maven:3.9-eclipse-temurin-21}") String mavenImage,
                               @Value("${relay.sandbox.images.node:node:20-alpine}") String nodeImage,
                               @Value("${relay.sandbox.images.python:python:3.12-slim}") String pythonImage) {
        this.enabled = enabled;
        this.command = command;
        this.dockerHost = dockerHost == null ? "" : dockerHost.trim();
        this.network = NETWORK.matcher(network).matches() ? network : "none";
        this.memory = memory.matches("[0-9]{1,5}[mg]") ? memory : "1g";
        this.cpus = cpus.matches("[0-9]{1,2}(\\.[0-9]{1,2})?") ? cpus : "1";
        this.pidsLimit = Math.max(32, Math.min(4096, pidsLimit));
        this.mavenImage = image(mavenImage);
        this.nodeImage = image(nodeImage);
        this.pythonImage = image(pythonImage);
    }

    @Override public boolean available() {
        return enabled && exec(List.of("info", "--format", "{{.ServerVersion}}"), Duration.ofSeconds(5)).exitCode() == 0;
    }

    @Override public String describeIsolation() {
        return "Disposable Docker container (network " + network + ", memory " + memory + ", cpus " + cpus + ", pids " + pidsLimit
                + ", all capabilities dropped, no-new-privileges); removed after the run.";
    }

    /** The fixed command for each ecosystem and phase. Package scripts are never run during install. */
    static String commandFor(Ecosystem ecosystem, Phase phase) {
        return switch (ecosystem) {
            case MAVEN -> phase == Phase.BUILD ? "mvn -B -q -DskipTests package" : "mvn -B -q test";
            case NPM -> phase == Phase.BUILD ? "npm ci --ignore-scripts --no-audit --no-fund && npm run build --if-present"
                                             : "npm ci --ignore-scripts --no-audit --no-fund && npm test --if-present";
            case PYTHON -> phase == Phase.BUILD ? "python -m compileall -q ."
                                                : "(test -f requirements.txt && pip install -q -r requirements.txt || true) && python -m pytest -q";
        };
    }

    String imageFor(Ecosystem ecosystem) {
        return switch (ecosystem) { case MAVEN -> mavenImage; case NPM -> nodeImage; case PYTHON -> pythonImage; };
    }

    @Override
    public PhaseResult run(Path workspace, Ecosystem ecosystem, Phase phase, Duration timeout) {
        long started = System.nanoTime();
        if (!enabled) return new PhaseResult("not_available", -1, "Sandbox execution is disabled (relay.sandbox.enabled=false).", 0);
        String name = "relay-sandbox-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        try {
            Exec created = exec(List.of("create", "--name", name, "--network", network, "--memory", memory, "--cpus", cpus,
                    "--pids-limit", Integer.toString(pidsLimit), "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                    "--workdir", "/workspace", "--env", "CI=true", imageFor(ecosystem), "sh", "-c",
                    "(" + commandFor(ecosystem, phase) + ") > " + OUTPUT_FILE + " 2>&1"),
                    Duration.ofSeconds(60));
            if (created.exitCode() != 0) return new PhaseResult("not_available", -1, "The sandbox container could not be created (image missing or Docker unavailable).", elapsed(started));
            Exec copied = exec(List.of("cp", workspace.toAbsolutePath() + "/.", name + ":/workspace"), Duration.ofSeconds(60));
            if (copied.exitCode() != 0) return new PhaseResult("not_available", -1, "The workspace could not be copied into the sandbox.", elapsed(started));
            // Every call addresses the container by its name. "start -a" and "logs" look the container up and then use its
            // id, which a name-scoped proxy rule cannot admit without admitting every container on the host; so the build
            // writes its output to a file in the sandbox, copied out by name afterwards.
            Exec launched = exec(List.of("start", name), Duration.ofSeconds(30));
            if (launched.exitCode() != 0) return new PhaseResult("not_available", -1, "The sandbox container could not be started.", elapsed(started));
            Exec waited = exec(List.of("wait", name), timeout);
            String output = copyOutput(name);
            if (waited.timedOut()) return new PhaseResult("timed_out", -1, tail(output), elapsed(started));
            Integer exitCode = exitCode(waited);
            if (exitCode == null) return new PhaseResult("not_available", -1, "The sandbox result could not be read.", elapsed(started));
            return new PhaseResult(exitCode == 0 ? "passed" : "failed", exitCode, tail(output), elapsed(started));
        } finally {
            exec(List.of("rm", "-f", name), Duration.ofSeconds(30));
        }
    }

    /** Copies the build output file out of the sandbox; empty when it cannot be read. */
    String copyOutput(String name) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("relay-sandbox-out-");
            Path target = dir.resolve("output.log");
            if (exec(List.of("cp", name + ":" + OUTPUT_FILE, target.toAbsolutePath().toString()), Duration.ofSeconds(30)).exitCode() != 0
                    || !Files.isRegularFile(target)) return "";
            return read(target);
        } catch (IOException ex) {
            return "";
        } finally {
            if (dir != null) {
                try { Files.deleteIfExists(dir.resolve("output.log")); Files.deleteIfExists(dir); } catch (IOException ignored) { /* temp dir */ }
            }
        }
    }

    /** "docker wait" prints the container's exit code; null when the command failed or printed something else. */
    static Integer exitCode(Exec waited) {
        if (waited.exitCode() != 0 || waited.output() == null) return null;
        String text = waited.output().trim();
        return text.matches("-?[0-9]{1,4}") ? Integer.valueOf(text) : null;
    }

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

    /** Last 20 lines, control characters removed and credential-like assignments masked. */
    static String tail(String output) {
        if (output == null || output.isBlank()) return "";
        String[] lines = output.split("\\r?\\n");
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, lines.length - 20); i < lines.length; i++) {
            String line = lines[i].replaceAll("[\\p{Cntrl}&&[^\\t]]", "")
                    .replaceAll("(?i)(password|passwd|secret|token|api[_-]?key)(\\s*[:=]\\s*)\\S+", "$1$2[REDACTED]");
            out.append(line.length() > 300 ? line.substring(0, 300) + "..." : line).append('\n');
        }
        return out.toString().trim();
    }

    private static String image(String value) {
        if (value == null || !IMAGE.matcher(value).matches()) throw new IllegalArgumentException("Invalid sandbox image reference.");
        return value;
    }

    record Exec(int exitCode, String output, boolean timedOut) {}

    Exec exec(List<String> args, Duration timeout) {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        commandLine.addAll(args);
        Path output = null;
        try {
            output = Files.createTempFile("relay-sandbox-", ".out");
            ProcessBuilder builder = new ProcessBuilder(commandLine).redirectErrorStream(true).redirectOutput(output.toFile());
            if (!dockerHost.isEmpty()) builder.environment().put("DOCKER_HOST", dockerHost);
            Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Exec(-1, read(output), true);
            }
            return new Exec(process.exitValue(), read(output), false);
        } catch (IOException ex) {
            return new Exec(-1, "", false);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Exec(-1, "", false);
        } finally {
            if (output != null) { try { Files.deleteIfExists(output); } catch (IOException ignored) { /* temp file */ } }
        }
    }

    private static String read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) { return new String(in.readNBytes(MAX_OUTPUT_BYTES), StandardCharsets.UTF_8); }
    }
}

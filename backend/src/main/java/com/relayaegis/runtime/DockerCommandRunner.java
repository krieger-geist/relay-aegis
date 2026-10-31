package com.relayaegis.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The only path from RELAY AEGIS to the Docker CLI. It exposes typed operations with fixed argument shapes:
 * read-only inspection (inspect, stats, logs, ps, info) and the two lifecycle mutations recovery may use
 * (start, restart). Container, project and service names are validated; no caller - and never AI output - can
 * pass free-form arguments. Output is bounded and never logged (logs may contain sensitive data).
 *
 * <p>When {@code relay.recovery.docker.host} is set (Compose sets {@code tcp://docker-proxy:2375}), the CLI talks to a
 * socket proxy that admits only the API calls listed above, so even a bug here cannot create, exec into or delete
 * containers.
 */
@Component
public class DockerCommandRunner {
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    static final int MAX_OUTPUT_BYTES = 512 * 1024;
    static final Duration INSPECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration MUTATION_TIMEOUT = Duration.ofSeconds(20);

    private final String command;
    private final String dockerHost;

    public DockerCommandRunner(@Value("${relay.recovery.docker.command:docker}") String command,
                               @Value("${relay.recovery.docker.host:}") String dockerHost) {
        this.command = command;
        this.dockerHost = dockerHost == null ? "" : dockerHost.trim();
    }

    /** exitCode -1 means the process could not run or exceeded its deadline (see {@code failure}). */
    public record Result(int exitCode, String output, String failure) {
        public boolean ok() { return exitCode == 0 && failure == null; }
    }

    public Result inspectContainer(String container) {
        return execute(List.of("inspect", "--type", "container", name(container)), INSPECT_TIMEOUT);
    }

    public Result stats(String container) {
        return execute(List.of("stats", "--no-stream", "--format", "{{json .}}", name(container)), INSPECT_TIMEOUT);
    }

    public Result logs(String container, int tail) {
        int bounded = Math.max(1, Math.min(500, tail));
        return execute(List.of("logs", "--tail", Integer.toString(bounded), "--timestamps", name(container)), INSPECT_TIMEOUT);
    }

    /** Names of containers belonging to one Compose service (used to resolve depends_on). */
    public Result composeServiceContainers(String project, String service) {
        return execute(List.of("ps", "-a", "--filter", "label=com.docker.compose.project=" + name(project),
                "--filter", "label=com.docker.compose.service=" + name(service), "--format", "{{.Names}}"), INSPECT_TIMEOUT);
    }

    public Result info() {
        return execute(List.of("info", "--format", "{{.ServerVersion}}"), Duration.ofSeconds(3));
    }

    public Result start(String container) {
        return execute(List.of("start", name(container)), MUTATION_TIMEOUT);
    }

    public Result restart(String container) {
        return execute(List.of("restart", name(container)), MUTATION_TIMEOUT);
    }

    /** Read-only: the labels of a network (used to confirm it belongs to the expected Compose project). */
    public Result networkLabels(String network) {
        return execute(List.of("network", "inspect", "--format", "{{json .Labels}}", name(network)), INSPECT_TIMEOUT);
    }

    /** Reattaches a container to a network under one alias. All three values are validated names, never free text. */
    public Result networkConnect(String network, String container, String alias) {
        return execute(List.of("network", "connect", "--alias", name(alias), name(network), name(container)), MUTATION_TIMEOUT);
    }

    static String name(String value) {
        if (value == null || !NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Docker object name.");
        }
        return value;
    }

    Result execute(List<String> args, Duration timeout) {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        commandLine.addAll(args);
        Path output = null;
        try {
            output = Files.createTempFile("relay-docker-", ".out");
            ProcessBuilder builder = new ProcessBuilder(commandLine).redirectErrorStream(true).redirectOutput(output.toFile());
            if (!dockerHost.isEmpty()) builder.environment().put("DOCKER_HOST", dockerHost);
            Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Result(-1, "", "Docker command exceeded its " + timeout.toSeconds() + " s deadline.");
            }
            return new Result(process.exitValue(), readBounded(output), null);
        } catch (IOException ex) {
            return new Result(-1, "", "Docker CLI could not be started.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Result(-1, "", "Docker command was interrupted.");
        } finally {
            if (output != null) { try { Files.deleteIfExists(output); } catch (IOException ignored) { /* temp file */ } }
        }
    }

    private static String readBounded(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(MAX_OUTPUT_BYTES);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}

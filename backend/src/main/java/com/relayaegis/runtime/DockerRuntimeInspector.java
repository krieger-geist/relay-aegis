package com.relayaegis.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.ai.EvidenceSanitizer;
import com.relayaegis.service.DockerControlService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Read-only runtime inspection of allowlisted containers: status, restart count, exit code, OOMKilled, health,
 * CPU, memory, ports, networks and logs. Environment variables, mounts and command lines are never read.
 */
@Component
public class DockerRuntimeInspector {
    static final int LOG_TAIL = 200;
    static final int MAX_STORED_LINES = 60;

    private static final Pattern OOM = Pattern.compile("(?i)(OutOfMemoryError|JavaScript heap out of memory|\\bout of memory\\b|oom-kill|Killed process \\d+|Cannot allocate memory|\\bMemoryError\\b)");
    /** Includes the JDBC driver form "Connection to postgres:5432 refused" (PostgreSQL), not only the libc/Node wording. */
    private static final Pattern REFUSED = Pattern.compile("(?i)(ECONNREFUSED|Connection refused|Connection to \\S+ refused)");
    private static final Pattern DNS = Pattern.compile("(?i)(ENOTFOUND|EAI_AGAIN|UnknownHostException|Name or service not known|getaddrinfo)");
    private static final Pattern TIMEOUT = Pattern.compile("(?i)(ETIMEDOUT|SocketTimeoutException|TimeoutException|\\btimed out\\b)");
    /** A dependency (database, cache, broker) rejected the application's credentials. */
    private static final Pattern AUTH_FAILURE = Pattern.compile("(?i)(password authentication failed|authentication failed for user|Access denied for user|Login failed for user|SASL authentication failed|WRONGPASS|NOAUTH Authentication required)");
    private static final Pattern PORT_IN_USE = Pattern.compile("(?i)(EADDRINUSE|Address already in use)");
    private static final Pattern FATAL = Pattern.compile("(?i)(\\bFATAL\\b|panic:|Unhandled|uncaughtException|\\bSegmentation fault\\b)");
    private static final Pattern EXCEPTION = Pattern.compile("(?:\\b[A-Z][A-Za-z0-9]*(?:Exception|Error)\\b|Traceback \\(most recent call last\\)|\\bERROR\\b)");
    private static final Pattern LISTENING = Pattern.compile("(?i)listening (?:on|at)\\s+(?:port\\s+)?(?:[a-z0-9_.:\\[\\]]*:)?(\\d{2,5})\\b");
    private static final Pattern TARGET = Pattern.compile("(?i)\\b((?:\\d{1,3}(?:\\.\\d{1,3}){3})|(?:[a-z][a-z0-9_-]*(?:\\.[a-z0-9_-]+)*)):(\\d{2,5})\\b");
    private static final Pattern TIMESTAMP_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z\\s+");

    private final DockerCommandRunner runner;
    private final DockerControlService docker;
    private final ObjectMapper mapper;

    public DockerRuntimeInspector(DockerCommandRunner runner, DockerControlService docker, ObjectMapper mapper) {
        this.runner = runner;
        this.docker = docker;
        this.mapper = mapper;
    }

    public boolean canInspect(String container) { return docker.canInspect(container); }

    /** Empty when inspection is not permitted for this container; a "missing" snapshot when Docker has no such container. */
    public Optional<ContainerSnapshot> inspect(String container) {
        if (!canInspect(container)) return Optional.empty();
        DockerCommandRunner.Result result = runner.inspectContainer(container);
        Instant now = Instant.now();
        if (result.failure() != null) return Optional.empty();
        if (!result.ok()) return Optional.of(ContainerSnapshot.missing(container, now));
        try {
            JsonNode root = mapper.readTree(result.output());
            JsonNode item = root.isArray() ? root.path(0) : root;
            if (item.isMissingNode() || item.isNull()) return Optional.of(ContainerSnapshot.missing(container, now));
            ContainerSnapshot base = parseInspect(container, item, now);
            if (!base.running()) return Optional.of(base);
            DockerCommandRunner.Result stats = runner.stats(container);
            return Optional.of(stats.ok() ? withStats(base, stats.output()) : base);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public LogExcerpt logs(String container) {
        if (!canInspect(container)) return LogExcerpt.unavailable(container, "Log access is not permitted for this container (not in the inspection allowlist).");
        DockerCommandRunner.Result result = runner.logs(container, LOG_TAIL);
        if (!result.ok()) return LogExcerpt.unavailable(container, result.failure() != null ? result.failure() : "Docker could not return logs for this container.");
        return parseLogs(container, result.output());
    }

    static ContainerSnapshot parseInspect(String container, JsonNode item, Instant now) {
        JsonNode state = item.path("State");
        JsonNode health = state.path("Health");
        JsonNode host = item.path("HostConfig");
        JsonNode config = item.path("Config");
        List<String> published = new ArrayList<>();
        JsonNode ports = item.path("NetworkSettings").path("Ports");
        for (Iterator<Map.Entry<String, JsonNode>> it = ports.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (entry.getValue().isArray()) {
                for (JsonNode binding : entry.getValue()) published.add(entry.getKey() + "->" + binding.path("HostIp").asText("") + ":" + binding.path("HostPort").asText(""));
            }
        }
        List<String> exposed = new ArrayList<>();
        config.path("ExposedPorts").fieldNames().forEachRemaining(exposed::add);
        ports.fieldNames().forEachRemaining(key -> { if (!exposed.contains(key)) exposed.add(key); });
        List<String> networks = new ArrayList<>();
        item.path("NetworkSettings").path("Networks").fieldNames().forEachRemaining(networks::add);
        Map<String, String> compose = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = config.path("Labels").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> label = it.next();
            if (label.getKey().startsWith("com.docker.compose.")) compose.put(label.getKey(), label.getValue().asText(""));
        }
        long memory = host.path("Memory").asLong(0);
        return new ContainerSnapshot(container, true,
                state.path("Status").asText("unknown").toLowerCase(Locale.ROOT),
                state.path("Running").asBoolean(false), state.path("Restarting").asBoolean(false),
                state.path("OOMKilled").asBoolean(false),
                state.has("ExitCode") ? state.path("ExitCode").asInt() : null,
                item.path("RestartCount").asInt(0),
                instant(state.path("StartedAt").asText(null)), instant(state.path("FinishedAt").asText(null)),
                health.isMissingNode() || health.isNull() ? null : health.path("Status").asText(null),
                health.isMissingNode() || health.isNull() ? null : health.path("FailingStreak").asInt(0),
                memory > 0 ? memory : null, null, null, null,
                List.copyOf(published), List.copyOf(exposed), List.copyOf(networks),
                EvidenceSanitizer.clean(config.path("Image").asText(""), 160),
                host.path("RestartPolicy").path("Name").asText(null),
                Map.copyOf(compose), now);
    }

    static ContainerSnapshot withStats(ContainerSnapshot s, String statsJson) {
        try {
            JsonNode stats = new ObjectMapper().readTree(statsJson.trim().split("\n")[0]);
            Double cpu = percent(stats.path("CPUPerc").asText(null));
            Double memPercent = percent(stats.path("MemPerc").asText(null));
            String usage = stats.path("MemUsage").asText("");
            Long used = usage.contains("/") ? bytes(usage.substring(0, usage.indexOf('/')).trim()) : null;
            return new ContainerSnapshot(s.name(), s.exists(), s.status(), s.running(), s.restarting(), s.oomKilled(), s.exitCode(),
                    s.restartCount(), s.startedAt(), s.finishedAt(), s.healthStatus(), s.healthFailingStreak(), s.memoryLimitBytes(),
                    cpu, used, memPercent, s.publishedPorts(), s.exposedPorts(), s.networks(), s.image(), s.restartPolicy(),
                    s.composeLabels(), s.inspectedAt());
        } catch (Exception ex) {
            return s;
        }
    }

    static LogExcerpt parseLogs(String container, String raw) {
        List<String> all = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            if (line.isBlank()) continue;
            all.add(EvidenceSanitizer.clean(TIMESTAMP_PREFIX.matcher(line).replaceFirst(""), 300));
        }
        List<String> kept = all.subList(Math.max(0, all.size() - MAX_STORED_LINES), all.size());
        List<LogExcerpt.LogSignal> signals = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            String line = kept.get(i);
            if (OOM.matcher(line).find()) signals.add(new LogExcerpt.LogSignal(LogExcerpt.LogSignal.Kind.OOM, i, line, null));
            if (REFUSED.matcher(line).find()) signals.add(new LogExcerpt.LogSignal(LogExcerpt.LogSignal.Kind.CONNECTION_REFUSED, i, line, target(line)));
            if (DNS.matcher(line).find()) signals.add(new LogExcerpt.LogSignal(LogExcerpt.LogSignal.Kind.DNS_FAILURE, i, line, target(line)));
            if (TIMEOUT.matcher(line).find()) signals.add(new LogExcerpt.LogSignal(LogExcerpt.LogSignal.Kind.TIMEOUT, i, line, target(line)));
            if (AUTH_FAILURE.matcher(line).find()) signals.add(new LogExcerpt.LogSignal(LogExcerpt.LogSignal.Kind.AUTH_FAILURE, i, line, null));
            if (PORT_IN_USE.matcher(line).find()) signals.add(new LogExcerpt.LogSignal(LogExcerpt.LogSignal.Kind.PORT_IN_USE, i, line, null));
            if (FATAL.matcher(line).find()) signals.add(new LogExcerpt.LogSignal(LogExcerpt.LogSignal.Kind.FATAL, i, line, null));
            else if (EXCEPTION.matcher(line).find()) signals.add(new LogExcerpt.LogSignal(LogExcerpt.LogSignal.Kind.EXCEPTION, i, line, null));
            Matcher listening = LISTENING.matcher(line);
            if (listening.find()) signals.add(new LogExcerpt.LogSignal(LogExcerpt.LogSignal.Kind.LISTENING, i, line, listening.group(1)));
        }
        return new LogExcerpt(container, true, null, List.copyOf(kept), List.copyOf(signals));
    }

    private static String target(String line) {
        Matcher match = TARGET.matcher(line);
        return match.find() ? match.group(1).toLowerCase(Locale.ROOT) + ":" + match.group(2) : null;
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank() || value.startsWith("0001-")) return null;
        try { return Instant.parse(value); } catch (Exception ex) { return null; }
    }

    static Double percent(String value) {
        if (value == null) return null;
        try { return Double.parseDouble(value.replace("%", "").trim()); } catch (NumberFormatException ex) { return null; }
    }

    static Long bytes(String value) {
        Matcher match = Pattern.compile("([0-9.]+)\\s*([KMGT]?i?B)", Pattern.CASE_INSENSITIVE).matcher(value);
        if (!match.find()) return null;
        double number = Double.parseDouble(match.group(1));
        String unit = match.group(2).toUpperCase(Locale.ROOT);
        double factor = switch (unit) {
            case "KIB" -> 1024d; case "MIB" -> 1024d * 1024; case "GIB" -> 1024d * 1024 * 1024; case "TIB" -> 1024d * 1024 * 1024 * 1024;
            case "KB" -> 1e3; case "MB" -> 1e6; case "GB" -> 1e9; case "TB" -> 1e12;
            default -> 1d;
        };
        return Math.round(number * factor);
    }
}

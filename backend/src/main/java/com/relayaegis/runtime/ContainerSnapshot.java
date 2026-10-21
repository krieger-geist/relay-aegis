package com.relayaegis.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Point-in-time runtime state of one container, read via {@code docker inspect} and {@code docker stats}.
 * Environment variables and mounts are deliberately never read (they commonly hold secrets).
 */
public record ContainerSnapshot(
        String name,
        boolean exists,
        String status,
        boolean running,
        boolean restarting,
        boolean oomKilled,
        Integer exitCode,
        int restartCount,
        Instant startedAt,
        Instant finishedAt,
        String healthStatus,
        Integer healthFailingStreak,
        Long memoryLimitBytes,
        Double cpuPercent,
        Long memoryUsageBytes,
        Double memoryPercent,
        List<String> publishedPorts,
        List<String> exposedPorts,
        List<String> networks,
        String image,
        String restartPolicy,
        Map<String, String> composeLabels,
        Instant inspectedAt) {

    public static ContainerSnapshot missing(String name, Instant at) {
        return new ContainerSnapshot(name, false, "missing", false, false, false, null, 0, null, null, null, null,
                null, null, null, null, List.of(), List.of(), List.of(), null, null, Map.of(), at);
    }

    public String composeProject() { return composeLabels.get("com.docker.compose.project"); }
    public String composeService() { return composeLabels.get("com.docker.compose.service"); }
    public String composeDependsOn() { return composeLabels.get("com.docker.compose.depends_on"); }

    /** Up and, when Docker has a health check, reported healthy. */
    public boolean healthy() {
        return running && !restarting && (healthStatus == null || "healthy".equals(healthStatus));
    }

    public String describe() {
        StringBuilder text = new StringBuilder("status=").append(status);
        if (exitCode != null && !running) text.append(", exitCode=").append(exitCode);
        if (oomKilled) text.append(", OOMKilled=true");
        text.append(", restartCount=").append(restartCount);
        if (healthStatus != null) text.append(", health=").append(healthStatus);
        if (memoryLimitBytes != null && memoryLimitBytes > 0) text.append(", memoryLimit=").append(memoryLimitBytes / (1024 * 1024)).append("MiB");
        if (cpuPercent != null) text.append(", cpu=").append(String.format("%.1f%%", cpuPercent));
        if (memoryPercent != null) text.append(", memory=").append(String.format("%.1f%%", memoryPercent));
        return text.toString();
    }
}

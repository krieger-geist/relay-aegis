package com.relayaegis.runtime;

import com.relayaegis.dto.RuntimeDtos.LogSignalSummaryDto;
import com.relayaegis.dto.RuntimeDtos.RuntimeComponentDto;
import com.relayaegis.dto.RuntimeDtos.SystemRuntimeDto;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.service.ApplicationService;
import com.relayaegis.service.DockerControlService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Level 1 runtime evidence of a System, outside of any incident: the registered container, its dependencies and the
 * failure signals in its recent logs. Read-only; uses the same inspection allowlist as diagnosis, so a container RELAY
 * may not inspect is reported as not observable rather than guessed. Environment variables and mounts are never read.
 */
@Service
public class SystemRuntimeService {
    private final ApplicationService applications;
    private final DockerControlService docker;
    private final DockerRuntimeInspector inspector;
    private final DependencyGraphService dependencyGraph;

    public SystemRuntimeService(ApplicationService applications, DockerControlService docker, DockerRuntimeInspector inspector,
                                DependencyGraphService dependencyGraph) {
        this.applications = applications;
        this.docker = docker;
        this.inspector = inspector;
        this.dependencyGraph = dependencyGraph;
    }

    public SystemRuntimeDto runtime(String applicationId) {
        ApplicationEntity app = applications.findEntity(applicationId);
        List<String> unavailable = new ArrayList<>();
        String container = app.dockerContainer == null || app.dockerContainer.isBlank() ? null : app.dockerContainer;
        if (container == null) {
            unavailable.add("No container is registered for this system.");
            return new SystemRuntimeDto(app.id, docker.isEnabled(), null, List.of(), false, "No container is registered.", List.of(), unavailable, Instant.now());
        }
        if (!inspector.canInspect(container)) {
            unavailable.add("Docker inspection is disabled or " + container + " is not in the inspection allowlist.");
            RuntimeComponentDto component = new RuntimeComponentDto("application", container, container, "registered", false, docker.isAllowed(container),
                    "not_observable", "Not in the inspection allowlist.", null, null);
            return new SystemRuntimeDto(app.id, docker.isEnabled(), component, List.of(), false, "Log access is not permitted for this container.", List.of(), unavailable, Instant.now());
        }
        ContainerSnapshot snapshot = inspector.inspect(container).orElse(null);
        if (snapshot == null) unavailable.add("Docker inspection of " + container + " did not complete.");
        RuntimeComponentDto application = component("application", container, container, "registered", snapshot, true, docker.isAllowed(container));

        List<RuntimeComponentDto> dependencies = new ArrayList<>();
        for (DependencyGraphService.DependencyNode node : dependencyGraph.dependenciesOf(container, snapshot)) {
            ContainerSnapshot dependency = node.inspectable() && node.container() != null ? inspector.inspect(node.container()).orElse(null) : null;
            if (dependency == null) unavailable.add("Dependency " + node.name() + ": state not observable (not in the inspection allowlist).");
            RuntimeComponentDto dep = component("dependency", node.name(), node.container(), node.source(), dependency, node.inspectable(), node.recoveryAllowed());
            var detached = NetworkPartitions.reattachment(snapshot, dependency);
            if (detached.isPresent() && "healthy".equals(dep.state())) {
                // Observed: the dependency runs, but is not on the application's Compose network, so the application cannot reach it.
                dep = new RuntimeComponentDto(dep.role(), dep.name(), dep.container(), dep.source(), dep.inspectable(), dep.recoverable(), "detached",
                        "running but not attached to " + detached.get().network() + " · " + dep.detail(), dep.restartCount(), dep.startedAt());
            }
            dependencies.add(dep);
        }

        LogExcerpt logs = inspector.logs(container);
        return new SystemRuntimeDto(app.id, docker.isEnabled(), application, List.copyOf(dependencies), logs.available(), logs.unavailableReason(),
                summarize(logs), List.copyOf(unavailable), Instant.now());
    }

    static RuntimeComponentDto component(String role, String name, String container, String source, ContainerSnapshot snapshot,
                                         boolean inspectable, boolean recoverable) {
        if (snapshot == null) {
            return new RuntimeComponentDto(role, name, container, source, inspectable, recoverable, "not_observable",
                    inspectable ? "Docker did not return its state." : "Not in the inspection allowlist.", null, null);
        }
        return new RuntimeComponentDto(role, name, container, source, inspectable, recoverable, state(snapshot), snapshot.describe(),
                snapshot.restartCount(), snapshot.startedAt());
    }

    /** Observed state only: a running container with a failing Docker health check is unhealthy, not healthy. */
    static String state(ContainerSnapshot snapshot) {
        if (!snapshot.exists()) return "missing";
        if (snapshot.restarting()) return "restarting";
        if (!snapshot.running()) return "stopped";
        if ("unhealthy".equals(snapshot.healthStatus())) return "unhealthy";
        if ("starting".equals(snapshot.healthStatus())) return "starting";
        return "healthy";
    }

    static List<LogSignalSummaryDto> summarize(LogExcerpt logs) {
        if (logs == null || !logs.available()) return List.of();
        Map<LogExcerpt.LogSignal.Kind, List<LogExcerpt.LogSignal>> byKind = new LinkedHashMap<>();
        for (LogExcerpt.LogSignal signal : logs.signals()) {
            if (signal.kind() == LogExcerpt.LogSignal.Kind.LISTENING) continue; // informational, not a failure signal
            byKind.computeIfAbsent(signal.kind(), kind -> new ArrayList<>()).add(signal);
        }
        List<LogSignalSummaryDto> out = new ArrayList<>();
        byKind.forEach((kind, signals) -> {
            String latest = signals.get(signals.size() - 1).line();
            out.add(new LogSignalSummaryDto(kind.name(), signals.size(), latest.length() > 220 ? latest.substring(0, 220) + "…" : latest));
        });
        return List.copyOf(out);
    }
}

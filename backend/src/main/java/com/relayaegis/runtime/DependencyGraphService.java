package com.relayaegis.runtime;

import com.relayaegis.service.DockerControlService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Dependency graph for Docker/Compose applications, from two sources:
 * <ul>
 *   <li>Compose: the {@code com.docker.compose.depends_on} label of the application container, resolved to
 *       container names within the same Compose project;</li>
 *   <li>Configuration: {@code relay.docker.dependencies}, e.g. {@code app=db|cache;worker=queue}.</li>
 * </ul>
 * A dependency is inspected only when it is in the inspection allowlist and restarted only when it is in the
 * recovery allowlist and the dependency-recovery policy authorizes it.
 */
@Component
public class DependencyGraphService {
    private final DockerCommandRunner runner;
    private final DockerControlService docker;
    private final Map<String, List<String>> configured;

    public DependencyGraphService(DockerCommandRunner runner, DockerControlService docker,
            @Value("${relay.docker.dependencies:}") String configuredDependencies) {
        this.runner = runner;
        this.docker = docker;
        this.configured = parse(configuredDependencies);
    }

    public record DependencyNode(String name, String container, String source, boolean inspectable, boolean recoveryAllowed) {}

    static Map<String, List<String>> parse(String raw) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return graph;
        for (String entry : raw.split(";")) {
            String[] parts = entry.split("=", 2);
            if (parts.length != 2 || parts[0].isBlank()) continue;
            List<String> deps = new ArrayList<>();
            for (String dep : parts[1].split("\\|")) {
                String name = dep.trim();
                if (DockerCommandRunner.NAME.matcher(name).matches()) deps.add(name);
            }
            if (!deps.isEmpty() && DockerCommandRunner.NAME.matcher(parts[0].trim()).matches()) graph.put(parts[0].trim(), List.copyOf(deps));
        }
        return graph;
    }

    /** Direct dependencies of an application container, deduplicated by container name. */
    public List<DependencyNode> dependenciesOf(String appContainer, ContainerSnapshot appSnapshot) {
        Map<String, DependencyNode> nodes = new LinkedHashMap<>();
        if (appContainer == null) return List.of();
        for (String dep : configured.getOrDefault(appContainer, List.of())) {
            nodes.putIfAbsent(dep, node(dep, dep, "configured"));
        }
        if (appSnapshot != null && appSnapshot.composeDependsOn() != null && appSnapshot.composeProject() != null) {
            for (String spec : appSnapshot.composeDependsOn().split(",")) {
                String service = spec.split(":")[0].trim();
                if (service.isBlank() || !DockerCommandRunner.NAME.matcher(service).matches()) continue;
                String container = resolveComposeService(appSnapshot.composeProject(), service);
                String key = container != null ? container : "service:" + service;
                nodes.putIfAbsent(key, container != null ? node(service, container, "compose") : new DependencyNode(service, null, "compose", false, false));
            }
        }
        return List.copyOf(nodes.values());
    }

    private DependencyNode node(String name, String container, String source) {
        return new DependencyNode(name, container, source, docker.canInspect(container), docker.isAllowed(container));
    }

    private String resolveComposeService(String project, String service) {
        try {
            DockerCommandRunner.Result result = runner.composeServiceContainers(project, service);
            if (!result.ok()) return null;
            for (String line : result.output().split("\\r?\\n")) {
                String name = line.trim();
                if (!name.isBlank() && DockerCommandRunner.NAME.matcher(name).matches()) return name;
            }
        } catch (IllegalArgumentException ignored) { /* invalid project/service label */ }
        return null;
    }
}

package com.relayaegis.service;

import com.relayaegis.runtime.DockerCommandRunner;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Docker policy gate. Mutations (start/restart) are limited to the recovery allowlist; read-only inspection is
 * limited to the recovery allowlist plus explicitly listed dependency containers. All commands go through
 * {@link DockerCommandRunner}, which only offers fixed, typed operations.
 */
@Service
public class DockerControlService {
    private final boolean enabled;
    private final Set<String> allowedContainers;
    private final Set<String> inspectableContainers;
    private final DockerCommandRunner runner;

    public DockerControlService(
            @Value("${relay.recovery.docker.enabled:false}") boolean enabled,
            @Value("${relay.recovery.docker.allowed-containers:relay-aegis-test-target}") String allowed,
            @Value("${relay.docker.inspect-allowed-containers:}") String inspectOnly,
            DockerCommandRunner runner) {
        this.enabled = enabled;
        this.allowedContainers = parse(allowed);
        Set<String> inspectable = new HashSet<>(allowedContainers);
        inspectable.addAll(parse(inspectOnly));
        this.inspectableContainers = Set.copyOf(inspectable);
        this.runner = runner;
    }

    private static Set<String> parse(String csv) {
        if (csv == null) return Set.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toUnmodifiableSet());
    }

    public boolean isEnabled() { return enabled; }
    /** May this container be started or restarted by recovery? */
    public boolean isAllowed(String container) { return container != null && allowedContainers.contains(container); }
    /** May this container be inspected (state, stats, logs)? Read-only. */
    public boolean canInspect(String container) { return enabled && container != null && inspectableContainers.contains(container); }

    public String restartContainer(String container) { return mutate("restart", container); }
    public String startContainer(String container) { return mutate("start", container); }

    /** Labels of a network, read-only; empty when Docker cannot answer. */
    public String networkLabels(String network) {
        if (!enabled) return "";
        DockerCommandRunner.Result result = runner.networkLabels(network);
        return result.ok() ? result.output().trim() : "";
    }

    /** Reattaches an allowlisted container to a network under its alias. Same gate as start/restart. */
    public String reconnectNetwork(String container, String network, String alias) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.CONFLICT, "Docker recovery is disabled by configuration. Review the environment and container allowlist before enabling it.");
        if (!isAllowed(container)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Container is not in RELAY's configured recovery allowlist.");
        DockerCommandRunner.Result result = runner.networkConnect(network, container, alias);
        if (result.failure() != null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, result.failure());
        if (result.exitCode() != 0) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Docker could not reconnect the allowlisted container to its network.");
        return result.output().trim();
    }

    public boolean isAvailable() {
        return enabled && runner.info().ok();
    }

    private String mutate(String action, String container) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.CONFLICT, "Docker recovery is disabled by configuration. Review the environment and container allowlist before enabling it.");
        if (!isAllowed(container)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Container is not in RELAY's configured recovery allowlist.");
        DockerCommandRunner.Result result = "start".equals(action) ? runner.start(container) : runner.restart(container);
        if (result.failure() != null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, result.failure());
        if (result.exitCode() != 0) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Docker " + action + " failed for the allowlisted container.");
        return result.output().trim();
    }
}

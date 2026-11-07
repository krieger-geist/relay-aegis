package com.relayaegis.runtime.kubernetes;

import com.relayaegis.runtime.kubernetes.KubernetesGateway.DeploymentState;
import com.relayaegis.runtime.kubernetes.KubernetesGateway.PodState;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Kubernetes policy gate, the counterpart of the Docker one: mutations are limited to the recovery allowlist, and
 * read-only inspection to the allowlist plus listed dependencies. The cluster's RBAC limits the same calls again, so a
 * bug here still cannot reach beyond the namespaces the chart granted.
 */
@Service
public class KubernetesControlService {
    private final boolean enabled;
    private final Set<String> allowed;
    private final Set<String> inspectable;
    private final Map<String, List<String>> dependencies;
    private final int restoreReplicas;
    private final KubernetesGateway gateway;

    @Autowired
    public KubernetesControlService(
            @Value("${relay.kubernetes.enabled:false}") boolean enabled,
            @Value("${relay.kubernetes.allowed-workloads:}") String allowed,
            @Value("${relay.kubernetes.inspect-workloads:}") String inspectOnly,
            @Value("${relay.kubernetes.dependencies:}") String dependencies,
            @Value("${relay.kubernetes.restore-replicas:1}") int restoreReplicas,
            KubernetesGateway gateway) {
        this.enabled = enabled;
        this.allowed = parse(allowed);
        Set<String> all = new HashSet<>(this.allowed);
        all.addAll(parse(inspectOnly));
        this.inspectable = Set.copyOf(all);
        this.dependencies = parseDependencies(dependencies);
        this.restoreReplicas = Math.max(1, restoreReplicas);
        this.gateway = gateway;
    }

    /** A gate with Kubernetes off, for tools and tests that only use Docker. */
    public static KubernetesControlService disabled() {
        return new KubernetesControlService(false, "", "", "", 1, null);
    }

    private static Set<String> parse(String csv) {
        if (csv == null) return Set.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(KubernetesControlService::valid).collect(Collectors.toUnmodifiableSet());
    }

    private static boolean valid(String target) { return KubernetesTarget.parse(target).isPresent(); }

    /** {@code k8s:ns/app=k8s:ns/db|k8s:ns/cache;k8s:ns/worker=k8s:ns/queue} */
    static Map<String, List<String>> parseDependencies(String raw) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return graph;
        for (String entry : raw.split(";")) {
            String[] parts = entry.split("=", 2);
            if (parts.length != 2 || !valid(parts[0].trim())) continue;
            List<String> deps = Arrays.stream(parts[1].split("\\|")).map(String::trim).filter(KubernetesControlService::valid).toList();
            if (!deps.isEmpty()) graph.put(parts[0].trim(), deps);
        }
        return graph;
    }

    public boolean isEnabled() { return enabled; }
    public boolean isAllowed(String target) { return target != null && allowed.contains(target); }
    public boolean canInspect(String target) { return enabled && target != null && inspectable.contains(target); }
    public List<String> dependenciesOf(String target) { return dependencies.getOrDefault(target, List.of()); }
    public Set<String> inspectableTargets() { return enabled ? inspectable : Set.of(); }
    public boolean isAvailable() { return enabled && gateway.reachable(); }

    public Optional<DeploymentState> state(String target) {
        return KubernetesTarget.parse(target).flatMap(gateway::state);
    }

    public String logs(String target, PodState pod, boolean previous, int tailLines) {
        return gateway.logs(require(target), pod.name(), pod.container(), previous, tailLines);
    }

    public void rolloutRestart(String target) { gateway.rolloutRestart(mutable(target)); }
    public void rolloutUndo(String target) { gateway.rolloutUndo(mutable(target)); }
    public int restoreReplicas() { return restoreReplicas; }
    public void scale(String target, int replicas) { gateway.scale(mutable(target), replicas); }

    /**
     * Deletes the worst pod of a workload that still has other ready pods, chosen from fresh state. Refuses otherwise:
     * with no other ready pod a delete would be an outage, and {@code null} is returned instead of a pod name.
     */
    public String deleteWorstPod(String target) {
        KubernetesTarget ref = mutable(target);
        DeploymentState state = gateway.state(ref).orElse(null);
        if (state == null || state.readyReplicas() < 1) return null;
        PodState worst = state.pods().stream().filter(p -> !p.ready()).max(Comparator.comparingInt(PodState::restarts)).orElse(null);
        if (worst == null) return null;
        gateway.deletePod(ref, worst.name());
        return worst.name();
    }

    private KubernetesTarget mutable(String target) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.CONFLICT, "Kubernetes recovery is disabled by configuration.");
        if (!isAllowed(target)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The workload is not in RELAY's Kubernetes recovery allowlist.");
        return require(target);
    }

    private static KubernetesTarget require(String target) {
        return KubernetesTarget.parse(target).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a Kubernetes target."));
    }
}

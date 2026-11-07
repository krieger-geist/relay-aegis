package com.relayaegis.runtime.kubernetes;

import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.PodResource;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The real client. Connects on first use, so a Compose deployment never touches Kubernetes: inside a cluster it uses
 * the pod's service account, outside one the current kubeconfig context.
 */
@Component
public class Fabric8KubernetesGateway implements KubernetesGateway {
    private static final String REVISION = "deployment.kubernetes.io/revision";
    private static final int MAX_WARNINGS = 5;

    private volatile KubernetesClient client;

    private KubernetesClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) client = new KubernetesClientBuilder().build();
            }
        }
        return client;
    }

    @PreDestroy
    void close() { if (client != null) client.close(); }

    @Override
    public Optional<DeploymentState> state(KubernetesTarget target) {
        Deployment deployment = deployment(target);
        if (deployment == null) return Optional.empty();
        Map<String, String> selector = deployment.getSpec().getSelector().getMatchLabels();
        long revision = revision(deployment.getMetadata().getAnnotations());
        Instant rolloutStartedAt = null;
        boolean previous = false;
        for (ReplicaSet set : client().apps().replicaSets().inNamespace(target.namespace()).withLabels(selector).list().getItems()) {
            long setRevision = revision(set.getMetadata().getAnnotations());
            if (setRevision == revision && set.getMetadata().getCreationTimestamp() != null) rolloutStartedAt = Instant.parse(set.getMetadata().getCreationTimestamp());
            if (setRevision > 0 && setRevision < revision) previous = true;
        }
        List<PodState> pods = client().pods().inNamespace(target.namespace()).withLabels(selector).list().getItems().stream()
                .filter(pod -> pod.getMetadata().getDeletionTimestamp() == null)
                .map(Fabric8KubernetesGateway::podState).toList();
        var container = deployment.getSpec().getTemplate().getSpec().getContainers().get(0);
        Quantity memory = container.getResources() == null || container.getResources().getLimits() == null ? null : container.getResources().getLimits().get("memory");
        var status = deployment.getStatus();
        int desired = deployment.getSpec().getReplicas() == null ? 1 : deployment.getSpec().getReplicas();
        int ready = status == null || status.getReadyReplicas() == null ? 0 : status.getReadyReplicas();
        int updated = status == null || status.getUpdatedReplicas() == null ? 0 : status.getUpdatedReplicas();
        return Optional.of(new DeploymentState(desired, ready, updated,
                revision, previous, rolloutStartedAt, container.getImage(),
                memory == null ? null : Quantity.getAmountInBytes(memory).longValue(),
                pods, warnings(target, pods),
                rolloutComplete(deployment.getMetadata().getGeneration(), status == null ? null : status.getObservedGeneration(),
                        desired, updated, status == null || status.getReplicas() == null ? 0 : status.getReplicas(), ready)));
    }

    /**
     * Until the controller has observed the latest change, the status still describes the old pods and would look finished
     * straight after a restart or an undo.
     */
    static boolean rolloutComplete(Long generation, Long observedGeneration, int desired, int updated, int total, int ready) {
        boolean observed = generation == null || (observedGeneration != null && observedGeneration >= generation);
        return observed && updated >= desired && total == updated && ready >= desired;
    }

    static PodState podState(Pod pod) {
        var status = pod.getStatus();
        List<ContainerStatus> containers = status == null || status.getContainerStatuses() == null ? List.of() : status.getContainerStatuses();
        ContainerStatus main = containers.stream().filter(c -> !Boolean.TRUE.equals(c.getReady())).findFirst().orElse(containers.isEmpty() ? null : containers.get(0));
        int restarts = containers.stream().mapToInt(c -> c.getRestartCount() == null ? 0 : c.getRestartCount()).sum();
        boolean ready = !containers.isEmpty() && containers.stream().allMatch(c -> Boolean.TRUE.equals(c.getReady()));
        String waiting = main == null || main.getState() == null || main.getState().getWaiting() == null ? null : main.getState().getWaiting().getReason();
        if (waiting == null && status != null && status.getConditions() != null) {
            waiting = status.getConditions().stream()
                    .filter(c -> "PodScheduled".equals(c.getType()) && "False".equals(c.getStatus()))
                    .map(PodCondition::getReason).findFirst().orElse(null);
        }
        ContainerState last = main == null ? null : main.getLastState();
        String lastReason = last == null || last.getTerminated() == null ? null : last.getTerminated().getReason();
        Integer lastExit = last == null || last.getTerminated() == null ? null : last.getTerminated().getExitCode();
        return new PodState(pod.getMetadata().getName(), status == null ? "Unknown" : status.getPhase(), ready, restarts, waiting, lastReason, lastExit,
                main == null ? null : main.getName());
    }

    private List<String> warnings(KubernetesTarget target, List<PodState> pods) {
        List<Event> events = new ArrayList<>(client().v1().events().inNamespace(target.namespace()).withField("type", "Warning").list().getItems());
        events.sort(Comparator.comparing(Event::getLastTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        return events.stream()
                .filter(e -> e.getInvolvedObject() != null && e.getInvolvedObject().getName() != null
                        && (target.deployment().equals(e.getInvolvedObject().getName()) || pods.stream().anyMatch(p -> p.name().equals(e.getInvolvedObject().getName()))))
                .limit(MAX_WARNINGS)
                .map(e -> e.getReason() + ": " + e.getMessage())
                .toList();
    }

    @Override
    public String logs(KubernetesTarget target, String pod, String container, boolean previous, int tailLines) {
        PodResource resource = client().pods().inNamespace(target.namespace()).withName(pod);
        // The zero skips waiting for the pod to become ready: a crashing pod never does, and its logs are the point.
        if (previous) return resource.inContainer(container).terminated().tailingLines(tailLines).withReadyWaitTimeout(0).getLog();
        return resource.inContainer(container).tailingLines(tailLines).withReadyWaitTimeout(0).getLog();
    }

    @Override
    public void rolloutRestart(KubernetesTarget target) {
        client().apps().deployments().inNamespace(target.namespace()).withName(target.deployment()).rolling().restart();
    }

    @Override
    public void rolloutUndo(KubernetesTarget target) {
        client().apps().deployments().inNamespace(target.namespace()).withName(target.deployment()).rolling().undo();
    }

    @Override
    public void scale(KubernetesTarget target, int replicas) {
        client().apps().deployments().inNamespace(target.namespace()).withName(target.deployment()).scale(replicas);
    }

    @Override
    public void deletePod(KubernetesTarget target, String pod) {
        client().pods().inNamespace(target.namespace()).withName(pod).delete();
    }

    @Override
    public boolean reachable() {
        try { return client().getKubernetesVersion() != null; }
        catch (RuntimeException ex) { return false; }
    }

    private Deployment deployment(KubernetesTarget target) {
        return client().apps().deployments().inNamespace(target.namespace()).withName(target.deployment()).get();
    }

    private static long revision(Map<String, String> annotations) {
        if (annotations == null || annotations.get(REVISION) == null) return 0;
        try { return Long.parseLong(annotations.get(REVISION)); }
        catch (NumberFormatException ex) { return 0; }
    }
}

package com.relayaegis.runtime;

import com.relayaegis.runtime.kubernetes.KubernetesRuntimeInspector;
import com.relayaegis.runtime.kubernetes.KubernetesTarget;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Sends a target of the form {@code k8s:<namespace>/<deployment>} to Kubernetes and any other target to Docker. */
@Component
@Primary
public class RoutingRuntimeInspector implements RuntimeInspector {
    private final DockerRuntimeInspector docker;
    private final KubernetesRuntimeInspector kubernetes;

    public RoutingRuntimeInspector(DockerRuntimeInspector docker, KubernetesRuntimeInspector kubernetes) {
        this.docker = docker;
        this.kubernetes = kubernetes;
    }

    private RuntimeInspector forTarget(String target) { return KubernetesTarget.is(target) ? kubernetes : docker; }

    @Override public boolean canInspect(String target) { return forTarget(target).canInspect(target); }
    @Override public Optional<ContainerSnapshot> inspect(String target) { return forTarget(target).inspect(target); }
    @Override public LogExcerpt logs(String target) { return forTarget(target).logs(target); }
}

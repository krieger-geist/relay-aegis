package com.relayaegis.runtime.kubernetes;

import com.relayaegis.cluster.LeaderElection;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.signals.SignalService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Turns pod trouble into signals, so a failure the health URL cannot see yet (one pod of many crash looping behind a
 * Service) still opens an incident. Each state is reported through the same path as any external alert: it can only
 * start the normal diagnosis, never an action. Polling keeps this simple and RBAC small; only the leader does it.
 */
@Component
public class KubernetesSignalPoller {
    static final String SOURCE = "kubernetes";
    private static final Logger log = LoggerFactory.getLogger(KubernetesSignalPoller.class);

    private final KubernetesControlService kubernetes;
    private final KubernetesRuntimeInspector inspector;
    private final ApplicationRepository applications;
    private final SignalService signals;
    private final LeaderElection leadership;
    /** Application id to the trouble last reported as firing; in memory, so a restart re-reports once (the signal path de-duplicates). */
    private final Map<String, String> firing = new ConcurrentHashMap<>();
    private final Set<String> unreadyLastPoll = ConcurrentHashMap.newKeySet();

    public KubernetesSignalPoller(KubernetesControlService kubernetes, KubernetesRuntimeInspector inspector, ApplicationRepository applications,
            SignalService signals, LeaderElection leadership) {
        this.kubernetes = kubernetes;
        this.inspector = inspector;
        this.applications = applications;
        this.signals = signals;
        this.leadership = leadership;
    }

    @Scheduled(fixedDelayString = "${relay.kubernetes.signal-interval-ms:15000}", initialDelayString = "${relay.kubernetes.signal-initial-delay-ms:20000}")
    public void poll() {
        if (!kubernetes.isEnabled() || !leadership.isLeader()) return;
        Set<String> targets = kubernetes.inspectableTargets();
        for (ApplicationEntity app : List.copyOf(applications.findAll())) {
            if (!app.monitoringEnabled || app.dockerContainer == null || !targets.contains(app.dockerContainer)) continue;
            try { check(app); }
            catch (RuntimeException ex) { log.warn("Kubernetes signal check failed for application {}: {}", app.id, ex.getClass().getSimpleName()); }
        }
    }

    void check(ApplicationEntity app) {
        ContainerSnapshot snapshot = inspector.inspect(app.dockerContainer).orElse(null);
        if (snapshot == null) return;
        String trouble = trouble(snapshot);
        // A replacement pod is briefly unready, so one unready pod only counts once it has stayed that way for a whole poll.
        if (trouble == null && oneUnreadyPod(snapshot)) {
            if (unreadyLastPoll.add(app.id)) return;
            trouble = "PodNotReady";
        } else {
            unreadyLastPoll.remove(app.id);
        }
        String previous = firing.get(app.id);
        Instant now = Instant.now();
        if (trouble != null && !trouble.equals(previous)) {
            if (previous != null) signals.receive(app, signal(app, previous, "resolved", now));
            signals.receive(app, signal(app, trouble, "firing", now));
            firing.put(app.id, trouble);
        } else if (trouble == null && previous != null) {
            signals.receive(app, signal(app, previous, "resolved", now));
            firing.remove(app.id);
        }
    }

    /** The reason this workload is in trouble now, or null. Being mid-rollout is not trouble. */
    static String trouble(ContainerSnapshot snapshot) {
        if (!snapshot.exists()) return "DeploymentMissing";
        String reason = snapshot.composeLabels().get(KubernetesRuntimeInspector.WAITING_REASON);
        // Pods alternate between these two while retrying a pull; as two troubles, each change would look like a new alert.
        if ("ImagePullBackOff".equals(reason) || "ErrImagePull".equals(reason)) return "ImagePullFailure";
        if (reason != null) return reason;
        if (snapshot.oomKilled()) return "OOMKilled";
        return null;
    }

    /** Some pods are ready, one or more are not, and no rollout is replacing them. */
    static boolean oneUnreadyPod(ContainerSnapshot snapshot) {
        Map<String, String> labels = snapshot.composeLabels();
        if (!labels.containsKey(KubernetesRuntimeInspector.DESIRED)) return false;
        int desired = Integer.parseInt(labels.get(KubernetesRuntimeInspector.DESIRED));
        int ready = Integer.parseInt(labels.get(KubernetesRuntimeInspector.READY));
        int updated = Integer.parseInt(labels.get(KubernetesRuntimeInspector.UPDATED));
        return desired > 1 && ready > 0 && ready < desired && updated >= desired;
    }

    private static SignalService.Signal signal(ApplicationEntity app, String reason, String status, Instant at) {
        return new SignalService.Signal(SOURCE, app.dockerContainer + "/" + reason, status, reason + " on " + app.dockerContainer,
                "Observed by RELAY through the Kubernetes API.", "high", null, at);
    }
}

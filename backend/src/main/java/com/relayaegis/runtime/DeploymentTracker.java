package com.relayaegis.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.ai.EvidenceSanitizer;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.runtime.kubernetes.KubernetesTarget;
import com.relayaegis.service.DockerControlService;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Watches the image each monitored Docker container runs, after every health check:
 * <ul>
 *   <li>An image becomes the application's <b>last good image</b> once it has passed {@code goodAfterChecks}
 *       consecutive health checks. That is the image a bad deploy is rolled back to.</li>
 *   <li>Memory and CPU are sampled into a short in-memory window, so diagnosis can show a resource trend
 *       (memory climbing towards the limit) rather than one point in time. The window is lost on restart.</li>
 * </ul>
 * Read-only: only {@code docker inspect} and {@code docker stats} of allowlisted containers. Environment variables
 * and mounts are never read here.
 */
@Component
public class DeploymentTracker {
    private static final Logger log = LoggerFactory.getLogger(DeploymentTracker.class);
    static final Pattern IMAGE_ID = Pattern.compile("sha256:[0-9a-f]{64}");
    static final int MAX_SAMPLES = 120;

    /** The image a container runs now. {@code createdAt} is when the container itself was created (a deploy recreates it). */
    public record Deployment(String container, String imageId, String imageRef, Instant createdAt) {}

    /** One resource observation of a running container. */
    public record ResourceSample(Instant at, Double memoryPercent, Long memoryBytes, Double cpuPercent) {}

    private record Candidate(String imageId, int healthyChecks) {}

    private final DockerCommandRunner runner;
    private final DockerControlService docker;
    private final ApplicationRepository applications;
    private final ObjectMapper mapper;
    private final int goodAfterChecks;
    private final Map<String, Candidate> candidates = new ConcurrentHashMap<>();
    private com.relayaegis.recovery.DockerRollbackService rollback;
    private final java.util.Set<String> pinned = ConcurrentHashMap.newKeySet();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRollback(com.relayaegis.recovery.DockerRollbackService rollback) { this.rollback = rollback; }
    private final Map<String, Deque<ResourceSample>> samples = new ConcurrentHashMap<>();

    public DeploymentTracker(DockerCommandRunner runner, DockerControlService docker, ApplicationRepository applications, ObjectMapper mapper,
                             @Value("${relay.deploys.good-after-checks:3}") int goodAfterChecks) {
        this.runner = runner;
        this.docker = docker;
        this.applications = applications;
        this.mapper = mapper;
        this.goodAfterChecks = Math.max(1, Math.min(20, goodAfterChecks));
    }

    static boolean tracks(String container) { return container != null && !container.isBlank() && !KubernetesTarget.is(container); }

    /** Called after each recorded health check. Failures never fail the check. */
    public void observe(String applicationId, boolean healthy) {
        try {
            ApplicationEntity app = applications.findById(applicationId).orElse(null);
            if (app == null || !tracks(app.dockerContainer) || !docker.canInspect(app.dockerContainer)) return;
            Optional<JsonNode> inspected = inspectJson(app.dockerContainer);
            if (inspected.isEmpty()) return;
            Deployment current = parse(app.dockerContainer, inspected.get()).orElse(null);
            if (current == null) return;
            if (inspected.get().path("State").path("Running").asBoolean(false)) sample(app.dockerContainer);
            if (!healthy) {
                candidates.computeIfPresent(app.id, (id, c) -> new Candidate(c.imageId(), 0));
                return;
            }
            Candidate next = candidates.merge(app.id, new Candidate(current.imageId(), 1),
                    (old, fresh) -> old.imageId().equals(fresh.imageId()) ? new Candidate(old.imageId(), old.healthyChecks() + 1) : fresh);
            if (next.healthyChecks() >= goodAfterChecks && !current.imageId().equals(app.lastGoodImageId)) {
                app.lastGoodImageId = current.imageId();
                app.lastGoodImageRef = current.imageRef();
                app.lastGoodAt = Instant.now();
                applications.save(app);
                log.info("Last good image for {} is now {} ({})", app.id, current.imageRef(), current.imageId().substring(7, 19));
            }
            // Pin once per process the image that is (still) the last good one, so a restart of RELAY re-pins it too.
            if (current.imageId().equals(app.lastGoodImageId) && rollback != null && rollback.isEnabled()
                    && pinned.add(app.dockerContainer + "@" + current.imageId()) && !rollback.pin(app.dockerContainer, current.imageId())) {
                pinned.remove(app.dockerContainer + "@" + current.imageId());
                log.warn("The last good image of {} is not pinned; Docker may delete it after the next deploy", app.dockerContainer);
            }
        } catch (RuntimeException ex) {
            log.debug("Deployment tracking skipped for {}: {}", applicationId, ex.getClass().getSimpleName());
        }
    }

    /** A fresh read of what the container runs now; empty when it cannot be inspected. */
    public Optional<Deployment> current(String container) {
        if (!tracks(container) || !docker.canInspect(container)) return Optional.empty();
        return inspectJson(container).flatMap(item -> parse(container, item));
    }

    /** Oldest first. */
    public List<ResourceSample> samples(String container) {
        Deque<ResourceSample> window = samples.get(container);
        if (window == null) return List.of();
        synchronized (window) { return List.copyOf(window); }
    }

    void record(String container, ResourceSample sample) {
        Deque<ResourceSample> window = samples.computeIfAbsent(container, c -> new ArrayDeque<>());
        synchronized (window) {
            window.addLast(sample);
            while (window.size() > MAX_SAMPLES) window.removeFirst();
        }
    }

    private void sample(String container) {
        DockerCommandRunner.Result stats = runner.stats(container);
        if (!stats.ok()) return;
        ContainerSnapshot base = ContainerSnapshot.missing(container, Instant.now());
        ContainerSnapshot withStats = DockerRuntimeInspector.withStats(base, stats.output());
        if (withStats.memoryPercent() == null && withStats.cpuPercent() == null) return;
        record(container, new ResourceSample(Instant.now(), withStats.memoryPercent(), withStats.memoryUsageBytes(), withStats.cpuPercent()));
    }

    private Optional<JsonNode> inspectJson(String container) {
        DockerCommandRunner.Result result = runner.inspectContainer(container);
        if (!result.ok()) return Optional.empty();
        try {
            JsonNode root = mapper.readTree(result.output());
            JsonNode item = root.isArray() ? root.path(0) : root;
            return item.isMissingNode() || item.isNull() ? Optional.empty() : Optional.of(item);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    static Optional<Deployment> parse(String container, JsonNode item) {
        String imageId = item.path("Image").asText("");
        if (!IMAGE_ID.matcher(imageId).matches()) return Optional.empty();
        String ref = EvidenceSanitizer.clean(item.path("Config").path("Image").asText(""), 200);
        Instant created = null;
        try { created = Instant.parse(item.path("Created").asText("")); } catch (Exception ignored) { /* left null */ }
        return Optional.of(new Deployment(container, imageId, ref, created));
    }
}

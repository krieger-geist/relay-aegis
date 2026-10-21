package com.relayaegis.recovery;

import com.relayaegis.dto.ApiDtos.HealthCheckResult;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.runtime.ContainerSnapshot;
import com.relayaegis.runtime.DockerRuntimeInspector;
import com.relayaegis.service.MonitoringService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Post-action verification from real observations: the recovered container must be running (and not restarting or
 * unhealthy), and the application must pass {@code requiredHealthyChecks} consecutive live health checks before the
 * deadline. A container that exits again is a definitive failure; reaching the deadline is "timed_out" - never success.
 */
@Component
public class VerificationEngine {
    private final MonitoringService monitoring;
    private final DockerRuntimeInspector inspector;
    private final Duration timeout;
    private final int requiredHealthyChecks;
    private final Duration pollInterval;

    public VerificationEngine(MonitoringService monitoring, DockerRuntimeInspector inspector,
            @Value("${relay.recovery.verification.timeout-ms:30000}") long timeoutMs,
            @Value("${relay.recovery.verification.required-healthy-checks:2}") int requiredHealthyChecks,
            @Value("${relay.recovery.verification.poll-ms:1000}") long pollMs) {
        this.monitoring = monitoring;
        this.inspector = inspector;
        this.timeout = Duration.ofMillis(Math.max(1000, Math.min(300_000, timeoutMs)));
        this.requiredHealthyChecks = Math.max(1, Math.min(10, requiredHealthyChecks));
        this.pollInterval = Duration.ofMillis(Math.max(50, Math.min(10_000, pollMs)));
    }

    public record VerificationResult(String status, String detail, List<String> observations) {
        public boolean passed() { return "passed".equals(status); }
    }

    public VerificationResult verify(ApplicationEntity app, RecoveryDecision decision) {
        Instant deadline = Instant.now().plus(timeout);
        List<String> observations = new ArrayList<>();
        int consecutiveHealthy = 0;
        boolean targetObserved = decision.target() == null || !inspector.canInspect(decision.target());
        String lastTarget = targetObserved ? "target state not inspectable; verified through health checks only" : "not yet observed";
        String lastCheck = "no health check completed";
        while (Instant.now().isBefore(deadline)) {
            boolean targetOk = true;
            if (decision.target() != null && inspector.canInspect(decision.target())) {
                ContainerSnapshot snapshot = inspector.inspect(decision.target()).orElse(null);
                if (snapshot != null) {
                    lastTarget = snapshot.describe();
                    targetOk = snapshot.exists() && snapshot.running() && !snapshot.restarting() && !"unhealthy".equals(snapshot.healthStatus())
                            && !"starting".equals(snapshot.healthStatus());
                    if (snapshot.exists() && !snapshot.running() && !snapshot.restarting() && snapshot.finishedAt() != null
                            && snapshot.startedAt() != null && snapshot.finishedAt().isAfter(snapshot.startedAt())) {
                        observations.add("Target " + decision.target() + ": " + lastTarget);
                        return new VerificationResult("failed", "The container " + decision.target() + " stopped again after the recovery action ("
                                + lastTarget + ").", List.copyOf(observations));
                    }
                } else {
                    targetOk = false;
                }
            }
            try {
                HealthCheckResult check = monitoring.check(app.id);
                lastCheck = check.healthy() ? "healthy" : check.message();
                consecutiveHealthy = check.healthy() ? consecutiveHealthy + 1 : 0;
            } catch (RuntimeException ex) {
                consecutiveHealthy = 0;
                lastCheck = "health check could not run";
            }
            if (targetOk && consecutiveHealthy >= requiredHealthyChecks) {
                observations.add("Target: " + lastTarget);
                observations.add(requiredHealthyChecks + " consecutive healthy checks after the action");
                return new VerificationResult("passed", "Verified: " + (decision.target() == null ? "" : decision.target() + " is running and ")
                        + "the application passed " + requiredHealthyChecks + " consecutive health checks after the action.", List.copyOf(observations));
            }
            sleep(pollInterval);
        }
        observations.add("Target: " + lastTarget);
        observations.add("Last health check: " + lastCheck);
        return new VerificationResult("timed_out", "Recovery was not verified within " + timeout.toSeconds() + " s (target: " + lastTarget
                + "; last health check: " + lastCheck + ").", List.copyOf(observations));
    }

    private static void sleep(Duration duration) {
        try { Thread.sleep(duration.toMillis()); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}

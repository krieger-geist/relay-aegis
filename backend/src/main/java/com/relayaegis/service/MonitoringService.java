package com.relayaegis.service;

import com.relayaegis.dto.ApiDtos.EvidenceItemDto;
import com.relayaegis.dto.ApiDtos.HealthCheckResult;
import com.relayaegis.dto.ApiDtos.HealthSampleDto;
import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.HealthCheckEntity;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.event.IncidentDetectedEvent;
import com.relayaegis.observability.RelayMetrics;
import com.relayaegis.repository.HealthCheckRepository;
import com.relayaegis.util.TargetUrlPolicy;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records real health observations and drives the incident lifecycle from them:
 * an incident opens only after {@code failureThreshold} consecutive failed checks, is deduplicated
 * per application component while open, and closes only after {@code recoveryThreshold}
 * consecutive healthy checks observed after detection (unless a recovery action owns the outcome).
 */
@Service
public class MonitoringService {
    private final ApplicationService applications;
    private final IncidentService incidentService;
    private final HealthCheckRepository healthChecks;
    private final TargetUrlPolicy targetUrlPolicy;
    private final HttpClient httpClient;
    private final int timeoutMs;
    private final int failureThreshold;
    private final int recoveryThreshold;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final RelayMetrics metrics;
    /** Serializes checks per application so concurrent manual and scheduled checks cannot open duplicate incidents. */
    private final Map<String, ReentrantLock> applicationLocks = new ConcurrentHashMap<>();

    public MonitoringService(ApplicationService applications, IncidentService incidentService,
            HealthCheckRepository healthChecks, TargetUrlPolicy targetUrlPolicy, ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager, RelayMetrics metrics,
            @Value("${relay.monitoring.timeout-ms:2500}") int timeoutMs,
            @Value("${relay.monitoring.failure-threshold:2}") int failureThreshold,
            @Value("${relay.monitoring.recovery-threshold:2}") int recoveryThreshold) {
        this.applications = applications;
        this.incidentService = incidentService;
        this.healthChecks = healthChecks;
        this.targetUrlPolicy = targetUrlPolicy;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
        this.timeoutMs = Math.max(250, timeoutMs);
        this.failureThreshold = MonitoringRules.clampThreshold(failureThreshold);
        this.recoveryThreshold = MonitoringRules.clampThreshold(recoveryThreshold);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(this.timeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Transactional(readOnly = true)
    public List<HealthSampleDto> history(String applicationId) {
        applications.findEntity(applicationId);
        return healthChecks.findTop100ByApplicationIdOrderByCheckedAtDesc(applicationId).stream()
                .map(sample -> new HealthSampleDto(sample.id, sample.applicationId, sample.checkedAt, sample.healthy,
                        sample.responseMs, sample.errorMessage, sample.provenance, outcomeOf(sample), sample.statusCode))
                .toList();
    }

    /**
     * Performs one bounded health check. A URL rejected by the allowlist is a configuration error,
     * not an observation of the target, so it is thrown to the caller and nothing is recorded.
     */
    public HealthCheckResult check(String applicationId) {
        ReentrantLock lock = applicationLocks.computeIfAbsent(applicationId, id -> new ReentrantLock());
        lock.lock();
        try {
            ApplicationEntity app = applications.findEntity(applicationId);
            URI uri = targetUrlPolicy.validate(app.url);
            // The network call happens outside any transaction so a slow target cannot hold a DB connection.
            Probe probe = probe(uri);
            HealthCheckResult result = transactions.execute(status -> record(applicationId, probe));
            metrics.healthCheck(applicationId, probe.outcome(), probe.elapsedMs());
            return result;
        } finally {
            lock.unlock();
        }
    }

    record Probe(Instant finishedAt, long elapsedMs, String outcome, Integer statusCode, String message) {}

    Probe probe(URI uri) {
        long start = System.nanoTime();
        String outcome;
        Integer statusCode = null;
        String message;
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", "RELAY-AEGIS-HealthMonitor/0.2").GET().build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            statusCode = response.statusCode();
            outcome = MonitoringRules.classifyStatus(statusCode);
            message = MonitoringRules.HEALTHY.equals(outcome) ? "Health endpoint responded with HTTP " + statusCode + "."
                    : statusCode >= 300 && statusCode < 400
                    ? "Health endpoint returned HTTP " + statusCode + "; redirects are not followed."
                    : "Health endpoint returned HTTP " + statusCode + ".";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            outcome = MonitoringRules.UNREACHABLE;
            message = "Health check was interrupted before a response arrived.";
        } catch (Exception ex) {
            outcome = MonitoringRules.UNREACHABLE;
            message = classifyFailure(ex, timeoutMs, MonitoringService::hostResolves, uri.getHost());
        }
        long elapsedMs = Math.max(1, Duration.ofNanos(System.nanoTime() - start).toMillis());
        return new Probe(Instant.now(), elapsedMs, outcome, statusCode, message);
    }

    private HealthCheckResult record(String applicationId, Probe probe) {
        ApplicationEntity app = applications.findEntity(applicationId);
        boolean healthy = MonitoringRules.HEALTHY.equals(probe.outcome());
        ApplicationEntity updated = applications.saveHealthResult(app, probe.outcome(), probe.elapsedMs(), "live");

        HealthCheckEntity check = new HealthCheckEntity();
        check.id = "CHK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        check.applicationId = app.id;
        check.checkedAt = probe.finishedAt();
        check.healthy = healthy;
        check.responseMs = probe.elapsedMs();
        check.errorMessage = healthy ? null : probe.message();
        check.outcome = probe.outcome();
        check.statusCode = probe.statusCode();
        check.provenance = "live";
        healthChecks.save(check);

        Instant errorWindowStart = Instant.now().minus(Duration.ofHours(24));
        long total = healthChecks.countByApplicationIdAndCheckedAtAfter(app.id, errorWindowStart);
        if (total > 0) {
            long failed = healthChecks.countByApplicationIdAndCheckedAtAfterAndHealthyFalse(app.id, errorWindowStart);
            updated = applications.updateRollingErrorRate(updated, Math.round(1000.0 * failed / total) / 10.0);
        }

        List<HealthCheckEntity> recent = new ArrayList<>(healthChecks.findTop10ByApplicationIdOrderByCheckedAtDesc(app.id));
        if (recent.isEmpty() || !check.id.equals(recent.get(0).id)) {
            recent.removeIf(item -> check.id.equals(item.id));
            recent.add(0, check);
        }
        String note = healthy ? handleHealthy(app, recent, check) : handleFailure(updated, recent, probe);
        String message = note == null ? probe.message() : probe.message() + " " + note;
        return new HealthCheckResult(applications.toDto(updated), healthy, probe.elapsedMs(), message, "live",
                probe.outcome(), probe.statusCode());
    }

    private String handleFailure(ApplicationEntity app, List<HealthCheckEntity> recent, Probe probe) {
        String component = componentFor(app);
        var open = incidentService.findOpenIncident(app.id, component);
        if (open.isPresent()) return "Open incident " + open.get().id + " already tracks this failure.";
        if (!MonitoringRules.hasFailureStreak(recent, failureThreshold)) {
            return "Failed check " + MonitoringRules.leadingFailures(recent) + " of " + failureThreshold
                    + " required before an incident is opened.";
        }
        List<HealthCheckEntity> streak = recent.subList(0, failureThreshold);
        HealthCheckEntity firstFailure = streak.get(streak.size() - 1);
        // The title states what was observed (the application's health check); the cause — container, dependency or
        // application — is established by diagnosis, so a linked container is never presumed to be the failure.
        String title = MonitoringRules.UNREACHABLE.equals(probe.outcome()) ? "Application unreachable"
                : "Health endpoint returned HTTP " + probe.statusCode();
        String summary = probe.message() + " Observed in " + failureThreshold + " consecutive check(s).";
        var incident = incidentService.create(app, title, "Sev-2", component, summary, "live");
        long detectionMs = Math.max(probe.elapsedMs(), Duration.between(firstFailure.checkedAt, Instant.now()).toMillis());
        incidentService.recordDetectionTiming(incident.id(), firstFailure.checkedAt, detectionMs);
        metrics.incidentOpened(app.id, detectionMs);
        IncidentEntity entity = incidentService.findEntity(incident.id());
        for (int i = streak.size() - 1; i >= 0; i--) {
            HealthCheckEntity failed = streak.get(i);
            incidentService.appendEvidence(entity, new EvidenceItemDto("ev-" + UUID.randomUUID().toString().substring(0, 8),
                    "health_check", "Failed check " + (streak.size() - i) + " of " + streak.size() + " (" + outcomeOf(failed) + ")",
                    (failed.errorMessage == null ? "Check failed." : failed.errorMessage) + " Response time " + failed.responseMs + " ms. Check " + failed.id + ".",
                    "URL monitor", failed.checkedAt, true));
        }
        incidentService.save(entity);
        events.publishEvent(new IncidentDetectedEvent(incident.id(), app.id));
        return "Incident " + incident.id() + " opened after " + failureThreshold + " consecutive failed check(s).";
    }

    private String handleHealthy(ApplicationEntity app, List<HealthCheckEntity> recent, HealthCheckEntity check) {
        var open = incidentService.findOpenIncident(app.id, componentFor(app));
        if (open.isEmpty()) return null;
        IncidentEntity incident = open.get();
        if ("action_taken".equals(incident.state) || "verifying".equals(incident.state)) {
            return "A recovery action is running for " + incident.id + "; its post-action verification decides the outcome.";
        }
        if (!MonitoringRules.hasRecoveryStreak(recent, recoveryThreshold, incident.detectedAt)) {
            return "Healthy check " + Math.min(MonitoringRules.leadingHealthy(recent), recoveryThreshold) + " of " + recoveryThreshold
                    + " required before " + incident.id + " is resolved.";
        }
        String detail = "The application was observed healthy in " + recoveryThreshold + " consecutive check(s); latest "
                + (check.statusCode == null ? "check" : "HTTP " + check.statusCode) + " in " + check.responseMs + " ms (" + check.id + ").";
        incidentService.resolveAfterObservedRecovery(incident, check.checkedAt, detail);
        return "Incident " + incident.id + " resolved after observed recovery.";
    }

    private static String componentFor(ApplicationEntity app) {
        return app.dockerContainer != null && !app.dockerContainer.isBlank() ? "docker:" + app.dockerContainer : "url-health-check";
    }

    /**
     * Read-only view of the auto-resolution rule used by handleHealthy(): consecutive healthy checks recorded after
     * the incident was detected, against the configured recovery threshold.
     */
    @Transactional(readOnly = true)
    public com.relayaegis.dto.ApiDtos.ResolutionProgressDto resolutionProgress(IncidentEntity incident) {
        List<HealthCheckEntity> recent = healthChecks.findTop10ByApplicationIdOrderByCheckedAtDesc(incident.applicationId);
        int healthy = 0;
        for (HealthCheckEntity check : recent) {
            if (!check.healthy || check.checkedAt == null || (incident.detectedAt != null && !check.checkedAt.isAfter(incident.detectedAt))) break;
            healthy++;
        }
        HealthCheckEntity latest = recent.isEmpty() ? null : recent.get(0);
        return new com.relayaegis.dto.ApiDtos.ResolutionProgressDto(incident.id, !"recovered".equals(incident.state),
                Math.min(healthy, recoveryThreshold), recoveryThreshold,
                latest == null ? null : outcomeOf(latest), latest == null ? null : latest.checkedAt);
    }

    private static String outcomeOf(HealthCheckEntity check) {
        if (check.outcome != null) return check.outcome;
        return check.healthy ? MonitoringRules.HEALTHY : "failed";
    }

    /**
     * Re-resolves the host after a connect timeout. The JDK client resolves names inside its connect timer, so a slow
     * failing DNS lookup surfaces as a connect timeout; the JVM has just cached the lookup result, so this is
     * normally instant. Bounded to one second; an undetermined result keeps the original classification.
     */
    static java.util.Optional<Boolean> hostResolves(String host) {
        try {
            return java.util.Optional.of(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { java.net.InetAddress.getAllByName(host); return true; }
                catch (java.net.UnknownHostException ex) { return false; }
            }).get(1, java.util.concurrent.TimeUnit.SECONDS));
        } catch (Exception ex) {
            return java.util.Optional.empty();
        }
    }

    static String classifyFailure(Throwable ex, long timeoutMs, java.util.function.Function<String, java.util.Optional<Boolean>> resolver, String host) {
        if (ex instanceof java.net.http.HttpConnectTimeoutException && resolver != null && host != null
                && Boolean.FALSE.equals(resolver.apply(host).orElse(null))) {
            return "Host could not be resolved (DNS lookup failed; it outlasted the " + timeoutMs + " ms connect limit).";
        }
        return classifyFailure(ex, timeoutMs);
    }

    /**
     * Separates the network layer that failed. The JDK HttpClient wraps DNS failures (UnresolvedAddressException)
     * and connect timeouts in ConnectException/HttpConnectTimeoutException, so the cause chain is inspected:
     * DNS failure, TCP refused, TCP connect timeout, and HTTP response timeout/failure each get their own wording.
     * The wording is matched by DiagnosticContext's classifiers; keep the two in sync.
     */
    static String classifyFailure(Throwable ex, long timeoutMs) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.nio.channels.UnresolvedAddressException || cause instanceof java.net.UnknownHostException) {
                return "Host could not be resolved (DNS lookup failed).";
            }
        }
        if (ex instanceof java.net.http.HttpConnectTimeoutException) {
            return "TCP connect timed out after " + timeoutMs + " ms; no connection was established.";
        }
        if (ex instanceof java.net.http.HttpTimeoutException) return "No response within the " + timeoutMs + " ms deadline.";
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (message.contains("connection refused")) return "Connection refused: nothing accepted the TCP connection.";
            if (cause instanceof java.net.SocketTimeoutException && message.contains("connect")) {
                return "TCP connect timed out after " + timeoutMs + " ms; no connection was established.";
            }
        }
        if (ex instanceof ConnectException) return "Connection could not be established (" + ex.getClass().getSimpleName() + ").";
        if (ex instanceof java.io.IOException) return "HTTP exchange failed after connecting (" + ex.getClass().getSimpleName() + ").";
        String message = ex.getMessage();
        if (message == null || message.isBlank()) return "Health check failed before a response arrived (" + ex.getClass().getSimpleName() + ").";
        return message.length() > 400 ? message.substring(0, 400) : message;
    }
}

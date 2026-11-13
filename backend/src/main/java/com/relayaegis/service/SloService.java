package com.relayaegis.service;

import com.relayaegis.entity.ApplicationEntity;
import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.repository.HealthCheckRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Availability SLO and error-budget burn rate per application, from its own health checks.
 * <p>
 * The burn rate is the error rate divided by the error budget (1 - target): at a burn rate of 1 the budget lasts exactly
 * the SLO period; at 14.4 a 30-day budget is gone in about two days. Alerts follow the multi-window rule from the
 * Google SRE workbook, so they fire on real burns and stop soon after the burn stops:
 * <ul>
 *   <li><b>fast burn</b>: 1 h burn rate of 14.4 or more, and the last 5 min still burning at that rate;</li>
 *   <li><b>slow burn</b>: 6 h burn rate of 6 or more, and the last 30 min still burning at that rate.</li>
 * </ul>
 * This catches what the consecutive-failure rule cannot: an application that fails every few checks but never twice in
 * a row. An alert opens its own incident (component {@value #COMPONENT}) only while no health incident is open for the
 * application, and closes once the 1 h burn rate falls below 1. It starts no diagnosis or recovery by itself.
 */
@Service
public class SloService {
    private static final Logger log = LoggerFactory.getLogger(SloService.class);
    public static final String COMPONENT = "slo-burn";
    static final double FAST_BURN = 14.4;
    static final double SLOW_BURN = 6.0;

    public record Window(String name, long checks, long failures, Double errorRate, Double burnRate) {}

    /** {@code alert} is "fast_burn", "slow_burn" or null; {@code budgetRemaining} is the share of the 30-day budget left (may be negative). */
    public record Status(String applicationId, double target, List<Window> windows, Double budgetRemaining, String alert) {}

    private final HealthCheckRepository checks;
    private final IncidentService incidents;
    private final double target;
    private final boolean alertsEnabled;
    private final int minChecks;

    public SloService(HealthCheckRepository checks, IncidentService incidents,
                      @Value("${relay.slo.availability-target:0.995}") double target,
                      @Value("${relay.slo.alerts-enabled:true}") boolean alertsEnabled,
                      @Value("${relay.slo.min-checks:10}") int minChecks) {
        this.checks = checks;
        this.incidents = incidents;
        this.target = target > 0.5 && target < 1 ? target : 0.995;
        this.alertsEnabled = alertsEnabled;
        this.minChecks = Math.max(3, minChecks);
    }

    public Status status(String applicationId) { return status(applicationId, Instant.now()); }

    Status status(String applicationId, Instant now) {
        Window h1 = window(applicationId, "1h", Duration.ofHours(1), now);
        Window m5 = window(applicationId, "5m", Duration.ofMinutes(5), now);
        Window h6 = window(applicationId, "6h", Duration.ofHours(6), now);
        Window m30 = window(applicationId, "30m", Duration.ofMinutes(30), now);
        Window d1 = window(applicationId, "24h", Duration.ofHours(24), now);
        Window d30 = window(applicationId, "30d", Duration.ofDays(30), now);
        String alert = null;
        if (burning(h1, FAST_BURN, minChecks) && burning(m5, FAST_BURN, 3)) alert = "fast_burn";
        else if (burning(h6, SLOW_BURN, minChecks * 3) && burning(m30, SLOW_BURN, Math.max(3, minChecks / 2))) alert = "slow_burn";
        Double remaining = d30.errorRate() == null ? null : 1 - d30.errorRate() / (1 - target);
        return new Status(applicationId, target, List.of(m5, h1, h6, d1, d30), remaining, alert);
    }

    private static boolean burning(Window w, double threshold, int min) {
        return w.checks() >= min && w.burnRate() != null && w.burnRate() >= threshold;
    }

    private Window window(String applicationId, String name, Duration length, Instant now) {
        Instant since = now.minus(length);
        long total = checks.countByApplicationIdAndCheckedAtAfter(applicationId, since);
        if (total == 0) return new Window(name, 0, 0, null, null);
        long failed = checks.countByApplicationIdAndCheckedAtAfterAndHealthyFalse(applicationId, since);
        double rate = (double) failed / total;
        return new Window(name, total, failed, rate, rate / (1 - target));
    }

    /** Called after each recorded check. Opens or closes the burn incident; never fails the check. */
    public void evaluate(ApplicationEntity app) {
        if (!alertsEnabled || app == null) return;
        try {
            Status status = status(app.id);
            Optional<IncidentEntity> open = incidents.findOpenIncident(app.id, COMPONENT);
            Window h1 = status.windows().get(1);
            if (open.isPresent()) {
                if (h1.burnRate() != null && h1.burnRate() < 1) {
                    incidents.resolveAfterObservedRecovery(open.get(), Instant.now(), String.format(Locale.ROOT,
                            "The 1 h error-budget burn rate fell to %.1f (below 1), so the budget is no longer being spent faster than planned.", h1.burnRate()));
                }
                return;
            }
            if (status.alert() == null) return;
            if (incidents.findOpenIncident(app.id, MonitoringService.componentFor(app)).isPresent()) return;
            boolean fast = "fast_burn".equals(status.alert());
            Window w = fast ? h1 : status.windows().get(2);
            String title = fast ? "Error budget burning fast" : "Error budget burning steadily";
            String summary = String.format(Locale.ROOT,
                    "%d of %d checks failed in the last %s (%.1f%% errors), a burn rate of %.1f against the %.2f%% availability SLO. "
                            + "At this rate the 30-day error budget lasts about %s. The failures are intermittent: no two in a row were needed.",
                    w.failures(), w.checks(), w.name(), 100 * w.errorRate(), w.burnRate(), 100 * target, lasts(w.burnRate()));
            var incident = incidents.create(app, title + " (" + String.format(Locale.ROOT, "%.0fx", w.burnRate()) + ")", fast ? "Sev-2" : "Sev-3",
                    COMPONENT, summary, "live", "slo", "RELAY SLO");
            log.info("Opened {} for {}: {}", incident.id(), app.id, status.alert());
        } catch (RuntimeException ex) {
            log.debug("SLO evaluation skipped for {}: {}", app.id, ex.getClass().getSimpleName());
        }
    }

    static String lasts(double burnRate) {
        if (burnRate <= 0) return "the whole period";
        double hours = 30 * 24 / burnRate;
        return hours >= 48 ? String.format(Locale.ROOT, "%.0f days", hours / 24) : String.format(Locale.ROOT, "%.0f hours", hours);
    }
}

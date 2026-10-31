package com.relayaegis.cluster;

import com.relayaegis.entity.LeaderLeaseEntity;
import com.relayaegis.repository.LeaderLeaseRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Lease-based leader election over the shared database, so that with several backend instances exactly one monitors
 * applications and runs recovery. The leader renews a single {@code leader_leases} row every renew interval; another
 * instance takes the row over only once it has expired. The leader treats itself as leader only until
 * {@code ttl - ttl/3} after its last successful renewal, so it stops acting before any other instance can take over
 * (instance clocks must agree to within that margin, e.g. through NTP). A graceful shutdown releases the lease at once.
 * Renewal runs on its own thread, so a slow monitoring round or AI call on the shared scheduler can never delay it.
 *
 * <p>Disabled ({@code relay.leader.enabled=false}, the default outside the {@code postgres} profile): this instance is
 * always the leader, which is the single-instance behaviour.
 */
@Component
public class LeaderElection {
    static final String LEASE = "relay-runtime";
    private static final Logger log = LoggerFactory.getLogger(LeaderElection.class);

    private final LeaderLeaseRepository leases;
    private final ApplicationEventPublisher events;
    private final boolean enabled;
    private final Duration ttl;
    private final Duration renewInterval;
    private ScheduledExecutorService renewer;
    private final String instanceId;
    private final String advertiseUrl;
    private final Clock clock;
    private volatile boolean held;
    private volatile Instant validUntil = Instant.EPOCH;

    @Autowired
    public LeaderElection(LeaderLeaseRepository leases, ApplicationEventPublisher events, MeterRegistry meters,
            @Value("${relay.leader.enabled:false}") boolean enabled,
            @Value("${relay.leader.lease-ttl-ms:15000}") long ttlMs,
            @Value("${relay.leader.renew-interval-ms:5000}") long renewMs,
            @Value("${relay.leader.advertise-url:}") String advertiseUrl,
            @Value("${server.port:8080}") int port) {
        this(leases, events, enabled, Duration.ofMillis(Math.max(3_000, ttlMs)), Duration.ofMillis(Math.max(500, renewMs)), defaultInstanceId(),
                advertiseUrl == null || advertiseUrl.isBlank() ? "http://" + localAddress() + ":" + port : advertiseUrl.trim(), Clock.systemUTC());
        Gauge.builder("relay.leader", this, election -> election.isLeader() ? 1 : 0)
                .description("1 while this instance is the RELAY leader (monitors and runs recovery)").register(meters);
    }

    LeaderElection(LeaderLeaseRepository leases, ApplicationEventPublisher events, boolean enabled, Duration ttl, Duration renewInterval,
            String instanceId, String advertiseUrl, Clock clock) {
        if (enabled && renewInterval.compareTo(ttl.dividedBy(3)) > 0) {
            throw new IllegalArgumentException("relay.leader.renew-interval-ms must be at most a third of relay.leader.lease-ttl-ms.");
        }
        this.leases = leases;
        this.events = events;
        this.enabled = enabled;
        this.ttl = ttl;
        this.renewInterval = renewInterval;
        this.instanceId = instanceId;
        this.advertiseUrl = advertiseUrl;
        this.clock = clock;
    }

    /** A single-instance election that always leads; for tests and tools that construct services directly. */
    public static LeaderElection standalone() {
        return new LeaderElection(null, event -> {}, false, Duration.ofSeconds(15), Duration.ofSeconds(5), "standalone", "", Clock.systemUTC());
    }

    public boolean enabled() { return enabled; }

    public String instanceId() { return instanceId; }

    /** True while this instance may monitor and act. Checked again right before every recovery action. */
    public boolean isLeader() { return !enabled || (held && clock.instant().isBefore(validUntil)); }

    /** The current leader's advertised URL, when another instance holds an unexpired lease. */
    public Optional<String> leaderUrl() {
        if (!enabled) return Optional.empty();
        Instant now = clock.instant();
        return leases.findById(LEASE).filter(l -> l.expiresAt.isAfter(now) && !instanceId.equals(l.holder)).map(l -> l.holderUrl);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            events.publishEvent(new LeadershipGrantedEvent(instanceId));
            return;
        }
        log.info("Leader election enabled: instance {} advertises {}", instanceId, advertiseUrl);
        heartbeat();
        renewer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "relay-leader-lease");
            thread.setDaemon(true);
            return thread;
        });
        renewer.scheduleWithFixedDelay(this::heartbeat, renewInterval.toMillis(), renewInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Renews the lease when held, otherwise tries to take it; publishes {@link LeadershipGrantedEvent} on gaining it. */
    public synchronized void heartbeat() {
        if (!enabled) return;
        boolean wasLeader = isLeader();
        Instant now = clock.instant();
        Instant expires = now.plus(ttl);
        boolean acquired;
        try {
            acquired = leases.renew(LEASE, instanceId, now, expires) == 1
                    || leases.takeOver(LEASE, instanceId, advertiseUrl, now, expires) == 1
                    || create(now, expires);
        } catch (RuntimeException ex) {
            log.warn("Leader lease could not be renewed: {}", ex.getClass().getSimpleName());
            acquired = false;
        }
        held = acquired;
        if (acquired) validUntil = now.plus(ttl).minus(ttl.dividedBy(3));
        if (acquired && !wasLeader) {
            log.info("Instance {} is now the RELAY leader", instanceId);
            events.publishEvent(new LeadershipGrantedEvent(instanceId));
        } else if (!acquired && wasLeader) {
            log.warn("Instance {} is no longer the RELAY leader", instanceId);
        }
    }

    private boolean create(Instant now, Instant expires) {
        if (leases.existsById(LEASE)) return false;
        try { return leases.create(LEASE, instanceId, advertiseUrl, now, expires) == 1; }
        catch (DataIntegrityViolationException ex) { return false; } // another instance created it first
    }

    @PreDestroy
    public synchronized void release() {
        if (renewer != null) renewer.shutdownNow();
        if (!enabled || !held) return;
        held = false;
        try { leases.release(LEASE, instanceId, clock.instant()); }
        catch (RuntimeException ex) { log.warn("Leader lease could not be released: {}", ex.getClass().getSimpleName()); }
    }

    /** The current lease row, for diagnostics. */
    public Optional<LeaderLeaseEntity> lease() { return enabled ? leases.findById(LEASE) : Optional.empty(); }

    private static String defaultInstanceId() {
        String host = System.getenv().getOrDefault("HOSTNAME", "relay");
        return (host.length() > 80 ? host.substring(0, 80) : host) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String localAddress() {
        try { return InetAddress.getLocalHost().getHostAddress(); }
        catch (Exception ex) { return "127.0.0.1"; }
    }
}

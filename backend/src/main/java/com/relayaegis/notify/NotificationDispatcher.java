package com.relayaegis.notify;

import com.relayaegis.entity.IncidentEntity;
import com.relayaegis.event.IncidentDetectedEvent;
import com.relayaegis.service.ApplicationService;
import com.relayaegis.service.IncidentService;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Delivers {@link RelayNotification}s to every enabled {@link Notifier} on one background thread with a bounded queue,
 * so a slow or unreachable Slack never delays detection or recovery. When the queue is full a notification is dropped
 * and logged; the incident itself is unaffected.
 */
@Component
public class NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private final List<Notifier> notifiers;
    private final IncidentService incidents;
    private final ApplicationService applications;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(200),
            r -> { Thread t = new Thread(r, "relay-notify"); t.setDaemon(true); return t; });

    public NotificationDispatcher(List<Notifier> notifiers, IncidentService incidents, ApplicationService applications) {
        this.notifiers = notifiers;
        this.incidents = incidents;
        this.applications = applications;
    }

    @EventListener
    public void on(RelayNotification notification) {
        if (notifiers.stream().noneMatch(Notifier::enabled)) return;
        submit(() -> notifiers.stream().filter(Notifier::enabled).forEach(n -> {
            try { n.send(notification); }
            catch (RuntimeException ex) { log.warn("{} notification for {} failed: {}", n.name(), notification.incidentId(), ex.getClass().getSimpleName()); }
        }));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onIncidentDetected(IncidentDetectedEvent event) {
        if (notifiers.stream().noneMatch(Notifier::enabled)) return;
        try {
            IncidentEntity incident = incidents.findEntity(event.incidentId());
            String environment = applications.findEntity(incident.applicationId).environment;
            on(new RelayNotification(RelayNotification.Kind.INCIDENT_OPENED, incident.id, incident.applicationName, environment,
                    incident.severity, incident.title, incident.summary, null, null, null, Instant.now()));
        } catch (RuntimeException ex) {
            log.warn("Incident {} could not be notified: {}", event.incidentId(), ex.getClass().getSimpleName());
        }
    }

    /** Runs a short notification task (such as answering a Slack button) on the notification thread. */
    public void submit(Runnable task) {
        try { worker.execute(task); }
        catch (RejectedExecutionException ex) { log.warn("Notification queue full; a notification was dropped"); }
    }

    @PreDestroy
    void stop() { worker.shutdown(); }
}

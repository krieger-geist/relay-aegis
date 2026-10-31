package com.relayaegis.service;

import com.relayaegis.cluster.LeaderElection;
import com.relayaegis.repository.ApplicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MonitoringScheduler {
    private static final Logger log=LoggerFactory.getLogger(MonitoringScheduler.class);
    private final ApplicationRepository applications;
    private final MonitoringService monitoring;
    private final LeaderElection leadership;
    public MonitoringScheduler(ApplicationRepository applications, MonitoringService monitoring, LeaderElection leadership) {
        this.applications=applications; this.monitoring=monitoring; this.leadership=leadership;
    }

    /** Only the leader monitors, so each application is checked once per interval however many instances run. */

    @Scheduled(fixedDelayString="${relay.monitoring.interval-ms:60000}", initialDelayString="${relay.monitoring.initial-delay-ms:15000}")
    public void checkEnabledApplications() {
        if (!leadership.isLeader()) return;
        applications.findAll().stream().filter(app -> app.monitoringEnabled).forEach(app -> {
            try { monitoring.check(app.id); }
            catch (Exception ex) { log.warn("Scheduled check failed for application {}: {}", app.id, ex.getMessage()); }
        });
    }
}

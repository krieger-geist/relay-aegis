package com.relayaegis.service;

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
    public MonitoringScheduler(ApplicationRepository applications, MonitoringService monitoring) { this.applications=applications; this.monitoring=monitoring; }

    @Scheduled(fixedDelayString="${relay.monitoring.interval-ms:60000}", initialDelayString="${relay.monitoring.initial-delay-ms:15000}")
    public void checkEnabledApplications() {
        applications.findAll().stream().filter(app -> app.monitoringEnabled).forEach(app -> {
            try { monitoring.check(app.id); }
            catch (Exception ex) { log.warn("Scheduled check failed for application {}: {}", app.id, ex.getMessage()); }
        });
    }
}

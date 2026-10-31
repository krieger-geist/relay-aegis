package com.relayaegis.service;

import com.relayaegis.cluster.LeaderElection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Starts recovery automatically only when the deterministic diagnosis recommends a mutating action and the recovery
 * policy engine authorizes it under "allow_automatically" (plus allowlist, cooldown and circuit breaker). AI output
 * plays no part in this decision.
 */
@Component
public class AutoRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(AutoRecoveryService.class);
    private final RecoveryOrchestrationService recovery;
    private final LeaderElection leadership;

    public AutoRecoveryService(RecoveryOrchestrationService recovery, LeaderElection leadership) {
        this.recovery = recovery;
        this.leadership = leadership;
    }

    /** Used after monitored incident evidence has been persisted. Never throws. */
    public boolean startIfAllowed(String incidentId) {
        if (!leadership.isLeader()) {
            log.info("Automatic recovery for incident {} left to the leader; this instance is not the leader", incidentId);
            return false;
        }
        try {
            boolean started = recovery.beginAutomaticRecovery(incidentId);
            if (started) log.info("Automatic recovery started for incident {}", incidentId);
            return started;
        } catch (RuntimeException ex) {
            log.error("Automatic recovery could not start for incident {}", incidentId, ex);
            return false;
        }
    }
}

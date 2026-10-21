package com.relayaegis.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

/** Initializes operational policy defaults without inserting synthetic applications, incidents, or history. */
@Service
public class OperationalBootstrapService implements ApplicationRunner {
    private final RecoveryPolicyService policies;
    private final RepairJobService repairs;

    public OperationalBootstrapService(RecoveryPolicyService policies, RepairJobService repairs) { this.policies = policies; this.repairs = repairs; }

    @Override
    public void run(ApplicationArguments args) {
        policies.seedDefaultsIfEmpty();
        repairs.failInterruptedProposals();
    }
}

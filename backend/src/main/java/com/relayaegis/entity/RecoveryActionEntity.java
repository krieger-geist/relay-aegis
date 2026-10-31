package com.relayaegis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One recovery attempt in the durable action journal: intent, execution and verification of a single Docker action. */
@Entity
@Table(name = "recovery_actions")
public class RecoveryActionEntity {
    @Id @Column(length = 40) public String id;
    /** Shared by the attempts of one recovery run (one "Run recovery" or automatic start). */
    @Column(nullable = false, length = 40) public String runId;
    @Column(nullable = false, length = 60) public String incidentId;
    @Column(nullable = false) public int attempt;
    @Column(nullable = false, length = 32) public String action;
    @Column(length = 200) public String target;
    @Column(length = 200) public String appContainer;
    @Column(length = 60) public String policyId;
    @Column(length = 32) public String policyMode;
    @Column(nullable = false) public boolean automatic;
    /** intended, executed, execution_failed, verified, not_verified or interrupted. */
    @Column(nullable = false, length = 24) public String phase;
    @Column(length = 2000) public String executionDetail;
    @Column(length = 24) public String verificationStatus;
    @Column(length = 2000) public String verificationDetail;
    @Column(nullable = false) public Instant intendedAt;
    public Instant executedAt;
    public Instant finishedAt;
    public RecoveryActionEntity() {}
}

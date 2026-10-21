package com.relayaegis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "recovery_policies")
public class RecoveryPolicyEntity {
    @Id public String id;
    @Column(nullable = false, length = 200) public String action;
    @Column(length = 1000) public String description;
    @Column(nullable = false, length = 32) public String mode;
    @Column(nullable = false, length = 16) public String risk;
    @Column(nullable = false, length = 160) public String scope;
    @Column(nullable = false, length = 24) public String provenance;
    public RecoveryPolicyEntity() {}
}

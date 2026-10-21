package com.relayaegis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "monitored_applications")
public class ApplicationEntity {
    @Id public String id;
    @Column(nullable = false, length = 160) public String name;
    @Column(nullable = false, length = 1000) public String url;
    @Column(nullable = false, length = 160) public String framework;
    @Column(nullable = false, length = 32) public String environment;
    @Column(nullable = false, length = 48) public String status;
    public Long responseMs;
    public Double errorRate;
    public Instant lastChecked;
    @Column(nullable = false, length = 100) public String deploymentSha;
    @Column(nullable = false) public boolean monitoringEnabled;
    @Column(length = 1000) public String description;
    @Column(length = 200) public String dockerContainer;
    @Column(length = 300) public String githubRepository;
    @Column(nullable = false, length = 24) public String provenance;
    @Column(nullable = false) public Instant createdAt;

    public ApplicationEntity() {}
}

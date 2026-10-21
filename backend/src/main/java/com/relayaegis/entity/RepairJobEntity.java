package com.relayaegis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "repair_jobs")
public class RepairJobEntity {
    @Id public String id;
    /** Set for incident-driven repairs; null for patch proposals that originate from an assessment finding. */
    @Column(length = 60) public String incidentId;
    @Column(nullable = false, length = 160) public String applicationName;
    @Column(nullable = false, length = 300) public String repository;
    @Column(nullable = false, length = 120) public String commitSha;
    @Column(nullable = false, length = 40) public String state;
    @Column(length = 2000) public String explanation;
    public int filesChanged;
    @Column(columnDefinition = "TEXT") public String diffJson;
    @Column(columnDefinition = "TEXT") public String checksJson;
    @Column(nullable = false, length = 24) public String verificationStatus;
    @Column(length = 1200) public String verificationDetail;
    @Column(nullable = false, length = 24) public String provenance;
    @Column(nullable = false) public Instant updatedAt;
    public Instant createdAt;
    @Column(length = 40) public String assessmentId;
    @Column(length = 40) public String findingId;
    @Column(length = 240) public String findingTitle;
    @Column(length = 400) public String filePath;
    @Column(length = 60) public String strategy;
    /** Unified diff for review. Credential values are redacted, so it is not guaranteed to apply verbatim. */
    @Column(columnDefinition = "TEXT") public String patchText;
    public RepairJobEntity() {}
}

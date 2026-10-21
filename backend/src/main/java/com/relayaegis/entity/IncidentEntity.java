package com.relayaegis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "incidents")
public class IncidentEntity {
    @Id public String id;
    @Column(nullable = false, length = 240) public String title;
    @Column(nullable = false, length = 60) public String applicationId;
    @Column(nullable = false, length = 160) public String applicationName;
    @Column(nullable = false, length = 16) public String severity;
    @Column(nullable = false, length = 32) public String state;
    @Column(nullable = false, length = 48) public String status;
    @Column(nullable = false) public Instant detectedAt;
    public Long detectionDurationMs;
    @Column(length = 200) public String component;
    @Column(length = 2000) public String summary;
    @Column(length = 2000) public String likelyCause;
    @Column(length = 16) public String confidence;
    @Column(columnDefinition = "TEXT") public String supportingEvidenceJson;
    @Column(columnDefinition = "TEXT") public String evidenceJson;
    @Column(nullable = false, length = 24) public String verificationStatus;
    @Column(length = 1200) public String verificationDetail;
    public Long recoveryDurationMs;
    @Column(columnDefinition = "TEXT") public String timelineJson;
    @Column(nullable = false, length = 24) public String provenance;
    public Instant recoveredAt;
    /** Latest stored DiagnosisDto (AI hypothesis or rule-based fallback), as JSON. */
    @Column(columnDefinition = "TEXT") public String diagnosisJson;

    public IncidentEntity() {}
}

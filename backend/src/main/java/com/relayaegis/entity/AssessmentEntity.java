package com.relayaegis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "software_assessments")
public class AssessmentEntity {
    @Id
    @Column(length = 40, nullable = false)
    private String id;

    @Column(length = 24, nullable = false)
    private String kind;

    @Column(name = "repository_url", length = 1000)
    private String repositoryUrl;

    @Column(name = "application_url", length = 2000)
    private String applicationUrl;

    @Column(length = 24, nullable = false)
    private String status;

    @Column(name = "current_stage", length = 40, nullable = false)
    private String currentStage = "queued";

    @Column(name = "timeline_json", columnDefinition = "TEXT", nullable = false)
    private String timelineJson = "[]";

    @Column(length = 1200)
    private String summary;

    @Column(name = "repository_commit", length = 80)
    private String repositoryCommit;

    @Column(name = "ai_triage_status", length = 40, nullable = false)
    private String aiTriageStatus = "not_requested";

    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    @Column(name = "findings_json", columnDefinition = "TEXT", nullable = false)
    private String findingsJson = "[]";

    @Column(name = "checks_json", columnDefinition = "TEXT", nullable = false)
    private String checksJson = "[]";

    @Column(name = "error_message", length = 1200)
    private String errorMessage;

    @Column(name = "ai_triage_requested", nullable = false)
    private boolean aiTriageRequested;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(length = 24, nullable = false)
    private String provenance = "live";

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getRepositoryUrl() { return repositoryUrl; }
    public void setRepositoryUrl(String repositoryUrl) { this.repositoryUrl = repositoryUrl; }
    public String getApplicationUrl() { return applicationUrl; }
    public void setApplicationUrl(String applicationUrl) { this.applicationUrl = applicationUrl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCurrentStage() { return currentStage; }
    public void setCurrentStage(String currentStage) { this.currentStage = currentStage; }
    public String getTimelineJson() { return timelineJson; }
    public void setTimelineJson(String timelineJson) { this.timelineJson = timelineJson; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getRepositoryCommit() { return repositoryCommit; }
    public void setRepositoryCommit(String repositoryCommit) { this.repositoryCommit = repositoryCommit; }
    public String getAiTriageStatus() { return aiTriageStatus; }
    public void setAiTriageStatus(String aiTriageStatus) { this.aiTriageStatus = aiTriageStatus; }
    public String getAiSummary() { return aiSummary; }
    public void setAiSummary(String aiSummary) { this.aiSummary = aiSummary; }
    public String getFindingsJson() { return findingsJson; }
    public void setFindingsJson(String findingsJson) { this.findingsJson = findingsJson; }
    public String getChecksJson() { return checksJson; }
    public void setChecksJson(String checksJson) { this.checksJson = checksJson; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public boolean isAiTriageRequested() { return aiTriageRequested; }
    public void setAiTriageRequested(boolean aiTriageRequested) { this.aiTriageRequested = aiTriageRequested; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public String getProvenance() { return provenance; }
    public void setProvenance(String provenance) { this.provenance = provenance; }
}

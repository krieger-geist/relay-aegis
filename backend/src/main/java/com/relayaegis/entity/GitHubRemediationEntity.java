package com.relayaegis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Audit record of one attempt to turn an approved patch proposal into a GitHub branch, commit and pull request.
 * Status only advances after GitHub confirms each step; partial progress (e.g. committed but no PR) is kept as is.
 * Never holds credentials.
 */
@Entity
@Table(name = "github_remediations")
public class GitHubRemediationEntity {
    public static final String APPLYING = "APPLYING";
    public static final String BRANCH_CREATED = "BRANCH_CREATED";
    public static final String COMMITTED = "COMMITTED";
    public static final String PR_CREATED = "PR_CREATED";
    public static final String FAILED = "FAILED";

    @Id public String id;
    @Column(nullable = false, length = 40) public String repairJobId;
    @Column(nullable = false, length = 40) public String assessmentId;
    @Column(nullable = false, length = 40) public String findingId;
    @Column(nullable = false, length = 40) public String userId;
    @Column(nullable = false, length = 160) public String repository;
    @Column(length = 255) public String baseBranch;
    @Column(length = 64) public String baseSha;
    @Column(length = 255) public String branch;
    @Column(length = 64) public String commitSha;
    public Integer prNumber;
    @Column(length = 500) public String prUrl;
    @Column(nullable = false, length = 24) public String status;
    @Column(length = 40) public String failedStage;
    @Column(length = 60) public String errorCode;
    @Column(length = 1000) public String errorMessage;
    @Column(nullable = false, length = 40) public String validationStatus;
    @Column(nullable = false) public boolean preexistingFailuresAcknowledged;
    @Column(length = 24) public String verificationStatus;
    @Column(length = 1200) public String verificationDetail;
    @Column(nullable = false) public Instant createdAt;
    @Column(nullable = false) public Instant updatedAt;

    public GitHubRemediationEntity() {}
}

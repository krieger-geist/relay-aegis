-- Additive: audit record of each attempt to apply an approved patch proposal to GitHub as a branch + pull request.
-- No existing table or row is changed. GitHub credentials are never stored.
CREATE TABLE github_remediations (
    id VARCHAR(40) PRIMARY KEY,
    repair_job_id VARCHAR(40) NOT NULL,
    assessment_id VARCHAR(40) NOT NULL,
    finding_id VARCHAR(40) NOT NULL,
    user_id VARCHAR(40) NOT NULL,
    repository VARCHAR(160) NOT NULL,
    base_branch VARCHAR(255),
    base_sha VARCHAR(64),
    branch VARCHAR(255),
    commit_sha VARCHAR(64),
    pr_number INTEGER,
    pr_url VARCHAR(500),
    -- APPLYING, BRANCH_CREATED, COMMITTED, PR_CREATED, FAILED
    status VARCHAR(24) NOT NULL,
    failed_stage VARCHAR(40),
    error_code VARCHAR(60),
    error_message VARCHAR(1000),
    validation_status VARCHAR(24) NOT NULL,
    preexisting_failures_acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    verification_status VARCHAR(24),
    verification_detail VARCHAR(1200),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_github_remediations_status CHECK (status IN ('APPLYING', 'BRANCH_CREATED', 'COMMITTED', 'PR_CREATED', 'FAILED'))
);
CREATE INDEX idx_github_remediations_job ON github_remediations(repair_job_id);
CREATE INDEX idx_github_remediations_user ON github_remediations(user_id);

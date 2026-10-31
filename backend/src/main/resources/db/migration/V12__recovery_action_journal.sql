-- Additive: the durable recovery action journal. Each attempt's intent is committed before the Docker operation runs
-- and is moved to executed / verified (or a failure phase) as it progresses, so a backend that stops mid-recovery can
-- tell on startup what was done and resume by verifying instead of repeating the action.
CREATE TABLE recovery_actions (
    id VARCHAR(40) PRIMARY KEY,
    run_id VARCHAR(40) NOT NULL,
    incident_id VARCHAR(60) NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    attempt INTEGER NOT NULL,
    action VARCHAR(32) NOT NULL,
    target VARCHAR(200),
    app_container VARCHAR(200),
    policy_id VARCHAR(60),
    policy_mode VARCHAR(32),
    automatic BOOLEAN NOT NULL,
    phase VARCHAR(24) NOT NULL,
    execution_detail VARCHAR(2000),
    verification_status VARCHAR(24),
    verification_detail VARCHAR(2000),
    intended_at TIMESTAMP WITH TIME ZONE NOT NULL,
    executed_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    -- One row per attempt number: two workers can never both record (and so both run) the same attempt.
    CONSTRAINT uk_recovery_actions_incident_attempt UNIQUE (incident_id, attempt)
);
CREATE INDEX idx_recovery_actions_phase ON recovery_actions(phase);

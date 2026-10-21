CREATE TABLE monitored_applications (
  id VARCHAR(60) PRIMARY KEY,
  name VARCHAR(160) NOT NULL,
  url VARCHAR(1000) NOT NULL,
  framework VARCHAR(160) NOT NULL,
  environment VARCHAR(32) NOT NULL,
  status VARCHAR(48) NOT NULL,
  response_ms BIGINT,
  error_rate DOUBLE PRECISION,
  last_checked TIMESTAMP WITH TIME ZONE,
  deployment_sha VARCHAR(100) NOT NULL,
  monitoring_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  description VARCHAR(1000),
  docker_container VARCHAR(200),
  github_repository VARCHAR(300),
  provenance VARCHAR(24) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE incidents (
  id VARCHAR(60) PRIMARY KEY,
  title VARCHAR(240) NOT NULL,
  application_id VARCHAR(60) NOT NULL,
  application_name VARCHAR(160) NOT NULL,
  severity VARCHAR(16) NOT NULL,
  state VARCHAR(32) NOT NULL,
  status VARCHAR(48) NOT NULL,
  detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
  detection_duration_ms BIGINT,
  component VARCHAR(200),
  summary VARCHAR(2000),
  likely_cause VARCHAR(2000),
  confidence VARCHAR(16),
  supporting_evidence_json TEXT,
  evidence_json TEXT,
  verification_status VARCHAR(24) NOT NULL,
  verification_detail VARCHAR(1200),
  recovery_duration_ms BIGINT,
  timeline_json TEXT,
  provenance VARCHAR(24) NOT NULL,
  recovered_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_incidents_app_detected ON incidents(application_id, detected_at);

CREATE TABLE health_checks (
  id VARCHAR(60) PRIMARY KEY,
  application_id VARCHAR(60) NOT NULL,
  checked_at TIMESTAMP WITH TIME ZONE NOT NULL,
  healthy BOOLEAN NOT NULL,
  response_ms BIGINT,
  error_message VARCHAR(1000),
  provenance VARCHAR(24) NOT NULL
);
CREATE INDEX idx_health_checks_app_time ON health_checks(application_id, checked_at);

CREATE TABLE recovery_events (
  id VARCHAR(60) PRIMARY KEY,
  incident_id VARCHAR(60) NOT NULL,
  application_name VARCHAR(160) NOT NULL,
  event VARCHAR(240) NOT NULL,
  outcome VARCHAR(48) NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  detection_duration_ms BIGINT,
  recovery_duration_ms BIGINT,
  detail VARCHAR(2000),
  provenance VARCHAR(24) NOT NULL
);
CREATE INDEX idx_recovery_events_time ON recovery_events(occurred_at);

CREATE TABLE recovery_policies (
  id VARCHAR(60) PRIMARY KEY,
  action VARCHAR(200) NOT NULL,
  description VARCHAR(1000),
  mode VARCHAR(32) NOT NULL,
  risk VARCHAR(16) NOT NULL,
  scope VARCHAR(160) NOT NULL,
  provenance VARCHAR(24) NOT NULL
);

CREATE TABLE repair_jobs (
  id VARCHAR(60) PRIMARY KEY,
  incident_id VARCHAR(60) NOT NULL,
  application_name VARCHAR(160) NOT NULL,
  repository VARCHAR(300) NOT NULL,
  commit_sha VARCHAR(120) NOT NULL,
  state VARCHAR(40) NOT NULL,
  explanation VARCHAR(2000),
  files_changed INTEGER NOT NULL DEFAULT 0,
  diff_json TEXT,
  checks_json TEXT,
  verification_status VARCHAR(24) NOT NULL,
  verification_detail VARCHAR(1200),
  provenance VARCHAR(24) NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

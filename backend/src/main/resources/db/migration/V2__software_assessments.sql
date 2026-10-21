CREATE TABLE software_assessments (
  id VARCHAR(40) PRIMARY KEY,
  kind VARCHAR(24) NOT NULL,
  repository_url VARCHAR(1000),
  application_url VARCHAR(2000),
  status VARCHAR(24) NOT NULL,
  current_stage VARCHAR(40) NOT NULL DEFAULT 'queued',
  timeline_json TEXT NOT NULL DEFAULT '[]',
  summary VARCHAR(1200),
  repository_commit VARCHAR(80),
  ai_triage_status VARCHAR(40) NOT NULL DEFAULT 'not_requested',
  ai_summary TEXT,
  findings_json TEXT NOT NULL DEFAULT '[]',
  checks_json TEXT NOT NULL DEFAULT '[]',
  error_message VARCHAR(1200),
  ai_triage_requested BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  started_at TIMESTAMP WITH TIME ZONE,
  completed_at TIMESTAMP WITH TIME ZONE,
  provenance VARCHAR(24) NOT NULL DEFAULT 'live'
);
CREATE INDEX idx_software_assessments_created ON software_assessments(created_at DESC);
CREATE INDEX idx_software_assessments_status ON software_assessments(status);

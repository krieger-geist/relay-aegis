-- Additive: lets a remediation record originate from an assessment finding instead of an incident.
-- Existing rows keep their incident_id; no data is changed or removed.
ALTER TABLE repair_jobs ALTER COLUMN incident_id DROP NOT NULL;
ALTER TABLE repair_jobs ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE repair_jobs ADD COLUMN assessment_id VARCHAR(40);
ALTER TABLE repair_jobs ADD COLUMN finding_id VARCHAR(40);
ALTER TABLE repair_jobs ADD COLUMN finding_title VARCHAR(240);
ALTER TABLE repair_jobs ADD COLUMN file_path VARCHAR(400);
ALTER TABLE repair_jobs ADD COLUMN strategy VARCHAR(60);
ALTER TABLE repair_jobs ADD COLUMN patch_text TEXT;
CREATE INDEX idx_repair_jobs_finding ON repair_jobs(assessment_id, finding_id);

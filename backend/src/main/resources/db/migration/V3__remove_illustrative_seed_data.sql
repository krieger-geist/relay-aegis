-- Purge synthetic records and any history attached to seed applications.
-- Assessment reports are preserved because they are independently collected records.
DELETE FROM repair_jobs
 WHERE provenance = 'demo'
    OR incident_id IN (SELECT id FROM incidents WHERE provenance = 'demo' OR application_id IN (SELECT id FROM monitored_applications WHERE provenance = 'demo'));
DELETE FROM recovery_events
 WHERE provenance = 'demo'
    OR incident_id IN (SELECT id FROM incidents WHERE provenance = 'demo' OR application_id IN (SELECT id FROM monitored_applications WHERE provenance = 'demo'));
DELETE FROM incidents
 WHERE provenance = 'demo'
    OR application_id IN (SELECT id FROM monitored_applications WHERE provenance = 'demo');
DELETE FROM health_checks
 WHERE provenance = 'demo'
    OR application_id IN (SELECT id FROM monitored_applications WHERE provenance = 'demo');
DELETE FROM monitored_applications WHERE provenance = 'demo';
DELETE FROM recovery_policies WHERE provenance = 'demo' OR id = 'policy-dependency';
UPDATE recovery_policies
   SET action = 'Restart an unhealthy container',
       description = 'Restart only an explicitly allowlisted, non-production container after policy validation.',
       scope = 'Configured non-production Docker container allowlist',
       provenance = 'live'
 WHERE id = 'policy-restart';

-- Additive (Phase 4): a user-journey URL checked after the health endpoint passes, an optional quarantine hook the
-- application exposes for moving one bad record aside, and the record a failing journey named. No data is changed.
ALTER TABLE monitored_applications ADD COLUMN journey_url VARCHAR(1000);
ALTER TABLE monitored_applications ADD COLUMN quarantine_url VARCHAR(1000);
ALTER TABLE health_checks ADD COLUMN record_ref VARCHAR(120);

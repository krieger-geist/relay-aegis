-- Additive only: distinguishes "responded but unhealthy" from "no response" for each observation.
-- Existing rows keep NULL and are presented using their original healthy flag; no data is changed or removed.
ALTER TABLE health_checks ADD COLUMN outcome VARCHAR(24);
ALTER TABLE health_checks ADD COLUMN status_code INTEGER;

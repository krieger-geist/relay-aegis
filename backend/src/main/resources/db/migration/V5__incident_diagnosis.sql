-- Additive only: stores the latest diagnosis (provider, fallback reason, recommendations) with each incident.
ALTER TABLE incidents ADD COLUMN diagnosis_json TEXT;

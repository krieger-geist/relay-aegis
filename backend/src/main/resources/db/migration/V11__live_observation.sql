-- Additive: the structured observation of a Live URL run (timings, TLS, header presence; never a response body),
-- used to break request time down and to compare a run with the previous run of the same URL. Null for other kinds.
ALTER TABLE software_assessments ADD COLUMN live_observation_json TEXT;

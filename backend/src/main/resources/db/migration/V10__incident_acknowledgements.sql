-- Additive: which incident notifications each user has acknowledged (opened or dismissed).
-- Acknowledging is a read state only; it never changes the incident, its evidence or its recovery state.
CREATE TABLE incident_acknowledgements (
    id VARCHAR(110) PRIMARY KEY,
    user_id VARCHAR(40) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    incident_id VARCHAR(60) NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    acknowledged_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_incident_ack_user_incident UNIQUE (user_id, incident_id)
);
CREATE INDEX idx_incident_ack_user ON incident_acknowledgements(user_id);

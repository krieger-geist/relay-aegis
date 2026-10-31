-- Additive: the leadership lease. With several backend instances, the one holding an unexpired row monitors
-- applications and runs recovery; the others serve the API and forward recovery requests to it.
CREATE TABLE leader_leases (
    name VARCHAR(60) PRIMARY KEY,
    holder VARCHAR(120) NOT NULL,
    holder_url VARCHAR(300),
    acquired_at TIMESTAMP WITH TIME ZONE NOT NULL,
    renewed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

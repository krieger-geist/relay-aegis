-- Additive: user accounts and rotating refresh tokens for authentication. No existing table or row is changed.
CREATE TABLE users (
    id VARCHAR(40) PRIMARY KEY,
    full_name VARCHAR(120) NOT NULL,
    -- Stored trimmed and lower-cased by the application, so the unique constraint is effectively case-insensitive.
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'))
);

-- Only a SHA-256 hash of each refresh token is stored; the raw value lives in the client's HttpOnly cookie.
-- Tokens rotate on every use; family_id groups a login's rotation chain so reuse of a rotated token revokes it all.
CREATE TABLE refresh_tokens (
    id VARCHAR(40) PRIMARY KEY,
    user_id VARCHAR(40) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    family_id VARCHAR(40) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    replaced_by_id VARCHAR(40),
    CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens(family_id);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens(expires_at);

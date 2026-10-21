-- Fix: "preexisting_test_failures" (25 characters) did not fit VARCHAR(24). Widening is non-destructive.
ALTER TABLE github_remediations ALTER COLUMN validation_status TYPE VARCHAR(40);

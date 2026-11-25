ALTER TABLE platform.app_user
    ALTER COLUMN email DROP NOT NULL,
    ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE platform.email_verification_challenge (
    id uuid PRIMARY KEY,
    email varchar(320) NOT NULL,
    code_hash char(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    verified_at timestamptz,
    consumed_at timestamptz,
    request_ip_hash char(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_email_challenge_hash CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_email_challenge_attempts CHECK (attempt_count BETWEEN 0 AND 5),
    CONSTRAINT ck_email_challenge_times CHECK (
        (verified_at IS NULL OR verified_at >= created_at)
        AND (consumed_at IS NULL OR consumed_at >= created_at)
        AND (expires_at > created_at)
    )
);

CREATE INDEX ix_email_challenge_email_created
    ON platform.email_verification_challenge(email, created_at DESC);

CREATE INDEX ix_email_challenge_ip_created
    ON platform.email_verification_challenge(request_ip_hash, created_at DESC);

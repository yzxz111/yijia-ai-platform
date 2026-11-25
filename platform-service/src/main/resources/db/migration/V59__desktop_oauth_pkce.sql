CREATE TABLE platform.oauth_authorization_codes (
    id uuid PRIMARY KEY,
    code_hash varchar(64) NOT NULL UNIQUE,
    client_id varchar(100) NOT NULL,
    user_id uuid NOT NULL REFERENCES platform.app_user(id) ON DELETE CASCADE,
    tenant_id uuid,
    redirect_uri text NOT NULL,
    code_challenge varchar(128) NOT NULL,
    code_challenge_method varchar(10) NOT NULL,
    device_id varchar(32) NOT NULL,
    scope text NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_oauth_authorization_codes_hash CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_oauth_authorization_codes_device CHECK (device_id ~ '^[0-9a-f]{32}$'),
    CONSTRAINT ck_oauth_authorization_codes_method CHECK (code_challenge_method = 'S256'),
    CONSTRAINT ck_oauth_authorization_codes_challenge CHECK (length(code_challenge) BETWEEN 43 AND 128),
    CONSTRAINT ck_oauth_authorization_codes_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_oauth_authorization_codes_consumption
        CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);

COMMENT ON TABLE platform.oauth_authorization_codes IS
    'Desktop OAuth 2.0 authorization codes (PKCE S256). Only the SHA-256 digest is stored.';

CREATE INDEX ix_oauth_authorization_codes_expire
    ON platform.oauth_authorization_codes (expires_at)
    WHERE consumed_at IS NULL;

CREATE INDEX ix_oauth_authorization_codes_user
    ON platform.oauth_authorization_codes (user_id, created_at DESC);

CREATE TABLE platform.oauth_refresh_tokens (
    id uuid PRIMARY KEY,
    token_hash varchar(64) NOT NULL UNIQUE,
    token_family_id uuid NOT NULL,
    client_id varchar(100) NOT NULL,
    user_id uuid NOT NULL REFERENCES platform.app_user(id) ON DELETE CASCADE,
    tenant_id uuid,
    device_id varchar(32) NOT NULL,
    terminal_session_id uuid REFERENCES platform.terminal_session(id) ON DELETE RESTRICT,
    scope text NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    revocation_reason varchar(40),
    replaced_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    CONSTRAINT ck_oauth_refresh_tokens_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_oauth_refresh_tokens_device CHECK (device_id ~ '^[0-9a-f]{32}$'),
    CONSTRAINT ck_oauth_refresh_tokens_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_oauth_refresh_tokens_revocation CHECK (
        (revoked_at IS NULL AND revocation_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revocation_reason IS NOT NULL)
    )
);

COMMENT ON TABLE platform.oauth_refresh_tokens IS
    'Desktop OAuth refresh tokens with rotation. Only the SHA-256 digest is stored.';

CREATE INDEX ix_oauth_refresh_tokens_user_client
    ON platform.oauth_refresh_tokens (user_id, client_id);

CREATE INDEX ix_oauth_refresh_tokens_family
    ON platform.oauth_refresh_tokens (token_family_id);

CREATE INDEX ix_oauth_refresh_tokens_expire
    ON platform.oauth_refresh_tokens (expires_at);

CREATE TABLE platform.oauth_login_transactions (
    id uuid PRIMARY KEY,
    transaction_id varchar(64) NOT NULL UNIQUE,
    client_id varchar(100) NOT NULL,
    redirect_uri text NOT NULL,
    device_id varchar(32) NOT NULL,
    scope text NOT NULL,
    state varchar(512) NOT NULL,
    code_challenge varchar(128) NOT NULL,
    code_challenge_method varchar(10) NOT NULL,
    browser_session_id varchar(255),
    user_id uuid,
    tenant_id uuid,
    status varchar(32) NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT ck_oauth_login_transactions_device CHECK (device_id ~ '^[0-9a-f]{32}$'),
    CONSTRAINT ck_oauth_login_transactions_method CHECK (code_challenge_method = 'S256'),
    CONSTRAINT ck_oauth_login_transactions_status CHECK (status IN (
        'PENDING', 'AUTHENTICATED', 'HANDOFF_PENDING', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_oauth_login_transactions_expiry CHECK (expires_at > created_at)
);

COMMENT ON TABLE platform.oauth_login_transactions IS
    'Browser-side OAuth login transactions that survive the login redirect.';

CREATE INDEX ix_oauth_login_transactions_expire
    ON platform.oauth_login_transactions (expires_at);

CREATE INDEX ix_oauth_login_transactions_browser_session
    ON platform.oauth_login_transactions (browser_session_id);

CREATE INDEX ix_oauth_login_transactions_open
    ON platform.oauth_login_transactions (status, created_at DESC)
    WHERE status IN ('PENDING', 'AUTHENTICATED', 'HANDOFF_PENDING');

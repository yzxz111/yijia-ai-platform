CREATE TABLE platform.terminal_session (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES platform.app_user(id) ON DELETE CASCADE,
    client_type varchar(32) NOT NULL,
    device_hash char(64) NOT NULL,
    status varchar(20) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    revocation_reason varchar(40),
    CONSTRAINT ck_terminal_session_status
        CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_terminal_session_device_hash
        CHECK (device_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_terminal_session_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_terminal_session_revocation CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL AND revocation_reason IS NULL)
        OR (status IN ('REVOKED', 'EXPIRED') AND revoked_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_terminal_session_active_slot
    ON platform.terminal_session(user_id, client_type)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_terminal_session_user_history
    ON platform.terminal_session(user_id, created_at DESC);

ALTER TABLE platform.refresh_token
    ADD COLUMN terminal_session_id uuid
        REFERENCES platform.terminal_session(id) ON DELETE RESTRICT,
    ADD COLUMN client_type varchar(32),
    ADD COLUMN device_hash char(64),
    ADD COLUMN revocation_reason varchar(40),
    ADD CONSTRAINT ck_refresh_token_terminal_binding CHECK (
        (terminal_session_id IS NULL AND client_type IS NULL AND device_hash IS NULL)
        OR (terminal_session_id IS NOT NULL AND client_type IS NOT NULL AND device_hash IS NOT NULL)
    ),
    ADD CONSTRAINT ck_refresh_token_device_hash CHECK (
        device_hash IS NULL OR device_hash ~ '^[0-9a-f]{64}$'
    );

CREATE INDEX ix_refresh_token_terminal
    ON platform.refresh_token(terminal_session_id, expires_at)
    WHERE terminal_session_id IS NOT NULL;

CREATE TABLE platform.login_handoff_request (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES platform.app_user(id) ON DELETE CASCADE,
    client_type varchar(32) NOT NULL,
    old_terminal_session_id uuid NOT NULL
        REFERENCES platform.terminal_session(id) ON DELETE RESTRICT,
    new_terminal_session_id uuid
        REFERENCES platform.terminal_session(id) ON DELETE RESTRICT,
    new_device_hash char(64) NOT NULL,
    request_token_hash char(64) NOT NULL UNIQUE,
    status varchar(24) NOT NULL,
    region varchar(100),
    user_agent_summary varchar(200),
    requested_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    decided_at timestamptz,
    decided_by_terminal_session_id uuid
        REFERENCES platform.terminal_session(id) ON DELETE RESTRICT,
    completed_at timestamptz,
    CONSTRAINT ck_login_handoff_status CHECK (status IN (
        'PENDING', 'APPROVED', 'DENIED', 'CANCELLED',
        'SUPERSEDED', 'COMPLETED', 'EXPIRED')),
    CONSTRAINT ck_login_handoff_hashes CHECK (
        new_device_hash ~ '^[0-9a-f]{64}$'
        AND request_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_login_handoff_expiry CHECK (expires_at > requested_at)
);

CREATE UNIQUE INDEX uq_login_handoff_pending_slot
    ON platform.login_handoff_request(user_id, client_type)
    WHERE status = 'PENDING';

CREATE INDEX ix_login_handoff_old_pending
    ON platform.login_handoff_request(old_terminal_session_id, requested_at DESC)
    WHERE status = 'PENDING';

CREATE INDEX ix_login_handoff_retention
    ON platform.login_handoff_request(status, completed_at, decided_at, expires_at);

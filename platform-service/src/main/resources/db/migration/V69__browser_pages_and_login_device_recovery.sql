CREATE TABLE platform.terminal_browser_page (
    terminal_session_id uuid NOT NULL REFERENCES platform.terminal_session(id) ON DELETE CASCADE,
    page_id uuid NOT NULL,
    event_sequence bigint NOT NULL CHECK (event_sequence >= 0),
    closed boolean NOT NULL,
    PRIMARY KEY (terminal_session_id, page_id)
);

CREATE TABLE platform.login_device_recovery (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES platform.app_user(id) ON DELETE CASCADE,
    device_hash varchar(64) NOT NULL,
    token_hash varchar(64) NOT NULL,
    csrf_hash varchar(64) NOT NULL,
    auth_version bigint NOT NULL,
    expires_at timestamptz NOT NULL,
    UNIQUE (user_id, device_hash)
);
CREATE INDEX login_device_recovery_expiry_idx ON platform.login_device_recovery(expires_at);

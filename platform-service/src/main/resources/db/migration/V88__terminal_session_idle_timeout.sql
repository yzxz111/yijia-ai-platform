-- Existing devices receive a full inactivity window after this migration.
ALTER TABLE platform.terminal_session
    ADD COLUMN last_activity_at timestamptz NOT NULL DEFAULT now();

CREATE INDEX terminal_session_idle_idx
    ON platform.terminal_session(last_activity_at, id)
    WHERE status = 'ACTIVE' AND client_type = 'DESKTOP_WEB';

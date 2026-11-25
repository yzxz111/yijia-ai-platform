ALTER TABLE platform.terminal_session
    ADD COLUMN browser_close_requested_at timestamptz;

CREATE INDEX ix_terminal_session_pending_browser_close
    ON platform.terminal_session(browser_close_requested_at)
    WHERE status = 'ACTIVE' AND browser_close_requested_at IS NOT NULL;

ALTER TABLE platform.app_user
    ADD COLUMN max_login_devices smallint NOT NULL DEFAULT 5,
    ADD CONSTRAINT ck_app_user_max_login_devices
        CHECK (max_login_devices BETWEEN 1 AND 10);

DROP INDEX IF EXISTS platform.uq_terminal_session_active_slot;

CREATE UNIQUE INDEX uq_terminal_session_active_device
    ON platform.terminal_session(user_id, client_type, device_hash)
    WHERE status = 'ACTIVE';

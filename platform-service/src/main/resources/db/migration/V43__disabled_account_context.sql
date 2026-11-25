ALTER TABLE platform.app_user
    ADD COLUMN disabled_reason varchar(500),
    ADD COLUMN disabled_at timestamptz,
    ADD COLUMN disabled_by uuid REFERENCES platform.app_user(id) ON DELETE SET NULL;

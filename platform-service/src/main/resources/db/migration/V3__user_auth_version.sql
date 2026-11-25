ALTER TABLE platform.app_user
    ADD COLUMN auth_version bigint NOT NULL DEFAULT 0;

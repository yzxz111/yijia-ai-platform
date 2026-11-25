SET LOCAL search_path = platform, public;

ALTER TABLE platform.app_user
    ADD COLUMN avatar_bytes bytea,
    ADD COLUMN avatar_content_type varchar(64),
    ADD COLUMN avatar_updated_at timestamptz;

ALTER TABLE platform.app_user
    ADD CONSTRAINT ck_app_user_avatar_content_type
    CHECK (avatar_content_type IS NULL OR avatar_content_type IN ('image/jpeg', 'image/png', 'image/gif'));

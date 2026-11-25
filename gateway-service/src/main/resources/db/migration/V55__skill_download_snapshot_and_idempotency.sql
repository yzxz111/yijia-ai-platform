-- Freeze the package selected when a download session is created and scope idempotency to the
-- authenticated caller, regardless of whether the caller used a platform JWT or an API key.

ALTER TABLE gateway.skill_download
    ADD COLUMN identity_key varchar(128),
    ADD COLUMN skill_code varchar(64),
    ADD COLUMN object_key varchar(512),
    ADD COLUMN md5 char(32),
    ADD COLUMN sha256 char(64),
    ADD COLUMN bytes_received bigint NOT NULL DEFAULT 0,
    ADD COLUMN idempotency_active boolean NOT NULL DEFAULT true;

UPDATE gateway.skill_download download
SET identity_key = CASE
        WHEN download.api_key_id IS NOT NULL
            THEN 'api-key:' || download.api_key_id::text
        WHEN download.tenant_id IS NOT NULL AND download.user_id IS NOT NULL
            THEN 'user:' || download.tenant_id::text || ':' || download.user_id::text
        ELSE 'user:' || download.user_id::text
    END,
    skill_code = skill.code,
    object_key = version.object_key,
    md5 = version.md5,
    sha256 = version.sha256,
    idempotency_active = download.expires_at > now()
FROM gateway.skill skill, gateway.skill_version version
WHERE download.skill_id = skill.id
  AND download.skill_version_id = version.id;

ALTER TABLE gateway.skill_download
    ALTER COLUMN identity_key SET NOT NULL,
    ALTER COLUMN skill_code SET NOT NULL,
    ADD CONSTRAINT ck_skill_download_bytes_received CHECK (bytes_received >= 0),
    ADD CONSTRAINT ck_skill_download_snapshot_md5
        CHECK (md5 IS NULL OR md5 ~ '^[0-9a-f]{32}$'),
    ADD CONSTRAINT ck_skill_download_snapshot_sha256
        CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$');

DROP INDEX gateway.uq_skill_download_idempotency;

CREATE UNIQUE INDEX uq_skill_download_active_idempotency
    ON gateway.skill_download (identity_key, idempotency_key)
    WHERE idempotency_key IS NOT NULL AND idempotency_active;

CREATE INDEX ix_skill_download_retention
    ON gateway.skill_download (started_at, id)
    WHERE NOT idempotency_active;

-- YCPS 1.0 is the only runtime package protocol from this migration onward.
-- Existing Legacy artifacts are deliberately archived and made unavailable; they must be
-- converted, validated and uploaded again instead of being relabelled as YCPS.

UPDATE gateway.skill_version
SET status = 'ARCHIVED'
WHERE status = 'PUBLISHED' AND deleted_at IS NULL;

UPDATE gateway.skill
SET status = 'UNPUBLISHED',
    published_version = NULL,
    unpublished_at = COALESCE(unpublished_at, now()),
    updated_at = now(),
    row_version = row_version + 1
WHERE status = 'PUBLISHED' AND deleted_at IS NULL;

UPDATE gateway.skill_download
SET idempotency_active = false,
    expires_at = LEAST(expires_at, now())
WHERE idempotency_active;

ALTER TABLE gateway.skill DROP CONSTRAINT ck_skill_code;
ALTER TABLE gateway.skill
    ADD CONSTRAINT ck_skill_code
        CHECK (code ~ '^[a-z0-9]+(-[a-z0-9]+)*$') NOT VALID;

ALTER TABLE gateway.skill_version DROP CONSTRAINT ck_skill_version_semver;
ALTER TABLE gateway.skill_version
    ADD CONSTRAINT ck_skill_version_semver CHECK (
        version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$'
    ) NOT VALID;

ALTER TABLE gateway.skill_version
    ADD COLUMN package_format varchar(16),
    ADD COLUMN ycps_version varchar(16),
    ADD COLUMN package_kind varchar(16),
    ADD COLUMN plugin_manifest jsonb,
    ADD COLUMN runtime_manifest jsonb,
    ADD COLUMN manifest_sha256 char(64),
    ADD COLUMN dependency_lock_sha256 char(64),
    ADD COLUMN signature_version varchar(64),
    ADD COLUMN signature_algorithm varchar(16),
    ADD COLUMN signature_key_id varchar(128),
    ADD COLUMN signature_envelope jsonb,
    ADD COLUMN signature text,
    ADD COLUMN sealed boolean NOT NULL DEFAULT false,
    ADD COLUMN sealed_at timestamptz;

ALTER TABLE gateway.skill_version
    ADD CONSTRAINT ck_skill_version_ycps_format
        CHECK (package_format IS NULL OR package_format = 'YCPS'),
    ADD CONSTRAINT ck_skill_version_ycps_version
        CHECK (ycps_version IS NULL OR ycps_version = '1.0.0'),
    ADD CONSTRAINT ck_skill_version_package_kind
        CHECK (package_kind IS NULL OR package_kind IN ('SKILL', 'EXPERT', 'TEAM')),
    ADD CONSTRAINT ck_skill_version_manifest_sha256
        CHECK (manifest_sha256 IS NULL OR manifest_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_skill_version_dependency_lock_sha256
        CHECK (dependency_lock_sha256 IS NULL OR dependency_lock_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_skill_version_sealed_at
        CHECK ((sealed AND sealed_at IS NOT NULL) OR (NOT sealed AND sealed_at IS NULL)),
    ADD CONSTRAINT ck_skill_version_published_ycps
        CHECK (status <> 'PUBLISHED' OR (
            package_format = 'YCPS'
            AND ycps_version = '1.0.0'
            AND package_kind IS NOT NULL
            AND plugin_manifest IS NOT NULL
            AND runtime_manifest IS NOT NULL
            AND manifest_sha256 IS NOT NULL
            AND dependency_lock_sha256 IS NOT NULL
            AND sealed
            AND signature_envelope IS NOT NULL
            AND signature IS NOT NULL));

ALTER TABLE gateway.skill_download
    ADD COLUMN revision integer,
    ADD COLUMN package_kind varchar(16),
    ADD COLUMN package_format varchar(16),
    ADD COLUMN ycps_version varchar(16),
    ADD COLUMN manifest_sha256 char(64),
    ADD COLUMN dependency_lock_sha256 char(64),
    ADD COLUMN dependencies jsonb,
    ADD COLUMN parent_download_id uuid REFERENCES gateway.skill_download(id) ON DELETE CASCADE,
    ADD COLUMN signature_envelope jsonb,
    ADD COLUMN signature text,
    ADD COLUMN sha256_verified boolean NOT NULL DEFAULT false;

ALTER TABLE gateway.skill_download
    ADD CONSTRAINT ck_skill_download_revision CHECK (revision IS NULL OR revision >= 1),
    ADD CONSTRAINT ck_skill_download_package_kind
        CHECK (package_kind IS NULL OR package_kind IN ('SKILL', 'EXPERT', 'TEAM')),
    ADD CONSTRAINT ck_skill_download_package_format
        CHECK (package_format IS NULL OR package_format = 'YCPS'),
    ADD CONSTRAINT ck_skill_download_ycps_version
        CHECK (ycps_version IS NULL OR ycps_version = '1.0.0');

CREATE TABLE gateway.skill_version_dependency (
    owner_version_id uuid NOT NULL REFERENCES gateway.skill_version(id) ON DELETE CASCADE,
    dependency_key varchar(64) NOT NULL,
    relation varchar(32) NOT NULL,
    required boolean NOT NULL,
    target_version_id uuid NOT NULL REFERENCES gateway.skill_version(id) ON DELETE RESTRICT,
    target_code varchar(64) NOT NULL,
    target_kind varchar(16) NOT NULL,
    target_version varchar(32) NOT NULL,
    target_revision integer NOT NULL,
    target_sha256 char(64) NOT NULL,
    PRIMARY KEY (owner_version_id, dependency_key),
    CONSTRAINT ck_skill_version_dependency_relation
        CHECK (relation IN ('SKILL_BINDING', 'TEAM_MEMBER')),
    CONSTRAINT ck_skill_version_dependency_kind
        CHECK (target_kind IN ('SKILL', 'EXPERT')),
    CONSTRAINT ck_skill_version_dependency_revision CHECK (target_revision >= 1),
    CONSTRAINT ck_skill_version_dependency_sha256
        CHECK (target_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_skill_version_dependency_not_self CHECK (owner_version_id <> target_version_id)
);

CREATE INDEX ix_skill_version_dependency_target
    ON gateway.skill_version_dependency (target_version_id, owner_version_id);

CREATE INDEX ix_skill_download_parent
    ON gateway.skill_download (parent_download_id)
    WHERE parent_download_id IS NOT NULL;

CREATE INDEX ix_skill_version_ycps_online
    ON gateway.skill_version (skill_id, version)
    WHERE deleted_at IS NULL AND package_format = 'YCPS' AND sealed;

COMMENT ON COLUMN gateway.skill_version.plugin_manifest IS
    'Validated root plugin.json for this immutable YCPS revision.';
COMMENT ON COLUMN gateway.skill_version.runtime_manifest IS
    'Validated com.yuzhihuihe.ycps/manifest.json for this immutable YCPS revision.';
COMMENT ON COLUMN gateway.skill_version.sealed IS
    'True after publish/signing. A sealed code+version+revision can never be replaced.';

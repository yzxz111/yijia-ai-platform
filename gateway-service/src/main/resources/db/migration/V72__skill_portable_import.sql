-- YCPS 1.1 Expert reverse-import staging.  This is deliberately separate from
-- gateway.skill_version: inspection and malware scanning must not reserve a skill/version.
CREATE TABLE gateway.skill_portable_import (
    import_id uuid PRIMARY KEY,
    object_key varchar(512) NOT NULL,
    artifact_sha256 char(64) NOT NULL,
    size_bytes bigint NOT NULL,
    artifact_md5 char(32) NOT NULL,
    file_name varchar(255) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'SCANNED',
    expires_at timestamptz NOT NULL,
    package_code varchar(64) NOT NULL,
    package_kind varchar(16) NOT NULL,
    package_version varchar(32) NOT NULL,
    package_revision integer NOT NULL DEFAULT 1,
    plugin_manifest jsonb NOT NULL,
    runtime_manifest jsonb NOT NULL,
    presentation jsonb NOT NULL,
    manifest_sha256 char(64) NOT NULL,
    dependency_lock_sha256 char(64) NOT NULL,
    min_client_version varchar(32),
    file_count integer NOT NULL,
    extracted_bytes bigint NOT NULL,
    scan_status varchar(16) NOT NULL,
    scan_report jsonb NOT NULL DEFAULT '{}'::jsonb,
    field_diff jsonb NOT NULL DEFAULT '{}'::jsonb,
    inventory jsonb NOT NULL DEFAULT '{}'::jsonb,
    warnings jsonb NOT NULL DEFAULT '[]'::jsonb,
    asset_keys jsonb NOT NULL DEFAULT '{}'::jsonb,
    target_skill_id uuid REFERENCES gateway.skill(id) ON DELETE SET NULL,
    target_row_version integer,
    commit_version_id uuid,
    committed_revision integer,
    commit_result jsonb,
    created_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    committed_at timestamptz,
    CONSTRAINT ck_skill_portable_import_sha256 CHECK (artifact_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_skill_portable_import_md5 CHECK (artifact_md5 ~ '^[0-9a-f]{32}$'),
    CONSTRAINT ck_skill_portable_import_size CHECK (size_bytes >= 0 AND extracted_bytes >= 0),
    CONSTRAINT ck_skill_portable_import_kind CHECK (package_kind = 'EXPERT'),
    CONSTRAINT ck_skill_portable_import_format CHECK (package_revision >= 1),
    CONSTRAINT ck_skill_portable_import_status CHECK (status IN ('SCANNED', 'COMMITTED', 'EXPIRED', 'FAILED')),
    CONSTRAINT ck_skill_portable_import_scan CHECK (scan_status IN ('PASSED', 'FAILED', 'REJECTED')),
    CONSTRAINT ck_skill_portable_import_result CHECK (
        (status = 'COMMITTED' AND commit_result IS NOT NULL
            AND commit_version_id IS NOT NULL AND committed_revision IS NOT NULL)
        OR status <> 'COMMITTED'),
    CONSTRAINT ck_skill_portable_import_committed_at CHECK (
        (status = 'COMMITTED' AND committed_at IS NOT NULL) OR status <> 'COMMITTED')
);

CREATE INDEX ix_skill_portable_import_expiry
    ON gateway.skill_portable_import (expires_at, status);
CREATE INDEX ix_skill_portable_import_code
    ON gateway.skill_portable_import (package_code, package_version, artifact_sha256);

COMMENT ON TABLE gateway.skill_portable_import IS
    'Isolated, scanned YCPS 1.1 Expert artifact awaiting an explicit admin commit.';
COMMENT ON COLUMN gateway.skill_portable_import.field_diff IS
    'Presence-aware package/current diff; null and empty values are retained for review.';
COMMENT ON COLUMN gateway.skill_portable_import.asset_keys IS
    'Object keys of Inspector-validated asset bytes; commit never trusts client declarations again.';

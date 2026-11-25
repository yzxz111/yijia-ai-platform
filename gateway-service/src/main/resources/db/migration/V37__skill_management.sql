-- Skill management domain: metadata, versions (packages) and download sessions.
-- Design: docs/superpowers/specs/2026-09-14-skill-management-client-download-design.md (4.1-4.3)

CREATE TABLE gateway.skill (
    id uuid PRIMARY KEY,
    code varchar(64) NOT NULL,
    name varchar(128) NOT NULL,
    category varchar(64),
    tags text[] NOT NULL DEFAULT '{}',
    summary varchar(512),
    description text,
    icon varchar(8),
    icon_object_key varchar(512),
    min_client_version varchar(32),
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    published_version varchar(32),
    latest_version varchar(32),
    download_count bigint NOT NULL DEFAULT 0,
    sort_weight integer NOT NULL DEFAULT 0,
    row_version integer NOT NULL DEFAULT 1,
    created_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid,
    updated_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    unpublished_at timestamptz,
    deleted_by uuid,
    deleted_at timestamptz,
    CONSTRAINT ck_skill_code CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT ck_skill_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'UNPUBLISHED', 'DELETED')),
    CONSTRAINT ck_skill_download_count CHECK (download_count >= 0),
    CONSTRAINT ck_skill_row_version CHECK (row_version >= 1),
    CONSTRAINT ck_skill_sort_weight CHECK (sort_weight >= 0),
    CONSTRAINT ck_skill_published_version CHECK (
        (status = 'PUBLISHED' AND published_version IS NOT NULL)
        OR status <> 'PUBLISHED')
);

CREATE UNIQUE INDEX ux_skill_code ON gateway.skill (code) WHERE deleted_at IS NULL;

CREATE INDEX ix_skill_client_visible ON gateway.skill (status, min_client_version)
    WHERE deleted_at IS NULL AND status = 'PUBLISHED';

CREATE TABLE gateway.skill_version (
    id uuid PRIMARY KEY,
    skill_id uuid NOT NULL REFERENCES gateway.skill (id) ON DELETE RESTRICT,
    version varchar(32) NOT NULL,
    revision integer NOT NULL DEFAULT 1,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    object_key varchar(512),
    quarantine_key varchar(512),
    file_name varchar(255),
    size_bytes bigint NOT NULL DEFAULT 0,
    md5 char(32),
    sha256 char(64),
    file_count integer NOT NULL DEFAULT 0,
    extracted_bytes bigint NOT NULL DEFAULT 0,
    min_client_version varchar(32),
    changelog text,
    scan_status varchar(16) NOT NULL DEFAULT 'PENDING',
    scan_report jsonb,
    download_count bigint NOT NULL DEFAULT 0,
    created_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT ck_skill_version_semver
        CHECK (version ~ '^[0-9]+(\.[0-9]+){0,3}(-[0-9A-Za-z.-]+)?$'),
    CONSTRAINT ck_skill_version_status
        CHECK (status IN ('DRAFT', 'READY', 'PUBLISHED', 'ARCHIVED', 'REJECTED', 'FAILED')),
    CONSTRAINT ck_skill_version_scan_status
        CHECK (scan_status IN ('PENDING', 'SCANNING', 'PASSED', 'REJECTED', 'FAILED')),
    CONSTRAINT ck_skill_version_revision CHECK (revision >= 1),
    CONSTRAINT ck_skill_version_md5 CHECK (md5 IS NULL OR md5 ~ '^[0-9a-f]{32}$'),
    CONSTRAINT ck_skill_version_sha256 CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_skill_version_sizes CHECK (size_bytes >= 0 AND extracted_bytes >= 0),
    CONSTRAINT ck_skill_version_object_key
        CHECK (object_key IS NULL OR status IN ('READY', 'PUBLISHED', 'ARCHIVED'))
);

CREATE UNIQUE INDEX ux_skill_version ON gateway.skill_version (skill_id, version)
    WHERE deleted_at IS NULL;

-- At most one published version per skill.
CREATE UNIQUE INDEX ux_skill_one_published ON gateway.skill_version (skill_id)
    WHERE status = 'PUBLISHED' AND deleted_at IS NULL;

CREATE INDEX ix_skill_version_skill ON gateway.skill_version (skill_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE gateway.skill_download (
    id uuid PRIMARY KEY,
    skill_id uuid NOT NULL REFERENCES gateway.skill (id) ON DELETE RESTRICT,
    skill_version_id uuid NOT NULL REFERENCES gateway.skill_version (id) ON DELETE RESTRICT,
    version varchar(32) NOT NULL,
    user_id uuid,
    tenant_id uuid,
    api_key_id uuid,
    device_id_hash char(64),
    status varchar(16) NOT NULL DEFAULT 'STARTED',
    content_length bigint NOT NULL,
    served_bytes bigint NOT NULL DEFAULT 0,
    range_from bigint,
    range_to bigint,
    md5_verified boolean NOT NULL DEFAULT false,
    installed boolean NOT NULL DEFAULT false,
    failure_code varchar(64),
    client_version varchar(32),
    client_platform varchar(32),
    request_id varchar(64),
    idempotency_key varchar(128),
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    expires_at timestamptz NOT NULL,
    CONSTRAINT ck_skill_download_status
        CHECK (status IN ('STARTED', 'COMPLETED', 'ABORTED', 'FAILED')),
    CONSTRAINT ck_skill_download_identity CHECK (user_id IS NOT NULL OR api_key_id IS NOT NULL),
    CONSTRAINT ck_skill_download_served CHECK (served_bytes >= 0 AND content_length >= 0),
    CONSTRAINT ck_skill_download_device
        CHECK (device_id_hash IS NULL OR device_id_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_skill_download_completion CHECK (
        (status = 'STARTED' AND completed_at IS NULL)
        OR (status <> 'STARTED' AND completed_at IS NOT NULL))
);

CREATE INDEX ix_skill_download_skill ON gateway.skill_download (skill_id, started_at DESC);

CREATE INDEX ix_skill_download_status_expiry ON gateway.skill_download (status, expires_at);

CREATE INDEX ix_skill_download_user ON gateway.skill_download (user_id, started_at DESC);

-- Idempotent session creation for one identity (design 6.7).
CREATE UNIQUE INDEX uq_skill_download_idempotency
    ON gateway.skill_download (api_key_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL AND api_key_id IS NOT NULL;

-- Download-count de-duplication lookup for one identity/device/version inside 24h (design 6.5).
CREATE INDEX ix_skill_download_dedupe
    ON gateway.skill_download (skill_version_id, device_id_hash, started_at DESC);


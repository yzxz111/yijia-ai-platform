-- Keep native ClawHub source archives outside the YCPS skill_version protocol.
ALTER TABLE gateway.skill
    ADD COLUMN source_format varchar(16) NOT NULL DEFAULT 'YCPS';

ALTER TABLE gateway.skill
    ADD CONSTRAINT ck_skill_source_format
        CHECK (source_format IN ('YCPS', 'CLAWHUB_SKILL'));

CREATE TABLE gateway.native_clawhub_source (
    id uuid PRIMARY KEY,
    skill_id uuid NOT NULL UNIQUE REFERENCES gateway.skill(id) ON DELETE RESTRICT,
    owner varchar(64) NOT NULL,
    slug varchar(64) NOT NULL,
    source_version varchar(128),
    display_name varchar(128) NOT NULL,
    description text,
    source_sha256 char(64) NOT NULL,
    size_bytes bigint NOT NULL,
    file_name varchar(255) NOT NULL,
    object_key varchar(512) NOT NULL UNIQUE,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    scan_status varchar(16) NOT NULL DEFAULT 'PENDING',
    review_required boolean NOT NULL DEFAULT false,
    inspection_review_required boolean NOT NULL DEFAULT false,
    adapter_version varchar(32) NOT NULL,
    decisions jsonb NOT NULL DEFAULT '[]'::jsonb,
    blocking_reasons jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_native_clawhub_owner CHECK (owner ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT ck_native_clawhub_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_native_clawhub_source_sha256 CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_native_clawhub_source_size CHECK (size_bytes > 0 AND size_bytes <= 52428800),
    CONSTRAINT ck_native_clawhub_source_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_native_clawhub_scan_status
        CHECK (scan_status IN ('PENDING', 'PASSED', 'REJECTED', 'FAILED')),
    CONSTRAINT ck_native_clawhub_format_marker CHECK (adapter_version <> '')
);

CREATE UNIQUE INDEX ux_native_clawhub_source_identity
    ON gateway.native_clawhub_source (owner, slug);

CREATE INDEX ix_native_clawhub_source_scan
    ON gateway.native_clawhub_source (scan_status, created_at DESC);

CREATE TABLE gateway.native_clawhub_release (
    id uuid PRIMARY KEY,
    skill_id uuid NOT NULL UNIQUE REFERENCES gateway.skill(id) ON DELETE RESTRICT,
    source_id uuid NOT NULL UNIQUE REFERENCES gateway.native_clawhub_source(id) ON DELETE RESTRICT,
    version varchar(32) NOT NULL,
    package_format varchar(16) NOT NULL DEFAULT 'CLAWHUB_SKILL',
    source_sha256 char(64) NOT NULL,
    size_bytes bigint NOT NULL,
    object_key varchar(512) NOT NULL UNIQUE,
    published_by uuid,
    published_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_native_clawhub_release_format CHECK (package_format = 'CLAWHUB_SKILL'),
    CONSTRAINT ck_native_clawhub_release_sha256 CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_native_clawhub_release_size CHECK (size_bytes > 0 AND size_bytes <= 52428800),
    CONSTRAINT ck_native_clawhub_release_version CHECK (
        version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)'
        '(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$')
);

CREATE INDEX ix_native_clawhub_release_catalog
    ON gateway.native_clawhub_release (published_at DESC);

COMMENT ON TABLE gateway.native_clawhub_source IS
    'Isolated, byte-preserved ClawHub Skill imports awaiting an explicit review and publish action.';
COMMENT ON TABLE gateway.native_clawhub_release IS
    'Published native ClawHub ZIP pointers; deliberately separate from YCPS skill_version.';
COMMENT ON COLUMN gateway.skill.source_format IS
    'Admin-console package source route; native ClawHub Skills must use the native publish and download APIs.';

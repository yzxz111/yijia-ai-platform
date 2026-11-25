-- Keep one immutable source row per uploaded ClawHub archive while retaining one current
-- release pointer per Skill. Existing V84 rows remain the first version and current release.
ALTER TABLE gateway.native_clawhub_source
    DROP CONSTRAINT IF EXISTS native_clawhub_source_skill_id_key;

DROP INDEX IF EXISTS gateway.ux_native_clawhub_source_identity;

CREATE INDEX ix_native_clawhub_source_identity
    ON gateway.native_clawhub_source (owner, slug, created_at DESC);

ALTER TABLE gateway.native_clawhub_source
    ADD COLUMN published_at timestamptz;

UPDATE gateway.native_clawhub_source source
   SET published_at = release.published_at
  FROM gateway.native_clawhub_release release
 WHERE release.source_id = source.id
   AND source.published_at IS NULL;

ALTER TABLE gateway.native_clawhub_source
    DROP CONSTRAINT IF EXISTS ck_native_clawhub_source_status;

ALTER TABLE gateway.native_clawhub_source
    ADD CONSTRAINT ck_native_clawhub_source_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'DELETED'));

CREATE UNIQUE INDEX ux_native_clawhub_version_semver
    ON gateway.native_clawhub_source (skill_id, source_version)
    WHERE source_version IS NOT NULL AND status <> 'DELETED';

CREATE UNIQUE INDEX ux_native_clawhub_version_sha256
    ON gateway.native_clawhub_source (skill_id, source_sha256)
    WHERE status <> 'DELETED';

CREATE INDEX ix_native_clawhub_versions
    ON gateway.native_clawhub_source (skill_id, created_at DESC, id DESC)
    WHERE status <> 'DELETED';

COMMENT ON COLUMN gateway.native_clawhub_source.published_at IS
    'First time this uploaded archive was published; retained to protect rollback history.';
COMMENT ON TABLE gateway.native_clawhub_release IS
    'Current native ClawHub release pointer per Skill. Uploaded archive history lives in native_clawhub_source.';

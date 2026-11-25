-- YCPS 1.0.0 remains frozen. 1.1.0 is an additive package profile used only for
-- EXPERT + expert-presentation-v1 packages; legacy packages retain their original semantics.
ALTER TABLE gateway.skill_version
    DROP CONSTRAINT ck_skill_version_ycps_version,
    ADD CONSTRAINT ck_skill_version_ycps_version
        CHECK (ycps_version IS NULL OR ycps_version IN ('1.0.0', '1.1.0')),
    DROP CONSTRAINT ck_skill_version_published_ycps;

ALTER TABLE gateway.skill_version
    ADD CONSTRAINT ck_skill_version_published_ycps
        CHECK (status <> 'PUBLISHED' OR (
            package_format = 'YCPS'
            AND ycps_version IN ('1.0.0', '1.1.0')
            AND package_kind IS NOT NULL
            AND plugin_manifest IS NOT NULL
            AND runtime_manifest IS NOT NULL
            AND manifest_sha256 IS NOT NULL
            AND dependency_lock_sha256 IS NOT NULL
            AND sealed));

ALTER TABLE gateway.skill_download
    DROP CONSTRAINT ck_skill_download_ycps_version,
    ADD CONSTRAINT ck_skill_download_ycps_version
        CHECK (ycps_version IS NULL OR ycps_version IN ('1.0.0', '1.1.0'));

COMMENT ON CONSTRAINT ck_skill_version_ycps_version ON gateway.skill_version IS
    'YCPS 1.0.0 is frozen; YCPS 1.1.0 is the additive Expert presentation profile.';

-- YCPS packages travel over HTTPS and are verified through the download session: artifact
-- SHA-256, MD5, manifest hash, dependency lock and per-entry CRC-32. The gateway does not sign
-- packages and desktop releases ship no package trust anchor, so the detached-signature columns
-- and the publish-time signature requirement are removed.

ALTER TABLE gateway.skill_version
    DROP CONSTRAINT ck_skill_version_published_ycps;

ALTER TABLE gateway.skill_version
    ADD CONSTRAINT ck_skill_version_published_ycps
        CHECK (status <> 'PUBLISHED' OR (
            package_format = 'YCPS'
            AND ycps_version = '1.0.0'
            AND package_kind IS NOT NULL
            AND plugin_manifest IS NOT NULL
            AND runtime_manifest IS NOT NULL
            AND manifest_sha256 IS NOT NULL
            AND dependency_lock_sha256 IS NOT NULL
            AND sealed));

ALTER TABLE gateway.skill_version
    DROP COLUMN signature_version,
    DROP COLUMN signature_algorithm,
    DROP COLUMN signature_key_id,
    DROP COLUMN signature_envelope,
    DROP COLUMN signature;

ALTER TABLE gateway.skill_download
    DROP COLUMN signature_envelope,
    DROP COLUMN signature;

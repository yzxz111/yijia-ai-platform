-- The strict SemVer contract describes YCPS artifacts, not the retired Legacy rows that still
-- carry historical labels such as "1.1". V57 added the strict rule as NOT VALID, so existing
-- Legacy rows were tolerated, but every later UPDATE re-validated the row and failed: archiving
-- or purging a retired Legacy version returned a check-constraint violation (HTTP 500) and the
-- row could never be cleaned up. Keep the original Legacy label rule for rows without a package
-- format and apply the strict SemVer rule only where a YCPS artifact is attached.

ALTER TABLE gateway.skill_version DROP CONSTRAINT ck_skill_version_semver;

ALTER TABLE gateway.skill_version
    ADD CONSTRAINT ck_skill_version_semver CHECK (
        (package_format = 'YCPS' AND version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$')
        OR (package_format IS DISTINCT FROM 'YCPS' AND version ~ '^[0-9]+(\.[0-9]+){0,3}(-[0-9A-Za-z.-]+)?$')
    );

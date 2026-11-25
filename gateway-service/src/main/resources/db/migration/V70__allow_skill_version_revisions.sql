-- A sealed YCPS artifact is immutable, but a portable presentation rebuild may create a
-- successor revision without changing the semantic version.  The revision is part of the
-- immutable identity; the old (skill_id, version) index prevented this contract.
DROP INDEX IF EXISTS gateway.ux_skill_version;

CREATE UNIQUE INDEX ux_skill_version_revision
    ON gateway.skill_version (skill_id, version, revision)
    WHERE deleted_at IS NULL;

COMMENT ON INDEX gateway.ux_skill_version_revision IS
    'One live row per skill semantic version and immutable revision; revisions preserve old artifacts.';

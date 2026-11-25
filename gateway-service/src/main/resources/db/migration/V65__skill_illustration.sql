-- The per-member avatar is retired; the package gains one optional introduction illustration.
--
-- Until now `gateway.skill_member.avatar_object_key` held a portrait per roster member. That was
-- the wrong carrier: a single expert has no roster at all, so a package-level picture had nowhere
-- to live, and the roster is really a team's list of *other experts* — whose portrait is already
-- the expert package's own icon (`gateway.skill.icon_object_key`, streamed by `/icon`).
--
-- So the portrait is dropped and the column's slot is re-homed as a package-level introduction
-- illustration. It mirrors the icon pointer exactly: the row keeps only the object key (the bytes
-- live in the object store), NULL means "nothing uploaded", and `illustration_updated_at` is the
-- cache validator a client uses to decide whether a locally cached copy is still current.
--
-- The member rows themselves are untouched: `member_key` stays the identity anchor and now also
-- names the expert package whose icon supplies that member's portrait.
--
-- No illustration is seeded: every package starts without one, and clients fall back to the
-- artwork they ship with.
--
-- The dropped column carried no data in any environment (verified before this migration was
-- written: 6 member rows, 0 with a non-null avatar), so nothing needs to be carried over.

ALTER TABLE gateway.skill
    ADD COLUMN illustration_object_key varchar(512),
    ADD COLUMN illustration_updated_at timestamptz;

COMMENT ON COLUMN gateway.skill.illustration_object_key IS
    'Object-store key of the package introduction illustration; NULL means none uploaded.';
COMMENT ON COLUMN gateway.skill.illustration_updated_at IS
    'When the illustration last changed; clients use it as the cache validator.';

ALTER TABLE gateway.skill_member
    DROP COLUMN avatar_object_key;

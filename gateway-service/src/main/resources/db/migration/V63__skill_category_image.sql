-- Categories gain an optional cover image, managed from the console.
--
-- The expert/team directory on the desktop renders the featured-scene cards from this
-- vocabulary, and each card's cover is the category's own image. The console uploads it, the
-- gateway stores the object key here and streams the bytes, and the desktop caches them locally.
--
-- The pointer column mirrors `gateway.skill.icon_object_key`: the row keeps only the object key
-- (the bytes live in the object store), and a NULL means "no cover uploaded yet" so the desktop
-- can fall back to the image it ships with. `image_updated_at` is the cache validator the desktop
-- uses to decide whether a locally cached cover is still current.
--
-- No cover is seeded: every category starts without one, and the desktop falls back to its
-- built-in image for the codes it knows.

ALTER TABLE gateway.skill_category
    ADD COLUMN image_object_key varchar(512),
    ADD COLUMN image_updated_at timestamptz;

COMMENT ON COLUMN gateway.skill_category.image_object_key IS
    'Object-store key of the category cover; NULL means no uploaded cover.';

COMMENT ON COLUMN gateway.skill_category.image_updated_at IS
    'When the cover last changed; the desktop uses it as the cache validator.';

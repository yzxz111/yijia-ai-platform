-- One optional example image per package. This is separate from the introduction illustration:
-- the illustration introduces the expert, while the example image demonstrates its output/features.
ALTER TABLE gateway.skill
    ADD COLUMN example_image_object_key varchar(512),
    ADD COLUMN example_image_updated_at timestamptz;

COMMENT ON COLUMN gateway.skill.example_image_object_key IS
    'Object-store key of the package example image; NULL means none uploaded.';
COMMENT ON COLUMN gateway.skill.example_image_updated_at IS
    'When the example image last changed; clients use it as the cache validator.';

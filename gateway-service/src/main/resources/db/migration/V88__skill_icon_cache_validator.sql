-- Give the package portrait its own cache validator. Previously desktop clients used the skill's
-- general updated_at value, so changing copy, tags, or category unnecessarily invalidated and
-- re-downloaded an unchanged portrait.
ALTER TABLE gateway.skill
    ADD COLUMN icon_updated_at timestamptz;

UPDATE gateway.skill
   SET icon_updated_at = updated_at
 WHERE icon_object_key IS NOT NULL;

COMMENT ON COLUMN gateway.skill.icon_updated_at IS
    'When the package portrait last changed; clients use it as the cache validator.';

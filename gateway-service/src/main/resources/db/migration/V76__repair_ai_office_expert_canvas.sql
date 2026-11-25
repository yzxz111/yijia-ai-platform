-- Repair the one malformed canvas written for the canonical AI Office Expert after V75.
--
-- The authoritative presentation is already complete in preview_zh/preview_en and in the
-- three parent-skill asset pointers.  The malformed canvas is only a presentation overlay: it
-- has the old 25-node shape, accidentally includes the example-image asset, omits the output
-- title and gives the heading's second run the cyan color #80ffff.  Clearing that overlay lets
-- clients use the canonical structured preview (including outputTitle and all eight output
-- paragraphs) and their compatibility renderer without losing any asset.
--
-- Keep this repair deliberately narrow.  It is safe to re-run manually: after the first update
-- canvas_zh is NULL and the signature no longer matches.  Flyway also executes the statement in
-- one transaction.  The parent row_version advances exactly once only when the profile row was
-- actually repaired.
WITH repaired AS (
    UPDATE gateway.skill_expert_profile AS profile
    SET canvas_zh = NULL,
        canvas_en = NULL,
        updated_at = now()
    FROM gateway.skill AS skill
    WHERE profile.skill_id = skill.id
      AND skill.code = 'ai-office-assistant'
      AND skill.kind = 'EXPERT'
      AND skill.deleted_at IS NULL
      AND profile.canvas_zh IS NOT NULL
      AND profile.canvas_en IS NULL
      AND profile.canvas_zh ->> 'version' = '1'
      AND profile.canvas_zh ->> 'width' = '1080'
      AND profile.canvas_zh ->> 'height' = '704'
      AND jsonb_array_length(
              CASE WHEN jsonb_typeof(profile.canvas_zh -> 'nodes') = 'array'
                   THEN profile.canvas_zh -> 'nodes' ELSE '[]'::jsonb END) = 25
      AND EXISTS (
          SELECT 1
          FROM jsonb_array_elements(
                   CASE WHEN jsonb_typeof(profile.canvas_zh -> 'nodes') = 'array'
                        THEN profile.canvas_zh -> 'nodes' ELSE '[]'::jsonb END) AS node
          WHERE node ->> 'field' = 'asset.exampleImage'
            AND node ->> 'kind' = 'image'
            AND node ->> 'assetRole' = 'exampleImage'
      )
      AND NOT EXISTS (
          SELECT 1
          FROM jsonb_array_elements(
                   CASE WHEN jsonb_typeof(profile.canvas_zh -> 'nodes') = 'array'
                        THEN profile.canvas_zh -> 'nodes' ELSE '[]'::jsonb END) AS node
          WHERE node ->> 'field' = 'preview.outputTitle'
      )
      AND EXISTS (
          SELECT 1
          FROM jsonb_array_elements(
                   CASE WHEN jsonb_typeof(profile.canvas_zh -> 'nodes') = 'array'
                        THEN profile.canvas_zh -> 'nodes' ELSE '[]'::jsonb END) AS node
          CROSS JOIN LATERAL jsonb_array_elements(
                   CASE WHEN jsonb_typeof(node -> 'runs') = 'array'
                        THEN node -> 'runs' ELSE '[]'::jsonb END) AS run
          WHERE node ->> 'field' = 'preview.heading'
            AND run ->> 'color' = '#80ffff'
      )
    RETURNING profile.skill_id
)
UPDATE gateway.skill AS skill
SET row_version = skill.row_version + 1,
    updated_at = now()
FROM repaired
WHERE skill.id = repaired.skill_id;

-- A stale console tab saved the legacy AI Office overlay again after V76 had
-- removed it.  Its authoritative preview is a semantic pipe-table and must be
-- rendered from preview_zh, never flattened into canvas text nodes.
--
-- The gateway now strips semantic canvases on every save.  This one-time,
-- idempotent cleanup removes the already reintroduced overlay while preserving
-- the preview and all parent-skill asset pointers.
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
      AND (profile.canvas_zh IS NOT NULL OR profile.canvas_en IS NOT NULL)
      AND (
          profile.preview_zh ? 'intro'
          OR profile.preview_zh ? 'table'
          OR profile.preview_zh ? 'sections'
          OR profile.preview_zh ? 'blocks'
          OR 2 <= (
              SELECT count(*)
              FROM jsonb_array_elements_text(
                  CASE
                      WHEN jsonb_typeof(profile.preview_zh -> 'outputParagraphs') = 'array'
                          THEN profile.preview_zh -> 'outputParagraphs'
                      ELSE '[]'::jsonb
                  END
              ) AS paragraph(value)
              WHERE length(value) - length(replace(value, '｜', '')) >= 2
                 OR length(value) - length(replace(value, '|', '')) >= 2
          )
      )
    RETURNING profile.skill_id
)
UPDATE gateway.skill AS skill
SET row_version = skill.row_version + 1,
    updated_at = now()
FROM repaired
WHERE skill.id = repaired.skill_id;

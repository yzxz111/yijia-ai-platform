-- Optional, presentation-only WYSIWYG canvas for YCPS 1.1 Expert profiles.
-- The two locale documents stay separate in storage; API/portable DTOs expose a
-- locale-keyed {"zh":{...},"en":{...}} object. The parent skill row_version
-- remains the sole aggregate CAS token.
ALTER TABLE gateway.skill_expert_profile
    ADD COLUMN canvas_zh jsonb,
    ADD COLUMN canvas_en jsonb;

ALTER TABLE gateway.skill_expert_profile
    ADD CONSTRAINT ck_skill_expert_profile_canvas_object
        CHECK ((canvas_zh IS NULL OR jsonb_typeof(canvas_zh) = 'object')
            AND (canvas_en IS NULL OR jsonb_typeof(canvas_en) = 'object'));

COMMENT ON COLUMN gateway.skill_expert_profile.canvas_zh IS
    'Optional normalized zh Expert WYSIWYG canvas JSON; not runtime routing data.';
COMMENT ON COLUMN gateway.skill_expert_profile.canvas_en IS
    'Optional normalized en Expert WYSIWYG canvas JSON; not runtime routing data.';

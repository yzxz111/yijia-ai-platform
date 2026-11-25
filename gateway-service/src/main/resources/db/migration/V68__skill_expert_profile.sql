-- Structured expert presentation owned by the gateway catalog.
--
-- YCPS 1.0 remains unchanged.  The profile is a cloud-side snapshot used by the WYSIWYG
-- editor and is deliberately separate from gateway.skill's operational columns.  A 1.1 portable
-- package can carry the same values, but importing a package must not mutate this row implicitly.
CREATE TABLE gateway.skill_expert_profile (
    skill_id uuid PRIMARY KEY REFERENCES gateway.skill (id) ON DELETE CASCADE,
    task_types text[] NOT NULL DEFAULT '{}',
    task_types_en text[] NOT NULL DEFAULT '{}',
    usage_guide text,
    usage_guide_en text,
    example_prompts jsonb NOT NULL DEFAULT '[]'::jsonb,
    example_prompts_en jsonb NOT NULL DEFAULT '[]'::jsonb,
    preview_zh jsonb,
    preview_en jsonb,
    portable_asset_manifest jsonb NOT NULL DEFAULT '{}'::jsonb,
    portable_revision integer NOT NULL DEFAULT 0,
    created_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_skill_expert_profile_task_types_array
        CHECK (array_ndims(task_types) IS NULL OR array_ndims(task_types) = 1),
    CONSTRAINT ck_skill_expert_profile_task_types_en_array
        CHECK (array_ndims(task_types_en) IS NULL OR array_ndims(task_types_en) = 1),
    CONSTRAINT ck_skill_expert_profile_task_types_limit
        CHECK (cardinality(task_types) <= 6),
    CONSTRAINT ck_skill_expert_profile_task_types_en_limit
        CHECK (cardinality(task_types_en) <= 6),
    CONSTRAINT ck_skill_expert_profile_prompts_array
        CHECK (jsonb_typeof(example_prompts) = 'array'),
    CONSTRAINT ck_skill_expert_profile_prompts_en_array
        CHECK (jsonb_typeof(example_prompts_en) = 'array'),
    CONSTRAINT ck_skill_expert_profile_preview_zh_object
        CHECK (preview_zh IS NULL OR jsonb_typeof(preview_zh) = 'object'),
    CONSTRAINT ck_skill_expert_profile_preview_en_object
        CHECK (preview_en IS NULL OR jsonb_typeof(preview_en) = 'object'),
    CONSTRAINT ck_skill_expert_profile_assets_object
        CHECK (jsonb_typeof(portable_asset_manifest) = 'object'),
    CONSTRAINT ck_skill_expert_profile_portable_revision
        CHECK (portable_revision >= 0),
    CONSTRAINT ck_skill_expert_profile_skill_fk
        CHECK (skill_id IS NOT NULL)
);

COMMENT ON TABLE gateway.skill_expert_profile IS
    'Structured WYSIWYG presentation for EXPERT skills; runtime routing and response contracts stay elsewhere.';
COMMENT ON COLUMN gateway.skill_expert_profile.task_types IS
    'Business-facing task type labels. This is distinct from gateway.skill.tags, which remain operational tags.';
COMMENT ON COLUMN gateway.skill_expert_profile.preview_zh IS
    'Canonical ExpertPreviewContent object for the default language.';
COMMENT ON COLUMN gateway.skill_expert_profile.preview_en IS
    'Canonical ExpertPreviewContent object for the optional English overlay.';
COMMENT ON COLUMN gateway.skill_expert_profile.portable_asset_manifest IS
    'Role/path/digest metadata for the latest portable presentation assets; bytes remain in object storage.';

CREATE INDEX ix_skill_expert_profile_updated
    ON gateway.skill_expert_profile (updated_at DESC);

-- Materialize the first-read compatibility fallback for existing Experts.  This is deliberately
-- one-way: operational gateway.skill.tags seed the new business-facing column once, while future
-- profile edits never write tags back.
INSERT INTO gateway.skill_expert_profile (skill_id, task_types, task_types_en)
SELECT id, COALESCE(tags[1:6], '{}'::text[]), '{}'::text[]
FROM gateway.skill
WHERE kind = 'EXPERT'
  AND deleted_at IS NULL
ON CONFLICT (skill_id) DO NOTHING;

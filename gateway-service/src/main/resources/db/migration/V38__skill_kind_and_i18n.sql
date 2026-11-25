-- Skill expert-isation: presentation kind (D1), the English overlay columns (D2) and the read-only
-- package manifest (D4).
-- Design: docs/superpowers/specs/2026-09-14-skill-expert-kind-and-members-design.md (2.1)

ALTER TABLE gateway.skill
    ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'SKILL',
    ADD COLUMN name_en varchar(128),
    ADD COLUMN summary_en varchar(512),
    ADD COLUMN description_en text,
    ADD COLUMN manifest jsonb;

ALTER TABLE gateway.skill
    ADD CONSTRAINT ck_skill_kind CHECK (kind IN ('SKILL', 'EXPERT', 'TEAM'));

-- Existing rows became SKILL through the column default, so no data migration is required.
COMMENT ON COLUMN gateway.skill.kind IS
    'Presentation type: SKILL / EXPERT / TEAM. Set on create only (E-01), never edited afterwards.';
COMMENT ON COLUMN gateway.skill.name_en IS 'English display name; falls back to name when empty (C1/C2).';
COMMENT ON COLUMN gateway.skill.summary_en IS 'English summary; falls back to summary when empty.';
COMMENT ON COLUMN gateway.skill.description_en IS 'English description; falls back to description when empty.';
COMMENT ON COLUMN gateway.skill.manifest IS
    'Verbatim plugin.json captured from the uploaded package; read-only for admins (D4).';

CREATE INDEX ix_skill_kind_status ON gateway.skill (kind, status) WHERE deleted_at IS NULL;


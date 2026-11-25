-- Skill expert roster (D3): the database is the single source of truth, the package manifest only
-- seeds it once, while the skill has no members yet.
-- Design: docs/superpowers/specs/2026-09-14-skill-expert-kind-and-members-design.md (2.2)

CREATE TABLE gateway.skill_member (
    id uuid PRIMARY KEY,
    skill_id uuid NOT NULL REFERENCES gateway.skill (id) ON DELETE CASCADE,
    member_key varchar(64) NOT NULL,
    name varchar(64),
    name_en varchar(64),
    profession varchar(64),
    profession_en varchar(64),
    avatar_object_key varchar(512),
    member_role varchar(16) NOT NULL DEFAULT 'MEMBER',
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_skill_member_role CHECK (member_role IN ('LEAD', 'MEMBER'))
);

-- Same shape as gateway.skill.code: the key is also the last path segment of the avatar object key.
ALTER TABLE gateway.skill_member
    ADD CONSTRAINT ck_skill_member_key CHECK (member_key ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$');

ALTER TABLE gateway.skill_member
    ADD CONSTRAINT ck_skill_member_sort_order CHECK (sort_order >= 0);

-- E-05: a roster never carries the same member key twice (whole-roster replacement semantics).
CREATE UNIQUE INDEX ux_skill_member_key ON gateway.skill_member (skill_id, member_key);

CREATE INDEX ix_skill_member_order ON gateway.skill_member (skill_id, sort_order);

-- E-04 is enforced in the service layer (422 invalid_skill_member), the index keeps the roster
-- lookup for at most one LEAD cheap.
CREATE INDEX ix_skill_member_lead ON gateway.skill_member (skill_id) WHERE member_role = 'LEAD';

COMMENT ON TABLE gateway.skill_member IS
    'Expert/team roster. Rows survive a logical skill delete (E-07): no cascade cleanup is performed.';
COMMENT ON COLUMN gateway.skill_member.member_key IS
    'Stable per-skill identifier, regex ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ (same style as skill.code).';
COMMENT ON COLUMN gateway.skill_member.member_role IS
    'LEAD or MEMBER; at most one LEAD per skill, validated in the service layer (E-04).';
COMMENT ON COLUMN gateway.skill_member.avatar_object_key IS
    'Object store key clean/skills/{code}/members/{memberKey}.{ext}; NULL when no avatar is set.';


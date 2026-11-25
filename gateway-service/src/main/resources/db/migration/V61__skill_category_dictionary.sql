-- Skill / expert categories become an administrator-managed dictionary.
--
-- Design: the console renders experts as two-level category cards and needs a curated,
-- editable category list. `gateway.skill.category` stays a free-text column (the package
-- carries its category as plain text, so an upload never has to resolve a foreign key),
-- and this table is the vocabulary the console offers and validates against.
--
-- `kind` scopes the vocabulary: SKILL covers reusable capabilities, EXPERT covers experts
-- and expert teams. The two lists are deliberately separate, so renaming a skill category can
-- never silently re-group an expert.
--
-- Seeded with the reviewed lists: seven capability categories for skills and the fifteen
-- business categories for experts/teams.

CREATE TABLE gateway.skill_category (
    id uuid PRIMARY KEY,
    kind varchar(16) NOT NULL,
    code varchar(64) NOT NULL,
    name varchar(64) NOT NULL,
    name_en varchar(64),
    sort_order integer NOT NULL DEFAULT 0,
    enabled boolean NOT NULL DEFAULT true,
    row_version integer NOT NULL DEFAULT 1,
    created_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid,
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_by uuid,
    deleted_at timestamptz,
    CONSTRAINT ck_skill_category_kind CHECK (kind IN ('SKILL', 'EXPERT')),
    CONSTRAINT ck_skill_category_code CHECK (code ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_skill_category_sort_order CHECK (sort_order >= 0),
    CONSTRAINT ck_skill_category_row_version CHECK (row_version >= 1)
);

-- One live row per (kind, code) and per (kind, name): the console offers the name as the
-- value it writes into gateway.skill.category, so a duplicate name would make the grouping
-- ambiguous. Both indexes ignore tombstones, so a deleted category can be re-added.
CREATE UNIQUE INDEX ux_skill_category_kind_code ON gateway.skill_category (kind, code)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX ux_skill_category_kind_name ON gateway.skill_category (kind, name)
    WHERE deleted_at IS NULL;

-- The console lists one kind's vocabulary in display order.
CREATE INDEX ix_skill_category_kind_order ON gateway.skill_category (kind, sort_order)
    WHERE deleted_at IS NULL;

-- Seven capability categories for skills.
INSERT INTO gateway.skill_category (id, kind, code, name, name_en, sort_order) VALUES
    (gen_random_uuid(), 'SKILL', 'documents-data', '文档与数据', 'Documents & Data', 1),
    (gen_random_uuid(), 'SKILL', 'research-retrieval', '研究与检索', 'Research & Retrieval', 2),
    (gen_random_uuid(), 'SKILL', 'content-marketing', '内容与营销', 'Content & Marketing', 3),
    (gen_random_uuid(), 'SKILL', 'browser-automation', '浏览器与自动化', 'Browser & Automation', 4),
    (gen_random_uuid(), 'SKILL', 'local-system-tools', '本地系统与工具', 'Local System & Tools', 5),
    (gen_random_uuid(), 'SKILL', 'dev-engineering', '开发与工程', 'Development & Engineering', 6),
    (gen_random_uuid(), 'SKILL', 'other', '其他能力', 'Other Capabilities', 7);

-- Fifteen business categories for experts and expert teams.
INSERT INTO gateway.skill_category (id, kind, code, name, name_en, sort_order) VALUES
    (gen_random_uuid(), 'EXPERT', 'tencent-expert', '腾讯专家', 'Tencent Expert', 1),
    (gen_random_uuid(), 'EXPERT', 'product-design', '产品设计', 'Product Design', 2),
    (gen_random_uuid(), 'EXPERT', 'engineering', '技术工程', 'Engineering', 3),
    (gen_random_uuid(), 'EXPERT', 'investment', '金融投资', 'Investment', 4),
    (gen_random_uuid(), 'EXPERT', 'global-growth', '全球发展', 'Global Growth', 5),
    (gen_random_uuid(), 'EXPERT', 'learning', '学习教育', 'Learning', 6),
    (gen_random_uuid(), 'EXPERT', 'gaming', '游戏空间', 'Gaming', 7),
    (gen_random_uuid(), 'EXPERT', 'data-intelligence', '数据智能', 'Data Intelligence', 8),
    (gen_random_uuid(), 'EXPERT', 'marketing-growth', '营销增长', 'Marketing Growth', 9),
    (gen_random_uuid(), 'EXPERT', 'content-creation', '内容创作', 'Content Creation', 10),
    (gen_random_uuid(), 'EXPERT', 'sales-business', '销售商务', 'Sales & Business', 11),
    (gen_random_uuid(), 'EXPERT', 'operations-hr', '运营人力', 'Operations & HR', 12),
    (gen_random_uuid(), 'EXPERT', 'project-quality', '项目质量', 'Project Quality', 13),
    (gen_random_uuid(), 'EXPERT', 'legal-security', '法务安全', 'Legal & Security', 14),
    (gen_random_uuid(), 'EXPERT', 'industry-advisory', '行业顾问', 'Industry Advisory', 15);

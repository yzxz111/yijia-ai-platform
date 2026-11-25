-- Re-categorise the experts and expert teams into the reviewed two-level taxonomy.
-- Design: the console renders experts as two-level category cards, where level 1 is the
-- YCPS kind (EXPERT / TEAM) and level 2 is the business category defined by
-- web-console/src/views/admin/expertCategories.ts (15 values).
--
-- The rows below were seeded with the three legacy YCPS buckets (content / life / work),
-- which are too coarse to group a directory. This migration assigns each seeded package a
-- business category by code, so the console groups and the desktop catalogue agree.
--
-- Idempotent on purpose: every statement only touches rows that still carry a legacy bucket,
-- so re-running it (or running it after an administrator has curated a category by hand)
-- is a no-op. `category` is varchar(64) free text by design - the taxonomy stays in the
-- console so the vocabulary can evolve without a migration, and unknown values fall back to
-- the raw text instead of disappearing.

-- 内容创作：选题与成稿（内容生产）
UPDATE gateway.skill SET category = '内容创作', row_version = row_version + 1
WHERE code = 'content-writing-expert' AND category IN ('content', 'life', 'work');

-- 内容总结：长文压缩与结论提炼，同属内容生产
UPDATE gateway.skill SET category = '内容创作', row_version = row_version + 1
WHERE code = 'content-summarizer' AND category IN ('content', 'life', 'work');

-- 社媒运营：广告投放、平台文案、短视频脚本（增长与投放）
UPDATE gateway.skill SET category = '营销增长', row_version = row_version + 1
WHERE code = 'social-media-operations-expert' AND category IN ('content', 'life', 'work');

-- 备课出卷：K-12 教案、试卷与教辅
UPDATE gateway.skill SET category = '学习教育', row_version = row_version + 1
WHERE code = 'lesson-planner' AND category IN ('content', 'life', 'work');

-- 销售分析：按商品/区域/周期的经营分析与报表
UPDATE gateway.skill SET category = '销售商务', row_version = row_version + 1
WHERE code = 'sales-analysis-expert' AND category IN ('content', 'life', 'work');

-- 文档审校：条款、数字与结构的核对，带可追溯的修订副本（合规审校）
UPDATE gateway.skill SET category = '法务安全', row_version = row_version + 1
WHERE code = 'document-review-expert' AND category IN ('content', 'life', 'work');

-- AI 办公助手：Word/PDF/Excel/PPT 的读写与批量事务处理
UPDATE gateway.skill SET category = '运营人力', row_version = row_version + 1
WHERE code = 'ai-office-assistant' AND category IN ('content', 'life', 'work');

-- 医疗健康解读：指标释义与就医建议（面向个人的专业解读）
UPDATE gateway.skill SET category = '行业顾问', row_version = row_version + 1
WHERE code = 'health-interpreter' AND category IN ('content', 'life', 'work');

-- 萌宠管家：宠物养护、行为与免疫计划咨询（面向个人的专业解读）
UPDATE gateway.skill SET category = '行业顾问', row_version = row_version + 1
WHERE code = 'pet-care' AND category IN ('content', 'life', 'work');

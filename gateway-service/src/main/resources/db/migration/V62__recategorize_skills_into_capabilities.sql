-- Re-categorise the seeded skills into the seven capability categories.
--
-- V61 introduced the administrator-managed dictionary and seeded both vocabularies.
-- The skill rows still carry the earlier ad-hoc Chinese labels (内容创作 / 数据分析 / 电商运营),
-- which are not part of the new seven, so the console would group them under "未分类".
--
-- Idempotent: each statement only touches a row that still carries the old label, so running
-- this again (or after an administrator has curated the category by hand) is a no-op.

-- 内容生成助手：为公众号/小红书/知乎产出成稿（内容生产）
UPDATE gateway.skill SET category = '内容与营销', row_version = row_version + 1
WHERE code = 'content-writer' AND category IN ('内容创作', 'content');

-- 数据分析助手：清洗 CSV/Excel、核对指标口径（文档与数据）
UPDATE gateway.skill SET category = '文档与数据', row_version = row_version + 1
WHERE code = 'data-analyst' AND category IN ('数据分析', 'data');

-- 电商运营助手：商品与店铺运营（内容与营销）
UPDATE gateway.skill SET category = '内容与营销', row_version = row_version + 1
WHERE code = 'ecommerce-ops' AND category IN ('电商运营', 'ecommerce');

-- 示例 Skill：能力演示（其他能力）
UPDATE gateway.skill SET category = '其他能力', row_version = row_version + 1
WHERE code = 'example-skill' AND (category IS NULL OR category = '');

-- xlsx：电子表格读写（文档与数据）
UPDATE gateway.skill SET category = '文档与数据', row_version = row_version + 1
WHERE code = 'xlsx' AND (category IS NULL OR category = '');

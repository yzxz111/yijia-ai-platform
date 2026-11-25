-- Rename the EXPERT category "运营人力" to "办公提效".
--
-- The seeded vocabulary named this bucket 运营人力 (operations-hr), which reads as a narrow
-- HR/back-office label while the bucket actually collects the general office-efficiency
-- experts (AI 办公助手 / 文档审校 / 内容总结). The console therefore showed a card whose
-- title did not describe its own members. The curated scenario is 办公提效, so the name moves.
--
-- The rename covers both halves of the vocabulary:
--   1. the dictionary row itself (code, name, name_en), and
--   2. every live package still carrying the old name in `gateway.skill.category`.
--
-- Both halves matter. `gateway.skill.category` is free text on purpose (a YCPS package ships
-- its category as plain text), so renaming only the dictionary would leave the packages on a
-- label the console no longer offers and they would fall out of every group. The `code` moves
-- with the name because it is the stable identifier the desktop keys its built-in scene cover
-- and its hand-tuned expert order on; a stale code would leave that card on its generic
-- keyword fallback instead of the shipped cover.
--
-- Note: the desktop still ships `operations-hr` in its own built-in scene list and cover map
-- (`expertMarketplace.ts`). It keeps working because `office-productivity` matches the
-- 'office' keyword fallback group, but the desktop's own copy should be renamed in step.
--
-- Idempotent: each statement only touches rows that still carry the old value, so re-running
-- this migration (or running it after an administrator has curated the row by hand) is a no-op.

UPDATE gateway.skill_category
SET code = 'office-productivity',
    name = '办公提效',
    name_en = 'Office Productivity',
    row_version = row_version + 1,
    updated_at = now()
WHERE kind = 'EXPERT'
  AND code = 'operations-hr'
  AND deleted_at IS NULL;

-- Re-point the packages. Scoped to the expert-side kinds (EXPERT / TEAM), matching
-- SkillCategoryRepository.kinds(), so a skill that happens to carry the same label can never
-- be re-grouped by an expert rename.
UPDATE gateway.skill
SET category = '办公提效',
    row_version = row_version + 1
WHERE deleted_at IS NULL
  AND category IS NOT NULL
  AND lower(category) = lower('运营人力')
  AND kind = ANY (ARRAY['EXPERT', 'TEAM']);

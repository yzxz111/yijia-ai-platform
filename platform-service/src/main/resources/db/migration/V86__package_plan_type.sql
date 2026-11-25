-- ---------------------------------------------------------------------------
-- V86：给套餐（package_plan）加上「类型」字段
-- ---------------------------------------------------------------------------
--
-- 目的：套餐目前只能靠 package_plan_version.usage_dimension 间接区分卖的是什么
-- （TOKEN / VIDEO_SECOND / IMAGE），而 usage_dimension 是**计费维度**，不是**商品
-- 品类**：它决定"这个数字怎么算钱"，不决定"这是文本套餐还是图片套餐"。控制台与
-- 商城要按品类分组/打标时，只能反查在售版本，既绕又容易在"还没有在售版本"的计划上
-- 落空（本库就有 5 个这样的计划，其中 1 个还是 ACTIVE）。
--
-- 因此把品类显式化到计划本身：
--   CHAT  —— 文本/对话类套餐（计费维度 TOKEN）
--   IMAGE —— 图片生成套餐（计费维度 IMAGE，单位张）
--   VIDEO —— 视频生成套餐（计费维度 VIDEO_SECOND，单位秒）
--
-- 取值风格与既有 varchar 枚举列保持一致（全大写：status 的 DRAFT/ACTIVE/ARCHIVED、
-- kind 的 STANDARD、usage_dimension 的 TOKEN/IMAGE/VIDEO_SECOND）。
--
-- 三条设计取舍，写在这里以免以后被"优化"掉：
--
-- 1) **放在计划层而不是版本层。** 本库实测没有任何计划混合过多种 usage_dimension
--    （见下方断言），所以品类对计划是唯一的；放在版本层会让"同一个套餐换了计费
--    维度就变成另一个品类"这种不该发生的事变得合法。将来若真的需要按版本区分，
--    那应当是一次显式的迁移，而不是靠一个可空列悄悄漂移。
--
-- 2) **保留 DEFAULT 'CHAT'。** 现有创建计划的代码路径不会提供这个字段，
--    若加 NOT NULL 而不给默认值，管理端"新建套餐"会立刻开始报错。默认值是为了
--    不破坏既有写入路径，**不代表新计划可以不自报品类**——创建图片/视频套餐时
--    必须显式写入，否则会以 CHAT 落库并让商城分错组。
--
-- 3) **回退顺序：在售版本 → 任意版本 → CHAT。** 前两级能覆盖本库全部计划；
--    最后一级只用于"一个版本都没有"的空壳计划（本库 5 个），它们没有可推导的
--    事实，给一个显式默认值比留空更诚实：留空会让每个读取点各自猜。
--
-- 本迁移**不做任何环境相关的假设**：类型全部由本库数据推导，断言也全部是
-- 由数据计算出来的（不含任何硬编码的计划编号或版本号）。

ALTER TABLE platform.package_plan
    ADD COLUMN IF NOT EXISTS type varchar(32);

COMMENT ON COLUMN platform.package_plan.type IS
    '商品品类：CHAT（文本/对话，计费维度 TOKEN）、IMAGE（图片生成，按张）、VIDEO（视频生成，按秒）。与在售版本的 usage_dimension 保持一致；空壳计划（无任何版本）默认 CHAT。';

-- 回退顺序：优先在售版本，其次任意版本，最后 CHAT。
UPDATE platform.package_plan plan_row
SET type = COALESCE(
        (SELECT CASE version.usage_dimension
                    WHEN 'IMAGE' THEN 'IMAGE'
                    WHEN 'VIDEO_SECOND' THEN 'VIDEO'
                    ELSE 'CHAT'
                END
         FROM platform.package_plan_version version
         WHERE version.package_plan_id = plan_row.id
           AND version.status = 'PUBLISHED'
         ORDER BY version.version DESC
         LIMIT 1),
        (SELECT CASE version.usage_dimension
                    WHEN 'IMAGE' THEN 'IMAGE'
                    WHEN 'VIDEO_SECOND' THEN 'VIDEO'
                    ELSE 'CHAT'
                END
         FROM platform.package_plan_version version
         WHERE version.package_plan_id = plan_row.id
         ORDER BY version.version DESC
         LIMIT 1),
        'CHAT')
WHERE plan_row.type IS NULL;

ALTER TABLE platform.package_plan
    ALTER COLUMN type SET DEFAULT 'CHAT';

ALTER TABLE platform.package_plan
    ALTER COLUMN type SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_package_plan_type'
          AND conrelid = 'platform.package_plan'::regclass
    ) THEN
        ALTER TABLE platform.package_plan
            ADD CONSTRAINT ck_package_plan_type
            CHECK (type IN ('CHAT', 'IMAGE', 'VIDEO'));
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 断言（全部由本库数据计算，无硬编码）
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    offender record;
    counted  integer;
BEGIN
    -- (1) 不允许有空类型
    SELECT count(*) INTO counted FROM platform.package_plan WHERE type IS NULL;
    IF counted > 0 THEN
        RAISE EXCEPTION 'plan_type_missing:count=%', counted;
    END IF;

    -- (2) 类型必须是合法取值（约束存在时由约束保证；这里再兜一次，
    --     因为 ADD CONSTRAINT 在列已存在同名约束时会被上面的 IF 跳过）
    SELECT plan_row.code, plan_row.type INTO offender
    FROM platform.package_plan plan_row
    WHERE plan_row.type NOT IN ('CHAT', 'IMAGE', 'VIDEO')
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'plan_type_unknown:%:type=%', offender.code, offender.type;
    END IF;

    -- (3) 品类必须与该计划在售版本的计费维度一致。这条是本次迁移真正要保证的
    --     性质：品类的唯一用途就是"按品类分组"，一旦与计费维度不一致，
    --     商城就会把按秒计费的套餐展示成文本套餐。
    SELECT plan_row.code, plan_row.type, version.usage_dimension, version.version
    INTO offender
    FROM platform.package_plan plan_row
    JOIN platform.package_plan_version version
      ON version.package_plan_id = plan_row.id
     AND version.status = 'PUBLISHED'
    WHERE (plan_row.type = 'CHAT' AND version.usage_dimension <> 'TOKEN')
       OR (plan_row.type = 'IMAGE' AND version.usage_dimension <> 'IMAGE')
       OR (plan_row.type = 'VIDEO' AND version.usage_dimension <> 'VIDEO_SECOND')
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'plan_type_disagrees_with_published_dimension:%:type=%:v%:dimension=%',
            offender.code, offender.type, offender.version, offender.usage_dimension;
    END IF;

    -- (4) 品类对计划必须是唯一的——用实测数据再次确认"没有计划混合维度"这个前提；
    --     一旦将来出现混合维度的计划，品类的语义就不再成立，应当在这里被挡住，
    --     而不是悄悄按某一个版本归类。
    SELECT plan_row.code, string_agg(DISTINCT version.usage_dimension, ',') AS dimensions
    INTO offender
    FROM platform.package_plan plan_row
    JOIN platform.package_plan_version version ON version.package_plan_id = plan_row.id
    GROUP BY plan_row.code
    HAVING count(DISTINCT version.usage_dimension) > 1
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'plan_mixes_usage_dimensions_so_a_single_type_is_not_well_defined:%:dimensions=%',
            offender.code, offender.dimensions;
    END IF;
END $$;

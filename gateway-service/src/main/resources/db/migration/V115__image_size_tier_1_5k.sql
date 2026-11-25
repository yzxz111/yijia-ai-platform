-- ---------------------------------------------------------------------------
-- 生图档位补上 1.5K：厂商文档里有、网关词表里没有的那一档。
--
-- 事实：Seedream 5.0 pro 的厂商文档口径是 1K / 1.5K / 2K（见
-- TianYiYunImageGenerationAdapter.DOCUMENTED_SIZE_TIERS），而网关自 V81 起
-- 只认 1K / 2K / 4K。差集只有一档，但它的后果不是"客户端少一个选项"：
--
--   * 客户端能从 GET /v1/image-models 的 supportedSizeTiers **读到** 1.5K，
--     提交时却以 422 invalid_image_request 被拒（ImageGenerationService.sizeTier
--     归约不出档位），而错误码看起来像"客户参数写错了"；
--   * 没有任何一行费率卡可以为这一档计成本——档位不在 ck_model_usage_price_tier
--     的词表里，价格根本登记不进来，于是"卖 1.5K"连毛利都算不出来。
--
-- 本迁移把 1.5K 变成一等档位，与 V81 建立的三处词表逐一对齐：
--   1. gateway.image_alias_tier     别名登记的在售档位；
--   2. gateway.model_usage_price    按档位定价的费率卡；
--   3. gateway.image_generation_task 每次生成归档的档位。
-- 平台侧的同名词表由 platform V106 一起放开（platform.model_usage_dimension 与
-- 控制台的 IMAGE_SIZE_TIERS），否则 1.5K 能提交却无法被任何套餐上架。
--
-- 费率行按**与 2K 同价**写入（成本 62 分/张、零售 89 分/张）。厂商对 1.5K 的
-- 计价口径尚未核实，而这里两个失败方向并不对称：报高了只是少赚，报低了会让
-- 套餐毛利守卫按低于实际的价格发布版本，并把平台真实成本记成更小的数。
-- 因此取已知的最坏情况（与 2K 基准逐字相同），而不是在区间里插值出一个更低的数。
-- 厂商确认 1.5K 单独计价之后，按 V82/V85 的房规关掉这一行、以新的 price_version
-- 写入真实数字即可；那时价格链路已经不再假设"一个别名只有一行当前费率"
-- （ProviderPriceQueryRepository 在同一维度内取最贵的一档）。
--
-- 在售档位不变：image-basic 仍然只登记 2K。1.5K 比它便宜，因此按
-- "不得大于在售档位"的规则可提交；本迁移不改任何套餐价格、渠道或凭据，
-- 也不改动 4K 的可用性（它仍不在售，且仍不在厂商文档口径里）。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 1. 三处档位词表
--
-- 每一处都是 drop 一个只讲档位的 CHECK 再原样加回、只多一个值。刻意不碰任何
-- 形状/取值 CHECK：它们用不等式（unit_price >= minimum_charge 等）表达，
-- 新增一个档位不该惊动它们。
-- ---------------------------------------------------------------------------

-- V81:86-87 的 ck_image_alias_tier_value。
ALTER TABLE gateway.image_alias_tier DROP CONSTRAINT ck_image_alias_tier_value;

ALTER TABLE gateway.image_alias_tier ADD CONSTRAINT ck_image_alias_tier_value
    CHECK (resolution_tier IN ('1K', '1.5K', '2K', '4K'));

-- V96:110-117 的 ck_model_usage_price_tier。它是**逐维度的析取**，因此只改
-- IMAGE 那一个分支：视频与音频的词表由 V96 钉住，本迁移无权顺手放宽——
-- 那正是 V96 记录过的缺陷形态（一个维度必须出现在每一条枚举它的约束里）。
ALTER TABLE gateway.model_usage_price DROP CONSTRAINT ck_model_usage_price_tier;

ALTER TABLE gateway.model_usage_price ADD CONSTRAINT ck_model_usage_price_tier
    CHECK (
        (usage_dimension = 'TOKEN' AND resolution_tier = '')
        OR (usage_dimension = 'VIDEO_SECOND'
            AND resolution_tier IN ('480P', '720P', '1080P'))
        OR (usage_dimension = 'IMAGE'
            AND resolution_tier IN ('1K', '1.5K', '2K', '4K'))
        OR (usage_dimension = 'AUDIO_SECOND' AND resolution_tier = ''));

-- V81:152 的 ck_image_task_tier。
ALTER TABLE gateway.image_generation_task DROP CONSTRAINT ck_image_task_tier;

ALTER TABLE gateway.image_generation_task ADD CONSTRAINT ck_image_task_tier
    CHECK (resolution_tier IN ('1K', '1.5K', '2K', '4K'));

-- 列注释与词表同源：注释停在旧的三档上，下一个读它的运维会以为 1.5K 不被接受。
COMMENT ON COLUMN gateway.image_alias_tier.resolution_tier IS
    '1K / 1.5K / 2K / 4K. The largest size tier the alias may be asked for: the '
    'alias is priced per image at this tier, so serving it (or a smaller render) '
    'can never cost more than the basis the package was published against, while '
    'a larger render is the loss V54 exists to prevent.';

COMMENT ON COLUMN gateway.model_usage_price.resolution_tier IS
    'Size tier the row prices. TOKEN: always ''''. VIDEO_SECOND: 480P / 720P / '
    '1080P. IMAGE: 1K / 1.5K / 2K / 4K (see V115). AUDIO_SECOND: always '''' '
    '(see V96) -- the unit is a second and there is no size ladder. The tier is '
    'part of the rate card identity, so one alias may hold one row per tier and '
    'dimension.';

-- ---------------------------------------------------------------------------
-- 2. 1.5K 的费率行
--
-- 与 2K 那一行（image-v2：成本 62、零售 89、绝对下限 25、目标毛利 30%）逐字相同。
-- uq_model_usage_price_current 是 UNIQUE (model_code, usage_dimension,
-- resolution_tier) WHERE effective_to IS NULL，档位在键里，因此新增一档不需要
-- 关掉任何既有行——这与"同一档位换价"是两件事，后者必须走 V82 那套
-- 关旧行、写新 price_version 的流程。
-- ---------------------------------------------------------------------------

INSERT INTO gateway.model_usage_price(
    id, model_code, usage_dimension, resolution_tier,
    minimum_units, minimum_charge, unit_price,
    provider_unit_cost, retry_rate, target_margin_ratio, min_absolute_margin,
    price_version, effective_from, official_source)
VALUES
    ('86000000-0000-4000-8000-000000000115', 'image-basic', 'IMAGE', '1.5K',
     1, 89, 89, 62, 0, 0.30000, 25, 'image-v3',
     now(),
     'product:1.5K carries the 2K worst-case basis (output 0.30-0.60 CNY/image '
     '+ input image 0.02 CNY/image) until the vendor confirms a separate rate')
ON CONFLICT (model_code, usage_dimension, resolution_tier, price_version) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. 断言
--
-- 与 V81/V82 同一口气：一个直接改写"档位能否成立"的迁移必须自己证明三处词表
-- 都真的接受了新档位，而不是只在注释里声称。约束定义读 pg_get_constraintdef，
-- 因为那正是运行期会执行的文本；"我写了一个 CHECK"和"这条 CHECK 真的在库里"
-- 是两件事。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    alias_tier_def  text;
    price_tier_def  text;
    task_tier_def   text;
    v15_row         record;
    v2_rows         integer;
    v2_cost         bigint;
    sold_tier       text;
    current_rows    integer;
    worst_case      bigint;
BEGIN
    SELECT pg_get_constraintdef(c.oid) INTO alias_tier_def
    FROM pg_constraint c
    JOIN pg_class t ON t.oid = c.conrelid
    JOIN pg_namespace n ON n.oid = t.relnamespace
    WHERE n.nspname = 'gateway' AND t.relname = 'image_alias_tier'
      AND c.conname = 'ck_image_alias_tier_value';

    SELECT pg_get_constraintdef(c.oid) INTO price_tier_def
    FROM pg_constraint c
    JOIN pg_class t ON t.oid = c.conrelid
    JOIN pg_namespace n ON n.oid = t.relnamespace
    WHERE n.nspname = 'gateway' AND t.relname = 'model_usage_price'
      AND c.conname = 'ck_model_usage_price_tier';

    SELECT pg_get_constraintdef(c.oid) INTO task_tier_def
    FROM pg_constraint c
    JOIN pg_class t ON t.oid = c.conrelid
    JOIN pg_namespace n ON n.oid = t.relnamespace
    WHERE n.nspname = 'gateway' AND t.relname = 'image_generation_task'
      AND c.conname = 'ck_image_task_tier';

    IF alias_tier_def IS NULL OR price_tier_def IS NULL OR task_tier_def IS NULL THEN
        RAISE EXCEPTION 'image_tier_constraint_missing:alias=%:price=%:task=%',
            alias_tier_def, price_tier_def, task_tier_def;
    END IF;

    -- 每一处都必须**同时**含新档位与三个旧档位：只加 1.5K 而丢掉 1K/2K/4K
    -- 会是一条比原缺陷更糟的约束（既有的在售档位被拒）。
    IF alias_tier_def NOT LIKE '%1.5K%' OR alias_tier_def NOT LIKE '%1K%'
            OR alias_tier_def NOT LIKE '%2K%' OR alias_tier_def NOT LIKE '%4K%' THEN
        RAISE EXCEPTION 'image_alias_tier_vocabulary_incomplete:%', alias_tier_def;
    END IF;
    IF price_tier_def NOT LIKE '%1.5K%' OR price_tier_def NOT LIKE '%1K%'
            OR price_tier_def NOT LIKE '%2K%' OR price_tier_def NOT LIKE '%4K%' THEN
        RAISE EXCEPTION 'image_price_tier_vocabulary_incomplete:%', price_tier_def;
    END IF;
    IF task_tier_def NOT LIKE '%1.5K%' OR task_tier_def NOT LIKE '%1K%'
            OR task_tier_def NOT LIKE '%2K%' OR task_tier_def NOT LIKE '%4K%' THEN
        RAISE EXCEPTION 'image_task_tier_vocabulary_incomplete:%', task_tier_def;
    END IF;
    -- 费率卡那条是逐维度的析取：视频与音频的分支必须原样还在。
    IF price_tier_def NOT LIKE '%480P%' OR price_tier_def NOT LIKE '%720P%'
            OR price_tier_def NOT LIKE '%1080P%'
            OR price_tier_def NOT LIKE '%AUDIO_SECOND%' THEN
        RAISE EXCEPTION 'other_dimension_tier_vocabulary_lost:%', price_tier_def;
    END IF;

    -- 1.5K 那一行必须存在、就是那组数字，并且是当前行。
    SELECT resolution_tier, unit_price, minimum_charge, minimum_units,
           provider_unit_cost, retry_rate, target_margin_ratio, min_absolute_margin,
           price_version
    INTO v15_row
    FROM gateway.model_usage_price
    WHERE model_code = 'image-basic' AND usage_dimension = 'IMAGE'
      AND resolution_tier = '1.5K' AND effective_to IS NULL;

    IF v15_row IS NULL THEN
        RAISE EXCEPTION 'image_1_5k_rate_row_missing:image-basic';
    END IF;
    IF v15_row.provider_unit_cost <> 62 OR v15_row.unit_price <> 89
            OR v15_row.minimum_charge <> 89 OR v15_row.minimum_units <> 1
            OR v15_row.retry_rate <> 0 OR v15_row.target_margin_ratio <> 0.30000
            OR v15_row.min_absolute_margin <> 25
            OR v15_row.price_version <> 'image-v3' THEN
        RAISE EXCEPTION 'image_1_5k_rate_row_not_the_worst_case_basis:%:%:%:%:%:%:%',
            v15_row.provider_unit_cost, v15_row.unit_price, v15_row.minimum_charge,
            v15_row.retry_rate, v15_row.target_margin_ratio,
            v15_row.min_absolute_margin, v15_row.price_version;
    END IF;

    -- 2K 那一行必须一位不改：它是 48 张套餐的定价基准，改它会让已发布版本的
    -- 成本基准与运行期重算分叉。
    SELECT count(*), min(provider_unit_cost) INTO v2_rows, v2_cost
    FROM gateway.model_usage_price
    WHERE model_code = 'image-basic' AND usage_dimension = 'IMAGE'
      AND resolution_tier = '2K' AND effective_to IS NULL;

    IF v2_rows <> 1 OR v2_cost <> 62 THEN
        RAISE EXCEPTION 'image_2k_rate_row_changed:rows=%:cost=%', v2_rows, v2_cost;
    END IF;

    -- 在售档位仍是 2K：本迁移加的是"更便宜的档位"，不是换在售档位。
    SELECT resolution_tier INTO sold_tier
    FROM gateway.image_alias_tier WHERE model_code = 'image-basic';

    IF sold_tier IS DISTINCT FROM '2K' THEN
        RAISE EXCEPTION 'image_alias_sold_tier_changed:%', sold_tier;
    END IF;

    -- 现在 image-basic 有两行当前费率（2K 与 1.5K），而取成本的那条查询必须
    -- 仍然给得出一个数：它在同一维度内取最贵的一档（最坏情况）。这里断言
    -- 两行同价、最坏情况仍是 62——一旦有人把 1.5K 改便宜，这条断言会失败，
    -- 提醒他发布守卫读到的仍然只会是 62。
    SELECT count(*) INTO current_rows
    FROM gateway.image_effective_cost WHERE model_code = 'image-basic';

    IF current_rows <> 2 THEN
        RAISE EXCEPTION 'image_current_rate_rows_unexpected:%', current_rows;
    END IF;

    SELECT max(effective_unit_cost) INTO worst_case
    FROM gateway.image_effective_cost WHERE model_code = 'image-basic';

    IF worst_case <> 62 THEN
        RAISE EXCEPTION 'image_worst_case_cost_changed:%', worst_case;
    END IF;
END $$;

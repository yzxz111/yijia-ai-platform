-- ---------------------------------------------------------------------------
-- 图片尺寸档位补上 1.5K：让"卖 1.5K"在平台侧成为可能。
--
-- 网关 V115 把 1.5K 放进三处词表（别名登记、费率卡、生成任务），平台侧的同名
-- 词表必须一起放开，否则会出现一个更难查的中间态：别名在网关能提交、能计成本，
-- 却在控制台里**登记不进去**（PUT /console/platform/admin/model-dimensions/{code}
-- 以 422 invalid_model_resolution_tier 拒绝），于是没有任何套餐能上架它。
--
-- 本迁移只动那一条 CHECK（放开 1.5K，并把扁平的值列表收紧为逐维度的析取），
-- 不改任何已经登记的行：
--   * image-basic 的镜像行仍是 2K / 零售 89 / 成本 62（image-v2）。它是**在售
--     档位**的镜像，而 1.5K 是比它便宜的档位，不改变这张表的形状——
--     platform.model_usage_dimension 的主键是 model_code，一个别名一行。
--   * IMAGE 维度的毛利下限（min 0.30 / warning 0.40）与单位标签（images）不动：
--     1.5K 的费率行与 2K 同价（gateway V115），因此下限口径不变。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 1. 档位词表
--
-- V83:48-53 的词表，但改成**逐维度的析取**而不是一个扁平的值列表。
--
-- 扁平列表本身就是一个缺陷，V83 只是把它撑大了：它允许一个 VIDEO_SECOND 行声称
-- '2K'（图片档位），而视频成本是按"分辨率加权的每秒成本"计算的，一个无法解释的
-- 档位会让那份加权失去依据。网关侧的 ck_model_usage_price_tier 早就是逐维度的
-- 形状（V96 的教训正是"一个维度必须出现在每一条枚举它的约束里"），这里把平台侧
-- 对齐成同一个形状，顺带把 '1.5K' 加进 IMAGE 那一支。
--
-- 刻意不碰 ck_model_usage_dimension_value（维度）与两条价格 CHECK：它们用值与
-- 不等式表达，新增一个尺寸档位不该惊动它们。
--
-- 收紧前先自证：任何一行都不该在新的析取下变得非法。写清楚是哪一行、哪个维度、
-- 哪个档位，比让 ALTER TABLE 抛一句语法级的约束错误有用得多。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    offender text;
BEGIN
    SELECT dimension.model_code || ':' || dimension.usage_dimension || ':'
           || dimension.resolution_tier
    INTO offender
    FROM platform.model_usage_dimension dimension
    WHERE NOT (
        (dimension.usage_dimension = 'TOKEN' AND dimension.resolution_tier = '')
        OR (dimension.usage_dimension = 'AUDIO_SECOND'
            AND dimension.resolution_tier = '')
        OR (dimension.usage_dimension = 'VIDEO_SECOND'
            AND dimension.resolution_tier IN ('480P', '720P', '1080P'))
        OR (dimension.usage_dimension = 'IMAGE'
            AND dimension.resolution_tier IN ('1K', '1.5K', '2K', '4K')))
    LIMIT 1;

    IF offender IS NOT NULL THEN
        RAISE EXCEPTION 'model_usage_dimension_row_outside_the_dimension_vocabulary:%',
            offender;
    END IF;
END $$;

ALTER TABLE platform.model_usage_dimension
    DROP CONSTRAINT ck_model_usage_dimension_tier;

ALTER TABLE platform.model_usage_dimension
    ADD CONSTRAINT ck_model_usage_dimension_tier
        CHECK (
            (usage_dimension = 'TOKEN' AND resolution_tier = '')
            OR (usage_dimension = 'AUDIO_SECOND' AND resolution_tier = '')
            OR (usage_dimension = 'VIDEO_SECOND'
                AND resolution_tier IN ('480P', '720P', '1080P'))
            OR (usage_dimension = 'IMAGE'
                AND resolution_tier IN ('1K', '1.5K', '2K', '4K')));

-- ---------------------------------------------------------------------------
-- 2. 断言
--
-- 与 V83/V85 同一口气：直接写入受约束结构的迁移必须自己证明写入合法。
-- 这里断言三件事：新词表确实生效且没有丢掉任何一个旧值**也没有丢掉维度分支**
-- （一个只剩值列表的约束会让视频别名重新可以声称图片档位）；视频与音频两支
-- 原样还在；image-basic 的镜像行一位没改。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    tier_def    text;
    image_dim   text;
    image_tier  text;
    image_cost  bigint;
    image_price bigint;
    image_ver   text;
    img_min     numeric;
    img_warning numeric;
    img_label   text;
BEGIN
    SELECT pg_get_constraintdef(c.oid) INTO tier_def
    FROM pg_constraint c
    JOIN pg_class t ON t.oid = c.conrelid
    JOIN pg_namespace n ON n.oid = t.relnamespace
    WHERE n.nspname = 'platform' AND t.relname = 'model_usage_dimension'
      AND c.conname = 'ck_model_usage_dimension_tier';

    IF tier_def IS NULL THEN
        RAISE EXCEPTION 'model_usage_dimension_tier_constraint_missing';
    END IF;
    IF tier_def NOT LIKE '%1.5K%' OR tier_def NOT LIKE '%1K%'
            OR tier_def NOT LIKE '%2K%' OR tier_def NOT LIKE '%4K%'
            OR tier_def NOT LIKE '%480P%' OR tier_def NOT LIKE '%720P%'
            OR tier_def NOT LIKE '%1080P%' THEN
        RAISE EXCEPTION 'model_usage_dimension_tier_vocabulary_incomplete:%', tier_def;
    END IF;
    -- 逐维度的形状必须留下来：只有值列表时，一个 VIDEO_SECOND 行可以声称
    -- '2K'，而那正是本迁移要顺手修掉的那条漏洞。
    IF tier_def NOT LIKE '%VIDEO_SECOND%' OR tier_def NOT LIKE '%IMAGE%'
            OR tier_def NOT LIKE '%AUDIO_SECOND%' OR tier_def NOT LIKE '%TOKEN%' THEN
        RAISE EXCEPTION 'model_usage_dimension_tier_lost_its_dimension_branches:%',
            tier_def;
    END IF;

    SELECT usage_dimension, resolution_tier, effective_unit_cost,
           retail_unit_price, price_version
    INTO image_dim, image_tier, image_cost, image_price, image_ver
    FROM platform.model_usage_dimension
    WHERE model_code = 'image-basic';

    IF image_dim IS DISTINCT FROM 'IMAGE' THEN
        RAISE EXCEPTION 'image_alias_dimension_changed:%', image_dim;
    END IF;
    IF image_tier IS DISTINCT FROM '2K' THEN
        RAISE EXCEPTION 'image_alias_tier_changed:%', image_tier;
    END IF;
    IF image_cost IS DISTINCT FROM 62 OR image_price IS DISTINCT FROM 89
            OR image_ver IS DISTINCT FROM 'image-v2' THEN
        RAISE EXCEPTION 'image_alias_basis_changed:cost=%:price=%:version=%',
            image_cost, image_price, image_ver;
    END IF;

    SELECT policy.minimum_margin, policy.warning_margin, policy.unit_label
    INTO img_min, img_warning, img_label
    FROM platform.package_dimension_price_policy policy
    WHERE policy.usage_dimension = 'IMAGE';

    IF img_min IS DISTINCT FROM 0.30000 OR img_warning IS DISTINCT FROM 0.40000
            OR img_label IS DISTINCT FROM 'images' THEN
        RAISE EXCEPTION 'image_dimension_policy_changed:min=%:warning=%:label=%',
            img_min, img_warning, img_label;
    END IF;
END $$;

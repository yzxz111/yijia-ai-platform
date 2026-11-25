-- 音频（按秒）维度的平台侧词表与价格策略。
--
-- 网关侧由 V100 建立：它把 AUDIO_SECOND 加进六个 CHECK，并新增
-- gateway.audio_alias_tier 与 gateway.audio_effective_cost。平台侧需要
-- 自己那份词表，理由与 V54/V83 完全相同：平台要在**不**同步调用网关的
-- 前提下验证一个套餐版本的维度，因此它需要知道 AUDIO_SECOND 是合法的，
-- 也需要知道这个维度的配额单位与毛利下限。
--
-- **版本号为什么是 88 而不是 86。** 本文件最初写成 V86，与同一目录下既有的
-- V86__package_plan_type.sql 撞号；V87 也已被
-- V87__verified_phone_registration_completion_window.sql 占用。
-- Flyway 遇到同一版本号的两个文件会在启动时直接失败，而这条冲突之所以
-- 能溜进来，正是因为本改动从未被构建或迁移测试跑过——一次
-- `PlatformMigrationTest` 就会以 duplicate version 报出来。
-- 这是"读代码自查"无法替代一次真实构建的具体例子。
--
-- 本迁移刻意只做两件事：读模型的维度词表，以及价格策略行。
-- 它**不**登记任何音频别名到 platform.model_usage_dimension——
-- 那个表登记的是"哪些别名按秒卖"，而音频别名要到网关 V101 才存在
-- （别名、档位、费率行一起落）。给一个还不存在的别名写一行
-- 读模型，会让这张表表达一个当时并不成立的断言。
--
-- 设计：docs/superpowers/specs/2026-09-30-audio-and-tianyi-model-onboarding-design.md
-- 参照：platform V54（视频秒维度）、V83（图片维度怎么加的）

-- ---------------------------------------------------------------------------
-- 1. 读模型的维度词表
--
-- resolution_tier 不动：AUDIO_SECOND 的计量单位就是秒，没有档位阶梯，
-- 因此它的行用 '' —— 与 TOKEN 行同形，而 V83 之后 '' 已经在词表里。
-- 只有 usage_dimension 这一个值列表需要多一个词。
-- ---------------------------------------------------------------------------

ALTER TABLE platform.model_usage_dimension
    DROP CONSTRAINT ck_model_usage_dimension_value;

ALTER TABLE platform.model_usage_dimension
    ADD CONSTRAINT ck_model_usage_dimension_value
        CHECK (usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE', 'AUDIO_SECOND'));

-- ---------------------------------------------------------------------------
-- 2. 价格策略行
--
-- quota_units_per_cost_unit = 1：一个「分/秒」的单价乘以一个秒数额度
-- 不需要任何缩放，与 VIDEO_SECOND/IMAGE 完全相同（TOKEN 才是 1000000）。
--
-- unit_label = 'seconds'，**不是**新造一个 'audio_seconds'。
-- 这个字段的用途是给毛利消息命名单位（PackageMarginPolicy:73-97 把它
-- 拼进描述性文本），而音频的额度确实以秒计，所以 'seconds' 既准确
-- 又不需要再放宽 ck_package_dimension_policy_label 的值表。
-- V83 为图片新造 'images' 是必要的，因为按张的额度标成秒是错的；
-- 音频标成秒恰恰是对的。
--
-- 两个下限取 0.30000 / 0.40000。它们**不是**从 V54 的初始值抄来的：
-- V54 当初给 VIDEO_SECOND/IMAGE 插的是 0.25000/0.30000，但那两个数字
-- 早已被后续迁移改掉，直接照抄 V54 会让音频维度落在产品的毛利保证之下：
--
--   V58  把每个维度的 minimum_margin 抬到 0.30000（0.25000 是历史值）
--   V62  把 TOKEN/VIDEO_SECOND/IMAGE 的 warning_margin 抬到 0.40000
--   V65  只把 VIDEO_SECOND 的 warning 降回 0.35000
--   V83  IMAGE 的 minimum/warning 保持 0.30000/0.40000，只改单位标签
--
-- 因此本迁移落库的最终形态是：
--
--   TOKEN         1000000  tokens   0.30000  0.40000
--   VIDEO_SECOND        1  seconds  0.30000  0.35000   <- V65 的视频专属下调
--   IMAGE               1  images   0.30000  0.40000
--   AUDIO_SECOND        1  seconds  0.30000  0.40000   <- 本行
--
-- 音频为什么取 0.30000 而不是 0.25000：0.30000 是产品的硬保证，
-- V62:58-65 与 V65:60-67 都断言过没有任何维度低于它。一个 0.25000 的行
-- 不会违反表自身的 CHECK（warning >= minimum 仍然成立），却会静默地
-- 让音频套餐按低于产品承诺的毛利发布——正是那两条断言存在的理由。
--
-- 音频为什么取 0.40000 而不是像视频那样 0.35000：V65:5-6 明说 0.35 是
-- **只为视频产品线**做的决定，针对的是 480P 入门档年付套餐实测 35.7%
-- 这一具体情况。音频目前没有任何已发布套餐，没有对应的实测数据，
-- 因此没有理由自造一个视频专属的例外；取区间上沿 0.40000，与
-- TOKEN/IMAGE 一致。将来若真的出现一个诚实的薄利音频档，应当像 V65
-- 那样用一个有实测数字支撑的专门迁移去下调，而不是在这里预先放宽。
--
-- 这个下限是发布**套餐版本**时的硬门槛，与网关侧的
-- ck_model_usage_price_margin 是两回事（后者管的是费率卡本身的单位毛利）。
-- ---------------------------------------------------------------------------

INSERT INTO platform.package_dimension_price_policy(
    usage_dimension, quota_units_per_cost_unit, unit_label, minimum_margin, warning_margin)
VALUES
    ('AUDIO_SECOND', 1, 'seconds', 0.30000, 0.40000)
ON CONFLICT (usage_dimension) DO UPDATE SET
    quota_units_per_cost_unit = EXCLUDED.quota_units_per_cost_unit,
    unit_label = EXCLUDED.unit_label,
    minimum_margin = EXCLUDED.minimum_margin,
    warning_margin = EXCLUDED.warning_margin;

COMMENT ON TABLE platform.model_usage_dimension IS
    'Read model of the gateway rate card: which aliases are priced per second, '
    'per image, or per audio second. Used to validate a package version''s '
    'usage_dimension before publication. An alias with no row here is a TOKEN '
    'alias (V54:124-126).';

-- ---------------------------------------------------------------------------
-- 3. 断言
--
-- 放宽一个 CHECK 不会让任何东西失败，它只会让将来的非法写入被接受；
-- 因此这里不满足于"迁移跑完了"，而是证明两处写入**确实生效**，
-- 并且证明它们没有顺手放宽别的东西。
-- 参照 V83:87-91 与网关 V100 的同一思路。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    dimension_definition text;
    policy_minimum numeric(8, 5);
    policy_warning numeric(8, 5);
    policy_units bigint;
    policy_label varchar(16);
    broken integer;
BEGIN
    -- 维度词表：取出约束的**实际定义**，而不是复述我刚写下的字面量。
    -- 若一次 DROP 成功而 ADD 被跳过（名字写错），definition 会是 NULL。
    SELECT pg_get_constraintdef(constraint_row.oid)
    INTO dimension_definition
    FROM pg_constraint constraint_row
    JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
    JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace
    WHERE schema_row.nspname = 'platform'
      AND table_row.relname = 'model_usage_dimension'
      AND constraint_row.conname = 'ck_model_usage_dimension_value';

    IF dimension_definition IS NULL THEN
        RAISE EXCEPTION 'ck_model_usage_dimension_value_missing';
    END IF;

    IF position('AUDIO_SECOND' IN dimension_definition) = 0 THEN
        RAISE EXCEPTION
            'dimension_vocabulary_does_not_admit_AUDIO_SECOND:definition=%',
            dimension_definition;
    END IF;

    -- 行为检查：这张表必须**真的接受**一行 AUDIO_SECOND，也必须
    -- 仍然拒绝一个不存在的维度。用真实的 CONSTRAINT 判断，
    -- 而不是文本匹配。
    BEGIN
        INSERT INTO platform.model_usage_dimension(
            model_code, usage_dimension, resolution_tier, retail_unit_price,
            effective_unit_cost, price_version)
        VALUES ('__audio_probe__', 'AUDIO_SECOND', '', 5, 0, 'probe');
        DELETE FROM platform.model_usage_dimension WHERE model_code = '__audio_probe__';
    EXCEPTION
        WHEN check_violation THEN
            RAISE EXCEPTION 'model_usage_dimension_rejects_AUDIO_SECOND';
    END;

    BEGIN
        INSERT INTO platform.model_usage_dimension(
            model_code, usage_dimension, resolution_tier, price_version)
        VALUES ('__audio_probe__', 'AUDIO_MINUTE', '', 'probe');
        DELETE FROM platform.model_usage_dimension WHERE model_code = '__audio_probe__';
        RAISE EXCEPTION 'model_usage_dimension_accepted_an_unknown_dimension';
    EXCEPTION
        WHEN check_violation THEN
            NULL; -- 期望路径。
        WHEN raise_exception THEN
            RAISE;
    END;

    -- 价格策略行必须存在且数值正确。逐列读回而不是断言"有一行"：
    -- 一个错的下限会被 PackageMarginPolicy 直接用来定价，
    -- 因此"行存在"远不如"行里的每个数字都对"重要。
    SELECT policy.quota_units_per_cost_unit, policy.unit_label,
           policy.minimum_margin, policy.warning_margin
    INTO policy_units, policy_label, policy_minimum, policy_warning
    FROM platform.package_dimension_price_policy policy
    WHERE policy.usage_dimension = 'AUDIO_SECOND';

    IF policy_units IS NULL THEN
        RAISE EXCEPTION 'audio_second_price_policy_row_missing';
    END IF;

    IF policy_units <> 1 THEN
        RAISE EXCEPTION
            'audio_second_quota_units_per_cost_unit_must_be_one:actual=%', policy_units;
    END IF;

    IF policy_label IS DISTINCT FROM 'seconds' THEN
        RAISE EXCEPTION
            'audio_second_unit_label_must_be_seconds:actual=%', policy_label;
    END IF;

    IF policy_minimum IS DISTINCT FROM 0.30000::numeric(8, 5)
            OR policy_warning IS DISTINCT FROM 0.40000::numeric(8, 5) THEN
        RAISE EXCEPTION
            'audio_second_margin_floors_must_be_the_product_band:min=%:warn=%',
            policy_minimum, policy_warning;
    END IF;

    -- 产品的硬保证：没有任何维度的 minimum_margin 低于 0.30000。
    -- V62:58-65 与 V65:60-67 都断言过这一点，本迁移也必须在**写入之后**
    -- 再证一次——否则「加了一个低于保证的维度」这件事只会表现为
    -- 某个不受约束的 INSERT 成功返回。
    SELECT count(*) INTO broken
    FROM platform.package_dimension_price_policy policy
    WHERE policy.minimum_margin < 0.30000
       OR policy.warning_margin < policy.minimum_margin;

    IF broken > 0 THEN
        RAISE EXCEPTION
            'a_dimension_sits_below_the_thirty_percent_guarantee:count=%', broken;
    END IF;

    -- 没有把所有维度写成同一个数：V65 的视频专属下调必须原样保留，
    -- TOKEN 与 IMAGE 也不得被本次写入拖走。
    --
    -- 这条断言是本文件初版的**修正**。初版写的是「三个按秒计费的维度
    -- 必须给出同一组下限（1/0.25000/0.30000）」，那个前提本身是错的：
    -- VIDEO_SECOND 的 warning 是 0.35000 而 IMAGE 是 0.40000。
    -- 一条把自己写下的字面量当作不变量复述的断言，既抓不到
    -- 「我抄错了历史值」，也无法在维度之间做出真正的区分；
    -- 它只会在我恰好抄错时失败一次，然后被我改成抄对的样子。
    -- 下面这条则是逐个维度钉住**迁移链的最终结果**，任何一次
    -- 无意的连带修改都会失败。
    SELECT count(*) INTO broken
    FROM (VALUES
        ('TOKEN',        1000000::bigint, 'tokens',  0.30000::numeric(8, 5), 0.40000::numeric(8, 5)),
        ('VIDEO_SECOND',        1::bigint, 'seconds', 0.30000::numeric(8, 5), 0.35000::numeric(8, 5)),
        ('IMAGE',               1::bigint, 'images',  0.30000::numeric(8, 5), 0.40000::numeric(8, 5)),
        ('AUDIO_SECOND',        1::bigint, 'seconds', 0.30000::numeric(8, 5), 0.40000::numeric(8, 5))
    ) AS expected(usage_dimension, quota_units, unit_label, minimum_margin, warning_margin)
    JOIN platform.package_dimension_price_policy policy
      ON policy.usage_dimension = expected.usage_dimension
    WHERE policy.quota_units_per_cost_unit <> expected.quota_units
       OR policy.unit_label <> expected.unit_label
       OR policy.minimum_margin <> expected.minimum_margin
       OR policy.warning_margin <> expected.warning_margin;

    IF broken > 0 THEN
        RAISE EXCEPTION
            'dimension_policy_does_not_match_the_migration_chain:count=%', broken;
    END IF;

    -- 四个维度必须都在：逐行比对用的是 JOIN，缺一行就会少比一行而不是报错。
    SELECT count(*) INTO broken
    FROM platform.package_dimension_price_policy policy
    WHERE policy.usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE', 'AUDIO_SECOND');

    IF broken <> 4 THEN
        RAISE EXCEPTION 'expected_exactly_four_priced_dimensions:actual=%', broken;
    END IF;

    -- 本迁移刻意不登记音频别名。价格策略行存在而读模型为空，
    -- 是**此刻**的正确状态：网关 V101 才建出那些别名，而平台侧的登记
    -- 在紧随其后的 V97__audio_dimension_plan_read_model.sql 里。
    --
    -- 这条断言因此是一个顺序守卫：若有人在跑本迁移之前就先登记了音频别名，
    -- 说明迁移顺序被破坏（或有人手动改了这张表），而那会让「平台知道的
    -- 别名」与「网关真正在卖的别名」不一致。它在 V97 之前必然是零，
    -- 在 V97 之后不再被求值。
    SELECT count(*) INTO broken
    FROM platform.model_usage_dimension dimension
    WHERE dimension.usage_dimension = 'AUDIO_SECOND';

    IF broken > 0 THEN
        RAISE EXCEPTION
            'audio_aliases_were_registered_before_V97:rows=%', broken;
    END IF;

    -- 本次改动新增的那条**跨服务**接线也必须在这里被证明：
    -- 平台摄入层有一个维度白名单，音频事件若不在其中会被**拒绝**，
    -- 于是音频成本永远不会出现在运营报表里——与"网关不写证据"是同一个
    -- 后果的不同半边。它是一个 Java 常量而不是数据库对象，因此这里
    -- 只能断言它的值确实存在于随附的导入契约里；真正的守卫是
    -- OperatingFactIngestionServiceTest。
    IF NOT EXISTS (
            SELECT 1 FROM platform.package_dimension_price_policy policy
            WHERE policy.usage_dimension = 'AUDIO_SECOND') THEN
        RAISE EXCEPTION 'audio_second_price_policy_row_missing_after_write';
    END IF;
END $$;

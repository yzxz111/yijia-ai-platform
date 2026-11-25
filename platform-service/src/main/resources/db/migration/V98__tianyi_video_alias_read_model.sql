-- 天翼分镜视频别名（video-minimax）的平台侧登记。
--
-- 网关 V101 新建了 video-minimax 别名、把它挂到天翼渠道、并按天翼的实测成本
-- （720P 有声 0.50 元/秒）写了费率行。平台需要知道这个别名是按秒卖的，
-- 否则会把它的套餐当成 token 套餐发布，然后在发放时被网关的权益触发器拒绝——
-- V83:15-19 为图片、V97 为音频写过完全相同的理由。
--
-- 版本号 90：V97 是音频读模型，V96 是音频维度词表。
--
-- 设计：docs/superpowers/specs/2026-09-30-audio-and-tianyi-model-onboarding-design.md
-- 参照：platform V54（视频别名登记）、V97（音频别名登记，同形）

-- ---------------------------------------------------------------------------
-- 1. 登记 video-minimax
--
-- 价格两列是对网关价目表所含内容的记录，而不是套餐定价的依据：
-- 发布校验从网关的 /internal/prices/effective-unit-cost 读取实时数值。
-- 权威值在 gateway.model_usage_price 的 video-minimax 行上（网关 V101）。
--
-- **effective_unit_cost 必须逐字等于网关存储的那一个。**
-- gateway.video_effective_cost 直接透传 provider_unit_cost（V45 记录了原因：
-- V40 把该列存成了 retry-inclusive，视图再乘一次会把允许量算两遍）。
-- 因此网关 V101 存的是「厂商原价 50 + 5% 重试」合并后的 **53**，镜像也必须存 53。
--
-- 这里曾经存 50，即把**厂商原价**当成了存储值——与 V45 的口径正好相反。
-- 它同时让网关侧少算了重试允差（网关 V101 的注释里有完整推演）。
-- 这与音频存 ceil 之后的值并不矛盾：两个视图对 retry_rate 的处理本来就不同，
-- 镜像跟着各自的视图走。
--
-- resolution_tier 是 '720P'：天翼那边由 TianYiYunVideoGenerationAdapter 翻译成
-- 768P（720P 的超集），平台的售卖档位仍是 720P。
-- ---------------------------------------------------------------------------

INSERT INTO platform.model_usage_dimension(
    model_code, usage_dimension, resolution_tier, retail_unit_price,
    effective_unit_cost, price_version)
VALUES
    ('video-minimax', 'VIDEO_SECOND', '720P', 83, 53, 'video-minimax-v1')
ON CONFLICT (model_code) DO UPDATE SET
    usage_dimension = EXCLUDED.usage_dimension,
    resolution_tier = EXCLUDED.resolution_tier,
    retail_unit_price = EXCLUDED.retail_unit_price,
    effective_unit_cost = EXCLUDED.effective_unit_cost,
    price_version = EXCLUDED.price_version,
    synced_at = now();

-- ---------------------------------------------------------------------------
-- 2. 断言
--
-- 一个直接写入受约束表的迁移必须自己证明写入合法（V83:87-91 的同一做法），
-- 并且必须证明**镜像的数值与网关实际会给出的那一个一致**——
-- 两张表给出不同成本基准时，控制台显示的数字与结算所用的数字会分家，
-- 而那种分家不会让任何东西失败。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    actual_tier   varchar(16);
    actual_dim    varchar(20);
    actual_retail bigint;
    actual_cost   bigint;
    broken        integer;
BEGIN
    SELECT dimension.resolution_tier, dimension.usage_dimension,
           dimension.retail_unit_price, dimension.effective_unit_cost
    INTO actual_tier, actual_dim, actual_retail, actual_cost
    FROM platform.model_usage_dimension dimension
    WHERE dimension.model_code = 'video-minimax';

    IF actual_dim IS NULL THEN
        RAISE EXCEPTION 'video_minimax_was_not_registered_in_the_platform_read_model';
    END IF;

    IF actual_dim <> 'VIDEO_SECOND' THEN
        RAISE EXCEPTION
            'video_minimax_must_be_registered_as_a_second_dimension_alias:actual=%',
            actual_dim;
    END IF;

    IF actual_tier <> '720P' THEN
        RAISE EXCEPTION 'video_minimax_tier_must_be_720P:actual=%', actual_tier;
    END IF;

    -- 零售 83 = 有效成本 53 在 30% 硬下限与绝对下限 30 之上的最小可发布价：
    --     比率 ceil(53 / 0.7) = 76；绝对 53 + 30 = 83；83 恰好同时越过两者，
    --     且毛利率 (83 - 53) / 83 = 36.1% 高于 30%。数字的来历见网关 V101。
    IF actual_retail <> 83 THEN
        RAISE EXCEPTION 'video_minimax_retail_must_be_83:actual=%', actual_retail;
    END IF;

    -- **刻意不在这里跨 schema 读 gateway.video_effective_cost。**
    -- 平台与网关是两个 schema、两条独立的 Flyway 历史，而
    -- PlatformMigrationTest 有一条断言要求 platform 角色**看不见** gateway
    -- 里面的东西（`assertThat(tableNames("gateway")).isEmpty()`）。
    -- 一条跨 schema 的读会让本迁移在平台测试里以权限错误失败，
    -- 而那正是那条隔离断言存在的意义——所以这里只断言平台自己那份数字。
    --
    -- 两侧数字的一致性由**运行时**保证而不是由迁移保证：
    -- 发布守卫每次创建与发布版本时都经
    -- /internal/prices/effective-unit-cost 从网关实时取成本，
    -- 平台存的这两列只是给人看的记录（V54 的注释已经说明这一点）。
    --
    -- 视频视图透传存储值，而存储值已经是含重试的 53，因此这里断言 53；
    -- 写成 50 会让控制台显示的成本比网关实际结算所用的低 3 分/秒。
    -- （音频那边存的是 ceil 之后的值，两者本来就该不同：视图算法不同。）
    IF actual_cost <> 53 THEN
        RAISE EXCEPTION
            'video_minimax_platform_cost_must_match_the_gateway_stored_cost:actual=%',
            actual_cost;
    END IF;

    -- 同一个别名不得同时登记两个维度：那会让平台对它是按秒还是按积分卖
    -- 给出两个答案，而套餐发布守卫只会读到其中一行。
    SELECT count(*) INTO broken
    FROM platform.model_usage_dimension dimension
    WHERE dimension.model_code = 'video-minimax';
    IF broken <> 1 THEN
        RAISE EXCEPTION 'video_minimax_must_have_exactly_one_read_model_row:count=%', broken;
    END IF;
END $$;

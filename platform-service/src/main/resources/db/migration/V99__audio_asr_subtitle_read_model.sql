-- 天翼时间戳字幕别名（audio-asr-subtitle）的平台侧登记。
--
-- 网关 V96 新建了这个别名，并把 ASR 换回了天翼。平台需要知道它是**按秒卖**的，
-- 否则会把它的套餐当成 token 套餐发布，然后在发放时被网关的权益触发器拒绝——
-- V83:15-19 为图片、V97 为音频写过完全相同的理由。
--
-- 为什么它必须单独登记，而不是复用 audio-asr-basic 行：
-- 一个别名一个维度一行，套餐版本是按 model_code 引用别名的（V66 起）。
-- 少了这一行，audio-asr-subtitle 的套餐在平台侧"查无维度"，
-- 而那种缺失不会让任何东西失败，只会让该别名在被发布时静默通过。
--
-- 价格两列是对网关价目表所含内容的记录，不是套餐定价的依据：发布校验从网关的
-- /internal/prices/effective-unit-cost 读取实时数值（V54 的注释说明了这一点）。
-- 天翼与百炼同价（0.00022 元/秒），因此这两列与 audio-asr-basic 相同。
--
-- 版本号 91：V98 是天翼视频读模型，V97 是音频读模型。
--
-- 设计：docs/superpowers/specs/2026-09-30-audio-and-tianyi-model-onboarding-design.md

INSERT INTO platform.model_usage_dimension(
    model_code, usage_dimension, resolution_tier, retail_unit_price,
    effective_unit_cost, price_version)
VALUES
    ('audio-asr-subtitle', 'AUDIO_SECOND', '', 5, 2, 'audio-v1')
ON CONFLICT (model_code) DO UPDATE SET
    usage_dimension = EXCLUDED.usage_dimension,
    resolution_tier = EXCLUDED.resolution_tier,
    retail_unit_price = EXCLUDED.retail_unit_price,
    effective_unit_cost = EXCLUDED.effective_unit_cost,
    price_version = EXCLUDED.price_version,
    synced_at = now();

-- ---------------------------------------------------------------------------
-- 断言
--
-- 一个直接写入受约束表的迁移必须自己证明写入合法（V83:87-91 的同一做法），
-- 并且必须证明**镜像的数值与网关实际会给出的那一个一致**——两张表给出不同成本
-- 基准时，控制台显示的数字与结算所用的数字会分家，而那种分家不会让任何东西失败。
--
-- 这里刻意**不跨 schema 读** gateway.audio_effective_cost：平台与网关是两个 schema、
-- 两条独立的 Flyway 历史，而 PlatformMigrationTest 有一条断言要求 platform 角色
-- 看不见 gateway 里面的东西。两侧的一致性由运行时经
-- /internal/prices/effective-unit-cost 保证。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    actual_dim    varchar(20);
    actual_tier   varchar(16);
    actual_retail bigint;
    actual_cost   bigint;
    actual_version varchar(64);
    broken        integer;
BEGIN
    SELECT dimension.usage_dimension, dimension.resolution_tier,
           dimension.retail_unit_price, dimension.effective_unit_cost,
           dimension.price_version
    INTO actual_dim, actual_tier, actual_retail, actual_cost, actual_version
    FROM platform.model_usage_dimension dimension
    WHERE dimension.model_code = 'audio-asr-subtitle';

    IF actual_dim IS NULL THEN
        RAISE EXCEPTION 'audio_asr_subtitle_was_not_registered_in_the_platform_read_model';
    END IF;

    IF actual_dim <> 'AUDIO_SECOND' THEN
        RAISE EXCEPTION
            'audio_asr_subtitle_must_be_registered_as_a_second_dimension_alias:actual=%',
            actual_dim;
    END IF;

    -- 音频的单位就是秒，没有尺寸或分辨率阶梯，因此档位为空（与 TOKEN 行同形）。
    IF actual_tier IS DISTINCT FROM '' THEN
        RAISE EXCEPTION 'audio_asr_subtitle_tier_must_be_empty:actual=%', actual_tier;
    END IF;

    -- 零售 5 分/秒、有效成本 2 分/秒：与 audio-asr-basic 逐字相同，因为两台平台同价。
    -- 数字的来历（含 bigint 取整那一步）见网关 V101/V96 的说明。
    IF actual_retail <> 5 OR actual_cost <> 2 THEN
        RAISE EXCEPTION
            'audio_asr_subtitle_must_mirror_the_gateway_rate_card:retail=%:cost=%',
            actual_retail, actual_cost;
    END IF;

    IF actual_version <> 'audio-v1' THEN
        RAISE EXCEPTION 'audio_asr_subtitle_price_version_must_stay_audio_v1:actual=%',
            actual_version;
    END IF;

    -- 同一个别名不得同时登记两个维度：那会让平台对它是按秒还是按积分卖给出
    -- 两个答案，而套餐发布守卫只会读到其中一行。
    SELECT count(*) INTO broken
    FROM platform.model_usage_dimension dimension
    WHERE dimension.model_code = 'audio-asr-subtitle';
    IF broken <> 1 THEN
        RAISE EXCEPTION 'audio_asr_subtitle_must_have_exactly_one_read_model_row:count=%',
            broken;
    END IF;
END $$;

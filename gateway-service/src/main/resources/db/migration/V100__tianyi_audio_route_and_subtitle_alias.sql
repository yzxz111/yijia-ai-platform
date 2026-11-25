-- 天翼音频渠道：把 ASR 换回需求指定的平台，并补上"带时间戳字幕"的别名。
--
-- 背景与事实来源（见设计文档 "ASR platform attribution: resolved"）：运维给出了
-- 天翼控制台上可直接运行的 curl，证明天翼网关跑的是**同一套 DashScope 协议**：
--   同步 ASR   POST {root}/services/aigc/multimodal-generation/generation
--   异步提交   POST {root}/services/audio/asr/transcription
--   任务查询   GET  {root}/tasks/{task_id}
-- 请求体、Authorization / X-DashScope-Async / X-DashScope-SSE 三个头都与百炼逐字
-- 相同，价格也都是 0.00022 元/秒。**唯一差别是版本根**：天翼是 /v1，百炼是 /api/v1。
-- 适配器已改为从渠道 base_url 推导版本根，因此这里只登记，不写第二份协议实现。
--
-- 为什么有两件事必须一起做：
--   1. 需求指定 qwen-audio-3.0-asr-flash 走**天翼云**，V97 当初注册在百炼
--      （当时天翼文档抓不到）。这里把它换回来，并**删掉百炼那条路由**，
--      使计费归属确定——留两条做故障切换会让路由挑中百炼，归属就不确定了。
--   2. 需求要的是"提取口播、**生成带时间戳的字幕**"，而同步的 -flash 模型
--      **不返回任何时间戳**。产出句子级时间戳的是异步的 -filetrans 模型，
--      因此它需要自己的别名：文档要求的那个模型名无法交付字幕。
--
-- 设计：docs/superpowers/specs/2026-09-30-audio-and-tianyi-model-onboarding-design.md
-- 参照：gateway V90（天翼视频渠道，同形）、V97（音频目录与费率卡，同形）

-- ---------------------------------------------------------------------------
-- 1. 天翼音频渠道
--
-- 不播种任何凭据，与 V71/V81/V90/V97 完全同形：渠道以 enabled = false /
-- last_health_status = 'UNKNOWN' 注册，运维填 key 并通过健康检查后才启用。
--
-- **base_url 必须存到 /v1。** 这是唯一区分两个平台的地方：适配器从它推导版本根，
-- 存成裸域名会回退到百炼口径（/api/v1）并拼出
-- https://ai.ctaigw.cn/api/v1/services/... —— 那条路径在天翼网关上不存在。
-- 下面的断言把这个要求钉死。
-- ---------------------------------------------------------------------------

INSERT INTO gateway.channel(
    id, provider_id, code, name, environment, base_url, enabled, priority, weight,
    timeout_ms, requests_per_minute, max_concurrency, last_health_status, settings)
SELECT
    '84000000-0000-4000-8000-000000000076', provider.id,
    'tianyi-audio-primary', '天翼云音频主渠道', 'PRODUCTION',
    'https://ai.ctaigw.cn/v1',
    false, 100, 100, 60000, 20, 4, 'UNKNOWN',
    '{"managed":true,"credentialOwner":"system-gateway"}'::jsonb
FROM gateway.provider provider
WHERE provider.code = 'tianyi'
  AND provider.provider_type = 'TIANYIYUN'
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. 时间戳字幕别名
--
-- 与 audio-asr-basic 同为 AUDIO_TRANSCRIBE 能力（客户端拿到的都是转写结果，
-- 区别只在结果里有没有句子级时间戳），因此两者共用同一份转写契约与同一个
-- AUDIO_SECOND 维度；它不需要新维度、新表或新计费口径。
-- ---------------------------------------------------------------------------

INSERT INTO gateway.model(
    id, code, display_name, description, capabilities,
    context_window, max_output_tokens, premium, enabled,
    requests_per_minute, max_concurrency, routing_strategy, routing_policy)
VALUES
    ('82000000-0000-4000-8000-000000000123', 'audio-asr-subtitle', '语音识别·字幕版',
     '提取口播并产出带时间戳的句子级字幕，按音频秒计费', '["AUDIO_TRANSCRIBE"]',
     1, 1, false, true, 60, 4, 'DIRECT', '{}'::jsonb)
ON CONFLICT (code) DO NOTHING;

INSERT INTO gateway.model_alias(id, model_id, alias)
SELECT alias_row.id, model.id, model.code
FROM (VALUES
    ('83000000-0000-4000-8000-000000000123'::uuid, 'audio-asr-subtitle')
) AS alias_row(id, model_code)
JOIN gateway.model model ON model.code = alias_row.model_code
ON CONFLICT (alias) DO NOTHING;

INSERT INTO gateway.audio_alias_tier(model_code, billing_unit)
VALUES ('audio-asr-subtitle', 'SECOND')
ON CONFLICT (model_code) DO UPDATE SET
    billing_unit = EXCLUDED.billing_unit,
    registered_at = now();

-- 原说明把"带时间戳字幕"写在了同步别名上，而同步端点根本没有时间戳。
-- 那句话会让人以为 audio-asr-basic 能出字幕，从而不去找真正的字幕别名。
UPDATE gateway.model
SET description = '提取口播文本（同步端点，不返回时间戳；需要带时间戳的字幕请用 audio-asr-subtitle）'
WHERE code = 'audio-asr-basic';

-- ---------------------------------------------------------------------------
-- 3. 路由
--
-- 先删百炼那条 ASR 路由：**换回天翼就要换干净**。留两条会变成故障切换对，
-- 而路由挑中哪一条决定了这次调用计在哪个平台上——需求要的是归属确定。
-- 百炼渠道本身保留：TTS 与 videoretalk 仍由它履约（需求指定它们是百炼北京）。
-- ---------------------------------------------------------------------------

DELETE FROM gateway.channel_model mapping
USING gateway.channel channel, gateway.model model
WHERE mapping.channel_id = channel.id
  AND mapping.model_id = model.id
  AND channel.code = 'bailian-audio-primary'
  AND model.code = 'audio-asr-basic';

-- 主键用 gen_random_uuid()，与 V97 同一理由：硬编码 UUID 会与运维在控制台
-- 手工建的路由主键撞车（沙箱用生产快照演练时，V97 的 …081/…082 就是这么撞上的）。
-- 幂等性由 (channel_id, model_id) 的 ON CONFLICT DO NOTHING 保证。
INSERT INTO gateway.channel_model(
    id, channel_id, model_id, upstream_model, enabled, capabilities)
SELECT gen_random_uuid(), channel.id, model.id, route.upstream_model, true,
       route.capabilities::jsonb
FROM (VALUES
    ('audio-asr-basic', 'qwen-audio-3.0-asr-flash', '["AUDIO_TRANSCRIBE"]'),
    ('audio-asr-subtitle', 'qwen-audio-3.0-asr-flash-filetrans', '["AUDIO_TRANSCRIBE"]')
) AS route(model_code, upstream_model, capabilities)
JOIN gateway.channel channel
  ON channel.code = 'tianyi-audio-primary'
JOIN gateway.model model
  ON model.code = route.model_code
ON CONFLICT (channel_id, model_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. 费率行
--
-- 天翼的价格与百炼**完全相同**（0.00022 元/秒，两个模型同价），因此这里不引入
-- 新的成本口径：与 V97 一致地存 1 分/秒（真实原价 0.022 分/秒低于 bigint 分辨率，
-- 取整到 1；理由与推演见 V97 的说明），零售 5 分/秒是产品决定。
-- provider_unit_cost 按音频维度存**厂商原价**（视图自己乘 (1 + retry_rate)），
-- 与视频维度相反——这条差异在 V96/V97 里都写过，这里照抄。
-- ---------------------------------------------------------------------------

INSERT INTO gateway.model_usage_price(
    id, model_code, usage_dimension, resolution_tier,
    minimum_units, minimum_charge, unit_price,
    provider_unit_cost, retry_rate, target_margin_ratio, min_absolute_margin,
    price_version, effective_from, official_source)
VALUES
    ('86000000-0000-4000-8000-000000000140', 'audio-asr-subtitle', 'AUDIO_SECOND', '',
     1, 5, 5, 1, 0.05000, 0.30000, 2, 'audio-v1', now(),
     'https://www.ctyun.cn/document/11061839/11099407 '
     '(天翼云星辰TokenHub 非实时语音识别; 音频时长 0.00022 CNY/second, 与百炼同价)')
ON CONFLICT (model_code, usage_dimension, resolution_tier, price_version) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 5. 断言
--
-- 一个直接写入受约束表的迁移必须自己证明写入合法（V83:87-91 的同一做法），
-- 并且要把"换干净了"这件事钉住——删漏一条路由不会有任何东西失败，
-- 只会让这次调用悄悄计到另一个平台上。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    actual_base_url      text;
    actual_provider_type varchar(32);
    actual_enabled       boolean;
    actual_routes        integer;
    actual_upstream      varchar(128);
    actual_unit          varchar(16);
    actual_tier          varchar(16);
    actual_retail        bigint;
    actual_cost          bigint;
    actual_retry         numeric(8, 5);
    actual_floor_ratio   bigint;
    actual_floor_abs     bigint;
BEGIN
    -- 5.1 渠道：必须是天翼，且 base_url 必须带 /v1
    SELECT channel.base_url, provider.provider_type, channel.enabled
    INTO actual_base_url, actual_provider_type, actual_enabled
    FROM gateway.channel channel
    JOIN gateway.provider provider ON provider.id = channel.provider_id
    WHERE channel.code = 'tianyi-audio-primary';

    IF actual_base_url IS NULL THEN
        RAISE EXCEPTION 'tianyi_audio_channel_missing';
    END IF;
    IF actual_provider_type <> 'TIANYIYUN' THEN
        RAISE EXCEPTION 'tianyi_audio_channel_has_the_wrong_provider_type:actual=%',
            actual_provider_type;
    END IF;
    -- 版本根存错会让每一次调用打到一条不存在的路径上。
    IF actual_base_url <> 'https://ai.ctaigw.cn/v1' THEN
        RAISE EXCEPTION
            'tianyi_audio_channel_base_url_must_end_at_the_v1_root:actual=%',
            actual_base_url;
    END IF;
    IF actual_enabled THEN
        RAISE EXCEPTION 'tianyi_audio_channel_must_start_disabled_until_a_key_is_stored';
    END IF;

    -- 5.2 ASR 换干净了：有且只有一条路由，且在天翼渠道上
    SELECT count(*), max(mapping.upstream_model)
    INTO actual_routes, actual_upstream
    FROM gateway.channel_model mapping
    JOIN gateway.model model ON model.id = mapping.model_id
    WHERE model.code = 'audio-asr-basic';

    IF actual_routes <> 1 THEN
        RAISE EXCEPTION 'audio_asr_basic_must_have_exactly_one_route:actual=%',
            actual_routes;
    END IF;
    IF actual_upstream <> 'qwen-audio-3.0-asr-flash' THEN
        RAISE EXCEPTION 'audio_asr_basic_routes_to_the_wrong_upstream:actual=%',
            actual_upstream;
    END IF;
    IF EXISTS (
            SELECT 1
            FROM gateway.channel_model mapping
            JOIN gateway.channel channel ON channel.id = mapping.channel_id
            JOIN gateway.model model ON model.id = mapping.model_id
            WHERE model.code = 'audio-asr-basic'
              AND channel.code = 'bailian-audio-primary') THEN
        RAISE EXCEPTION 'the_bailian_asr_route_was_not_removed';
    END IF;

    -- 5.3 字幕别名：唯一的异步路由，指向 -filetrans（只有它给时间戳）
    SELECT count(*), max(mapping.upstream_model)
    INTO actual_routes, actual_upstream
    FROM gateway.channel_model mapping
    JOIN gateway.model model ON model.id = mapping.model_id
    JOIN gateway.channel channel ON channel.id = mapping.channel_id
    WHERE model.code = 'audio-asr-subtitle'
      AND channel.code = 'tianyi-audio-primary';

    IF actual_routes <> 1 THEN
        RAISE EXCEPTION 'audio_asr_subtitle_must_have_exactly_one_route:actual=%',
            actual_routes;
    END IF;
    IF actual_upstream <> 'qwen-audio-3.0-asr-flash-filetrans' THEN
        RAISE EXCEPTION
            'audio_asr_subtitle_must_use_the_filetrans_model_which_returns_timestamps:actual=%',
            actual_upstream;
    END IF;

    -- 5.4 字幕别名按秒卖，且费率行满足两个下限
    SELECT tier.billing_unit INTO actual_unit
    FROM gateway.audio_alias_tier tier
    WHERE tier.model_code = 'audio-asr-subtitle';
    IF actual_unit <> 'SECOND' THEN
        RAISE EXCEPTION 'audio_asr_subtitle_billing_unit_must_be_SECOND:actual=%',
            actual_unit;
    END IF;

    SELECT price.resolution_tier, price.unit_price, price.provider_unit_cost,
           price.retry_rate
    INTO actual_tier, actual_retail, actual_cost, actual_retry
    FROM gateway.model_usage_price price
    WHERE price.model_code = 'audio-asr-subtitle'
      AND price.usage_dimension = 'AUDIO_SECOND'
      AND price.effective_to IS NULL;

    IF actual_tier IS DISTINCT FROM '' THEN
        RAISE EXCEPTION 'audio_asr_subtitle_tier_must_be_empty_like_TOKEN_rows:actual=%',
            actual_tier;
    END IF;

    -- 与 V97 同一条重算：两个下限都拿**视图的** effective 值算。
    actual_cost := ceil(actual_cost::numeric * (1 + actual_retry))::bigint;
    actual_floor_ratio := ceil(actual_cost::numeric / (1 - 0.30000));
    actual_floor_abs := actual_cost + 2;
    IF actual_retail < actual_floor_ratio OR actual_retail < actual_floor_abs THEN
        RAISE EXCEPTION
            'audio_asr_subtitle_violates_a_margin_floor:retail=%:effective=%:ratio_floor=%:absolute_floor=%',
            actual_retail, actual_cost, actual_floor_ratio, actual_floor_abs;
    END IF;

    -- 5.5 音频目录现在有四个别名（V97 的三个 + 字幕版）
    SELECT count(*) INTO actual_routes
    FROM gateway.audio_alias_tier tier;
    IF actual_routes <> 4 THEN
        RAISE EXCEPTION 'audio_catalogue_must_now_seed_exactly_four_aliases:actual=%',
            actual_routes;
    END IF;
END $$;

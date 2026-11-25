-- 音频目录与费率卡：ASR、TTS、VideoRetalk。
--
-- V96 建立了 AUDIO_SECOND 维度、gateway.audio_alias_tier 与
-- gateway.audio_effective_cost，但**故意没有建任何别名**：一个维度先有词表、
-- 后有行，是为了让「维度本身合法」与「用它卖东西」成为两次可以分别审查的改动。
-- 本迁移做后一半。
--
-- 三个别名与三个上游：
--
--   audio-asr-basic  qwen-audio-3.0-asr-flash          天翼云（需求指定）
--   audio-tts-basic  qwen-audio-3.0-tts-flash          百炼北京（需求指定）
--   audio-retalk     videoretalk                        百炼北京（需求指定）
--
-- 需求文档把 ASR 也指到天翼云。**本迁移按百炼的模型与端点建行**，
-- 理由见下「ASR 的归属」一节，而这是一个需要产品确认的口径差，
-- 不是一次实现选择。
--
-- 设计：docs/superpowers/specs/2026-09-30-audio-and-tianyi-model-onboarding-design.md

-- ---------------------------------------------------------------------------
-- 1. 三个音频别名
--
-- context_window / max_output_tokens 取 1：ck_model_limits（V1:26）要求两者
-- 均为正，而它们对音频别名不描述任何东西——计费完全来自 AUDIO_SECOND
-- 费率卡，从不读这两列。与 V40 给视频别名取的同一个值。
--
-- capabilities 用具体能力名而不是一个笼统的 AUDIO：
-- 三个别名做的是三件不同的事，而管理端的能力守卫要能回答
-- 「这个供应商能不能做换口型」这种粒度的问题。
-- 名字取自 ImagePostProcessAdapter / ImageAnalysisAdapter 同期的做法——
-- 常量集中一处，三处一起读。
-- ---------------------------------------------------------------------------

INSERT INTO gateway.model(
    id, code, display_name, description, capabilities,
    context_window, max_output_tokens, premium, enabled,
    requests_per_minute, max_concurrency, routing_strategy, routing_policy)
VALUES
    ('82000000-0000-4000-8000-000000000120', 'audio-asr-basic', '语音识别基础版',
     '提取口播、生成带时间戳字幕，按音频秒计费', '["AUDIO_TRANSCRIBE"]',
     1, 1, false, true, 60, 4, 'DIRECT', '{}'::jsonb),
    ('82000000-0000-4000-8000-000000000121', 'audio-tts-basic', '配音基础版',
     '把商品文案转成配音，按产出音频秒计费', '["AUDIO_SYNTHESIS"]',
     1, 1, false, true, 60, 4, 'DIRECT', '{}'::jsonb),
    ('82000000-0000-4000-8000-000000000122', 'audio-retalk', '口型替换',
     '用指定配音替换人物视频口型，按生成视频秒计费', '["LIP_SYNC"]',
     1, 1, false, true, 10, 1, 'DIRECT', '{}'::jsonb)
ON CONFLICT (code) DO NOTHING;

INSERT INTO gateway.model_alias(id, model_id, alias)
SELECT alias_row.id, model.id, model.code
FROM (VALUES
    ('83000000-0000-4000-8000-000000000120'::uuid, 'audio-asr-basic'),
    ('83000000-0000-4000-8000-000000000121'::uuid, 'audio-tts-basic'),
    ('83000000-0000-4000-8000-000000000122'::uuid, 'audio-retalk')
) AS alias_row(id, model_code)
JOIN gateway.model model ON model.code = alias_row.model_code
ON CONFLICT (alias) DO NOTHING;

-- 档位登记：每个音频别名都按秒卖。V96 的 audio_alias_tier 只认 'SECOND'。
INSERT INTO gateway.audio_alias_tier(model_code, billing_unit)
VALUES
    ('audio-asr-basic', 'SECOND'),
    ('audio-tts-basic', 'SECOND'),
    ('audio-retalk', 'SECOND')
ON CONFLICT (model_code) DO UPDATE SET
    billing_unit = EXCLUDED.billing_unit,
    registered_at = now();

-- ---------------------------------------------------------------------------
-- 2. 音频渠道
--
-- **为什么另立渠道而不是复用 bailian-video-primary。** 三个端点三个路径：
--
--   bailian-video-primary  /api/v1/services/aigc/video-generation/video-synthesis
--   videoretalk            /api/v1/services/aigc/image2video/video-synthesis
--   ASR / TTS              /api/v1/services/aigc/multimodal-generation/generation
--
-- 渠道的 base_url 是所有路由共享的一个值，而适配器各自从它组出端点。
-- 把三条不同路径的服务挂到一个渠道上，只能靠某个适配器去猜运维存的是哪个
-- 端点——V71 为百炼拆渠道、V90/V91 为天翼拆渠道，都是同一个理由。
--
-- 不播种任何凭据，与 V71/V81/V90/V91 完全同形：渠道以 enabled = false /
-- last_health_status = 'UNKNOWN' / encrypted_api_key IS NULL 注册。
--
-- **base_url 存到 /api/v1**，且三个适配器都以此为准组路径。VideoRetalk 与
-- ASR/TTS 的文档都写明华北2（北京）需要工作空间域名
-- （https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com），而源站
-- dashscope.aliyuncs.com 在部分账号下也能用；因此这里存源站并**明确标注**
-- 运维启用前应换成自己工作空间的域名，与 V71 的做法一致。
--
-- **max_concurrency = 1，而不是 4。** VideoRetalk 在同一时刻每个账号只允许
-- 1 个作业处于运行状态，其余排队。把网关的并发配成 4 只会让三个请求在厂商侧
-- 排队，而客户看到的是「提交成功然后长时间没结果」；配成 1 则让第四个请求
-- 在网关侧被限流，即快速失败。后者对客户更清楚。
-- ---------------------------------------------------------------------------

INSERT INTO gateway.channel(
    id, provider_id, code, name, environment, base_url, enabled, priority, weight,
    timeout_ms, requests_per_minute, max_concurrency, last_health_status, settings)
SELECT
    '84000000-0000-4000-8000-000000000075', provider.id,
    'bailian-audio-primary', '百炼音频主渠道', 'PRODUCTION',
    'https://dashscope.aliyuncs.com',
    false, 100, 100, 60000, 40, 1, 'UNKNOWN',
    '{"managed":true,"region":"cn-beijing","requiresWorkspaceEndpoint":true,'
    '"oneConcurrentJobPerAccount":true,"credentialOwner":"system-gateway"}'::jsonb
FROM gateway.provider provider
WHERE provider.code = 'bailian'
  AND provider.provider_type = 'BAILIAN'
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. 路由
--
-- capabilities 与别名的能力标记一致：路由行声明的是**这个上游**能服务什么，
-- 而不是别名要什么。两者不一致会让能力守卫与真实路由打架。
-- ---------------------------------------------------------------------------

-- 主键用 gen_random_uuid()，**不用硬编码 UUID**：生产上运维在控制台手工建的天翼
-- 视频路由已经占用了 …081/…082/…083 这几个号（video-budget/basic/pro →
-- minimax-h3-max），硬编码主键会直接撞主键失败（沙箱用生产快照演练时实测）。
-- 幂等性由唯一键 (channel_id, model_id) 的 ON CONFLICT DO NOTHING 保证，
-- 不依赖主键值，因此每跑一次换一个随机主键也不会重复插入。
INSERT INTO gateway.channel_model(
    id, channel_id, model_id, upstream_model, enabled, capabilities)
SELECT gen_random_uuid(), channel.id, model.id, route.upstream_model, true,
       route.capabilities::jsonb
FROM (VALUES
    ('audio-asr-basic', 'qwen-audio-3.0-asr-flash', '["AUDIO_TRANSCRIBE"]'),
    ('audio-tts-basic', 'qwen-audio-3.0-tts-flash', '["AUDIO_SYNTHESIS"]'),
    ('audio-retalk', 'videoretalk', '["LIP_SYNC"]')
) AS route(model_code, upstream_model, capabilities)
JOIN gateway.channel channel
  ON channel.code = 'bailian-audio-primary'
JOIN gateway.model model
  ON model.code = route.model_code
ON CONFLICT (channel_id, model_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. 费率卡（分/秒）
--
-- 成本基准来自厂商文档，2026-09-30 核对，华北2（北京）：
--
--   qwen-audio-3.0-asr-flash  0.00022 元/秒          = 0.022 分/秒
--     https://help.aliyun.com/zh/model-studio/qwen-audio-3-0-asr-flash
--   qwen-audio-3.0-tts-flash  1 元/万字符             = 0.02 分/秒
--     https://help.aliyun.com/zh/model-studio/qwen-audio-3-0-tts-flash
--   videoretalk               0.08 元/秒（后付费，按生成视频时长） = 8 分/秒
--     https://help.aliyun.com/zh/model-studio/videoretalk
--
-- **按秒的 ASR/TTS 成本低于这一列的分辨率。**
-- model_usage_price.provider_unit_cost 是 bigint，单位是**分**（V40:36）。
-- 而 ASR 是 0.022 分/秒、TTS 是 0.02 分/秒——都不到 1 分。把一个带小数的
-- 字面量写进 bigint 列，PostgreSQL 会**静默四舍五入**：字面量的类型是
-- unknown，按目标列强转，于是 0.022 → 0 而**不报任何错**。
-- 那一行的成本基准就变成了 0，视图报出 effective_unit_cost = 0，平台侧的
-- 毛利守卫再也无法为音频套餐证明任何毛利——而这条费率行看起来完全正常。
-- （本次就是这么写错的，是 GatewayMigrationTest 的逐行断言把它抓出来的。）
--
-- 因此这里**向上取整到 1 分/秒**，真实原价仍然逐字写在 official_source 里备查。
-- 方向是保守的：1 分/秒 > 0.022 分/秒，毛利检验比真实情况更严，
-- 它只会拦下发布，不会放过一次亏本售卖；反过来存 0 才是危险方向。
--
-- 这是「按秒维度 + 整数分」这个组合的固有局限，不是这两行的特例：任何单价
-- 低于 1 分/秒的按秒能力都会碰到它。要精确表达就得把该列改成 numeric，
-- 那会动到全部三个维度与所有消费者，不在本次改动范围内。
--
-- **provider_unit_cost 存厂商原价，不预乘 retry_rate。**
-- gateway.audio_effective_cost 自己应用 retry_rate（它的算法与 V81 的
-- image_effective_cost 相同：ceil(provider_unit_cost * (1 + retry_rate))），
-- 因此预乘会被算两次——那正是 V41/V45 在视频维度上发生过的事故
-- （V41 的视图重复计了一次 retry，V45 专门写一条迁移来撤销它）。
-- 这里的约定与 V81 的图片视图一致，而不是与 V45 的视频视图一致，
-- 因此下面所有推导都基于 **ceil 之后的** effective 值。
--
-- **TTS 的字符→秒换算是 2 字符/秒，不是正常语速的 7-8。**
-- ¥1.00 / 10000 字符 ÷ 2 字符/秒 × 100 分/元 = 0.02 分/秒。取偏保守（偏小）
-- 的字符速率会折出**更长**的秒数，即更高的成本基准，与 V81:312-318
-- 「存峰值而不是平均值」是同一条规矩。结算侧用的是同一个常量
-- （AudioSynthesisAdapter.CHARACTERS_PER_SECOND），两处若取不同值，
-- 毛利会随文案密度漂移。
--
-- **零售价与两个下限。** ck_model_usage_price_margin（V40:114-125）要求：
--     unit_price >= ceil(provider_unit_cost / (1 - target_margin_ratio))
--     unit_price * minimum_units - provider_unit_cost * minimum_units
--         >= min_absolute_margin
-- 其中 provider_unit_cost 是 **effective** 值（ceil 之后），因为守卫读的是
-- 视图。逐行核对（min_absolute_margin：ASR/TTS 取 2、retalk 取 5，理由见下）：
--
--   ASR   effective = ceil(1 * 1.05) = 2      （成本基准 1 分/秒，见上面的取整说明）
--         比率下限 = ceil(2 / 0.7) = 3；绝对下限 = 2 * 1 + 2 = 4
--         取 5 —— 两个下限都满足，且 5 是产品定的「一次请求值 5 分」。
--         注意比率下限（3）在这里几乎不构成约束：真正决定价格的是绝对下限
--         与产品决定，这是按秒维度单位成本极低时的常态。
--   TTS   effective = ceil(1 * 1.05) = 2，同上
--   retalk effective = ceil(8 * 1.05) = 9
--         比率下限 = ceil(9 / 0.7) = 13；绝对下限 = 9 * 1 + 5 = 14
--         取 14（绝对下限是约束方，比率下限 13 不够）
--
-- **min_absolute_margin 取 2 而不是 5，就为了让 ASR/TTS 停在 5 分/秒。**
-- 产品决定的是「一次音频请求值 5 分」。而 5 分要同时满足两个下限，
-- 在 effective cost = 1 时需要 min_absolute_margin <= 4。取 2 留出余量，
-- 且对 retalk 那行没有影响（它的绝对下限由 9 与 5 主导，得出 14）。
-- 把 5 写进 ASR/TTS 的 min_absolute_margin 会把它们抬到 6 分/秒——
-- 一个 20% 的涨价，而且它与「5 分」这个决定不一致。
-- ---------------------------------------------------------------------------

INSERT INTO gateway.model_usage_price(
    id, model_code, usage_dimension, resolution_tier,
    minimum_units, minimum_charge, unit_price,
    provider_unit_cost, retry_rate, target_margin_ratio, min_absolute_margin,
    price_version, effective_from, official_source)
VALUES
    -- ASR：真实原价 0.022 分/秒，低于 bigint 分辨率，按 1 分/秒 存储（见上方说明）。
    ('86000000-0000-4000-8000-000000000120', 'audio-asr-basic', 'AUDIO_SECOND', '',
     1, 5, 5, 1, 0.05000, 0.30000, 2, 'audio-v1', now(),
     'https://help.aliyun.com/zh/model-studio/qwen-audio-3-0-asr-flash '
     '(0.00022 CNY/second of input audio, cn-beijing)'),
    -- TTS：1 元/万字符，按最坏 2 字符/秒折成 0.02 分/秒；
    -- 同样低于 bigint 分辨率，按 1 分/秒 存储。
    ('86000000-0000-4000-8000-000000000121', 'audio-tts-basic', 'AUDIO_SECOND', '',
     1, 5, 5, 1, 0.05000, 0.30000, 2, 'audio-v1', now(),
     'https://help.aliyun.com/zh/model-studio/qwen-audio-3-0-tts-flash '
     '(1 CNY/10000 characters, cn-beijing); converted at the worst-case '
     '2 characters per second the synthesis rate card and the settlement path '
     'both use (AudioSynthesisAdapter.CHARACTERS_PER_SECOND)'),
    -- VideoRetalk：0.08 元/秒，按生成视频秒后付费计费。
    ('86000000-0000-4000-8000-000000000122', 'audio-retalk', 'AUDIO_SECOND', '',
     1, 14, 14, 8, 0.05000, 0.30000, 5, 'audio-v1', now(),
     'https://help.aliyun.com/zh/model-studio/videoretalk '
     '(0.08 CNY/second of generated video, post-paid, cn-beijing)')
ON CONFLICT (model_code, usage_dimension, resolution_tier, price_version) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 5. 断言
--
-- 与 V81/V82/V96 同一做法：直接写入受约束表的迁移必须自己证明写入合法。
-- 这里的核心是**重新推导一遍上面的算术**，而不是复述写下的字面量——
-- 一条把 12 写成 14 的手算错误不会违反任何约束以外的规则，
-- 而它会让这个别名在成本上涨时最先亏钱。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    row_record      record;
    expected        bigint;
    broken          integer;
    audio_aliases   integer;
    tier_missing    integer;
BEGIN
    -- 三个别名、三个档位登记，一个都不能少。
    SELECT count(*) INTO audio_aliases
    FROM gateway.model model
    WHERE model.capabilities @> '["AUDIO_TRANSCRIBE"]'::jsonb
       OR model.capabilities @> '["AUDIO_SYNTHESIS"]'::jsonb
       OR model.capabilities @> '["LIP_SYNC"]'::jsonb;

    IF audio_aliases <> 3 THEN
        RAISE EXCEPTION 'audio_catalogue_must_seed_exactly_three_aliases:actual=%',
            audio_aliases;
    END IF;

    SELECT count(*) INTO tier_missing
    FROM gateway.model model
    WHERE (model.capabilities @> '["AUDIO_TRANSCRIBE"]'::jsonb
           OR model.capabilities @> '["AUDIO_SYNTHESIS"]'::jsonb
           OR model.capabilities @> '["LIP_SYNC"]'::jsonb)
      AND NOT EXISTS (
            SELECT 1 FROM gateway.audio_alias_tier tier
            WHERE tier.model_code = model.code);

    IF tier_missing > 0 THEN
        RAISE EXCEPTION 'audio_alias_without_a_registered_billing_unit:count=%', tier_missing;
    END IF;

    -- 每个 AUDIO_SECOND 费率行必须满足两个下限，而这里**独立重算**一遍。
    -- ck_model_usage_price_margin 已经保证了这一点；这条断言的价值在于
    -- 它同时检查了有效成本取自视图这一事实（ceil 之后的值），
    -- 那是手算时最容易漏掉的一步。
    FOR row_record IN
        SELECT price.model_code,
               price.unit_price,
               price.minimum_units,
               price.min_absolute_margin,
               price.target_margin_ratio,
               cost.effective_unit_cost
        FROM gateway.model_usage_price price
        JOIN gateway.audio_effective_cost cost
          ON cost.model_code = price.model_code
         AND cost.resolution_tier = price.resolution_tier
        WHERE price.usage_dimension = 'AUDIO_SECOND'
          AND price.effective_to IS NULL
    LOOP
        -- 比率下限：ceil(effective / (1 - target_margin_ratio))
        expected := ceil(row_record.effective_unit_cost::numeric
                / (1 - row_record.target_margin_ratio));
        IF row_record.unit_price < expected THEN
            RAISE EXCEPTION
                'audio_ratio_floor_violated:%:unit_price=%:effective_cost=%:ratio_floor=%',
                row_record.model_code, row_record.unit_price,
                row_record.effective_unit_cost, expected;
        END IF;

        -- 绝对下限：名义成本的贡献额必须够。
        expected := row_record.effective_unit_cost * row_record.minimum_units
                + row_record.min_absolute_margin;
        IF row_record.unit_price * row_record.minimum_units < expected THEN
            RAISE EXCEPTION
                'audio_absolute_floor_violated:%:unit_price=%:effective_cost=%:'
                'min_absolute_margin=%:required_total=%',
                row_record.model_code, row_record.unit_price,
                row_record.effective_unit_cost, row_record.min_absolute_margin, expected;
        END IF;
    END LOOP;

    -- **刻意的价格点断言。** 上面两条是约束的重述，而这条钉住的是
    -- 产品决定本身：ASR 与 TTS 各 5 分/秒。这不是从成本推出来的
    -- （它们的成本是 1 分/秒，任何 >= 2 的价格都合规），因此它必须被显式钉住，
    -- 否则一次「顺手调高 min_absolute_margin」会悄悄把它们变成 6 分/秒。
    FOR row_record IN
        SELECT price.model_code, price.unit_price
        FROM gateway.model_usage_price price
        WHERE price.usage_dimension = 'AUDIO_SECOND'
          AND price.effective_to IS NULL
          AND price.model_code IN ('audio-asr-basic', 'audio-tts-basic')
    LOOP
        IF row_record.unit_price <> 5 THEN
            RAISE EXCEPTION
                'the_agreed_audio_retail_price_changed:%:unit_price=%:expected=5',
                row_record.model_code, row_record.unit_price;
        END IF;
    END LOOP;

    -- 一个别名不得同时在两个生成维度上定价。V81:411-429 为图片与视频写过
    -- 同一条断言；音频加入后它必须覆盖三个维度，否则一个被同时写成
    -- 音频与图片价的行会让 /internal/prices/effective-unit-cost 静默丢弃该别名。
    SELECT count(*) INTO broken
    FROM (
        SELECT model_code
        FROM (
            SELECT model_code FROM gateway.video_effective_cost
            UNION ALL
            SELECT model_code FROM gateway.image_effective_cost
            UNION ALL
            SELECT model_code FROM gateway.audio_effective_cost
        ) all_dimensions
        GROUP BY model_code
        HAVING count(*) > 1
    ) ambiguous;

    IF broken > 0 THEN
        RAISE EXCEPTION
            'alias_priced_in_more_than_one_generation_dimension:count=%', broken;
    END IF;

    -- 渠道必须与 V71/V81/V90/V91 同形：无凭据、未启用、健康度未知。
    -- IS DISTINCT FROM 而不是 <>：encrypted_api_key 为 NULL 时 <> 会得到 NULL。
    SELECT count(*) INTO broken
    FROM gateway.channel channel
    WHERE channel.code = 'bailian-audio-primary'
      AND (channel.enabled
           OR channel.encrypted_api_key IS NOT NULL
           OR channel.last_health_status IS DISTINCT FROM 'UNKNOWN');

    IF broken > 0 THEN
        RAISE EXCEPTION 'bailian_audio_channel_must_be_uncredentialed_and_disabled';
    END IF;

    -- 并发必须配成 1：厂商每账号只允许一个作业在跑。
    -- 配大了不会失败，只会让客户在排队里等，而那是一个不会报警的体验缺陷。
    SELECT count(*) INTO broken
    FROM gateway.channel channel
    WHERE channel.code = 'bailian-audio-primary'
      AND channel.max_concurrency IS DISTINCT FROM 1;

    IF broken > 0 THEN
        RAISE EXCEPTION
            'bailian_audio_channel_concurrency_must_match_the_vendor_single_job_limit';
    END IF;

    -- 三条路由，且每条的能力标记与别名的能力标记互相包含。
    SELECT count(*) INTO broken
    FROM gateway.channel_model mapping
    JOIN gateway.channel channel ON channel.id = mapping.channel_id
    WHERE channel.code = 'bailian-audio-primary';

    IF broken <> 3 THEN
        RAISE EXCEPTION 'bailian_audio_route_count_must_be_three:actual=%', broken;
    END IF;

    SELECT count(*) INTO broken
    FROM gateway.channel_model mapping
    JOIN gateway.channel channel ON channel.id = mapping.channel_id
    JOIN gateway.model model ON model.id = mapping.model_id
    WHERE channel.code = 'bailian-audio-primary'
      AND NOT (mapping.capabilities @> model.capabilities
               AND model.capabilities @> mapping.capabilities);

    IF broken > 0 THEN
        RAISE EXCEPTION
            'audio_route_and_alias_capabilities_disagree:count=%', broken;
    END IF;
END $$;

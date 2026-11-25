-- 天翼云 minmax-h3-max 与 Qwen2.5-VL-72B-Instruct 的费率行。
--
-- V90 与 V91 建好了路由与渠道，但**没有播种任何费率行**——那时这两个价格都拿不到，
-- 而凭空写一个成本基数会拿错的数字放行一个亏钱的售价。运营从天翼云控制台抄来了
-- 实测价格，本迁移因此可以落价，并把 V91 那条"证明自己没有写费率行"的断言
-- 反过来。
--
-- ---------------------------------------------------------------------------
-- 运营提供的价格（天翼云控制台计费说明）
-- ---------------------------------------------------------------------------
--
--   minimax-h3-max  480P 有声  0.33 元/秒 = 33 分/秒
--                   720P 有声  0.50 元/秒 = 50 分/秒
--   Qwen2.5-VL-72B-Instruct   输入 4.13 元/百万 = 413 分/百万
--                             输出 4.13 元/百万 = 413 分/百万
--
-- ---------------------------------------------------------------------------
-- 视频：为什么为天翼分镜视频**新建专属别名**，而不是复用既有别名
-- ---------------------------------------------------------------------------
--
-- 曾经的做法是把 minimax-h3-max 作为可路由渠道挂到既有的 video-budget(480P)
-- 与 video-basic(720P) 上。那留下了两个问题：
--
--   1. **定价不可解释**：那条路由的售价是按百炼的第一方价格定的
--      （V40：480P 50 分/秒、720P 100 分/秒 零售；成本 35 / 69 分/秒），
--      而实际可能走天翼的 33 / 50 分/秒。那不是亏损（卖价高于买价），
--      但真实毛利取决于两家上游之间随机的路由选择，运营无法解释。
--   2. **吃掉图生视频**（决定性的一条）：天翼该端点不接受首帧图片，而目录
--      对一个别名的能力报告的是它**全部可达路由的保守交集**。video-basic 一旦
--      多出这条路由，它的 IMAGE_TO_VIDEO 模态就会因交集为空而从目录里消失——
--      客户已经买到的图生视频能力被一条 failover 路由吃掉。
--
-- 因此 V90 那两条映射已被**删除**（见 V90 第 2 节的说明），本迁移改为给天翼
-- 分镜视频**新建一个专属别名**并按天翼实际成本定价。这与 V97 给音频做的、
-- 以及 V81 给生图做的，是同一条规矩：
-- **一个按单位计费的别名，其费率卡应当描述真正在履约的那一家。**
--
-- ---------------------------------------------------------------------------
-- 逐行核对两个下限（ck_model_usage_price_margin，V40:114-125）
-- ---------------------------------------------------------------------------
--
-- 视频（720P，厂商原价 50 分/秒，retry_rate 0.05，min_absolute_margin 30，target 0.30）：
--     存储成本 = ceil(50 * 1.05) = 53 —— **视频维度存的是含重试的有效成本**，
--     因为 gateway.video_effective_cost 把存储值原样透出（V45 头部有完整理由）。
--     图片与音频恰好相反：那两个视图自己乘上 (1 + retry_rate)，因此那边存厂商原价。
--     effective = 53
--     比率下限 = ceil(53 / 0.7) = 76
--     绝对下限 = 53 + 30 = 83
--     取 83 —— 绝对下限是约束方，且 83 * 0.3 = 24.9 > 23 = 83 - 60，
--     因此毛利率 30.1% 越过 30% 的产品保证。
--
-- 视觉（TOKEN，V40 的 token 行口径：target 0.40，min_absolute_margin 0）：
--     输入零售 550 >= 成本 413；输出零售 550 >= 成本 413。
--     550 / 413 = 1.33 倍，毛利 24.9%。这是刻意选的：天翼的
--     Qwen2.5-VL-72B 输入输出同价（4.13 元/百万），而百炼的 vision-basic
--     零售是 22 / 85 分/百万——天翼这条贵得多，因此它不该被当成
--     vision-basic 的替代品，而是一个独立的、只在天翼履约的别名。
--
-- cache 三列必须非空（ck_model_usage_price_fallback），但**取零成本**：
-- 天翼的报价单里没有缓存价，因此缓存命中按未命中计价——把缓存价设成
-- 比输入价更低会是凭空发明一个折扣。
-- ---------------------------------------------------------------------------

-- 天翼分镜视频的专属别名。
INSERT INTO gateway.model(
    id, code, display_name, description, capabilities,
    context_window, max_output_tokens, premium, enabled,
    requests_per_minute, max_concurrency, routing_strategy, routing_policy)
VALUES (
    '82000000-0000-4000-8000-000000000130', 'video-minimax', '分镜视频（MiniMax）',
    '天翼云 MiniMax H3-Max 分镜视频，720P 有声，按秒计费', '["TEXT","IMAGE","VIDEO"]',
    1, 1, false, true, 20, 4, 'DIRECT', '{}'::jsonb)
ON CONFLICT (code) DO NOTHING;

INSERT INTO gateway.model_alias(id, model_id, alias)
SELECT '83000000-0000-4000-8000-000000000130', model.id, model.code
FROM gateway.model model
WHERE model.code = 'video-minimax'
ON CONFLICT (alias) DO NOTHING;

-- 档位登记：720P。天翼那边由适配器翻译成 768P（TianYiYunVideoGenerationAdapter
-- 的 MODEL_RESOLUTIONS），768P 是 720P 的超集，对客户只多不少。
INSERT INTO gateway.video_alias_tier(model_code, resolution_tier)
VALUES ('video-minimax', '720P')
ON CONFLICT (model_code) DO UPDATE SET
    resolution_tier = EXCLUDED.resolution_tier;

-- 路由：挂到一条**已经在承载天翼视频路由**的渠道上。
--
-- 这里原本写死 channel.code = 'tianyi-video-primary'，即 V90 建的那条无凭据、
-- 未启用的占位渠道。那样别名在目录里可见、却永远没有可执行路由（提交 503），
-- 因为生产实际使用的天翼视频渠道是运维在控制台配好的另一条
-- （有凭据、HEALTHY，且已被 video-basic/video-budget/video-pro 引用）。
--
-- 因此改成"挑一条已被至少一个 VIDEO 别名引用的 TIANYIYUN 视频渠道"：
--   * 生产：选中那条真实的、健康的渠道 → video-minimax 立刻可调用；
--   * 全新库/测试库：没有这样的渠道，回退到 V90 的占位渠道 → 迁移照样能跑通，
--     别名可见、路由待运维配置，与其它天翼视频别名的初始状态一致。
-- 用 NOT EXISTS 排序保证有多条时结果稳定（只取第一条）。
-- 主键用 gen_random_uuid() 而不是硬编码 UUID：生产上这些映射是运维在控制台
-- 手工建的，已经占用了 …081/…082/…083 等号；硬编码主键会撞车（沙箱演练实测）。
-- 幂等性由唯一键 (channel_id, model_id) 的 ON CONFLICT DO NOTHING 保证。
INSERT INTO gateway.channel_model(
    id, channel_id, model_id, upstream_model, enabled, capabilities)
SELECT gen_random_uuid(), chosen.id, model.id,
       'minimax-h3-max', true, '["TEXT","IMAGE","VIDEO"]'::jsonb
FROM gateway.model model
CROSS JOIN LATERAL (
    SELECT channel.id
    FROM gateway.channel channel
    JOIN gateway.provider provider ON provider.id = channel.provider_id
    WHERE provider.provider_type = 'TIANYIYUN'
      AND EXISTS (
            SELECT 1
            FROM gateway.channel_model existing
            JOIN gateway.model existing_model ON existing_model.id = existing.model_id
            WHERE existing.channel_id = channel.id
              AND existing_model.capabilities @> '["VIDEO"]'::jsonb)
    ORDER BY channel.enabled DESC, channel.last_health_status = 'HEALTHY' DESC, channel.code
    LIMIT 1
) AS chosen
WHERE model.code = 'video-minimax'
ON CONFLICT (channel_id, model_id) DO NOTHING;

-- 回退：没有任何已承载天翼视频路由的渠道时（全新库），挂到 V90 的占位渠道。
INSERT INTO gateway.channel_model(
    id, channel_id, model_id, upstream_model, enabled, capabilities)
SELECT gen_random_uuid(), channel.id, model.id,
       'minimax-h3-max', true, '["TEXT","IMAGE","VIDEO"]'::jsonb
FROM gateway.channel channel
JOIN gateway.model model ON model.code = 'video-minimax'
WHERE channel.code = 'tianyi-video-primary'
  AND NOT EXISTS (
        SELECT 1
        FROM gateway.channel_model mapping
        JOIN gateway.model mapped ON mapped.id = mapping.model_id
        WHERE mapped.code = 'video-minimax')
ON CONFLICT (channel_id, model_id) DO NOTHING;

-- 视频费率行：厂商原价 0.50 元/秒 = 50 分/秒，含 5% 重试允差后**存 53**。
--
-- 这里曾经写成 50，而两个下限的注释与下面第 176 行的断言都在按 53 计算。
-- 它之所以没有立刻失败：把厂商原价写进视频维度，视图会报 50、两个下限也按
-- 50 算（比率下限 72、绝对下限 80），于是 retail 83 照样通过全部约束——
-- 但真实有效成本是 53，实际毛利率因此只有 (83-53)/83 = 36%，而报价所依据的
-- 前提是 30% 的保证。少算重试允差是 V41→V45 修过的那个缺陷的反方向形态，
-- 两者都源于同一个混淆：**视频维度存含重试值，图片/音频维度存厂商原价。**
INSERT INTO gateway.model_usage_price(
    id, model_code, usage_dimension, resolution_tier,
    minimum_units, minimum_charge, unit_price,
    provider_unit_cost, retry_rate, target_margin_ratio, min_absolute_margin,
    price_version, effective_from, official_source)
VALUES
    ('86000000-0000-4000-8000-000000000130', 'video-minimax', 'VIDEO_SECOND', '720P',
     1, 83, 83, 53, 0.05000, 0.30000, 30, 'video-minimax-v1', now(),
     'tianyi console: minimax-h3-max 720P with audio 0.50 CNY/second '
     '(480P tier is 0.33 CNY/second and is not sold by this alias)')
ON CONFLICT (model_code, usage_dimension, resolution_tier, price_version) DO NOTHING;

-- 视觉费率行：输入输出各 4.13 元/百万 = 413 分/百万。
--
-- 这是 V91 里那个"证明自己没写费率行"的断言的收尾：价格到位了，
-- 因此那一行必须被写入，而 V91 的那条断言已随之改为证明**这一行合规**。
INSERT INTO gateway.model_usage_price(
    id, model_code, usage_dimension, resolution_tier,
    minimum_units, minimum_charge, unit_price,
    input_price_per_million, output_price_per_million,
    cache_hit_price_per_million, cache_write_price_per_million,
    reasoning_price_per_million,
    provider_cost_input_per_million, provider_cost_output_per_million,
    provider_cache_hit_cost_per_million,
    retry_rate, target_margin_ratio, min_absolute_margin,
    price_version, effective_from, official_source)
VALUES
    ('86000000-0000-4000-8000-000000000131', 'vision-tianyi', 'TOKEN', '',
     1, 1, NULL,
     550, 550, 413, 413, 550,
     413, 413, 0,
     0, 0.25000, 0, 'vision-tianyi-v1', now(),
     'tianyi console: Qwen2.5-VL-72B-Instruct 4.13 CNY per million input tokens '
     'and 4.13 CNY per million output tokens')
ON CONFLICT (model_code, usage_dimension, resolution_tier, price_version) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 断言
--
-- 与 V81/V82/V97 同一做法：直接写入受约束表的迁移必须自己证明写入合法，
-- 并且**独立重算**下限，而不是复述写下的字面量。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    row_record record;
    expected   bigint;
    broken     integer;
BEGIN
    -- 视频行：两个下限都独立重算一遍。
    FOR row_record IN
        SELECT price.model_code, price.unit_price, price.minimum_units,
               price.min_absolute_margin, price.target_margin_ratio,
               cost.effective_unit_cost
        FROM gateway.model_usage_price price
        JOIN gateway.video_effective_cost cost
          ON cost.model_code = price.model_code
         AND cost.resolution_tier = price.resolution_tier
        WHERE price.model_code = 'video-minimax'
          AND price.effective_to IS NULL
    LOOP
        expected := ceil(row_record.effective_unit_cost::numeric
                / (1 - row_record.target_margin_ratio));
        IF row_record.unit_price < expected THEN
            RAISE EXCEPTION
                'video_minimax_ratio_floor_violated:unit_price=%:effective=%:floor=%',
                row_record.unit_price, row_record.effective_unit_cost, expected;
        END IF;

        expected := row_record.effective_unit_cost * row_record.minimum_units
                + row_record.min_absolute_margin;
        IF row_record.unit_price * row_record.minimum_units < expected THEN
            RAISE EXCEPTION
                'video_minimax_absolute_floor_violated:unit_price=%:effective=%:required=%',
                row_record.unit_price, row_record.effective_unit_cost, expected;
        END IF;

        -- 有效成本必须是 53 = ceil(50 * 1.05)，即**含** 5% 重试允差的成本。
        -- 视频视图不自己做这一步（它原样透出存储值），因此这个数只能来自
        -- 费率行本身写对了——上面那行曾经写成厂商原价 50，而这张表上的
        -- 任何约束都不会因此失败。这条断言就是为它存在的。
        IF row_record.effective_unit_cost <> 53 THEN
            RAISE EXCEPTION
                'video_minimax_effective_cost_should_be_53:actual=%',
                row_record.effective_unit_cost;
        END IF;
    END LOOP;

    IF NOT EXISTS (
            SELECT 1 FROM gateway.video_effective_cost
            WHERE model_code = 'video-minimax') THEN
        RAISE EXCEPTION 'video_minimax_rate_card_row_missing';
    END IF;

    -- 视觉行：输入与输出零售都不得低于各自的成本。
    SELECT input_price_per_million, output_price_per_million,
           provider_cost_input_per_million, provider_cost_output_per_million
    INTO row_record
    FROM gateway.model_usage_price
    WHERE model_code = 'vision-tianyi'
      AND usage_dimension = 'TOKEN'
      AND effective_to IS NULL;

    IF row_record.input_price_per_million IS NULL THEN
        RAISE EXCEPTION 'vision_tianyi_rate_card_row_missing';
    END IF;

    IF row_record.input_price_per_million < row_record.provider_cost_input_per_million
            OR row_record.output_price_per_million
                < row_record.provider_cost_output_per_million THEN
        RAISE EXCEPTION
            'vision_tianyi_retail_below_cost:in=%/%:out=%/%',
            row_record.input_price_per_million, row_record.provider_cost_input_per_million,
            row_record.output_price_per_million, row_record.provider_cost_output_per_million;
    END IF;

    -- 天翼的两个价格相同，因此零售也必须相同；一个不对等的零售价会让
    -- 同一批 token 的毛利取决于它是输入还是输出，而成本并没有这个区别。
    IF row_record.input_price_per_million <> row_record.output_price_per_million THEN
        RAISE EXCEPTION
            'vision_tianyi_retail_must_be_symmetric_because_its_cost_is:in=%:out=%',
            row_record.input_price_per_million, row_record.output_price_per_million;
    END IF;

    -- 排他性：video-minimax 只能有一个当前费率行，否则
    -- /internal/prices/effective-unit-cost 的 HAVING count(*) = 1 会整体丢弃它，
    -- 平台发布守卫随之以 provider_price_missing fail-closed。
    SELECT count(*) INTO broken
    FROM gateway.model_usage_price
    WHERE model_code = 'video-minimax' AND effective_to IS NULL;
    IF broken <> 1 THEN
        RAISE EXCEPTION 'video_minimax_must_have_exactly_one_current_row:count=%', broken;
    END IF;

    SELECT count(*) INTO broken
    FROM gateway.model_usage_price
    WHERE model_code = 'vision-tianyi' AND effective_to IS NULL;
    IF broken <> 1 THEN
        RAISE EXCEPTION 'vision_tianyi_must_have_exactly_one_current_row:count=%', broken;
    END IF;
END $$;

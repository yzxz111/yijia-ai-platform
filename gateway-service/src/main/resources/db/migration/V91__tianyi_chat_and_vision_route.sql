-- 天翼云视觉理解路由：Qwen2.5-VL-72B-Instruct
--
-- 背景，这是一条**修事故**的迁移。ProviderCapabilityCatalog 的存在理由
-- 就是一次真实事故：运营把对话/视觉渠道挂到了 provider_type = TIANYIYUN
-- 的供应商下。天翼云那把 key 明明能跑对话与读图（厂商侧 /v1/models 里
-- 有 Qwen2.5-VL-72B-Instruct），但 TIANYIYUN 这个类型**只有生图与视频
-- 适配器**，于是每一条聊天请求都在运行时以 503 upstream_unavailable 失败，
-- 而控制台上渠道显示 HEALTHY。
--
-- 当时的修复是**在保存路由时拒绝**这个组合（ProviderCapabilityCatalog
-- .unavailableReason），并有 ProviderCapabilityCatalogTest
-- .tianyiYunCannotServeImageReadingEvenThoughTheKeyCan 与
-- GatewayAdminServiceTest.bulkOnboardingWritesMappingsAndRejectsCapabilityMismatch
-- 两条回归测试钉住它。那是正确的修复：一个没有适配器的能力组合必须
-- 在保存时被拒，而不是等真实请求失败。
--
-- 本次需求要求真正**使用**天翼的 Qwen2.5-VL-72B-Instruct，因此正确的
-- 做法不是放宽校验，而是**补上缺失的适配器**：
-- TianYiYunChatAdapter（provider/ 包）声明 TIANYIYUN 支持 TEXT 与 IMAGE。
-- 适配器 bean 一旦存在，能力矩阵会自动把它读进来——ProviderCapabilityCatalog
-- 从 bean 取能力，不读硬编码表——于是天翼的视觉路由从"必须被拒"
-- 变成"可以挂"。
--
-- 与 V90/V81 同形：**不播种任何凭据**。
--
-- 设计：docs/superpowers/specs/2026-09-30-audio-and-tianyi-model-onboarding-design.md

-- ---------------------------------------------------------------------------
-- 1. 对话/视觉渠道
--
-- 与 tianyi-image-primary、tianyi-video-primary 分开：这三个端点的路径不同
-- （/v1/chat/completions、/v1/images/generations、/v1/video_generation），
-- 而渠道的 base_url 是所有路由共享的一个值。注意本渠道的 base_url
-- **带 /v1**，因为对话适配器拼的是 {base}/chat/completions；
-- 而生图/视频适配器自己会补 /v1。V71 为百炼拆渠道时是同一个理由。
-- ---------------------------------------------------------------------------

-- 渠道：本迁移只在**没有这条渠道时**创建占位（未启用、无凭据、UNKNOWN）。
--
-- ON CONFLICT DO NOTHING 而不是 DO UPDATE 是刻意的：生产上运维已经把这条渠道
-- 配上凭据、启用并做过健康检查。补跑本迁移时绝不能把那些配置覆盖回占位状态
-- ——那会把一条正在工作的天翼视觉路由打回 503。
INSERT INTO gateway.channel(
    id, provider_id, code, name, environment, base_url, enabled, priority, weight,
    timeout_ms, requests_per_minute, max_concurrency, last_health_status, settings)
SELECT
    '84000000-0000-4000-8000-000000000074', provider.id,
    'tianyi-chat-primary', '天翼云对话视觉主渠道', 'PRODUCTION',
    'https://ai.ctaigw.cn/v1',
    false, 100, 100, 60000, 60, 10, 'UNKNOWN',
    '{"managed":true,"credentialOwner":"system-gateway"}'::jsonb
FROM gateway.provider provider
WHERE provider.code = 'tianyi'
  AND provider.provider_type = 'TIANYIYUN'
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. 路由：挂到既有的 vision-basic 别名上
--
-- **刻意新建一个 vision-tianyi 别名**，而不是把本渠道路由挂到既有的
-- vision-basic 上。理由是费率卡的单位语义：
--
-- 一个别名的费率行描述的是**这个别名**，而有多条可路由渠道时成本基准
-- 必须取最贵的那条，否则 failover 到贵渠道的请求就是亏损。vision-basic
-- 现在被 V15/V48/V50 定价为百炼的 qwen-vl-plus（成本 120/720 分每百万
-- token，零售 180/1080）。把天翼的 Qwen2.5-VL-72B-Instruct 加成第二渠道
-- 会让该别名的成本基准取决于两家供应商里更贵的一方，而天翼的价格
-- **尚未确认**。先把一条未定价的路线挂进一个已定价的别名，会让
-- 已发布套餐的毛利凭空变化——那不是一条迁移该顺手做的事。
--
-- 新建别名则完全隔离：vision-tianyi 有自己的费率行与自己的档位，
-- 天翼降价或涨价都不影响已经卖出去的 vision-basic 套餐。
-- 一个"天翼专属视觉别名"是运营可以真的建出来、并在控制台里看到的能力，
-- 而不是一个只能靠改代码解释的悬空配置。
-- ---------------------------------------------------------------------------

INSERT INTO gateway.model(
    id, code, display_name, description, capabilities,
    context_window, max_output_tokens, premium, enabled,
    requests_per_minute, max_concurrency, routing_strategy, routing_policy)
VALUES (
    '82000000-0000-4000-8000-000000000111', 'vision-tianyi', '天翼视觉理解',
    '视觉理解：识别商品、读取包装、分析参考图、生成文案草稿（天翼云 Qwen2.5-VL-72B）',
    '["TEXT","IMAGE"]',
    32768, 8192, false, true, 40, 4, 'DIRECT', '{}'::jsonb)
ON CONFLICT (code) DO NOTHING;

INSERT INTO gateway.model_alias(id, model_id, alias)
SELECT '83000000-0000-4000-8000-000000000111', model.id, model.code
FROM gateway.model model
WHERE model.code = 'vision-tianyi'
ON CONFLICT (alias) DO NOTHING;

INSERT INTO gateway.channel_model(
    id, channel_id, model_id, upstream_model, enabled, capabilities)
SELECT '85000000-0000-4000-8000-000000000079', channel.id, model.id,
       'Qwen2.5-VL-72B-Instruct', true, '["TEXT","IMAGE"]'::jsonb
FROM gateway.channel channel
JOIN gateway.model model ON model.code = 'vision-tianyi'
WHERE channel.code = 'tianyi-chat-primary'
ON CONFLICT (channel_id, model_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. 费率行：**故意不播种，并在这里显式阻塞**
--
-- Qwen2.5-VL-72B-Instruct 在天翼的单价本次改动拿不到；天翼云星辰 TokenHub
-- 的文档站是 JS 渲染的，抓取只回导航壳，搜索结果也只给到页面壳。
--
-- 一个 token 维度的别名需要五个成本列
-- （provider_cost_input_per_million、provider_cost_output_per_million、
-- provider_cache_hit_cost_per_million，加上缓存写与推理的分项），
-- 而 ck_model_usage_price_margin 与 ck_model_usage_price_cache_floor
-- 会让任何一个凭空填的零值**静默放行**一个亏钱的零售价。
--
-- 因此这里不写行，而**让下面的断言主动证明它没有写行**。
-- 这不是一条阻塞迁移：渠道与路由是完整、可用、无副作用的能力，
-- 而"没有费率行"正是它们此刻的诚实状态——一个未定价的别名不会被
-- 平台发布守卫接受，因此它既不会静默亏钱，也不会阻断其余测试。
-- 若这里 RAISE 阻塞，整个 schema 会停在这一版上，连回归测试都跑不起来。
--
-- 价格到位后的收尾（应作为紧随其后的一条新迁移，而不是改写本文件）：
-- 插入 vision-tianyi 的费率行，把下面第二个断言的期望值反过来，
-- 并按 V81/V82 同形补一组"证明写入合法"的断言。
-- 若届时决定给这个别名一个非零的 cache 价格，
-- ck_model_usage_price_cache_floor 要求它至少为 15 分/百万。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    broken integer;
BEGIN
    -- 渠道必须存在，且必须仍然指向天翼源站的 /v1。
    --
    -- 这里**刻意不检查"未启用 / 无凭据 / credentialOwner 标记"**。这条迁移在
    -- 全新库里把渠道建为占位状态，但生产上运维随后就会把凭据与地址配好并
    -- 启用它——那是这条迁移的**目的**，不是违规。把配置进度写成断言，等于
    -- 要求生产永远不要完成配置：在真的配好的库上（用生产快照演练、或对已
    -- 上线环境补跑本迁移）它会直接失败，把一个正在工作的渠道报成错误。
    -- 断言只保留"渠道还在、源站还对"这两条真正的不变量。
    SELECT count(*) INTO broken
    FROM gateway.channel channel
    WHERE channel.code = 'tianyi-chat-primary';

    IF broken <> 1 THEN
        RAISE EXCEPTION 'tianyi_chat_channel_missing:actual=%', broken;
    END IF;

    -- 对话适配器拼 {base}/chat/completions，因此本渠道必须存到 /v1。
    -- 存成裸域名会得到 https://ai.ctaigw.cn/chat/completions，
    -- 即一个 404 —— 而那正是"配好了却跑不通"这一类事故的形态。
    SELECT count(*) INTO broken
    FROM gateway.channel channel
    WHERE channel.code = 'tianyi-chat-primary'
      AND channel.base_url IS DISTINCT FROM 'https://ai.ctaigw.cn/v1';

    IF broken > 0 THEN
        RAISE EXCEPTION 'tianyi_chat_channel_base_url_must_end_with_v1';
    END IF;

    -- 路由必须存在，且必须声明 IMAGE：一个只声明 TEXT 的视觉路由
    -- 会在能力校验里被当成纯文本模型，然后读图请求在运行时才失败。
    SELECT count(*) INTO broken
    FROM gateway.channel_model mapping
    JOIN gateway.channel channel ON channel.id = mapping.channel_id
    JOIN gateway.model model ON model.id = mapping.model_id
    WHERE channel.code = 'tianyi-chat-primary'
      AND model.code = 'vision-tianyi'
      AND mapping.upstream_model = 'Qwen2.5-VL-72B-Instruct'
      AND mapping.capabilities @> '["IMAGE"]'::jsonb;

    IF broken <> 1 THEN
        RAISE EXCEPTION 'tianyi_vision_route_missing_or_lacks_image_capability';
    END IF;

    -- 未定价状态必须被**证明**，而不是被假设。本迁移建路由时价格还没到，
    -- 因此此刻 vision-tianyi 必须没有费率行：一条零成本的费率行会被
    -- ck_model_usage_price_margin 放行，然后平台就能以任意低价发布一个
    -- 亏钱的套餐版本。
    --
    -- **这条断言在 V95 里被正式收尾。** V95 拿到了天翼的实测价格
    -- （输入输出各 4.13 元/百万），因此它插入费率行，并把这条断言替换为
    -- 「证明那一行合规」。本迁移先于 V95 运行，所以这里期望 0 是正确的；
    -- 若有人在 V95 之后回填本文件，它会以一句指名道姓的错误失败，
    -- 而不是让两份迁移对同一个别名给出矛盾的断言。
    SELECT count(*) INTO broken
    FROM gateway.model_usage_price price
    WHERE price.model_code = 'vision-tianyi'
      AND price.effective_to IS NULL;

    IF broken <> 0 THEN
        RAISE EXCEPTION
            'vision_tianyi_must_not_be_priced_before_V95:rows=%', broken;
    END IF;
END $$;

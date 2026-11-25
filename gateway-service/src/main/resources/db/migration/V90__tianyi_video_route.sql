-- 天翼云分镜视频路由：minimax-h3-max
--
-- 背景。TianYiYunVideoGenerationAdapter 早就写好了——它自己探测过
-- ai.ctaigw.cn 的契约，有 MODEL_RESOLUTIONS 表和
-- TianYiYunVideoGenerationAdapterContractTest——但**从来没有任何迁移
-- 建过天翼的视频渠道或路由行**。全库 `minimax-h3-max` 只在
-- Java 与测试里出现。这是一个"有适配器、无路由"的孤儿：能力存在，
-- 但没有任何请求能到达它。
--
-- 为什么单独一条渠道而不是复用 tianyi-image-primary。两个端点的路径
-- 不同（生图是 /v1/images/generations，分镜视频是 /v1/video_generation），
-- 而渠道的 base_url 是所有路由共享的一个值。V71 为百炼做过同样的拆分：
-- 一个渠道一个协议，而不是让适配器去猜运维存的是哪个端点。
--
-- 与 V81 的天翼生图渠道完全同形：**不播种任何凭据**。渠道以
-- enabled = false / last_health_status = 'UNKNOWN' / encrypted_api_key IS NULL
-- 注册，运维在「上游渠道 / 模型管理」里存一次系统托管的 key、启用、
-- 跑一次健康检查。客户可见的模型目录刻意不依赖这些路由条件——
-- 有权益的别名在其上游配好之前就该被展示——而 POST /v1/videos 的候选
-- 查询保持严格，因此仅凭本迁移的这些行还不足以让模型可调用。
--
-- 设计：docs/superpowers/specs/2026-09-30-audio-and-tianyi-model-onboarding-design.md

-- ---------------------------------------------------------------------------
-- 1. 分镜视频渠道
-- ---------------------------------------------------------------------------

INSERT INTO gateway.channel(
    id, provider_id, code, name, environment, base_url, enabled, priority, weight,
    timeout_ms, requests_per_minute, max_concurrency, last_health_status, settings)
SELECT
    '84000000-0000-4000-8000-000000000073', provider.id,
    'tianyi-video-primary', '天翼云分镜视频主渠道', 'PRODUCTION',
    'https://ai.ctaigw.cn',
    false, 100, 100, 60000, 20, 4, 'UNKNOWN',
    '{"managed":true,"credentialOwner":"system-gateway"}'::jsonb
FROM gateway.provider provider
WHERE provider.code = 'tianyi'
  AND provider.provider_type = 'TIANYIYUN'
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. 路由：只有适配器真正能履约的档位
--
-- TianYiYunVideoGenerationAdapter 的 MODEL_RESOLUTIONS 把
-- minimax-h3-max 钉死为 Set.of("480P", "768P")，而平台的视频档位被
-- V54 钉死为 480P/720P/1080P。适配器做的翻译是：
--
--     平台 480P -> 厂商 480P
--     平台 720P -> 厂商 768P   （768P 是 720P 的超集，对客户只多不少）
--     平台 1080P -> null      （**本厂商无法履约**，直接拒绝）
--
-- 因此这里只建 video-budget(480P) 与 video-basic(720P) 两条。
-- video-standard 与 video-max 是 1080P 别名：给它们建行会得到一条
-- validateSubmission 必然以 unsupported_tianyi_video_model 拒绝的路由，
-- 即一张永远服务不了任何请求的路由表。下面的断言把这条规则钉住，
-- 免得后来的人"补齐"成五条。
--
-- video-pro 也是 720P，因此它**可以**走本渠道。本迁移故意不为它建行：
-- 它现在被 V40 定价为 Kling v3-turbo 的第二供应商（成本 92 分/秒），
-- 把天翼加成 failover 会让最贵的那条路线决定毛利下限，而天翼的价格
-- 尚未确认（见下）。等价格到位再决定，不预先猜。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 2. 本迁移**不登记任何别名路由**（保留图生视频）
-- ---------------------------------------------------------------------------
--
-- 这里原本把 video-budget(480P) 与 video-basic(720P) 挂到本渠道上作为
-- failover。**已删除，且是有意的**：天翼 minimax-h3-max 端点不接受首帧图片
-- （TianYiYunVideoGenerationAdapter.validateSubmission 直接拒绝），而目录对
-- 一个别名的能力报告的是它**全部可达路由的保守交集**。因此只要 video-basic
-- 多出这一条路由，它的 IMAGE_TO_VIDEO 模态就会因为交集为空而从目录里消失
-- —— 一条 failover 路由把客户已经买到的图生视频能力吃掉了。
--
-- 生产可达的路由本来就由运维按渠道配置决定（本迁移建的渠道无凭据、未启用）。
-- 分镜视频别名 video-minimax 自己的路由由 V99 登记，同样不再经由这里。
--
-- 另外：V99 曾断言"这两条映射保留"，并据此把天翼描述为它们的 failover 供应商。
-- 那个断言与本决定冲突，已一并删除——否则上面这条能力保全会被一条断言推回去。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 3. 费率行：**故意不播种**
--
-- 本次改动拿不到 minimax-h3-max 的天翼采购价。天翼云星辰 TokenHub 的
-- 文档站是 JS 渲染的，抓取只回导航壳；MiniMax 生视频 API 页
-- (https://www.ctyun.cn/document/11061839/11099144) 与按量付费计费页
-- (https://www.ctyun.cn/document/11061839/11062267) 都在价目表之前被截断。
-- 第三方转售价（768P 档 0.50-0.80 元/秒）**不是本平台的采购价**，
-- 把它写进 provider_unit_cost 会让 ck_model_usage_price_margin 拿一个
-- 错的基数放行一个亏钱的售价。
--
-- 因此这里不写任何费率行，video-budget / video-basic 继续用 V40 的
-- 百炼基准（480P 35 分/秒、720P 69 分/秒，均为 retry-inclusive）。
--
-- 这个省略是**保守方向**，值得写清楚：一个别名的费率卡描述的是**别名**
-- 而不是某一家上游。当同一个别名有多条可路由渠道时，成本基准必须取
-- 最贵的那条，否则 failover 到贵渠道的那次请求就会变成亏损。天翼的
-- 价格一旦确认并低于百炼，V40 的行**不需要改**——它已经是安全的上界。
-- 反之若天翼更贵，则该行必须重定价，而那需要价格数字，不是猜测。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 4. 断言
-- ---------------------------------------------------------------------------
--
-- 与 V81/V82 同一做法：直接写入受约束表的迁移必须自己证明写入合法。
-- 这里断言四件事——渠道在无凭据状态下被注册、上面登记的路由确实只覆盖
-- 适配器能履约的档位、被拒绝的 1080P 别名在本渠道上没有行、
-- 以及天翼供应商的 base_url 仍是适配器所假设的那个源站。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    broken integer;
    mapping_count integer;
    provider_origin text;
BEGIN
    -- 渠道必须以无凭据、未启用的状态存在。IS DISTINCT FROM 而不是 <>：
    -- encrypted_api_key 为 NULL 时 <> 会得到 NULL，从而静默通过它本该
    -- 执行的检查（V81:444 踩过同一个坑）。
    SELECT count(*) INTO broken
    FROM gateway.channel channel
    WHERE channel.code = 'tianyi-video-primary'
      AND (channel.enabled
           OR channel.encrypted_api_key IS NOT NULL
           OR channel.last_health_status IS DISTINCT FROM 'UNKNOWN');

    IF broken > 0 THEN
        RAISE EXCEPTION 'tianyi_video_channel_must_be_uncredentialed_and_disabled';
    END IF;

    IF NOT EXISTS (
            SELECT 1
            FROM gateway.channel channel
            WHERE channel.code = 'tianyi-video-primary'
              AND channel.settings -> 'credentialOwner'
                  IS NOT DISTINCT FROM '"system-gateway"'::jsonb) THEN
        RAISE EXCEPTION 'tianyi_video_channel_settings_missing_credential_owner';
    END IF;

    -- 供应商源站必须还是适配器假设的那个。适配器从渠道 base_url 组合
    -- /v1/video_generation；若运维把它换成别家的源站，这条路由会以
    -- 一个难懂的上游错误失败，而不是在这里失败。
    SELECT provider.base_url INTO provider_origin
    FROM gateway.provider provider
    WHERE provider.code = 'tianyi';

    IF provider_origin IS DISTINCT FROM 'https://ai.ctaigw.cn' THEN
        RAISE EXCEPTION 'tianyi_provider_origin_changed:actual=%', provider_origin;
    END IF;

    -- 本渠道上**不应有任何别名路由**：它无凭据、未启用，是留给运维配置的
    -- 占位渠道（生产实际用的是运维配好的那条天翼视频渠道）。V99 之后
    -- video-minimax（V99）会挂到一条**已承载天翼视频路由**的渠道上，而不是这一条。
    SELECT count(*) INTO mapping_count
    FROM gateway.channel_model mapping
    JOIN gateway.channel channel ON channel.id = mapping.channel_id
    WHERE channel.code = 'tianyi-video-primary';

    IF mapping_count <> 0 THEN
        RAISE EXCEPTION 'tianyi_video_placeholder_channel_must_stay_unrouted:actual=%', mapping_count;
    END IF;

    -- 1080P 别名在本渠道上必须没有行：适配器的 providerResolution 对
    -- 它们返回 null 并以 unsupported_tianyi_video_model 拒绝，
    -- 因此一条这样的行永远服务不了请求。
    SELECT count(*) INTO broken
    FROM gateway.channel_model mapping
    JOIN gateway.channel channel ON channel.id = mapping.channel_id
    JOIN gateway.model model ON model.id = mapping.model_id
    WHERE channel.code = 'tianyi-video-primary'
      AND model.code IN ('video-standard', 'video-max');

    IF broken > 0 THEN
        RAISE EXCEPTION
            'tianyi_video_route_registered_for_a_tier_the_adapter_rejects:count=%', broken;
    END IF;

    -- video-pro 是 720P，因此它可以走本渠道，但本迁移刻意不登记它。
    -- 若有人后来加了，说明费率基准的决定已经做过，这条断言应当随之更新。
    SELECT count(*) INTO broken
    FROM gateway.channel_model mapping
    JOIN gateway.channel channel ON channel.id = mapping.channel_id
    JOIN gateway.model model ON model.id = mapping.model_id
    WHERE channel.code = 'tianyi-video-primary'
      AND model.code = 'video-pro';

    IF broken > 0 THEN
        RAISE EXCEPTION
            'tianyi_video_pro_route_needs_a_recorded_cost_basis_decision';
    END IF;
END $$;

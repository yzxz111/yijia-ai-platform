-- 客户端的文本模型列表只保留两个「通道」：普通 / 深度。
--
-- ⚠️ 本文件在**生产已应用之后**被改过一次（2026-10-09 故障复盘），只改了第 7 步
-- 里那句"承载渠道必须 enabled 且 HEALTHY"的断言：它把**运行时**状态当成建库前置
-- 条件，导致任何全新库都无法完成迁移（网关起不来）。改动会让本文件的 Flyway
-- 校验和变化，因此已经登记过 V102 的环境必须同步那一行，否则启动时校验和不匹配：
--
--   python scripts/flyway-checksum.py                      # 取新的 checksum
--   UPDATE gateway.flyway_schema_history
--      SET checksum = <新值> WHERE version = '102';
--
-- 这条更新是幂等的，且只影响"已经应用过 V102"的环境；新环境直接按新校验和登记。
-- 之所以不能另起一条新迁移来修：断言在 V102 **执行时**就会失败，后面任何迁移
-- 都来不及跑。
--
-- 需求：客户端（APP）不应看到模型名（text-basic / text-max / …这种内部别名），
-- 只应看到两个入口——「普通」与「深度」。第三方兼容客户端（直接调 OpenAI 接口）
-- 仍要能按原别名调用。
--
-- 因此本迁移**不动任何调用路径、不动套餐放行**，只做两件事：
--   1. 新增两个模型别名 chat-standard / chat-deep，并给它们定价；
--   2. 新增 gateway.model.client_visible，把「目录里露不露出名字」与
--      「能不能调用」彻底分开：/v1/models 只回 client_visible = true 的行，
--      而鉴权与执行仍然只认套餐的 allowed_models。
--
-- 为什么用新列而不是复用 routing_strategy：后者是 DIRECT/COMPOSITE 的**路由语义**，
-- 被 ProviderPriceQueryRepository 与 CompositeModelPolicyService 读取，往里塞一个
-- 展示开关会改变计价与路由行为。展示可见性是另一件事，值得自己一列。
--
-- 默认值 true 保证向后兼容：任何既有行、以及将来不加这列的写入，行为与今天一致。
--
-- 两个通道当前指向**同一个上游模型**（天翼 Qwen2.5-VL-72B-Instruct），因为生产上
-- 唯一 HEALTHY 的对话渠道就是 tianyi-chat-primary，且天翼适配器是薄透传、不改写
-- 请求参数——网关侧无法为「深度」单独开启推理模式。两者的差异体现在计费档位：
-- 普通对齐全库最低毛利档（4%），深度对齐旗舰档（40%）。
-- 等确认天翼还有更便宜或推理类模型后，把 chat-standard 的 channel_model 改成那个
-- 模型即可，目录与客户端无需任何改动。

-- ---------------------------------------------------------------------------
-- 1. 展示可见性开关
-- ---------------------------------------------------------------------------

ALTER TABLE gateway.model
    ADD COLUMN IF NOT EXISTS client_visible boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN gateway.model.client_visible IS
    '是否出现在客户端模型目录里（/v1/models）。与「能否调用」无关：调用只认套餐的 '
    'allowed_models。设为 false 的别名仍可被第三方兼容客户端按名字调用。';

-- ---------------------------------------------------------------------------
-- 2. 两个通道的模型行
--
-- context_window / max_output_tokens 取同一上游模型（Qwen2.5-VL-72B-Instruct）
-- 在库中已有的口径：vision-tianyi 就是同一个上游模型，因此不与它打架。
-- ---------------------------------------------------------------------------

INSERT INTO gateway.model(
    id, code, display_name, description, capabilities, context_window,
    max_output_tokens, premium, enabled)
VALUES
    ('82000000-0000-4000-8000-000000000201',
     'chat-standard', '普通',
     '日常问答通道：响应优先，单价最低',
     '["TEXT"]'::jsonb, 131072, 8192, false, true),
    ('82000000-0000-4000-8000-000000000202',
     'chat-deep', '深度',
     '复杂任务通道：能力最强的模型，单价更高',
     '["TEXT"]'::jsonb, 131072, 8192, true, true)
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. 别名
--
-- 客户端的 model 参数提交的就是别名（chat-standard / chat-deep）。
-- 展示名是中文的「普通 / 深度」——客户端按 displayName 渲染即可，
-- 这也是它本来就该用的字段（/v1/models 的响应里一直带着 display_name）。
-- ---------------------------------------------------------------------------

INSERT INTO gateway.model_alias(id, model_id, alias)
SELECT gen_random_uuid(), model.id, model.code
FROM gateway.model model
WHERE model.code IN ('chat-standard', 'chat-deep')
  AND NOT EXISTS (
        SELECT 1 FROM gateway.model_alias existing
        WHERE existing.alias = model.code);

-- ---------------------------------------------------------------------------
-- 4. 路由：两条通道都挂到生产在用的那条健康渠道
--
-- 渠道就是 tianyi-chat-primary —— 注意它挂的供应商是 **BAILIAN 类型**
-- （provider.code = 'tianyi-chat'，base_url = https://ai.ctaigw.cn/v1）。
-- 这不是笔误：天翼网关是 OpenAI/DashScope 兼容的聚合网关，厂商类型要按**协议**
-- 选，选 TIANYIYUN 会让请求走到天翼专用的适配器上而 503。生产上现有的
-- text-* / vision-* / file-basic 全部路由在这条渠道上。
-- 因此这里**按渠道 code 定位，不按 provider_type 过滤**：把供应商类型写进
-- 断言里等于把"渠道归属"当成前置条件，而真正的前置条件是"这条渠道在、且健康"。
-- ---------------------------------------------------------------------------

INSERT INTO gateway.channel_model(
    id, channel_id, model_id, upstream_model, enabled, capabilities)
SELECT gen_random_uuid(), channel.id, model.id,
       'Qwen2.5-VL-72B-Instruct', true, '["TEXT"]'::jsonb
FROM gateway.model model
CROSS JOIN gateway.channel channel
WHERE model.code IN ('chat-standard', 'chat-deep')
  AND channel.code = 'tianyi-chat-primary'
  AND NOT EXISTS (
        SELECT 1 FROM gateway.channel_model existing
        WHERE existing.channel_id = channel.id
          AND existing.model_id = model.id);

-- ---------------------------------------------------------------------------
-- 5. 费率（每百万 token，单位：分）
--
-- 成本基准 413/413 来自同上游模型在本库已生效的口径（vision-tianyi：
-- provider_cost 413/413、零售 550/550、毛利 25%）。新通道沿用同一成本基准，
-- 因为路由到的就是同一个上游模型；若将来 chat-standard 换到更便宜的模型，
-- 这一行的 provider_cost_* 必须同步改成那个模型的成本。
--
--   chat-standard  4% 毛利 → ceil(413/0.96) = 430
--   chat-deep     40% 毛利 → ceil(413/0.60) = 689 → 取 690
--
-- 三条必需的 fallback 列（ck_model_usage_price_fallback 要求 TOKEN 行必须
-- 同时带 cache_hit / cache_write / reasoning）：
--   cache_hit   = 100（>= ck_model_usage_price_cache_floor 的 15，且不高于输入价）
--   cache_write = 430 / 690（与输入同价，即不额外加价）
--   reasoning   = 430 / 690（本通道不单独计推理 token，与输出同口径）
-- 所有零售列都 >= 对应的 provider_cost 列，满足 ck_model_usage_price_margin。
-- ---------------------------------------------------------------------------

INSERT INTO gateway.model_usage_price(
    id, model_code, usage_dimension, resolution_tier, minimum_units, minimum_charge,
    unit_price, input_price_per_million, output_price_per_million,
    cache_hit_price_per_million, cache_write_price_per_million,
    reasoning_price_per_million, provider_unit_cost,
    provider_cost_input_per_million, provider_cost_output_per_million,
    provider_cache_hit_cost_per_million, retry_rate, target_margin_ratio,
    min_absolute_margin, price_version, effective_from, effective_to, metadata)
SELECT gen_random_uuid(), channel.code, 'TOKEN', '', 1, 1,
       NULL, channel.retail, channel.retail, 100, channel.retail, channel.retail,
       0, 413, 413, 0, 0.00000, channel.margin, 0,
       'channels-v1', now(), NULL, '{}'::jsonb
FROM (VALUES
        ('chat-standard', 430::bigint, 0.04000::numeric),
        ('chat-deep',     690::bigint, 0.40000::numeric)
     ) AS channel(code, retail, margin)
WHERE NOT EXISTS (
        SELECT 1 FROM gateway.model_usage_price existing
        WHERE existing.model_code = channel.code
          AND existing.usage_dimension = 'TOKEN'
          AND existing.effective_to IS NULL);

-- ---------------------------------------------------------------------------
-- 6. 目录只露两个通道
--
-- 被隐藏的是**通用文本入口**：四个 text-* 纯文本别名，以及 multimodal-basic
-- （它按文本入口被售卖，实际能力是 TEXT+FILE）。
--
-- 刻意不隐藏的：vision-basic / vision-pro / file-basic / vision-tianyi。
--   * 读图与传文件在这套系统里是**独立于文本选择的入口**：传文件的额度按套餐
--     判定（UploadEntitlementService.packageEntitlement），不看模型可见性；
--     读图请求提交的是 vision-* 别名。把它们从目录里抹掉只会让客户端的读图/
--     文件入口失去依据，而不是"文本只留两个通道"。
--   * 需求原文也是「和读图传文件视频生成没有关系」。
-- 第三方兼容客户端不受影响：这些别名仍在套餐 allowed_models 里，照常可调用，
-- 只是不再出现在 /v1/models 的列表里。
-- ---------------------------------------------------------------------------

UPDATE gateway.model
SET client_visible = false, updated_at = now()
WHERE code IN ('text-basic', 'text-standard', 'text-pro', 'text-max', 'multimodal-basic')
  AND client_visible;

-- ---------------------------------------------------------------------------
-- 7. 断言
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    broken              integer;
    carrier_enabled     boolean;
    carrier_health      varchar(20);
    carrier_credentialed boolean;
BEGIN
    -- 两个通道必须各自存在模型行、别名、路由、现价行
    SELECT count(*) INTO broken
    FROM (VALUES ('chat-standard'), ('chat-deep')) AS expected(code)
    WHERE NOT EXISTS (
            SELECT 1 FROM gateway.model model
            WHERE model.code = expected.code AND model.enabled)
       OR NOT EXISTS (
            SELECT 1 FROM gateway.model_alias alias
            WHERE alias.alias = expected.code)
       OR NOT EXISTS (
            SELECT 1 FROM gateway.channel_model mapping
            JOIN gateway.model model ON model.id = mapping.model_id
            WHERE model.code = expected.code AND mapping.enabled)
       OR NOT EXISTS (
            SELECT 1 FROM gateway.model_usage_price price
            WHERE price.model_code = expected.code
              AND price.usage_dimension = 'TOKEN'
              AND price.effective_to IS NULL);
    IF broken > 0 THEN
        RAISE EXCEPTION 'chat_channel_incomplete:count=%', broken;
    END IF;

    -- 两个通道必须是可见的，且展示名必须是中文的「普通 / 深度」
    SELECT count(*) INTO broken
    FROM gateway.model model
    WHERE (model.code = 'chat-standard' AND (model.display_name <> '普通' OR NOT model.client_visible))
       OR (model.code = 'chat-deep' AND (model.display_name <> '深度' OR NOT model.client_visible));
    IF broken > 0 THEN
        RAISE EXCEPTION 'chat_channel_display_name_or_visibility_wrong:count=%', broken;
    END IF;

    -- 承载渠道必须存在，且两条通道确实挂在它上面（第 4 步写入的路由行）。
    --
    -- **这里刻意不再要求 enabled = true / last_health_status = 'HEALTHY'。**
    -- 那两列是**运行时**状态：渠道由 V91 以 enabled = false、UNKNOWN、无凭据登记，
    -- 只有运维填了密钥并跑过一次健康探测之后才会变成可调度。把它们写成迁移断言，
    -- 等于要求"先有运行时状态、再能建库"——任何**全新库**都做不到，于是
    -- Flyway 在这里失败、网关起不来：2026-10-09 的生产故障第二段正是这个形态
    -- （/console/gateway/** 全 502，而 /console/platform/** 仍然 200，
    -- 说明是网关没起来而不是反向代理的问题）。本仓库自己的迁移测试也是被这一句
    -- 全部卡住的（38/38 报 chat_channel_carrier_not_ready）。
    --
    -- 断言要防的是"看得见、点不通"，但那是**上线前**该确认的事，不是可重放的
    -- 数据迁移能证明的事。因此这里降级成 NOTICE/WARNING：把承载渠道当时的
    -- 可调度状态原样报出来，部署者一眼就能看到还差什么，而建库不再被它挡住。
    SELECT count(*) INTO broken
    FROM gateway.channel channel
    WHERE channel.code = 'tianyi-chat-primary';
    IF broken <> 1 THEN
        RAISE EXCEPTION 'chat_channel_carrier_missing:code=tianyi-chat-primary';
    END IF;

    SELECT count(*) INTO broken
    FROM (VALUES ('chat-standard'), ('chat-deep')) AS expected(code)
    WHERE NOT EXISTS (
        SELECT 1
        FROM gateway.channel_model mapping
        JOIN gateway.model model ON model.id = mapping.model_id
        JOIN gateway.channel channel ON channel.id = mapping.channel_id
        WHERE model.code = expected.code
          AND channel.code = 'tianyi-chat-primary'
          AND mapping.enabled);
    IF broken > 0 THEN
        RAISE EXCEPTION 'chat_channel_route_missing:code=tianyi-chat-primary:count=%', broken;
    END IF;

    SELECT channel.enabled, channel.last_health_status,
           channel.encrypted_api_key IS NOT NULL
    INTO carrier_enabled, carrier_health, carrier_credentialed
    FROM gateway.channel channel
    WHERE channel.code = 'tianyi-chat-primary';

    IF carrier_enabled IS TRUE AND carrier_health = 'HEALTHY' AND carrier_credentialed THEN
        RAISE NOTICE 'chat_channel_carrier_ready:code=tianyi-chat-primary';
    ELSE
        RAISE WARNING
            'chat_channel_carrier_not_schedulable_yet:code=tianyi-chat-primary:'
            'enabled=%:health=%:credentialed=% —— 建库不因此失败；上线前需要运维填好'
            '渠道凭据并跑一次健康探测，否则客户端会看到两个文本入口却点不通。',
            carrier_enabled, carrier_health, carrier_credentialed;
    END IF;

    -- 被隐藏的通用文本入口确实都不再可见
    SELECT count(*) INTO broken
    FROM gateway.model model
    WHERE model.code IN ('text-basic', 'text-standard', 'text-pro', 'text-max', 'multimodal-basic')
      AND model.client_visible;
    IF broken > 0 THEN
        RAISE EXCEPTION 'internal_text_alias_still_visible:count=%', broken;
    END IF;
END $$;

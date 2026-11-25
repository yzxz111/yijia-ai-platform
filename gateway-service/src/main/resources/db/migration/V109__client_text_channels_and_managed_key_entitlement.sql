-- V109：把「客户端文本入口」发给卖文本的实例，并把托管 Key 的名单对齐到它的套餐。
--
-- 背景（2026-10-09 生产故障，桌面端"模型在客户端全部不可用"）。网关对模型有**两份**
-- 名单，请求要两边都放行才成立：
--
--   1. gateway.package_instance.allowed_models —— 这份实例买了哪些别名；
--   2. gateway.api_key.allowed_models          —— 这把钥匙被允许调哪些别名。
--
-- /v1/models 先按 ① 过滤、再按 ② 过滤（ModelCatalogService），鉴权与路由
-- （DatabaseGatewayCatalog）两边都要 `@>` 那一行。V102 建立了 chat-standard /
-- chat-deep，V103 又把其它所有带 TEXT 的别名从客户端目录里收起（client_visible =
-- false），于是**客户端目录里的文本入口只剩这两个**——而这两件事没有任何一步
-- 会把它们发给套餐或 Key：
--
--   * 平台的在售版本里没有这两个别名（platform.package_plan_version_model 只有
--     text-* / vision-* / multimodal-basic / file-basic），生产库当时的实例是人工补的；
--   * 托管 Key 的名单**只在发放那一刻**从套餐抄一次，此后套餐的名单怎么变都不跟
--     （PackageActivationRepository.rebindApiKeys 显式排除 managed Key）。
--
-- 结果是：套餐那一侧补过、Key 那一侧没补 → 客户端拿到 5 个 allowedModels、
-- 目录却是 `data: []`，而且 reason 还是容易误导的 no_chat_models。
--
-- 本迁移只做这两件事，**不删任何东西**：
--
--   1. 给「卖文本」的 TOKEN 实例补上两个客户端文本通道（已经有的原样不动）；
--   2. 把托管 Key 的名单对齐成它所属套餐的名单（与
--      ManagedApiKeyService.reconcileEntitlement 同一条规则，只是这里覆盖
--      已经发放的存量——下一次 Bootstrap 也会做同一件事，两边是幂等的）。
--
-- 为什么 1 需要**存量回填**而不是等下次发放：一台已经发放的实例不会因为这次部署
-- 重新发放一次，而客户端今天就要能用。为什么 2 需要回填而不是只靠 Bootstrap：
-- 桌面端可能几天不重新登录，而"套餐改了、钥匙没跟上"在那之前一直是坏状态。
--
-- 新发放的实例由 PackageActivationService.effectiveModels 按同一条规则补齐；
-- 那一条与下面第 2 步必须一起改，本迁移末尾的断言会证明库里的实例都满足它。

-- ---------------------------------------------------------------------------
-- 1. 哪些实例「卖了文本」
--
-- 判据与 /v1/models 的目录查询（ModelCatalogRepository.findEntitledModels）**同一套**：
-- 别名存在、模型启用、能力含 TEXT、不是生成类（VIDEO / IMAGE_GEN）、不在视频/图片/
-- 音频的档位表与按秒费率里。少写任何一条都会把"其实只是视频/音频套餐"的实例也补上
-- 两个文本入口，那是在替产品做决定。
--
-- 维度只取 TOKEN：按秒/按张计费的实例本来就不进对话目录
-- （目录查询里有 `usage_dimension <> 'VIDEO_SECOND' / 'IMAGE' / 'AUDIO_SECOND'`）。
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE v109_text_instances ON COMMIT DROP AS
SELECT DISTINCT package.id
FROM gateway.package_instance package
JOIN jsonb_array_elements(package.allowed_models) allowed ON true
JOIN gateway.model_alias alias ON alias.alias = allowed ->> 'modelCode'
JOIN gateway.model model ON model.id = alias.model_id AND model.enabled
WHERE package.status = 'ACTIVE'
  AND COALESCE(package.usage_dimension, 'TOKEN') = 'TOKEN'
  AND model.capabilities @> '["TEXT"]'::jsonb
  AND NOT (model.capabilities @> '["VIDEO"]'::jsonb)
  AND NOT (model.capabilities @> '["IMAGE_GEN"]'::jsonb)
  AND NOT EXISTS (
      SELECT 1 FROM gateway.model_usage_price video_price
      WHERE video_price.model_code = alias.alias
        AND video_price.usage_dimension = 'VIDEO_SECOND'
        AND video_price.effective_to IS NULL)
  AND NOT EXISTS (
      SELECT 1 FROM gateway.video_alias_tier video_alias
      WHERE video_alias.model_code = alias.alias)
  AND NOT EXISTS (
      SELECT 1 FROM gateway.image_alias_tier image_alias
      WHERE image_alias.model_code = alias.alias)
  AND NOT EXISTS (
      SELECT 1 FROM gateway.audio_alias_tier audio_alias
      WHERE audio_alias.model_code = alias.alias);

-- 本次改动之前**已经**带着这两个通道的实例。生产上运维是人工补的，本迁移对它们是
-- no-op；把它们单独记下来，第 4 步才能把"本迁移加进去的"与"本来就在的"分开断言。
CREATE TEMP TABLE v109_preexisting ON COMMIT DROP AS
SELECT package.id
FROM gateway.package_instance package
WHERE EXISTS (SELECT 1 FROM jsonb_array_elements(package.allowed_models) existing
              WHERE existing ->> 'modelCode' IN ('chat-standard', 'chat-deep'));

-- ---------------------------------------------------------------------------
-- 2. 补两个通道
--
-- 形状与 V106 给音频别名写的那个逐字一致：`||` 只在尾部追加，原有条目（含每模型
-- 自额度与能力串）一字不动；`modelTokenQuota` 留空表示该别名可以花掉整份 token 桶
-- （QuotaReservationRepository.lockModelQuota 找不到该行时退回套餐桶），因此不需要
-- 也给 package_instance_model_quota 补行。
--
-- 能力串 `["TEXT"]` 与 gateway.model.capabilities（V102 写下的）一致——它与目录
-- 查询用的是同一个词，写错会让别名"存在但不可见"。
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE v109_channels ON COMMIT DROP AS
SELECT * FROM (VALUES
    (1, 'chat-standard', '["TEXT"]'::jsonb),
    (2, 'chat-deep',     '["TEXT"]'::jsonb)
) AS channel(position, model_code, capabilities);

UPDATE gateway.package_instance package
SET allowed_models = package.allowed_models || (
        SELECT COALESCE(jsonb_agg(jsonb_build_object(
                   'modelCode', channel.model_code,
                   'capabilities', channel.capabilities,
                   'modelTokenQuota', NULL)
                   -- 顺序写死（普通在前、深度在后，与 PackageActivationService
                   -- 的 CLIENT_TEXT_CHANNELS 一致）：聚合的输入顺序否则取决于计划，
                   -- 同一条规则在"新发放"与"存量回填"两条路径上会写出不同顺序的数组，
                   -- 而"这份实例买了什么"应当是可比对的。
                   ORDER BY channel.position), '[]'::jsonb)
        FROM v109_channels channel
        WHERE NOT EXISTS (
            SELECT 1 FROM jsonb_array_elements(package.allowed_models) existing
            WHERE existing ->> 'modelCode' = channel.model_code)),
    updated_at = now()
WHERE package.id IN (SELECT id FROM v109_text_instances);

-- ---------------------------------------------------------------------------
-- 3. 托管 Key 的名单对齐到套餐
--
-- 只动 `managed AND status = 'ACTIVE'` 的行：托管 Key 由网关自己发放（每实例一把、
-- 名字固定 "Internal client"），没有让用户挑子集的入口，因此它的授权**就是**套餐的
-- 授权。自助创建的 Key 不在本次范围内——那份名单是用户自己选的子集，扩大它需要
-- 产品确认，而它们不受本次故障影响（桌面端走的是托管 Key）。
--
-- 结果统一规范化成**字符串数组**（与 ManagedApiKeyService.insertManagedKey 写的
-- 形状一致，ApiKeyService.readStringSet 两种形态都认）。去重是必要的：一个重复
-- 条目会让目录返回两份、也让"这把钥匙能调什么"这句话失去唯一答案。
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE v109_key_targets ON COMMIT DROP AS
SELECT key.id AS key_id,
       (SELECT COALESCE(jsonb_agg(DISTINCT code ORDER BY code), '[]'::jsonb)
        FROM (SELECT allowed ->> 'modelCode' AS code
              FROM jsonb_array_elements(package.allowed_models) allowed) package_model
        WHERE code IS NOT NULL AND code <> '') AS package_models
FROM gateway.api_key key
JOIN gateway.package_instance package ON package.id = key.package_instance_id
WHERE key.managed AND key.status = 'ACTIVE';

UPDATE gateway.api_key key
SET allowed_models = target.package_models,
    updated_at = now()
FROM v109_key_targets target
WHERE key.id = target.key_id
  AND key.allowed_models IS DISTINCT FROM target.package_models;

-- ---------------------------------------------------------------------------
-- 4. 断言
--
-- 迁移受"已发布数据不可变"之外的约束很少，因此它要自己证明结果，而且证明的是
-- **不变量**（在空库上也成立），不是"此刻恰好有几行"。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    broken    integer;
    granted   integer;
    synced    integer;
    remaining integer;
BEGIN
    -- (a) 卖文本的 TOKEN 实例必须两个通道都有：这是本迁移的全部意图，
    --     漏一个就等于客户端少一个入口（V103 之后没有别的文本入口可退）。
    SELECT count(*) INTO broken
    FROM v109_text_instances wanted
    WHERE NOT EXISTS (
        SELECT 1
        FROM gateway.package_instance package
        WHERE package.id = wanted.id
          AND EXISTS (SELECT 1 FROM jsonb_array_elements(package.allowed_models) existing
                      WHERE existing ->> 'modelCode' = 'chat-standard')
          AND EXISTS (SELECT 1 FROM jsonb_array_elements(package.allowed_models) existing
                      WHERE existing ->> 'modelCode' = 'chat-deep'));
    IF broken > 0 THEN
        RAISE EXCEPTION 'a_text_package_instance_is_still_without_a_client_text_channel:count=%',
            broken;
    END IF;

    -- (b) 本迁移碰过的实例里，两个别名各恰好一次：重复条目会让目录返回两份。
    SELECT count(*) INTO broken
    FROM v109_text_instances wanted
    JOIN gateway.package_instance package ON package.id = wanted.id
    JOIN v109_channels channel ON true
    WHERE (SELECT count(*) FROM jsonb_array_elements(package.allowed_models) existing
           WHERE existing ->> 'modelCode' = channel.model_code) > 1;
    IF broken > 0 THEN
        RAISE EXCEPTION 'client_text_channel_appears_more_than_once:count=%', broken;
    END IF;

    -- (c) **反向**：带着文本通道的实例，要么本来就有（生产人工补的），要么确实
    --     卖文本。断言写成"集合相等"而不是"没有多出来的行"，因此它同时证明了
    --     本迁移没有漏掉任何一个卖文本的实例。
    SELECT count(*) INTO broken
    FROM gateway.package_instance package
    WHERE EXISTS (SELECT 1 FROM jsonb_array_elements(package.allowed_models) existing
                  WHERE existing ->> 'modelCode' IN ('chat-standard', 'chat-deep'))
      AND NOT EXISTS (SELECT 1 FROM v109_preexisting preexisting
                      WHERE preexisting.id = package.id)
      AND NOT EXISTS (SELECT 1 FROM v109_text_instances wanted
                      WHERE wanted.id = package.id);
    IF broken > 0 THEN
        RAISE EXCEPTION 'a_package_that_does_not_sell_text_got_a_text_channel:count=%', broken;
    END IF;

    -- (d) 托管 Key 与它的套餐必须**逐条一致**：本迁移跑完这条必须成立，
    --     而它不成立时客户端看到的就是"套餐里有、目录里没有"。
    SELECT count(*) INTO broken
    FROM v109_key_targets target
    JOIN gateway.api_key key ON key.id = target.key_id
    WHERE key.allowed_models IS DISTINCT FROM target.package_models;
    IF broken > 0 THEN
        RAISE EXCEPTION 'a_managed_key_does_not_agree_with_its_package:count=%', broken;
    END IF;

    -- (e) 报告本次实际改了什么。用 NOTICE 而不是断言：一个全新的部署里没有历史
    --     实例，"回填 0 行"是正确的而不是失败（与 V106 同一处理）。
    SELECT count(*) INTO granted
    FROM gateway.package_instance package
    WHERE EXISTS (SELECT 1 FROM jsonb_array_elements(package.allowed_models) existing
                  WHERE existing ->> 'modelCode' = 'chat-standard');
    SELECT count(*) INTO synced FROM v109_key_targets;
    SELECT count(*) INTO remaining
    FROM v109_text_instances wanted
    WHERE NOT EXISTS (
        SELECT 1 FROM gateway.package_instance package
        WHERE package.id = wanted.id
          AND EXISTS (SELECT 1 FROM jsonb_array_elements(package.allowed_models) existing
                      WHERE existing ->> 'modelCode' = 'chat-standard'));
    RAISE NOTICE
        'client text channels: instances granting chat-standard=%, managed keys aligned '
        'to their package=%, text instances still without the channel=%',
        granted, synced, remaining;
END $$;

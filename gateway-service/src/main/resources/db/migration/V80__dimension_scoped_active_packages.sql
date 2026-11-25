-- 一个用户同时持有文本套餐与视频套餐。
--
-- 今天「每用户至多一个 ACTIVE 套餐」由 uq_active_package_per_user 强制，
-- 而套餐版本本身是单维度的（V42 的 usage_dimension，平台侧 V54 同样约定：
-- 一个版本不允许混用 TOKEN 与 VIDEO_SECOND 模型）。因此「同时持有两种套餐」
-- 的准确含义是「每个维度各一个 ACTIVE 实例」，而不是一个实例同时卖两种配额。
--
-- 唯一性因此按维度收窄，而不是被放弃：同一维度仍然只有一个生效实例，
-- 排队、限流、结算与 /v1/quota 的既有语义全部不变。
--
-- 同时给 api_key 补上 usage_dimension。绑定关系仍然是「一把 key 一个实例」，
-- 但激活时的重绑必须只作用于同维度的 key：否则激活一个视频套餐会把该用户
-- 所有文本 key 抢过去，而文本路径显式排除 VIDEO_SECOND 套餐
-- （DatabaseGatewayCatalog、ModelCatalogRepository），文本调用会当场失效。
-- 这正是本次改动要消除的既有缺陷。

-- ---------------------------------------------------------------------------
-- 1. API key 的维度
-- ---------------------------------------------------------------------------

ALTER TABLE gateway.api_key
    ADD COLUMN usage_dimension varchar(20) NOT NULL DEFAULT 'TOKEN';

ALTER TABLE gateway.api_key
    ADD CONSTRAINT ck_api_key_usage_dimension
        CHECK (usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE'));

-- 已经绑定了实例的 key 以其绑定实例的维度为准。托管 key 是每个实例一把
-- （uq_api_key_active_managed_package），所以绑定到视频套餐的托管 key
-- 必须回填成 VIDEO_SECOND；其余（含钱包 key，package_instance_id 为 NULL）
-- 保持默认 TOKEN，与它们此前的实际行为一致。
UPDATE gateway.api_key AS key
SET usage_dimension = package.usage_dimension
FROM gateway.package_instance AS package
WHERE package.id = key.package_instance_id;

-- ---------------------------------------------------------------------------
-- 2. ACTIVE 唯一性按维度
-- ---------------------------------------------------------------------------

DROP INDEX gateway.uq_active_package_per_user;

CREATE UNIQUE INDEX uq_active_package_per_user_dimension
    ON gateway.package_instance (user_id, usage_dimension)
    WHERE status = 'ACTIVE';

-- usage_dimension 自 V42 起 NOT NULL DEFAULT 'TOKEN'，因此该索引不需要 COALESCE。
-- 免费领取的唯一性（uq_free_claim_package_per_identity）刻意保持跨维度唯一：
-- 「每个身份只能领一次」是身份级规则，不该因为多了一个维度就变成可以领三次。

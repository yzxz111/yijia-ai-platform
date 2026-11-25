-- Usage Dimension Rate Card
--
-- Introduces a priced usage dimension so video generation (billed per second)
-- can coexist with token models (billed per million tokens) without sharing a
-- counter or a unit price.
--
-- Money unit is 分 (CNY cent), consistent with gateway.model_billing_price,
-- gateway.provider_model_price and ModelBillingPriceCalculator.
--
-- Design: docs/superpowers/specs/2026-09-12-multimodal-pricing-and-video-package-design.md
--
-- Supersession: gateway.model_usage_price SUPERSEDES gateway.model_billing_price
-- for the TOKEN dimension. The legacy table is intentionally left in place so
-- that in-flight reservations keep settling against the price version they
-- started with (gateway.quota_reservation.billing_price_version). The pricing
-- service must read model_usage_price first and fall back to model_billing_price
-- only for a price version that predates this migration.

-- ---------------------------------------------------------------------------
-- 1. Rate card
-- ---------------------------------------------------------------------------

CREATE TABLE gateway.model_usage_price (
    id uuid PRIMARY KEY,
    model_code varchar(128) NOT NULL,
    usage_dimension varchar(20) NOT NULL,
    resolution_tier varchar(16) NOT NULL DEFAULT '',
    minimum_units bigint NOT NULL DEFAULT 1,
    minimum_charge bigint NOT NULL DEFAULT 1,
    unit_price bigint,
    input_price_per_million bigint,
    output_price_per_million bigint,
    cache_hit_price_per_million bigint,
    cache_write_price_per_million bigint,
    reasoning_price_per_million bigint,
    provider_unit_cost bigint NOT NULL DEFAULT 0,
    provider_cost_input_per_million bigint NOT NULL DEFAULT 0,
    provider_cost_output_per_million bigint NOT NULL DEFAULT 0,
    -- Cache hits have their own cost, which is 5x-50x below the miss cost
    -- depending on the vendor. Defaulting it to the miss cost is the mistake
    -- this column exists to prevent: it would price cache from the wrong basis.
    provider_cache_hit_cost_per_million bigint NOT NULL DEFAULT 0,
    retry_rate numeric(8, 5) NOT NULL DEFAULT 0,
    target_margin_ratio numeric(8, 5) NOT NULL DEFAULT 0.40000,
    min_absolute_margin bigint NOT NULL DEFAULT 0,
    price_version varchar(64) NOT NULL,
    effective_from timestamptz NOT NULL DEFAULT now(),
    effective_to timestamptz,
    published_by uuid,
    official_source varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    -- Provider cost columns are the PEAK / worst-case basis. DeepSeek bills half
    -- price outside Beijing peak hours; storing the discounted cost would let
    -- the margin guard publish a price that loses money on peak traffic.
    peak_price_window varchar(64) NOT NULL
        DEFAULT 'Asia/Shanghai Mon-Fri 09:00-12:00,14:00-18:00',
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uq_model_usage_price_version
        UNIQUE (model_code, usage_dimension, resolution_tier, price_version),
    CONSTRAINT ck_model_usage_price_dimension
        CHECK (usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE')),
    CONSTRAINT ck_model_usage_price_tier
        CHECK (resolution_tier IN ('', '480P', '720P', '1080P')
            AND (usage_dimension <> 'TOKEN' OR resolution_tier = '')
            AND (usage_dimension = 'TOKEN' OR resolution_tier <> '')),
    -- A rate card row prices exactly one dimension. Token rows must not carry a
    -- per-second price and second rows must not carry a per-million price.
    CONSTRAINT ck_model_usage_price_shape CHECK (
        (usage_dimension = 'TOKEN'
            AND resolution_tier = ''
            AND input_price_per_million > 0
            AND output_price_per_million > 0
            AND unit_price IS NULL)
        OR (usage_dimension IN ('VIDEO_SECOND', 'IMAGE')
            AND unit_price > 0
            AND input_price_per_million IS NULL
            AND output_price_per_million IS NULL
            AND cache_hit_price_per_million IS NULL
            AND cache_write_price_per_million IS NULL
            AND reasoning_price_per_million IS NULL)),
    CONSTRAINT ck_model_usage_price_fallback CHECK (
        usage_dimension <> 'TOKEN'
        OR (cache_hit_price_per_million IS NOT NULL
            AND cache_write_price_per_million IS NOT NULL
            AND reasoning_price_per_million IS NOT NULL)),
    CONSTRAINT ck_model_usage_price_categories CHECK (
        (cache_hit_price_per_million IS NULL OR cache_hit_price_per_million >= 0)
        AND (cache_write_price_per_million IS NULL OR cache_write_price_per_million >= 0)
        AND (reasoning_price_per_million IS NULL OR reasoning_price_per_million >= 0)),
    CONSTRAINT ck_model_usage_price_floor CHECK (
        unit_price IS NULL OR unit_price >= minimum_charge),
    CONSTRAINT ck_model_usage_price_values CHECK (
        minimum_units > 0 AND minimum_charge > 0
        AND provider_unit_cost >= 0
        AND provider_cost_input_per_million >= 0
        AND provider_cost_output_per_million >= 0
        AND retry_rate >= 0 AND retry_rate < 1
        AND target_margin_ratio >= 0 AND target_margin_ratio < 1
        AND min_absolute_margin >= 0
        AND (effective_to IS NULL OR effective_to > effective_from)),
    -- The publication guard as a database invariant.
    --
    -- VIDEO_SECOND (and IMAGE) rows carry a single large unit cost, so the ratio
    -- floor is meaningful there and is enforced: a sold second must clear
    -- provider_unit_cost / (1 - target_margin) AND an absolute contribution.
    --
    -- TOKEN rows deliberately do NOT get a per-category ratio floor. Cheap
    -- aliases carry a high multiple and expensive aliases a low one, so a single
    -- stored ratio cannot describe all of them; the earlier per-category version
    -- of this constraint rejected every seeded token row. The hard invariants for
    -- tokens are "no category priced below its own cost" and "cache hits clear
    -- their absolute floor" (below). The ratio policy is applied at publication
    -- time by the pricing service, which is where a per-alias target belongs.
    CONSTRAINT ck_model_usage_price_margin CHECK (
        (unit_price IS NULL
            OR (unit_price >= ceil(provider_unit_cost::numeric
                    / (1 - target_margin_ratio))
                AND unit_price * minimum_units - provider_unit_cost * minimum_units
                    >= min_absolute_margin))
        AND (input_price_per_million IS NULL
            OR input_price_per_million >= provider_cost_input_per_million)
        AND (output_price_per_million IS NULL
            OR output_price_per_million >= provider_cost_output_per_million)
        AND (cache_hit_price_per_million IS NULL
            OR cache_hit_price_per_million >= provider_cache_hit_cost_per_million)),
    -- Cache hits are the highest-leverage line in the product; keep an absolute
    -- floor so a failover provider with a pricier cache cannot turn it negative.
    CONSTRAINT ck_model_usage_price_cache_floor CHECK (
        (cache_hit_price_per_million IS NULL AND usage_dimension <> 'TOKEN')
        OR cache_hit_price_per_million >= 15),
    -- A cache hit can never cost more than a miss. If it did, the vendor would be
    -- penalising caching, and the rate card should not model that as a discount.
    CONSTRAINT ck_model_usage_price_cache_cost CHECK (
        provider_cache_hit_cost_per_million <= provider_cost_input_per_million)
);

CREATE UNIQUE INDEX uq_model_usage_price_current
    ON gateway.model_usage_price (model_code, usage_dimension, resolution_tier)
    WHERE effective_to IS NULL;

CREATE INDEX ix_model_usage_price_lookup
    ON gateway.model_usage_price (model_code, usage_dimension, effective_from DESC);

-- ---------------------------------------------------------------------------
-- 2. Video catalog
-- ---------------------------------------------------------------------------

-- Video models carry no token semantics. context_window / max_output_tokens are
-- set to the smallest legal value because ck_model_limits (V1) requires both to
-- be positive; billing for these aliases comes from the VIDEO_SECOND rate card,
-- never from these columns.
INSERT INTO gateway.model(
    id, code, display_name, description, capabilities,
    context_window, max_output_tokens, premium, enabled,
    requests_per_minute, max_concurrency, routing_strategy, routing_policy)
VALUES
    ('82000000-0000-4000-8000-000000000100', 'video-budget', 'Video Budget',
     'Short clip generation, 480P economy tier', '["TEXT","IMAGE","VIDEO"]',
     1, 1, false, true, 20, 4, 'DIRECT', '{}'::jsonb),
    ('82000000-0000-4000-8000-000000000101', 'video-basic', 'Video Basic',
     'Short clip generation 720P, audio included', '["TEXT","IMAGE","VIDEO"]',
     1, 1, false, true, 20, 4, 'DIRECT', '{}'::jsonb),
    ('82000000-0000-4000-8000-000000000102', 'video-standard', 'Video Standard',
     'Short clip generation 1080P, audio included', '["TEXT","IMAGE","VIDEO"]',
     1, 1, false, true, 20, 4, 'DIRECT', '{}'::jsonb),
    ('82000000-0000-4000-8000-000000000103', 'video-pro', 'Video Pro',
     'Premium 720P multi-shot generation, audio co-generated',
     '["TEXT","IMAGE","VIDEO"]',
     1, 1, true, true, 10, 2, 'DIRECT', '{}'::jsonb),
    ('82000000-0000-4000-8000-000000000104', 'video-max', 'Video Max',
     '1080P / 4K generation with audio', '["TEXT","IMAGE","VIDEO"]',
     1, 1, true, true, 10, 2, 'DIRECT', '{}'::jsonb);

INSERT INTO gateway.model_alias(id, model_id, alias)
SELECT gen_random_uuid(), model.id, model.code
FROM gateway.model model
WHERE model.code IN (
    'video-budget', 'video-basic', 'video-standard', 'video-pro', 'video-max')
ON CONFLICT (alias) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. Video rate card (分 per second)
--
-- Wholesale reference verified September 2026 (CNY per second, first-party):
--   wan2.5-t2v-preview  480P 0.30 / 720P 0.60 / 1080P 1.00
--   wan2.6-t2v          720P 0.60 / 1080P 1.00, audio included
--                        https://help.aliyun.com/zh/model-studio/wan2-6-t2v
--   kling-v3-turbo     720P 0.80 / 1080P 1.00, audio co-generated
--   kling-v3-omni      720P 0.90 / 1080P 1.20 / 4K 3.00 with audio
--   kling-v3-omni silent 720P 0.60 / 1080P 0.80
--   MiniMax H3         768P 0.50 / 2K 0.80
--   Doubao-Seedance-2.0  ~1.00 (CNY 46 per M video tokens)
--                        https://m.ithome.com/html/925937.htm
--
-- Do NOT build a SKU on OpenAI video: the /v1/videos family and every sora-2
-- snapshot are deprecated with a 2026-09-24 shutdown and no replacement.
-- https://developers.openai.com/api/docs/deprecations
--
-- provider_unit_cost stores the EFFECTIVE cost = wholesale * (1 + retry_rate).
--
-- retry_rate is 0.15, not 0.10: Kling CHARGES for failed generations while Veo
-- does not. Cited failure rates are Kling ~10%, Runway ~12%, Veo/Sora ~15%, and
-- real iteration multiples run 2-3x list. Choose the upstream per tier based on
-- whether it bills failures, and encode that choice in retry_rate.
--
-- Retail is set at the LOW end of defensible on purpose. Measured across eight
-- platforms reselling Kling 3.0, effective prices ran 0.41x-1.29x of official
-- list (only one above list), because resellers buy on 28-30% rebates. A 1.8x
-- video markup gets undercut on day one.
--
-- i2v is priced identically to t2v by every vendor found, so there is no i2v
-- premium here. v2v IS genuinely cheaper (Seedance prices it at 0.60x t2v) and
-- belongs as a discounted tier once that route is wired.
-- ---------------------------------------------------------------------------

INSERT INTO gateway.model_usage_price(
    id, model_code, usage_dimension, resolution_tier,
    minimum_units, minimum_charge, unit_price,
    provider_unit_cost, retry_rate, target_margin_ratio, min_absolute_margin,
    price_version, effective_from, official_source)
VALUES
    -- 480P budget tier, wholesale 30 分/s.
    (gen_random_uuid(), 'video-budget', 'VIDEO_SECOND', '480P',
     1, 50, 50, 35, 0.15000, 0.30000, 15, 'video-v1',
     '2026-09-12T00:00:00Z',
     'https://help.aliyun.com/zh/model-studio/wan2-6-t2v'),
    -- 720P primary tier, wholesale 60 分/s. This is the volume tier.
    (gen_random_uuid(), 'video-basic', 'VIDEO_SECOND', '720P',
     1, 100, 100, 69, 0.15000, 0.30000, 30, 'video-v1',
     '2026-09-12T00:00:00Z',
     'https://help.aliyun.com/zh/model-studio/wan2-6-t2v'),
    -- 1080P tier, wholesale 100 分/s.
    (gen_random_uuid(), 'video-standard', 'VIDEO_SECOND', '1080P',
     1, 180, 180, 115, 0.15000, 0.35000, 50, 'video-v1',
     '2026-09-12T00:00:00Z',
     'https://help.aliyun.com/zh/model-studio/wan2-6-t2v'),
    -- Premium 720P from a second supplier (Kling v3-turbo), wholesale 80 分/s.
    -- Kept as a failover route and as the audio-co-generated option.
    (gen_random_uuid(), 'video-pro', 'VIDEO_SECOND', '720P',
     1, 130, 130, 92, 0.15000, 0.28000, 35, 'video-v1',
     '2026-09-12T00:00:00Z',
     'https://help.aliyun.com/zh/model-studio/kling-v3-turbo-video-generation'),
    -- 4K with audio (Kling v3-omni), wholesale 300 分/s. 4K ONLY: at CNY 5.00/s
    -- a 1080P row here would not sell, so video-standard covers 1080P.
    (gen_random_uuid(), 'video-max', 'VIDEO_SECOND', '1080P',
     1, 500, 500, 345, 0.15000, 0.30000, 100, 'video-v1',
     '2026-09-12T00:00:00Z',
     'https://help.aliyun.com/zh/model-studio/kling-v3-omni-video-generation');

-- ---------------------------------------------------------------------------
-- 4. Token rate card correction
--
-- V21__wallet_model_usage_billing.sql seeded every alias at 1/2 分 per million,
-- which is below provider cost for every routable alias. These rows are the
-- sellable correction: a higher multiple on cheap aliases, a lower multiple on
-- expensive aliases, and a cache-hit price priced from the cost of a cache hit.
--
-- Provider costs verified September 2026:
--   deepseek-flash  peak hit 0.04 / miss 2.00 CNY, output 8.00 CNY per M
--                   https://api-docs.deepseek.com/zh-cn/quick_start/pricing
--   deepseek-v4-pro peak hit 0.30 / miss 9.00 CNY, output 27.00 CNY per M
--   qwen (Bailian)  https://help.aliyun.com/zh/model-studio/model-pricing
--
-- Cost columns are the PEAK basis. DeepSeek bills half price off-peak, so a
-- rate card built on the off-peak cost publishes a price that loses money
-- during Beijing peak hours (Mon-Fri 09:00-12:00 and 14:00-18:00).
--
-- The cache-hit retail price is the single most important number here: the
-- upstream ratio is 1/50 (flash) and 1/30 (v4-pro), so the retail cache price
-- is set from cache COST, never as a fixed fraction of the input price.
-- ---------------------------------------------------------------------------

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
    -- deepseek-flash: 20 分/M miss, 0.4 分/M cache hit, 80 分/M output.
    -- The cache hit is 73% below the input price for the buyer and 20x its own
    -- cost for us. target_margin_ratio is the ACHIEVED margin of the cheapest
    -- category (300 vs 200 = 33%); the cache-hit line is the binding floor at
    -- 80 against a 0.4 cost.
    (gen_random_uuid(), 'text-basic', 'TOKEN', '', 1, 1, NULL,
     300, 1100, 80, 375, 1100, 200, 800, 0.4, 0, 0.04000, 0, 'retail-v1',
     '2026-09-12T00:00:00Z',
     'https://api-docs.deepseek.com/zh-cn/quick_start/pricing'),
    -- qwen-family cache ratio is only 1/5 (cache read 11.00 CNY per M against a
    -- 40.00 miss), i.e. ten times worse than DeepSeek's 1/50. The cache-hit
    -- retail price is set from the most expensive routable cache read, which is
    -- why these rows look expensive on cache and the DeepSeek rows do not.
    (gen_random_uuid(), 'text-standard', 'TOKEN', '', 1, 1, NULL,
     5500, 22000, 1650, 6900, 22000, 4000, 16000, 1100, 0, 0.40000, 0,
     'retail-v1', '2026-09-12T00:00:00Z',
     'https://help.aliyun.com/zh/model-studio/model-pricing'),
    (gen_random_uuid(), 'vision-basic', 'TOKEN', '', 1, 1, NULL,
     2200, 8500, 600, 2800, 8500, 1500, 6000, 400, 0, 0.40000, 0,
     'retail-v1', '2026-09-12T00:00:00Z',
     'https://help.aliyun.com/zh/model-studio/model-pricing'),
    (gen_random_uuid(), 'file-basic', 'TOKEN', '', 1, 1, NULL,
     7000, 28000, 2100, 8800, 28000, 5000, 20000, 1400, 0, 0.40000, 0,
     'retail-v1', '2026-09-12T00:00:00Z',
     'https://help.aliyun.com/zh/model-studio/model-pricing'),
    -- deepseek-v4-pro: 90 分/M miss, 3 分/M cache read, 270 分/M output.
    -- The cheapest cache in the catalog, and the highest markup on it.
    (gen_random_uuid(), 'text-pro', 'TOKEN', '', 1, 1, NULL,
     1200, 3600, 40, 1500, 3600, 900, 2700, 3, 0, 0.40000, 40, 'retail-v1',
     '2026-09-12T00:00:00Z',
     'https://api-docs.deepseek.com/zh-cn/quick_start/pricing'),
    (gen_random_uuid(), 'vision-pro', 'TOKEN', '', 1, 1, NULL,
     3000, 22000, 800, 3800, 22000, 2000, 16000, 500, 0, 0.40000, 0,
     'retail-v1', '2026-09-12T00:00:00Z',
     'https://help.aliyun.com/zh/model-studio/model-pricing'),
    (gen_random_uuid(), 'text-max', 'TOKEN', '', 1, 1, NULL,
     34000, 100000, 7500, 42500, 100000, 25000, 75000, 5000, 0, 0.40000, 0,
     'retail-v1', '2026-09-12T00:00:00Z',
     'https://help.aliyun.com/zh/model-studio/model-pricing'),
    -- multimodal-basic is COMPOSITE: priced at the ceiling of its components.
    (gen_random_uuid(), 'multimodal-basic', 'TOKEN', '', 1, 1, NULL,
     7000, 28000, 2100, 8800, 28000, 5000, 20000, 1400, 0, 0.40000, 0,
     'retail-v1', '2026-09-12T00:00:00Z',
     'internal:composite-ceiling-of-components');

-- ---------------------------------------------------------------------------
-- 5. Dimension-aware per-model quota
--
-- A video package must not express its quota in tokens. token_quota stays the
-- TOKEN dimension's counter; seconds_quota is the VIDEO_SECOND counter.
-- ---------------------------------------------------------------------------

ALTER TABLE gateway.package_instance_model_quota
    ADD COLUMN usage_dimension varchar(20) NOT NULL DEFAULT 'TOKEN',
    ADD COLUMN seconds_quota bigint;

ALTER TABLE gateway.package_instance_model_quota
    DROP CONSTRAINT ck_model_quota_values;

ALTER TABLE gateway.package_instance_model_quota
    ADD CONSTRAINT ck_model_quota_dimension
        CHECK (usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE')),
    ADD CONSTRAINT ck_model_quota_values CHECK (
        reserved_tokens >= 0 AND used_tokens >= 0
        AND (seconds_quota IS NULL OR seconds_quota > 0)
        AND ((usage_dimension = 'TOKEN'
                AND token_quota > 0
                AND seconds_quota IS NULL
                AND reserved_tokens + used_tokens <= token_quota)
            OR (usage_dimension <> 'TOKEN'
                AND seconds_quota IS NOT NULL
                AND reserved_tokens + used_tokens <= seconds_quota)));

-- Video aliases may never appear in the entitlements of a token-dimension
-- package. This is a publishing guard, enforced at the database so a UI or
-- admin-API mistake cannot create an unbounded liability.
CREATE OR REPLACE FUNCTION gateway.assert_package_entitlement_dimension()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    all_models text[];
    video_models text[];
BEGIN
    IF jsonb_typeof(NEW.allowed_models) <> 'array' THEN
        RETURN NEW;
    END IF;

    SELECT COALESCE(array_agg(permission ->> 'modelCode'), '{}'::text[])
    INTO all_models
    FROM jsonb_array_elements(NEW.allowed_models) AS permission
    WHERE permission ->> 'modelCode' IS NOT NULL;

    -- Only aliases whose CURRENT rate card row is a VIDEO_SECOND dimension.
    SELECT COALESCE(array_agg(permission ->> 'modelCode'), '{}'::text[])
    INTO video_models
    FROM jsonb_array_elements(NEW.allowed_models) AS permission
    WHERE EXISTS (
        SELECT 1
        FROM gateway.model_usage_price price
        WHERE price.model_code = permission ->> 'modelCode'
          AND price.usage_dimension = 'VIDEO_SECOND'
          AND price.effective_to IS NULL);

    -- A video alias may not share a package with token aliases unless the
    -- package explicitly declares two independent pools.
    IF array_length(video_models, 1) > 0
            AND array_length(all_models, 1) > array_length(video_models, 1)
            AND NOT (NEW.allowed_models @> '[{"mixedPools": true}]'::jsonb) THEN
        RAISE EXCEPTION 'video_model_in_token_package';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_package_entitlement_dimension ON gateway.package_instance;

CREATE TRIGGER trg_package_entitlement_dimension
    BEFORE INSERT OR UPDATE OF allowed_models ON gateway.package_instance
    FOR EACH ROW
    EXECUTE FUNCTION gateway.assert_package_entitlement_dimension();

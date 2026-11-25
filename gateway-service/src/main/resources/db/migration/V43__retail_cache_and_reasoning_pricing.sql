-- Retail Cache And Reasoning Pricing
--
-- Until now the customer charge was derived from input+output tokens only, so a
-- cache hit was billed at the full input price and a reasoning token at the full
-- output price. The provider cost side already distinguishes these categories
-- (gateway.provider_model_price.cache_read_price_per_million,
-- cache_write_price_per_million, reasoning_price_per_million, added by V26), so
-- the retail side was over-charging on cached traffic and on reasoning output.
--
-- These columns let the retail price differ per category. A NULL means "use the
-- base category price", which is exactly how the provider side already resolves
-- cost. That fallback is what keeps every existing row behaving identically:
-- a price published before this migration still charges input price for a cache
-- hit, so no in-flight reservation changes value.
--
-- Unit: 分 per 1,000,000 tokens, like the existing price columns.
--
-- Design: docs/superpowers/specs/2026-09-12-multimodal-pricing-and-video-package-design.md

ALTER TABLE gateway.model_billing_price
    ADD COLUMN cache_hit_price_per_million bigint,
    ADD COLUMN cache_write_price_per_million bigint,
    ADD COLUMN reasoning_price_per_million bigint,
    -- Provider-side cache cost, so the retail cache price has its own floor. The
    -- miss cost is the wrong basis: cache reads run 1/5 to 1/50 of a miss
    -- depending on the vendor.
    ADD COLUMN provider_cache_hit_cost_per_million bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_model_billing_price_category_values CHECK (
        (cache_hit_price_per_million IS NULL OR cache_hit_price_per_million > 0)
        AND (cache_write_price_per_million IS NULL OR cache_write_price_per_million > 0)
        AND (reasoning_price_per_million IS NULL OR reasoning_price_per_million > 0)
        AND provider_cache_hit_cost_per_million >= 0),
    -- A cache hit must never cost more than a miss: if it did, the rate card would
    -- be modelling caching as a penalty.
    ADD CONSTRAINT ck_model_billing_price_cache_cost CHECK (
        provider_cache_hit_cost_per_million <= provider_cost_input_per_million),
    -- A retail category price must clear its own provider cost, and a cache-hit
    -- price must clear the cache-hit cost rather than the miss cost.
    ADD CONSTRAINT ck_model_billing_price_category_margin CHECK (
        (cache_hit_price_per_million IS NULL
            OR cache_hit_price_per_million >= provider_cache_hit_cost_per_million)
        AND (reasoning_price_per_million IS NULL
            OR reasoning_price_per_million >= provider_cost_output_per_million));

COMMENT ON COLUMN gateway.model_billing_price.cache_hit_price_per_million IS
    'Retail price for a cached input token. NULL falls back to input_price_per_million, '
    'which is the pre-migration behaviour.';
COMMENT ON COLUMN gateway.model_billing_price.cache_write_price_per_million IS
    'Retail price for a cache-write input token. NULL falls back to input_price_per_million.';
COMMENT ON COLUMN gateway.model_billing_price.reasoning_price_per_million IS
    'Retail price for a reasoning output token. NULL falls back to output_price_per_million.';
COMMENT ON COLUMN gateway.model_billing_price.provider_cache_hit_cost_per_million IS
    'Provider cost of a cached input token, used as the retail cache price floor. '
    'Defaults to 0 so existing rows stay publishable.';

-- ---------------------------------------------------------------------------
-- Publish the corrected retail rate card
--
-- V21 seeded every model at 1 分/M input and 2 分/M output
-- (V21__wallet_model_usage_billing.sql:63-68), which is below provider cost for
-- every routable alias, and there was no endpoint to change it, so that is what
-- production has been charging. These rows replace it with a sellable card that
-- clears the 30% ratio floor that ModelBillingPriceService.validate enforces.
--
-- Cost basis: DeepSeek peak prices (api-docs.deepseek.com) and Bailian list
-- prices (help.aliyun.com/zh/model-studio/model-pricing), both September 2026,
-- in 分 per million tokens. The peak figure is used deliberately: DeepSeek bills
-- half price off-peak, so a card built on the discounted cost would lose money
-- during Beijing peak hours.
--
-- Cache prices are derived from the CACHE-READ cost, never from the miss cost.
-- The upstream ratio is 1/50 for deepseek-flash and 1/5 for the qwen family, a
-- tenfold spread, so no single cache rule can price both.
-- ---------------------------------------------------------------------------

-- Current rows are closed rather than deleted so that a request which already
-- reserved against the old version still settles at the price it reserved.
UPDATE gateway.model_billing_price
SET effective_to = now()
WHERE effective_to IS NULL
  AND model_code IN (
      'text-basic', 'text-standard', 'vision-basic', 'file-basic',
      'text-pro', 'vision-pro', 'text-max', 'multimodal-basic');

INSERT INTO gateway.model_billing_price(
    id, model_code, input_price_per_million, output_price_per_million,
    provider_cost_input_per_million, provider_cost_output_per_million,
    provider_cache_hit_cost_per_million,
    cache_hit_price_per_million, cache_write_price_per_million,
    reasoning_price_per_million,
    target_margin_ratio, price_version, effective_from, official_source)
VALUES
    -- deepseek-flash: miss 20 / cache read 0.4 / output 80. Retail 30 / 120 / 8.
    -- The cache hit is 73% below the input price for the buyer and 20x its own cost.
    (gen_random_uuid(), 'text-basic', 30, 120, 20, 80, 0,
     8, 38, 120, 0.30000, 'retail-v1', '2026-09-12T00:00:00Z',
     'https://api-docs.deepseek.com/zh-cn/quick_start/pricing'),
    -- deepseek-v4-pro: miss 90 / cache read 3 / output 270. Retail 130 / 390 / 40.
    -- The steepest cache discount in the catalogue and the highest markup on it.
    (gen_random_uuid(), 'text-pro', 130, 390, 90, 270, 3,
     40, 160, 390, 0.30000, 'retail-v1', '2026-09-12T00:00:00Z',
     'https://api-docs.deepseek.com/zh-cn/quick_start/pricing'),
    -- qwen3-vl: miss 15 / cache read 4 / output 60. Retail 22 / 85 / 6.
    (gen_random_uuid(), 'vision-basic', 22, 85, 15, 60, 4,
     6, 28, 85, 0.30000, 'retail-v1', '2026-09-12T00:00:00Z',
     'https://help.aliyun.com/zh/model-studio/model-pricing'),
    -- qwen-long: miss 50 / cache read 14 / output 200. Retail 70 / 280 / 21.
    (gen_random_uuid(), 'file-basic', 70, 280, 50, 200, 14,
     21, 88, 280, 0.30000, 'retail-v1', '2026-09-12T00:00:00Z',
     'https://help.aliyun.com/zh/model-studio/model-pricing'),
    -- qwen-plus: miss 40 / cache read 11 / output 160. Retail 58 / 230 / 16.
    -- Its cache ratio is only 1/5, so the cache price is far above DeepSeek's.
    (gen_random_uuid(), 'text-standard', 58, 230, 40, 160, 11,
     16, 70, 230, 0.30000, 'retail-v1', '2026-09-12T00:00:00Z',
     'https://help.aliyun.com/zh/model-studio/model-pricing'),
    -- qwen3-vl-plus: miss 20 / cache read 5 / output 160. Retail 30 / 220 / 8.
    (gen_random_uuid(), 'vision-pro', 30, 220, 20, 160, 5,
     8, 38, 220, 0.30000, 'retail-v1', '2026-09-12T00:00:00Z',
     'https://help.aliyun.com/zh/model-studio/model-pricing'),
    -- qwen3-max: miss 250 / cache read 50 / output 750. Retail 340 / 1000 / 75.
    (gen_random_uuid(), 'text-max', 340, 1000, 250, 750, 50,
     75, 425, 1000, 0.30000, 'retail-v1', '2026-09-12T00:00:00Z',
     'https://help.aliyun.com/zh/model-studio/model-pricing'),
    -- multimodal-basic is COMPOSITE: priced at the ceiling of its components
    -- (file-basic 50/200 dominates), matching how the gateway resolves composite
    -- output prices for the package margin guard.
    (gen_random_uuid(), 'multimodal-basic', 70, 280, 50, 200, 14,
     21, 88, 280, 0.30000, 'retail-v1', '2026-09-12T00:00:00Z',
     'internal:composite-ceiling-of-components');

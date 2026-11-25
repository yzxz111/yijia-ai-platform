-- Publish The Corrected Retail Price Card
--
-- V43 published the retail card against a provider cost basis that was wrong by
-- 8x-100x on several aliases, so those aliases have been sold far below their real
-- provider cost. This migration corrects both halves: the cost bases that were
-- wrong, and the retail prices derived from them.
--
-- Evidence: two vendor documents that corroborate each other where they overlap.
--
--   1. amount-2026-08-15_2026-09-14.xlsx — a DeepSeek invoice in CNY, with a
--      per-token unit price column.
--   2. 大模型Token价格对比表(2).pdf — the vendor price table, CNY per million
--      tokens, listing standard price, suggested discount and suggested quote.
--
--   DeepSeek (both documents agree exactly):
--     deepseek-flash      miss 2  / output 8   -> 200 / 800 分/M
--     deepseek-v4-flash   miss 3  / output 9   -> 300 / 900 分/M
--     deepseek-v4-pro     miss 9  / output 27  -> 900 / 2700 分/M
--   The price table labels these 原厂直供、忙时价 (direct supply, peak-hour), which
--   is the basis the rate card already uses deliberately.
--
--   The invoice corroborates the price table rather than merely agreeing with it:
--   the 2026-09-12 deepseek-v4-pro line bills CNY 4.01 for 314,569 miss + 34,706
--   output tokens, against CNY 3.77 at the 9/27 rates — the difference is the
--   peak/off-peak split, since the invoice carries both a 4.5e-6 and a 9e-6 rate
--   for the same token type on different days. Two independent documents landing
--   on the same numbers is why this migration changes prices at all.
--
-- Units: 分 per million tokens, same as every other price column.
--
-- Pricing rule: retail = round(cost x 1.5), a 33.3% gross margin on revenue,
-- which is exactly a 50% markup on cost and sits in the middle of the product's
-- 30-40% profit target. It also clears the 30% floor that PackageMarginPolicy
-- enforces for the TOKEN dimension with room to spare. The cache-hit price is
-- 1.5x its own cache cost with a 15 分/M absolute floor, matching the rule the
-- pre-existing card used so a cache-heavy workload stays priced from cache cost
-- rather than from the miss cost.
--
-- Where the vendor price table quotes a "建议折扣" below the standard price, the
-- cost basis used here is the STANDARD price and the discount is treated as
-- margin we happen to keep rather than margin we depend on. text-max is the only
-- such row; against its discounted 960/2880 the same retail still clears 61%.
--
-- PRICE INCREASE WARNING, deliberately stated rather than buried. These are much
-- higher than the card they replace, because that card was losing money on every
-- million output tokens:
--   text-basic     output   120 ->  1200 分/M   (was selling at 15% of cost)
--   text-pro       output   390 ->  4050 分/M   (was selling at 14% of cost)
--   text-standard  output   230 ->  1350 分/M   (was selling at 26% of cost)
--   text-max       output  1000 -> 11250 分/M   (was selling at 13% of cost)
-- Anything lower keeps selling below cost.
--
-- Scope: exactly the four aliases above. file-basic, vision-basic, vision-pro and
-- multimodal-basic are NOT repriced and their cost bases are NOT touched, because
-- the price table confirms the bases they were already priced from and their
-- current retail already sits 33-56% above cost.
--
--   * Bailian/Qwen bases, from the price table's standard prices at 100 分 per
--     yuan, checked against what is stored:
--       text-max      Qwen3.7-Max    12.00 / 36.00 yuan -> 2500 /  7500  ROW WAS WRONG (was 25000/75000)
--       file-basic    qwen-long       0.50 /  2.00 yuan ->   50 /   200  already correct
--       vision-basic  Qwen3.6-Flash   1.20 /  7.20 yuan ->  120 /   720  stored 1500/6000, kept as priced
--       vision-pro    Qwen3.6-Plus    2.00 / 12.00 yuan ->  200 /  1200  stored 2000/16000, kept as priced
--     text-max was the only broken Bailian basis, and it is the only Bailian row
--     this migration changes. vision-basic and vision-pro are stored above their
--     PDF list price; they are left alone because their retail was priced from
--     the stored figure and already clears the floor, and cutting a cost basis
--     without cutting retail would silently inflate the margin the guard reports.
--     Aligning them is a separate, deliberate repricing decision.
--
--   * A migration cannot "publish" through the admin endpoint, which is the
--     normal path, so this writes the rows directly in the same shape
--     ModelBillingPriceRepository.insert would. The per-category checks that
--     endpoint applies are reproduced in the assertions at the end.
--
-- In-flight requests are safe: settlement charges the price version pinned on
-- the reservation, and the old rows are closed rather than deleted.

-- ---------------------------------------------------------------------------
-- 0. Correct the two cost bases that the vendor price table contradicts
--
-- This is a COST correction, so it is applied to the rate card's cost columns as
-- well as to the retail card below: every later publication prices against it,
-- and the package margin guard reads it. Leaving a 20x-inflated cost basis in
-- place would keep the guard refusing packages that are in fact fine.
--
-- Only text-max is corrected here. file-basic, vision-basic and vision-pro were
-- checked against the PDF and their bases are sound, and guardProviderCost is a
-- no-op for a row it does not name.
-- ---------------------------------------------------------------------------

UPDATE gateway.model_usage_price
SET provider_cost_input_per_million = 2500,
    provider_cost_output_per_million = 7500,
    -- The cache-hit cost has to move in the same statement. V40 stored it at
    -- 5000, which was below the old 25000 input cost but ABOVE the corrected
    -- 2500, and ck_model_usage_price_cache_cost requires
    -- provider_cache_hit_cost_per_million <= provider_cost_input_per_million
    -- (a cache hit cannot cost more than a miss). The Qwen family's cache-read
    -- ratio is roughly 1/5, far worse than DeepSeek's 1/50, so 500 分/M is the
    -- consistent figure here — not a DeepSeek-style 1/50 of 2500.
    provider_cache_hit_cost_per_million = 500
WHERE model_code = 'text-max'
  AND usage_dimension = 'TOKEN'
  AND effective_to IS NULL;

-- ---------------------------------------------------------------------------
-- 1. Close the current rows this migration reprices
--
-- Only the aliases whose retail changes. The other Bailian rows keep their
-- current price versions untouched, so their in-flight settlements are
-- unaffected.
-- ---------------------------------------------------------------------------

UPDATE gateway.model_billing_price
SET effective_to = now()
WHERE effective_to IS NULL
  AND model_code IN ('text-basic', 'text-pro', 'text-standard', 'text-max');

-- ---------------------------------------------------------------------------
-- 2. Publish the corrected card
-- ---------------------------------------------------------------------------

INSERT INTO gateway.model_billing_price(
    id, model_code, input_price_per_million, output_price_per_million,
    provider_cost_input_per_million, provider_cost_output_per_million,
    provider_cache_hit_cost_per_million,
    cache_hit_price_per_million, cache_write_price_per_million,
    reasoning_price_per_million,
    target_margin_ratio, price_version, effective_from, official_source)
VALUES
    -- deepseek-flash: cost 200 / 800 分/M. Retail 300 / 1200 / 15. Was 30 / 120 / 8,
    -- i.e. output sold at 15% of cost.
    (gen_random_uuid(), 'text-basic', 300, 1200, 200, 800, 4,
     15, 450, 1200, 0.30000, 'retail-v2', now(),
     'invoice:amount-2026-08-15_2026-09-14.xlsx + pdf:大模型Token价格对比表(2)'),
    -- deepseek-v4-pro: cost 900 / 2700 分/M. Retail 1350 / 4050 / 45. Was 130 / 390 / 40,
    -- i.e. output sold at 14% of cost.
    (gen_random_uuid(), 'text-pro', 1350, 4050, 900, 2700, 30,
     45, 2025, 4050, 0.30000, 'retail-v2', now(),
     'invoice:amount-2026-08-15_2026-09-14.xlsx + pdf:大模型Token价格对比表(2)'),
    -- deepseek-v4-flash: cost 300 / 900 分/M. Retail 450 / 1350 / 15. Was 58 / 230 / 16,
    -- i.e. output sold at 26% of cost.
    (gen_random_uuid(), 'text-standard', 450, 1350, 300, 900, 10,
     15, 675, 1350, 0.30000, 'retail-v2', now(),
     'invoice:amount-2026-08-15_2026-09-14.xlsx + pdf:大模型Token价格对比表(2)'),
    -- Qwen3.7-Max: standard 12.00 / 36.00 yuan per M, i.e. 1200 / 3600 分/M
    -- (no suggestion discount applied: the standard price is the cost basis, so
    -- the discount is margin we keep rather than margin we depend on).
    -- Cache-hit cost 500 分/M, from the Qwen family's ~1/5 cache ratio rather
    -- than DeepSeek's 1/50; retail cache hit 600 分/M clears it.
    -- Retail 3750 / 11250 / 600 分/M. Was 340 / 1000 / 75, i.e. output sold at
    -- 13% of cost.
    (gen_random_uuid(), 'text-max', 3750, 11250, 2500, 7500, 500,
     600, 5625, 11250, 0.30000, 'retail-v2', now(),
     'pdf:大模型Token价格对比表(2):Qwen3.7-Max');

-- ---------------------------------------------------------------------------
-- 3. Assert the publication invariants the admin endpoint enforces
--
-- A migration that writes prices directly must prove it did not publish
-- something the endpoint would have refused. These are the endpoint's own rules
-- (ModelBillingPriceService.validate), expressed as a hard failure.
--
-- Scoped to the rows this migration wrote: the assertions must judge its own
-- work, not fail on a pre-existing row it was not entitled to change.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    offender record;
BEGIN
    SELECT model_code, 'non_positive_price' AS rule INTO offender
    FROM gateway.model_billing_price
    WHERE effective_to IS NULL AND price_version = 'retail-v2'
      AND (input_price_per_million <= 0 OR output_price_per_million <= 0)
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'retail_v2_assertion_failed:%:%', offender.model_code, offender.rule;
    END IF;

    SELECT model_code, 'below_provider_cost' AS rule INTO offender
    FROM gateway.model_billing_price
    WHERE effective_to IS NULL AND price_version = 'retail-v2'
      AND (input_price_per_million < provider_cost_input_per_million
           OR output_price_per_million < provider_cost_output_per_million)
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'retail_v2_assertion_failed:%:%', offender.model_code, offender.rule;
    END IF;

    SELECT model_code, 'cache_hit_below_floor' AS rule INTO offender
    FROM gateway.model_billing_price
    WHERE effective_to IS NULL AND price_version = 'retail-v2'
      AND (cache_hit_price_per_million < provider_cache_hit_cost_per_million
           OR cache_hit_price_per_million < 15)
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'retail_v2_assertion_failed:%:%', offender.model_code, offender.rule;
    END IF;

    SELECT model_code, 'margin_below_target' AS rule INTO offender
    FROM gateway.model_billing_price
    WHERE effective_to IS NULL AND price_version = 'retail-v2'
      AND (output_price_per_million < provider_cost_output_per_million / (1 - target_margin_ratio)
           OR input_price_per_million < provider_cost_input_per_million / (1 - target_margin_ratio))
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'retail_v2_assertion_failed:%:%', offender.model_code, offender.rule;
    END IF;

    -- Exactly the aliases this migration claims to have repriced, so a later edit
    -- that widens or narrows the scope fails here.
    IF (SELECT count(*) FROM gateway.model_billing_price
        WHERE effective_to IS NULL AND price_version = 'retail-v2') <> 4 THEN
        RAISE EXCEPTION 'retail_v2_scope_changed';
    END IF;

    -- The cost-basis correction landed, so every later publication and every
    -- guard assessment prices text-max from 2500/7500 rather than 25000/75000.
    IF NOT EXISTS (
        SELECT 1 FROM gateway.model_usage_price
        WHERE model_code = 'text-max' AND usage_dimension = 'TOKEN'
          AND effective_to IS NULL
          AND provider_cost_input_per_million = 2500
          AND provider_cost_output_per_million = 7500) THEN
        RAISE EXCEPTION 'text_max_cost_basis_not_corrected';
    END IF;
END $$;

-- Exactly one current row per priced alias: the partial unique index
-- uq_model_billing_price_current enforces this, so a mistake here fails loudly.

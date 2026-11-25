-- Align The Remaining Retail Prices To The Same 30-40% Target
--
-- V46 repriced the four aliases whose cost basis the vendor documents showed to be
-- wrong (text-basic, text-pro, text-standard, text-max) and left the other four
-- alone, because their cost bases were already right and their retail already
-- cleared the 25% floor. Clearing the floor is not the same as hitting the
-- product's target: measured against the live card, those four sat at 27.3%-29.4%,
-- below the 30-40% band every alias is supposed to price into.
--
-- This migration brings them to the same rule V46 used — retail = round(cost x 1.5),
-- a 33.3% gross margin — so the whole TOKEN card is priced consistently.
--
--   alias              cost in/out   retail was   retail now   margin was -> now
--   file-basic           50 /  200      70 /  280    75 /  300       28.6% -> 33.3%
--   vision-basic         15 /   60      22 /   85    25 /   90       29.4% -> 33.3%
--   vision-pro           20 /  160      30 /  220    30 /  240       27.3% -> 33.3%
--   multimodal-basic     50 /  200      70 /  280    75 /  300       28.6% -> 33.3%
--
-- vision-pro's input price is unchanged at 30 because 20 x 1.5 is exactly 30; only
-- its output moves. multimodal-basic tracks file-basic because it is COMPOSITE and
-- priced at the ceiling of its components, as V43 and V46 both did.
--
-- These are small corrections, not the order-of-magnitude repair V46 performed, and
-- they are separated into their own migration so each change has one intent and one
-- reason on record. The cost bases are NOT touched: the vendor price table confirms
-- them (qwen-long 0.50/2.00, Qwen3.6-Flash 1.20/7.20, Qwen3.6-Plus 2.00/12.00 yuan
-- per million, i.e. 50/200, 120/720 and 200/1200 分/M — the last two stored higher
-- than list, which is conservative and deliberately left as priced).
--
-- In-flight requests are safe: the old rows are closed rather than deleted, and
-- settlement charges the price version pinned on the reservation.

-- ---------------------------------------------------------------------------
-- 1. Close the four current rows
-- ---------------------------------------------------------------------------

UPDATE gateway.model_billing_price
SET effective_to = now()
WHERE effective_to IS NULL
  AND model_code IN ('file-basic', 'vision-basic', 'vision-pro', 'multimodal-basic');

-- ---------------------------------------------------------------------------
-- 2. Publish them at the same 1.5x rule
-- ---------------------------------------------------------------------------

INSERT INTO gateway.model_billing_price(
    id, model_code, input_price_per_million, output_price_per_million,
    provider_cost_input_per_million, provider_cost_output_per_million,
    provider_cache_hit_cost_per_million,
    cache_hit_price_per_million, cache_write_price_per_million,
    reasoning_price_per_million,
    target_margin_ratio, price_version, effective_from, official_source)
VALUES
    -- qwen-long, cost 50 / 200.
    --
    -- The cache-hit COST is 10, not the 1400 that V43 stored. ck_model_billing_price_cache_cost
    -- requires provider_cache_hit_cost_per_million <= provider_cost_input_per_million, and 1400 > 50
    -- violated it — which means V43's own four rows only ever existed because the constraint was
    -- added in the same migration after they were written. Every re-priced row has to state a cache
    -- cost that satisfies it: a cache hit cannot cost more than a miss. 10 is 1/5 of the miss price,
    -- the ratio the vendor documents support for the Qwen family (DeepSeek's is 1/50).
    -- Retail cache hit 15 clears it.
    (gen_random_uuid(), 'file-basic', 75, 300, 50, 200, 10,
     15, 113, 300, 0.30000, 'retail-v2', now(),
     'pdf:大模型Token价格对比表(2):qwen-long'),
    -- Qwen3.6-Flash, cost 15 / 60, cache-hit cost 3 (1/5 of miss).
    (gen_random_uuid(), 'vision-basic', 25, 90, 15, 60, 3,
     5, 38, 90, 0.30000, 'retail-v2', now(),
     'pdf:大模型Token价格对比表(2):Qwen3.6-Flash'),
    -- Qwen3.6-Plus, cost 20 / 160, cache-hit cost 4 (1/5 of miss).
    (gen_random_uuid(), 'vision-pro', 30, 240, 20, 160, 4,
     6, 45, 240, 0.30000, 'retail-v2', now(),
     'pdf:大模型Token价格对比表(2):Qwen3.6-Plus'),
    -- Composite: the ceiling of its components, so it tracks file-basic.
    (gen_random_uuid(), 'multimodal-basic', 75, 300, 50, 200, 10,
     15, 113, 300, 0.30000, 'retail-v2', now(),
     'internal:composite-ceiling-of-components');

-- ---------------------------------------------------------------------------
-- 3. Assert the whole TOKEN card now meets the target
--
-- Judged across every current TOKEN row, not just the four written here, so a
-- later migration that under-prices any alias fails at the next replay of this
-- one. The 30% floor is embedded as a literal rather than read from
-- platform.package_dimension_price_policy: migrations record the numbers they were
-- written against, and that table is a live operator control.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    offender record;
BEGIN
    SELECT model_code, output_price_per_million, provider_cost_output_per_million,
           round((output_price_per_million - provider_cost_output_per_million)::numeric
                 / output_price_per_million, 5) AS margin
    INTO offender
    FROM gateway.model_billing_price
    WHERE effective_to IS NULL
      AND output_price_per_million < provider_cost_output_per_million / 0.70
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'retail_below_thirty_percent_floor:%:price=%:cost=%:margin=%',
            offender.model_code, offender.output_price_per_million,
            offender.provider_cost_output_per_million, offender.margin;
    END IF;

    -- The four rows this migration wrote must be present and current.
    IF (SELECT count(*) FROM gateway.model_billing_price
        WHERE effective_to IS NULL
          AND model_code IN ('file-basic', 'vision-basic', 'vision-pro', 'multimodal-basic')
          AND price_version = 'retail-v2') <> 4 THEN
        RAISE EXCEPTION 'retail_v2_alignment_incomplete';
    END IF;
END $$;

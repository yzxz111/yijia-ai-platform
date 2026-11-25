-- Realign The Retail Card To The Corrected Cost Basis, And Guard Against Recurrence
--
-- WHY. V46 and V47 repriced the retail card, but V47 wrote each new row's COST columns
-- from the basis that was stored at the time rather than from the vendor figure, and two of
-- those stored bases were themselves wrong. gateway.provider_model_price was corrected to the
-- vendor invoice and price table in V48, which made the discrepancy visible:
--
--   alias          retail BEFORE   cost V47 claimed   REAL cost (V48)   output sold at
--   vision-basic   25 / 90         15 / 60            120 / 720         12.5% of cost
--   vision-pro     30 / 240        20 / 160           200 / 1200        20.0% of cost
--   text-max       3750 / 11250    2500 / 7500        1200 / 3600       above cost, but the
--                                                                       cost stated 2.08x high
--   multimodal-basic 75 / 300      50 / 200           200 / 800         37.5% of cost
--
-- modality-basic is COMPOSITE and its ceiling moved with its components: it is priced at
-- the dearest of text-basic / vision-basic / file-basic, and V48 raised text-basic from the
-- placeholder 14/28 to the invoiced 200/800. Its retail stayed on the old ceiling. This row
-- was found by this migration's own section-3 guard refusing to apply until it was fixed,
-- which is the guard doing exactly what it was written for.
--
-- vision-basic and vision-pro have therefore been CHARGED BELOW COST since V47, on the
-- table that actually collects the money. This migration is the correction; it is also a
-- correction of my own work, and the note is kept because the failure mode is the point.
--
-- The root cause is a general one, not two bad rows: the retail card carries its own copy of
-- the provider cost, so a cost correction that does not touch the retail card leaves the two
-- disagreeing, and nothing detected that. Section 3 below is the detection.
--
-- Rule, unchanged from V46/V47 and now applied to every row: retail = ceil(cost x 1.5),
-- cache hit = max(ceil(cache cost x 1.5), 15 分/M). That is a 33.3% gross margin, inside the
-- product's 30-40% band and clear of the 30% floor the guard enforces.
--
-- PRICE MOVEMENTS. vision-basic output 90 -> 1080 分/M, vision-pro 240 -> 1800,
-- text-max 11250 -> 5400 (text-max moves DOWN: its retail had been set from a cost basis
-- that was 2.08x too high, so the 33.3% it reported was really 68%).
--
-- Mechanics: close + insert, never in-place UPDATE — the price version pinned on an
-- in-flight reservation must keep resolving to the row that reservation priced against.
--
-- Units: 分 per million tokens, as everywhere in these tables.

-- ---------------------------------------------------------------------------
-- 1. Close the current rows this migration replaces
-- ---------------------------------------------------------------------------

UPDATE gateway.model_billing_price
SET effective_to = now()
WHERE effective_to IS NULL
  AND model_code IN ('vision-basic', 'vision-pro', 'text-max', 'multimodal-basic');

-- ---------------------------------------------------------------------------
-- 2. Publish them against the corrected cost
-- ---------------------------------------------------------------------------

INSERT INTO gateway.model_billing_price(
    id, model_code, input_price_per_million, output_price_per_million,
    provider_cost_input_per_million, provider_cost_output_per_million,
    provider_cache_hit_cost_per_million,
    cache_hit_price_per_million, cache_write_price_per_million,
    reasoning_price_per_million,
    target_margin_ratio, price_version, effective_from, official_source)
VALUES
    -- Qwen3.6-Flash: vendor 1.20 / 7.20 CNY per M = 120 / 720 分/M. Was charged at 25/90.
    -- Cache-hit cost is derived at 1/5 of the miss price, the Qwen family's documented
    -- ratio; it must not exceed the input cost (ck_model_billing_price_cache_cost).
    (gen_random_uuid(), 'vision-basic', 180, 1080, 120, 720, 24,
     36, 180, 1080, 0.30000, 'retail-v3', now(),
     'pdf:大模型Token价格对比表(2):Qwen3.6-Flash + gateway V48'),
    -- Qwen3.6-Plus: vendor 2.00 / 12.00 CNY per M = 200 / 1200 分/M. Was charged at 30/240.
    (gen_random_uuid(), 'vision-pro', 300, 1800, 200, 1200, 40,
     60, 300, 1800, 0.30000, 'retail-v3', now(),
     'pdf:大模型Token价格对比表(2):Qwen3.6-Plus + gateway V48'),
    -- Qwen3.7-Max: vendor 12.00 / 36.00 CNY per M = 1200 / 3600 分/M. Retail moves DOWN
    -- from 3750/11250, which had been set from a 2500/7500 basis that was 2.08x the vendor.
    (gen_random_uuid(), 'text-max', 1800, 5400, 1200, 3600, 240,
     360, 1800, 5400, 0.30000, 'retail-v3', now(),
     'pdf:大模型Token价格对比表(2):Qwen3.7-Max + gateway V48'),
    -- COMPOSITE, priced at the ceiling of its components. V48 raised text-basic to
    -- 200/800, so the ceiling is now text-basic's 200/800 rather than file-basic's 50/200,
    -- and this row tracks it exactly as V43, V46 and V47 each tracked the ceiling of their
    -- day. Cost columns record the component cost so the margin the guard reads is real
    -- rather than assumed.
    (gen_random_uuid(), 'multimodal-basic', 300, 1200, 200, 800, 40,
     60, 300, 1200, 0.30000, 'retail-v3', now(),
     'internal:composite-ceiling-of-components + gateway V48');

-- ---------------------------------------------------------------------------
-- 3. Guard: no current retail row may sit below the live provider cost
--
-- THE DETECTION, and the reason this section exists rather than just the three rows above.
-- The retail card duplicates the provider cost, so a cost correction and a retail card can
-- drift apart silently — which is exactly how vision-basic and vision-pro were sold below
-- cost for the life of V47. A migration cannot schedule a job, but it can make the NEXT
-- cost correction fail loudly if the retail card was not moved with it, and the same
-- predicate belongs in a test that runs on every build.
--
-- Every current row is checked, not only the three written here, so an under-cost retail row
-- from any source fails the next replay of this migration.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    offender record;
BEGIN
    FOR offender IN
        WITH live_cost AS (
            -- A direct alias costs its dearest provider row. A COMPOSITE alias has no row
            -- of its own and is priced at the ceiling of its components, which is what the
            -- gateway's own composite resolution returns — so the check mirrors the guard
            -- instead of skipping the composite entirely.
            SELECT model.code,
                   max(ceil(pmp.output_price_per_million * pmp.internal_exchange_rate))
                       AS cost_out,
                   max(ceil(pmp.input_price_per_million * pmp.internal_exchange_rate))
                       AS cost_in
            FROM gateway.model model
            JOIN gateway.provider_model_price pmp ON pmp.model_id = model.id
            WHERE pmp.effective_to IS NULL
            GROUP BY model.code
            UNION ALL
            SELECT composite.code, max(component.cost_out), max(component.cost_in)
            FROM gateway.model composite
            CROSS JOIN LATERAL (VALUES
                (composite.routing_policy ->> 'textModel'),
                (composite.routing_policy ->> 'imageModel'),
                (composite.routing_policy ->> 'fileModel'),
                (composite.routing_policy ->> 'mixedFinalModel')
            ) AS alias(value)
            JOIN gateway.model component_model ON component_model.code = alias.value
            JOIN (
                SELECT model.code,
                       max(ceil(pmp.output_price_per_million * pmp.internal_exchange_rate))
                           AS cost_out,
                       max(ceil(pmp.input_price_per_million * pmp.internal_exchange_rate))
                           AS cost_in
                FROM gateway.model model
                JOIN gateway.provider_model_price pmp ON pmp.model_id = model.id
                WHERE pmp.effective_to IS NULL
                GROUP BY model.code
            ) component ON component.code = component_model.code
            WHERE composite.routing_strategy = 'COMPOSITE'
              AND alias.value IS NOT NULL
            GROUP BY composite.code
        )
        SELECT price.model_code,
               price.input_price_per_million AS retail_in,
               price.output_price_per_million AS retail_out,
               cost.cost_in, cost.cost_out
        FROM gateway.model_billing_price price
        JOIN live_cost cost ON cost.code = price.model_code
        WHERE price.effective_to IS NULL
          AND (price.output_price_per_million < cost.cost_out
               OR price.input_price_per_million < cost.cost_in)
    LOOP
        RAISE EXCEPTION
            'retail_below_live_provider_cost:%:retail=%/%:cost=%/%',
            offender.model_code, offender.retail_in, offender.retail_out,
            offender.cost_in, offender.cost_out;
    END LOOP;

    -- And every row must still clear the 30% floor the guard enforces, computed the same way
    -- the guard computes it: against its own recorded cost basis.
    FOR offender IN
        SELECT model_code, output_price_per_million, provider_cost_output_per_million
        FROM gateway.model_billing_price
        WHERE effective_to IS NULL
          AND provider_cost_output_per_million > 0
          AND output_price_per_million
              < provider_cost_output_per_million / 0.70
    LOOP
        RAISE EXCEPTION 'retail_below_thirty_percent_floor:%:retail=%:cost=%',
            offender.model_code, offender.output_price_per_million,
            offender.provider_cost_output_per_million;
    END LOOP;

    -- The rows this migration wrote are present and current.
    IF (SELECT count(*) FROM gateway.model_billing_price
        WHERE effective_to IS NULL AND price_version = 'retail-v3') <> 4 THEN
        RAISE EXCEPTION 'retail_v3_scope_changed';
    END IF;
END $$;

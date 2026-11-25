-- Correct The Provider Cost Basis In gateway.provider_model_price To The Vendor List
--
-- gateway.provider_model_price is the COST table: ProviderCostCalculator reads it per
-- request to snapshot what the vendor charged us, ProviderPriceQueryRepository serves it
-- to the platform's package-publication margin guard (/internal/prices/non-promotional-output),
-- to the price monitor and to the console price listing, and the usage report reads it
-- live. Every cost report and every margin number derives from it.
--
-- It was wrong on six of its eight seeded rows, understating the alias cost by 2.8x to
-- 28.6x, because V4 seeded it from an older generation of list prices and, for DeepSeek,
-- from a USD basis that matches no published figure at all.
--
-- Two vendor documents settle it. Both are denominated in CNY:
--
--   1. amount-2026-08-15_2026-09-14.xlsx — the DeepSeek invoice. Its `price` column is
--      CNY per TOKEN, so x 1e6 gives CNY per million and x 1e8 gives 分 per million.
--      It carries both the peak and the off-peak rate for each token type; the standard
--      (peak) rate is the one used here, and it is the rate the price table also quotes.
--
--   2. 大模型Token价格对比表(2).pdf — the vendor price table, CNY per million tokens.
--      The column used is 输入标准价 / 输出标准价, i.e. the standard price BEFORE the
--      建议折扣 column, so any discount we are granted is margin we keep rather than
--      margin we depend on. The table's DeepSeek rows agree with the invoice exactly,
--      which is why it is trusted for the Bailian rows the invoice does not cover.
--
--   alias          vendor model        input  output  cache-hit   source            -> 分/M
--   text-basic     deepseek-flash       2.00    8.00       0.04   invoice           ->  200 /  800 /  4
--   text-standard  deepseek-v4-flash    3.00    9.00       0.10   invoice + pdf     ->  300 /  900 / 10
--   text-pro       deepseek-v4-pro      9.00   27.00       0.30   invoice + pdf     ->  900 / 2700 / 30
--   text-max       Qwen3.7-Max         12.00   36.00          —   pdf (standard)    -> 1200 / 3600
--   vision-basic   Qwen3.6-Flash        1.20    7.20          —   pdf (<256K tier)  ->  120 /  720
--   vision-pro     Qwen3.6-Plus         2.00   12.00          —   pdf (<256K tier)  ->  200 / 1200
--   file-basic     qwen-long           0.50    2.00           —   unchanged         ->   50 /  200
--
-- Alias -> vendor model comes from the gateway's route table (channel_model.upstream_model,
-- V4 + V15): DeepSeek serves text-basic / text-standard / text-pro, Bailian serves
-- text-max / vision-basic / vision-pro / file-basic.
--
-- CURRENCY: the corrected rows are stored as CNY with internal_exchange_rate = 1.
-- The pair (currency, internal_exchange_rate) is the BASIS OF THE STORED NUMBER, not a
-- conversion instruction: ProviderCostCalculator records cost as
-- price * internal_exchange_rate / 1e6 分, so a CNY row at rate 1 means "this number is
-- 分 per million", and a USD row at 7.2 means "this number is US cents per million,
-- worth 7.2 分 each". Both vendor documents quote CNY, and the invoice proves we are
-- billed in CNY. Storing the CNY list price under currency='USD' would be a false
-- statement about the number; converting it to US cents at 7.2 would produce
-- 27.777... 美分/M — a figure that is neither DeepSeek's USD list price nor the invoiced
-- CNY price, that cannot be represented exactly in a bigint column, and that would make
-- the recorded cost of an unchanged vendor price move with the FX rate. The USD basis is
-- therefore retired for every DeepSeek row: the two rows that carried it (14/28 and
-- 44/87 USD at 7.2, i.e. 100.8 and 316.8/626.4 分/M) are closed by this migration.
--
-- SCOPE — six rows written, five of them replacing a closed row:
--   * deepseek/text-basic is INSERTED. The alias is served by a DeepSeek channel
--     (V15 added it) but had no DeepSeek price row at all, so it was costed from
--     Bailian's legacy qwen-turbo bootstrap row (14/28 分/M) — 14.3x/28.6x below the
--     vendor figure. A route with a channel and no price is how a cost silently
--     disappears; the row is added rather than the route removed.
--   * bailian/file-basic (qwen-long 50/200) is NOT touched: the price table does not
--     list qwen-long and its stored basis is the one already verified.
--   * bailian/text-basic (qwen-turbo 14/28) and bailian/text-pro (qwen-plus 40/160) are
--     NOT touched. Neither vendor document covers qwen-turbo or qwen-plus, and under
--     the alias mapping those two aliases are bought as the DeepSeek models, so
--     rewriting them to the DeepSeek figures would state a price Aliyun does not
--     charge. They stay as the unverified figures they are, and they cannot raise an
--     alias's worst-case cost because the corrected DeepSeek row dominates them in the
--     guard's max(). Residual, deliberately left and named: if Bailian were enabled for
--     text-basic or text-pro while DeepSeek was disabled, that route would still be
--     costed from the stale row. Closing them instead was rejected: a route with no
--     current price returns a missing snapshot, and provider_cost_record.amount is NOT
--     NULL, so a Bailian-served request would fail to record its cost at all.
--   * cache_write and reasoning stay NULL on every row written here. The invoice bills
--     cache-write tokens as cache-miss tokens (it has no cache-write category) and bills
--     reasoning tokens as output tokens, and COALESCE(cache_write, input) and
--     COALESCE(reasoning, output) in ProviderCostCalculator express exactly that; a
--     number invented for either column would be a price neither vendor publishes.
--   * cache_read is NULL for the three Qwen rows because neither document quotes a Qwen
--     cache-hit price. NULL is the conservative reading: COALESCE(cache_read, input)
--     charges a cache hit at the miss price, so a cache-heavy Qwen workload is costed
--     at or above what the vendor charges, never below.
--
-- KNOWN REMAINING GAP, stated rather than hidden: the price table prices Qwen3.6-Flash
-- and Qwen3.6-Plus by input length (<256K and 256K-1000K, the latter 4x the former) and
-- this table has one row per (provider, model) with no context-length dimension, so the
-- <256K standard price is stored and a >256K request is costed 4x low. Closing that
-- needs a column, not a price edit.
--
-- IN-FLIGHT SAFETY: the wrong rows are CLOSED and new rows INSERTED, never updated in
-- place. ProviderCostCalculator resolves the price row per request from
-- (provider_id, model_id, occurred_at) and the settlement then carries that row's id, so
-- a request that has already been priced must keep resolving to the row it priced
-- against. An in-place UPDATE would silently re-price every request that started before
-- this migration but settles after it, at a price that was never in force for it. The
-- new rows take effective_from = now(), which is exactly the old rows' effective_to:
-- no gap, no overlap, and any occurred_at before now() still matches only the old row
-- (effective_to > occurred_at, new effective_from > occurred_at).
--
-- UNITS: 分 per million tokens, the same as every other price column in this schema.

-- ---------------------------------------------------------------------------
-- 1. Close the six wrong current rows (five corrected rows and the USD basis)
-- ---------------------------------------------------------------------------

UPDATE gateway.provider_model_price AS price
SET effective_to = now()
FROM gateway.provider AS provider, gateway.model AS model
WHERE price.provider_id = provider.id
  AND price.model_id = model.id
  AND price.effective_to IS NULL
  AND (provider.code, model.code) IN (
      ('deepseek', 'text-standard'),
      ('deepseek', 'text-pro'),
      ('bailian', 'text-max'),
      ('bailian', 'vision-basic'),
      ('bailian', 'vision-pro'));

-- ---------------------------------------------------------------------------
-- 2. Insert the corrected rows
--
-- Fixed ids in the 86100000-... segment so the rows are addressable and an assertion
-- can name them; the segment sorts after V4's 86000000-... ids, which keeps
-- "ORDER BY price.id LIMIT 1" (UsageQueryServiceTest) resolving to the same row it
-- resolved to before this migration.
-- ---------------------------------------------------------------------------

INSERT INTO gateway.provider_model_price(
    id, provider_id, model_id, input_price_per_million, output_price_per_million,
    cache_read_price_per_million, currency, internal_exchange_rate, official_source,
    effective_from)
SELECT vendor.id, provider.id, model.id,
       vendor.input_cents, vendor.output_cents, vendor.cache_read_cents,
       'CNY', 1, vendor.official_source, now()
FROM (VALUES
    ('86100000-0000-4000-8000-000000000001'::uuid, 'deepseek', 'text-basic',
     200, 800, 4,
     'invoice:amount-2026-08-15_2026-09-14.xlsx:deepseek-flash'),
    ('86100000-0000-4000-8000-000000000002'::uuid, 'deepseek', 'text-standard',
     300, 900, 10,
     'invoice:amount-2026-08-15_2026-09-14.xlsx:deepseek-v4-flash + pdf:大模型Token价格对比表(2):DeepSeek V4 Flash'),
    ('86100000-0000-4000-8000-000000000003'::uuid, 'deepseek', 'text-pro',
     900, 2700, 30,
     'invoice:amount-2026-08-15_2026-09-14.xlsx:deepseek-v4-pro + pdf:大模型Token价格对比表(2):DeepSeek V4 Pro'),
    ('86100000-0000-4000-8000-000000000004'::uuid, 'bailian', 'text-max',
     1200, 3600, NULL,
     'pdf:大模型Token价格对比表(2):Qwen3.7-Max 正式版 输入标准价/输出标准价'),
    ('86100000-0000-4000-8000-000000000005'::uuid, 'bailian', 'vision-basic',
     120, 720, NULL,
     'pdf:大模型Token价格对比表(2):Qwen3.6-Flash 输入<256K 输入标准价/输出标准价'),
    ('86100000-0000-4000-8000-000000000006'::uuid, 'bailian', 'vision-pro',
     200, 1200, NULL,
     'pdf:大模型Token价格对比表(2):Qwen3.6-Plus 输入<256K 输入标准价/输出标准价')
) AS vendor(id, provider_code, model_code, input_cents, output_cents, cache_read_cents,
            official_source)
JOIN gateway.provider AS provider ON provider.code = vendor.provider_code
JOIN gateway.model AS model ON model.code = vendor.model_code;

-- ---------------------------------------------------------------------------
-- 3. Assert the rows this migration wrote, and only those
--
-- The expected rows are restated here independently of the INSERT above, so a typo in
-- either place fails the migration. The predicates of ck_provider_price and
-- ck_provider_price_usage_categories are restated as well even though the constraints
-- already rejected a violation: this is the money path, and the assertion must be
-- readable next to the numbers it checks.
--
-- Note that neither of those constraints says cache_read <= input (the same-named rule
-- lives on gateway.model_usage_price as ck_model_usage_price_cache_cost); a cache hit
-- costing more than a miss is nonsense in either table, so it is asserted here.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    expected record;
    actual record;
    alias_worst_cents bigint;
    closed_count integer;
BEGIN
    -- Every expected row is present, current, and equal to the vendor figure.
    FOR expected IN
        SELECT * FROM (VALUES
            ('deepseek', 'text-basic',    200::bigint,  800::bigint,    4::bigint,  2.00::numeric,   8.00::numeric,  0.04::numeric),
            ('deepseek', 'text-standard', 300::bigint,  900::bigint,   10::bigint,  3.00::numeric,   9.00::numeric,  0.10::numeric),
            ('deepseek', 'text-pro',      900::bigint, 2700::bigint,   30::bigint,  9.00::numeric,  27.00::numeric,  0.30::numeric),
            ('bailian',  'text-max',     1200::bigint, 3600::bigint, NULL::bigint, 12.00::numeric,  36.00::numeric,  NULL::numeric),
            ('bailian',  'vision-basic',  120::bigint,  720::bigint, NULL::bigint,  1.20::numeric,   7.20::numeric,  NULL::numeric),
            ('bailian',  'vision-pro',    200::bigint, 1200::bigint, NULL::bigint,  2.00::numeric,  12.00::numeric,  NULL::numeric)
        ) AS expected_row(provider_code, model_code, input_cents, output_cents,
                          cache_read_cents, input_yuan, output_yuan, cache_read_yuan)
    LOOP
        SELECT price.input_price_per_million, price.output_price_per_million,
               price.cache_read_price_per_million, price.cache_write_price_per_million,
               price.reasoning_price_per_million, price.currency,
               price.internal_exchange_rate, price.effective_to
        INTO actual
        FROM gateway.provider_model_price price
        JOIN gateway.provider provider ON provider.id = price.provider_id
        JOIN gateway.model model ON model.id = price.model_id
        WHERE provider.code = expected.provider_code
          AND model.code = expected.model_code
          AND price.effective_to IS NULL;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'provider_price_missing:%:%', expected.provider_code,
                expected.model_code;
        END IF;

        -- ck_provider_price
        IF actual.input_price_per_million <= 0 OR actual.output_price_per_million <= 0 THEN
            RAISE EXCEPTION 'provider_price_non_positive:%:%:%:%',
                expected.provider_code, expected.model_code,
                actual.input_price_per_million, actual.output_price_per_million;
        END IF;
        IF actual.internal_exchange_rate <= 0 THEN
            RAISE EXCEPTION 'provider_price_bad_exchange_rate:%:%:%',
                expected.provider_code, expected.model_code, actual.internal_exchange_rate;
        END IF;
        IF actual.effective_to IS NOT NULL THEN
            RAISE EXCEPTION 'provider_price_not_current:%:%', expected.provider_code,
                expected.model_code;
        END IF;

        -- ck_provider_price_usage_categories, plus the cache-hit-cannot-cost-more-than-a-miss
        -- rule that constraint does not carry on this table.
        IF actual.cache_read_price_per_million IS NOT NULL
           AND (actual.cache_read_price_per_million < 0
                OR actual.cache_read_price_per_million > actual.input_price_per_million) THEN
            RAISE EXCEPTION 'provider_price_cache_read_out_of_range:%:%:%',
                expected.provider_code, expected.model_code,
                actual.cache_read_price_per_million;
        END IF;
        IF (actual.cache_write_price_per_million IS NOT NULL
            AND actual.cache_write_price_per_million < 0)
           OR (actual.reasoning_price_per_million IS NOT NULL
               AND actual.reasoning_price_per_million < 0) THEN
            RAISE EXCEPTION 'provider_price_usage_category_negative:%:%',
                expected.provider_code, expected.model_code;
        END IF;

        -- The stored number, converted exactly the way ProviderCostCalculator converts it,
        -- must equal the vendor figure: 分 per million here, CNY per million = 分 / 100.
        IF actual.currency <> 'CNY' THEN
            RAISE EXCEPTION 'provider_price_not_cny:%:%:%', expected.provider_code,
                expected.model_code, actual.currency;
        END IF;
        IF actual.input_price_per_million * actual.internal_exchange_rate
               <> expected.input_cents
           OR actual.output_price_per_million * actual.internal_exchange_rate
               <> expected.output_cents
           OR round(actual.input_price_per_million * actual.internal_exchange_rate / 100, 2)
               <> expected.input_yuan
           OR round(actual.output_price_per_million * actual.internal_exchange_rate / 100, 2)
               <> expected.output_yuan THEN
            RAISE EXCEPTION 'provider_price_does_not_match_vendor:%:%:%:%:%:%',
                expected.provider_code, expected.model_code,
                actual.input_price_per_million, actual.output_price_per_million,
                expected.input_cents, expected.output_cents;
        END IF;
        IF expected.cache_read_cents IS NULL THEN
            IF actual.cache_read_price_per_million IS NOT NULL THEN
                RAISE EXCEPTION 'provider_price_unexpected_cache_read:%:%:%',
                    expected.provider_code, expected.model_code,
                    actual.cache_read_price_per_million;
            END IF;
        ELSIF actual.cache_read_price_per_million IS DISTINCT FROM expected.cache_read_cents
              OR round(actual.cache_read_price_per_million
                       * actual.internal_exchange_rate / 100, 2)
                 <> expected.cache_read_yuan THEN
            RAISE EXCEPTION 'provider_price_cache_read_does_not_match_invoice:%:%:%',
                expected.provider_code, expected.model_code,
                actual.cache_read_price_per_million;
        END IF;

        -- Exactly one current row: ProviderCostCalculator takes ORDER BY effective_from
        -- DESC LIMIT 1, so a second open row would be resolved by nothing but luck.
        IF (SELECT count(*) FROM gateway.provider_model_price price
            JOIN gateway.provider provider ON provider.id = price.provider_id
            JOIN gateway.model model ON model.id = price.model_id
            WHERE provider.code = expected.provider_code
              AND model.code = expected.model_code
              AND price.effective_to IS NULL) <> 1 THEN
            RAISE EXCEPTION 'provider_price_multiple_current:%:%', expected.provider_code,
                expected.model_code;
        END IF;
    END LOOP;

    -- Every row this migration replaced is CLOSED with its old figure intact, so an
    -- in-flight request that already priced against it still resolves to it, and so the
    -- old values remain readable as the record of what was in force before.
    FOR expected IN
        SELECT * FROM (VALUES
            ('deepseek', 'text-standard',  14::bigint,  28::bigint, 'USD'),
            ('deepseek', 'text-pro',       44::bigint,  87::bigint, 'USD'),
            ('bailian',  'text-max',      250::bigint, 750::bigint, 'CNY'),
            ('bailian',  'vision-basic',   15::bigint,  60::bigint, 'CNY'),
            ('bailian',  'vision-pro',     20::bigint, 160::bigint, 'CNY')
        ) AS closed_row(provider_code, model_code, input_cents, output_cents, currency)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM gateway.provider_model_price price
            JOIN gateway.provider provider ON provider.id = price.provider_id
            JOIN gateway.model model ON model.id = price.model_id
            WHERE provider.code = expected.provider_code
              AND model.code = expected.model_code
              AND price.input_price_per_million = expected.input_cents
              AND price.output_price_per_million = expected.output_cents
              AND price.currency = expected.currency
              AND price.effective_to IS NOT NULL
              AND price.effective_to > price.effective_from) THEN
            RAISE EXCEPTION 'provider_price_superseded_row_not_closed:%:%',
                expected.provider_code, expected.model_code;
        END IF;
    END LOOP;

    -- The rows this migration must NOT have touched are still current with their old
    -- figures: file-basic on the verified qwen-long basis, and the two legacy Bailian
    -- rows no vendor document covers.
    FOR expected IN
        SELECT * FROM (VALUES
            ('bailian', 'file-basic',  50::bigint, 200::bigint),
            ('bailian', 'text-basic',  14::bigint,  28::bigint),
            ('bailian', 'text-pro',    40::bigint, 160::bigint)
        ) AS untouched_row(provider_code, model_code, input_cents, output_cents)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM gateway.provider_model_price price
            JOIN gateway.provider provider ON provider.id = price.provider_id
            JOIN gateway.model model ON model.id = price.model_id
            WHERE provider.code = expected.provider_code
              AND model.code = expected.model_code
              AND price.input_price_per_million = expected.input_cents
              AND price.output_price_per_million = expected.output_cents
              AND price.currency = 'CNY'
              AND price.internal_exchange_rate = 1
              AND price.effective_to IS NULL) THEN
            RAISE EXCEPTION 'provider_price_out_of_scope_row_changed:%:%',
                expected.provider_code, expected.model_code;
        END IF;
    END LOOP;

    -- The correction landed where it matters most: for every alias this migration
    -- corrected, the alias's worst-case output cost — the max over EVERY provider row,
    -- which is exactly what the package-publication margin guard prices the whole quota
    -- against — is now the vendor figure, so no other row (a stale one, or a cheaper
    -- failover) still defines the alias's cost.
    FOR expected IN
        SELECT * FROM (VALUES
            ('deepseek', 'text-basic',    800::bigint),
            ('deepseek', 'text-standard', 900::bigint),
            ('deepseek', 'text-pro',     2700::bigint),
            ('bailian',  'text-max',     3600::bigint),
            ('bailian',  'vision-basic',  720::bigint),
            ('bailian',  'vision-pro',   1200::bigint)
        ) AS worst_case_row(provider_code, model_code, output_cents)
    LOOP
        SELECT max(ceil(price.output_price_per_million * price.internal_exchange_rate))
        INTO alias_worst_cents
        FROM gateway.provider_model_price price
        JOIN gateway.model model ON model.id = price.model_id
        WHERE model.code = expected.model_code
          AND price.effective_to IS NULL;
        IF alias_worst_cents IS DISTINCT FROM expected.output_cents THEN
            RAISE EXCEPTION 'provider_price_alias_worst_case_wrong:%:%:%',
                expected.model_code, alias_worst_cents, expected.output_cents;
        END IF;
    END LOOP;

    -- Guard against the scope of this migration changing silently: the six pairs it
    -- writes are present and current, and each of the five it replaced has its superseded
    -- row closed rather than deleted or mutated.
    IF (SELECT count(*) FROM gateway.provider_model_price price
        JOIN gateway.provider provider ON provider.id = price.provider_id
        JOIN gateway.model model ON model.id = price.model_id
        WHERE price.effective_to IS NULL
          AND price.id::text LIKE '86100000-%') <> 6 THEN
        RAISE EXCEPTION 'provider_price_written_row_count_changed';
    END IF;
    SELECT count(*) INTO closed_count
    FROM gateway.provider_model_price price
    JOIN gateway.provider provider ON provider.id = price.provider_id
    JOIN gateway.model model ON model.id = price.model_id
    WHERE price.effective_to IS NOT NULL
      AND (provider.code, model.code) IN (
          ('deepseek', 'text-standard'), ('deepseek', 'text-pro'),
          ('bailian', 'text-max'), ('bailian', 'vision-basic'),
          ('bailian', 'vision-pro'));
    IF closed_count < 5 THEN
        RAISE EXCEPTION 'provider_price_closed_row_count_changed:%', closed_count;
    END IF;
END $$;

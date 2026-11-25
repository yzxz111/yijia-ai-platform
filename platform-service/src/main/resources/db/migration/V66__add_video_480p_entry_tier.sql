-- Add The 480P Video Entry Tier (Monthly And Annual)
--
-- WHY A NEW PLAN INSTEAD OF A NEW VERSION OF VIDEO_STARTER. VIDEO_STARTER sells a
-- mixed 480P/720P/1080P entitlement, so its worst case is priced against 1080P's
-- 115 分/second and cannot be sold cheaply without losing money. The entry tier
-- this migration adds sells ONE alias (video-budget, 480P) at 35 分/second, and
-- the gateway now refuses any other resolution for that alias (gateway V54), so
-- the thin price is real rather than a hole: 59 分/second is a 40.7% worst-case
-- margin, and the annual package's 54.45 分/second is 35.7%.
--
--   plan                 price(分)  seconds  days  SPM  per-second  worst margin
--   VIDEO_LITE                5,900      100    30   60     59.00       40.68%
--   VIDEO_LITE_ANNUAL        59,900    1,100   365   60     54.45       35.73%
--
-- Both clear the video dimension's 30% floor with room, and both sit above the
-- 35% warning line V65 just set for this dimension -- which is why that floor
-- moved: at the old 40% line the annual package would have published flagged.
--
-- THE ENTRY TIER IS 480P ON PURPOSE. It is the only tier whose wholesale (30
-- 分/second, wan2.5-t2v-preview) leaves room for a sub-1-CNY price. 720P costs
-- 60 分 wholesale and 1080P 100 分, so a "cheap" 720P tier is not available at any
-- margin worth defending -- the existing three tiers keep their prices and their
-- upstream until a cheaper vendor is contracted.
--
-- No upstream route is wired here: the video aliases have no channel_model rows in
-- any migration yet (only the probe fixture under test-results/ has one). Which
-- vendor serves video-budget is an operator decision taken in the console, and the
-- margins above hold for the 30 分/second wholesale this tier is priced against.
--
-- Published versions are immutable and one plan may have only one PUBLISHED
-- version, so the order below is forced: insert the plan, insert the version as
-- DRAFT, give it its model permissions (platform.reject_published_model_mutation
-- refuses permission rows while the parent is PUBLISHED), then publish.
--
-- display_order places the entry tier between VIDEO_STARTER (40) and
-- VIDEO_CREATOR (50) so the video tab reads cheap-to-dear.

-- ---------------------------------------------------------------------------
-- 1. The two plan rows
-- ---------------------------------------------------------------------------

INSERT INTO platform.package_plan(id, code, name, description, status, display_order)
VALUES
    ('00000000-0000-4000-8000-000000000014', 'VIDEO_LITE', 'Video Lite',
     'Entry video package: 100 seconds per month at 480P', 'ACTIVE', 45),
    ('00000000-0000-4000-8000-000000000015', 'VIDEO_LITE_ANNUAL', 'Video Lite Annual',
     'Annual entry video package: 1100 seconds at 480P', 'ACTIVE', 46)
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. DRAFT versions
--
-- max_output_tokens is NULL for the existing video versions, but V64's guard
-- rejects any PUBLISHED row whose max_output_tokens is not positive, so the new
-- rows carry the same 4096 the token tiers use. Billing never reads it for video
-- (the video path bounds a request by its own duration and by the seconds quota).
-- ---------------------------------------------------------------------------

INSERT INTO platform.package_plan_version(
    id, package_plan_id, version, price_amount, currency, total_token_quota,
    duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
    max_concurrency, target_margin_ratio, status, published_at, retired_at,
    daily_request_limit, max_output_tokens, max_file_size_bytes, once_per_user,
    estimated_cost_amount, estimated_margin_ratio, margin_warning, stock_total,
    low_stock_threshold, max_purchases_per_user, usage_dimension)
VALUES
    -- 100 s x 35 = 3,500 worst-case cost against a 5,900 price.
    ('00000000-0000-4000-8000-000000000401', '00000000-0000-4000-8000-000000000014',
     1, 5900, 'CNY', 100, 30, 2, 20, 60, 2, 0.35000, 'DRAFT', NULL, NULL,
     200, 4096, 20971520, false, 3500, 0.40678, false, NULL, 0, NULL, 'VIDEO_SECOND'),
    -- 1,100 s x 35 = 38,500 worst-case cost against a 59,900 price.
    ('00000000-0000-4000-8000-000000000402', '00000000-0000-4000-8000-000000000015',
     1, 59900, 'CNY', 1100, 365, 3, 30, 60, 3, 0.35000, 'DRAFT', NULL, NULL,
     500, 4096, 52428800, false, 38500, 0.35726, false, NULL, 0, NULL, 'VIDEO_SECOND');

-- ---------------------------------------------------------------------------
-- 3. Model permissions: video-budget only
--
-- NULL model_token_quota means "this alias may consume the whole package
-- balance"; there is no per-alias ceiling to sell on a single-alias tier.
-- ---------------------------------------------------------------------------

INSERT INTO platform.package_plan_version_model(
    id, package_plan_version_id, model_code, model_token_quota, capabilities)
VALUES
    (gen_random_uuid(), '00000000-0000-4000-8000-000000000401', 'video-budget',
     NULL, '["TEXT","IMAGE","VIDEO"]'),
    (gen_random_uuid(), '00000000-0000-4000-8000-000000000402', 'video-budget',
     NULL, '["TEXT","IMAGE","VIDEO"]');

-- ---------------------------------------------------------------------------
-- 4. Publish
-- ---------------------------------------------------------------------------

UPDATE platform.package_plan_version
SET status = 'PUBLISHED', published_at = now()
WHERE id IN (
        '00000000-0000-4000-8000-000000000401',
        '00000000-0000-4000-8000-000000000402')
  AND status = 'DRAFT';

-- ---------------------------------------------------------------------------
-- 5. Assert what was sold
--
-- The stored margin is recomputed here from the same 35 分/second basis the
-- publication guard uses (dearest permitted alias first, each consuming
-- min(cap, remaining) -- with one capless alias that is the whole quota), so a
-- row whose stored snapshot disagreed with its own arithmetic refuses the
-- migration rather than publishing a wrong number to the console.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    expected  record;
    actual    record;
    worst     bigint;
    cost      bigint;
BEGIN
    FOR expected IN
        SELECT * FROM (VALUES
            ('VIDEO_LITE',        100::bigint,   5900::bigint,  30::integer, 60::bigint, 0.40678::numeric),
            ('VIDEO_LITE_ANNUAL', 1100::bigint, 59900::bigint, 365::integer, 60::bigint, 0.35726::numeric)
        ) AS wanted(code, seconds, price_amount, duration_days, spm, margin)
    LOOP
        SELECT plan_row.code, version.id, version.status, version.price_amount,
               version.currency, version.total_token_quota, version.duration_days,
               version.tokens_per_minute, version.usage_dimension,
               version.estimated_cost_amount, version.estimated_margin_ratio,
               version.margin_warning
        INTO actual
        FROM platform.package_plan plan_row
        JOIN platform.package_plan_version version ON version.package_plan_id = plan_row.id
        WHERE plan_row.code = expected.code AND version.version = 1;

        IF actual IS NULL THEN
            RAISE EXCEPTION 'entry_tier_version_missing:%', expected.code;
        END IF;
        IF actual.status <> 'PUBLISHED' THEN
            RAISE EXCEPTION 'entry_tier_not_published:%:status=%', expected.code, actual.status;
        END IF;
        IF actual.usage_dimension <> 'VIDEO_SECOND' THEN
            RAISE EXCEPTION 'entry_tier_dimension_changed:%:dimension=%',
                expected.code, actual.usage_dimension;
        END IF;
        IF actual.total_token_quota <> expected.seconds
                OR actual.price_amount <> expected.price_amount
                OR actual.duration_days <> expected.duration_days
                OR actual.tokens_per_minute <> expected.spm THEN
            RAISE EXCEPTION 'entry_tier_not_as_sold:%:seconds=%:price=%:days=%:spm=%',
                expected.code, actual.total_token_quota, actual.price_amount,
                actual.duration_days, actual.tokens_per_minute;
        END IF;

        -- The same worst-case fill the publication guard performs (dearest permitted alias
        -- first, each consuming min(cap, remaining)), with the cost basis embedded as
        -- literals exactly as V56/V61/V63/V64 do: a migration is a fixed record of the
        -- numbers it was written against.
        WITH basis(model_code, unit_cost) AS (
            VALUES ('video-budget', 35)
        ), perms AS (
            SELECT model.model_token_quota AS cap, basis.unit_cost
            FROM platform.package_plan_version_model model
            JOIN basis ON basis.model_code = model.model_code
            WHERE model.package_plan_version_id = actual.id
        ), filled AS (
            SELECT cap, unit_cost,
                   least(coalesce(cap, actual.total_token_quota),
                         greatest(actual.total_token_quota - coalesce(
                             sum(coalesce(cap, actual.total_token_quota)) OVER (
                                 ORDER BY unit_cost DESC, cap
                                 ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0), 0)) AS consumed
            FROM perms
        )
        SELECT coalesce(sum(consumed * unit_cost), 0)::bigint INTO worst FROM filled;

        IF worst <> actual.estimated_cost_amount THEN
            RAISE EXCEPTION 'entry_tier_stored_cost_disagrees:%:stored=%:computed=%',
                expected.code, actual.estimated_cost_amount, worst;
        END IF;

        IF round((actual.price_amount - worst)::numeric / actual.price_amount, 5)
                <> expected.margin THEN
            RAISE EXCEPTION 'entry_tier_stored_margin_disagrees:%:stored=%:computed=%',
                expected.code, actual.estimated_margin_ratio,
                round((actual.price_amount - worst)::numeric / actual.price_amount, 5);
        END IF;

        IF actual.margin_warning <> (expected.margin < 0.35000) THEN
            RAISE EXCEPTION 'entry_tier_warning_flag_disagrees:%:flag=%:margin=%',
                expected.code, actual.margin_warning, expected.margin;
        END IF;
    END LOOP;

    -- The entry tier sells one alias, and only the 480P one: a permission row for
    -- any other alias would silently re-introduce the mixed-dimension worst case
    -- that made a cheap entry tier impossible.
    SELECT count(*) INTO worst
    FROM platform.package_plan_version_model model
    JOIN platform.package_plan_version version ON version.id = model.package_plan_version_id
    JOIN platform.package_plan plan_row ON plan_row.id = version.package_plan_id
    WHERE plan_row.code IN ('VIDEO_LITE', 'VIDEO_LITE_ANNUAL')
      AND model.model_code <> 'video-budget';

    IF worst > 0 THEN
        RAISE EXCEPTION 'entry_tier_grants_more_than_the_480p_alias:count=%', worst;
    END IF;

    -- Both rows must satisfy the invariant V64 asserts for every published
    -- version: a seconds plan's SPM ceiling is 1..86400, and no published version
    -- may sell a non-positive limit.
    SELECT count(*) INTO worst
    FROM platform.package_plan_version version
    JOIN platform.package_plan plan_row ON plan_row.id = version.package_plan_id
    WHERE plan_row.code IN ('VIDEO_LITE', 'VIDEO_LITE_ANNUAL')
      AND (version.tokens_per_minute <= 0 OR version.tokens_per_minute > 86400
           OR version.requests_per_minute <= 0 OR version.max_api_keys <= 0
           OR version.max_concurrency <= 0 OR version.duration_days <= 0
           OR version.total_token_quota <= 0 OR version.max_file_size_bytes <= 0
           OR version.daily_request_limit <= 0 OR version.max_output_tokens <= 0);

    IF worst > 0 THEN
        RAISE EXCEPTION 'entry_tier_sells_a_non_positive_limit:count=%', worst;
    END IF;
END $$;

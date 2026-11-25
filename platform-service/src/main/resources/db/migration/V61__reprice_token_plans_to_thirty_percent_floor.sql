-- Reprice The Token Plans Against The Corrected Cost Basis
--
-- WHY THIS MIGRATION EXISTS
--
-- V48 corrected gateway.provider_model_price to the figures on the vendor's own
-- invoice and price list, and V50 realigned the retail card to them. The three
-- published TOKEN plans were not repriced with them: their prices were set
-- against a cost basis that was wrong by roughly an order of magnitude, and
-- against the corrected basis every one of them sells at a loss.
--
-- PublishedPlanMarginMonitor recomputes each published version's worst case
-- against the live basis on a schedule. On the live database it reported:
--
--   STARTER     1,900  vs worst  4,500  -> -136.8%
--   ADVANCED   19,900  vs worst 36,600  ->  -83.9%
--   ENTERPRISE 24,900  vs worst 104,200 -> -318.5%
--
-- That is a loss of up to CNY 26 / 167 / 793 on every sale, on plans that are
-- live and purchasable. The ENTERPRISE figure is not a pricing opinion, it is a
-- typo: v1 (retired) charged 249,900 分 for the same 100,000,000-token quota that
-- v2 charges 24,900 分 for, so v2 is v1 with a zero missing.
--
-- WHY THE PLANS LOSED MONEY: THE GUARD'S WORST CASE IS THE ONLY ONE THAT MATTERS
--
-- A TOKEN quota carries no input/output split and, on STARTER, no per-model
-- ceiling at all, so the margin guard must assume the whole quota is burned on
-- the most expensive thing the plan advertises, priced at that alias's OUTPUT
-- rate. A plan is safe only if it survives that fill:
--
--   STARTER advertised text-standard with no ceiling -> 5,000,000 x 900/1e6
--   = 4,500 分 of cost against a 1,900 分 price. It lost money even on a
--   realistic 70/30 input/output mix, so no amount of "typical traffic"
--   reasoning rescues it.
--
-- THE FIX
--
-- New versions, because published versions are immutable
-- (platform.reject_published_version_mutation). Prices and quotas are chosen so
-- that the guard's own worst case lands inside the 30-40% band the product
-- requires, with the same fill the guard performs:
--
--   FREE        v2      0 分   50,000 tokens   (was 100,000)
--     giveaway by design; halved so the worst case per claim is 40 分, not 80.
--     Zero-price versions are deliberately outside margin alerting.
--
--   STARTER     v2  2,900 分 (CNY 29)   2,000,000 tokens   (was 5,000,000)
--     worst case 2,000,000 x 900/1e6 = 1,800  -> +37.93%
--
--   ADVANCED    v2 39,900 分 (CNY 399) 16,000,000 tokens   (was 20,000,000)
--     text-pro <= 6,000,000, vision-pro <= 2,000,000
--     worst case  6,000,000 x 2700/1e6 = 16,200
--              +  2,000,000 x 1200/1e6 =  2,400
--              +  8,000,000 x  900/1e6 =  7,200   (text-standard takes the rest)
--              -------------------------------- = 25,800  -> +35.34%
--
--   ENTERPRISE  v3 159,900 分 (CNY 1,599) 100,000,000 tokens (price corrected)
--     ceilings kept exactly as v2 had them; they already sum to the quota
--     worst case 104,200  -> +34.83%
--
-- Every one of the four lands inside 30-40%. The tiers keep their shape — the
-- same models, the same capabilities, the same duration, keys, rate limits and
-- concurrency — and only the price, the quota and ADVANCED's two ceilings move.
--
-- STARTER and ADVANCED also SHRINK because their old quotas cannot be sold at
-- any price a customer would pay: 5,000,000 tokens of text-standard costs
-- 4,500 分, so covering it at a 30% floor needs 6,429 分 (CNY 64) — the same
-- unit price as the CNY 29 / 2,000,000 offer, in a package whose headline price
-- is 3.4x higher. The product decision is the smaller, cheaper tier; the
-- alternative was measured and rejected, not overlooked.
--
-- ORDER MATTERS and is not the obvious one. A version must be inserted as DRAFT,
-- given its model permissions, and only then published: the companion trigger
-- platform.reject_published_model_mutation refuses to insert a permission row
-- while its parent version is PUBLISHED or RETIRED, and
-- uq_published_package_plan_version allows only one PUBLISHED version per plan,
-- so the old version has to be retired before the new one can be published.
--
-- The cost basis below is embedded as literals rather than read from the
-- gateway schema. A migration must be a fixed record of the numbers it was
-- written against, so a later rate-card change cannot silently rewrite what
-- these versions were approved on; and the platform's Flyway owns only the
-- `platform` schema, so a cross-schema read would fail in every platform-only
-- Testcontainers run. These literals are the live basis at the time of writing.

-- ---------------------------------------------------------------------------
-- 1. Retire the four published versions this migration replaces
-- ---------------------------------------------------------------------------

-- Administrators may already have replaced the seed catalogue. Only replace a
-- seed that is still published; never republish a retired seed over their plan.
CREATE TEMP TABLE v61_reprice_targets ON COMMIT DROP AS
SELECT id FROM platform.package_plan_version
WHERE status = 'PUBLISHED' AND id IN (
    '00000000-0000-4000-8000-000000000101',
    '00000000-0000-4000-8000-000000000102',
    '00000000-0000-4000-8000-000000000103',
    '00000000-0000-4000-8000-000000000204');

-- The immutability trigger permits exactly this transition: status -> RETIRED
-- with only retired_at also changing.
UPDATE platform.package_plan_version
SET status = 'RETIRED', retired_at = now()
WHERE id IN (
        '00000000-0000-4000-8000-000000000101',  -- FREE v1
        '00000000-0000-4000-8000-000000000102',  -- STARTER v1
        '00000000-0000-4000-8000-000000000103',  -- ADVANCED v1
        '00000000-0000-4000-8000-000000000204')  -- ENTERPRISE v2
  AND status = 'PUBLISHED';

-- ---------------------------------------------------------------------------
-- 2. Add the replacement versions as DRAFTs
--
-- Config columns are copied from the version being replaced rather than
-- re-typed, so the only differences between a plan and its successor are the
-- ones this migration intends: price, quota, the two ADVANCED ceilings, and the
-- stored margin estimate. Anything not named here is preserved by construction.
-- ---------------------------------------------------------------------------

INSERT INTO platform.package_plan_version(
    id, package_plan_id, version, price_amount, currency, total_token_quota,
    duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
    max_concurrency, target_margin_ratio, status, published_at, retired_at,
    daily_request_limit, max_output_tokens, max_file_size_bytes, once_per_user,
    estimated_cost_amount, estimated_margin_ratio, margin_warning, stock_total,
    low_stock_threshold, max_purchases_per_user, usage_dimension)
SELECT replacement.id, old.package_plan_id,
       (SELECT max(existing.version) + 1 FROM platform.package_plan_version existing
        WHERE existing.package_plan_id = old.package_plan_id), replacement.price,
       old.currency, replacement.quota, old.duration_days, old.max_api_keys,
       old.requests_per_minute, old.tokens_per_minute, old.max_concurrency,
       replacement.target_margin, 'DRAFT', NULL, NULL, old.daily_request_limit,
       old.max_output_tokens, old.max_file_size_bytes, old.once_per_user,
       replacement.worst_cost, replacement.margin, false, old.stock_total,
       old.low_stock_threshold, old.max_purchases_per_user, old.usage_dimension
FROM (VALUES
        ('00000000-0000-4000-8000-000000000111'::uuid,
         '00000000-0000-4000-8000-000000000101'::uuid, 2,      0::bigint,
           50000::bigint, 0.00000::numeric,  40::bigint, NULL::numeric),
        ('00000000-0000-4000-8000-000000000112'::uuid,
         '00000000-0000-4000-8000-000000000102'::uuid, 2,   2900::bigint,
         2000000::bigint, 0.35000::numeric, 1800::bigint, 0.37931::numeric),
        ('00000000-0000-4000-8000-000000000113'::uuid,
         '00000000-0000-4000-8000-000000000103'::uuid, 2,  39900::bigint,
        16000000::bigint, 0.35000::numeric, 25800::bigint, 0.35338::numeric),
        ('00000000-0000-4000-8000-000000000214'::uuid,
         '00000000-0000-4000-8000-000000000204'::uuid, 3, 159900::bigint,
       100000000::bigint, 0.35000::numeric, 104200::bigint, 0.34834::numeric)
     ) AS replacement(id, replaces, version, price, quota, target_margin,
                      worst_cost, margin)
JOIN platform.package_plan_version old ON old.id = replacement.replaces
JOIN v61_reprice_targets target ON target.id = old.id;

-- ---------------------------------------------------------------------------
-- 3. Copy the model permissions, tightening ADVANCED's two ceilings
--
-- ADVANCED is the only plan whose ceilings move: text-pro drops from 10,000,000
-- to 6,000,000. It is the alias that makes the plan expensive (2,700 分 per
-- million against a 900 分 next-dearmost), so its share of a 16,000,000-token
-- quota is what decides whether the plan is priceable at all. vision-pro is
-- named in the CASE only to pin its value at the 2,000,000 it already had, so
-- the two ceilings this plan depends on are stated together rather than one
-- being visible and the other inherited. ENTERPRISE keeps v2's ceilings verbatim —
-- they already sum to the quota, so every advertised token stays usable and the
-- fill is fully determined.
-- ---------------------------------------------------------------------------

INSERT INTO platform.package_plan_version_model(
    id, package_plan_version_id, model_code, model_token_quota, capabilities)
SELECT gen_random_uuid(),
       replacement.id,
       old.model_code,
       CASE
           WHEN replacement.id = '00000000-0000-4000-8000-000000000113'
                AND old.model_code IN ('text-pro', 'vision-pro')
               THEN CASE old.model_code
                        WHEN 'text-pro' THEN 6000000
                        ELSE 2000000
                    END
           ELSE old.model_token_quota
       END,
       old.capabilities
FROM (VALUES
        ('00000000-0000-4000-8000-000000000111'::uuid,
         '00000000-0000-4000-8000-000000000101'::uuid),
        ('00000000-0000-4000-8000-000000000112'::uuid,
         '00000000-0000-4000-8000-000000000102'::uuid),
        ('00000000-0000-4000-8000-000000000113'::uuid,
         '00000000-0000-4000-8000-000000000103'::uuid),
        ('00000000-0000-4000-8000-000000000214'::uuid,
         '00000000-0000-4000-8000-000000000204'::uuid)
     ) AS replacement(id, replaces)
JOIN platform.package_plan_version_model old
  ON old.package_plan_version_id = replacement.replaces
JOIN platform.package_plan_version created
  ON created.id = replacement.id AND created.status = 'DRAFT';

-- ---------------------------------------------------------------------------
-- 4. Publish the replacements
-- ---------------------------------------------------------------------------

UPDATE platform.package_plan_version
SET status = 'PUBLISHED', published_at = now()
WHERE id IN (
        '00000000-0000-4000-8000-000000000111',
        '00000000-0000-4000-8000-000000000112',
        '00000000-0000-4000-8000-000000000113',
        '00000000-0000-4000-8000-000000000214')
  AND status = 'DRAFT';

-- ---------------------------------------------------------------------------
-- 5. Resolve the margin alerts this migration makes obsolete
--
-- The monitor raised an OPEN LOW_MARGIN_PACKAGE alert for each of the three
-- versions retired in section 1; their fingerprint is the version id. Retiring a
-- version does not retract its alert, and the monitor only ever opens alerts —
-- it has no resolve path — so without this the operator console would keep
-- showing three red plans that are no longer for sale. Resolved here, in the
-- same migration that removes the condition, so the alert history stays honest:
-- the alerts were true when raised, and the versions they describe are gone.
-- ---------------------------------------------------------------------------

UPDATE platform.operating_alert
SET status = 'RESOLVED', resolved_at = now(), updated_at = now()
WHERE alert_type = 'LOW_MARGIN_PACKAGE'
  AND status IN ('OPEN', 'ACKED')
  AND fingerprint IN (SELECT 'package-margin:' || id::text FROM v61_reprice_targets)
  AND fingerprint IN (
        'package-margin:00000000-0000-4000-8000-000000000102',
        'package-margin:00000000-0000-4000-8000-000000000103',
        'package-margin:00000000-0000-4000-8000-000000000204');

-- ---------------------------------------------------------------------------
-- 6. Assert every replacement is admissible and inside the required band
--
-- A migration that writes a published version directly must prove the guard
-- would have accepted it. The fill below is the guard's own calculation,
-- reproduced exactly: order the permissions by unit cost descending and let each
-- consume min(cap, remaining), so a NULL cap means "this alias may take whatever
-- is left".
--
-- The basis is also asserted to COVER every permission the plan grants. A model
-- missing from the basis would be skipped by the fill, which can only understate
-- the worst case — the guard fails closed on a missing cost, and a check that is
-- more permissive than the guard it verifies is worse than no check.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    plan       record;
    worst      bigint;
    unknown    integer;
    floor_     numeric := 0.30000;
    ceiling_   numeric := 0.40000;
    margin     numeric;
BEGIN
    FOR plan IN
        SELECT version.id, plan_row.code, version.price_amount,
               version.total_token_quota, version.estimated_cost_amount
        FROM platform.package_plan_version version
        JOIN platform.package_plan plan_row ON plan_row.id = version.package_plan_id
        WHERE version.id IN (
                '00000000-0000-4000-8000-000000000112',
                '00000000-0000-4000-8000-000000000113',
                '00000000-0000-4000-8000-000000000214')
    LOOP
        -- Fail closed if the plan grants something this migration cannot price.
        SELECT count(*) INTO unknown
        FROM platform.package_plan_version_model model
        WHERE model.package_plan_version_id = plan.id
          AND model.model_code NOT IN (
                'file-basic', 'text-basic', 'text-standard', 'text-pro',
                'text-max', 'vision-basic', 'vision-pro', 'multimodal-basic');

        IF unknown > 0 THEN
            RAISE EXCEPTION 'repriced_plan_has_unpriced_model:%:count=%',
                plan.code, unknown;
        END IF;

        WITH basis(model_code, unit_cost) AS (
            VALUES ('file-basic', 200), ('text-basic', 800), ('text-standard', 900),
                   ('text-pro', 2700), ('text-max', 3600), ('vision-basic', 720),
                   ('vision-pro', 1200), ('multimodal-basic', 800)
        ), perms AS (
            SELECT model.model_token_quota AS cap, basis.unit_cost
            FROM platform.package_plan_version_model model
            JOIN basis ON basis.model_code = model.model_code
            WHERE model.package_plan_version_id = plan.id
        ), filled AS (
            SELECT cap, unit_cost,
                   least(coalesce(cap, plan.total_token_quota),
                         greatest(plan.total_token_quota - coalesce(
                             sum(coalesce(cap, plan.total_token_quota)) OVER (
                                 ORDER BY unit_cost DESC, cap
                                 ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0), 0)) AS consumed
            FROM perms
        )
        -- TOKEN costs are 分 per MILLION tokens, so every term carries its own
        -- division by 1,000,000. Rounding mirrors the guard term by term (10 dp,
        -- HALF_UP) and then ceils the total, because that is how
        -- PackageMarginPolicy turns a cost into the bigint it stores.
        SELECT coalesce(ceil(sum(round(consumed * unit_cost / 1000000.0, 10))), 0)::bigint
        INTO worst
        FROM filled;

        IF worst <= 0 THEN
            RAISE EXCEPTION 'repriced_plan_unpriceable:%:no_cost_basis', plan.code;
        END IF;
        IF worst > plan.price_amount THEN
            RAISE EXCEPTION 'repriced_plan_loses_money:%:price=%:worst=%',
                plan.code, plan.price_amount, worst;
        END IF;

        -- The stored estimate is what the console shows an operator, so it has
        -- to be the same number this assertion just computed.
        IF worst <> plan.estimated_cost_amount THEN
            RAISE EXCEPTION 'repriced_plan_estimate_disagrees:%:stored=%:computed=%',
                plan.code, plan.estimated_cost_amount, worst;
        END IF;

        margin := (plan.price_amount - worst)::numeric / plan.price_amount;
        IF margin < floor_ THEN
            RAISE EXCEPTION 'repriced_plan_below_floor:%:margin=%:floor=%',
                plan.code, round(margin, 5), floor_;
        END IF;
        IF margin > ceiling_ THEN
            RAISE EXCEPTION 'repriced_plan_above_band:%:margin=%:ceiling=%',
                plan.code, round(margin, 5), ceiling_;
        END IF;
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 7. Assert nothing else in the catalogue is underwater either
--
-- The token plans were the loud failures, but the same argument applies to every
-- published version: if its worst case does not clear the floor, it is a loss
-- waiting for the right customer. Swept across BOTH dimensions, using each
-- dimension's own basis and unit, so the video plans are checked against seconds
-- rather than tokens.
--
-- A zero-price version is a giveaway by design and is skipped, which is also
-- what PublishedPlanMarginMonitor does — FREE is the only one.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    plan     record;
    worst    bigint;
    unknown  integer;
    floor_   numeric := 0.30000;
BEGIN
    FOR plan IN
        SELECT version.id, plan_row.code, version.price_amount,
               version.total_token_quota, version.usage_dimension
        FROM platform.package_plan_version version
        JOIN platform.package_plan plan_row ON plan_row.id = version.package_plan_id
        WHERE version.status = 'PUBLISHED'
          AND version.price_amount > 0
          -- Assert seeded replacements, not an administrator's independent catalogue.
          AND version.id IN (
              '00000000-0000-4000-8000-000000000112',
              '00000000-0000-4000-8000-000000000113',
              '00000000-0000-4000-8000-000000000214',
              '00000000-0000-4000-8000-000000000301',
              '00000000-0000-4000-8000-000000000302',
              '00000000-0000-4000-8000-000000000304')
    LOOP
        SELECT count(*) INTO unknown
        FROM platform.package_plan_version_model model
        WHERE model.package_plan_version_id = plan.id
          AND NOT EXISTS (
                SELECT 1 FROM (VALUES
                    ('TOKEN', 'file-basic'), ('TOKEN', 'text-basic'),
                    ('TOKEN', 'text-standard'), ('TOKEN', 'text-pro'),
                    ('TOKEN', 'text-max'), ('TOKEN', 'vision-basic'),
                    ('TOKEN', 'vision-pro'), ('TOKEN', 'multimodal-basic'),
                    ('VIDEO_SECOND', 'video-max'), ('VIDEO_SECOND', 'video-standard'),
                    ('VIDEO_SECOND', 'video-pro'), ('VIDEO_SECOND', 'video-basic'),
                    ('VIDEO_SECOND', 'video-budget')
                ) AS known(dimension, model_code)
                WHERE known.dimension = plan.usage_dimension
                  AND known.model_code = model.model_code);

        IF unknown > 0 THEN
            RAISE EXCEPTION 'published_plan_has_unpriced_model:%:count=%',
                plan.code, unknown;
        END IF;

        WITH basis(usage_dimension, model_code, unit_cost) AS (
            VALUES ('TOKEN', 'file-basic', 200), ('TOKEN', 'text-basic', 800),
                   ('TOKEN', 'text-standard', 900), ('TOKEN', 'text-pro', 2700),
                   ('TOKEN', 'text-max', 3600), ('TOKEN', 'vision-basic', 720),
                   ('TOKEN', 'vision-pro', 1200), ('TOKEN', 'multimodal-basic', 800),
                   ('VIDEO_SECOND', 'video-max', 345), ('VIDEO_SECOND', 'video-standard', 115),
                   ('VIDEO_SECOND', 'video-pro', 92), ('VIDEO_SECOND', 'video-basic', 69),
                   ('VIDEO_SECOND', 'video-budget', 35)
        ), perms AS (
            SELECT model.model_token_quota AS cap, basis.unit_cost
            FROM platform.package_plan_version_model model
            JOIN basis ON basis.model_code = model.model_code
                      AND basis.usage_dimension = plan.usage_dimension
            WHERE model.package_plan_version_id = plan.id
        ), filled AS (
            SELECT cap, unit_cost,
                   least(coalesce(cap, plan.total_token_quota),
                         greatest(plan.total_token_quota - coalesce(
                             sum(coalesce(cap, plan.total_token_quota)) OVER (
                                 ORDER BY unit_cost DESC, cap
                                 ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0), 0)) AS consumed
            FROM perms
        )
        -- Each dimension divides by its own unit: 1,000,000 tokens per TOKEN cost,
        -- 1 per VIDEO_SECOND cost. Getting this wrong is not cosmetic — it
        -- multiplies a token plan's worst case by a million and would have this
        -- check reject every token plan in the catalogue.
        SELECT coalesce(ceil(sum(round(consumed * unit_cost
                    / CASE WHEN plan.usage_dimension = 'TOKEN' THEN 1000000.0 ELSE 1 END, 10))), 0)::bigint
        INTO worst
        FROM filled;

        IF worst > plan.price_amount THEN
            RAISE EXCEPTION 'published_plan_loses_money:%:dimension=%:price=%:worst=%',
                plan.code, plan.usage_dimension, plan.price_amount, worst;
        END IF;
        IF (plan.price_amount - worst)::numeric / plan.price_amount < floor_ THEN
            RAISE EXCEPTION 'published_plan_below_floor:%:dimension=%:margin=%:floor=%',
                plan.code, plan.usage_dimension,
                round((plan.price_amount - worst)::numeric / plan.price_amount, 5), floor_;
        END IF;
    END LOOP;
END $$;

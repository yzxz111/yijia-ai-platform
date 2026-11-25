-- VIDEO_PRO: Real Cost Basis And A Per-Model Ceiling
--
-- WHY THIS MIGRATION EXISTS
--
-- V54 seeded the three video plans with hand-written cost snapshots (4,800 /
-- 15,200 / 48,000 分) rather than publishing them through the margin guard, so
-- their stored margins were never computed. Checked against the live rate card
-- (gateway.video_effective_cost), two of the three were merely pessimistic and
-- one was a loss:
--
--   VIDEO_STARTER  60 s x 115 =   6,900 vs  12,900  -> +46.5%   safe
--   VIDEO_CREATOR 190 s x 115 =  21,850 vs  39,900  -> +45.2%   safe
--   VIDEO_PRO     600 s x 345 = 207,000 vs 129,900  -> -59.4%   LOSS
--
-- VIDEO_PRO granted `video-max` (345 分/s cost against a 500 分/s retail) a
-- share of a 600-second quota priced at 216.5 分/s, so a customer who spent the
-- quota on the most attractive entitlement it advertised lost the platform
-- 77,100 分 (CNY 771) per subscription, uncapped.
--
-- THE FIX, AND WHY BOTH HALVES ARE NEEDED
--
-- The plan is repriced to 139,900 分 (CNY 1,399, an 8% increase over CNY 1,299)
-- AND every alias gets its own per-model ceiling. A ceiling alone would have
-- made the plan honest but pointless — capping `video-max` at a few seconds
-- leaves the expensive tier unusable — and a price rise alone would have needed
-- to clear 207,000 分, a 59% increase on a plan whose whole appeal is its
-- per-second price. Together they keep the headline at 600 seconds, raise
-- `video-max` from an unbounded liability to a usable 150 seconds, and bound the
-- worst case into the intended 30-40% band:
--
--   guard worst case, dearest alias first, each capped:
--     video-max      150 s x 345 = 51,750
--     video-standard 120 s x 115 = 13,800
--     video-basic    100 s x  69 =  6,900
--     video-pro       80 s x  92 =  7,360
--     video-budget   150 s x  35 =  5,250
--     ------------------------------------
--     total          600 s       = 85,060 分  against 139,900 分 -> +39.2% on
--     revenue, i.e. 64.5% profit on cost. The same worst case at the old
--     CNY 1,299 price would still clear the floor at +34.3%, so the increase is
--     not what makes the plan safe — the ceilings are. It is charged because the
--     old price was set against a cost basis that was simply wrong.
--
-- The ceilings sum to exactly the 600-second headline, so every advertised
-- second is usable; they only decide WHICH alias supplies it. That is what makes
-- the guard's own model work here: it fills the whole quota with the dearest
-- permitted alias first, so without ceilings the only admissible price is one
-- that covers 600 seconds of `video-max` (207,000 分). The ceilings express the
-- product decision — this plan sells a broad 600 seconds, with 150 of them at
-- the premium tier — and the guard then prices that decision instead of a worst
-- case that no longer exists.
--
-- Per-model quotas are already honoured end to end: platform
-- package_plan_version_model.model_token_quota is validated as
-- `> 0 AND <= total_token_quota` by PackageActivationService and written to
-- gateway.package_instance_model_quota.seconds_quota for the seconds dimension.
--
-- Published versions are immutable (platform.reject_published_version_mutation),
-- so this adds a NEW version and retires the old one, exactly as the service's
-- publish path does. The v1 rows and their snapshot stay for history.
--
-- ORDER MATTERS and is not the obvious one. A version must be inserted as DRAFT,
-- given its model permissions, and only then published: the companion trigger
-- platform.reject_published_model_mutation refuses to insert a permission row
-- while its parent version is PUBLISHED or RETIRED. Inserting the version as
-- PUBLISHED first and adding permissions afterwards fails with
-- `published_version_immutable`. V54 sets out the same order in its own comment.

-- ---------------------------------------------------------------------------
-- 1. Retire VIDEO_PRO v1 and add v2 as a DRAFT
-- ---------------------------------------------------------------------------

-- The trigger permits exactly this transition: status -> RETIRED with only
-- retired_at also changing.
UPDATE platform.package_plan_version
SET status = 'RETIRED', retired_at = now()
WHERE id = '00000000-0000-4000-8000-000000000303'
  AND package_plan_id = '00000000-0000-4000-8000-000000000013'
  AND status = 'PUBLISHED';

INSERT INTO platform.package_plan_version(
    id, package_plan_id, version, price_amount, currency, total_token_quota,
    duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
    max_concurrency, target_margin_ratio, status, published_at,
    daily_request_limit, max_output_tokens, max_file_size_bytes,
    once_per_user, estimated_cost_amount, estimated_margin_ratio,
    margin_warning, stock_total, usage_dimension)
VALUES (
    '00000000-0000-4000-8000-000000000304',
    '00000000-0000-4000-8000-000000000013',
    2, 139900, 'CNY', 600,
    30, 10, 120, 600,
    10, 0.35000, 'DRAFT', NULL,
    1200, NULL, 104857600,
    false, 85060, 0.39199,
    false, NULL, 'VIDEO_SECOND');

-- ---------------------------------------------------------------------------
-- 2. Per-alias ceilings
--
-- Each ceiling is the share of the 600 seconds that alias may consume. They sum
-- to exactly the headline, so no advertised second is unreachable; they only
-- decide which alias supplies it. Listed cheapest-first for readability, which
-- is not the order the guard prices them in (it starts at video-max).
--
-- `video-max` gets the largest single share because it is the plan's premium
-- selling point; the point of the ceiling is that its share is knowable, not
-- that it is small.
-- ---------------------------------------------------------------------------

INSERT INTO platform.package_plan_version_model(
    id, package_plan_version_id, model_code, model_token_quota, capabilities)
VALUES
    (gen_random_uuid(), '00000000-0000-4000-8000-000000000304', 'video-budget',    150, '["TEXT","IMAGE","VIDEO"]'),
    (gen_random_uuid(), '00000000-0000-4000-8000-000000000304', 'video-basic',     100, '["TEXT","IMAGE","VIDEO"]'),
    (gen_random_uuid(), '00000000-0000-4000-8000-000000000304', 'video-pro',        80, '["TEXT","IMAGE","VIDEO"]'),
    (gen_random_uuid(), '00000000-0000-4000-8000-000000000304', 'video-standard',  120, '["TEXT","IMAGE","VIDEO"]'),
    (gen_random_uuid(), '00000000-0000-4000-8000-000000000304', 'video-max',       150, '["TEXT","IMAGE","VIDEO"]');

-- ---------------------------------------------------------------------------
-- 2b. Publish v2
--
-- The immutability trigger permits exactly this one transition, which is why the
-- estimate columns were written on the DRAFT insert above.
-- ---------------------------------------------------------------------------

UPDATE platform.package_plan_version
SET status = 'PUBLISHED', published_at = now()
WHERE id = '00000000-0000-4000-8000-000000000304'
  AND status = 'DRAFT';

-- ---------------------------------------------------------------------------
-- 3. Assert the published version is admissible and profitable
--
-- A migration that writes a published version directly must prove the guard
-- would have accepted it and that its worst case clears the seconds floor.
--
-- The cost basis is embedded as literals rather than read from
-- gateway.video_effective_cost, for two reasons. A migration must be a fixed
-- record of the numbers it was written against, so a later rate-card change
-- cannot silently rewrite what this version was approved on. And the platform's
-- Flyway owns only the `platform` schema — the gateway schema does not exist in a
-- platform-only container, so a cross-schema query here would fail this
-- migration in every platform Testcontainers run. These literals are the live
-- rate card at the time of writing, and PlatformMigrationTest asserts them equal
-- to gateway.video_effective_cost in the environment where both schemas exist.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    price  bigint;
    quota  bigint;
    worst  bigint;
    -- The VIDEO_SECOND floor this version was approved against, frozen as a
    -- literal. It deliberately does NOT read
    -- platform.package_dimension_price_policy: migrations must record the numbers
    -- they were written against, and since V57 that table is a live operator
    -- control, so reading it here would make a replayed migration reach a
    -- different verdict than the one that actually ran.
    floor_ numeric := 0.25000;
BEGIN
    SELECT version.price_amount, version.total_token_quota
    INTO price, quota
    FROM platform.package_plan_version version
    WHERE version.id = '00000000-0000-4000-8000-000000000304';

    IF price IS NULL THEN
        RAISE EXCEPTION 'video_pro_v2_missing';
    END IF;

    -- The guard's own calculation, reproduced exactly: order the permissions by
    -- unit cost descending and let each consume min(cap, remaining), so a NULL cap
    -- means "this alias may take whatever is left".
    WITH basis(model_code, unit_cost) AS (
        VALUES ('video-max', 345), ('video-standard', 115), ('video-pro', 92),
               ('video-basic', 69), ('video-budget', 35)
    ), perms AS (
        SELECT model.model_token_quota AS cap, basis.unit_cost
        FROM platform.package_plan_version_model model
        JOIN basis ON basis.model_code = model.model_code
        WHERE model.package_plan_version_id = '00000000-0000-4000-8000-000000000304'
    ), filled AS (
        SELECT cap, unit_cost,
               least(coalesce(cap, quota),
                     greatest(quota - coalesce(
                         sum(coalesce(cap, quota)) OVER (
                             ORDER BY unit_cost DESC, cap
                             ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0), 0)) AS consumed
        FROM perms
    )
    SELECT coalesce(sum(consumed * unit_cost), 0)::bigint INTO worst FROM filled;

    IF worst <= 0 THEN
        RAISE EXCEPTION 'video_pro_v2_unpriceable:no_cost_basis';
    END IF;
    IF worst > price THEN
        RAISE EXCEPTION 'video_pro_v2_loses_money:price=%:worst=%', price, worst;
    END IF;
    IF (price - worst)::numeric / price < floor_ THEN
        RAISE EXCEPTION 'video_pro_v2_below_seconds_floor:margin=%:floor=%',
            round((price - worst)::numeric / price, 5), floor_;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 4. Confirm the two healthy plans are still healthy
--
-- VIDEO_STARTER and VIDEO_CREATOR keep their existing versions and prices: their
-- worst cases clear the seconds floor comfortably (46.5% and 45.2%). Asserted
-- rather than assumed, so a later rate-card change fails here instead of
-- quietly turning them into losses.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    plan record;
    worst bigint;
    floor_ numeric := 0.25000;
BEGIN
    FOR plan IN
        SELECT version.id, plan_row.code, version.price_amount, version.total_token_quota
        FROM platform.package_plan_version version
        JOIN platform.package_plan plan_row ON plan_row.id = version.package_plan_id
        WHERE version.status = 'PUBLISHED'
          AND version.usage_dimension = 'VIDEO_SECOND'
          AND plan_row.code IN ('VIDEO_STARTER', 'VIDEO_CREATOR')
    LOOP
        -- Same embedded basis as section 3, and for the same reason: no
        -- cross-schema read, so this migration applies in a platform-only
        -- container.
        WITH basis(model_code, unit_cost) AS (
            VALUES ('video-max', 345), ('video-standard', 115), ('video-pro', 92),
                   ('video-basic', 69), ('video-budget', 35)
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
        SELECT coalesce(sum(consumed * unit_cost), 0)::bigint INTO worst FROM filled;

        IF worst > plan.price_amount THEN
            RAISE EXCEPTION 'video_plan_loses_money:%:price=%:worst=%',
                plan.code, plan.price_amount, worst;
        END IF;
        IF (plan.price_amount - worst)::numeric / plan.price_amount < floor_ THEN
            RAISE EXCEPTION 'video_plan_below_seconds_floor:%:margin=%:floor=%',
                plan.code,
                round((plan.price_amount - worst)::numeric / plan.price_amount, 5), floor_;
        END IF;
    END LOOP;
END $$;

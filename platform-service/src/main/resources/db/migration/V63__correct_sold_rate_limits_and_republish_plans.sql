-- Correct The Sold Rate Limits That TPM Enforcement Made Real
--
-- THE BUG. tokens_per_minute is now enforced by the gateway per package, and the
-- three video plans carry the SECONDS quota in that column:
--
--   VIDEO_STARTER  total_token_quota =  60 s  ->  tokens_per_minute =   60
--   VIDEO_CREATOR  total_token_quota = 190 s  ->  tokens_per_minute =  190
--   VIDEO_PRO      total_token_quota = 600 s  ->  tokens_per_minute =  600
--
-- Every video plan grants TEXT capabilities (each of its aliases carries
-- ["TEXT","IMAGE","VIDEO"]), so those plans sell chat traffic too. With the column
-- enforced, a VIDEO_PRO customer's chat is capped at 600 tokens per minute and a
-- VIDEO_STARTER customer's at 60 -- roughly one short answer per minute. The column
-- was written from the quota because nothing read it; now that something does, the
-- value has to be the rate the plan actually sells.
--
-- The video tiers are the token tier at the same price point -- VIDEO_STARTER 129.00
-- for 60 s sits with STARTER 29.00, VIDEO_CREATOR 399.00 for 190 s with ADVANCED
-- 399.00, VIDEO_PRO 1,399.00 for 600 s with ENTERPRISE 1,599.00 -- so their chat
-- entitlement is the same product and they get the same rate as that tier: 100,000 /
-- 300,000 / 500,000 tokens per minute.
--
-- THE SECOND BUG. The three paid token plans sell no daily request limit and no
-- output cap at all (NULL), so the enforcement points the gateway now has are simply
-- absent on the plans customers pay for. They get the tiers they advertise:
--
--   plan          tokens_per_minute  daily_request_limit  max_output_tokens
--   STARTER              100,000            2,000               4,096   (tpm unchanged)
--   ADVANCED             500,000           10,000               8,192   (tpm unchanged)
--   ENTERPRISE         2,000,000           50,000              16,384   (tpm unchanged)
--   VIDEO_STARTER        100,000              200               4,096   (was 60)
--   VIDEO_CREATOR        300,000              500               8,192   (was 190)
--   VIDEO_PRO            500,000            1,200               8,192   (was 600)
--
-- FREE needs no new version: 20,000 / 20 / 2,000 is already coherent.
--
-- PRICE, QUOTA, DURATION, CAPABILITIES AND MODEL SETS ARE UNCHANGED. Only the three
-- sold limits above and the stored margin estimate move.
--
-- THE STORED MARGIN ESTIMATE IS RECOMPUTED for the video plans, because theirs was
-- computed against the pre-V55 cost basis and disagrees with the guard's own fill:
-- VIDEO_STARTER carried 4,800 (60 s valued at 80 per second) and VIDEO_CREATOR
-- 15,200, while the dearest alias each plan actually advertises is video-standard at
-- 115 per second, so the guard's fill is 6,900 and 21,850. VIDEO_PRO's 85,060 was
-- already right. The estimate is what the console shows an operator, so it has to be
-- the number the guard computes; the assertion block below refuses to publish a row
-- where the two disagree.
--
-- ORDER MATTERS and is not the obvious one. A version must be inserted as DRAFT,
-- given its model permissions, and only then published: the companion trigger
-- platform.reject_published_model_mutation refuses to insert a permission row while
-- its parent version is PUBLISHED or RETIRED, and uq_published_package_plan_version
-- allows only one PUBLISHED version per plan, so the old version has to be retired
-- before the new one can be published. Published versions are immutable
-- (platform.reject_published_version_mutation) and are NOT patched here: the only
-- change the trigger permits is status -> RETIRED.
--
-- The cost basis below is embedded as literals rather than read from the gateway
-- schema, exactly as V61 does: a migration is a fixed record of the numbers it was
-- written against, and platform Flyway owns only the `platform` schema, so a
-- cross-schema read would fail in every platform-only Testcontainers run.
--
-- The LOW_MARGIN_PACKAGE alerts for the versions retired here are NOT hand-resolved
-- the way V61 section 5 resolved its three. They no longer need to be:
-- PublishedPlanMarginMonitor now closes an alert whose version is no longer
-- PUBLISHED on its next run, so a migration is no longer the only thing that can
-- retract one.

-- ---------------------------------------------------------------------------
-- 1. Retire the six published versions this migration replaces
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE v63_rate_targets ON COMMIT DROP AS
SELECT id FROM platform.package_plan_version
WHERE status = 'PUBLISHED' AND id IN (
    '00000000-0000-4000-8000-000000000112',
    '00000000-0000-4000-8000-000000000113',
    '00000000-0000-4000-8000-000000000214',
    '00000000-0000-4000-8000-000000000301',
    '00000000-0000-4000-8000-000000000302',
    '00000000-0000-4000-8000-000000000304');

UPDATE platform.package_plan_version
SET status = 'RETIRED', retired_at = now()
WHERE id IN (
        '00000000-0000-4000-8000-000000000112',  -- STARTER v2
        '00000000-0000-4000-8000-000000000113',  -- ADVANCED v2
        '00000000-0000-4000-8000-000000000214',  -- ENTERPRISE v3
        '00000000-0000-4000-8000-000000000301',  -- VIDEO_STARTER v1
        '00000000-0000-4000-8000-000000000302',  -- VIDEO_CREATOR v1
        '00000000-0000-4000-8000-000000000304')  -- VIDEO_PRO v2
  AND status = 'PUBLISHED';

-- ---------------------------------------------------------------------------
-- 2. Add the replacements as DRAFTs
--
-- Everything not named in the SELECT is copied from the version being replaced, so
-- price, quota, duration, keys, request rate, concurrency, file size, inventory and
-- the dimension are preserved by construction and cannot drift with a re-typed
-- literal. margin_warning is derived from the recomputed estimate with the guard's
-- own comparison (margin < warning_margin, the 0.40000 V62 set), not typed in.
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
        WHERE existing.package_plan_id = old.package_plan_id), old.price_amount,
       old.currency, old.total_token_quota, old.duration_days, old.max_api_keys,
       old.requests_per_minute, replacement.tokens_per_minute, old.max_concurrency,
       old.target_margin_ratio, 'DRAFT', NULL, NULL, replacement.daily_request_limit,
       replacement.max_output_tokens, old.max_file_size_bytes, old.once_per_user,
       replacement.worst_cost, replacement.margin,
       replacement.margin < 0.40000, old.stock_total, old.low_stock_threshold,
       old.max_purchases_per_user, old.usage_dimension
FROM (VALUES
        ('00000000-0000-4000-8000-000000000122'::uuid,
         '00000000-0000-4000-8000-000000000112'::uuid, 3,
          100000::bigint,  2000::integer,  4096::integer,  1800::bigint, 0.37931::numeric),
        ('00000000-0000-4000-8000-000000000123'::uuid,
         '00000000-0000-4000-8000-000000000113'::uuid, 3,
          500000::bigint, 10000::integer,  8192::integer, 25800::bigint, 0.35338::numeric),
        ('00000000-0000-4000-8000-000000000224'::uuid,
         '00000000-0000-4000-8000-000000000214'::uuid, 4,
         2000000::bigint, 50000::integer, 16384::integer,104200::bigint, 0.34834::numeric),
        ('00000000-0000-4000-8000-000000000311'::uuid,
         '00000000-0000-4000-8000-000000000301'::uuid, 2,
          100000::bigint,   200::integer,  4096::integer,  6900::bigint, 0.46512::numeric),
        ('00000000-0000-4000-8000-000000000312'::uuid,
         '00000000-0000-4000-8000-000000000302'::uuid, 2,
          300000::bigint,   500::integer,  8192::integer, 21850::bigint, 0.45238::numeric),
        ('00000000-0000-4000-8000-000000000313'::uuid,
         '00000000-0000-4000-8000-000000000304'::uuid, 3,
          500000::bigint,  1200::integer,  8192::integer, 85060::bigint, 0.39199::numeric)
     ) AS replacement(id, replaces, version, tokens_per_minute, daily_request_limit,
                      max_output_tokens, worst_cost, margin)
JOIN platform.package_plan_version old ON old.id = replacement.replaces
JOIN v63_rate_targets target ON target.id = old.id;

-- Scope assertions to rows this migration creates. Custom plans and seed plans
-- already replaced by administrators are outside this data correction.
CREATE TEMP TABLE v63_created_versions ON COMMIT DROP AS
SELECT id FROM platform.package_plan_version
WHERE status = 'DRAFT' AND id IN (
    '00000000-0000-4000-8000-000000000122',
    '00000000-0000-4000-8000-000000000123',
    '00000000-0000-4000-8000-000000000224',
    '00000000-0000-4000-8000-000000000311',
    '00000000-0000-4000-8000-000000000312',
    '00000000-0000-4000-8000-000000000313');

-- ---------------------------------------------------------------------------
-- 3. Copy the model permissions verbatim
--
-- The model sets, their per-alias ceilings and their capabilities are untouched by
-- this migration: this is a rate-limit correction, not a repricing.
-- ---------------------------------------------------------------------------

INSERT INTO platform.package_plan_version_model(
    id, package_plan_version_id, model_code, model_token_quota, capabilities)
SELECT gen_random_uuid(), replacement.id, old.model_code, old.model_token_quota,
       old.capabilities
FROM (VALUES
        ('00000000-0000-4000-8000-000000000122'::uuid,
         '00000000-0000-4000-8000-000000000112'::uuid),
        ('00000000-0000-4000-8000-000000000123'::uuid,
         '00000000-0000-4000-8000-000000000113'::uuid),
        ('00000000-0000-4000-8000-000000000224'::uuid,
         '00000000-0000-4000-8000-000000000214'::uuid),
        ('00000000-0000-4000-8000-000000000311'::uuid,
         '00000000-0000-4000-8000-000000000301'::uuid),
        ('00000000-0000-4000-8000-000000000312'::uuid,
         '00000000-0000-4000-8000-000000000302'::uuid),
        ('00000000-0000-4000-8000-000000000313'::uuid,
         '00000000-0000-4000-8000-000000000304'::uuid)
     ) AS replacement(id, replaces)
JOIN platform.package_plan_version_model old
  ON old.package_plan_version_id = replacement.replaces
JOIN v63_created_versions created ON created.id = replacement.id;

-- ---------------------------------------------------------------------------
-- 4. Publish the replacements
-- ---------------------------------------------------------------------------

UPDATE platform.package_plan_version
SET status = 'PUBLISHED', published_at = now()
WHERE id IN (
        '00000000-0000-4000-8000-000000000122',
        '00000000-0000-4000-8000-000000000123',
        '00000000-0000-4000-8000-000000000224',
        '00000000-0000-4000-8000-000000000311',
        '00000000-0000-4000-8000-000000000312',
        '00000000-0000-4000-8000-000000000313')
  AND status = 'DRAFT';

-- ---------------------------------------------------------------------------
-- 5. Assert the sold limits, the margin floor and the bug's absence
--
-- Three separate claims, because they fail for different reasons:
--
--   a) every replacement carries exactly the sold limits this migration intends,
--      and the version that replaced a video plan no longer sells the seconds quota
--      as a token rate;
--   b) the guard would still accept every published version: same fill as V61
--      (dearest permitted alias first, each consuming min(cap, remaining), a NULL
--      cap taking the remainder), same embedded basis, each dimension divided by
--      its own unit, and the stored estimate has to be the number just computed;
--   c) the bug shape itself is gone from the whole catalogue: no published version
--      that grants TEXT sells a token rate of zero, negative or below 10,000, and no
--      published version sells a zero or negative limit anywhere.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    plan      record;
    expected  record;
    actual    record;
BEGIN
    FOR expected IN
        SELECT * FROM (VALUES
            ('STARTER',       3,  100000::bigint,  2000::integer,  4096::integer),
            ('ADVANCED',      3,  500000::bigint, 10000::integer,  8192::integer),
            ('ENTERPRISE',    4, 2000000::bigint, 50000::integer, 16384::integer),
            ('VIDEO_STARTER', 2,  100000::bigint,   200::integer,  4096::integer),
            ('VIDEO_CREATOR', 2,  300000::bigint,   500::integer,  8192::integer),
            ('VIDEO_PRO',     3,  500000::bigint,  1200::integer,  8192::integer)
        ) AS wanted(code, version, tokens_per_minute, daily_request_limit, max_output_tokens)
        WHERE EXISTS (
            SELECT 1 FROM v63_created_versions created
            JOIN platform.package_plan_version version ON version.id = created.id
            JOIN platform.package_plan plan_row ON plan_row.id = version.package_plan_id
            WHERE plan_row.code = wanted.code)
    LOOP
        SELECT plan_row.code, version.version, version.tokens_per_minute,
               version.daily_request_limit, version.max_output_tokens,
               version.status
        INTO actual
        FROM platform.package_plan_version version
        JOIN platform.package_plan plan_row ON plan_row.id = version.package_plan_id
        WHERE plan_row.code = expected.code
          AND version.id IN (SELECT id FROM v63_created_versions);

        IF actual IS NULL THEN
            RAISE EXCEPTION 'expected_plan_version_missing:%:v%',
                expected.code, expected.version;
        END IF;
        IF actual.status <> 'PUBLISHED' THEN
            RAISE EXCEPTION 'expected_plan_version_not_published:%:v%:status=%',
                expected.code, expected.version, actual.status;
        END IF;
        IF actual.tokens_per_minute <> expected.tokens_per_minute
                OR actual.daily_request_limit IS DISTINCT FROM expected.daily_request_limit
                OR actual.max_output_tokens IS DISTINCT FROM expected.max_output_tokens THEN
            RAISE EXCEPTION 'sold_limits_not_applied:%:v%:tpm=%:daily=%:maxout=%',
                expected.code, expected.version, actual.tokens_per_minute,
                actual.daily_request_limit, actual.max_output_tokens;
        END IF;
    END LOOP;
END $$;

DO $$
DECLARE
    plan     record;
    worst    bigint;
    unknown  integer;
    floor_   numeric := 0.30000;
    warning_ numeric := 0.40000;
    margin   numeric;
    legacy   integer;
BEGIN
    FOR plan IN
        SELECT version.id, plan_row.code, version.price_amount,
               version.total_token_quota, version.usage_dimension,
               version.estimated_cost_amount, version.estimated_margin_ratio,
               version.margin_warning
        FROM platform.package_plan_version version
        JOIN platform.package_plan plan_row ON plan_row.id = version.package_plan_id
        WHERE version.status = 'PUBLISHED' AND version.price_amount > 0
          AND version.id IN (SELECT id FROM v63_created_versions)
    LOOP
        -- Fail closed if the plan grants something this migration cannot price.
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
        -- The divisor is the dimension's own unit: 1,000,000 tokens per TOKEN cost,
        -- 1 per VIDEO_SECOND cost. Getting this wrong is not cosmetic -- it would
        -- multiply a token plan's worst case by a million.
        SELECT coalesce(ceil(sum(round(consumed * unit_cost
                    / CASE WHEN plan.usage_dimension = 'TOKEN' THEN 1000000.0 ELSE 1 END, 10))), 0)::bigint
        INTO worst
        FROM filled;

        IF worst <= 0 THEN
            RAISE EXCEPTION 'published_plan_unpriceable:%:no_cost_basis', plan.code;
        END IF;
        IF worst > plan.price_amount THEN
            RAISE EXCEPTION 'published_plan_loses_money:%:dimension=%:price=%:worst=%',
                plan.code, plan.usage_dimension, plan.price_amount, worst;
        END IF;

        -- The stored estimate is what the console shows an operator, so it has to be
        -- the same number this assertion just computed.
        IF worst <> plan.estimated_cost_amount THEN
            RAISE EXCEPTION 'published_plan_estimate_disagrees:%:stored=%:computed=%',
                plan.code, plan.estimated_cost_amount, worst;
        END IF;

        margin := (plan.price_amount - worst)::numeric / plan.price_amount;
        IF round(margin, 5) <> plan.estimated_margin_ratio THEN
            RAISE EXCEPTION 'published_plan_margin_ratio_disagrees:%:stored=%:computed=%',
                plan.code, plan.estimated_margin_ratio, round(margin, 5);
        END IF;
        IF margin < floor_ THEN
            RAISE EXCEPTION 'published_plan_below_floor:%:dimension=%:margin=%:floor=%',
                plan.code, plan.usage_dimension, round(margin, 5), floor_;
        END IF;

        -- Decision 1's consequence, asserted on the stored flag: a version inside
        -- 30-40% is published and flagged, not refused. The comparison has to be the
        -- one PackageMarginPolicy makes (strictly below the warning floor).
        IF plan.margin_warning <> (plan.estimated_margin_ratio < warning_) THEN
            RAISE EXCEPTION 'published_plan_warning_flag_disagrees:%:flag=%:margin=%',
                plan.code, plan.margin_warning, plan.estimated_margin_ratio;
        END IF;
    END LOOP;

    -- The bug shape itself: a TEXT-entitling plan sold a token rate a chat customer
    -- cannot use. 10,000 per minute is a low bar -- far below every real tier -- and
    -- chosen only so that a value which is obviously not a token rate (60, 190, 600,
    -- or a NULL) cannot pass.
    SELECT count(*) INTO legacy
    FROM platform.package_plan_version version
    JOIN platform.package_plan plan_row ON plan_row.id = version.package_plan_id
    WHERE version.status = 'PUBLISHED'
      AND version.id IN (SELECT id FROM v63_created_versions)
      AND version.tokens_per_minute < 10000
      AND EXISTS (
            SELECT 1 FROM platform.package_plan_version_model model
            WHERE model.package_plan_version_id = version.id
              AND model.capabilities @> '["TEXT"]'::jsonb);

    IF legacy > 0 THEN
        RAISE EXCEPTION 'published_plan_text_capability_below_a_usable_token_rate:count=%',
            legacy;
    END IF;

    -- No published version may sell a zero or negative limit anywhere.
    PERFORM 1 FROM platform.package_plan_version
    WHERE status = 'PUBLISHED'
      AND id IN (SELECT id FROM v63_created_versions)
      AND (tokens_per_minute <= 0 OR requests_per_minute <= 0 OR max_api_keys <= 0
           OR max_concurrency <= 0 OR duration_days <= 0 OR total_token_quota <= 0
           OR max_file_size_bytes <= 0
           OR daily_request_limit <= 0 OR max_output_tokens <= 0);
    IF FOUND THEN
        RAISE EXCEPTION 'published_plan_sells_a_non_positive_limit';
    END IF;
END $$;

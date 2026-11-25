-- Restore The Video Plans' Seconds-Per-Minute Ceiling
--
-- THE MISTAKE THIS MIGRATION CORRECTS. V63 read
-- platform.package_plan_version.tokens_per_minute as a plain token rate and
-- "corrected" the three VIDEO_SECOND plans to 100,000 / 300,000 / 500,000 --
-- the rate of the token tier at the same price point. That was wrong. The
-- column is DIMENSION-CARRYING, exactly like total_token_quota and
-- package_plan_version_model.model_token_quota (V54): for a TOKEN version it is
-- a token rate, but for a VIDEO_SECOND version it is a SECONDS-PER-MINUTE (SPM)
-- ceiling -- how many seconds of video the plan may consume per minute of wall
-- clock, which is what the gateway enforces against the seconds dimension.
--
-- The product data says so, and did so before V63 touched it:
--
--   plan          price (分)   seconds  SPM ceiling   meaning
--   VIDEO_STARTER     12,900        60           60   1x realtime
--   VIDEO_CREATOR     39,900       190          190   ~3.2x realtime
--   VIDEO_PRO        139,900       600          600   10x realtime
--
-- and the console depends on it: web-console/src/views/user/PlansView.vue
-- labels the value "SPM" for a VIDEO_SECOND plan (its speed-limit mapping tests
-- isSecondDimension(usageDimension) before choosing SPM or TPM) precisely because
-- the number is a seconds-per-minute ceiling. V63 turned real product data into a
-- nonsense number, and the console has been rendering "SPM 100,000" ever since.
--
-- WHY "1x realtime" IS THE RIGHT READING AND NOT A COINCIDENCE: the ceiling
-- equals the plan's whole seconds quota for VIDEO_STARTER (60 s per minute is
-- realtime -- a one-minute video takes one minute), and exceeds it by the factor
-- the tier sells (VIDEO_PRO's 600 is 10x realtime, which is why a 600-second
-- package can be burned in one minute at 10x). A token-rate reading makes the
-- value depend on the token tier instead, which is what V63 did.
--
-- NOTHING ELSE MOVES. Price (12,900 / 39,900 / 139,900 分), seconds quota
-- (60 / 190 / 600), duration, keys, request rate, concurrency, file size,
-- inventory, capabilities, model sets, per-model ceilings and the stored margin
-- estimate are copied verbatim from the version being replaced, so the only
-- difference between a video plan and its successor is the one column V63 got
-- wrong. The margins stay where V63 left them -- 46.512%, 45.238% and 39.199%,
-- the last of which keeps VIDEO_PRO's margin_warning true -- and the assertion
-- block below recomputes each one from the cost basis regardless, so a copy that
-- disagreed with the guard's arithmetic would refuse the migration.
--
-- ORDER MATTERS and is not the obvious one. A version must be inserted as DRAFT,
-- given its model permissions, and only then published: the companion trigger
-- platform.reject_published_model_mutation refuses to insert a permission row
-- while its parent version is PUBLISHED or RETIRED, and
-- uq_published_package_plan_version allows only one PUBLISHED version per plan,
-- so the old version has to be retired before the new one can be published.
-- Published versions are immutable (platform.reject_published_version_mutation)
-- and are NOT patched here: the only change the trigger permits is
-- status -> RETIRED.
--
-- The cost basis below is embedded as literals rather than read from the gateway
-- schema, exactly as V61/V63 do: a migration is a fixed record of the numbers it
-- was written against, and platform Flyway owns only the `platform` schema, so a
-- cross-schema read would fail in every platform-only Testcontainers run.
--
-- The LOW_MARGIN_PACKAGE alerts for the versions retired here are NOT
-- hand-resolved, the way V61 section 5 resolved its three. PublishedPlanMarginMonitor
-- closes an alert whose version is no longer PUBLISHED on its next run, so a
-- migration is no longer the only thing that can retract one -- and the resolution
-- it writes is attributable to the machine that performed it.

-- ---------------------------------------------------------------------------
-- 1. Retire the three published video versions this migration replaces
-- ---------------------------------------------------------------------------

-- The immutability trigger permits exactly this transition: status -> RETIRED
-- with only retired_at also changing.
CREATE TEMP TABLE v64_rate_targets ON COMMIT DROP AS
SELECT id FROM platform.package_plan_version
WHERE status = 'PUBLISHED' AND id IN (
    '00000000-0000-4000-8000-000000000311',
    '00000000-0000-4000-8000-000000000312',
    '00000000-0000-4000-8000-000000000313');

UPDATE platform.package_plan_version
SET status = 'RETIRED', retired_at = now()
WHERE id IN (
        '00000000-0000-4000-8000-000000000311',  -- VIDEO_STARTER v2
        '00000000-0000-4000-8000-000000000312',  -- VIDEO_CREATOR v2
        '00000000-0000-4000-8000-000000000313')  -- VIDEO_PRO v3
  AND status = 'PUBLISHED';

-- ---------------------------------------------------------------------------
-- 2. Add the replacements as DRAFTs
--
-- Everything not named in the SELECT is copied from the version being replaced,
-- so price, seconds quota, duration, keys, request rate, concurrency, file size,
-- inventory, dimension, capabilities and the stored margin estimate are
-- preserved by construction and cannot drift with a re-typed literal. The only
-- re-typed values are the version number and the seconds-per-minute ceiling --
-- the single column this migration exists to correct.
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
       old.requests_per_minute, replacement.seconds_per_minute, old.max_concurrency,
       old.target_margin_ratio, 'DRAFT', NULL, NULL, old.daily_request_limit,
       old.max_output_tokens, old.max_file_size_bytes, old.once_per_user,
       old.estimated_cost_amount, old.estimated_margin_ratio, old.margin_warning,
       old.stock_total, old.low_stock_threshold, old.max_purchases_per_user,
       old.usage_dimension
FROM (VALUES
        ('00000000-0000-4000-8000-000000000321'::uuid,
         '00000000-0000-4000-8000-000000000311'::uuid, 3,  60::bigint),
        ('00000000-0000-4000-8000-000000000322'::uuid,
         '00000000-0000-4000-8000-000000000312'::uuid, 3, 190::bigint),
        ('00000000-0000-4000-8000-000000000323'::uuid,
         '00000000-0000-4000-8000-000000000313'::uuid, 4, 600::bigint)
     ) AS replacement(id, replaces, version, seconds_per_minute)
JOIN platform.package_plan_version old ON old.id = replacement.replaces
JOIN v64_rate_targets target ON target.id = old.id;

CREATE TEMP TABLE v64_created_versions ON COMMIT DROP AS
SELECT id FROM platform.package_plan_version
WHERE status = 'DRAFT' AND id IN (
    '00000000-0000-4000-8000-000000000321',
    '00000000-0000-4000-8000-000000000322',
    '00000000-0000-4000-8000-000000000323');

-- ---------------------------------------------------------------------------
-- 3. Copy the model permissions verbatim
--
-- The model sets, their per-alias ceilings and their capabilities are untouched
-- by this migration: this is a rate-ceiling correction, not a repricing.
-- ---------------------------------------------------------------------------

INSERT INTO platform.package_plan_version_model(
    id, package_plan_version_id, model_code, model_token_quota, capabilities)
SELECT gen_random_uuid(), replacement.id, old.model_code, old.model_token_quota,
       old.capabilities
FROM (VALUES
        ('00000000-0000-4000-8000-000000000321'::uuid,
         '00000000-0000-4000-8000-000000000311'::uuid),
        ('00000000-0000-4000-8000-000000000322'::uuid,
         '00000000-0000-4000-8000-000000000312'::uuid),
        ('00000000-0000-4000-8000-000000000323'::uuid,
         '00000000-0000-4000-8000-000000000313'::uuid)
     ) AS replacement(id, replaces)
JOIN platform.package_plan_version_model old
  ON old.package_plan_version_id = replacement.replaces
JOIN v64_created_versions created ON created.id = replacement.id;

-- ---------------------------------------------------------------------------
-- 4. Publish the replacements
-- ---------------------------------------------------------------------------

UPDATE platform.package_plan_version
SET status = 'PUBLISHED', published_at = now()
WHERE id IN (
        '00000000-0000-4000-8000-000000000321',
        '00000000-0000-4000-8000-000000000322',
        '00000000-0000-4000-8000-000000000323')
  AND status = 'DRAFT';

-- ---------------------------------------------------------------------------
-- 5. Document the dimension-carrying convention on the column itself
--
-- The convention lived only in the product data and in the console mapping that
-- reads it, which is how V63 came to overwrite it. Naming the consumer makes the
-- dependency discoverable from psql, where the next person to "correct" this
-- column will be standing.
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN platform.package_plan_version.tokens_per_minute IS
    'Rate ceiling in the unit named by usage_dimension. TOKEN = tokens per minute '
    'sold to a chat customer; VIDEO_SECOND = seconds of video per minute of wall '
    'clock (SPM), where 60 is realtime and VIDEO_PRO''s 600 is 10x realtime. The '
    'two are not interchangeable: a VIDEO_SECOND plan''s value is its seconds '
    'ceiling, never its token tier. Consumer that depends on this: '
    'web-console/src/views/user/PlansView.vue (maps tokensPerMinute to the SPM '
    'label for a VIDEO_SECOND plan).';

-- ---------------------------------------------------------------------------
-- 6. Assert the restored ceilings, the margin floor and both rate invariants
--
-- Three separate claims, because they fail for different reasons:
--
--   a) every replacement carries exactly the sold limits and dimension this
--      migration intends, and the version it replaced is no longer published;
--   b) the guard would still accept every published version: same fill as
--      V61/V63 (dearest permitted alias first, each consuming min(cap,
--      remaining), a NULL cap taking the remainder), same embedded basis, each
--      dimension divided by its own unit, and the stored estimate has to be the
--      number just computed;
--   c) the invariant that was missing, asserted in both directions so neither
--      mistake can recur silently: a VIDEO_SECOND version's tokens_per_minute is
--      a seconds-per-minute ceiling (1..86400), and a TOKEN version that grants
--      TEXT sells a token rate a chat customer can actually use (>= 10000).
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    plan      record;
    expected  record;
    actual    record;
    superseded integer;
BEGIN
    FOR expected IN
        SELECT * FROM (VALUES
            ('VIDEO_STARTER', 3,  60::bigint,  60::bigint,  12900::bigint,  200::integer, 4096::integer, false),
            ('VIDEO_CREATOR', 3, 190::bigint, 190::bigint,  39900::bigint,  500::integer, 8192::integer, false),
            ('VIDEO_PRO',     4, 600::bigint, 600::bigint, 139900::bigint, 1200::integer, 8192::integer, true)
        ) AS wanted(code, version, tokens_per_minute, total_token_quota, price_amount,
                    daily_request_limit, max_output_tokens, margin_warning)
        WHERE EXISTS (
            SELECT 1 FROM v64_created_versions created
            JOIN platform.package_plan_version version ON version.id = created.id
            JOIN platform.package_plan plan_row ON plan_row.id = version.package_plan_id
            WHERE plan_row.code = wanted.code)
    LOOP
        SELECT plan_row.code, version.version, version.tokens_per_minute,
               version.total_token_quota, version.price_amount,
               version.daily_request_limit, version.max_output_tokens,
               version.usage_dimension, version.margin_warning, version.status
        INTO actual
        FROM platform.package_plan_version version
        JOIN platform.package_plan plan_row ON plan_row.id = version.package_plan_id
        WHERE plan_row.code = expected.code
          AND version.id IN (SELECT id FROM v64_created_versions);

        IF actual IS NULL THEN
            RAISE EXCEPTION 'expected_plan_version_missing:%:v%',
                expected.code, expected.version;
        END IF;
        IF actual.status <> 'PUBLISHED' THEN
            RAISE EXCEPTION 'expected_plan_version_not_published:%:v%:status=%',
                expected.code, expected.version, actual.status;
        END IF;
        IF actual.usage_dimension <> 'VIDEO_SECOND' THEN
            RAISE EXCEPTION 'expected_video_plan_dimension_changed:%:v%:dimension=%',
                expected.code, expected.version, actual.usage_dimension;
        END IF;
        IF actual.tokens_per_minute <> expected.tokens_per_minute
                OR actual.total_token_quota <> expected.total_token_quota
                OR actual.price_amount <> expected.price_amount
                OR actual.daily_request_limit IS DISTINCT FROM expected.daily_request_limit
                OR actual.max_output_tokens IS DISTINCT FROM expected.max_output_tokens
                OR actual.margin_warning <> expected.margin_warning THEN
            RAISE EXCEPTION 'sold_limits_not_applied:%:v%:spm=%:quota=%:price=%:daily=%:maxout=%:warning=%',
                expected.code, expected.version, actual.tokens_per_minute,
                actual.total_token_quota, actual.price_amount,
                actual.daily_request_limit, actual.max_output_tokens,
                actual.margin_warning;
        END IF;
    END LOOP;

    -- Retiring before republishing is what makes one PUBLISHED version per plan
    -- possible, so a replacement left as the plan's second published row (or a
    -- superseded row still published) is the failure this catches.
    SELECT count(*) INTO superseded
    FROM platform.package_plan_version
    WHERE id IN (
            '00000000-0000-4000-8000-000000000311',
            '00000000-0000-4000-8000-000000000312',
            '00000000-0000-4000-8000-000000000313')
      AND id IN (SELECT id FROM v64_rate_targets)
      AND status <> 'RETIRED';
    IF superseded > 0 THEN
        RAISE EXCEPTION 'superseded_video_version_not_retired:count=%', superseded;
    END IF;
END $$;

DO $$
DECLARE
    offending integer;
BEGIN
    -- A VIDEO_SECOND version's tokens_per_minute is a seconds-per-minute ceiling.
    -- Zero, a negative, or anything above a day's worth of seconds is not a
    -- ceiling any product sells; 86,400 is a sanity bound, not a physical one,
    -- and its job is to make "100000" (V63's value) impossible to write again.
    -- IMAGE is included because PublishedPlanMarginMonitor and the gateway both
    -- treat it as a seconds dimension too.
    SELECT count(*) INTO offending
    FROM platform.package_plan_version version
    WHERE version.status = 'PUBLISHED'
      AND version.id IN (SELECT id FROM v64_created_versions)
      AND version.usage_dimension IN ('VIDEO_SECOND', 'IMAGE')
      AND (version.tokens_per_minute <= 0 OR version.tokens_per_minute > 86400);

    IF offending > 0 THEN
        RAISE EXCEPTION 'published_seconds_plan_sells_a_nonsense_spm_ceiling:count=%',
            offending;
    END IF;

    -- A TOKEN version's tokens_per_minute is a token rate, and a version that
    -- grants TEXT has to sell a rate a chat customer can use. 10,000 per minute
    -- is a low bar -- far below every real tier -- chosen only so that a value
    -- which is obviously not a token rate (60, 190, 600, or a NULL) cannot pass,
    -- which is the shape this migration had to undo.
    SELECT count(*) INTO offending
    FROM platform.package_plan_version version
    WHERE version.status = 'PUBLISHED'
      AND version.id IN (SELECT id FROM v64_created_versions)
      AND version.usage_dimension = 'TOKEN'
      AND version.tokens_per_minute < 10000
      AND EXISTS (
            SELECT 1 FROM platform.package_plan_version_model model
            WHERE model.package_plan_version_id = version.id
              AND model.capabilities @> '["TEXT"]'::jsonb);

    IF offending > 0 THEN
        RAISE EXCEPTION 'published_text_plan_below_a_usable_token_rate:count=%',
            offending;
    END IF;

    -- No published version may sell a zero or negative limit anywhere, in either
    -- dimension. A seconds ceiling of 60 is a limit like any other.
    PERFORM 1 FROM platform.package_plan_version
    WHERE status = 'PUBLISHED'
      AND id IN (SELECT id FROM v64_created_versions)
      AND (tokens_per_minute <= 0 OR requests_per_minute <= 0 OR max_api_keys <= 0
           OR max_concurrency <= 0 OR duration_days <= 0 OR total_token_quota <= 0
           OR max_file_size_bytes <= 0
           OR daily_request_limit <= 0 OR max_output_tokens <= 0);
    IF FOUND THEN
        RAISE EXCEPTION 'published_plan_sells_a_non_positive_limit';
    END IF;
END $$;

DO $$
DECLARE
    plan     record;
    worst    bigint;
    unknown  integer;
    floor_   numeric := 0.30000;
    warning_ numeric := 0.40000;
    margin   numeric;
BEGIN
    FOR plan IN
        SELECT version.id, plan_row.code, version.price_amount,
               version.total_token_quota, version.usage_dimension,
               version.estimated_cost_amount, version.estimated_margin_ratio,
               version.margin_warning
        FROM platform.package_plan_version version
        JOIN platform.package_plan plan_row ON plan_row.id = version.package_plan_id
        WHERE version.status = 'PUBLISHED' AND version.price_amount > 0
          AND version.id IN (SELECT id FROM v64_created_versions)
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
        -- the same number this assertion just computed -- including for the three
        -- versions whose estimate was copied rather than recomputed above.
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
END $$;

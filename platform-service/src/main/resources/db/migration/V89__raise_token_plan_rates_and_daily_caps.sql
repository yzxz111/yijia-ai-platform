-- Rebased from the old deploy branch's V70 after reconciling with the live
-- platform Flyway lineage. Live V70-V87 are already applied and immutable.
-- Raise The Per-Minute Rate Sold By The Three Token Plans
--
-- WHY. The gateway enforces the per-minute credit ceiling per package instance
-- (`gateway.package_instance.tokens_per_minute`, counted in Redis by
-- `RedisPackageTokenRateLimitStore`), and V63 sold STARTER 100,000 credits per minute.
-- An agentic client that re-sends its whole context on every tool call spends about
-- 28,750 tokens per call, so four calls settle 115,000 credits and the fifth call in
-- the same clock minute is refused with `429 tokens_per_minute_exceeded`
-- ("Credits per minute exceeded"). The limiter was behaving exactly as sold: the tier
-- was sized for chat, not for tool-calling. 500,000 admits 17 such calls per minute,
-- and still 15 if the per-call context grows to 33,000.
--
-- The daily request caps move with it. At 17 calls per minute STARTER's 2,000-request
-- day is gone in about two hours, so without this the customer meets the same wall one
-- limit later.
--
--   plan          tokens_per_minute          daily_request_limit
--   STARTER       100,000  -> 500,000        2,000   -> 5,000
--   ADVANCED        500,000 (unchanged)     10,000  -> 25,000
--   ENTERPRISE    2,000,000 (unchanged)     50,000 -> 100,000
--
-- STATE-DRIVEN, NOT ID-DRIVEN. The published catalogue is not always the one V61 and V63
-- built. A deployment can have its catalogue restored from a cloud snapshot
-- (`scripts/restore-catalog-snapshot.py`), which publishes rows with different ids,
-- different version numbers and different prices -- production does today: STARTER v2,
-- ADVANCED v2 and ENTERPRISE v3, all selling 100,000 per minute, while the rows V63
-- wrote are absent entirely. Naming those rows would retire nothing there and then fail
-- on `uq_published_package_plan_version`, which admits one PUBLISHED row per plan. So
-- this migration reads whichever version is PUBLISHED for each plan and replaces THAT
-- one, whatever its id or number.
--
-- NEVER LOWER, NEVER INVENT A CAP. tokens_per_minute takes GREATEST against what the
-- plan already sold, and daily_request_limit stays NULL when it is NULL. A plan that
-- sold no daily cap must not acquire one, and on the snapshot catalogue above all three
-- plans sell NULL: writing the local lineage's 5,000 there would ADD a ceiling to a plan
-- that never had one, which is the opposite of what this migration is for. The same
-- GREATEST rule is what makes this file safe to re-run against a catalogue that an
-- operator has already raised by hand.
--
-- WHAT DOES NOT MOVE, AND WHY THE MARGIN IS COPIED RATHER THAN RECOMPUTED. Price,
-- quota, duration, keys, request rate, concurrency, output cap, file size, inventory,
-- the model sets and the stored margin estimate are all copied from the version being
-- replaced, so none of them can drift with a re-typed literal. Copying the margin is
-- not a shortcut: the guard reads only the price and the quota against the cost basis,
-- so neither a per-minute rate nor a daily request cap is an input to it. The assertion
-- at the end of each iteration proves that by comparing every copied column on the
-- replacement with the row it replaced -- a repricing smuggled into this migration would
-- fail rather than publish.
--
-- A rate is not a quota. Raising it lets a customer burn the quota they already paid
-- for faster; it cannot change the margin, because the quota and the price are the same
-- numbers as before.
--
-- ORDER MATTERS and is not the obvious one, exactly as in V61 and V63: the version being
-- replaced is retired first, the replacement is inserted as DRAFT, its model permissions
-- are copied, and only then is it published, because
-- `platform.reject_published_model_mutation` refuses to insert a permission row while
-- its parent version is PUBLISHED or RETIRED, and `uq_published_package_plan_version`
-- admits one PUBLISHED version per plan. Published rows are immutable
-- (`platform.reject_published_version_mutation`) and are NOT patched here: the only
-- change the trigger permits is status -> RETIRED.

-- ---------------------------------------------------------------------------
-- 1. Replace the published version of each token plan
--
-- One loop rather than three set-based statements, because every step needs the row it
-- is replacing: the new version number, the copy source, the permission source and the
-- comparison the assertion makes. The replacement ids are fixed rather than generated so
-- that a migration test can name the rows this file writes.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    wanted               record;
    previous             platform.package_plan_version%ROWTYPE;
    replacement_version  integer;
    permissions_copied   integer;
    mismatched_columns   text;
BEGIN
    FOR wanted IN
        SELECT * FROM (VALUES
            ('STARTER'::text,    500000::bigint,   5000::integer,
             '00000000-0000-4000-8000-000000000132'::uuid),
            ('ADVANCED'::text,   500000::bigint,  25000::integer,
             '00000000-0000-4000-8000-000000000133'::uuid),
            ('ENTERPRISE'::text, 2000000::bigint, 100000::integer,
             '00000000-0000-4000-8000-000000000234'::uuid)
        ) AS plan_targets(plan_code, tokens_per_minute, daily_request_limit, replacement_id)
    LOOP
        SELECT version.*
        INTO previous
        FROM platform.package_plan_version version
        JOIN platform.package_plan plan ON plan.id = version.package_plan_id
        WHERE plan.code = wanted.plan_code
          AND version.status = 'PUBLISHED';

        IF previous.id IS NULL THEN
            RAISE EXCEPTION 'token_plan_has_no_published_version:%', wanted.plan_code;
        END IF;

        SELECT coalesce(max(version.version), 0) + 1
        INTO replacement_version
        FROM platform.package_plan_version version
        WHERE version.package_plan_id = previous.package_plan_id;

        -- Retire before inserting: one PUBLISHED row per plan, and RETIRED is the only
        -- edit the immutability trigger allows on a published row.
        UPDATE platform.package_plan_version
        SET status = 'RETIRED', retired_at = now()
        WHERE id = previous.id;

        INSERT INTO platform.package_plan_version(
            id, package_plan_id, version, price_amount, currency, total_token_quota,
            duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
            max_concurrency, target_margin_ratio, status, published_at, retired_at,
            daily_request_limit, max_output_tokens, max_file_size_bytes, once_per_user,
            estimated_cost_amount, estimated_margin_ratio, margin_warning, stock_total,
            low_stock_threshold, max_purchases_per_user, usage_dimension)
        VALUES (
            wanted.replacement_id, previous.package_plan_id, replacement_version,
            previous.price_amount, previous.currency, previous.total_token_quota,
            previous.duration_days, previous.max_api_keys, previous.requests_per_minute,
            GREATEST(previous.tokens_per_minute, wanted.tokens_per_minute),
            previous.max_concurrency, previous.target_margin_ratio, 'DRAFT', NULL, NULL,
            CASE
                WHEN previous.daily_request_limit IS NULL THEN NULL
                ELSE GREATEST(previous.daily_request_limit, wanted.daily_request_limit)
            END,
            previous.max_output_tokens, previous.max_file_size_bytes,
            previous.once_per_user, previous.estimated_cost_amount,
            previous.estimated_margin_ratio, previous.margin_warning,
            previous.stock_total, previous.low_stock_threshold,
            previous.max_purchases_per_user, previous.usage_dimension);

        -- Copied while the replacement is still a DRAFT: the model-permission trigger
        -- refuses to insert under a PUBLISHED or RETIRED parent.
        INSERT INTO platform.package_plan_version_model(
            id, package_plan_version_id, model_code, model_token_quota, capabilities)
        SELECT gen_random_uuid(), wanted.replacement_id, model.model_code,
               model.model_token_quota, model.capabilities
        FROM platform.package_plan_version_model model
        WHERE model.package_plan_version_id = previous.id;

        GET DIAGNOSTICS permissions_copied = ROW_COUNT;
        IF permissions_copied = 0 THEN
            RAISE EXCEPTION 'replacement_has_no_model_permissions:%', wanted.plan_code;
        END IF;

        UPDATE platform.package_plan_version
        SET status = 'PUBLISHED', published_at = now()
        WHERE id = wanted.replacement_id
          AND status = 'DRAFT';

        -- The copy is the point of this migration, so it is asserted rather than
        -- trusted: every column that is not a limit is compared against the row it
        -- replaced, and the two limit columns must have moved exactly as intended.
        --
        -- WHY THIS IS A LIST AND NOT ONE BIG `PERFORM 1 ... AND ...`. It used to be a
        -- single conjunction, and that made a failure useless: on 2026-10-12 a fresh
        -- chain hit `replacement_does_not_copy_the_row_it_replaced:STARTER` with
        -- twenty-six columns ANDed together and nothing to say which one disagreed.
        -- Whoever picks this up next gets the column name, both values and the row
        -- identity, because a bare "something is wrong" costs another full
        -- investigation. The check is otherwise identical: same columns, same rules.
        SELECT string_agg(column_name || ' expected=' || coalesce(expected, '<null>')
                          || ' actual=' || coalesce(actual, '<null>'), ', ' ORDER BY column_name)
        INTO mismatched_columns
        FROM platform.package_plan_version published
        CROSS JOIN LATERAL (
            SELECT 'version' AS column_name,
                   replacement_version::text AS expected, published.version::text AS actual
            UNION ALL SELECT 'package_plan_id',
                   previous.package_plan_id::text, published.package_plan_id::text
            UNION ALL SELECT 'usage_dimension',
                   previous.usage_dimension, published.usage_dimension
            UNION ALL SELECT 'tokens_per_minute',
                   GREATEST(previous.tokens_per_minute, wanted.tokens_per_minute)::text,
                   published.tokens_per_minute::text
            UNION ALL SELECT 'daily_request_limit',
                   CASE
                       WHEN previous.daily_request_limit IS NULL THEN NULL
                       ELSE GREATEST(previous.daily_request_limit, wanted.daily_request_limit)::text
                   END,
                   published.daily_request_limit::text
            UNION ALL SELECT 'price_amount',
                   previous.price_amount::text, published.price_amount::text
            UNION ALL SELECT 'currency', previous.currency, published.currency
            UNION ALL SELECT 'total_token_quota',
                   previous.total_token_quota::text, published.total_token_quota::text
            UNION ALL SELECT 'duration_days',
                   previous.duration_days::text, published.duration_days::text
            UNION ALL SELECT 'max_api_keys',
                   previous.max_api_keys::text, published.max_api_keys::text
            UNION ALL SELECT 'requests_per_minute',
                   previous.requests_per_minute::text, published.requests_per_minute::text
            UNION ALL SELECT 'max_concurrency',
                   previous.max_concurrency::text, published.max_concurrency::text
            UNION ALL SELECT 'max_output_tokens',
                   previous.max_output_tokens::text, published.max_output_tokens::text
            UNION ALL SELECT 'max_file_size_bytes',
                   previous.max_file_size_bytes::text, published.max_file_size_bytes::text
            UNION ALL SELECT 'once_per_user',
                   previous.once_per_user::text, published.once_per_user::text
            UNION ALL SELECT 'target_margin_ratio',
                   previous.target_margin_ratio::text, published.target_margin_ratio::text
            UNION ALL SELECT 'estimated_cost_amount',
                   previous.estimated_cost_amount::text, published.estimated_cost_amount::text
            UNION ALL SELECT 'estimated_margin_ratio',
                   previous.estimated_margin_ratio::text,
                   published.estimated_margin_ratio::text
            UNION ALL SELECT 'margin_warning',
                   previous.margin_warning::text, published.margin_warning::text
            UNION ALL SELECT 'stock_total',
                   previous.stock_total::text, published.stock_total::text
            UNION ALL SELECT 'low_stock_threshold',
                   previous.low_stock_threshold::text, published.low_stock_threshold::text
            UNION ALL SELECT 'max_purchases_per_user',
                   previous.max_purchases_per_user::text,
                   published.max_purchases_per_user::text
        ) comparison
        WHERE published.id = wanted.replacement_id
          AND comparison.expected IS DISTINCT FROM comparison.actual;

        -- The replacement row itself must exist and be PUBLISHED; a missing row would
        -- make every comparison above vacuously empty and read as "no mismatches".
        PERFORM 1
        FROM platform.package_plan_version published
        WHERE published.id = wanted.replacement_id
          AND published.status = 'PUBLISHED';

        IF NOT FOUND THEN
            RAISE EXCEPTION 'replacement_row_is_missing_or_not_published:%:id=%',
                wanted.plan_code, wanted.replacement_id;
        END IF;

        IF mismatched_columns IS NOT NULL THEN
            RAISE EXCEPTION 'replacement_does_not_copy_the_row_it_replaced:%:replaced_version=%:columns=[%]',
                wanted.plan_code, previous.version, mismatched_columns;
        END IF;
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 2. Assert what this migration is responsible for
--
-- SCOPED TO THE THREE PLANS ON PURPOSE, and NOT the catalogue-wide sweep V63 and V64
-- close with. Those migrations were establishing a catalogue they owned, so a
-- catalogue-wide claim was theirs to make. This one amends a catalogue it did not build,
-- and that catalogue can have been restored from a cloud snapshot that bypassed the
-- publication guards entirely: production today publishes FREE at 5,000 tokens per
-- minute, below the 10,000 floor `PackageRateCeilingPolicy` imposes on a TEXT-capable
-- version, because the snapshot was written straight into the tables. Asserting the
-- floor catalogue-wide here would fail the deploy on state this file did not create and
-- cannot fix, and a migration that refuses to run on a legitimate pre-existing catalogue
-- is worse than one that checks only its own work. The claims below are the ones that
-- would be false if THIS migration were wrong: the rate it just published, the dimension
-- it wrote it on, and the shape of the three rows it touched.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    replaced_plans  integer;
    wrong_dimension integer;
    below_floor     integer;
    non_positive    integer;
BEGIN
    SELECT count(*) INTO replaced_plans
    FROM platform.package_plan plan
    JOIN platform.package_plan_version version ON version.package_plan_id = plan.id
    WHERE plan.code IN ('STARTER', 'ADVANCED', 'ENTERPRISE')
      AND version.status = 'PUBLISHED';

    IF replaced_plans <> 3 THEN
        RAISE EXCEPTION 'token_plan_published_version_count_is_wrong:count=%', replaced_plans;
    END IF;

    SELECT count(*) INTO wrong_dimension
    FROM platform.package_plan plan
    JOIN platform.package_plan_version version ON version.package_plan_id = plan.id
    WHERE plan.code IN ('STARTER', 'ADVANCED', 'ENTERPRISE')
      AND version.status = 'PUBLISHED'
      AND version.usage_dimension <> 'TOKEN';

    IF wrong_dimension > 0 THEN
        RAISE EXCEPTION 'token_plan_published_version_is_not_token_dimension:count=%',
            wrong_dimension;
    END IF;

    -- The rate this file sells must clear the floor a TEXT-capable token version is
    -- published under, or the plan it just republished would be refused by
    -- `PackageRateCeilingPolicy` the next time an operator tried to publish it.
    SELECT count(*) INTO below_floor
    FROM platform.package_plan plan
    JOIN platform.package_plan_version version ON version.package_plan_id = plan.id
    WHERE plan.code IN ('STARTER', 'ADVANCED', 'ENTERPRISE')
      AND version.status = 'PUBLISHED'
      AND version.tokens_per_minute < 10000;

    IF below_floor > 0 THEN
        RAISE EXCEPTION 'republished_token_plan_is_below_the_ceiling_floor:count=%', below_floor;
    END IF;

    SELECT count(*) INTO non_positive
    FROM platform.package_plan plan
    JOIN platform.package_plan_version version ON version.package_plan_id = plan.id
    WHERE plan.code IN ('STARTER', 'ADVANCED', 'ENTERPRISE')
      AND version.status = 'PUBLISHED'
      AND (tokens_per_minute <= 0 OR requests_per_minute <= 0 OR max_api_keys <= 0
           OR max_concurrency <= 0 OR duration_days <= 0 OR total_token_quota <= 0
           OR daily_request_limit <= 0 OR max_output_tokens <= 0);

    IF non_positive > 0 THEN
        RAISE EXCEPTION 'republished_token_plan_has_non_positive_limit:count=%', non_positive;
    END IF;
END $$;

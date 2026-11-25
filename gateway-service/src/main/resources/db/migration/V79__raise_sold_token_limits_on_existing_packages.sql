-- Raise The Per-Minute Rate And Daily Cap On Token Packages Already Sold
--
-- WHY THIS IS A SEPARATE MIGRATION FROM platform V70. V70 raises what the three token
-- plans SELL. A package instance is a snapshot, not a view: PackageActivationService
-- writes gateway.package_instance.tokens_per_minute and daily_request_limit once, at
-- activation, from the PACKAGE_GRANTED_V1 payload, and nothing in the gateway ever
-- updates them again (there is no UPDATE of either column anywhere in the service).
-- Raising the catalogue therefore reaches nobody who already bought: an existing
-- STARTER instance keeps 100,000 credits per minute and its 2,000-request day, and its
-- owner keeps meeting `429 tokens_per_minute_exceeded` after four tool calls, until
-- this migration moves them.
--
--   plan          tokens_per_minute          daily_request_limit
--   STARTER       100,000  -> 500,000        2,000   -> 5,000
--   ADVANCED        500,000 (unchanged)     10,000  -> 25,000
--   ENTERPRISE    2,000,000 (unchanged)     50,000 -> 100,000
--
-- THE DIMENSION FILTER IS THE SAFETY ARGUMENT. package_instance.tokens_per_minute
-- carries the unit of the instance's usage_dimension: credits per minute on a TOKEN
-- instance, SECONDS per minute on a VIDEO_SECOND or IMAGE one. PackageRateLimits reads
-- any VIDEO_SECOND value >= 10,000 as a token rate, so writing 500,000 onto a video
-- instance would not lift a ceiling, it would remove one -- the seconds-per-minute
-- ceiling would silently stop being enforced. That is the V63 defect V64 had to undo.
-- The three package codes below are token plans, so no video instance shares them, but
-- the filter stays as defence in depth: the unit hazard is the one thing a future edit
-- to this file is most likely to get wrong.
--
-- NULL daily_request_limit means the plan sold no daily cap at all, and it must stay
-- NULL rather than become an invented 5,000. GREATEST everywhere, so a value an
-- operator deliberately set above the new one is never lowered.

-- Only rows that actually change are written, so an instance already at or above the
-- new limits (including one an operator raised by hand) keeps its exact values and its
-- updated_at. QUEUED is included because a queued package has not started yet and will
-- activate with whatever this snapshot holds.
UPDATE gateway.package_instance instance
SET tokens_per_minute = GREATEST(instance.tokens_per_minute, wanted.tokens_per_minute),
    daily_request_limit = CASE
        WHEN instance.daily_request_limit IS NULL THEN NULL
        ELSE GREATEST(instance.daily_request_limit, wanted.daily_request_limit)
    END,
    updated_at = now()
FROM (VALUES
        ('STARTER',     500000::bigint,   5000::integer),
        ('ADVANCED',    500000::bigint,  25000::integer),
        ('ENTERPRISE', 2000000::bigint, 100000::integer)
     ) AS wanted(package_code, tokens_per_minute, daily_request_limit)
WHERE instance.package_code = wanted.package_code
  AND instance.usage_dimension = 'TOKEN'
  AND instance.status IN ('QUEUED', 'ACTIVE')
  AND (instance.tokens_per_minute < wanted.tokens_per_minute
       OR instance.daily_request_limit < wanted.daily_request_limit);

-- ---------------------------------------------------------------------------
-- Assert that no live token package is still below the limits this migration sells
--
-- Scoped to QUEUED and ACTIVE, which is the set the statement above touches. An
-- EXPIRED, EXHAUSTED or REVOKED instance keeps its historical snapshot on purpose: it
-- cannot serve a request, and rewriting what a closed package was sold would falsify
-- the record of what its owner bought.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    below  integer;
    latent integer;
BEGIN
    SELECT count(*) INTO below
    FROM gateway.package_instance instance
    JOIN (VALUES
            ('STARTER',     500000::bigint,   5000::integer),
            ('ADVANCED',    500000::bigint,  25000::integer),
            ('ENTERPRISE', 2000000::bigint, 100000::integer)
         ) AS wanted(package_code, tokens_per_minute, daily_request_limit)
      ON instance.package_code = wanted.package_code
    WHERE instance.usage_dimension = 'TOKEN'
      AND instance.status IN ('QUEUED', 'ACTIVE')
      AND (instance.tokens_per_minute < wanted.tokens_per_minute
           OR (instance.daily_request_limit IS NOT NULL
               AND instance.daily_request_limit < wanted.daily_request_limit));

    IF below > 0 THEN
        RAISE EXCEPTION 'token_package_instance_still_below_the_new_limits:count=%', below;
    END IF;

    -- Reported, not repaired. A VIDEO_SECOND instance whose tokens_per_minute is at or
    -- above the token-rate floor is one activated while V63 sold the token tiers'
    -- 100,000 / 300,000 / 500,000 in the seconds column: its chat is capped as if the
    -- number were a token rate, and its seconds-per-minute ceiling is not enforced at
    -- all. Repairing it means writing the plan's real SPM (60 / 190 / 600) onto a
    -- customer's video entitlement, which IMPOSES a ceiling that customer is not
    -- currently subject to. That is a product decision, not a data repair, so this
    -- migration counts it and leaves it alone.
    SELECT count(*) INTO latent
    FROM gateway.package_instance
    WHERE usage_dimension IN ('VIDEO_SECOND', 'IMAGE')
      AND tokens_per_minute >= 10000;

    IF latent > 0 THEN
        RAISE NOTICE 'video_instance_sells_a_token_rate_shaped_value:count=%', latent;
    END IF;
END $$;

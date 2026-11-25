-- Video Second Operating Fact Dimension
--
-- Gives the operating-fact contract a usage dimension and a dimension-qualified unit
-- count, so a per-second video settlement can be recorded without writing seconds into a
-- token counter.
--
-- Why this exists: `operating_fact` was token-shaped end to end. Ingestion enforced
-- `total_tokens = input_tokens + output_tokens` and
-- `package_settled_tokens <= total_tokens`, so an honest zero-token video fact could not
-- carry a settled second count, and the only shape that reached
-- `OperatingAlertService.evaluatePackageMargin` wrote the seconds into `output_tokens` —
-- a value the token reports then summed as if it were tokens. See
-- docs/video-economics-evidence-gap.md.
--
-- Convention (same as gateway V42/V49 and platform V54): a quantity column is
-- dimension-carrying and `usage_dimension` says which unit it holds —
--   TOKEN          tokens   (usage_units = total_tokens)
--   VIDEO_SECOND   seconds  (token counters MUST be 0)
--   IMAGE          images   (token counters MUST be 0)
-- The invariant is per dimension, not global: whichever unit a fact is denominated in,
-- the columns of the other unit stay at zero. That is what keeps the token sums in
-- operating_daily_aggregate and the operating reports honest.
--
-- DEPLOYMENT ORDER: this migration MUST be applied (and the platform service deployed)
-- BEFORE the gateway emits USAGE_ECONOMICS_V3. Ingestion rejects an unknown event version
-- as INVALID and audits it (OperatingFactIngestionService.validate) — fail-closed. The
-- reverse order would make every dimensioned video event a rejected, audited dead letter
-- until the platform caught up, whereas an OLD platform receiving the new version fails
-- closed rather than reading seconds as tokens. Never reorder these two deploys.

SET LOCAL search_path = platform, public;

-- ---------------------------------------------------------------------------
-- 1. operating_fact: the dimension of a fact and its qualified unit count
-- ---------------------------------------------------------------------------

ALTER TABLE platform.operating_fact
    ADD COLUMN usage_dimension varchar(20) NOT NULL DEFAULT 'TOKEN',
    ADD COLUMN usage_units bigint NOT NULL DEFAULT 0;

-- Rows written before this migration are token facts (the only dimension the contract
-- admitted), so their unit count is their token total. Backfill before the invariant is
-- enforced, or the constraint would reject every historical row.
--
-- The backfill UPDATEs a table guarded by `trg_operating_fact_append_only`, whose function
-- raises unconditionally on any UPDATE. That is the intended runtime contract, but it also
-- rejects this one-time historical backfill. The trigger is therefore disabled for the
-- duration of the statement and re-enabled immediately after — same pattern as V9's
-- `trg_package_version_model_immutable` bypass. ALTER TABLE ... DISABLE TRIGGER takes an
-- exclusive lock, so no concurrent writer can slip an unbackfilled row past the window.
-- The invariant still holds at the end of the migration: usage_units = total_tokens for
-- every pre-existing row, which is exactly what ck_operating_fact_usage_units then enforces.
ALTER TABLE platform.operating_fact
    DISABLE TRIGGER trg_operating_fact_append_only;

UPDATE platform.operating_fact
SET usage_units = total_tokens
WHERE usage_units <> total_tokens;

ALTER TABLE platform.operating_fact
    ENABLE TRIGGER trg_operating_fact_append_only;

ALTER TABLE platform.operating_fact
    ADD CONSTRAINT ck_operating_fact_usage_dimension CHECK (
        usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE')),
    ADD CONSTRAINT ck_operating_fact_usage_units CHECK (
        usage_units >= 0
        AND ((usage_dimension = 'TOKEN'
                AND usage_units = total_tokens)
            OR (usage_dimension <> 'TOKEN'
                AND input_tokens = 0
                AND output_tokens = 0
                AND total_tokens = 0)));

COMMENT ON COLUMN platform.operating_fact.usage_dimension IS
    'Unit of usage_units: TOKEN (tokens), VIDEO_SECOND (seconds) or IMAGE (images). '
    'A TOKEN fact keeps the token invariant (usage_units = total_tokens); every other '
    'dimension keeps all three token counters at zero.';
COMMENT ON COLUMN platform.operating_fact.usage_units IS
    'Quantity consumed in usage_dimension, and the basis of the package revenue '
    'allocation (price * package_settled_units / package quota in the same unit).';

-- Non-token facts are a small minority (video, image) and are queried as their own
-- reporting slice; a partial index keeps that read off the token reporting path.
CREATE INDEX ix_operating_fact_usage_dimension
    ON platform.operating_fact(usage_dimension, recognized_at DESC)
    WHERE usage_dimension <> 'TOKEN';

-- ---------------------------------------------------------------------------
-- 2. operating_daily_aggregate: one row per date AND dimension
-- ---------------------------------------------------------------------------

-- The dimension joins the unique key: two facts of the same date, model and currency that
-- are denominated in different units are different aggregate rows, not one row whose
-- units are added together.
ALTER TABLE platform.operating_daily_aggregate
    DROP CONSTRAINT uq_operating_daily_dimensions,
    ADD COLUMN usage_dimension varchar(20) NOT NULL DEFAULT 'TOKEN',
    ADD COLUMN usage_units bigint NOT NULL DEFAULT 0;

UPDATE platform.operating_daily_aggregate
SET usage_units = total_tokens
WHERE usage_units <> total_tokens;

ALTER TABLE platform.operating_daily_aggregate
    ADD CONSTRAINT uq_operating_daily_dimensions UNIQUE NULLS NOT DISTINCT (
        business_date, timezone, tenant_id, user_id, package_plan_version_id,
        logical_model, upstream_model, channel, provider, billing_mode, currency,
        usage_dimension),
    ADD CONSTRAINT ck_operating_daily_usage_dimension CHECK (
        usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE')),
    ADD CONSTRAINT ck_operating_daily_usage_units CHECK (
        usage_units >= 0
        AND ((usage_dimension = 'TOKEN'
                AND usage_units = total_tokens)
            OR (usage_dimension <> 'TOKEN'
                AND input_tokens = 0
                AND output_tokens = 0
                AND total_tokens = 0)));

COMMENT ON COLUMN platform.operating_daily_aggregate.usage_dimension IS
    'Aggregation dimension: a separate daily row exists per usage_dimension so seconds '
    'are never summed into the token metrics.';
COMMENT ON COLUMN platform.operating_daily_aggregate.usage_units IS
    'Daily quantity in usage_dimension. For TOKEN rows this equals total_tokens; for a '
    'VIDEO_SECOND row it is the settled seconds and total_tokens stays 0.';

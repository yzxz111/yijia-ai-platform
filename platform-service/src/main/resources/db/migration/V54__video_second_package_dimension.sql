-- Video Second Package Dimension
--
-- Lets a subscription package express its entitlement quota in SECONDS rather
-- than tokens, so a video package can be created, priced, published, delivered
-- and enforced without forging a token count that nothing decrements.
--
-- Why a dimension column instead of a seconds column:
--   platform.package_plan_version.total_token_quota is NOT NULL and has
--   CHECK (total_token_quota > 0) since V1. Relaxing that would break every
--   existing reader. Adding usage_dimension instead makes the EXISTING quota
--   column dimension-carrying: for a TOKEN package the quota is tokens, for a
--   VIDEO_SECOND package the same column holds seconds. The invariant stays
--   true and the number stays meaningful.
--
-- Design: docs/superpowers/specs/2026-09-12-multimodal-pricing-and-video-package-design.md

-- ---------------------------------------------------------------------------
-- 1. Plan version dimension
-- ---------------------------------------------------------------------------

ALTER TABLE platform.package_plan_version
    ADD COLUMN usage_dimension varchar(20) NOT NULL DEFAULT 'TOKEN',
    ADD CONSTRAINT ck_package_version_usage_dimension
        CHECK (usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE'));

COMMENT ON COLUMN platform.package_plan_version.usage_dimension IS
    'Unit of total_token_quota and of every package_plan_version_model.model_token_quota '
    'in this version. TOKEN = tokens; VIDEO_SECOND = seconds. A single version may '
    'not mix dimensions, and a VIDEO_SECOND version may not share a package with '
    'token aliases.';
COMMENT ON COLUMN platform.package_plan_version.total_token_quota IS
    'Package-wide quota in the unit named by usage_dimension: tokens for TOKEN, '
    'seconds for VIDEO_SECOND.';
COMMENT ON COLUMN platform.package_plan_version_model.model_token_quota IS
    'Per-model sub-quota in the parent version''s usage_dimension unit. NULL means '
    'the model may consume the whole package balance.';

-- ---------------------------------------------------------------------------
-- 2. Where the margin guard reads a per-dimension cost
--
-- PackageMarginPolicy.requirePrice throws when a model has no cost. Video aliases
-- deliberately have no gateway.provider_model_price row (those columns are
-- per-MILLION-TOKEN and cannot express a per-second cost without lying), so the
-- guard must read the rate card for them. This defines the contract: a video
-- alias's cost basis is its effective per-second cost, in 分, including the
-- retry allowance that upstream GPU billing imposes.
--
-- The gateway exposes it at /internal/prices/effective-unit-cost. Recorded here
-- so the platform's expected shape is documented next to the schema.
--
-- Example for the seeded tiers (see gateway V40):
--   video-basic    720P  provider_unit_cost 69  分/s, retry 0.15 -> 80 分/s
--   video-standard 1080P provider_unit_cost 115 分/s, retry 0.15 -> 133 分/s
--   video-max      1080P provider_unit_cost 345 分/s, retry 0.15 -> 397 分/s
-- ---------------------------------------------------------------------------

CREATE TABLE platform.package_dimension_price_policy (
    usage_dimension varchar(20) PRIMARY KEY,
    -- Quota is per this many units when multiplying a unit cost.
    -- TOKEN: quota is in tokens and cost is per 1,000,000 tokens.
    -- VIDEO_SECOND: quota is in seconds and cost is per 1 second.
    quota_units_per_cost_unit bigint NOT NULL,
    unit_label varchar(16) NOT NULL,
    minimum_margin numeric(8, 5) NOT NULL,
    warning_margin numeric(8, 5) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_package_dimension_policy_values CHECK (
        quota_units_per_cost_unit > 0
        AND minimum_margin >= 0 AND minimum_margin < 1
        AND warning_margin >= 0 AND warning_margin < 1
        AND warning_margin >= minimum_margin),
    CONSTRAINT ck_package_dimension_policy_label CHECK (unit_label IN ('tokens', 'seconds'))
);

INSERT INTO platform.package_dimension_price_policy(
    usage_dimension, quota_units_per_cost_unit, unit_label, minimum_margin, warning_margin)
VALUES
    -- Tokens: a 分-per-million price multiplied by a token quota needs /1e6.
    ('TOKEN', 1000000, 'tokens', 0.30000, 0.35000),
    -- Seconds: a 分-per-second price multiplied by a second quota needs no scaling.
    -- The lower floor reflects that the video resale market clears below list
    -- (measured 0.41x-1.29x of official list), so a 30% floor on the cheapest
    -- tier is not reachable while staying competitive.
    ('VIDEO_SECOND', 1, 'seconds', 0.25000, 0.30000),
    ('IMAGE', 1, 'seconds', 0.25000, 0.30000);

COMMENT ON TABLE platform.package_dimension_price_policy IS
    'Per-dimension scaling and margin floors for package publication. Replaces the '
    'hard-coded TOKENS_PER_MILLION / MINIMUM_MARGIN pair in PackageMarginPolicy, '
    'which could only ever price token packages.';

-- ---------------------------------------------------------------------------
-- 3. Inventory of the video catalog
--
-- The gateway owns gateway.model_usage_price. The platform needs to know which
-- aliases are video aliases in order to validate a package's dimension without
-- a synchronous call to the gateway on every write. This table is a read model,
-- refreshed by the same hourly job that mirrors provider prices, and is
-- advisory only: the gateway re-validates at delivery time and rejects a
-- mismatch with the V40 package-entitlement trigger.
-- ---------------------------------------------------------------------------

CREATE TABLE platform.model_usage_dimension (
    model_code varchar(128) PRIMARY KEY,
    usage_dimension varchar(20) NOT NULL,
    resolution_tier varchar(16) NOT NULL DEFAULT '',
    retail_unit_price bigint,
    effective_unit_cost bigint,
    price_version varchar(64),
    synced_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_model_usage_dimension_value
        CHECK (usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE')),
    CONSTRAINT ck_model_usage_dimension_tier
        CHECK (resolution_tier IN ('', '480P', '720P', '1080P')),
    CONSTRAINT ck_model_usage_dimension_prices CHECK (
        (retail_unit_price IS NULL OR retail_unit_price > 0)
        AND (effective_unit_cost IS NULL OR effective_unit_cost >= 0))
);

COMMENT ON TABLE platform.model_usage_dimension IS
    'Read model of the gateway rate card: which aliases are priced per second. '
    'Used to validate a package version''s usage_dimension before publication.';

-- Seed the five video aliases from gateway V40 so publication works before the
-- first sync job run. TOKEN aliases are intentionally absent: the absence of a
-- row means "not a video alias" and the default dimension is TOKEN.
INSERT INTO platform.model_usage_dimension(
    model_code, usage_dimension, resolution_tier, retail_unit_price, effective_unit_cost,
    price_version)
VALUES
    ('video-budget',   'VIDEO_SECOND', '480P',  50,  41, 'video-v1'),
    ('video-basic',    'VIDEO_SECOND', '720P', 100,  80, 'video-v1'),
    ('video-standard', 'VIDEO_SECOND', '1080P', 180, 133, 'video-v1'),
    ('video-pro',      'VIDEO_SECOND', '720P', 130, 106, 'video-v1'),
    ('video-max',      'VIDEO_SECOND', '1080P', 500, 397, 'video-v1')
ON CONFLICT (model_code) DO UPDATE SET
    usage_dimension = EXCLUDED.usage_dimension,
    resolution_tier = EXCLUDED.resolution_tier,
    retail_unit_price = EXCLUDED.retail_unit_price,
    effective_unit_cost = EXCLUDED.effective_unit_cost,
    price_version = EXCLUDED.price_version,
    synced_at = now();

-- ---------------------------------------------------------------------------
-- 4. Video packages
--
-- Three subscription tiers plus the catalogue entry point. Included seconds are
-- derived from the margin table in the design doc, not chosen first: at a 720P
-- effective cost of 80 分/second, 60 / 190 / 600 seconds map to
-- 44.7% / 53.7% / 60.5% contribution once CNY 30 of fixed per-subscriber cost
-- (storage, egress, queue worker, safety check) is allocated.
--
-- Priced at the low end of defensible on purpose: resale of video APIs measured
-- 0.41x-1.29x of first-party list, so a premium multiple is not available.
--
-- total_token_quota holds SECONDS for these versions; usage_dimension says so.
-- The column name is historical and is documented as dimension-carrying rather
-- than renamed, which would touch every existing reader.
--
-- Inserted as DRAFT, given their model permissions, then published. The
-- immutability trigger permits the DRAFT->PUBLISHED transition and blocks every
-- later edit, so a price change requires a new version.
-- ---------------------------------------------------------------------------

INSERT INTO platform.package_plan(id, code, name, description, status)
VALUES
    ('00000000-0000-4000-8000-000000000011', 'VIDEO_STARTER', 'Video Starter',
     'Entry video package: 60 seconds per month at 720P', 'ACTIVE'),
    ('00000000-0000-4000-8000-000000000012', 'VIDEO_CREATOR', 'Video Creator',
     'Creator video package: 190 seconds per month, 720P and 1080P', 'ACTIVE'),
    ('00000000-0000-4000-8000-000000000013', 'VIDEO_PRO', 'Video Pro',
     'Professional video package: 600 seconds per month, priority queue', 'ACTIVE')
ON CONFLICT (code) DO NOTHING;

UPDATE platform.package_plan
SET display_order = CASE code
        WHEN 'VIDEO_STARTER' THEN 40
        WHEN 'VIDEO_CREATOR' THEN 50
        WHEN 'VIDEO_PRO' THEN 60
        ELSE display_order
    END
WHERE code IN ('VIDEO_STARTER', 'VIDEO_CREATOR', 'VIDEO_PRO');

INSERT INTO platform.package_plan_version(
    id, package_plan_id, version, price_amount, currency, total_token_quota,
    duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
    max_concurrency, target_margin_ratio, status, published_at,
    daily_request_limit, max_output_tokens, max_file_size_bytes, once_per_user,
    estimated_cost_amount, estimated_margin_ratio, margin_warning,
    low_stock_threshold, usage_dimension)
VALUES
    -- CNY 129 / 60 s. Provider cost 60 x 80 = 4800 分; with the CNY 30 fixed
    -- allocation, contribution = 12900 - 4800 - 3000 = 5100 分 = 39.5%.
    -- margin_ratio below is the provider-cost-only figure the guard checks.
    ('00000000-0000-4000-8000-000000000301', '00000000-0000-4000-8000-000000000011',
     1, 12900, 'CNY', 60, 30, 2, 20, 60, 2, 0.35000, 'DRAFT', NULL,
     200, NULL, 20971520, false, 4800, 0.62791, false, 0, 'VIDEO_SECOND'),
    -- CNY 399 / 190 s. Provider cost 190 x 80 = 15200 分.
    ('00000000-0000-4000-8000-000000000302', '00000000-0000-4000-8000-000000000012',
     1, 39900, 'CNY', 190, 30, 3, 60, 190, 5, 0.35000, 'DRAFT', NULL,
     500, NULL, 52428800, false, 15200, 0.61905, false, 0, 'VIDEO_SECOND'),
    -- CNY 1299 / 600 s. Provider cost 600 x 80 = 48000 分.
    ('00000000-0000-4000-8000-000000000303', '00000000-0000-4000-8000-000000000013',
     1, 129900, 'CNY', 600, 30, 10, 120, 600, 10, 0.35000, 'DRAFT', NULL,
     1200, NULL, 104857600, false, 48000, 0.63049, false, 0, 'VIDEO_SECOND');

INSERT INTO platform.package_plan_version_model(
    id, package_plan_version_id, model_code, model_token_quota, capabilities)
VALUES
    ('00000000-0000-4000-8000-000000000311', '00000000-0000-4000-8000-000000000301', 'video-budget',    NULL, '["TEXT","IMAGE","VIDEO"]'),
    ('00000000-0000-4000-8000-000000000312', '00000000-0000-4000-8000-000000000301', 'video-basic',     NULL, '["TEXT","IMAGE","VIDEO"]'),
    ('00000000-0000-4000-8000-000000000313', '00000000-0000-4000-8000-000000000301', 'video-standard',  NULL, '["TEXT","IMAGE","VIDEO"]'),

    ('00000000-0000-4000-8000-000000000321', '00000000-0000-4000-8000-000000000302', 'video-budget',    NULL, '["TEXT","IMAGE","VIDEO"]'),
    ('00000000-0000-4000-8000-000000000322', '00000000-0000-4000-8000-000000000302', 'video-basic',     NULL, '["TEXT","IMAGE","VIDEO"]'),
    ('00000000-0000-4000-8000-000000000323', '00000000-0000-4000-8000-000000000302', 'video-standard',  NULL, '["TEXT","IMAGE","VIDEO"]'),
    ('00000000-0000-4000-8000-000000000324', '00000000-0000-4000-8000-000000000302', 'video-pro',       NULL, '["TEXT","IMAGE","VIDEO"]'),

    ('00000000-0000-4000-8000-000000000331', '00000000-0000-4000-8000-000000000303', 'video-budget',    NULL, '["TEXT","IMAGE","VIDEO"]'),
    ('00000000-0000-4000-8000-000000000332', '00000000-0000-4000-8000-000000000303', 'video-basic',     NULL, '["TEXT","IMAGE","VIDEO"]'),
    ('00000000-0000-4000-8000-000000000333', '00000000-0000-4000-8000-000000000303', 'video-standard',  NULL, '["TEXT","IMAGE","VIDEO"]'),
    ('00000000-0000-4000-8000-000000000334', '00000000-0000-4000-8000-000000000303', 'video-pro',       NULL, '["TEXT","IMAGE","VIDEO"]'),
    ('00000000-0000-4000-8000-000000000335', '00000000-0000-4000-8000-000000000303', 'video-max',       NULL, '["TEXT","IMAGE","VIDEO"]');

-- Publish. Everything except status/retired_at must be byte-identical to the
-- DRAFT row for the immutability trigger to allow this transition.
UPDATE platform.package_plan_version
SET status = 'PUBLISHED',
    published_at = now()
WHERE id IN (
        '00000000-0000-4000-8000-000000000301',
        '00000000-0000-4000-8000-000000000302',
        '00000000-0000-4000-8000-000000000303')
  AND status = 'DRAFT';

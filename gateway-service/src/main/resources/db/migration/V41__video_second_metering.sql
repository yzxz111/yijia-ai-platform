-- Video Second Metering
--
-- Adds a second-dimension usage path so a video generation request can be
-- reserved, billed, settled and released without ever touching a token counter.
--
-- Unit: money columns are 分 (CNY cent); video prices are 分 per SECOND.
--
-- Prerequisite: V40__usage_dimension_rate_card.sql created
-- gateway.model_usage_price with usage_dimension IN ('TOKEN','VIDEO_SECOND').
--
-- Design: docs/superpowers/specs/2026-09-12-multimodal-pricing-and-video-package-design.md

-- ---------------------------------------------------------------------------
-- 1. Per-model quota dimension
--
-- V40 already added usage_dimension + seconds_quota to
-- package_instance_model_quota. A video alias carries its quota in
-- seconds_quota; a token alias keeps using token_quota. One row can never
-- serve both, which is what stops a video request from draining a text pool.
--
-- The table names stay as they are (reserved_tokens / used_tokens) because the
-- ledger arithmetic is dimension-agnostic; usage_dimension says which unit the
-- numbers are in. Renaming columns would touch every settlement path for no
-- behavioural gain.
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN gateway.package_instance_model_quota.usage_dimension IS
    'TOKEN | VIDEO_SECOND | IMAGE. Selects both the counter column and the rate '
    'card dimension used to price consumption against this row.';
COMMENT ON COLUMN gateway.package_instance_model_quota.seconds_quota IS
    'Quota in seconds when usage_dimension = VIDEO_SECOND; NULL otherwise.';
COMMENT ON COLUMN gateway.package_instance_model_quota.reserved_tokens IS
    'Reserved units in the row''s usage_dimension (tokens OR seconds), not always tokens.';
COMMENT ON COLUMN gateway.package_instance_model_quota.used_tokens IS
    'Consumed units in the row''s usage_dimension (tokens OR seconds), not always tokens.';

-- ---------------------------------------------------------------------------
-- 2. Video generation tasks
--
-- Video generation is asynchronous upstream: submit -> poll -> fetch result.
-- Billing must happen exactly once, at the terminal state. This table is the
-- idempotency anchor: the gateway reserves seconds at submission, then settles
-- once when the task reaches SUCCEEDED/FAILED/CANCELLED, keyed by
-- (provider_id, upstream_task_id).
-- ---------------------------------------------------------------------------

CREATE TABLE gateway.video_generation_task (
    id uuid PRIMARY KEY,
    request_id varchar(128) NOT NULL UNIQUE,
    reservation_id uuid NOT NULL REFERENCES gateway.quota_reservation(id) ON DELETE RESTRICT,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    api_key_id uuid,
    package_instance_id uuid,
    model_code varchar(128) NOT NULL,
    provider_id uuid NOT NULL REFERENCES gateway.provider(id) ON DELETE RESTRICT,
    channel_id uuid NOT NULL REFERENCES gateway.channel(id) ON DELETE RESTRICT,
    upstream_model varchar(128) NOT NULL,
    resolution_tier varchar(16) NOT NULL,
    usage_dimension varchar(20) NOT NULL DEFAULT 'VIDEO_SECOND',
    requested_seconds integer NOT NULL,
    reserved_seconds integer NOT NULL,
    billed_seconds integer,
    upstream_task_id varchar(256),
    status varchar(20) NOT NULL DEFAULT 'SUBMITTED',
    error_category varchar(64),
    submitted_at timestamptz NOT NULL DEFAULT now(),
    terminal_at timestamptz,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_video_task_upstream UNIQUE (provider_id, upstream_task_id),
    CONSTRAINT ck_video_task_dimension CHECK (usage_dimension = 'VIDEO_SECOND'),
    CONSTRAINT ck_video_task_tier CHECK (resolution_tier IN ('480P', '720P', '1080P')),
    CONSTRAINT ck_video_task_status
        CHECK (status IN ('SUBMITTED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_video_task_values CHECK (
        requested_seconds > 0
        AND reserved_seconds > 0
        AND (billed_seconds IS NULL OR (billed_seconds >= 0 AND billed_seconds <= requested_seconds))
        AND (status IN ('SUBMITTED', 'RUNNING'))
            = (terminal_at IS NULL AND billed_seconds IS NULL)),
    -- A task may only be polled until its deadline; after that recovery settles it.
    CONSTRAINT ck_video_task_expiry CHECK (expires_at > submitted_at)
);

CREATE INDEX ix_video_task_user_created
    ON gateway.video_generation_task (user_id, created_at DESC);
CREATE INDEX ix_video_task_open
    ON gateway.video_generation_task (status, expires_at)
    WHERE status IN ('SUBMITTED', 'RUNNING');
CREATE INDEX ix_video_task_reservation
    ON gateway.video_generation_task (reservation_id);

-- ---------------------------------------------------------------------------
-- 3. Provider cost for a video second
--
-- gateway.provider_model_price stores per-MILLION-token prices, which cannot
-- express a per-second cost. Overloading those columns with a different unit
-- would make ProviderCostCalculator produce silently wrong numbers, so the
-- authoritative per-second cost stays in the rate card
-- (gateway.model_usage_price.provider_unit_cost) and this view exposes it in
-- the shape the package publication guard needs.
--
-- The guard must NOT treat this as a per-million-token price: a video package's
-- quota is seconds. The guard therefore resolves video aliases through this
-- view and scales by seconds, and token aliases through
-- provider_model_price and scales by tokens. Adding a fake per-million price
-- row for a video alias would hide the unit mismatch rather than resolve it.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE VIEW gateway.video_effective_cost AS
SELECT price.model_code,
       price.resolution_tier,
       price.provider_unit_cost,
       price.retry_rate,
       price.min_absolute_margin,
       price.target_margin_ratio,
       price.unit_price,
       price.price_version,
       -- Effective cost of one sold second: upstream GPU time is billed on
       -- failed generations too, so the retry rate is part of the cost basis.
       ceil(price.provider_unit_cost * (1 + price.retry_rate))::bigint AS effective_unit_cost
FROM gateway.model_usage_price price
WHERE price.usage_dimension = 'VIDEO_SECOND'
  AND price.effective_to IS NULL;

COMMENT ON VIEW gateway.video_effective_cost IS
    'Current per-second effective provider cost for video aliases, including the '
    'retry allowance. Consumed by the package publication margin guard, which '
    'scales it by SECONDS rather than by tokens.';

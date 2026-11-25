SET LOCAL search_path = platform, public;

CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

CREATE TABLE platform.payment_fee_rate_version (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider varchar(40) NOT NULL,
    product varchar(64) NOT NULL,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    payment_fee_rate numeric(20, 6) NOT NULL,
    refund_fee_rate numeric(20, 6) NOT NULL,
    rate_source varchar(128),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_fee_rate_version UNIQUE (provider, product, effective_from),
    CONSTRAINT ex_payment_fee_rate_no_overlap EXCLUDE USING gist (
        provider WITH =,
        product WITH =,
        tstzrange(effective_from, COALESCE(effective_to, 'infinity'::timestamptz), '[)') WITH &&),
    CONSTRAINT ck_payment_fee_rate_period CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_payment_fee_rates CHECK (
        payment_fee_rate >= 0 AND payment_fee_rate < 'Infinity'::numeric
        AND refund_fee_rate >= 0 AND refund_fee_rate < 'Infinity'::numeric),
    CONSTRAINT ck_payment_fee_rate_metadata CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX ix_payment_fee_rate_effective
    ON platform.payment_fee_rate_version(provider, product, effective_from DESC);

CREATE TABLE platform.provider_price_snapshot (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider varchar(40) NOT NULL,
    logical_model varchar(128) NOT NULL,
    upstream_model varchar(128) NOT NULL,
    input_price numeric(20, 6) NOT NULL,
    output_price numeric(20, 6) NOT NULL,
    currency char(3) NOT NULL,
    exchange_rate numeric(20, 6) NOT NULL,
    price_source varchar(128) NOT NULL,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    original_price_id varchar(256) NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    captured_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_price_snapshot_original_id UNIQUE (original_price_id),
    CONSTRAINT ex_provider_price_no_overlap EXCLUDE USING gist (
        provider WITH =,
        upstream_model WITH =,
        tstzrange(effective_from, COALESCE(effective_to, 'infinity'::timestamptz), '[)') WITH &&),
    CONSTRAINT ck_provider_price_period CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_provider_prices CHECK (
        input_price >= 0 AND input_price < 'Infinity'::numeric
        AND output_price >= 0 AND output_price < 'Infinity'::numeric),
    CONSTRAINT ck_provider_exchange_rate CHECK (
        exchange_rate > 0 AND exchange_rate < 'Infinity'::numeric),
    CONSTRAINT ck_provider_price_metadata CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX ix_provider_price_effective
    ON platform.provider_price_snapshot(provider, upstream_model, effective_from DESC);

CREATE TABLE platform.provider_price_sync_health (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    healthy boolean NOT NULL,
    last_error text,
    checked_at timestamptz NOT NULL,
    last_success_at timestamptz
);

CREATE TABLE platform.operating_fact (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_service varchar(64) NOT NULL,
    source_event_id varchar(256) NOT NULL,
    line_no integer NOT NULL,
    event_type varchar(64) NOT NULL,
    occurred_at timestamptz NOT NULL,
    recognized_at timestamptz NOT NULL,
    tenant_id uuid,
    user_id uuid,
    order_id uuid,
    package_plan_version_id uuid,
    payment_transaction_id uuid,
    refund_id uuid,
    request_id varchar(128),
    logical_model varchar(128),
    upstream_model varchar(128),
    channel varchar(64),
    provider varchar(40),
    input_tokens bigint NOT NULL DEFAULT 0,
    output_tokens bigint NOT NULL DEFAULT 0,
    total_tokens bigint NOT NULL DEFAULT 0,
    billing_mode varchar(20) NOT NULL,
    sign smallint NOT NULL DEFAULT 1,
    reversal boolean NOT NULL DEFAULT false,
    confirmed_revenue_amount numeric(20, 6) NOT NULL DEFAULT 0,
    cash_in_amount numeric(20, 6) NOT NULL DEFAULT 0,
    refund_amount numeric(20, 6) NOT NULL DEFAULT 0,
    provider_input_cost_amount numeric(20, 6) NOT NULL DEFAULT 0,
    provider_output_cost_amount numeric(20, 6) NOT NULL DEFAULT 0,
    payment_provider_fee_amount numeric(20, 6) NOT NULL DEFAULT 0,
    refund_provider_fee_amount numeric(20, 6) NOT NULL DEFAULT 0,
    allocated_usage_revenue_amount numeric(20, 6) NOT NULL DEFAULT 0,
    currency char(3) NOT NULL,
    price_snapshot_id uuid REFERENCES platform.provider_price_snapshot(id) ON DELETE RESTRICT,
    payment_fee_rate_version_id uuid REFERENCES platform.payment_fee_rate_version(id) ON DELETE RESTRICT,
    estimated boolean NOT NULL DEFAULT false,
    missing_source boolean NOT NULL DEFAULT false,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_operating_fact_source_line UNIQUE (source_service, source_event_id, line_no),
    CONSTRAINT ck_operating_fact_line_no CHECK (line_no >= 0),
    CONSTRAINT ck_operating_fact_tokens CHECK (
        input_tokens >= 0 AND output_tokens >= 0 AND total_tokens >= 0
        AND total_tokens = input_tokens + output_tokens),
    CONSTRAINT ck_operating_fact_billing_mode CHECK (
        billing_mode IN ('PACKAGE_ONLY', 'WALLET_ONLY', 'HYBRID')),
    CONSTRAINT ck_operating_fact_sign CHECK (sign IN (-1, 1)),
    CONSTRAINT ck_operating_fact_reversal_sign CHECK (reversal = (sign = -1)),
    CONSTRAINT ck_operating_fact_amounts CHECK (
        confirmed_revenue_amount >= 0 AND confirmed_revenue_amount < 'Infinity'::numeric
        AND cash_in_amount >= 0 AND cash_in_amount < 'Infinity'::numeric
        AND refund_amount >= 0 AND refund_amount < 'Infinity'::numeric
        AND provider_input_cost_amount >= 0 AND provider_input_cost_amount < 'Infinity'::numeric
        AND provider_output_cost_amount >= 0 AND provider_output_cost_amount < 'Infinity'::numeric
        AND payment_provider_fee_amount >= 0 AND payment_provider_fee_amount < 'Infinity'::numeric
        AND refund_provider_fee_amount >= 0 AND refund_provider_fee_amount < 'Infinity'::numeric
        AND allocated_usage_revenue_amount >= 0
        AND allocated_usage_revenue_amount < 'Infinity'::numeric),
    CONSTRAINT ck_operating_fact_metadata CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE OR REPLACE FUNCTION platform.reject_operating_fact_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'operating_fact_append_only';
END;
$$;

CREATE TRIGGER trg_operating_fact_append_only
BEFORE UPDATE OR DELETE ON platform.operating_fact
FOR EACH ROW EXECUTE FUNCTION platform.reject_operating_fact_mutation();

CREATE INDEX ix_operating_fact_recognized_query
    ON platform.operating_fact(recognized_at DESC, tenant_id, currency);
CREATE INDEX ix_operating_fact_dimensions
    ON platform.operating_fact(tenant_id, logical_model, upstream_model, channel, provider, billing_mode, recognized_at);
CREATE INDEX ix_operating_fact_dirty_recompute
    ON platform.operating_fact(recognized_at, tenant_id)
    WHERE estimated OR missing_source;
CREATE INDEX ix_operating_fact_request
    ON platform.operating_fact(request_id) WHERE request_id IS NOT NULL;
CREATE INDEX ix_operating_fact_order
    ON platform.operating_fact(order_id) WHERE order_id IS NOT NULL;

CREATE TABLE platform.operating_fact_event_receipt (
    source_service varchar(64) NOT NULL,
    source_event_id varchar(256) NOT NULL,
    payload_hash char(64) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source_service, source_event_id),
    CONSTRAINT ck_operating_fact_receipt_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$')
);

INSERT INTO platform.operating_fact_event_receipt(
    source_service, source_event_id, payload_hash, received_at)
SELECT source_service, source_event_id, min(metadata->>'payloadHash'), min(created_at)
FROM platform.operating_fact
WHERE metadata->>'payloadHash' ~ '^[0-9a-f]{64}$'
GROUP BY source_service, source_event_id
HAVING count(DISTINCT metadata->>'payloadHash') = 1
ON CONFLICT (source_service, source_event_id) DO NOTHING;

CREATE TRIGGER trg_operating_fact_event_receipt_append_only
BEFORE UPDATE OR DELETE ON platform.operating_fact_event_receipt
FOR EACH ROW EXECUTE FUNCTION platform.reject_operating_fact_mutation();

CREATE TABLE platform.operating_daily_aggregate (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_date date NOT NULL,
    timezone varchar(64) NOT NULL DEFAULT 'Asia/Shanghai',
    tenant_id uuid,
    user_id uuid,
    package_plan_version_id uuid,
    logical_model varchar(128),
    upstream_model varchar(128),
    channel varchar(64),
    provider varchar(40),
    billing_mode varchar(20),
    currency char(3) NOT NULL,
    confirmed_revenue_amount numeric(20, 6) NOT NULL DEFAULT 0,
    cash_in_amount numeric(20, 6) NOT NULL DEFAULT 0,
    refund_amount numeric(20, 6) NOT NULL DEFAULT 0,
    provider_cost_amount numeric(20, 6) NOT NULL DEFAULT 0,
    payment_provider_fee_amount numeric(20, 6) NOT NULL DEFAULT 0,
    refund_provider_fee_amount numeric(20, 6) NOT NULL DEFAULT 0,
    allocated_usage_revenue_amount numeric(20, 6) NOT NULL DEFAULT 0,
    profit_amount numeric(20, 6) NOT NULL DEFAULT 0,
    input_tokens bigint NOT NULL DEFAULT 0,
    output_tokens bigint NOT NULL DEFAULT 0,
    total_tokens bigint NOT NULL DEFAULT 0,
    fact_count bigint NOT NULL DEFAULT 0,
    estimated_count bigint NOT NULL DEFAULT 0,
    missing_source_count bigint NOT NULL DEFAULT 0,
    allocation_remainder_amount numeric(20, 6) NOT NULL DEFAULT 0,
    needs_recompute boolean NOT NULL DEFAULT false,
    recomputed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_operating_daily_dimensions UNIQUE NULLS NOT DISTINCT (
        business_date, timezone, tenant_id, user_id, package_plan_version_id,
        logical_model, upstream_model,
        channel, provider, billing_mode, currency),
    CONSTRAINT ck_operating_daily_timezone CHECK (timezone = 'Asia/Shanghai'),
    CONSTRAINT ck_operating_daily_billing_mode CHECK (
        billing_mode IS NULL OR billing_mode IN ('PACKAGE_ONLY', 'WALLET_ONLY', 'HYBRID')),
    CONSTRAINT ck_operating_daily_amounts_finite CHECK (
        confirmed_revenue_amount > '-Infinity'::numeric AND confirmed_revenue_amount < 'Infinity'::numeric
        AND cash_in_amount > '-Infinity'::numeric AND cash_in_amount < 'Infinity'::numeric
        AND refund_amount > '-Infinity'::numeric AND refund_amount < 'Infinity'::numeric
        AND provider_cost_amount > '-Infinity'::numeric AND provider_cost_amount < 'Infinity'::numeric
        AND payment_provider_fee_amount > '-Infinity'::numeric AND payment_provider_fee_amount < 'Infinity'::numeric
        AND refund_provider_fee_amount > '-Infinity'::numeric AND refund_provider_fee_amount < 'Infinity'::numeric
        AND allocated_usage_revenue_amount > '-Infinity'::numeric AND allocated_usage_revenue_amount < 'Infinity'::numeric
        AND profit_amount > '-Infinity'::numeric AND profit_amount < 'Infinity'::numeric
        AND allocation_remainder_amount > '-Infinity'::numeric AND allocation_remainder_amount < 'Infinity'::numeric),
    CONSTRAINT ck_operating_daily_counts CHECK (
        input_tokens >= 0 AND output_tokens >= 0 AND total_tokens = input_tokens + output_tokens
        AND fact_count >= 0 AND estimated_count >= 0 AND missing_source_count >= 0)
);

CREATE INDEX ix_operating_daily_query
    ON platform.operating_daily_aggregate(
        business_date DESC, tenant_id, user_id, package_plan_version_id, currency);
CREATE INDEX ix_operating_daily_dirty_claim
    ON platform.operating_daily_aggregate(business_date, id) WHERE needs_recompute;

CREATE OR REPLACE FUNCTION platform.mark_operating_day_dirty()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    dirty_date date := (NEW.recognized_at AT TIME ZONE 'Asia/Shanghai')::date;
BEGIN
    PERFORM pg_advisory_xact_lock(
        1357911, dirty_date - DATE '2000-01-01');
    INSERT INTO platform.operating_daily_aggregate(
        business_date, timezone, currency, needs_recompute)
    VALUES (
        dirty_date, 'Asia/Shanghai', NEW.currency, true)
    ON CONFLICT ON CONSTRAINT uq_operating_daily_dimensions
    DO UPDATE SET needs_recompute = true, updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_operating_fact_mark_day_dirty
AFTER INSERT ON platform.operating_fact
FOR EACH ROW EXECUTE FUNCTION platform.mark_operating_day_dirty();

CREATE TABLE platform.operating_reconciliation_run (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reconciliation_type varchar(64) NOT NULL,
    range_start timestamptz NOT NULL,
    range_end timestamptz NOT NULL,
    scope jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    item_count bigint NOT NULL DEFAULT 0,
    difference_count bigint NOT NULL DEFAULT 0,
    job_claim_token uuid,
    job_claimed_until timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_operating_reconciliation_range CHECK (range_end > range_start),
    CONSTRAINT ck_operating_reconciliation_run_status CHECK (
        status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_operating_reconciliation_scope CHECK (jsonb_typeof(scope) = 'object'),
    CONSTRAINT ck_operating_reconciliation_counts CHECK (item_count >= 0 AND difference_count >= 0)
);

CREATE INDEX ix_operating_reconciliation_run_claim
    ON platform.operating_reconciliation_run(status, created_at)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE TABLE platform.operating_reconciliation_item (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id uuid NOT NULL REFERENCES platform.operating_reconciliation_run(id) ON DELETE CASCADE,
    difference_type varchar(64) NOT NULL,
    source_service varchar(64),
    source_event_id varchar(256),
    fact_id uuid REFERENCES platform.operating_fact(id) ON DELETE RESTRICT,
    currency char(3) NOT NULL,
    source_amount numeric(20, 6) NOT NULL,
    fact_amount numeric(20, 6) NOT NULL,
    difference_amount numeric(20, 6) NOT NULL,
    evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_operating_reconciliation_amounts CHECK (
        source_amount >= 0 AND source_amount < 'Infinity'::numeric
        AND fact_amount >= 0 AND fact_amount < 'Infinity'::numeric
        AND difference_amount > '-Infinity'::numeric AND difference_amount < 'Infinity'::numeric),
    CONSTRAINT ck_operating_reconciliation_evidence CHECK (jsonb_typeof(evidence) = 'object')
);

CREATE INDEX ix_operating_reconciliation_item_run
    ON platform.operating_reconciliation_item(run_id, difference_type);
CREATE INDEX ix_operating_reconciliation_item_source
    ON platform.operating_reconciliation_item(source_service, source_event_id);

CREATE TABLE platform.operating_alert (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_type varchar(40) NOT NULL,
    fingerprint varchar(128) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'OPEN',
    tenant_id uuid,
    logical_model varchar(128),
    provider varchar(40),
    threshold_value numeric(20, 6) NOT NULL,
    observed_value numeric(20, 6) NOT NULL,
    currency char(3),
    evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
    first_observed_at timestamptz NOT NULL,
    last_observed_at timestamptz NOT NULL,
    acknowledged_by uuid,
    acknowledged_at timestamptz,
    resolved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_operating_alert_type CHECK (
        alert_type IN ('ABNORMAL_COST', 'LOW_GROSS_MARGIN', 'CHANNEL_PRICE_CHANGE',
                       'ABNORMAL_PROVIDER_COST', 'LOW_MARGIN_PACKAGE', 'PROVIDER_PRICE_CHANGED')),
    CONSTRAINT ck_operating_alert_status CHECK (status IN ('OPEN', 'ACKED', 'RESOLVED')),
    CONSTRAINT ck_operating_alert_values CHECK (
        threshold_value > '-Infinity'::numeric AND threshold_value < 'Infinity'::numeric
        AND observed_value > '-Infinity'::numeric AND observed_value < 'Infinity'::numeric),
    CONSTRAINT ck_operating_alert_evidence CHECK (jsonb_typeof(evidence) = 'object'),
    CONSTRAINT ck_operating_alert_observed_period CHECK (last_observed_at >= first_observed_at)
);

CREATE INDEX ix_operating_alert_open_query
    ON platform.operating_alert(status, alert_type, last_observed_at DESC)
    WHERE status IN ('OPEN', 'ACKED');

CREATE UNIQUE INDEX uq_operating_alert_active_fingerprint
    ON platform.operating_alert(fingerprint) WHERE status IN ('OPEN', 'ACKED');

CREATE TABLE platform.operating_backfill_checkpoint (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_service varchar(64) NOT NULL,
    source_type varchar(64) NOT NULL,
    watermark jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    last_error text,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    job_claim_token uuid,
    job_claimed_until timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_operating_backfill_source_type UNIQUE (source_service, source_type),
    CONSTRAINT ck_operating_backfill_status CHECK (
        status IN ('PENDING', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_operating_backfill_watermark CHECK (jsonb_typeof(watermark) = 'object'),
    CONSTRAINT ck_operating_backfill_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX ix_operating_backfill_checkpoint_claim
    ON platform.operating_backfill_checkpoint(status, next_attempt_at, job_claimed_until)
    WHERE status IN ('PENDING', 'RUNNING', 'FAILED');

ALTER TABLE platform.wallet_usage_reservation
    ADD COLUMN billing_mode varchar(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD CONSTRAINT ck_wallet_usage_reservation_billing_mode CHECK (
        billing_mode IN ('HYBRID', 'WALLET_ONLY', 'UNKNOWN'));

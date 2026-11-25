ALTER TABLE gateway.api_key
    ALTER COLUMN package_instance_id DROP NOT NULL;

ALTER TABLE gateway.quota_reservation
    ALTER COLUMN package_instance_id DROP NOT NULL,
    ADD COLUMN package_reserved_tokens bigint NOT NULL DEFAULT 0,
    ADD COLUMN package_settled_tokens bigint NOT NULL DEFAULT 0,
    ADD COLUMN wallet_usage_reservation_id uuid,
    ADD COLUMN wallet_reserved_amount bigint NOT NULL DEFAULT 0,
    ADD COLUMN wallet_settled_amount bigint NOT NULL DEFAULT 0,
    ADD COLUMN billing_mode varchar(16) NOT NULL DEFAULT 'PACKAGE_ONLY',
    ADD COLUMN billing_price_version varchar(64);

UPDATE gateway.quota_reservation
SET package_reserved_tokens = reserved_tokens,
    package_settled_tokens = GREATEST(0, reserved_tokens - uncovered_tokens)
WHERE package_instance_id IS NOT NULL;

ALTER TABLE gateway.quota_reservation
    ADD CONSTRAINT ck_reservation_billing_mode CHECK (
        billing_mode IN ('PACKAGE_ONLY', 'WALLET_ONLY', 'HYBRID')),
    ADD CONSTRAINT ck_reservation_billing_values CHECK (
        package_reserved_tokens >= 0 AND package_settled_tokens >= 0
        AND wallet_reserved_amount >= 0 AND wallet_settled_amount >= 0
        AND (package_reserved_tokens > 0 OR wallet_reserved_amount > 0));

ALTER TABLE gateway.quota_ledger
    ADD COLUMN wallet_usage_reservation_id uuid,
    ADD COLUMN wallet_amount_delta bigint NOT NULL DEFAULT 0,
    DROP CONSTRAINT ck_ledger_scope,
    ADD CONSTRAINT ck_ledger_scope CHECK (
        (entry_type = 'SWITCH'
            AND reservation_id IS NULL
            AND source_package_instance_id IS NOT NULL)
        OR (entry_type <> 'SWITCH' AND reservation_id IS NOT NULL));

CREATE TABLE gateway.model_billing_price (
    id uuid PRIMARY KEY,
    model_code varchar(128) NOT NULL,
    input_price_per_million bigint NOT NULL,
    output_price_per_million bigint NOT NULL,
    provider_cost_input_per_million bigint NOT NULL DEFAULT 0,
    provider_cost_output_per_million bigint NOT NULL DEFAULT 0,
    target_margin_ratio numeric(8,5) NOT NULL DEFAULT 0.40000,
    price_version varchar(64) NOT NULL,
    effective_from timestamptz NOT NULL DEFAULT now(),
    effective_to timestamptz,
    published_by uuid,
    official_source varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_model_billing_price_version UNIQUE (model_code, price_version),
    CONSTRAINT ck_model_billing_price_values CHECK (
        input_price_per_million >= 0 AND output_price_per_million >= 0
        AND provider_cost_input_per_million >= 0 AND provider_cost_output_per_million >= 0
        AND target_margin_ratio >= 0 AND target_margin_ratio < 1
        AND (effective_to IS NULL OR effective_to > effective_from))
);

CREATE INDEX ix_model_billing_price_current
    ON gateway.model_billing_price(model_code, effective_from DESC)
    WHERE effective_to IS NULL;

INSERT INTO gateway.model_billing_price(
    id, model_code, input_price_per_million, output_price_per_million,
    price_version, official_source)
SELECT gen_random_uuid(), m.code, 1, 2, 'bootstrap-v1', 'platform bootstrap'
FROM gateway.model m
ON CONFLICT (model_code, price_version) DO NOTHING;

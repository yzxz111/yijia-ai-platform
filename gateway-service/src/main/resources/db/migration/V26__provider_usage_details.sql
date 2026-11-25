ALTER TABLE gateway.provider_model_price
    ADD COLUMN cache_read_price_per_million bigint,
    ADD COLUMN cache_write_price_per_million bigint,
    ADD COLUMN reasoning_price_per_million bigint,
    ADD CONSTRAINT ck_provider_price_usage_categories CHECK (
        (cache_read_price_per_million IS NULL OR cache_read_price_per_million >= 0)
        AND (cache_write_price_per_million IS NULL OR cache_write_price_per_million >= 0)
        AND (reasoning_price_per_million IS NULL OR reasoning_price_per_million >= 0)
    );

ALTER TABLE gateway.usage_stage
    ADD COLUMN provider_request_id varchar(512),
    ADD COLUMN usage_source varchar(24),
    ADD COLUMN cache_hit_tokens bigint,
    ADD COLUMN cache_miss_tokens bigint,
    ADD COLUMN cache_write_tokens bigint,
    ADD COLUMN reasoning_tokens bigint,
    ADD COLUMN text_tokens bigint,
    ADD COLUMN image_tokens bigint,
    ADD COLUMN audio_tokens bigint,
    ADD COLUMN raw_usage jsonb,
    ADD COLUMN customer_billable boolean,
    ADD COLUMN provider_billable boolean,
    ADD COLUMN reconciliation_status varchar(20),
    ADD COLUMN provider_uncached_input_cost_amount bigint,
    ADD COLUMN provider_cache_read_cost_amount bigint,
    ADD COLUMN provider_cache_write_cost_amount bigint,
    ADD COLUMN provider_normal_output_cost_amount bigint,
    ADD COLUMN provider_reasoning_cost_amount bigint;

UPDATE gateway.usage_stage
SET usage_source = CASE
        WHEN usage_estimated THEN 'LOCAL_ESTIMATED'
        ELSE 'PROVIDER_ACTUAL'
    END,
    customer_billable = true,
    provider_billable = true,
    reconciliation_status = 'NOT_APPLICABLE';

ALTER TABLE gateway.usage_stage
    ALTER COLUMN usage_source SET NOT NULL,
    ALTER COLUMN usage_source SET DEFAULT 'LOCAL_ESTIMATED',
    ALTER COLUMN customer_billable SET NOT NULL,
    ALTER COLUMN customer_billable SET DEFAULT true,
    ALTER COLUMN provider_billable SET NOT NULL,
    ALTER COLUMN provider_billable SET DEFAULT true,
    ALTER COLUMN reconciliation_status SET NOT NULL,
    ALTER COLUMN reconciliation_status SET DEFAULT 'PENDING',
    ADD CONSTRAINT ck_usage_stage_usage_source CHECK (
        usage_source IN (
            'PROVIDER_ACTUAL', 'LOCAL_ESTIMATED',
            'BILL_RECONCILED', 'MANUAL_CORRECTED'
        )
    ),
    ADD CONSTRAINT ck_usage_stage_reconciliation_status CHECK (
        reconciliation_status IN (
            'PENDING', 'MATCHED', 'MISMATCHED', 'ADJUSTED', 'NOT_APPLICABLE'
        )
    ),
    ADD CONSTRAINT ck_usage_stage_usage_details CHECK (
        (cache_hit_tokens IS NULL
            OR cache_hit_tokens BETWEEN 0 AND input_tokens)
        AND (cache_miss_tokens IS NULL
            OR cache_miss_tokens BETWEEN 0 AND input_tokens)
        AND (cache_write_tokens IS NULL
            OR cache_write_tokens BETWEEN 0 AND input_tokens)
        AND (reasoning_tokens IS NULL
            OR reasoning_tokens BETWEEN 0 AND output_tokens)
        AND (text_tokens IS NULL
            OR text_tokens BETWEEN 0 AND input_tokens)
        AND (image_tokens IS NULL
            OR image_tokens BETWEEN 0 AND input_tokens)
        AND (audio_tokens IS NULL
            OR audio_tokens BETWEEN 0 AND input_tokens)
        AND COALESCE(cache_hit_tokens, 0) + COALESCE(cache_write_tokens, 0)
            <= input_tokens
    ),
    ADD CONSTRAINT ck_usage_stage_raw_usage CHECK (
        raw_usage IS NULL
        OR (
            jsonb_typeof(raw_usage) = 'object'
            AND octet_length(raw_usage::text) <= 16384
        )
    ),
    ADD CONSTRAINT ck_usage_stage_detailed_cost CHECK (
        (
            provider_uncached_input_cost_amount IS NULL
            AND provider_cache_read_cost_amount IS NULL
            AND provider_cache_write_cost_amount IS NULL
            AND provider_normal_output_cost_amount IS NULL
            AND provider_reasoning_cost_amount IS NULL
        )
        OR (
            provider_uncached_input_cost_amount >= 0
            AND provider_cache_read_cost_amount >= 0
            AND provider_cache_write_cost_amount >= 0
            AND provider_normal_output_cost_amount >= 0
            AND provider_reasoning_cost_amount >= 0
            AND provider_input_cost_amount = provider_uncached_input_cost_amount
                + provider_cache_read_cost_amount + provider_cache_write_cost_amount
            AND provider_output_cost_amount = provider_normal_output_cost_amount
                + provider_reasoning_cost_amount
        )
    ),
    ADD CONSTRAINT ck_usage_stage_provider_billable_cost CHECK (
        provider_billable
        OR (
            provider_cost_amount IS NULL
            AND provider_cost_currency IS NULL
            AND provider_price_id IS NULL
            AND provider_input_cost_amount IS NULL
            AND provider_output_cost_amount IS NULL
            AND provider_original_input_cost_amount IS NULL
            AND provider_original_output_cost_amount IS NULL
            AND provider_original_cost_amount IS NULL
            AND provider_original_cost_currency IS NULL
            AND provider_exchange_rate IS NULL
            AND provider_uncached_input_cost_amount IS NULL
            AND provider_cache_read_cost_amount IS NULL
            AND provider_cache_write_cost_amount IS NULL
            AND provider_normal_output_cost_amount IS NULL
            AND provider_reasoning_cost_amount IS NULL
            AND NOT price_estimated
        )
    );

CREATE INDEX ix_usage_stage_provider_request
    ON gateway.usage_stage (provider_id, provider_request_id)
    WHERE provider_request_id IS NOT NULL;

CREATE INDEX ix_usage_stage_reconciliation
    ON gateway.usage_stage (reconciliation_status, created_at, id)
    WHERE provider_billable AND reconciliation_status IN ('PENDING', 'MISMATCHED');

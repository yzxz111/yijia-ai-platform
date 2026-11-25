ALTER TABLE gateway.provider_cost_record
    ADD COLUMN provider_price_id uuid
        REFERENCES gateway.provider_model_price(id) ON DELETE SET NULL,
    ADD COLUMN provider_input_cost_amount bigint,
    ADD COLUMN provider_output_cost_amount bigint,
    ADD COLUMN provider_original_input_cost_amount numeric(20,8),
    ADD COLUMN provider_original_output_cost_amount numeric(20,8),
    ADD COLUMN provider_original_cost_amount numeric(20,8),
    ADD COLUMN provider_original_cost_currency char(3),
    ADD COLUMN provider_exchange_rate numeric(20,8),
    ADD CONSTRAINT ck_provider_cost_record_breakdown CHECK (
        (provider_input_cost_amount IS NULL AND provider_output_cost_amount IS NULL)
        OR (
            amount IS NOT NULL
            AND provider_input_cost_amount IS NOT NULL
            AND provider_output_cost_amount IS NOT NULL
            AND provider_input_cost_amount >= 0
            AND provider_output_cost_amount >= 0
            AND amount = provider_input_cost_amount + provider_output_cost_amount
        )
    ),
    ADD CONSTRAINT ck_provider_cost_record_original CHECK (
        (
            provider_original_input_cost_amount IS NULL
            AND provider_original_output_cost_amount IS NULL
            AND provider_original_cost_amount IS NULL
            AND provider_original_cost_currency IS NULL
            AND provider_exchange_rate IS NULL
        )
        OR (
            provider_original_input_cost_amount IS NOT NULL
            AND provider_original_output_cost_amount IS NOT NULL
            AND provider_original_cost_amount IS NOT NULL
            AND provider_original_cost_currency IS NOT NULL
            AND provider_exchange_rate IS NOT NULL
            AND provider_original_input_cost_amount NOT IN
                ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
            AND provider_original_output_cost_amount NOT IN
                ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
            AND provider_original_cost_amount NOT IN
                ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
            AND provider_exchange_rate NOT IN
                ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
            AND provider_original_input_cost_amount >= 0
            AND provider_original_output_cost_amount >= 0
            AND provider_original_cost_amount =
                provider_original_input_cost_amount + provider_original_output_cost_amount
            AND provider_exchange_rate > 0
        )
    );

ALTER TABLE gateway.usage_stage
    ADD COLUMN provider_price_id uuid
        REFERENCES gateway.provider_model_price(id) ON DELETE SET NULL,
    ADD COLUMN provider_input_cost_amount bigint,
    ADD COLUMN provider_output_cost_amount bigint,
    ADD COLUMN provider_original_input_cost_amount numeric(20,8),
    ADD COLUMN provider_original_output_cost_amount numeric(20,8),
    ADD COLUMN provider_original_cost_amount numeric(20,8),
    ADD COLUMN provider_original_cost_currency char(3),
    ADD COLUMN provider_exchange_rate numeric(20,8),
    ADD CONSTRAINT ck_usage_stage_cost_breakdown CHECK (
        (provider_input_cost_amount IS NULL AND provider_output_cost_amount IS NULL)
        OR (
            provider_cost_amount IS NOT NULL
            AND provider_input_cost_amount IS NOT NULL
            AND provider_output_cost_amount IS NOT NULL
            AND provider_input_cost_amount >= 0
            AND provider_output_cost_amount >= 0
            AND provider_cost_amount = provider_input_cost_amount + provider_output_cost_amount
        )
    ),
    ADD CONSTRAINT ck_usage_stage_original_cost CHECK (
        (
            provider_original_input_cost_amount IS NULL
            AND provider_original_output_cost_amount IS NULL
            AND provider_original_cost_amount IS NULL
            AND provider_original_cost_currency IS NULL
            AND provider_exchange_rate IS NULL
        )
        OR (
            provider_original_input_cost_amount IS NOT NULL
            AND provider_original_output_cost_amount IS NOT NULL
            AND provider_original_cost_amount IS NOT NULL
            AND provider_original_cost_currency IS NOT NULL
            AND provider_exchange_rate IS NOT NULL
            AND provider_original_input_cost_amount NOT IN
                ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
            AND provider_original_output_cost_amount NOT IN
                ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
            AND provider_original_cost_amount NOT IN
                ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
            AND provider_exchange_rate NOT IN
                ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
            AND provider_original_input_cost_amount >= 0
            AND provider_original_output_cost_amount >= 0
            AND provider_original_cost_amount =
                provider_original_input_cost_amount + provider_original_output_cost_amount
            AND provider_exchange_rate > 0
        )
    );

UPDATE gateway.provider_cost_record
SET price_estimated = true
WHERE provider_price_id IS NULL
  AND provider_input_cost_amount IS NULL
  AND provider_output_cost_amount IS NULL;

UPDATE gateway.usage_stage
SET price_estimated = true
WHERE provider_price_id IS NULL
  AND provider_input_cost_amount IS NULL
  AND provider_output_cost_amount IS NULL
  AND provider_cost_amount IS NOT NULL;

ALTER TABLE gateway.usage_log
    ADD COLUMN economics_event_pending boolean NOT NULL DEFAULT false;

CREATE INDEX ix_usage_log_economics_event_pending
    ON gateway.usage_log (created_at, id) INCLUDE (reservation_id)
    WHERE economics_event_pending;

CREATE TABLE gateway.usage_economics_outbox (
    event_id uuid PRIMARY KEY,
    event_fingerprint varchar(64) NOT NULL,
    payload jsonb NOT NULL,
    payload_hash varchar(64) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    claim_token uuid,
    claimed_until timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    sent_at timestamptz,
    last_error varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_usage_economics_outbox_hashes CHECK (
        event_fingerprint ~ '^[0-9a-f]{64}$'
        AND payload_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_usage_economics_outbox_status CHECK (
        status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'DEAD')
    ),
    CONSTRAINT ck_usage_economics_outbox_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_usage_economics_outbox_claim CHECK (
        (status = 'SENDING' AND claim_token IS NOT NULL AND claimed_until IS NOT NULL)
        OR (status <> 'SENDING' AND claim_token IS NULL AND claimed_until IS NULL)
    ),
    CONSTRAINT ck_usage_economics_outbox_sent CHECK (
        (status = 'SENT' AND sent_at IS NOT NULL)
        OR (status <> 'SENT' AND sent_at IS NULL)
    )
);

CREATE UNIQUE INDEX uq_usage_economics_outbox_fingerprint
    ON gateway.usage_economics_outbox (event_fingerprint);

CREATE INDEX ix_usage_economics_outbox_due
    ON gateway.usage_economics_outbox (next_attempt_at, created_at, event_id)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX ix_usage_economics_outbox_stale_claim
    ON gateway.usage_economics_outbox (claimed_until, event_id)
    WHERE status = 'SENDING';

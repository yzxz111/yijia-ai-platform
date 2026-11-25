ALTER TABLE platform.account_balance
    ADD COLUMN reserved_amount bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_account_balance_reserved_nonnegative CHECK (reserved_amount >= 0);

ALTER TABLE platform.balance_ledger
    ADD COLUMN model_code varchar(128),
    ADD COLUMN price_version varchar(64),
    DROP CONSTRAINT ck_balance_ledger_entry_type,
    DROP CONSTRAINT ck_balance_ledger_posting_shape;

ALTER TABLE platform.balance_ledger
    ADD CONSTRAINT ck_balance_ledger_entry_type CHECK (
        entry_type IN ('RECHARGE', 'PACKAGE_PURCHASE', 'PACKAGE_REFUND', 'MODEL_USAGE')),
    ADD CONSTRAINT ck_balance_ledger_posting_shape CHECK (
        (
            entry_type = 'RECHARGE'
            AND business_type = 'RECHARGE_ORDER'
            AND amount_delta > 0
            AND paid_amount > 0
            AND bonus_amount = 0
            AND amount_delta = paid_amount + bonus_amount
        )
        OR (
            entry_type = 'PACKAGE_PURCHASE'
            AND business_type = 'ORDER'
            AND amount_delta < 0
            AND paid_amount = 0
            AND bonus_amount = 0
        )
        OR (
            entry_type = 'PACKAGE_REFUND'
            AND business_type = 'REFUND'
            AND amount_delta > 0
            AND paid_amount = 0
            AND bonus_amount = 0
        )
        OR (
            entry_type = 'MODEL_USAGE'
            AND business_type = 'MODEL_USAGE'
            AND amount_delta < 0
            AND paid_amount = abs(amount_delta)
            AND bonus_amount = 0
        ));

CREATE TABLE platform.wallet_usage_reservation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    account_id uuid NOT NULL,
    request_id varchar(128) NOT NULL,
    estimated_amount bigint NOT NULL,
    settled_amount bigint,
    status varchar(16) NOT NULL DEFAULT 'RESERVED',
    model_code varchar(128) NOT NULL,
    price_version varchar(64),
    input_tokens bigint NOT NULL DEFAULT 0,
    output_tokens bigint NOT NULL DEFAULT 0,
    actual_input_tokens bigint,
    actual_output_tokens bigint,
    idempotency_key varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    settled_at timestamptz,
    released_at timestamptz,
    CONSTRAINT uq_wallet_usage_account_request UNIQUE (account_id, request_id),
    CONSTRAINT uq_wallet_usage_account_idempotency UNIQUE (account_id, idempotency_key),
    CONSTRAINT fk_wallet_usage_account_scope FOREIGN KEY (tenant_id, user_id, account_id)
        REFERENCES platform.account_balance(tenant_id, user_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_wallet_usage_amounts CHECK (
        estimated_amount > 0 AND (settled_amount IS NULL OR settled_amount >= 0)),
    CONSTRAINT ck_wallet_usage_tokens CHECK (
        input_tokens >= 0 AND output_tokens >= 0
        AND (actual_input_tokens IS NULL OR actual_input_tokens >= 0)
        AND (actual_output_tokens IS NULL OR actual_output_tokens >= 0)),
    CONSTRAINT ck_wallet_usage_status CHECK (status IN ('RESERVED', 'SETTLED', 'RELEASED', 'EXPIRED'))
);

CREATE INDEX ix_wallet_usage_user_created
    ON platform.wallet_usage_reservation(tenant_id, user_id, created_at DESC);

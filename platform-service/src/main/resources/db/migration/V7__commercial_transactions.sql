CREATE TABLE platform.refund_policy_version (
    version integer PRIMARY KEY,
    fee_bps integer NOT NULL,
    minimum_fee bigint NOT NULL,
    refund_window_days integer NOT NULL,
    maximum_usage_bps integer NOT NULL,
    effective_at timestamptz NOT NULL,
    published boolean NOT NULL DEFAULT false,
    created_by uuid REFERENCES platform.app_user(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_refund_policy_values CHECK (
        fee_bps BETWEEN 0 AND 10000
        AND minimum_fee >= 0
        AND refund_window_days > 0
        AND maximum_usage_bps BETWEEN 0 AND 10000
    )
);

INSERT INTO platform.refund_policy_version(
    version, fee_bps, minimum_fee, refund_window_days,
    maximum_usage_bps, effective_at, published)
VALUES (1, 500, 100, 7, 1000, now(), true);

CREATE FUNCTION platform.reject_published_refund_policy_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.published THEN
        RAISE EXCEPTION 'published_refund_policy_immutable' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_refund_policy_immutable
BEFORE UPDATE OR DELETE ON platform.refund_policy_version
FOR EACH ROW EXECUTE FUNCTION platform.reject_published_refund_policy_mutation();

ALTER TABLE platform.grant_request
    DROP CONSTRAINT ck_grant_status;

ALTER TABLE platform.grant_request
    ADD CONSTRAINT ck_grant_status
        CHECK (status IN ('QUEUED', 'ACTIVE', 'FAILED', 'REVOKED'));

ALTER TABLE platform.customer_order
    ADD COLUMN expires_at timestamptz,
    ADD COLUMN package_snapshot jsonb,
    ADD COLUMN idempotency_key varchar(128),
    ADD COLUMN successful_payment_transaction_id uuid,
    ADD COLUMN closed_reason varchar(64),
    ADD COLUMN lock_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN close_attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN next_close_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN job_claim_token uuid,
    ADD COLUMN job_claimed_until timestamptz,
    ADD CONSTRAINT ck_order_close_attempts CHECK (close_attempt_count >= 0);

UPDATE platform.customer_order AS customer_order
SET expires_at = customer_order.created_at + interval '15 minutes',
    next_close_at = customer_order.created_at + interval '15 minutes',
    package_snapshot = jsonb_build_object(
        'packageVersionId', package_version.id,
        'packageCode', package_plan.code,
        'packageName', package_plan.name,
        'priceAmount', customer_order.amount,
        'currency', customer_order.currency,
        'totalTokenQuota', package_version.total_token_quota,
        'durationDays', package_version.duration_days,
        'maxApiKeys', package_version.max_api_keys,
        'requestsPerMinute', package_version.requests_per_minute,
        'tokensPerMinute', package_version.tokens_per_minute,
        'maxConcurrency', package_version.max_concurrency,
        'dailyRequestLimit', package_version.daily_request_limit,
        'maxOutputTokens', package_version.max_output_tokens,
        'maxFileSizeBytes', package_version.max_file_size_bytes,
        'oncePerUser', package_version.once_per_user,
        'models', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'modelCode', model.model_code,
                'modelTokenQuota', model.model_token_quota,
                'capabilities', model.capabilities)
                ORDER BY model.model_code)
            FROM platform.package_plan_version_model AS model
            WHERE model.package_plan_version_id = package_version.id
        ), '[]'::jsonb)
    )
FROM platform.package_plan_version AS package_version
JOIN platform.package_plan AS package_plan
  ON package_plan.id = package_version.package_plan_id
WHERE customer_order.package_plan_version_id = package_version.id;

CREATE UNIQUE INDEX uq_order_user_idempotency
    ON platform.customer_order(user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX ix_order_pending_expiry
    ON platform.customer_order(status, expires_at, next_close_at, job_claimed_until)
    WHERE status = 'PENDING';

ALTER TABLE platform.payment_transaction
    ADD COLUMN payment_product varchar(32),
    ADD COLUMN provider_status varchar(64),
    ADD COLUMN provider_error_code varchar(128),
    ADD COLUMN expires_at timestamptz,
    ADD COLUMN last_queried_at timestamptz,
    ADD COLUMN callback_received_at timestamptz,
    ADD COLUMN applied_to_order boolean NOT NULL DEFAULT false,
    ADD COLUMN request_payload_hash varchar(64),
    ADD COLUMN lock_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN query_attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN next_query_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN job_claim_token uuid,
    ADD COLUMN job_claimed_until timestamptz,
    ADD CONSTRAINT ck_payment_product CHECK (
        payment_product IS NULL OR payment_product IN (
            'MOCK', 'WECHAT_NATIVE', 'WECHAT_H5', 'ALIPAY_PAGE', 'ALIPAY_WAP'
        )
    ),
    ADD CONSTRAINT ck_payment_query_attempts CHECK (query_attempt_count >= 0);

UPDATE platform.payment_transaction
SET payment_product = 'MOCK'
WHERE provider = 'MOCK' AND payment_product IS NULL;

CREATE UNIQUE INDEX uq_payment_active_attempt_per_order
    ON platform.payment_transaction(order_id)
    WHERE status IN ('CREATED', 'PENDING');

CREATE UNIQUE INDEX uq_payment_applied_per_order
    ON platform.payment_transaction(order_id)
    WHERE applied_to_order;

CREATE INDEX ix_payment_pending_query
    ON platform.payment_transaction(status, next_query_at, job_claimed_until)
    WHERE status = 'PENDING';

CREATE INDEX ix_payment_reconciliation_success
    ON platform.payment_transaction(provider, paid_at, transaction_no)
    WHERE status = 'SUCCEEDED';

ALTER TABLE platform.customer_order
    ADD CONSTRAINT fk_order_successful_payment
    FOREIGN KEY (successful_payment_transaction_id)
    REFERENCES platform.payment_transaction(id) ON DELETE RESTRICT;

ALTER TABLE platform.refund
    DROP CONSTRAINT ck_refund_status,
    DROP CONSTRAINT ck_refund_amount;

ALTER TABLE platform.refund
    ADD COLUMN refund_type varchar(16),
    ADD COLUMN policy_version integer REFERENCES platform.refund_policy_version(version),
    ADD COLUMN calculation_snapshot jsonb,
    ADD COLUMN requested_by uuid REFERENCES platform.app_user(id) ON DELETE RESTRICT,
    ADD COLUMN reviewed_by uuid REFERENCES platform.app_user(id) ON DELETE RESTRICT,
    ADD COLUMN reviewed_at timestamptz,
    ADD COLUMN review_note varchar(500),
    ADD COLUMN provider varchar(32),
    ADD COLUMN provider_status varchar(64),
    ADD COLUMN provider_error_code varchar(128),
    ADD COLUMN gateway_hold_business_no varchar(128),
    ADD COLUMN revocation_business_no varchar(128),
    ADD COLUMN submitted_at timestamptz,
    ADD COLUMN lock_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN provider_attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN job_claim_token uuid,
    ADD COLUMN job_claimed_until timestamptz;

UPDATE platform.refund AS refund
SET status = CASE refund.status
        WHEN 'PENDING' THEN 'PROCESSING'
        WHEN 'SUCCEEDED' THEN 'COMPLETED'
        ELSE 'FAILED'
    END,
    refund_type = CASE
        WHEN refund.amount = customer_order.amount THEN 'FULL'
        ELSE 'PARTIAL'
    END,
    policy_version = 1,
    calculation_snapshot = jsonb_build_object('migratedFromPhase1', true),
    requested_by = customer_order.user_id,
    provider = payment_transaction.provider,
    gateway_hold_business_no = 'legacy-hold:' || refund.refund_no,
    revocation_business_no = 'legacy-revoke:' || refund.refund_no
FROM platform.customer_order, platform.payment_transaction
WHERE customer_order.id = refund.order_id
  AND payment_transaction.id = refund.payment_transaction_id;

ALTER TABLE platform.refund
    ADD CONSTRAINT ck_refund_status CHECK (status IN (
        'PREPARING', 'PENDING_APPROVAL', 'REJECTED', 'CANCELED',
        'SUBMITTING', 'PROCESSING', 'REVOKING', 'COMPLETED', 'FAILED'
    )),
    ADD CONSTRAINT ck_refund_type CHECK (
        refund_type IS NULL OR refund_type IN ('FULL', 'PARTIAL')
    ),
    ADD CONSTRAINT ck_refund_amount_state CHECK (
        amount >= 0
        AND (
            status NOT IN ('PENDING_APPROVAL', 'SUBMITTING', 'PROCESSING', 'REVOKING', 'COMPLETED')
            OR (amount > 0 AND refund_type IS NOT NULL AND calculation_snapshot IS NOT NULL)
        )
    ),
    ADD CONSTRAINT ck_refund_provider_attempts CHECK (provider_attempt_count >= 0);

CREATE UNIQUE INDEX uq_refund_open_per_order
    ON platform.refund(order_id)
    WHERE status IN ('PREPARING', 'PENDING_APPROVAL', 'SUBMITTING', 'PROCESSING', 'REVOKING');

CREATE UNIQUE INDEX uq_refund_gateway_hold_business_no
    ON platform.refund(gateway_hold_business_no)
    WHERE gateway_hold_business_no IS NOT NULL;

CREATE UNIQUE INDEX uq_refund_revocation_business_no
    ON platform.refund(revocation_business_no)
    WHERE revocation_business_no IS NOT NULL;

CREATE INDEX ix_refund_next_action
    ON platform.refund(status, next_attempt_at, job_claimed_until)
    WHERE status IN ('PREPARING', 'SUBMITTING', 'PROCESSING');

CREATE INDEX ix_refund_reconciliation_completed
    ON platform.refund(provider, completed_at, refund_no)
    WHERE status = 'COMPLETED';

CREATE TABLE platform.payment_anomaly (
    id uuid PRIMARY KEY,
    fingerprint varchar(64) NOT NULL,
    anomaly_type varchar(64) NOT NULL,
    provider varchar(32),
    order_id uuid REFERENCES platform.customer_order(id) ON DELETE RESTRICT,
    payment_transaction_id uuid REFERENCES platform.payment_transaction(id) ON DELETE RESTRICT,
    refund_id uuid REFERENCES platform.refund(id) ON DELETE RESTRICT,
    status varchar(20) NOT NULL DEFAULT 'OPEN',
    assignee uuid REFERENCES platform.app_user(id) ON DELETE SET NULL,
    detail jsonb NOT NULL DEFAULT '{}'::jsonb,
    resolution_note varchar(1000),
    resolved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    lock_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_payment_anomaly_status
        CHECK (status IN ('OPEN', 'INVESTIGATING', 'RESOLVED')),
    CONSTRAINT ck_payment_anomaly_detail
        CHECK (jsonb_typeof(detail) = 'object')
);

CREATE UNIQUE INDEX uq_payment_anomaly_open_fingerprint
    ON platform.payment_anomaly(fingerprint)
    WHERE status IN ('OPEN', 'INVESTIGATING');

CREATE INDEX ix_payment_anomaly_status_created
    ON platform.payment_anomaly(status, created_at DESC);

CREATE TABLE platform.reconciliation_run (
    id uuid PRIMARY KEY,
    provider varchar(32) NOT NULL,
    bill_date date NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    bill_sha256 varchar(64),
    summary jsonb NOT NULL DEFAULT '{}'::jsonb,
    started_at timestamptz,
    completed_at timestamptz,
    job_claim_token uuid,
    job_claimed_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider, bill_date),
    CONSTRAINT ck_reconciliation_run_status
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_reconciliation_run_summary
        CHECK (jsonb_typeof(summary) = 'object')
);

CREATE INDEX ix_reconciliation_provider_date
    ON platform.reconciliation_run(provider, bill_date DESC);

CREATE TABLE platform.reconciliation_item (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES platform.reconciliation_run(id) ON DELETE CASCADE,
    provider_record_id varchar(128) NOT NULL,
    transaction_no varchar(64),
    refund_no varchar(64),
    difference_type varchar(64) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'OPEN',
    provider_amount bigint,
    platform_amount bigint,
    detail jsonb NOT NULL DEFAULT '{}'::jsonb,
    resolved_by uuid REFERENCES platform.app_user(id) ON DELETE SET NULL,
    resolution_note varchar(1000),
    resolved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_id, provider_record_id),
    CONSTRAINT ck_reconciliation_item_status
        CHECK (status IN ('OPEN', 'AUTO_RESOLVED', 'RESOLVED')),
    CONSTRAINT ck_reconciliation_item_amounts CHECK (
        (provider_amount IS NULL OR provider_amount >= 0)
        AND (platform_amount IS NULL OR platform_amount >= 0)
    ),
    CONSTRAINT ck_reconciliation_item_detail
        CHECK (jsonb_typeof(detail) = 'object')
);

CREATE INDEX ix_reconciliation_item_status
    ON platform.reconciliation_item(run_id, status, created_at);

ALTER TABLE platform.payment_config
    ADD COLUMN credential_fingerprint varchar(128),
    ADD COLUMN last_validation_status varchar(32),
    ADD COLUMN last_validated_at timestamptz;

INSERT INTO platform.payment_config(id, provider, environment, enabled)
VALUES
    (gen_random_uuid(), 'WECHAT_PAY', 'SANDBOX', false),
    (gen_random_uuid(), 'ALIPAY', 'SANDBOX', false)
ON CONFLICT (provider, environment) DO NOTHING;

UPDATE platform.payment_config
SET enabled = false
WHERE provider = 'MOCK' AND environment = 'LOCAL';

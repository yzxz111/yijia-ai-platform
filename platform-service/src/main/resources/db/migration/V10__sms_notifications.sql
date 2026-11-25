ALTER TABLE platform.app_user
    ADD COLUMN phone_ciphertext bytea,
    ADD COLUMN phone_lookup_hash char(64),
    ADD COLUMN phone_last4 char(4),
    ADD COLUMN phone_verified_at timestamptz,
    ADD COLUMN sms_notifications_enabled boolean NOT NULL DEFAULT true,
    ADD CONSTRAINT ck_app_user_phone_state CHECK (
        (phone_ciphertext IS NULL
            AND phone_lookup_hash IS NULL
            AND phone_last4 IS NULL
            AND phone_verified_at IS NULL)
        OR
        (phone_ciphertext IS NOT NULL
            AND phone_lookup_hash IS NOT NULL
            AND phone_last4 IS NOT NULL
            AND phone_verified_at IS NOT NULL)
    ),
    ADD CONSTRAINT ck_app_user_phone_hash CHECK (
        phone_lookup_hash IS NULL OR phone_lookup_hash ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT ck_app_user_phone_last4 CHECK (
        phone_last4 IS NULL OR phone_last4 ~ '^[0-9]{4}$'
    ),
    ADD CONSTRAINT uq_app_user_tenant_id UNIQUE (tenant_id, id);

CREATE UNIQUE INDEX uq_app_user_phone_lookup_hash
    ON platform.app_user(phone_lookup_hash)
    WHERE phone_lookup_hash IS NOT NULL;

CREATE FUNCTION platform.valid_sms_template_variable_names(candidate jsonb)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
    SELECT CASE
        WHEN jsonb_typeof(candidate) <> 'array' THEN false
        ELSE
            NOT EXISTS (
                SELECT 1
                FROM jsonb_array_elements(candidate) AS item(value)
                WHERE jsonb_typeof(value) <> 'string'
                   OR (value #>> '{}') !~ '^[A-Za-z][A-Za-z0-9_]{0,63}$'
            )
            AND jsonb_array_length(candidate) = (
                SELECT count(DISTINCT value #>> '{}')
                FROM jsonb_array_elements(candidate) AS item(value)
            )
    END
$$;

CREATE TABLE platform.sms_template (
    template_key varchar(64) PRIMARY KEY,
    template_code varchar(32) NOT NULL UNIQUE,
    template_type varchar(20) NOT NULL,
    variable_names jsonb NOT NULL DEFAULT '[]'::jsonb,
    integration_status varchar(24) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_sms_template_key CHECK (template_key ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_sms_template_type CHECK (
        template_type IN ('VERIFICATION', 'NOTIFICATION')
    ),
    CONSTRAINT ck_sms_template_variables CHECK (
        platform.valid_sms_template_variable_names(variable_names)
    ),
    CONSTRAINT ck_sms_template_status CHECK (
        integration_status IN ('ACTIVE', 'PENDING_INTEGRATION', 'RESERVED')
    )
);

CREATE TABLE platform.sms_verification_challenge (
    id uuid PRIMARY KEY,
    tenant_id uuid,
    user_id uuid,
    purpose varchar(32) NOT NULL,
    phone_ciphertext bytea NOT NULL,
    phone_lookup_hash char(64) NOT NULL,
    phone_last4 char(4) NOT NULL,
    code_hash varchar(128) NOT NULL,
    expires_at timestamptz NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    verified_at timestamptz,
    consumed_at timestamptz,
    request_ip_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_sms_challenge_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_sms_challenge_user_scope CHECK (
        (tenant_id IS NULL AND user_id IS NULL)
        OR (tenant_id IS NOT NULL AND user_id IS NOT NULL)
    ),
    CONSTRAINT ck_sms_challenge_purpose CHECK (
        purpose IN (
            'REGISTER', 'CHANGE_PASSWORD', 'PAYMENT_CONFIRM', 'API_KEY_RESET',
            'PHONE_BIND', 'PHONE_CHANGE'
        )
    ),
    CONSTRAINT ck_sms_challenge_attempts CHECK (
        attempt_count BETWEEN 0 AND 5
    ),
    CONSTRAINT ck_sms_challenge_phone_hash CHECK (
        phone_lookup_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_sms_challenge_phone_last4 CHECK (
        phone_last4 ~ '^[0-9]{4}$'
    ),
    CONSTRAINT ck_sms_challenge_ip_hash CHECK (
        request_ip_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_sms_challenge_times CHECK (
        expires_at > created_at
        AND expires_at <= created_at + interval '5 minutes'
        AND (verified_at IS NULL OR (verified_at >= created_at AND verified_at <= expires_at))
        AND (consumed_at IS NULL OR (
            consumed_at >= created_at
            AND consumed_at <= expires_at
            AND verified_at IS NOT NULL
            AND consumed_at >= verified_at
        ))
    ),
    CONSTRAINT ck_sms_challenge_consumption CHECK (
        consumed_at IS NULL OR verified_at IS NOT NULL
    )
);

CREATE INDEX ix_sms_challenge_phone_rate_limit
    ON platform.sms_verification_challenge(phone_lookup_hash, purpose, created_at DESC);

CREATE INDEX ix_sms_challenge_phone_daily
    ON platform.sms_verification_challenge(phone_lookup_hash, created_at DESC);

CREATE INDEX ix_sms_challenge_ip_rate_limit
    ON platform.sms_verification_challenge(request_ip_hash, created_at DESC);

CREATE INDEX ix_sms_challenge_user
    ON platform.sms_verification_challenge(user_id, created_at DESC)
    WHERE user_id IS NOT NULL;

CREATE TABLE platform.sms_proof (
    id uuid PRIMARY KEY,
    token_hash char(64) NOT NULL UNIQUE,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    purpose varchar(32) NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_sms_proof_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_sms_proof_purpose CHECK (
        purpose IN (
            'REGISTER', 'CHANGE_PASSWORD', 'PAYMENT_CONFIRM', 'API_KEY_RESET',
            'PHONE_BIND', 'PHONE_CHANGE'
        )
    ),
    CONSTRAINT ck_sms_proof_token_hash CHECK (
        token_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_sms_proof_times CHECK (
        expires_at > created_at
        AND (consumed_at IS NULL OR (consumed_at >= created_at AND consumed_at <= expires_at))
    )
);

CREATE INDEX ix_sms_proof_user
    ON platform.sms_proof(user_id, purpose, created_at DESC);

CREATE INDEX ix_sms_proof_expiry
    ON platform.sms_proof(expires_at)
    WHERE consumed_at IS NULL;

CREATE TABLE platform.sms_delivery (
    id uuid PRIMARY KEY,
    tenant_id uuid,
    user_id uuid,
    template_key varchar(64) NOT NULL REFERENCES platform.sms_template(template_key) ON DELETE RESTRICT,
    template_code varchar(32) NOT NULL,
    phone_ciphertext bytea NOT NULL,
    phone_last4 char(4) NOT NULL,
    variables jsonb NOT NULL DEFAULT '{}'::jsonb,
    sensitive_variables_ciphertext bytea,
    deduplication_key varchar(160) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    claim_token uuid,
    claimed_until timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    provider_request_id varchar(128),
    provider_biz_id varchar(128),
    sanitized_error varchar(500),
    sent_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_sms_delivery_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE SET NULL (user_id),
    CONSTRAINT ck_sms_delivery_user_scope CHECK (
        user_id IS NULL OR tenant_id IS NOT NULL
    ),
    CONSTRAINT ck_sms_delivery_status CHECK (
        status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'DEAD')
    ),
    CONSTRAINT ck_sms_delivery_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_sms_delivery_phone_last4 CHECK (
        phone_last4 ~ '^[0-9]{4}$'
    ),
    CONSTRAINT ck_sms_delivery_variables CHECK (
        jsonb_typeof(variables) = 'object'
    ),
    CONSTRAINT ck_sms_delivery_no_plaintext_code CHECK (
        NOT (variables ? 'code')
    ),
    CONSTRAINT ck_sms_delivery_claim CHECK (
        (status = 'SENDING' AND claim_token IS NOT NULL AND claimed_until IS NOT NULL)
        OR (status <> 'SENDING' AND claim_token IS NULL AND claimed_until IS NULL)
    ),
    CONSTRAINT ck_sms_delivery_sent CHECK (
        status <> 'SENT' OR sent_at IS NOT NULL
    )
);

CREATE UNIQUE INDEX uq_sms_delivery_deduplication
    ON platform.sms_delivery(deduplication_key);

CREATE INDEX ix_sms_delivery_claim
    ON platform.sms_delivery(status, next_attempt_at, claimed_until, created_at)
    WHERE status IN ('PENDING', 'FAILED', 'SENDING');

CREATE INDEX ix_sms_delivery_user
    ON platform.sms_delivery(user_id, created_at DESC)
    WHERE user_id IS NOT NULL;

INSERT INTO platform.sms_template(
    template_key, template_code, template_type, variable_names, integration_status)
VALUES
    ('REGISTER_CODE', 'SMS_511405090', 'VERIFICATION', '["code"]'::jsonb, 'ACTIVE'),
    ('LOGIN_CODE', 'SMS_511430077', 'VERIFICATION', '["code"]'::jsonb, 'PENDING_INTEGRATION'),
    ('PASSWORD_CODE', 'SMS_511175095', 'VERIFICATION', '["code"]'::jsonb, 'ACTIVE'),
    ('PAYMENT_CONFIRM_CODE', 'SMS_511390110', 'VERIFICATION', '["code"]'::jsonb, 'ACTIVE'),
    ('API_KEY_RESET_CODE', 'SMS_511455130', 'VERIFICATION', '["code"]'::jsonb, 'ACTIVE'),
    ('PHONE_CHANGE_CODE', 'SMS_511410115', 'VERIFICATION', '["code"]'::jsonb, 'ACTIVE'),
    ('BALANCE_RECHARGE_SUCCEEDED', 'SMS_511190106', 'NOTIFICATION', '["payMoney", "balance"]'::jsonb, 'PENDING_INTEGRATION'),
    ('PACKAGE_PAYMENT_SUCCEEDED', 'SMS_511335109', 'NOTIFICATION', '["payMoney", "expireDate"]'::jsonb, 'ACTIVE'),
    ('API_USAGE_DEDUCTED', 'SMS_511360123', 'NOTIFICATION', '["costMoney", "balance"]'::jsonb, 'PENDING_INTEGRATION'),
    ('BALANCE_LOW', 'SMS_511390111', 'NOTIFICATION', '["warnMoney"]'::jsonb, 'PENDING_INTEGRATION'),
    ('PACKAGE_EXPIRES_IN_3_DAYS', 'SMS_511440119', 'NOTIFICATION', '["expireDate"]'::jsonb, 'ACTIVE'),
    ('ACCOUNT_ANOMALY_FROZEN', 'SMS_511300116', 'NOTIFICATION', '[]'::jsonb, 'PENDING_INTEGRATION'),
    ('ACCOUNT_BANNED', 'SMS_511465117', 'NOTIFICATION', '[]'::jsonb, 'PENDING_INTEGRATION'),
    ('LOCATION_LOGIN_WARNING', 'SMS_511410119', 'NOTIFICATION', '["loginAddr"]'::jsonb, 'PENDING_INTEGRATION'),
    ('ORDER_PAYMENT_FAILED', 'SMS_511295121', 'NOTIFICATION', '["payMoney"]'::jsonb, 'ACTIVE'),
    ('PACKAGE_EXPIRED', 'SMS_511180141', 'NOTIFICATION', '[]'::jsonb, 'ACTIVE'),
    ('API_RATE_LIMITED', 'SMS_511495111', 'NOTIFICATION', '[]'::jsonb, 'ACTIVE'),
    ('ACCOUNT_UNFROZEN', 'SMS_511380117', 'NOTIFICATION', '[]'::jsonb, 'PENDING_INTEGRATION'),
    ('PLATFORM_MAINTENANCE', 'SMS_511280134', 'NOTIFICATION', '["maintainTime"]'::jsonb, 'PENDING_INTEGRATION'),
    ('REFUND_SUCCEEDED', 'SMS_511460123', 'NOTIFICATION', '["refundMoney"]'::jsonb, 'ACTIVE'),
    ('ORDER_TIMED_OUT', 'SMS_511260138', 'NOTIFICATION', '["orderNo"]'::jsonb, 'ACTIVE'),
    ('SUPPORT_TICKET_COMPLETED', 'SMS_511135150', 'NOTIFICATION', '["ticketNo"]'::jsonb, 'PENDING_INTEGRATION'),
    ('PACKAGE_PURCHASE_SUCCEEDED_FALLBACK', 'SMS_511410101', 'NOTIFICATION', '[]'::jsonb, 'RESERVED'),
    ('GENERIC_VERIFICATION', 'SMS_337520649', 'VERIFICATION', '["code"]'::jsonb, 'RESERVED');

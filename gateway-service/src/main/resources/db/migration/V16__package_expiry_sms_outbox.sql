CREATE TABLE gateway.package_expiry_sms_outbox (
    id uuid PRIMARY KEY,
    package_instance_id uuid NOT NULL
        REFERENCES gateway.package_instance(id) ON DELETE CASCADE,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    signal_state varchar(16) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    claim_token uuid,
    claimed_at timestamptz,
    sent_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_package_expiry_sms_state
        UNIQUE (package_instance_id, signal_state),
    CONSTRAINT ck_package_expiry_sms_signal_state
        CHECK (signal_state IN ('WARNING', 'EXPIRED')),
    CONSTRAINT ck_package_expiry_sms_status
        CHECK (status IN ('PENDING', 'DELIVERING', 'SENT', 'FAILED')),
    CONSTRAINT ck_package_expiry_sms_attempt_count
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_package_expiry_sms_claim
        CHECK ((status = 'DELIVERING' AND claim_token IS NOT NULL AND claimed_at IS NOT NULL)
            OR (status <> 'DELIVERING' AND claim_token IS NULL AND claimed_at IS NULL)),
    CONSTRAINT ck_package_expiry_sms_sent
        CHECK ((status = 'SENT' AND sent_at IS NOT NULL)
            OR (status <> 'SENT' AND sent_at IS NULL))
);

CREATE INDEX ix_package_expiry_sms_due
    ON gateway.package_expiry_sms_outbox (next_attempt_at, created_at, id)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX ix_package_expiry_sms_stale_claim
    ON gateway.package_expiry_sms_outbox (claimed_at, id)
    WHERE status = 'DELIVERING';

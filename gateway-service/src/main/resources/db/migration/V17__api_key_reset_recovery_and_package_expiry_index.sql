CREATE UNIQUE INDEX uq_api_key_identity_owner
    ON gateway.api_key (id, tenant_id, user_id);

CREATE TABLE gateway.api_key_reset_intent (
    operation_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    api_key_id uuid NOT NULL,
    proof_fingerprint varchar(64) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_api_key_reset_intent_owner
        FOREIGN KEY (api_key_id, tenant_id, user_id)
        REFERENCES gateway.api_key (id, tenant_id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_api_key_reset_intent_fingerprint
        CHECK (proof_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_api_key_reset_intent_status
        CHECK (status IN ('PENDING', 'COMPLETED')),
    CONSTRAINT ck_api_key_reset_intent_completion
        CHECK ((status = 'PENDING' AND completed_at IS NULL)
            OR (status = 'COMPLETED' AND completed_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_api_key_reset_intent_fingerprint
    ON gateway.api_key_reset_intent (proof_fingerprint);

CREATE UNIQUE INDEX uq_api_key_reset_intent_pending_key
    ON gateway.api_key_reset_intent (api_key_id)
    WHERE status = 'PENDING';

CREATE INDEX ix_api_key_reset_intent_owner_status
    ON gateway.api_key_reset_intent (tenant_id, user_id, api_key_id, status);

CREATE INDEX ix_package_instance_expiry_materialization
    ON gateway.package_instance (expires_at, id)
    INCLUDE (tenant_id, user_id)
    WHERE status IN ('ACTIVE', 'EXPIRED');

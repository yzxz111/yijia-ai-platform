CREATE TABLE platform.referral_code (
    id uuid PRIMARY KEY,
    inviter_tenant_id uuid NOT NULL,
    inviter_user_id uuid NOT NULL,
    code varchar(32) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    CONSTRAINT fk_referral_code_inviter FOREIGN KEY (inviter_tenant_id, inviter_user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_referral_code_owner_tuple UNIQUE (id, inviter_tenant_id, inviter_user_id),
    CONSTRAINT ck_referral_code_format CHECK (code ~ '^[A-Za-z0-9]{32}$'),
    CONSTRAINT ck_referral_code_revoked_at CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE UNIQUE INDEX uq_referral_code_active_inviter
    ON platform.referral_code(inviter_user_id) WHERE revoked_at IS NULL;

CREATE TABLE platform.referral_attribution (
    invitee_user_id uuid PRIMARY KEY REFERENCES platform.app_user(id) ON DELETE RESTRICT,
    inviter_tenant_id uuid NOT NULL,
    inviter_user_id uuid NOT NULL,
    referral_code_id uuid NOT NULL,
    registered_at timestamptz NOT NULL,
    CONSTRAINT fk_referral_attribution_inviter FOREIGN KEY (inviter_tenant_id, inviter_user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_referral_attribution_code_owner
        FOREIGN KEY (referral_code_id, inviter_tenant_id, inviter_user_id)
        REFERENCES platform.referral_code(id, inviter_tenant_id, inviter_user_id)
        ON DELETE RESTRICT
);

CREATE INDEX ix_referral_attribution_inviter_registered
    ON platform.referral_attribution(inviter_tenant_id, inviter_user_id,
        registered_at DESC, invitee_user_id DESC);

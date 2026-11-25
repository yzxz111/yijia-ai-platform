CREATE TABLE platform.account_balance (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES platform.app_user(id) ON DELETE RESTRICT,
    currency char(3) NOT NULL DEFAULT 'CNY',
    available_amount bigint NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_account_balance_owner_currency UNIQUE (tenant_id, user_id, currency),
    CONSTRAINT uq_account_balance_owner_id UNIQUE (tenant_id, user_id, id),
    CONSTRAINT fk_account_balance_user_scope FOREIGN KEY (tenant_id, user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_account_balance_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_account_balance_nonnegative CHECK (available_amount >= 0),
    CONSTRAINT ck_account_balance_version CHECK (version >= 0)
);

CREATE INDEX ix_account_balance_tenant_user
    ON platform.account_balance(tenant_id, user_id);

CREATE FUNCTION platform.reject_account_balance_identity_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
            OR NEW.user_id IS DISTINCT FROM OLD.user_id
            OR NEW.currency IS DISTINCT FROM OLD.currency THEN
        RAISE EXCEPTION 'account_balance identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_account_balance_identity_immutable
BEFORE UPDATE ON platform.account_balance
FOR EACH ROW EXECUTE FUNCTION platform.reject_account_balance_identity_mutation();

INSERT INTO platform.account_balance(
    id, tenant_id, user_id, currency, available_amount, version)
SELECT gen_random_uuid(), tenant_id, id, 'CNY', 0, 0
FROM platform.app_user
ON CONFLICT (tenant_id, user_id, currency) DO NOTHING;

CREATE TABLE platform.balance_ledger (
    id uuid PRIMARY KEY,
    entry_no varchar(64) NOT NULL UNIQUE,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    account_id uuid NOT NULL,
    entry_type varchar(32) NOT NULL,
    amount_delta bigint NOT NULL,
    paid_amount bigint NOT NULL DEFAULT 0,
    bonus_amount bigint NOT NULL DEFAULT 0,
    balance_before bigint NOT NULL,
    balance_after bigint NOT NULL,
    business_type varchar(32) NOT NULL,
    business_id uuid NOT NULL,
    business_no varchar(64) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    description varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_balance_ledger_owner_id UNIQUE (tenant_id, user_id, id),
    CONSTRAINT uq_balance_ledger_business UNIQUE (
        account_id, entry_type, business_type, business_id),
    CONSTRAINT uq_balance_ledger_idempotency UNIQUE (account_id, idempotency_key),
    CONSTRAINT fk_balance_ledger_account_scope FOREIGN KEY (tenant_id, user_id, account_id)
        REFERENCES platform.account_balance(tenant_id, user_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_balance_ledger_business_no CHECK (
        business_no ~ '[^[:space:]]'),
    CONSTRAINT ck_balance_ledger_idempotency_key CHECK (
        idempotency_key ~ '[^[:space:]]'),
    CONSTRAINT ck_balance_ledger_entry_type CHECK (
        entry_type IN ('RECHARGE', 'PACKAGE_PURCHASE', 'PACKAGE_REFUND')),
    CONSTRAINT ck_balance_ledger_delta CHECK (amount_delta <> 0),
    CONSTRAINT ck_balance_ledger_amounts CHECK (
        paid_amount >= 0 AND bonus_amount = 0),
    CONSTRAINT ck_balance_ledger_posting_shape CHECK (
        (
            entry_type = 'RECHARGE'
            AND business_type = 'RECHARGE_ORDER'
            AND amount_delta > 0
            AND paid_amount > 0
            AND bonus_amount = 0
            AND amount_delta::numeric = paid_amount::numeric + bonus_amount::numeric
        )
        OR
        (
            entry_type = 'PACKAGE_PURCHASE'
            AND business_type = 'ORDER'
            AND amount_delta < 0
            AND paid_amount = 0
            AND bonus_amount = 0
        )
        OR
        (
            entry_type = 'PACKAGE_REFUND'
            AND business_type = 'REFUND'
            AND amount_delta > 0
            AND paid_amount = 0
            AND bonus_amount = 0
        )),
    CONSTRAINT ck_balance_ledger_snapshots CHECK (
        balance_before >= 0
        AND balance_after >= 0
        AND balance_before + amount_delta = balance_after)
);

CREATE INDEX ix_balance_ledger_owner_created
    ON platform.balance_ledger(tenant_id, user_id, created_at DESC, id DESC);

CREATE INDEX ix_balance_ledger_account_created
    ON platform.balance_ledger(account_id, created_at DESC, id DESC);

CREATE INDEX ix_balance_ledger_business
    ON platform.balance_ledger(business_type, business_id);

CREATE FUNCTION platform.reject_balance_ledger_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'balance_ledger is append-only';
END;
$$;

CREATE TRIGGER trg_balance_ledger_immutable
BEFORE UPDATE OR DELETE ON platform.balance_ledger
FOR EACH ROW EXECUTE FUNCTION platform.reject_balance_ledger_mutation();

CREATE TABLE platform.recharge_option (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    amount bigint NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    display_order integer NOT NULL,
    created_by uuid,
    updated_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_recharge_option_tenant_amount UNIQUE (tenant_id, amount),
    CONSTRAINT uq_recharge_option_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_recharge_option_created_by FOREIGN KEY (tenant_id, created_by)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE SET NULL (created_by),
    CONSTRAINT fk_recharge_option_updated_by FOREIGN KEY (tenant_id, updated_by)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE SET NULL (updated_by),
    CONSTRAINT ck_recharge_option_amount CHECK (amount BETWEEN 100 AND 5000000),
    CONSTRAINT ck_recharge_option_display_order CHECK (display_order >= 0)
);

CREATE INDEX ix_recharge_option_tenant_enabled_order
    ON platform.recharge_option(tenant_id, enabled, display_order, amount);

INSERT INTO platform.recharge_option(id, tenant_id, amount, enabled, display_order)
SELECT gen_random_uuid(), tenant.tenant_id, defaults.amount, true, defaults.display_order
FROM (SELECT DISTINCT tenant_id FROM platform.app_user) AS tenant
CROSS JOIN (VALUES
    (1000::bigint, 10),
    (3000::bigint, 20),
    (5000::bigint, 30),
    (10000::bigint, 40),
    (20000::bigint, 50),
    (30000::bigint, 60),
    (50000::bigint, 70)
) AS defaults(amount, display_order)
ON CONFLICT (tenant_id, amount) DO NOTHING;

CREATE TABLE platform.recharge_order (
    id uuid PRIMARY KEY,
    recharge_no varchar(64) NOT NULL UNIQUE,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    option_id uuid,
    paid_amount bigint NOT NULL,
    bonus_amount bigint NOT NULL DEFAULT 0,
    credited_amount bigint NOT NULL,
    currency char(3) NOT NULL DEFAULT 'CNY',
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    idempotency_key varchar(128) NOT NULL,
    expires_at timestamptz NOT NULL,
    paid_at timestamptz,
    closed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_recharge_order_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_recharge_order_user_scope FOREIGN KEY (tenant_id, user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_recharge_order_option_scope FOREIGN KEY (tenant_id, option_id)
        REFERENCES platform.recharge_option(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_recharge_order_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_recharge_order_amounts CHECK (
        paid_amount BETWEEN 100 AND 5000000
        AND bonus_amount = 0
        AND credited_amount = paid_amount + bonus_amount),
    CONSTRAINT ck_recharge_order_status CHECK (
        status IN ('PENDING', 'PAID', 'CLOSED', 'FAILED')),
    CONSTRAINT ck_recharge_order_times CHECK (
        expires_at > created_at
        AND (paid_at IS NULL OR paid_at >= created_at)
        AND (closed_at IS NULL OR closed_at >= created_at)),
    CONSTRAINT ck_recharge_order_lifecycle CHECK (
        (status = 'PENDING' AND paid_at IS NULL AND closed_at IS NULL)
        OR (status = 'PAID' AND paid_at IS NOT NULL AND closed_at IS NULL)
        OR (status = 'CLOSED' AND paid_at IS NULL AND closed_at IS NOT NULL)
        OR status = 'FAILED')
);

CREATE INDEX ix_recharge_order_owner_created
    ON platform.recharge_order(tenant_id, user_id, created_at DESC, id DESC);

CREATE INDEX ix_recharge_order_pending_expiry
    ON platform.recharge_order(status, expires_at)
    WHERE status = 'PENDING';

ALTER TABLE platform.payment_transaction
    ALTER COLUMN order_id DROP NOT NULL,
    ADD COLUMN recharge_order_id uuid
        REFERENCES platform.recharge_order(id) ON DELETE RESTRICT,
    ADD CONSTRAINT uq_payment_order_transaction UNIQUE (order_id, id),
    ADD CONSTRAINT ck_payment_owner CHECK (
        (order_id IS NOT NULL)::integer
        + (recharge_order_id IS NOT NULL)::integer = 1);

ALTER TABLE platform.customer_order
    DROP CONSTRAINT fk_order_successful_payment,
    ADD CONSTRAINT fk_order_successful_payment
        FOREIGN KEY (id, successful_payment_transaction_id)
        REFERENCES platform.payment_transaction(order_id, id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_payment_active_attempt_per_recharge
    ON platform.payment_transaction(recharge_order_id)
    WHERE recharge_order_id IS NOT NULL AND status IN ('CREATED', 'PENDING');

CREATE UNIQUE INDEX uq_payment_applied_per_recharge
    ON platform.payment_transaction(recharge_order_id)
    WHERE recharge_order_id IS NOT NULL AND applied_to_order;

CREATE INDEX ix_payment_recharge_created
    ON platform.payment_transaction(recharge_order_id, created_at DESC)
    WHERE recharge_order_id IS NOT NULL;

ALTER TABLE platform.customer_order
    ADD COLUMN settlement_method varchar(16),
    ADD COLUMN balance_debit_ledger_id uuid,
    ADD CONSTRAINT fk_order_user_scope FOREIGN KEY (tenant_id, user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_order_balance_debit_scope
        FOREIGN KEY (tenant_id, user_id, balance_debit_ledger_id)
        REFERENCES platform.balance_ledger(tenant_id, user_id, id) ON DELETE RESTRICT;

UPDATE platform.customer_order
SET settlement_method = CASE
    WHEN status IN ('PAID', 'REFUNDED', 'PARTIALLY_REFUNDED') THEN 'EXTERNAL'
    ELSE 'UNSELECTED'
END;

ALTER TABLE platform.customer_order
    ALTER COLUMN settlement_method SET DEFAULT 'UNSELECTED',
    ALTER COLUMN settlement_method SET NOT NULL,
    ADD CONSTRAINT ck_order_settlement_method CHECK (
        settlement_method IN ('UNSELECTED', 'EXTERNAL', 'BALANCE')),
    ADD CONSTRAINT ck_order_balance_settlement CHECK (
        (settlement_method = 'BALANCE' AND balance_debit_ledger_id IS NOT NULL)
        OR (settlement_method <> 'BALANCE' AND balance_debit_ledger_id IS NULL));

CREATE INDEX ix_order_settlement_created
    ON platform.customer_order(tenant_id, settlement_method, created_at DESC);

ALTER TABLE platform.refund
    DROP CONSTRAINT refund_payment_transaction_id_fkey,
    ALTER COLUMN payment_transaction_id DROP NOT NULL,
    ADD COLUMN funding_source varchar(16),
    ADD COLUMN balance_refund_ledger_id uuid
        REFERENCES platform.balance_ledger(id) ON DELETE RESTRICT,
    ADD CONSTRAINT refund_payment_transaction_id_fkey
        FOREIGN KEY (order_id, payment_transaction_id)
        REFERENCES platform.payment_transaction(order_id, id) ON DELETE RESTRICT;

UPDATE platform.refund
SET funding_source = 'EXTERNAL';

ALTER TABLE platform.refund
    ALTER COLUMN funding_source SET DEFAULT 'EXTERNAL',
    ALTER COLUMN funding_source SET NOT NULL,
    ADD CONSTRAINT ck_refund_funding_source CHECK (
        funding_source IN ('EXTERNAL', 'BALANCE')),
    ADD CONSTRAINT ck_refund_funding_references CHECK (
        (
            funding_source = 'EXTERNAL'
            AND payment_transaction_id IS NOT NULL
            AND balance_refund_ledger_id IS NULL
        )
        OR
        (
            funding_source = 'BALANCE'
            AND payment_transaction_id IS NULL
            AND (status <> 'COMPLETED' OR balance_refund_ledger_id IS NOT NULL)
        ));

CREATE INDEX ix_refund_funding_created
    ON platform.refund(funding_source, created_at DESC);

CREATE FUNCTION platform.validate_order_wallet_ledger_reference()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.balance_debit_ledger_id IS NOT NULL OR EXISTS (
            SELECT 1
            FROM platform.balance_ledger AS ledger
            WHERE ledger.entry_type = 'PACKAGE_PURCHASE'
              AND ledger.business_type = 'ORDER'
              AND ledger.business_id = OLD.id) THEN
            RAISE EXCEPTION 'wallet-linked customer_order cannot be deleted';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.balance_debit_ledger_id IS NOT NULL
                AND NEW.balance_debit_ledger_id IS DISTINCT FROM OLD.balance_debit_ledger_id THEN
            RAISE EXCEPTION 'wallet-linked customer_order ledger reference is immutable';
        END IF;
        IF NEW.id IS DISTINCT FROM OLD.id AND (
                OLD.balance_debit_ledger_id IS NOT NULL OR EXISTS (
                    SELECT 1
                    FROM platform.balance_ledger AS ledger
                    WHERE ledger.entry_type = 'PACKAGE_PURCHASE'
                      AND ledger.business_type = 'ORDER'
                      AND ledger.business_id = OLD.id)) THEN
            RAISE EXCEPTION 'wallet-linked customer_order id is immutable';
        END IF;
    END IF;
    IF NEW.balance_debit_ledger_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1
            FROM platform.balance_ledger AS ledger
            WHERE ledger.id = NEW.balance_debit_ledger_id
              AND ledger.tenant_id = NEW.tenant_id
              AND ledger.user_id = NEW.user_id
              AND ledger.entry_type = 'PACKAGE_PURCHASE'
              AND ledger.business_type = 'ORDER'
              AND ledger.business_id = NEW.id) THEN
            RAISE EXCEPTION 'invalid order balance ledger reference';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION platform.validate_refund_wallet_ledger_reference()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.balance_refund_ledger_id IS NOT NULL OR EXISTS (
            SELECT 1
            FROM platform.balance_ledger AS ledger
            WHERE ledger.entry_type = 'PACKAGE_REFUND'
              AND ledger.business_type = 'REFUND'
              AND ledger.business_id = OLD.id) THEN
            RAISE EXCEPTION 'wallet-linked refund cannot be deleted';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.balance_refund_ledger_id IS NOT NULL
                AND NEW.balance_refund_ledger_id IS DISTINCT FROM OLD.balance_refund_ledger_id THEN
            RAISE EXCEPTION 'wallet-linked refund ledger reference is immutable';
        END IF;
        IF NEW.id IS DISTINCT FROM OLD.id AND (
                OLD.balance_refund_ledger_id IS NOT NULL OR EXISTS (
                    SELECT 1
                    FROM platform.balance_ledger AS ledger
                    WHERE ledger.entry_type = 'PACKAGE_REFUND'
                      AND ledger.business_type = 'REFUND'
                      AND ledger.business_id = OLD.id)) THEN
            RAISE EXCEPTION 'wallet-linked refund id is immutable';
        END IF;
    END IF;
    IF NEW.balance_refund_ledger_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1
            FROM platform.customer_order AS customer_order
            JOIN platform.balance_ledger AS ledger
              ON ledger.tenant_id = customer_order.tenant_id
             AND ledger.user_id = customer_order.user_id
            WHERE customer_order.id = NEW.order_id
              AND ledger.id = NEW.balance_refund_ledger_id
              AND ledger.entry_type = 'PACKAGE_REFUND'
              AND ledger.business_type = 'REFUND'
              AND ledger.business_id = NEW.id) THEN
            RAISE EXCEPTION 'invalid refund balance ledger reference';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_order_wallet_ledger_reference
BEFORE INSERT OR UPDATE OF id, tenant_id, user_id, balance_debit_ledger_id
ON platform.customer_order
FOR EACH ROW EXECUTE FUNCTION platform.validate_order_wallet_ledger_reference();

CREATE TRIGGER trg_order_wallet_ledger_delete
BEFORE DELETE ON platform.customer_order
FOR EACH ROW EXECUTE FUNCTION platform.validate_order_wallet_ledger_reference();

CREATE TRIGGER trg_refund_wallet_ledger_reference
BEFORE INSERT OR UPDATE OF id, order_id, balance_refund_ledger_id
ON platform.refund
FOR EACH ROW EXECUTE FUNCTION platform.validate_refund_wallet_ledger_reference();

CREATE TRIGGER trg_refund_wallet_ledger_delete
BEFORE DELETE ON platform.refund
FOR EACH ROW EXECUTE FUNCTION platform.validate_refund_wallet_ledger_reference();

UPDATE platform.sms_template
SET integration_status = 'ACTIVE', updated_at = now()
WHERE template_key = 'BALANCE_RECHARGE_SUCCEEDED';

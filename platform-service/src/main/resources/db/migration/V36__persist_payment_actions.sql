ALTER TABLE platform.payment_transaction
    ADD COLUMN payment_action jsonb;

ALTER TABLE platform.payment_transaction
    ADD CONSTRAINT ck_payment_action_object
        CHECK (payment_action IS NULL OR jsonb_typeof(payment_action) = 'object');

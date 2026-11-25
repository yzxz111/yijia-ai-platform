ALTER TABLE platform.sms_proof
    ADD COLUMN consumed_operation_id uuid,
    ADD CONSTRAINT ck_sms_proof_consumed_operation CHECK (
        consumed_operation_id IS NULL OR consumed_at IS NOT NULL
    );

CREATE UNIQUE INDEX uq_sms_proof_consumed_operation
    ON platform.sms_proof (consumed_operation_id)
    WHERE consumed_operation_id IS NOT NULL;

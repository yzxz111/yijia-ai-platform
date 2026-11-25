ALTER TABLE gateway.quota_reservation
    ADD COLUMN uncovered_tokens bigint NOT NULL DEFAULT 0,
    ADD COLUMN usage_quality varchar(20),
    ADD COLUMN release_reason varchar(40),
    ADD CONSTRAINT ck_reservation_uncovered CHECK (uncovered_tokens >= 0),
    ADD CONSTRAINT ck_reservation_usage_quality CHECK (
        usage_quality IS NULL OR usage_quality IN ('ACTUAL', 'ESTIMATED')
    );

ALTER TABLE gateway.quota_ledger
    ALTER COLUMN reservation_id DROP NOT NULL,
    ADD COLUMN source_package_instance_id uuid
        REFERENCES gateway.package_instance(id) ON DELETE RESTRICT,
    DROP CONSTRAINT ck_ledger_type,
    ADD CONSTRAINT ck_ledger_type CHECK (
        entry_type IN ('RESERVE', 'SETTLE', 'RELEASE', 'EXPIRE', 'SWITCH')
    ),
    ADD CONSTRAINT ck_ledger_scope CHECK (
        (entry_type = 'SWITCH'
            AND reservation_id IS NULL
            AND source_package_instance_id IS NOT NULL)
        OR
        (entry_type <> 'SWITCH'
            AND reservation_id IS NOT NULL
            AND source_package_instance_id IS NULL)
    );

CREATE OR REPLACE FUNCTION gateway.reject_quota_ledger_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'quota_ledger_immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_quota_ledger_immutable
BEFORE UPDATE OR DELETE ON gateway.quota_ledger
FOR EACH ROW EXECUTE FUNCTION gateway.reject_quota_ledger_mutation();

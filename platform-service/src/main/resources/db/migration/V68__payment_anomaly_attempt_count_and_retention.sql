-- Bound the growth of the invalid-callback anomaly evidence.
--
-- platform.payment_anomaly stores one OPEN row per distinct callback fingerprint, and the
-- CALLBACK_SIGNATURE_INVALID fingerprint used to be sha256(attacker-supplied body), so an attacker
-- could insert one permanent row per byte changed. This column records how many invalid callbacks a
-- single (provider, coarse time window) fingerprint collapsed, and the retention job added with this
-- migration deletes rows older than the configured retention period in bounded batches.

ALTER TABLE platform.payment_anomaly
    ADD COLUMN attempt_count bigint NOT NULL DEFAULT 1;

ALTER TABLE platform.payment_anomaly
    ADD CONSTRAINT ck_payment_anomaly_attempt_count
        CHECK (attempt_count >= 1);

-- Supports the retention job's "oldest first, bounded batch" delete. V7 already indexes
-- (status, created_at DESC); this one is ordered oldest-first with the id tiebreaker.
CREATE INDEX ix_payment_anomaly_retention
    ON platform.payment_anomaly(created_at, id);

COMMENT ON COLUMN platform.payment_anomaly.attempt_count IS
    'Invalid callbacks collapsed into this row by the windowed CALLBACK_SIGNATURE_INVALID dedup key';

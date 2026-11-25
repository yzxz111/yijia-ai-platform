ALTER TABLE platform.wallet_usage_reservation
    ADD COLUMN expires_at timestamptz;

UPDATE platform.wallet_usage_reservation
SET expires_at = created_at + interval '15 minutes'
WHERE expires_at IS NULL;

ALTER TABLE platform.wallet_usage_reservation
    ALTER COLUMN expires_at SET NOT NULL;

CREATE INDEX ix_wallet_usage_reservation_expiry
    ON platform.wallet_usage_reservation(expires_at, id)
    WHERE status = 'RESERVED';

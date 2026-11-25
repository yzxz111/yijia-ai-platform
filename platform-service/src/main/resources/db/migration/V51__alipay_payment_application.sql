ALTER TABLE platform.payment_transaction
    ADD COLUMN provider_app_id varchar(16),
    ADD CONSTRAINT ck_payment_provider_app_id
        CHECK (provider_app_id IS NULL OR (provider = 'ALIPAY' AND provider_app_id ~ '^[0-9]{16}$'));

-- Existing signed actions retain the application that originally created the payment.
UPDATE platform.payment_transaction
SET provider_app_id = payment_action #>> '{fields,app_id}'
WHERE provider = 'ALIPAY'
  AND (payment_action #>> '{fields,app_id}') ~ '^[0-9]{16}$';

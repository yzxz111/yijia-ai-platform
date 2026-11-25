CREATE INDEX ix_sms_delivery_phone_last4_created
    ON platform.sms_delivery(phone_last4, created_at DESC);

CREATE INDEX ix_sms_delivery_template_key_created
    ON platform.sms_delivery(template_key, created_at DESC);

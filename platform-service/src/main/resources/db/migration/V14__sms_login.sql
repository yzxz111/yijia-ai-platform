ALTER TABLE platform.sms_verification_challenge
    DROP CONSTRAINT ck_sms_challenge_purpose;

ALTER TABLE platform.sms_verification_challenge
    ADD CONSTRAINT ck_sms_challenge_purpose CHECK (
        purpose IN (
            'REGISTER', 'LOGIN', 'CHANGE_PASSWORD', 'PAYMENT_CONFIRM', 'API_KEY_RESET',
            'PHONE_BIND', 'PHONE_CHANGE'
        )
    );

UPDATE platform.sms_template
SET integration_status = 'ACTIVE', updated_at = now()
WHERE template_key = 'LOGIN_CODE'
  AND template_code = 'SMS_511430077'
  AND template_type = 'VERIFICATION';

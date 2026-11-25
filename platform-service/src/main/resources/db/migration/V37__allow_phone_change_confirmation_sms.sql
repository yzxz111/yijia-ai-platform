ALTER TABLE platform.sms_verification_challenge
    DROP CONSTRAINT ck_sms_challenge_purpose;

ALTER TABLE platform.sms_verification_challenge
    ADD CONSTRAINT ck_sms_challenge_purpose CHECK (
        purpose IN (
            'REGISTER', 'LOGIN', 'CHANGE_PASSWORD', 'PAYMENT_CONFIRM', 'API_KEY_RESET',
            'PHONE_BIND', 'PHONE_CHANGE', 'PHONE_CHANGE_CONFIRM'
        )
    );

ALTER TABLE platform.sms_proof
    DROP CONSTRAINT ck_sms_proof_purpose;

ALTER TABLE platform.sms_proof
    ADD CONSTRAINT ck_sms_proof_purpose CHECK (
        purpose IN (
            'REGISTER', 'CHANGE_PASSWORD', 'PAYMENT_CONFIRM', 'API_KEY_RESET',
            'PHONE_BIND', 'PHONE_CHANGE', 'PHONE_CHANGE_CONFIRM'
        )
    );

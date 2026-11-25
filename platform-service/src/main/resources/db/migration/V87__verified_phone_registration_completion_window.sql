ALTER TABLE platform.sms_verification_challenge
    DROP CONSTRAINT ck_sms_challenge_times;

ALTER TABLE platform.sms_verification_challenge
    ADD CONSTRAINT ck_sms_challenge_times CHECK (
        expires_at > created_at
        AND (
            expires_at <= created_at + interval '5 minutes'
            OR (
                purpose = 'REGISTER'
                AND verified_at IS NOT NULL
                AND expires_at <= verified_at + interval '10 minutes'
            )
        )
        AND (verified_at IS NULL OR (verified_at >= created_at AND verified_at <= expires_at))
        AND (consumed_at IS NULL OR (
            consumed_at >= created_at
            AND consumed_at <= expires_at
            AND verified_at IS NOT NULL
            AND consumed_at >= verified_at
        ))
    );

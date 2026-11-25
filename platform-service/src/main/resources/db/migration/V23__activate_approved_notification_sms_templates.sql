DO $$
DECLARE
    matched_count integer;
BEGIN
    SELECT count(*) INTO matched_count
    FROM platform.sms_template template
    JOIN (VALUES
        ('ACCOUNT_ANOMALY_FROZEN', 'SMS_511300116'),
        ('ACCOUNT_BANNED', 'SMS_511465117'),
        ('ACCOUNT_UNFROZEN', 'SMS_511380117'),
        ('API_USAGE_DEDUCTED', 'SMS_511360123'),
        ('BALANCE_LOW', 'SMS_511390111'),
        ('LOCATION_LOGIN_WARNING', 'SMS_511410119'),
        ('PLATFORM_MAINTENANCE', 'SMS_511280134'),
        ('SUPPORT_TICKET_COMPLETED', 'SMS_511135150')
    ) approved(template_key, template_code)
      ON approved.template_key = template.template_key
     AND approved.template_code = template.template_code
    WHERE template.template_type = 'NOTIFICATION'
      AND template.integration_status IN ('PENDING_INTEGRATION', 'ACTIVE');

    IF matched_count <> 8 THEN
        RAISE EXCEPTION 'approved SMS template catalog mismatch: expected 8, found %', matched_count;
    END IF;

    UPDATE platform.sms_template
    SET integration_status = 'ACTIVE', updated_at = now()
    WHERE template_key IN (
        'ACCOUNT_ANOMALY_FROZEN', 'ACCOUNT_BANNED', 'ACCOUNT_UNFROZEN',
        'API_USAGE_DEDUCTED', 'BALANCE_LOW', 'LOCATION_LOGIN_WARNING',
        'PLATFORM_MAINTENANCE', 'SUPPORT_TICKET_COMPLETED'
    );
END $$;

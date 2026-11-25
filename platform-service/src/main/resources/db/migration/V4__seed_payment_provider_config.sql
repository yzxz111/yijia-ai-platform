INSERT INTO platform.payment_config(
    id, provider, environment, enabled, encrypted_credentials)
VALUES
    ('00000000-0000-4000-8000-000000002001', 'MOCK', 'LOCAL', true, NULL),
    ('00000000-0000-4000-8000-000000002002', 'ALIPAY', 'PRODUCTION', false, NULL),
    ('00000000-0000-4000-8000-000000002003', 'WECHAT_PAY', 'PRODUCTION', false, NULL)
ON CONFLICT (provider, environment) DO NOTHING;

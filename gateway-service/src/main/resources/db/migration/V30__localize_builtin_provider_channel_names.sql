UPDATE gateway.provider AS provider
SET name = localized.name,
    updated_at = now()
FROM (VALUES
    ('bailian', 'Alibaba Bailian', '阿里云百炼'),
    ('local-mock', 'Local WireMock', '本地模拟服务')
) AS localized(code, original_name, name)
WHERE provider.code = localized.code
  AND provider.name = localized.original_name;

UPDATE gateway.channel AS channel
SET name = localized.name,
    updated_at = now()
FROM (VALUES
    ('bailian-primary', 'Bailian Primary', '百炼主渠道'),
    ('deepseek-primary', 'DeepSeek Primary', 'DeepSeek 主渠道'),
    ('local-mock-primary', 'Local Mock Primary', '本地模拟主渠道'),
    ('local-mock-failover', 'Local Mock Failover', '本地模拟故障转移渠道')
) AS localized(code, original_name, name)
WHERE channel.code = localized.code
  AND channel.name = localized.original_name;

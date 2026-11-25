UPDATE gateway.channel_model AS mapping
SET priority_override = 110,
    updated_at = now()
FROM gateway.channel AS channel
JOIN gateway.provider AS provider ON provider.id = channel.provider_id
CROSS JOIN gateway.model AS model
WHERE mapping.channel_id = channel.id
  AND mapping.model_id = model.id
  AND provider.code = 'bailian'
  AND model.code = 'text-basic'
  AND mapping.priority_override IS DISTINCT FROM 110;

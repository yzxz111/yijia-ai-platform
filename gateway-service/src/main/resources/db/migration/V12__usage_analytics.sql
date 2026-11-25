ALTER TABLE gateway.usage_log
    ADD COLUMN upstream_model varchar(128);

CREATE INDEX ix_usage_created_analytics
    ON gateway.usage_log (created_at DESC)
    INCLUDE (user_id, model_code, provider_id, upstream_model, input_tokens,
             output_tokens, total_tokens, outcome, platform_added_latency_ms);

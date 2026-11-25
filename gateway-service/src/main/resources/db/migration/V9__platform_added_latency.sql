ALTER TABLE gateway.usage_log
    ADD COLUMN platform_added_latency_ms bigint;

ALTER TABLE gateway.usage_log
    ADD CONSTRAINT ck_usage_platform_added_latency
    CHECK (platform_added_latency_ms IS NULL OR platform_added_latency_ms >= 0);

CREATE INDEX ix_usage_recent_platform_latency
    ON gateway.usage_log (tenant_id, user_id, created_at DESC)
    INCLUDE (platform_added_latency_ms)
    WHERE platform_added_latency_ms IS NOT NULL;

package com.yuzhihuihe.apiplatform.gateway.safety;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public final class ContentSafetyMetrics {
    private final MeterRegistry registry;
    public ContentSafetyMetrics(MeterRegistry registry) { this.registry = registry; }
    public void checked(String direction, String result) {
        Counter.builder("content_safety_checks_total").tag("direction", safe(direction))
                .tag("result", safe(result)).register(registry).increment();
    }
    public void streamStopped(String reason) {
        Counter.builder("content_safety_stream_stops_total").tag("reason", safe(reason))
                .register(registry).increment();
    }
    public void persistenceFailed(String kind) {
        Counter.builder("content_safety_persistence_failures_total").tag("kind", safe(kind))
                .register(registry).increment();
    }
    public void policySync(String result) {
        Counter.builder("content_safety_policy_sync_total").tag("result", safe(result))
                .register(registry).increment();
    }
    public void policyExpired() {
        Counter.builder("content_safety_policy_expired_total").register(registry).increment();
    }
    private String safe(String value) { return value == null || value.isBlank() ? "unknown" : value.toLowerCase(); }
}

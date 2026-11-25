package com.yuzhihuihe.apiplatform.gateway.quota;

import java.time.Duration;
import java.util.Set;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public final class PackageRefundMetrics {

    private static final Set<String> PREPARE_OUTCOMES = Set.of("READY", "WAITING", "REJECTED");
    private static final Set<String> RELEASE_OUTCOMES = Set.of("RELEASED", "REPLAY", "REJECTED");
    private static final Set<String> REVOKE_OUTCOMES = Set.of("REVOKED", "REPLAY", "REJECTED");

    private final MeterRegistry registry;

    public PackageRefundMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void prepare(String outcome) {
        counter("gateway.refund.prepare", PREPARE_OUTCOMES, outcome);
    }

    void release(String outcome) {
        counter("gateway.refund.release", RELEASE_OUTCOMES, outcome);
    }

    void revoke(String outcome) {
        counter("gateway.refund.revoke", REVOKE_OUTCOMES, outcome);
    }

    void holdWait(Duration duration) {
        registry.timer("gateway.refund.hold.wait")
                .record(duration.isNegative() ? Duration.ZERO : duration);
    }

    private void counter(String name, Set<String> allowed, String outcome) {
        String bounded = allowed.contains(outcome) ? outcome : "REJECTED";
        registry.counter(name, "outcome", bounded).increment();
    }
}

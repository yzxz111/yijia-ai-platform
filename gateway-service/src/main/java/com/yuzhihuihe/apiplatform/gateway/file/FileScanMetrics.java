package com.yuzhihuihe.apiplatform.gateway.file;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public final class FileScanMetrics {
    private final MeterRegistry registry;

    public FileScanMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void outcome(String outcome) {
        Counter.builder("gateway.file.scan").tag("outcome", safe(outcome))
                .register(registry).increment();
    }

    public void duration(Duration duration) {
        Timer.builder("gateway.file.scan.duration").register(registry).record(duration);
    }

    public void objectOperation(String operation, String outcome) {
        Counter.builder("gateway.file.oss.operation")
                .tag("operation", safe(operation)).tag("outcome", safe(outcome))
                .register(registry).increment();
    }

    public void orphan(String type) {
        Counter.builder("gateway.file.orphan.object").tag("type", safe(type))
                .register(registry).increment();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.toLowerCase();
    }
}

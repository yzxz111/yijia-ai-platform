package com.yuzhihuihe.apiplatform.gateway.file;

import java.util.Set;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public final class FileUploadMetrics {

    private static final String SESSION_METRIC = "gateway.file.upload_session";
    private static final String COMPLETE_METRIC = "gateway.file.direct_upload_complete";
    private static final Set<String> OUTCOMES = Set.of(
            "created", "replayed", "rejected", "failed");
    private static final Set<String> COMPLETE_OUTCOMES = Set.of(
            "accepted", "replayed", "missing", "mismatch", "failed");

    private final MeterRegistry registry;

    public FileUploadMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void record(String outcome) {
        String bounded = OUTCOMES.contains(outcome) ? outcome : "failed";
        registry.counter(SESSION_METRIC, "outcome", bounded).increment();
    }

    void recordComplete(String outcome) {
        String bounded = COMPLETE_OUTCOMES.contains(outcome) ? outcome : "failed";
        registry.counter(COMPLETE_METRIC, "outcome", bounded).increment();
    }
}

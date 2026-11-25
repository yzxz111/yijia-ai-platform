package com.yuzhihuihe.apiplatform.gateway.safety;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component("mockSemanticSafetyProvider")
public final class MockSemanticSafetyProvider implements SemanticSafetyProvider {

    @Override
    public Mono<SemanticSafetyResult> inspect(SafetyPayload payload, SafetyContext context) {
        String value = payload == null || payload.text() == null ? "" : payload.text().toLowerCase();
        if (value.contains("safety-test-unavailable")) {
            return Mono.just(SemanticSafetyResult.unavailable("MOCK"));
        }
        if (value.contains("safety-test-block")) {
            return Mono.just(SemanticSafetyResult.block("UNSAFE_CONTENT", "HIGH", "MOCK"));
        }
        return Mono.just(SemanticSafetyResult.allow("MOCK"));
    }
}

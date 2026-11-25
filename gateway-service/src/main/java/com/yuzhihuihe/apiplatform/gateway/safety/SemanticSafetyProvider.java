package com.yuzhihuihe.apiplatform.gateway.safety;

import reactor.core.publisher.Mono;

public interface SemanticSafetyProvider {

    Mono<SemanticSafetyResult> inspect(SafetyPayload payload, SafetyContext context);
}

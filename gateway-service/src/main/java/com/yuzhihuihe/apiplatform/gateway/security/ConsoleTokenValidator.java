package com.yuzhihuihe.apiplatform.gateway.security;

import reactor.core.publisher.Mono;

public interface ConsoleTokenValidator {

    Mono<Boolean> validate(String bearerToken);
}

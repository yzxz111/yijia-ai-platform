package com.yuzhihuihe.apiplatform.gateway.security;

import reactor.core.publisher.Mono;

public interface TerminalDeviceValidator {

    Mono<Boolean> validate(String bearerToken, String deviceId);
}

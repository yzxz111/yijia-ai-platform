package com.yuzhihuihe.apiplatform.gateway.security;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

@Component
final class PlatformTerminalDeviceValidator implements TerminalDeviceValidator {

    private final WebClient webClient;
    private final String validationUri;
    private final Duration timeout;

    PlatformTerminalDeviceValidator(
            WebClient.Builder webClientBuilder,
            @Value("${gateway.terminal-device-validation.uri:"
                    + "http://platform-service:8080/console/platform/auth/terminal-device/validate}")
            String validationUri,
            @Value("${gateway.terminal-device-validation.timeout:1s}") Duration timeout) {
        if (validationUri == null || validationUri.isBlank()) {
            throw new IllegalArgumentException("terminal_device_validation_uri_required");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("terminal_device_validation_timeout_invalid");
        }
        this.webClient = webClientBuilder.build();
        this.validationUri = validationUri;
        this.timeout = timeout;
    }

    @Override
    public Mono<Boolean> validate(String bearerToken, String deviceId) {
        if (bearerToken == null || bearerToken.isBlank()
                || deviceId == null || deviceId.isBlank()) {
            return Mono.just(false);
        }
        return webClient.get()
                .uri(validationUri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .header("X-Device-Id", deviceId)
                .exchangeToMono(response -> response.releaseBody()
                        .thenReturn(response.statusCode().is2xxSuccessful()))
                .timeout(timeout)
                .onErrorReturn(false)
                .defaultIfEmpty(false);
    }
}

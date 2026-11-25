package com.yuzhihuihe.apiplatform.gateway.safety;

import java.time.Duration;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component("httpSemanticSafetyProvider")
public final class HttpSemanticSafetyProvider implements SemanticSafetyProvider {

    private static final int MAX_PAYLOAD_CHARS = 1_000_000;

    private final WebClient client;
    private final Duration timeout;
    private final String credential;

    public HttpSemanticSafetyProvider(
            WebClient.Builder builder,
            ContentSafetyProperties properties) {
        this.client = builder.baseUrl(properties.getSemantic().getBaseUrl()).build();
        this.timeout = properties.getSemantic().getTotalTimeout();
        this.credential = properties.getSemanticCredential() == null
                ? "" : properties.getSemanticCredential();
    }

    @Override
    public Mono<SemanticSafetyResult> inspect(SafetyPayload payload, SafetyContext context) {
        if (payload == null || payload.text().length() > MAX_PAYLOAD_CHARS || credential.isBlank()) {
            return Mono.just(SemanticSafetyResult.unavailable("HTTP"));
        }
        return client.post().uri("/v1/inspect")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(credential))
                .bodyValue(Map.of(
                        "text", payload.text(), "role", payload.role(), "direction", payload.direction(),
                        "fileSha256", payload.fileSha256(), "requestId", String.valueOf(context.requestId()),
                        "policyVersion", context.policyVersion()))
                .retrieve()
                .bodyToMono(SemanticResponse.class)
                .timeout(timeout)
                .map(this::toResult)
                .onErrorReturn(SemanticSafetyResult.unavailable("HTTP"));
    }

    private SemanticSafetyResult toResult(SemanticResponse response) {
        if (response == null || response.result() == null) {
            return SemanticSafetyResult.unavailable("HTTP");
        }
        return switch (response.result().toUpperCase(java.util.Locale.ROOT)) {
            case "ALLOW" -> SemanticSafetyResult.allow("HTTP");
            case "BLOCK" -> SemanticSafetyResult.block(response.category(), response.severity(), "HTTP");
            default -> SemanticSafetyResult.unavailable("HTTP");
        };
    }

    private record SemanticResponse(String result, String category, String severity) {
    }
}

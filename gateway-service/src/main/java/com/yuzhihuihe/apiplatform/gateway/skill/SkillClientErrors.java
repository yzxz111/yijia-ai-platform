package com.yuzhihuihe.apiplatform.gateway.skill;

import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.openai.ChatCompletionController;
import com.yuzhihuihe.apiplatform.gateway.openai.OpenAiErrorMapper;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Builds the {@code /v1/**} OpenAI error envelope ({@code {"error":{…},"request_id":…}}) for the skill
 * client endpoints, including the {@code Retry-After} header of a throttled download session.
 */
final class SkillClientErrors {

    static final String REQUEST_ID_HEADER = "x-request-id";

    private SkillClientErrors() {}

    static String requestId(ServerWebExchange exchange) {
        return exchange.getAttributeOrDefault(
                ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE,
                "req_" + UUID.randomUUID().toString().replace("-", ""));
    }

    static ResponseEntity<ChatCompletionController.OpenAiErrorResponse> error(
            Throwable error, String requestId) {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(error);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(descriptor.status())
                .header(REQUEST_ID_HEADER, requestId)
                .contentType(MediaType.APPLICATION_JSON);
        if (error instanceof ResponseStatusException status
                && status.getHeaders() != null
                && !status.getHeaders().isEmpty()) {
            status.getHeaders().forEach((name, values) -> {
                if (!HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)) {
                    builder.header(name, values.toArray(String[]::new));
                }
            });
        }
        return builder.body(new ChatCompletionController.OpenAiErrorResponse(
                new ChatCompletionController.OpenAiError(
                        descriptor.message(), descriptor.type(), descriptor.code()),
                requestId));
    }

    /**
     * Writes the same envelope straight into a response that is already being streamed, so the
     * download endpoint can fail (for example {@code 416}) without committing a body.
     */
    static Mono<Void> write(
            ServerWebExchange exchange, Throwable error, String requestId, HttpHeaders extra) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return response.setComplete();
        }
        ResponseEntity<ChatCompletionController.OpenAiErrorResponse> entity = error(error, requestId);
        response.setStatusCode(entity.getStatusCode());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        entity.getHeaders().forEach((name, values) -> response.getHeaders().put(name, values));
        if (extra != null) {
            extra.forEach((name, values) -> response.getHeaders().put(name, values));
        }
        byte[] body;
        try {
            body = new ObjectMapper().writeValueAsBytes(entity.getBody());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("skill_error_serialization_failed", exception);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}


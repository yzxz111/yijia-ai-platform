package com.yuzhihuihe.apiplatform.gateway.openai;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import com.yuzhihuihe.apiplatform.gateway.safety.OutputSafetyInspector;
import com.yuzhihuihe.apiplatform.gateway.safety.SafetyStreamScanner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ResponsesController {

    private final ResponsesService service;
    private final OutputSafetyInspector outputSafety;
    private final SafetyStreamScanner streamSafety;

    @Autowired
    public ResponsesController(ResponsesService service, OutputSafetyInspector outputSafety,
            SafetyStreamScanner streamSafety) {
        this.service = service;
        this.outputSafety = outputSafety;
        this.streamSafety = streamSafety;
    }

    public ResponsesController(ResponsesService service) {
        this(service, null, null);
    }

    @PostMapping(path = "/v1/responses", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> create(
            @RequestBody JsonNode body, ServerWebExchange exchange) {
        String requestId = requestId(exchange);
        ApiKeyService.ApiKeyContext context =
                exchange.getAttribute(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE);
        return Mono.defer(() -> body.path("stream").asBoolean(false)
                        ? service.openStream(context, body, requestId)
                                .flatMap(FirstChunkGate::await)
                                .map(stream -> streamSafety.scan(stream, context, requestId))
                                .<ResponseEntity<?>>map(stream -> ResponseEntity.ok()
                                        .header("x-request-id", requestId)
                                        .contentType(MediaType.TEXT_EVENT_STREAM)
                                        .body(stream.map(data -> ServerSentEvent.builder(data).build())))
                        : service.complete(context, body, requestId)
                                .flatMap(response -> outputSafety.inspect(context, response, requestId)
                                        .flatMap(decision -> decision.blocked()
                                                ? Mono.error(new org.springframework.web.server.ResponseStatusException(
                                                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, decision.code()))
                                                : Mono.just(response)))
                                .<ResponseEntity<?>>map(response -> ResponseEntity.ok()
                                        .header("x-request-id", requestId)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(response)))
                .onErrorResume(error -> Mono.just(errorResponse(error, requestId)));
    }

    private ResponseEntity<ChatCompletionController.OpenAiErrorResponse> errorResponse(
            Throwable error, String requestId) {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(error);
        return ResponseEntity.status(descriptor.status())
                .headers(OpenAiErrorMapper.retryHeaders(error))
                .header("x-request-id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChatCompletionController.OpenAiErrorResponse(
                        new ChatCompletionController.OpenAiError(
                                descriptor.message(), descriptor.type(), descriptor.code()),
                        requestId));
    }

    private String requestId(ServerWebExchange exchange) {
        return exchange.getAttributeOrDefault(
                ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE,
                "req_" + UUID.randomUUID().toString().replace("-", ""));
    }
}

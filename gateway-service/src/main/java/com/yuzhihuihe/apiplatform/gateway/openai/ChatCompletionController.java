package com.yuzhihuihe.apiplatform.gateway.openai;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import com.yuzhihuihe.apiplatform.gateway.safety.OutputSafetyInspector;
import com.yuzhihuihe.apiplatform.gateway.safety.SafetyStreamScanner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ChatCompletionController {

    private static final String REQUEST_ID_HEADER = "x-request-id";

    private final CompletionDispatchService dispatcher;
    private final ChatCompletionService directService;
    private final OutputSafetyInspector outputSafety;
    private final SafetyStreamScanner streamSafety;
    private final ChatStreamHeartbeat heartbeat;

    @Autowired
    public ChatCompletionController(CompletionDispatchService dispatcher,
            OutputSafetyInspector outputSafety, SafetyStreamScanner streamSafety,
            ChatStreamHeartbeat heartbeat) {
        this.dispatcher = dispatcher;
        this.directService = null;
        this.outputSafety = outputSafety;
        this.streamSafety = streamSafety;
        this.heartbeat = heartbeat;
    }

    public ChatCompletionController(CompletionDispatchService dispatcher) {
        this(dispatcher, null, null, null);
    }

    ChatCompletionController(ChatCompletionService service) {
        this.dispatcher = null;
        this.directService = service;
        this.outputSafety = null;
        this.streamSafety = null;
        this.heartbeat = null;
    }

    @PostMapping(path = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> complete(
            @RequestBody JsonNode body, ServerWebExchange exchange) {
        String requestId = exchange.getAttributeOrDefault(
                ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, requestId());
        ApiKeyService.ApiKeyContext apiKey = exchange.getAttribute(
                ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE);
        return Mono.defer(() -> body.path("stream").asBoolean(false)
                        ? streamResponse(apiKey, body, requestId)
                        : jsonResponse(apiKey, body, requestId))
                .onErrorResume(error -> Mono.just(errorResponse(error, requestId)));
    }

    private Mono<ResponseEntity<?>> jsonResponse(
            ApiKeyService.ApiKeyContext apiKey, JsonNode body, String requestId) {
        Mono<ChatCompletionService.CompletionResult> completion = dispatcher == null
                ? directService.complete(apiKey, body, requestId)
                : dispatcher.complete(apiKey, body, requestId);
        return completion
                .flatMap(result -> outputSafety == null
                        ? Mono.just(result)
                        : outputSafety.inspect(apiKey, result.body(), requestId)
                                .flatMap(decision -> decision.blocked()
                                        ? Mono.error(new org.springframework.web.server.ResponseStatusException(
                                                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, decision.code()))
                                        : Mono.just(result)))
                .map(result -> ResponseEntity.ok()
                        .header(REQUEST_ID_HEADER, requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(result.body()));
    }

    private Mono<ResponseEntity<?>> streamResponse(
            ApiKeyService.ApiKeyContext apiKey, JsonNode body, String requestId) {
        Mono<Flux<String>> opened = dispatcher == null
                ? directService.openStream(apiKey, body, requestId)
                : dispatcher.openStream(apiKey, body, requestId);
        return opened
                .flatMap(FirstChunkGate::await)
                .map(stream -> streamSafety == null ? stream : streamSafety.scan(stream, apiKey, requestId))
                .map(stream -> {
                    // 心跳只加在安全扫描之后：它不进结算链路，也不该被当成内容送去审核。
                    Flux<ServerSentEvent<String>> events = heartbeat == null
                            ? stream.map(data -> ServerSentEvent.builder(data).build())
                            : heartbeat.attach(stream);
                    return ResponseEntity.ok()
                            .header(REQUEST_ID_HEADER, requestId)
                            .contentType(MediaType.TEXT_EVENT_STREAM)
                            .body(events);
                });
    }

    private ResponseEntity<OpenAiErrorResponse> errorResponse(Throwable error, String requestId) {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(error);
        return ResponseEntity.status(descriptor.status())
                .headers(OpenAiErrorMapper.retryHeaders(error))
                .header(REQUEST_ID_HEADER, requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OpenAiErrorResponse(
                        new OpenAiError(
                                descriptor.message(), descriptor.type(), descriptor.code(),
                                reason(error)),
                        requestId));
    }

    /**
     * 非流式错误信封里的受控原因码，与流式失败帧保持同一个取值来源。
     *
     * <p>加它是为了让两条失败路径说同一种话：客户端在流式下能拿到的
     * {@code provider_stream_missing_done} 这类原因，在非流式下不该凭空消失——否则同一个上游
     * 故障会因为调用方式不同而变得可诊断或不可诊断，而排障的人无法预期自己拿到哪一种。
     *
     * <p>为 null 时该键**被省略**（见 {@link OpenAiError} 的 {@code @JsonInclude}），
     * 不写出 {@code "reason":null}：客户端会把后者读成一个存在但为空的字段。
     */
    private String reason(Throwable error) {
        return error instanceof ProviderAdapter.ProviderException provider
                ? provider.reason() : null;
    }

    private String requestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }

    public record OpenAiErrorResponse(OpenAiError error, String request_id) { }

    /**
     * @param reason 受控原因码；没有可公开的原因码时该键**缺席**，而不是 null
     *        （{@code NON_NULL} 保证这一点，客户端因此不必区分「不存在」与「空」）
     */
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record OpenAiError(String message, String type, String code, String reason) {

        /**
         * 没有受控原因码时的三参形式。
         *
         * <p>保留它是因为其它能力域的控制器（视频、音频等）也复用这个信封，而它们的失败原因
         * 目前没有对应的安全原因码——给它们编一个假的反而会污染诊断。它们照旧只发前三段，
         * {@code reason} 键缺席。
         */
        public OpenAiError(String message, String type, String code) {
            this(message, type, code, null);
        }
    }

}

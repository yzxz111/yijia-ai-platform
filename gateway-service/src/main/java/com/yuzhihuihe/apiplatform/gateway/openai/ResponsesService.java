package com.yuzhihuihe.apiplatform.gateway.openai;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.file.ProviderFileBindingService;
import com.yuzhihuihe.apiplatform.gateway.file.ResponsesRequestPreparer;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ResponsesService {

    private final ResponsesRequestPreparer requestPreparer;
    private final CompletionDispatchService dispatcher;
    private final ChatCompletionService chat;
    private final ProviderFileBindingService bindings;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public ResponsesService(
            ResponsesRequestPreparer requestPreparer,
            CompletionDispatchService dispatcher,
            ProviderFileBindingService bindings,
            ObjectMapper objectMapper) {
        this(requestPreparer, dispatcher, bindings, objectMapper, Clock.systemUTC());
    }

    ResponsesService(
            ResponsesRequestPreparer requestPreparer,
            CompletionDispatchService dispatcher,
            ProviderFileBindingService bindings,
            ObjectMapper objectMapper,
            Clock clock) {
        this.requestPreparer = requestPreparer;
        this.dispatcher = dispatcher;
        this.chat = null;
        this.bindings = bindings;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    ResponsesService(
            ResponsesRequestPreparer requestPreparer,
            ChatCompletionService chat,
            ProviderFileBindingService bindings,
            ObjectMapper objectMapper,
            Clock clock) {
        this.requestPreparer = requestPreparer;
        this.dispatcher = null;
        this.chat = chat;
        this.bindings = bindings;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Mono<JsonNode> complete(
            ApiKeyService.ApiKeyContext context, JsonNode body, String requestId) {
        Mono<CompletionDispatchService.PreparedRequest> prepared = prepare(context, body);
        Mono<ChatCompletionService.CompletionResult> completion = dispatcher == null
                ? prepared.flatMap(request -> chat.completePrepared(
                        context, request.body(), requestId,
                        request.capabilities(), request.requestPreparer()))
                : dispatcher.completePrepared(context, body, requestId, prepared);
        return completion.map(this::toResponse);
    }

    public Mono<Flux<String>> openStream(
            ApiKeyService.ApiKeyContext context, JsonNode body, String requestId) {
        Mono<CompletionDispatchService.PreparedRequest> prepared = prepare(context, body);
        Mono<Flux<String>> stream = dispatcher == null
                ? prepared.flatMap(request -> chat.openPreparedStream(
                        context, request.body(), requestId,
                        request.capabilities(), request.requestPreparer()))
                : dispatcher.openPreparedStream(context, body, requestId, prepared);
        return stream
                .flatMap(FirstChunkGate::await)
                .map(chatStream -> responseEvents(chatStream, body.path("model").asText()));
    }

    private Mono<CompletionDispatchService.PreparedRequest> prepare(
            ApiKeyService.ApiKeyContext context, JsonNode body) {
        return Mono.defer(() -> requestPreparer.prepare(context, body))
                .map(prepared -> new CompletionDispatchService.PreparedRequest(
                        prepared.chatBody(), prepared.capabilities(), providerPreparer(prepared)));
    }

    private ChatCompletionService.TargetRequestPreparer providerPreparer(
            ResponsesRequestPreparer.PreparedResponseRequest prepared) {
        AtomicReference<Mono<JsonNode>> hydrated = new AtomicReference<>();
        return (providerBody, target, adapter) -> {
            Mono<JsonNode> current = hydrated.get();
            if (current == null) {
                Mono<JsonNode> candidate = requestPreparer.hydrateImages(providerBody).cache();
                hydrated.compareAndSet(null, candidate);
                current = hydrated.get();
            }
            return current.flatMap(body -> prepareProviderBody(prepared, body, target, adapter));
        };
    }

    Mono<JsonNode> prepareProviderBody(
            ResponsesRequestPreparer.PreparedResponseRequest prepared,
            JsonNode body,
            ChatCompletionService.RouteTarget target,
            ProviderAdapter adapter) {
        if (prepared.files().isEmpty()) {
            return Mono.just(body);
        }
        Map<String, String> upstreamIds = new LinkedHashMap<>();
        return Flux.fromIterable(prepared.files())
                .concatMap(file -> bindings.bind(file, adapter, target.channel())
                        .doOnNext(upstream -> upstreamIds.put(file.externalFileId(), upstream)))
                .then(Mono.fromSupplier(() -> rewriteFileIds(body, upstreamIds)));
    }

    JsonNode toResponse(ChatCompletionService.CompletionResult completion) {
        JsonNode chatBody = completion.body();
        String responseId = responseId();
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", responseId)
                .put("object", "response")
                .put("created_at", chatBody.path("created").asLong(clock.instant().getEpochSecond()))
                .put("status", "completed")
                .put("model", chatBody.path("model").asText());
        ObjectNode message = response.putArray("output").addObject();
        message.put("id", "msg_" + UUID.randomUUID().toString().replace("-", ""))
                .put("type", "message")
                .put("status", "completed")
                .put("role", "assistant");
        ObjectNode outputText = message.putArray("content").addObject();
        outputText.put("type", "output_text")
                .put("text", assistantText(chatBody))
                .set("annotations", objectMapper.createArrayNode());
        JsonNode usage = chatBody.path("usage");
        response.putObject("usage")
                .put("input_tokens", usage.path("prompt_tokens").asLong())
                .put("output_tokens", usage.path("completion_tokens").asLong())
                .put("total_tokens", usage.path("total_tokens").asLong());
        return response;
    }

    /**
     * 把聊天侧的 SSE 流翻译成 Responses 事件流。
     *
     * <p><b>失败必须压过完成。</b>聊天侧现在会在首段之后的失败上发送一帧
     * {@code {"error":{…}}} 然后正常结束流（见
     * {@link ChatCompletionResponseProtocol#failureFrame}）。这个转换层若不认识它，
     * 就会把错误帧当成一个「没有 {@code choices[0].delta.content} 的普通块」丢掉，
     * 再因为是正常 EOF 而追加 {@code response.completed} —— 于是断流被重新包装成
     * 一次成功，客户端只会看到「任务完成」而没有任何产出。
     *
     * <p>因此失败路径在 {@link #responseEventsFor} 里被显式识别，并在这里三件事一起做：
     * 发 {@code response.failed}、**抑制** {@code response.completed}、结束流
     * （不再把后续帧当成有效内容）。
     */
    private Flux<String> responseEvents(Flux<String> chatStream, String model) {
        String responseId = responseId();
        AtomicBoolean failed = new AtomicBoolean();
        AtomicBoolean truncated = new AtomicBoolean();
        ObjectNode created = objectMapper.createObjectNode();
        created.put("type", "response.created").putObject("response")
                .put("id", responseId).put("object", "response")
                .put("status", "in_progress").put("model", model);
        ObjectNode completed = objectMapper.createObjectNode();
        completed.put("type", "response.completed").putObject("response")
                .put("id", responseId).put("object", "response")
                .put("status", "completed").put("model", model);
        // **截断必须报 incomplete，不能报 completed。** 被输出上限切断的回答只写了一半，
        // 把它的终态说成 completed，与失败之后发 response.completed 是同一个谎话——
        // 客户端会把只做了一半的任务当成做完了。incomplete 是 Responses 里对应的终态。
        ObjectNode incomplete = objectMapper.createObjectNode();
        incomplete.put("type", "response.incomplete").putObject("response")
                .put("id", responseId).put("object", "response")
                .put("status", "incomplete").put("model", model)
                .putObject("incomplete_details")
                .put("reason", "max_output_tokens");
        return Flux.concat(
                Flux.just(write(created)),
                chatStream.concatMap(value ->
                        responseEventsFor(responseId, value, failed, truncated)),
                Flux.defer(() -> {
                    if (failed.get()) {
                        return Flux.empty();
                    }
                    return Flux.just(write(truncated.get() ? incomplete : completed));
                }));
    }

    private Flux<String> responseEventsFor(
            String responseId,
            String value,
            AtomicBoolean failed,
            AtomicBoolean truncated) {
        if ("[DONE]".equals(value)) {
            return Flux.empty();
        }
        try {
            JsonNode chunk = objectMapper.readTree(value);
            if (chat.isFailureFrame(chunk)) {
                failed.set(true);
                return Flux.just(write(failure(responseId, chunk)));
            }
            if (chat.isTruncatedFrame(chunk)) {
                truncated.set(true);
            }
            JsonNode delta = chunk.path("choices").path(0).path("delta").get("content");
            if (delta == null || !delta.isTextual() || delta.asText().isEmpty()) {
                return Flux.empty();
            }
            ObjectNode event = objectMapper.createObjectNode();
            event.put("type", "response.output_text.delta")
                    .put("response_id", responseId)
                    .put("output_index", 0)
                    .put("content_index", 0)
                    .put("delta", delta.asText());
            return Flux.just(write(event));
        } catch (Exception invalidChunk) {
            // 一个连 JSON 都不是的帧同样以失败事件收场，而不是把异常抛掉：抛出去会终结
            // 事件流、连 response.failed 都发不出来，客户端拿到的又是一个没有终态的断流。
            failed.set(true);
            return Flux.just(write(failure(responseId,
                    new ProviderAdapter.ProviderException(
                            502, "invalid_provider_stream_chunk"))));
        }
    }

    /** 由异常直接构造失败事件（用于没有现成失败帧可透传的解析失败）。 */
    private ObjectNode failure(String responseId, Throwable error) {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(error);
        ObjectNode envelope = objectMapper.createObjectNode();
        ObjectNode body = envelope.putObject("error");
        body.put("message", descriptor.message());
        body.put("type", descriptor.type());
        body.put("code", descriptor.code());
        if (error instanceof ProviderAdapter.ProviderException provider
                && provider.reason() != null) {
            body.put("reason", provider.reason());
        }
        return failure(responseId, envelope);
    }

    /**
     * 由聊天侧的失败帧生成 Responses 的 {@code response.failed} 事件。
     *
     * <p>错误码、消息与受控原因码原样透传：它们是这个转换层唯一能提供的诊断价值，
     * 而 {@code request_id} 让桌面能把这次失败与中台用量记录对齐（交接方案四.5 要求
     * 桌面保留并上报这个 ID）。
     */
    private ObjectNode failure(String responseId, JsonNode frame) {
        JsonNode error = frame.path("error");
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", responseId).put("object", "response")
                .put("status", "failed")
                .put("model", "");
        ObjectNode failure = response.putObject("error");
        failure.put("code", error.path("code").asText("provider_error"));
        failure.put("message", error.path("message").asText("Upstream provider request failed"));
        failure.put("type", error.path("type").asText("server_error"));
        if (error.path("reason").isTextual()) {
            failure.put("reason", error.path("reason").asText());
        }
        String requestId = frame.path("request_id").asText("");
        if (!requestId.isEmpty()) {
            failure.put("request_id", requestId);
        }
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "response.failed").set("response", response);
        return event;
    }

    private JsonNode rewriteFileIds(JsonNode source, Map<String, String> upstreamIds) {
        JsonNode copy = source.deepCopy();
        copy.path("messages").forEach(message -> message.path("content").forEach(part -> {
            if (part.isObject() && "input_file".equals(part.path("type").asText())) {
                String externalId = part.path("file_id").asText();
                String upstreamId = upstreamIds.get(externalId);
                if (upstreamId == null || upstreamId.isBlank()) {
                    throw new IllegalStateException("provider_file_binding_missing");
                }
                ((ObjectNode) part).put("file_id", upstreamId);
            }
        }));
        return copy;
    }

    private String assistantText(JsonNode chatBody) {
        JsonNode content = chatBody.path("choices").path(0).path("message").get("content");
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder text = new StringBuilder();
        content.forEach(part -> {
            if (part.path("text").isTextual()) {
                text.append(part.path("text").asText());
            }
        });
        return text.toString();
    }

    private String responseId() {
        return "resp_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception impossible) {
            throw new IllegalStateException("responses_serialization_failed", impossible);
        }
    }
}

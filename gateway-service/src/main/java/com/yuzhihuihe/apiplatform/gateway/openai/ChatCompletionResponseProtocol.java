package com.yuzhihuihe.apiplatform.gateway.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import java.time.Instant;
import java.util.Iterator;
import org.springframework.stereotype.Component;

@Component
public final class ChatCompletionResponseProtocol {

    /**
     * 被长度/预算截断时的 {@code finish_reason}，与 OpenAI 的取值一致。
     *
     * <p>它是这个类里唯一对外的「回答没写完」标记，因此只定义一次：生产截断标记的 {@link #markTruncated} 与识别它的 {@code ResponsesService}
     * 必须用同一个字面量， 否则两边会悄悄错开——一边发 {@code length}，另一边不认识，于是又一次把截断读成完成。
     */
    static final String FINISH_REASON_LENGTH = "length";

    private final ObjectMapper objectMapper;

    ChatCompletionResponseProtocol(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void validateCompletion(JsonNode source) {
        if (!hasResponseMetadata(source)
                || !"chat.completion".equals(source.path("object").asText())
                || !source.path("choices").isArray()
                || source.path("choices").isEmpty()) {
            throw invalidCompletion();
        }
        source.path("choices")
                .forEach(
                        choice -> {
                            if (!choice.isObject()
                                    || !choice.path("index").isIntegralNumber()
                                    || !choice.path("message").isObject()
                                    || !"assistant"
                                            .equals(choice.path("message").path("role").asText())
                                    || !choice.path("finish_reason").isTextual()
                                    || choice.path("finish_reason").asText().isBlank()) {
                                throw invalidCompletion();
                            }
                        });
    }

    JsonNode rewriteCompletion(
            JsonNode source, String modelAlias, ProviderAdapter.TokenUsage usage) {
        ObjectNode copy = rewriteModel(source, modelAlias);
        copy.putObject("usage")
                .put("prompt_tokens", usage.inputTokens())
                .put("completion_tokens", usage.outputTokens())
                .put("total_tokens", usage.totalTokens());
        return copy;
    }

    /**
     * 一个已解析的上游块是不是**结构上可接受的**流式块。
     *
     * <p><b>{@code choices:[]} 一律合法，无论有没有 {@code usage}。</b> 这条规则有一个测出来的来历：
     * 2026-10-16 的生产排查发现，上游天翼云在**输出被长度截断**时会在结束块的前一格多发一个
     * <em>既没有 choices 元素、也没有 usage</em> 的块：
     *
     * <pre>
     * {"id":"...","object":"chat.completion.chunk","model":"deepseek-v4.1-flash","choices":[]}
     * </pre>
     *
     * <p>它是分隔符性质的无操作帧，不携带任何会交付给客户的内容。而此前这里要求「{@code choices}
     * 为空时必须带 {@code usage}」，于是这一帧被判成协议违规。炸点在响应**已经提交之后**
     * （HTTP 200 与 {@code text/event-stream} 早已发出），因此状态码改不了，只能把一条**已经
     * 在产出内容**的流收尾成失败帧：客户端拿到半个回答、没有 {@code [DONE]}，并且这次截断
     * 从未被标记过（{@code finish_reason=length} 就在紧接的下一块里，而网关在读到它之前就抛了）。
     * 更糟的是那个原因码在下游丢成了 {@code unknown_stream_failure}，排查只能从「上游流中断」
     * 这个词往回猜。
     *
     * <p><b>为什么放宽是安全的。</b> 空 {@code choices} 不产出内容，因此放行它不会让任何东西
     * 漏给客户：带 {@code usage} 的用量尾帧走结算后由调用方丢弃（见 {@code ChatCompletionService}
     * 的流式映射），不带的同样被丢弃。要求它必须带 {@code usage} 换不来任何安全性——这一帧
     * 无法伪造内容，只能换来一次误杀。真正该拒绝的仍然是下面这些：元数据缺失、{@code choices}
     * 不是数组、以及内容块里缺 {@code index} 或 {@code delta}。
     *
     * <p><b>放宽的代价由调用方那两条兜底承担，缺一不可。</b> 放宽之后，一条全由无操作帧组成的坏流
     * 不再于第一帧失败，因此必须有人拦住它，否则它会一路跑到 {@code [DONE]}，把一个零内容的上游故障
     * 包装成一次成功——而 {@code [DONE]} 的语义正是「这段回答完整地结束了」。两条判据按「首字节
     * 之前 / 之后」分工，都落在 {@code ChatCompletionService} 一侧：
     *
     * <ul>
     *   <li><b>一帧都没转发出去</b>（整条流只发空 {@code choices}）：由执行器既有的
     *       {@code empty_provider_stream} 守卫在**首字节之前**拦下，因此这次失败还换得了渠道。
     *   <li><b>转发过帧，但既没有内容、也没有任何终止信号</b>：响应已经提交、换不了渠道，
     *       由收尾处的同一条零内容保障收成失败帧。
     * </ul>
     *
     * <p>反过来，一次**合法的空回答**（结束帧的 delta 是空的、{@code finish_reason=stop}）必须照旧
     * 成功收尾：它同样一个字都没有，但上游明确宣告了这条流结束了。按「没有内容」一刀切会把这种
     * 正常结果报成上游故障，那是相反方向的谎。
     */
    void validateStreamChunk(JsonNode source) {
        if (!hasResponseMetadata(source) || !source.path("choices").isArray()) {
            throw invalidStreamChunk();
        }
        if (source.path("choices").isEmpty()) {
            return;
        }
        source.path("choices")
                .forEach(
                        choice -> {
                            if (!choice.isObject()
                                    || !choice.path("index").isIntegralNumber()
                                    || !choice.path("delta").isObject()) {
                                throw invalidStreamChunk();
                            }
                        });
    }

    /*
     * 返回类型是 ObjectNode 而不是 JsonNode：调用方需要就地改写它（见 markTruncated），
     * 而这里本来就一定是对象。签名如实表达这一点，调用方就不必再强转一次。
     */
    ObjectNode rewriteStreamChunk(
            JsonNode source, String modelAlias, String requestId, Instant startedAt) {
        ObjectNode copy = rewriteModel(source, modelAlias);
        copy.put("id", completionId(requestId));
        copy.put("object", "chat.completion.chunk");
        copy.put("created", startedAt.getEpochSecond());
        copy.remove("usage");
        return copy;
    }

    JsonNode usageChunk(
            String modelAlias,
            String requestId,
            Instant startedAt,
            ProviderAdapter.TokenUsage usage) {
        ObjectNode chunk = objectMapper.createObjectNode();
        chunk.put("id", completionId(requestId));
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", startedAt.getEpochSecond());
        chunk.put("model", modelAlias);
        chunk.putArray("choices");
        chunk.putObject("usage")
                .put("prompt_tokens", usage.inputTokens())
                .put("completion_tokens", usage.outputTokens())
                .put("total_tokens", usage.totalTokens());
        return chunk;
    }

    /*
     * HTTP 响应已经提交（首段已发出）之后，用来宣告失败的 SSE 帧。
     *
     * 为什么不能只把异常抛出去了事：到这一步 HTTP 200 与
     * {@code Content-Type: text/event-stream} 早已发出，状态码再也改不了；
     * 把未处理的响应异常抛给已提交的响应，客户端的观感是「连接被重置」
     * ——2026-10-14 桌面 A 样本记录的正是 {@code ECONNRESET}。客户端因此既拿不到
     * 原因，也没有可解析的终态，只能把一次失败读成一次可疑的成功或一次传输抖动。
     *
     * 形状与 /v1/** 的非流式错误信封一致（error.code / message / type 加上
     * request_id），因此客户端只需要一个解析器就能同时处理两种失败形态。code 恒为
     * 客户端可照做的稳定分类（见 OpenAiErrorMapper），reason 是网关自己写下的受控
     * 原因码（见 ProviderException#reason），供客户端日志与中台用量记录对齐——
     * 它不含上游原文、请求体或凭据。
     *
     * 本帧之后不发 [DONE]：[DONE] 的含义是「这段回答完整地结束了」；在失败之后发它，
     * 正是把一次断流重新包装成一次成功。
     */
    JsonNode failureFrame(Throwable error, String requestId) {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(error);
        ObjectNode envelope = objectMapper.createObjectNode();
        ObjectNode body = envelope.putObject("error");
        body.put("message", descriptor.message());
        body.put("type", descriptor.type());
        body.put("code", descriptor.code());
        if (error instanceof ProviderAdapter.ProviderException provider
                && provider.reason() != null) {
            // 必须显式判空：ObjectNode.put(key, (String) null) 会写下一个 JSON null，
            // 而不是省略这个键。客户端会把 "reason":null 读成一个存在但为空的字段，
            // 而这里的 null 语义是「没有可安全公开的受控原因码」——**缺席**才是正确表达。
            body.put("reason", provider.reason());
        }
        envelope.put("request_id", requestId);
        return envelope;
    }

    /*
     * 一个已序列化的帧是不是上面那种失败帧。
     *
     * 转换层（/v1/responses）据此把聊天侧的失败
     * 翻译成自己的失败事件，而不是把错误帧当成
     * 一个「没有 delta 的普通块」丢掉——那正是它
     * 在失败之后仍然发出 response.completed 的原因。
     */
    boolean isFailureFrame(JsonNode value) {
        return value != null && value.isObject() && value.path("error").isObject();
    }

    /*
     * 命中输出上限时，把**最后一个**内容块的 finish_reason 改成 length。
     *
     * 为什么必须做：网关会在收到的输出达到预留容量时主动切断上游流
     * （见 ChatCompletionService 的 takeUntil(outputCapReached)）。被切断的流如果照旧以
     * [DONE] 收尾，客户端读到的是「这段回答完整结束了」——而它其实只写了一半。这与本次
     * 修掉的「失败之后发 [DONE]」是同一类假完成信号，只是走的是第二条通路：桌面会把一个
     * 只做了一半的任务报成完成。
     *
     * 只改 finish_reason，不动内容与其它元数据；并且只有当流**确实产出了内容**
     * （truncatedByOutputCap，而不是零额度从未开始）时才该调用它——否则就是在为一个不存在
     * 的回答编造「写了一半」。
     *
     * finalChunk 由 ChatCompletionService 保证是一个可变的 ObjectNode（先 deepCopy 再改），
     * 因此这里就地改写不会污染上游那一帧。
     */
    void markTruncated(JsonNode finalChunk) {
        if (finalChunk == null || !finalChunk.isObject()) {
            return;
        }
        ArrayNode choices = (ArrayNode) finalChunk.get("choices");
        if (choices == null || choices.isEmpty()) {
            return;
        }
        choices.forEach(
                choice -> {
                    if (choice.isObject()) {
                        ((ObjectNode) choice).put("finish_reason", FINISH_REASON_LENGTH);
                    }
                });
    }

    /**
     * 一个已解析的块是否宣告了「被长度截断」。
     *
     * <p><b>{@code choices} 为空是合法输入，必须当成「没有截断信号」而不是崩。</b> 网关自己就会发出这种帧：开启了 {@code
     * stream_options.include_usage} 时， 流的最后一帧是只有 {@code usage}、{@code choices:[]} 的用量块 （见 {@link
     * #usageChunk}）。此前这里用 {@code get(0)} 取首元素，空数组上它返回 {@code null}，紧接着的 {@code .path(...)} 直接抛
     * NPE；调用方 （{@code ResponsesService}）把一个宽泛的 catch 当成「非法块」，于是**一次已经 完整成功、内容和 [DONE] 都齐了的流被报成
     * {@code response.failed}**—— 恰好是本次要消灭的「假失败」，只是方向相反。
     *
     * <p>改用 {@code path(0)}：对空数组返回 {@code MissingNode}，后续 {@code asText("")}
     * 得到空串，自然判定为「没有宣告截断」。因此这个判断对空数组是 false，而不是异常。
     */
    boolean isTruncated(JsonNode value) {
        return value != null
                && value.path("choices").isArray()
                && value.path("choices")
                        .path(0)
                        .path("finish_reason")
                        .asText("")
                        .equals(FINISH_REASON_LENGTH);
    }

    /**
     * 一个块里宣告的结束原因；没有就说 null。
     *
     * <p>给 T5 的诊断字段用：一次被输出上限截断的请求，在 {@code outcome}、 {@code http_status}、{@code error_summary}
     * 三个字段上都与"自然结束"完全一样， 唯一能把它区分开的就是上游这一帧上的 {@code finish_reason}（或网关自己改写成的 {@code
     * length}）。用量行因此必须把它记下来。
     *
     * <p>与 {@link #isTruncated} 同样按空数组安全处理：网关自己发出的用量尾帧就是 {@code choices:[]}，那种块没有结束原因，应当是 null
     * 而不是异常。
     */
    String finishReasonOf(JsonNode value) {
        if (value == null || !value.path("choices").isArray()) {
            return null;
        }
        String reason = value.path("choices").path(0).path("finish_reason").asText("");
        return reason.isEmpty() ? null : reason;
    }

    /**
     * 这一块是否携带**会交付给客户的**内容。
     *
     * <p>它是 {@link #validateStreamChunk} 放宽 {@code choices:[]} 之后的配套判据：那个放宽让
     * 一条只由无操作帧组成的流不再于第一帧失败，于是必须有人回答「整条流到底交付过内容没有」。
     * 这个判定不能退化成「块数大于零」——空 {@code choices}、usage 尾帧、以及只有
     * {@code finish_reason} 的结束帧都是合法的非内容帧，一条流可以全由它们构成而客户一个字都没拿到。
     *
     * <p><b>判据刻意按「有东西」而不是按「白名单字段非空」。</b> {@code delta} 里放什么由上游决定：
     * {@code content}（正文）、{@code reasoning_content}（推理链，本渠道实测在用）、
     * {@code tool_calls}（工具调用）都算交付，而将来多一个字段不该让一段真实回答被当成空流。
     * 因此除 {@code role} 这个纯粹的信封字段之外，任何一个值非空的属性都算内容；全为空对象
     * （{@code "delta":{}}）或全为空白字符串的块不算。
     */
    boolean hasDeliverableContent(JsonNode value) {
        if (value == null || !value.path("choices").isArray()) {
            return false;
        }
        for (JsonNode choice : value.path("choices")) {
            if (!choice.isObject() || !choice.path("delta").isObject()) {
                continue;
            }
            JsonNode delta = choice.path("delta");
            Iterator<String> names = delta.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if ("role".equals(name)) {
                    continue;
                }
                if (carriesSomething(delta.path(name))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean carriesSomething(JsonNode value) {
        if (value.isTextual()) {
            return !value.asText().isBlank();
        }
        if (value.isArray() || value.isObject()) {
            return !value.isEmpty();
        }
        return !value.isMissingNode() && !value.isNull();
    }

    String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("chat_chunk_serialization_failed", exception);
        }
    }

    private ObjectNode rewriteModel(JsonNode source, String modelAlias) {
        if (source == null || !source.isObject()) {
            throw invalidCompletion();
        }
        ObjectNode copy = ((ObjectNode) source).deepCopy();
        copy.put("model", modelAlias);
        return copy;
    }

    private boolean hasResponseMetadata(JsonNode source) {
        return source != null
                && source.isObject()
                && source.path("id").isTextual()
                && !source.path("id").asText().isBlank()
                && source.path("object").isTextual()
                && !source.path("object").asText().isBlank()
                && source.path("created").isIntegralNumber()
                && source.path("model").isTextual()
                && !source.path("model").asText().isBlank();
    }

    private String completionId(String requestId) {
        return "chatcmpl-" + (requestId.startsWith("req_") ? requestId.substring(4) : requestId);
    }

    private ProviderAdapter.ProviderException invalidCompletion() {
        return new ProviderAdapter.ProviderException(502, "invalid_provider_response");
    }

    private ProviderAdapter.ProviderException invalidStreamChunk() {
        return new ProviderAdapter.ProviderException(502, "invalid_provider_stream_chunk");
    }
}

package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChatCompletionResponseProtocolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant STARTED_AT = Instant.ofEpochSecond(1_700_000_000L);

    private final ChatCompletionResponseProtocol protocol =
            new ChatCompletionResponseProtocol(JSON);

    @Test
    void rewritesCompletionModelAndUsageWithoutMutatingProviderResponse() {
        ObjectNode providerResponse = completion();
        providerResponse.putObject("usage")
                .put("prompt_tokens", 90)
                .put("completion_tokens", 9)
                .put("total_tokens", 99);

        protocol.validateCompletion(providerResponse);
        JsonNode rewritten = protocol.rewriteCompletion(
                providerResponse, "text-basic", new ProviderAdapter.TokenUsage(10, 20, 30));

        assertThat(rewritten.path("model").asText()).isEqualTo("text-basic");
        assertThat(rewritten.path("usage").path("prompt_tokens").asLong()).isEqualTo(10);
        assertThat(rewritten.path("usage").path("completion_tokens").asLong()).isEqualTo(20);
        assertThat(rewritten.path("usage").path("total_tokens").asLong()).isEqualTo(30);
        assertThat(providerResponse.path("model").asText()).isEqualTo("provider-model");
        assertThat(providerResponse.path("usage").path("total_tokens").asLong()).isEqualTo(99);
    }

    /**
     * {@code choices:[]} 上不得抛异常——它是网关自己会发出的合法帧。
     *
     * <p>这条用例补的是一个**没有直接测试**的方法：{@code isTruncated} 改前用
     * {@code get(0)} 取首元素，空数组上返回 {@code null}，紧随的 {@code .path(...)}
     * 直接抛 NPE。
     *
     * <p>触发它的正是这段代码自己写出的帧：开启 {@code stream_options.include_usage}
     * 时，流的最后一帧是只有 {@code usage}、{@code choices:[]} 的用量尾帧
     * （见 {@link ChatCompletionResponseProtocol#usageChunk}）。转换层
     * （{@code ResponsesService}）会把那个 NPE 当成"非法块"，于是一次**内容与
     * {@code [DONE]} 都齐了的成功流被报成 {@code response.failed}** ——
     * 与本次要消灭的"假完成"方向相反的"假失败"。
     *
     * <p>协议测试之所以没能拦住它，是因为 {@code ResponsesContractTest} 把
     * {@code isTruncatedFrame} 打了桩（自己的宽松实现用 {@code path(0)}），
     * 于是真实方法从来没被跑到。这条用例直接打真实方法。
     */
    @Test
    void treatsEmptyChoicesAsNoTruncationInsteadOfThrowing() {
        // 用量尾帧：只有 usage，choices 是空数组。这是网关自己的产物，不是畸形数据。
        ObjectNode usageOnly = JSON.createObjectNode();
        usageOnly.putArray("choices");
        usageOnly.putObject("usage").put("total_tokens", 7);

        assertThat(protocol.isTruncated(usageOnly)).isFalse();

        // 连 choices 字段都没有的块同样不能被当成截断，也不该崩。
        ObjectNode withoutChoices = JSON.createObjectNode();
        assertThat(protocol.isTruncated(withoutChoices)).isFalse();

        // null 是调用方可能的输入，必须安全。
        assertThat(protocol.isTruncated(null)).isFalse();

        // 对照：真正的截断块必须仍然被认出来，否则这条修复会把功能改没。
        ObjectNode truncated = JSON.createObjectNode();
        truncated.putArray("choices").addObject().put("finish_reason", "length");
        assertThat(protocol.isTruncated(truncated)).isTrue();

        // 而正常结束的块不是截断。
        ObjectNode stopped = JSON.createObjectNode();
        stopped.putArray("choices").addObject().put("finish_reason", "stop");
        assertThat(protocol.isTruncated(stopped)).isFalse();
    }

    @Test
    void rejectsMalformedCompletionWithStableProviderError() {        ObjectNode malformed = completion();
        ((ObjectNode) malformed.withArray("choices").get(0)).remove("finish_reason");

        assertProviderFailure(
                () -> protocol.validateCompletion(malformed), "invalid_provider_response");
    }

    @Test
    void normalizesStreamMetadataAndRemovesProviderUsageWithoutMutation() {
        ObjectNode providerChunk = streamChunk();
        providerChunk.putObject("usage")
                .put("prompt_tokens", 1)
                .put("completion_tokens", 2)
                .put("total_tokens", 3);

        protocol.validateStreamChunk(providerChunk);
        JsonNode rewritten = protocol.rewriteStreamChunk(
                providerChunk, "text-basic", "req_00000000000000000000000000000001", STARTED_AT);

        assertThat(rewritten.path("id").asText())
                .isEqualTo("chatcmpl-00000000000000000000000000000001");
        assertThat(rewritten.path("object").asText()).isEqualTo("chat.completion.chunk");
        assertThat(rewritten.path("created").asLong()).isEqualTo(STARTED_AT.getEpochSecond());
        assertThat(rewritten.path("model").asText()).isEqualTo("text-basic");
        assertThat(rewritten.has("usage")).isFalse();
        assertThat(providerChunk.path("id").asText()).isEqualTo("provider-id");
        assertThat(providerChunk.has("usage")).isTrue();
    }

    /**
     * {@code choices:[]} 且<strong>没有</strong> {@code usage} 的块必须被接受，而不是判成协议违规。
     *
     * <p>这条契约的来源是一次生产排查（2026-10-16）：上游天翼云在**输出被长度截断**时，会在结束块
     * 的前一格多发一个这样的帧——
     *
     * <pre>
     * {"id":"...","object":"chat.completion.chunk","model":"deepseek-v4.1-flash","choices":[]}
     * </pre>
     *
     * <p>实测复现确认了它与截断的绑定关系：{@code max_tokens=64} 时末尾是「{@code finish_reason=length}
     * → 空 choices <b>带</b> usage → [DONE]」（不触发），而 {@code max_tokens=200} 时是
     * 「空 choices <b>不带</b> usage → {@code finish_reason=length} → usage 块 → [DONE]」（触发）。
     * 也就是说同一家上游在同样被截断时会有两种帧序，网关只能对两种都容错。
     *
     * <p>此前这里要求「{@code choices} 为空时必须带 {@code usage}」，于是第二帧序被判成违规。
     * 炸点在响应**已经提交之后**，所以状态码改不了，只能把一条正在产出内容的流收尾成失败帧：
     * 客户端拿到半个回答、没有 {@code [DONE]}，而 {@code finish_reason=length} 就在紧接的下一块里、
     * 网关在读到它之前就抛了，于是这次截断从未被标记过。
     *
     * <p>注意本用例**只**断言不抛异常。空 choices 不产出内容，因此放行它不会让任何东西漏给客户；
     * 真正防止「一条全由无操作帧组成的流被当成成功」的是调用方的零内容兜底，那一条由
     * {@code ChatStreamFailureClassifierTest} 与 {@code ChatCompletionContractTest} 覆盖。
     */
    @Test
    void acceptsEmptyChoicesWithoutUsageAsAValidStreamChunk() {
        // 上游的长度分隔帧：空 choices，没有 usage。
        ObjectNode lengthSeparator = streamChunk();
        lengthSeparator.remove("choices");
        lengthSeparator.putArray("choices");

        protocol.validateStreamChunk(lengthSeparator);

        // 对照：usage 尾帧（网关自己也会发这种帧）照旧合法。
        ObjectNode usageTail = streamChunk();
        usageTail.remove("choices");
        usageTail.putArray("choices");
        usageTail.putObject("usage").put("total_tokens", 7);

        protocol.validateStreamChunk(usageTail);
    }

    /**
     * 元数据缺失仍然是违规——放宽 {@code choices:[]} 不能顺手把整个校验阉掉。
     *
     * <p>放行的是「空数组」这一种形态；{@code choices} 字段整个缺失、或者不是数组，仍然是协议违规，
     * 因为那时网关无法判断这一帧到底想表达什么。内容块里缺 {@code index} / {@code delta} 同理。
     */
    @Test
    void stillRejectsChunksThatAreNotEvenShapedLikeAStreamChunk() {
        ObjectNode withoutChoices = streamChunk();
        withoutChoices.remove("choices");

        assertProviderFailure(
                () -> protocol.validateStreamChunk(withoutChoices), "invalid_provider_stream_chunk");

        ObjectNode choicesNotAnArray = streamChunk();
        choicesNotAnArray.put("choices", "nope");

        assertProviderFailure(
                () -> protocol.validateStreamChunk(choicesNotAnArray),
                "invalid_provider_stream_chunk");

        ObjectNode choiceWithoutDelta = streamChunk();
        ((ObjectNode) choiceWithoutDelta.withArray("choices").get(0)).remove("delta");

        assertProviderFailure(
                () -> protocol.validateStreamChunk(choiceWithoutDelta),
                "invalid_provider_stream_chunk");
    }

    /**
     * 「这一帧交付了内容吗」必须把**只有 role 的空信封帧**判成「没有内容」——序列化之后也一样。
     *
     * <p>这个判据是「上游掐断后能不能换渠道」的唯一开关：上游先回一个空信封帧让响应提交、
     * 再在吐出第一个字之前掐断连接时，网关只有认出「客户什么都没收到」才敢换渠道。
     * 2026-10-16 的实测发现这个形状被判成了「含内容」，于是换渠道被跳过——
     * 而那正是生产上 11 次「上游流中断」全部无法挽回的原因。
     */
    @Test
    void treatsARoleOnlyEnvelopeAsNoContentEvenAfterSerialization() {
        ObjectNode envelope = streamChunk();
        ((ObjectNode) envelope.withArray("choices").get(0))
                .withObject("delta")
                .removeAll()
                .put("role", "assistant");

        assertThat(protocol.hasDeliverableContent(envelope)).isFalse();

        // 序列化（改写元数据）之后仍须是 false：换渠道判定读的正是改写后的那一帧。
        protocol.validateStreamChunk(envelope);
        JsonNode forwarded = protocol.rewriteStreamChunk(
                envelope, "text-basic", "req_00000000000000000000000000000001", STARTED_AT);
        assertThat(protocol.hasDeliverableContent(forwarded)).isFalse();

        // 对照：真正带内容的帧必须被判成有内容，否则网关会去换一条渠道、造成文字混合。
        assertThat(protocol.hasDeliverableContent(streamChunk())).isTrue();
    }

    @Test
    void createsAndSerializesFinalUsageChunkWithStableMetadata() throws Exception {
        JsonNode usageChunk = protocol.usageChunk(
                "text-basic",
                "external-request-id",
                STARTED_AT,
                new ProviderAdapter.TokenUsage(10, 20, 30));

        JsonNode serialized = JSON.readTree(protocol.writeJson(usageChunk));
        assertThat(serialized.path("id").asText()).isEqualTo("chatcmpl-external-request-id");
        assertThat(serialized.path("object").asText()).isEqualTo("chat.completion.chunk");
        assertThat(serialized.path("created").asLong()).isEqualTo(STARTED_AT.getEpochSecond());
        assertThat(serialized.path("model").asText()).isEqualTo("text-basic");
        assertThat(serialized.path("choices")).isEmpty();
        assertThat(serialized.path("usage").path("prompt_tokens").asLong()).isEqualTo(10);
        assertThat(serialized.path("usage").path("completion_tokens").asLong()).isEqualTo(20);
        assertThat(serialized.path("usage").path("total_tokens").asLong()).isEqualTo(30);
    }

    /**
     * 失败帧的形状，以及它与**普通块**的区分。
     *
     * <p>这条判据是「断流会不会被重新包装成成功」的唯一开关：{@code /v1/responses} 的转换层
     * 靠 {@link ChatCompletionResponseProtocol#isFailureFrame} 决定是发
     * {@code response.failed} 还是照常发 {@code response.completed}。它必须在**真实实现**上
     * 被钉住，否则一个「看起来像错误帧但没被认出」的回归只会在生产里表现为「任务完成了，
     * 但没有产出」。
     */
    @Test
    void failureFramesAreDistinguishableFromEveryOrdinaryChunk() throws Exception {
        JsonNode frame = protocol.failureFrame(
                new ProviderAdapter.ProviderException(502, "provider_stream_missing_done"),
                "req_00000000000000000000000000000009");
        JsonNode serialized = JSON.readTree(protocol.writeJson(frame));

        assertThat(protocol.isFailureFrame(serialized)).isTrue();
        assertThat(serialized.path("error").path("code").asText()).isEqualTo("provider_error");
        assertThat(serialized.path("error").path("type").asText()).isEqualTo("server_error");
        assertThat(serialized.path("error").path("reason").asText())
                .isEqualTo("provider_stream_missing_done");
        assertThat(serialized.path("request_id").asText())
                .isEqualTo("req_00000000000000000000000000000009");

        // 正常内容块、用量尾帧、[DONE]、以及一个没有 choices 的怪异块，都不能被误判为失败帧。
        assertThat(protocol.isFailureFrame(streamChunk())).isFalse();
        assertThat(protocol.isFailureFrame(protocol.usageChunk(
                "text-basic", "req_1", STARTED_AT, new ProviderAdapter.TokenUsage(1, 2, 3))))
                .isFalse();
        assertThat(protocol.isFailureFrame(JSON.createObjectNode().putArray("choices")))
                .isFalse();
        assertThat(protocol.isFailureFrame(JSON.getNodeFactory().textNode("[DONE]"))).isFalse();
        assertThat(protocol.isFailureFrame(null)).isFalse();
    }

    @Test
    void failureFramesOmitTheReasonWhenTheExceptionHasNoSafeCode() {
        JsonNode frame = protocol.failureFrame(
                new ProviderAdapter.ProviderException(429, "provider secret"), "req_secret");

        assertThat(protocol.isFailureFrame(frame)).isTrue();
        // 自由文本永远不进错误帧，正如它永远不进用量表。
        assertThat(frame.path("error").has("reason")).isFalse();
        assertThat(frame.toString()).doesNotContain("provider secret");
    }

    @Test
    void failureFramesCarryTheClientActionableCodeForEveryProviderStatus() {
        assertThat(protocol.failureFrame(
                new ProviderAdapter.ProviderException(504, "provider_attempt_timeout"), "req_t")
                .path("error").path("code").asText()).isEqualTo("provider_timeout");
        assertThat(protocol.failureFrame(
                new ProviderAdapter.ProviderException(429, "provider_http_429"), "req_t")
                .path("error").path("code").asText()).isEqualTo("provider_error");
        assertThat(protocol.failureFrame(
                new ProviderAdapter.ProviderException(
                        502, "content policy", null, null, true), "req_t")
                .path("error").path("code").asText())
                .isEqualTo("image_content_policy_violation");
    }

    private static ObjectNode completion() {
        ObjectNode response = JSON.createObjectNode();
        response.put("id", "provider-id");
        response.put("object", "chat.completion");
        response.put("created", 100);
        response.put("model", "provider-model");
        response.putArray("choices")
                .addObject()
                .put("index", 0)
                .putObject("message")
                .put("role", "assistant")
                .put("content", "hello");
        ((ObjectNode) response.withArray("choices").get(0)).put("finish_reason", "stop");
        return response;
    }

    private static ObjectNode streamChunk() {
        ObjectNode chunk = JSON.createObjectNode();
        chunk.put("id", "provider-id");
        chunk.put("object", "vendor.chunk");
        chunk.put("created", 100);
        chunk.put("model", "provider-model");
        chunk.putArray("choices")
                .addObject()
                .put("index", 0)
                .putObject("delta")
                .put("content", "hello");
        return chunk;
    }

    private static void assertProviderFailure(Runnable action, String message) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error -> {
                    assertThat(error.status()).isEqualTo(502);
                    assertThat(error).hasMessage(message);
                });
    }
}

package com.yuzhihuihe.apiplatform.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 天翼渠道（{@code provider_type = TIANYIYUN}）的流式协议契约。
 *
 * <p><b>为什么单独为天翼写一个契约测试。</b>2026-10-14 的三次桌面对话中断全部落在
 * {@code tianyi / tianyi-chat-primary}、上游 {@code deepseek-v4.1-flash} 上，而此前的
 * 适配器契约测试只覆盖百炼（{@code BailianAdapterContractTest}）。百炼与天翼共用
 * {@link OpenAiCompatibleProviderAdapter} 的解码器，但**共用实现不等于共用协议**：需要固定的
 * 是这条渠道实际发出的帧长什么样，以及网关在这些帧上必须保持哪些不变量。
 *
 * <p>测试用真实 HTTP/SSE 传输（而非内存 Flux 断言），且**逐字节刷出**响应——因为
 * 「网络 chunk 边界不是 SSE 事件边界」只有在能控制分块时才能验证（交接方案四.3 第 5 条）。
 */
class TianYiYunChatAdapterContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID CHANNEL_ID = UUID.fromString("10000000-0000-4000-8000-0000000000a1");

    @Test
    void toolCallFragmentsSurviveWithIdNameAndArgumentOrderIntact() throws Exception {
        List<ProviderAdapter.ProviderChunk> chunks = decode(
                "data: {\"id\":\"chatcmpl-t\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1,\"model\":\"deepseek-v4.1-flash\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"write_file\","
                        + "\"arguments\":\"\"}}]},\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"chatcmpl-t\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1,\"model\":\"deepseek-v4.1-flash\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                        + "{\"arguments\":\"{\\\"path\\\":\"}}]},\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"chatcmpl-t\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1,\"model\":\"deepseek-v4.1-flash\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                        + "{\"arguments\":\"\\\"a.html\\\"}\"}}]},\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"chatcmpl-t\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1,\"model\":\"deepseek-v4.1-flash\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                        + "data: [DONE]\n\n");

        // 适配器**不重组**工具参数（重组是客户端 SDK 的职责），但必须一个字段都不丢、
        // 一个分片都不合并、顺序不变——否则半截 JSON 会被当成可执行的工具参数交付。
        assertThat(chunks).hasSize(4);
        JsonNode call = chunks.get(0).data().path("choices").get(0).path("delta")
                .path("tool_calls").get(0);
        assertThat(call.path("id").asText()).isEqualTo("call_1");
        assertThat(call.path("type").asText()).isEqualTo("function");
        assertThat(call.path("function").path("name").asText()).isEqualTo("write_file");
        assertThat(call.path("index").asInt()).isZero();

        StringBuilder arguments = new StringBuilder();
        for (ProviderAdapter.ProviderChunk chunk : chunks) {
            chunk.data().path("choices").get(0).path("delta").path("tool_calls")
                    .forEach(fragment -> arguments.append(
                            fragment.path("function").path("arguments").asText("")));
        }
        assertThat(arguments.toString()).isEqualTo("{\"path\":\"a.html\"}");
        assertThat(JSON.readTree(arguments.toString()).path("path").asText()).isEqualTo("a.html");

        assertThat(chunks.get(3).data().path("choices").get(0).path("finish_reason").asText())
                .isEqualTo("tool_calls");
    }

    @Test
    void theUsageTrailerArrivesAfterContentAndBeforeDone() {
        List<ProviderAdapter.ProviderChunk> chunks = decode(
                "data: {\"id\":\"chatcmpl-u\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1,\"model\":\"deepseek-v4.1-flash\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"hi\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"chatcmpl-u\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1,\"model\":\"deepseek-v4.1-flash\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: {\"id\":\"chatcmpl-u\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1,\"model\":\"deepseek-v4.1-flash\",\"choices\":[],"
                        + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,"
                        + "\"total_tokens\":18}}\n\n"
                        + "data: [DONE]\n\n");

        // 用量尾帧是结算依据：少了它这次调用只能按估算计费——正是生产里那些
        // usageEstimated=true 的行。finish_reason 与它都必须在 [DONE] 之前被交给上层。
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(1).data().path("choices").get(0).path("finish_reason").asText())
                .isEqualTo("stop");
        assertThat(chunks.get(2).data().path("choices").isEmpty()).isTrue();
        assertThat(chunks.get(2).usage()).isNotNull();
        assertThat(chunks.get(2).usage().inputTokens()).isEqualTo(11);
        assertThat(chunks.get(2).usage().outputTokens()).isEqualTo(7);
        assertThat(chunks.get(2).usage().totalTokens()).isEqualTo(18);
        assertThat(chunks.get(2).usage().source())
                .isEqualTo(ProviderAdapter.UsageSource.PROVIDER_ACTUAL);
        // 供应商请求 ID 被保留，供向供应商侧排查（交接方案三.3）。
        assertThat(chunks.get(2).usage().providerRequestId()).isEqualTo("chatcmpl-u");
    }

    /**
     * 心跳与协议字段不是载荷。
     *
     * <p>很多上游用 {@code : keep-alive} 或 {@code event: ping} 维持长连接。它们此前会
     * 原样进入 JSON 解析器，于是**一次心跳就让整条已经在产出内容的流以
     * {@code invalid_provider_stream_chunk} 失败**。
     */
    @Test
    void keepAliveCommentsAndFramingFieldsAreIgnoredRatherThanFailingTheStream() {
        List<ProviderAdapter.ProviderChunk> chunks = decode(
                ": keep-alive\n\n"
                        + "event: message\n\n"
                        + "id: 42\n\n"
                        + "retry: 3000\n\n"
                        + "data: {\"id\":\"chatcmpl-k\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1,\"model\":\"deepseek-v4.1-flash\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n"
                        + "data: [DONE]\n\n");

        assertThat(chunks).singleElement().satisfies(chunk -> assertThat(
                chunk.data().path("choices").get(0).path("delta").path("content").asText())
                .isEqualTo("ok"));
    }

    /**
     * 网络分块边界不是 SSE 事件边界。
     *
     * <p>响应按单字节刷出，于是每个事件都跨多个网络分块——包括切在 UTF-8 中文字符中间、
     * 切在 {@code data:} 与载荷之间、切在 {@code [DONE]} 中间。解码必须只看行边界。
     */
    @Test
    void byteLevelChunkBoundariesNeverSplitAnEvent() {
        List<ProviderAdapter.ProviderChunk> chunks = decodeInSingleByteChunks(
                "data: {\"id\":\"chatcmpl-c\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1,\"model\":\"deepseek-v4.1-flash\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"中文回答\"},\"finish_reason\":null}]}\r\n\r\n"
                        + "data: [DONE]\r\n\r\n");

        assertThat(chunks).singleElement().satisfies(chunk -> assertThat(
                chunk.data().path("choices").get(0).path("delta").path("content").asText())
                .isEqualTo("中文回答"));
    }

    /**
     * 缺 {@code [DONE]} 必须失败。
     *
     * <p>这是交接方案四.3 最后一段的落点：**不能**放宽为「只要 EOF 就成功」。一个在首段
     * 之后被掐断的上游，其可观测形态正是「有内容、没有 [DONE]」；把它读成成功，就是把断流
     * 重新包装成正常结束。
     */
    @Test
    void aStreamThatEndsWithoutDoneStillFails() {
        ProviderAdapter.Channel channel = channel(serverServing(
                "data: {\"id\":\"chatcmpl-n\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1,\"model\":\"deepseek-v4.1-flash\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"half\"},\"finish_reason\":null}]}\n\n",
                4096));

        assertThatThrownBy(() -> adapter().stream(routed(channel))
                        .collectList().block(Duration.ofSeconds(5)))
                .isInstanceOf(ProviderAdapter.ProviderException.class)
                .hasMessage("provider_stream_missing_done");
    }

    @Test
    void malformedJsonInsideADataLineStillFailsInsteadOfBeingSkipped() {
        ProviderAdapter.Channel channel = channel(serverServing(
                "data: {\"id\":\"chatcmpl-b\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1,\"model\":\"deepseek-v4.1-flash\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"chatcmpl-b\",\"object\":\"chat.completion.chunk\"\n\n"
                        + "data: [DONE]\n\n",
                4096));

        assertThatThrownBy(() -> adapter().stream(routed(channel))
                        .collectList().block(Duration.ofSeconds(5)))
                .isInstanceOf(ProviderAdapter.ProviderException.class)
                .hasMessage("invalid_provider_stream_chunk");
    }

    @Test
    void streamingAlwaysAsksTheProviderForUsage() {
        AtomicReference<JsonNode> forwarded = new AtomicReference<>();
        ProviderAdapter.Channel channel = channel(serverServing(
                "data: {\"id\":\"chatcmpl-r\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1,\"model\":\"deepseek-v4.1-flash\",\"choices\":[],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,"
                        + "\"total_tokens\":3}}\n\n"
                        + "data: [DONE]\n\n",
                4096, forwarded));

        adapter().stream(routed(channel)).collectList().block(Duration.ofSeconds(5));

        JsonNode body = forwarded.get();
        assertThat(body).isNotNull();
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("stream_options").path("include_usage").asBoolean()).isTrue();
        assertThat(body.path("model").asText()).isEqualTo("deepseek-v4.1-flash");
    }

    private TianYiYunChatAdapter adapter() {
        return new TianYiYunChatAdapter(WebClient.builder(), JSON);
    }

    private ProviderAdapter.RoutedRequest routed(ProviderAdapter.Channel channel) {
        ObjectNode body = JSON.createObjectNode();
        body.putArray("messages").addObject().put("role", "user").put("content", "hello");
        return new ProviderAdapter.RoutedRequest(
                "req_tianyi_contract", "chat-standard", body, channel);
    }

    private ProviderAdapter.Channel channel(int port) {
        return new ProviderAdapter.Channel(
                CHANNEL_ID, UUID.randomUUID(), "TIANYIYUN",
                "http://127.0.0.1:" + port, "secret", 5_000, "deepseek-v4.1-flash");
    }

    private List<ProviderAdapter.ProviderChunk> decode(String payload) {
        return adapter().stream(routed(channel(serverServing(payload, 4096))))
                .collectList().block(Duration.ofSeconds(5));
    }

    private List<ProviderAdapter.ProviderChunk> decodeInSingleByteChunks(String payload) {
        return adapter().stream(routed(channel(serverServing(payload, 1))))
                .collectList().block(Duration.ofSeconds(5));
    }

    /**
     * 一个以分块传输写 SSE 的服务器。
     *
     * <p>{@code chunkSize} 是**传输层**分块的大小，不是 SSE 事件大小：它让「一个事件跨若干
     * 网络分块」成为默认情形，而不是碰运气才出现的情形。{@code flushAfterEachChunk} 让
     * 这些分块真的分别落到网络上。
     */
    private int serverServing(String payload) {
        return serverServing(payload, 4096);
    }

    private int serverServing(String payload, int chunkSize) {
        return serverServing(payload, chunkSize, new AtomicReference<>());
    }

    private int serverServing(
            String payload, int chunkSize, AtomicReference<JsonNode> forwarded) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            server.createContext("/chat/completions", exchange -> {
                forwarded.set(JSON.readTree(exchange.getRequestBody()));
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                // 0 = 长度未知 → 分块传输编码，响应体可以按任意边界刷出。
                exchange.sendResponseHeaders(200, 0);
                try (var out = exchange.getResponseBody()) {
                    for (int offset = 0; offset < bytes.length; offset += chunkSize) {
                        int length = Math.min(chunkSize, bytes.length - offset);
                        out.write(bytes, offset, length);
                        out.flush();
                    }
                }
            });
            server.start();
            Thread stopper = new Thread(() -> {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                server.stop(0);
            });
            stopper.setDaemon(true);
            stopper.start();
            return server.getAddress().getPort();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

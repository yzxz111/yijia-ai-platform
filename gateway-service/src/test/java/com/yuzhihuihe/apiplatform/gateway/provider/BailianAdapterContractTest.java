package com.yuzhihuihe.apiplatform.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class BailianAdapterContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void streamingAlwaysRequestsUsageUpstreamWithoutChangingTheClientBody() throws Exception {
        AtomicReference<JsonNode> forwarded = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            forwarded.set(JSON.readTree(exchange.getRequestBody()));
            byte[] response = ("data: {\"id\":\"chatcmpl-stream\",\"object\":\"chat.completion.chunk\","
                    + "\"created\":1,\"model\":\"deepseek-chat\",\"choices\":[]}"
                    + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            DeepSeekAdapter adapter = new DeepSeekAdapter(WebClient.builder(), JSON);
            ObjectNode body = JSON.createObjectNode();
            body.putArray("messages").addObject().put("role", "user").put("content", "hello");
            ObjectNode original = body.deepCopy();

            adapter.stream(new ProviderAdapter.RoutedRequest(
                            "req_force_stream_usage", "text-basic", body,
                            new ProviderAdapter.Channel(
                                    UUID.randomUUID(), UUID.randomUUID(), "DEEPSEEK",
                                    "http://127.0.0.1:" + server.getAddress().getPort(),
                                    "secret", 10_000, "deepseek-chat")))
                    .collectList().block(Duration.ofSeconds(3));

            assertThat(body).isEqualTo(original);
            assertThat(forwarded.get().path("stream_options").path("include_usage").asBoolean())
                    .isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void qwenLongReferencesUploadedFilesThroughSystemFileIds() throws Exception {
        AtomicReference<JsonNode> forwarded = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            forwarded.set(JSON.readTree(exchange.getRequestBody()));
            byte[] response = ("{\"id\":\"chatcmpl-file\",\"object\":\"chat.completion\","
                    + "\"created\":1,\"model\":\"qwen-long\",\"choices\":[{\"index\":0,"
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"summary\"},"
                    + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":8,"
                    + "\"completion_tokens\":2,\"total_tokens\":10}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            BailianAdapter adapter = new BailianAdapter(WebClient.builder(), JSON);
            ProviderAdapter.Channel channel = new ProviderAdapter.Channel(
                    UUID.randomUUID(), UUID.randomUUID(), "BAILIAN",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "secret", 10_000, "qwen-long");
            ObjectNode body = JSON.createObjectNode().put("model", "file-basic");
            ArrayNode content = body.putArray("messages").addObject()
                    .put("role", "user").putArray("content");
            content.addObject().put("type", "text").put("text", "Summarize both documents.");
            content.addObject().put("type", "input_file").put("file_id", "file-fe-one");
            content.addObject().put("type", "input_file").put("file_id", "file-fe-two");

            adapter.complete(new ProviderAdapter.RoutedRequest(
                    "req_qwen_long_file_contract", "file-basic", body, channel))
                    .block(Duration.ofSeconds(3));

            JsonNode messages = forwarded.get().path("messages");
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
            assertThat(messages.get(0).path("content").asText())
                    .isEqualTo("fileid://file-fe-one,file-fe-two");
            assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
            assertThat(messages.get(1).path("content").asText())
                    .isEqualTo("Summarize both documents.");
            assertThat(forwarded.get().toString()).doesNotContain("input_file");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void qwenLongCollapsesLocallyExtractedTextPartsIntoPlainContent() {
        BailianAdapter adapter = new BailianAdapter(WebClient.builder(), JSON);
        ObjectNode body = JSON.createObjectNode().put("model", "file-basic");
        ArrayNode content = body.putArray("messages").addObject()
                .put("role", "user").putArray("content");
        content.addObject().put("type", "text").put("text", "Summarize both documents.");
        content.addObject().put("type", "text").put("text", "BEGIN_UNTRUSTED_DOCUMENT one");
        content.addObject().put("type", "text").put("text", "BEGIN_UNTRUSTED_DOCUMENT two");

        JsonNode prepared = adapter.prepareProviderBody(body, "qwen-long");

        assertThat(prepared.path("messages").get(0).path("content").asText())
                .isEqualTo("Summarize both documents.\n"
                        + "BEGIN_UNTRUSTED_DOCUMENT one\n"
                        + "BEGIN_UNTRUSTED_DOCUMENT two");
    }

    @Test
    void qwenLongUsesTheStableCommonErrorForMalformedMessages() {
        BailianAdapter adapter = new BailianAdapter(WebClient.builder(), JSON);
        ObjectNode body = JSON.createObjectNode();
        body.putArray("messages").add("not-an-object");

        assertThatThrownBy(() -> adapter.complete(routed(body, "qwen-long")).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_message");
    }

    @Test
    void qwenLongRejectsAnEmptyProviderFileId() {
        BailianAdapter adapter = new BailianAdapter(WebClient.builder(), JSON);
        ObjectNode body = fileBody("Summarize the document.", "");

        assertThatThrownBy(() -> adapter.prepareProviderBody(body, "qwen-long"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_file_id");
    }

    @Test
    void qwenLongRejectsAFileOnlyMessageWithoutAUserQuestion() {
        BailianAdapter adapter = new BailianAdapter(WebClient.builder(), JSON);
        ObjectNode body = fileBody(null, "file-fe-one");

        assertThatThrownBy(() -> adapter.prepareProviderBody(body, "qwen-long"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_message_content");
    }

    @Test
    void qwenLongDeduplicatesProviderFileIdsInFirstSeenOrder() {
        BailianAdapter adapter = new BailianAdapter(WebClient.builder(), JSON);
        ObjectNode body = fileBody("Summarize the documents.",
                "file-fe-two", "file-fe-one", "file-fe-two");

        JsonNode prepared = adapter.prepareProviderBody(body, "qwen-long");

        assertThat(prepared.path("messages").get(0).path("content").asText())
                .isEqualTo("fileid://file-fe-two,file-fe-one");
    }

    @Test
    void qwenLongPreservesSystemOrderMixedPartsAndTheInputBody() {
        BailianAdapter adapter = new BailianAdapter(WebClient.builder(), JSON);
        ObjectNode body = fileBody("Use the file and image.", "file-fe-one");
        body.withArray("messages").insertObject(0)
                .put("role", "system").put("content", "Existing system instruction.");
        ((ArrayNode) body.withArray("messages").get(1).get("content")).addObject()
                .put("type", "image_url").putObject("image_url")
                .put("url", "data:image/png;base64,AA==");
        ObjectNode original = body.deepCopy();

        JsonNode prepared = adapter.prepareProviderBody(body, "qwen-long");

        assertThat(body).isEqualTo(original);
        assertThat(prepared.path("messages").get(0).path("content").asText())
                .isEqualTo("Existing system instruction.");
        assertThat(prepared.path("messages").get(1).path("content").asText())
                .isEqualTo("fileid://file-fe-one");
        assertThat(prepared.path("messages").get(2).path("content").get(1)
                .path("type").asText()).isEqualTo("image_url");
    }

    @Test
    void nonQwenLongAdaptersKeepTheDefaultProviderBodyHook() {
        DeepSeekAdapter adapter = new DeepSeekAdapter(WebClient.builder(), JSON);
        ObjectNode body = JSON.createObjectNode().putArray("messages").addObject()
                .put("role", "user").put("content", "hello");

        assertThat(adapter.prepareProviderBody(body, "deepseek-chat")).isSameAs(body);
    }

    private static ObjectNode fileBody(String text, String... fileIds) {
        ObjectNode body = JSON.createObjectNode().put("model", "file-basic");
        ArrayNode content = body.putArray("messages").addObject()
                .put("role", "user").putArray("content");
        if (text != null) {
            content.addObject().put("type", "text").put("text", text);
        }
        for (String fileId : fileIds) {
            content.addObject().put("type", "input_file").put("file_id", fileId);
        }
        return body;
    }

    private static ProviderAdapter.RoutedRequest routed(ObjectNode body, String upstreamModel) {
        ProviderAdapter.Channel channel = new ProviderAdapter.Channel(
                UUID.randomUUID(), UUID.randomUUID(), "BAILIAN",
                "http://127.0.0.1:1", "secret", 200, upstreamModel);
        return new ProviderAdapter.RoutedRequest(
                "req_qwen_long_validation", "file-basic", body, channel);
    }
}

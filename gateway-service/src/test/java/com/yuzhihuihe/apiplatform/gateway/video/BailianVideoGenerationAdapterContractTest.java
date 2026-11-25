package com.yuzhihuihe.apiplatform.gateway.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuzhihuihe.apiplatform.gateway.video.VideoGenerationAdapter.TaskRequest;
import com.yuzhihuihe.apiplatform.gateway.video.VideoGenerationAdapter.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 网关与阿里云百炼异步视频 API 之间的线上契约，针对厂商的本地
 * 桩、通过真实 HTTP 演练。
 *
 * <p>正是这些断言让一条真实渠道无需付费调用就可被配置：
 * 提交载荷、轮询 URL、终态映射以及计费
 * 决策都在此钉住，因此实跑中唯一未经验证的变量
 * 就是厂商自身的行为。
 */
class BailianVideoGenerationAdapterContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUBMIT_PATH = "/api/v1/services/aigc/video-generation/video-synthesis";
    private static final String TASK_PATH = "/api/v1/tasks/";

    private HttpServer server;
    private String baseUrl;
    private BailianVideoGenerationAdapter adapter;
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> asyncHeader = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void prepareStubVendor() throws Exception {
        // 仅回环地址、临时端口：这里的任何测试都不会发起外部调用。
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        adapter = new BailianVideoGenerationAdapter(WebClient.builder(), JSON);
    }

    @AfterEach
    void stopStubVendor() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void submitsTheDocumentedAsyncPayloadAndReturnsTheVendorTaskId() throws Exception {
        respond(200, """
                {"output":{"task_status":"PENDING","task_id":"0385dc79-5ff8-4d82-bcb6-aaa"},"request_id":"req-1"}
                """);
        TaskRequest request = request(10, "720P");

        String taskId = adapter.submit(request).block(Duration.ofSeconds(5)).upstreamTaskId();

        assertThat(taskId).isEqualTo("0385dc79-5ff8-4d82-bcb6-aaa");
        assertThat(requestPath.get()).isEqualTo(SUBMIT_PATH);
        // 没有这个请求头，厂商会直接拒绝该调用。
        assertThat(asyncHeader.get()).isEqualTo("enable");
        assertThat(authorization.get()).isEqualTo("Bearer sk-vendor-secret");
        JsonNode body = requestBody.get();
        assertThat(body.path("model").asText()).isEqualTo("wan2.7-t2v-2026-06-12");
        assertThat(body.path("input").path("prompt").asText()).isEqualTo("黄昏的灯塔");
        assertThat(body.path("parameters").path("resolution").asText()).isEqualTo("720P");
        assertThat(body.path("parameters").path("duration").asInt()).isEqualTo(10);
        assertThat(body.path("parameters").path("ratio").asText()).isEqualTo("16:9");
        // 没有音频开关：厂商协同生成音频，且没有对应参数。
        assertThat(body.path("parameters").has("with_audio")).isFalse();
        assertThat(body.path("parameters").has("audio")).isFalse();
        // 文生视频请求不得夹带图像字段。
        assertThat(body.path("input").has("img_url")).isFalse();
    }

    @Test
    void sendsTheFirstFrameAsAnImageToVideoSubmission() throws Exception {
        respond(200, """
                {"output":{"task_status":"PENDING","task_id":"i2v-task"}}
                """);

        adapter.submit(requestWithFirstFrame("https://cdn.example.test/first-frame.png"))
                .block(Duration.ofSeconds(5));

        JsonNode body = requestBody.get();
        assertThat(body.path("model").asText()).isEqualTo("wan2.7-i2v");
        assertThat(body.path("input").path("media").path(0).path("type").asText())
                .isEqualTo("first_frame");
        assertThat(body.path("input").path("media").path(0).path("url").asText())
                .isEqualTo("https://cdn.example.test/first-frame.png");
        assertThat(body.path("input").has("img_url")).isFalse();
        assertThat(body.path("parameters").path("resolution").asText()).isEqualTo("1080P");
        assertThat(body.path("parameters").has("ratio")).isFalse();
    }

    @Test
    void translatesWan25TextAndImageRequestsToTheirDistinctLegacyProtocols() throws Exception {
        respond(200, """
                {"output":{"task_status":"PENDING","task_id":"legacy-task"}}
                """);

        adapter.submit(requestForModel(
                        "wan2.5-t2v-preview", null, 10, "480P", "9:16", true))
                .block(Duration.ofSeconds(5));

        JsonNode textBody = requestBody.get();
        assertThat(textBody.path("model").asText()).isEqualTo("wan2.5-t2v-preview");
        assertThat(textBody.path("parameters").path("size").asText()).isEqualTo("480*832");
        assertThat(textBody.path("parameters").has("resolution")).isFalse();

        requestBody.set(null);
        adapter.submit(requestForModel(
                        "wan2.5-t2v-preview",
                        "https://cdn.example.test/legacy-frame.png",
                        5,
                        "480P",
                        null,
                        false))
                .block(Duration.ofSeconds(5));

        JsonNode imageBody = requestBody.get();
        assertThat(imageBody.path("model").asText()).isEqualTo("wan2.5-i2v-preview");
        assertThat(imageBody.path("input").path("img_url").asText())
                .isEqualTo("https://cdn.example.test/legacy-frame.png");
        assertThat(imageBody.path("parameters").path("resolution").asText())
                .isEqualTo("480P");
        assertThat(imageBody.path("parameters").has("size")).isFalse();
    }

    @Test
    void translatesTheSeededWan26RouteUsingSizeAndItsImageSibling() throws Exception {
        respond(200, """
                {"output":{"task_status":"PENDING","task_id":"wan26-task"}}
                """);

        adapter.submit(requestForModel(
                        "wan2.6-t2v", null, 15, "1080P", "4:3", true))
                .block(Duration.ofSeconds(5));

        JsonNode textBody = requestBody.get();
        assertThat(textBody.path("model").asText()).isEqualTo("wan2.6-t2v");
        assertThat(textBody.path("parameters").path("size").asText())
                .isEqualTo("1632*1248");
        assertThat(textBody.path("parameters").has("resolution")).isFalse();

        requestBody.set(null);
        adapter.submit(requestForModel(
                        "wan2.6-t2v",
                        "https://cdn.example.test/wan26-frame.png",
                        8,
                        "720P",
                        null,
                        false))
                .block(Duration.ofSeconds(5));

        JsonNode imageBody = requestBody.get();
        assertThat(imageBody.path("model").asText()).isEqualTo("wan2.6-i2v");
        assertThat(imageBody.path("input").path("img_url").asText())
                .isEqualTo("https://cdn.example.test/wan26-frame.png");
        assertThat(imageBody.path("parameters").path("resolution").asText())
                .isEqualTo("720P");
    }

    @Test
    void translatesKlingUsingModeAspectRatioAndAudioRatherThanWanFields() throws Exception {
        respond(200, """
                {"output":{"task_status":"PENDING","task_id":"kling-task"}}
                """);

        adapter.submit(requestForModel(
                        "kling/kling-v3-omni-video-generation",
                        null,
                        12,
                        "1080P",
                        "9:16",
                        true))
                .block(Duration.ofSeconds(5));

        JsonNode body = requestBody.get();
        assertThat(body.path("model").asText())
                .isEqualTo("kling/kling-v3-omni-video-generation");
        assertThat(body.path("parameters").path("mode").asText()).isEqualTo("pro");
        assertThat(body.path("parameters").path("aspect_ratio").asText()).isEqualTo("9:16");
        assertThat(body.path("parameters").path("audio").asBoolean()).isTrue();
        assertThat(body.path("parameters").has("resolution")).isFalse();
        assertThat(body.path("parameters").has("ratio")).isFalse();
        assertThat(body.path("parameters").has("prompt_extend")).isFalse();
    }

    @Test
    void translatesAKlingFirstFrameToTypedMediaWithoutAnAspectRatio() throws Exception {
        respond(200, """
                {"output":{"task_status":"PENDING","task_id":"kling-image-task"}}
                """);

        adapter.submit(requestForModel(
                        "kling/kling-v3-turbo-video-generation",
                        "https://cdn.example.test/kling-frame.png",
                        5,
                        "720P",
                        "4:3",
                        false))
                .block(Duration.ofSeconds(5));

        JsonNode body = requestBody.get();
        assertThat(body.path("input").path("media").path(0).path("type").asText())
                .isEqualTo("first_frame");
        assertThat(body.path("input").path("media").path(0).path("url").asText())
                .isEqualTo("https://cdn.example.test/kling-frame.png");
        assertThat(body.path("parameters").path("mode").asText()).isEqualTo("std");
        assertThat(body.path("parameters").has("aspect_ratio")).isFalse();
        // Turbo 总是生成音频，因此发送 false 会虚假地承诺一个开关。
        assertThat(body.path("parameters").has("audio")).isFalse();
    }

    @Test
    void readsATopLevelTaskIdFromTheSdkShapedCreateResponse() throws Exception {
        // 文档中的响应体把 id 嵌套起来；也要容忍扁平形式，
        // 而不是让一次厂商已经接受、并且会据以计费的提交失败。
        respond(200, """
                {"task_id":"top-level-task","output":{"task_status":"PENDING"}}
                """);

        String taskId = adapter.submit(requestForModel(
                        "wan2.5-t2v-preview", null, 5, "480P", "16:9", true))
                .block(Duration.ofSeconds(5))
                .upstreamTaskId();

        assertThat(taskId).isEqualTo("top-level-task");
    }

    @Test
    void pollingUsesTheDocumentedTaskUrlAndReportsNonTerminalStates() throws Exception {
        respond(200, """
                {"output":{"task_id":"task-1","task_status":"RUNNING"}}
                """);

        TaskStatus status = adapter.poll(request(5, "720P"), "task-1").block(Duration.ofSeconds(5));

        assertThat(requestPath.get()).isEqualTo(TASK_PATH + "task-1");
        assertThat(status.state()).isEqualTo(TaskStatus.State.RUNNING);
        assertThat(status.terminal()).isFalse();
        assertThat(status.billedSeconds()).isNull();
    }

    @Test
    void aSucceededTaskBillsTheDurationTheVendorReportsAndExposesItsLocation() throws Exception {
        respond(200, """
                {"request_id":"caa6","output":{"task_id":"task-2","task_status":"SUCCEEDED",
                 "video_url":"https://dashscope-result-bj.oss-accelerate.aliyuncs.com/out.mp4?Expires=1"},
                 "usage":{"duration":10,"output_video_duration":10,"input_video_duration":0,"SR":720}}
                """);

        TaskStatus status = adapter.poll(request(15, "720P"), "task-2").block(Duration.ofSeconds(5));

        assertThat(status.state()).isEqualTo(TaskStatus.State.SUCCEEDED);
        assertThat(status.billedSeconds()).isEqualTo(10);
        assertThat(status.outputUrl())
                .isEqualTo("https://dashscope-result-bj.oss-accelerate.aliyuncs.com/out.mp4?Expires=1");
        assertThat(status.errorCategory()).isNull();
    }

    @Test
    void aSucceededTaskFallsBackToTheOrderedLengthWhenUsageIsAbsent() throws Exception {
        // 成功意味着 GPU 时间已被消耗。这里报告零会把这次
        // 生成白送出去，因此所订购的时长就是回退值。
        respond(200, """
                {"output":{"task_id":"task-3","task_status":"SUCCEEDED","video_url":"https://cdn.test/a.mp4"}}
                """);

        TaskStatus status = adapter.poll(request(8, "720P"), "task-3").block(Duration.ofSeconds(5));

        assertThat(status.billedSeconds()).isEqualTo(8);
    }

    @Test
    void aSucceededTaskWithoutAResultLocationStaysOpenUntilTheVideoCanBeFetched() throws Exception {
        respond(200, """
                {"output":{"task_id":"task-without-output","task_status":"SUCCEEDED"},
                 "usage":{"output_video_duration":8}}
                """);

        TaskStatus status = adapter.poll(request(8, "720P"), "task-without-output")
                .block(Duration.ofSeconds(5));

        assertThat(status.state()).isEqualTo(TaskStatus.State.PENDING);
        assertThat(status.terminal()).isFalse();
        assertThat(status.billedSeconds()).isNull();
        assertThat(status.outputUrl()).isNull();
        assertThat(status.errorCategory()).isEqualTo("provider_output_not_ready");
    }

    @Test
    void aFailedTaskBillsNothingAndKeepsTheVendorsOwnErrorCode() throws Exception {
        respond(200, """
                {"request_id":"e5d7","output":{"task_id":"task-4","task_status":"FAILED",
                 "code":"InvalidParameter","message":"The size is not match"}}
                """);

        TaskStatus status = adapter.poll(request(10, "720P"), "task-4").block(Duration.ofSeconds(5));

        assertThat(status.state()).isEqualTo(TaskStatus.State.FAILED);
        // 失败时没有 usage 对象并不构成收费的证据，因此不向客户
        // 计费；费率卡上的 retry_rate 就是与之对应的余量。
        assertThat(status.billedSeconds()).isZero();
        assertThat(status.outputUrl()).isNull();
        assertThat(status.errorCategory()).isEqualTo("InvalidParameter");
    }

    @Test
    void anUnknownTaskStatusKeepsTheReservationHeld() throws Exception {
        respond(200, """
                {"output":{"task_id":"task-5","task_status":"UNKNOWN"}}
                """);

        TaskStatus status = adapter.poll(request(10, "720P"), "task-5").block(Duration.ofSeconds(5));

        assertUnknownHeld(status);
    }

    @Test
    void anEmptyTaskStatusKeepsTheReservationHeld() throws Exception {
        respond(200, """
                {"output":{"task_id":"task-empty","task_status":""}}
                """);

        TaskStatus status = adapter.poll(request(10, "720P"), "task-empty")
                .block(Duration.ofSeconds(5));

        assertUnknownHeld(status);
    }

    @Test
    void anUnrecognisedTaskStatusKeepsTheReservationHeld() throws Exception {
        respond(200, """
                {"output":{"task_id":"task-future","task_status":"PAUSED_BY_VENDOR"}}
                """);

        TaskStatus status = adapter.poll(request(10, "720P"), "task-future")
                .block(Duration.ofSeconds(5));

        assertUnknownHeld(status);
    }

    @Test
    void aMissingUpstreamTaskIdIsHeldAsUnknownRatherThanFailed() {
        TaskStatus status = adapter.poll(request(10, "720P"), " ")
                .block(Duration.ofSeconds(5));

        assertUnknownHeld(status);
    }

    @Test
    void aCancelledTaskIsReportedAsCancelled() throws Exception {
        respond(200, """
                {"output":{"task_id":"task-6","task_status":"CANCELED"}}
                """);

        TaskStatus status = adapter.poll(request(10, "720P"), "task-6").block(Duration.ofSeconds(5));

        assertThat(status.state()).isEqualTo(TaskStatus.State.CANCELLED);
        assertThat(status.billedSeconds()).isZero();
    }

    @Test
    void aCreateResponseWithoutATaskIdFailsAsAnUpstreamProtocolError() throws Exception {
        respond(200, """
                {"request_id":"req-no-task","output":{"task_status":"PENDING"}}
                """);

        assertThatThrownBy(() -> adapter.submit(request(5, "720P")).block(Duration.ofSeconds(5)))
                .hasMessageContaining("provider_invalid_submission");
    }

    @Test
    void anUpstreamRefusalIsReportedAsAnUpstreamFaultRatherThanABadRequest() throws Exception {
        respond(400, """
                {"code":"InvalidApiKey","message":"Invalid API-key provided.","request_id":"req-2"}
                """);

        assertThatThrownBy(() -> adapter.submit(request(5, "720P")).block(Duration.ofSeconds(5)))
                .hasMessageContaining("provider_rejected_generation");
    }

    @Test
    void rejectsProviderSpecificProfilesBeforeAnyHttpCall() {
        assertThatThrownBy(() -> adapter.validateSubmission(requestForModel(
                        "wan2.5-t2v-preview", null, 6, "480P", "16:9", true)))
                .hasMessageContaining("bailian_video_duration_not_supported");
        assertThatThrownBy(() -> adapter.validateSubmission(requestForModel(
                        "kling/kling-v3-omni-video-generation",
                        null,
                        5,
                        "1080P",
                        "4:3",
                        true)))
                .hasMessageContaining("bailian_video_aspect_ratio_not_supported");
        assertThat(requestBody.get()).isNull();
    }

    @Test
    void theApiRootIsNotDuplicatedWhenOperatorsPasteTheVersionedBaseUrl() {
        assertThat(BailianVideoGenerationAdapter.apiRoot(channel("https://dashscope.aliyuncs.com", "wan")))
                .isEqualTo("https://dashscope.aliyuncs.com/api/v1");
        assertThat(BailianVideoGenerationAdapter.apiRoot(channel("https://dashscope.aliyuncs.com/", "wan")))
                .isEqualTo("https://dashscope.aliyuncs.com/api/v1");
        assertThat(BailianVideoGenerationAdapter.apiRoot(channel(baseUrl + "/api/v1", "wan")))
                .isEqualTo(baseUrl + "/api/v1");
        assertThat(BailianVideoGenerationAdapter.apiRoot(channel(baseUrl + "/api/v1/", "wan")))
                .isEqualTo(baseUrl + "/api/v1");
        assertThat(BailianVideoGenerationAdapter.apiRoot(channel(baseUrl + SUBMIT_PATH, "wan")))
                .isEqualTo(baseUrl + "/api/v1");
        assertThat(BailianVideoGenerationAdapter.submitUri(channel(baseUrl + SUBMIT_PATH + "/", "wan")))
                .isEqualTo(baseUrl + SUBMIT_PATH);
    }

    @Test
    void usesAnOperatorProvidedFullSubmitEndpointAndItsParentForPolling() throws Exception {
        respond(200, """
                {"output":{"task_status":"PENDING","task_id":"full-submit-task"}}
                """);

        TaskRequest request = requestForModelAtUrl(
                baseUrl + SUBMIT_PATH,
                "wan2.7-t2v-2026-06-12",
                null,
                5,
                "720P",
                "16:9",
                true);

        assertThat(adapter.submit(request).block(Duration.ofSeconds(5)).upstreamTaskId())
                .isEqualTo("full-submit-task");
        assertThat(requestPath.get()).isEqualTo(SUBMIT_PATH);

        adapter.poll(request, "full-submit-task").block(Duration.ofSeconds(5));
        assertThat(requestPath.get()).isEqualTo(TASK_PATH + "full-submit-task");
    }

    @Test
    void advertisesTheVideoCapabilityUnderTheBailianProviderType() {
        assertThat(adapter.providerType()).isEqualTo("BAILIAN");
        assertThat(adapter.capabilities()).containsExactly("VIDEO");
    }

    private TaskRequest request(int durationSeconds, String resolutionTier) {
        return new TaskRequest(
                channel(baseUrl, "wan2.7-t2v-2026-06-12"),
                "video-req-1",
                "黄昏的灯塔",
                null,
                durationSeconds,
                resolutionTier,
                "16:9",
                true,
                60_000);
    }

    private TaskRequest requestWithFirstFrame(String inputImageUrl) {
        return requestForModel(
                "wan2.7-t2v-2026-06-12",
                inputImageUrl,
                5,
                "1080P",
                null,
                false);
    }

    private TaskRequest requestForModel(
            String upstreamModel,
            String inputImageUrl,
            int durationSeconds,
            String resolutionTier,
            String aspectRatio,
            boolean withAudio) {
        return requestForModelAtUrl(
                baseUrl,
                upstreamModel,
                inputImageUrl,
                durationSeconds,
                resolutionTier,
                aspectRatio,
                withAudio);
    }

    private TaskRequest requestForModelAtUrl(
            String url,
            String upstreamModel,
            String inputImageUrl,
            int durationSeconds,
            String resolutionTier,
            String aspectRatio,
            boolean withAudio) {
        return new TaskRequest(
                channel(url, upstreamModel),
                "video-req-i2v",
                "让画面动起来",
                inputImageUrl,
                durationSeconds,
                resolutionTier,
                aspectRatio,
                withAudio,
                60_000);
    }

    private VideoGenerationAdapter.Channel channel(String url, String upstreamModel) {
        return new VideoGenerationAdapter.Channel(
                UUID.randomUUID(), UUID.randomUUID(), "BAILIAN", url, "sk-vendor-secret", upstreamModel);
    }

    private void assertUnknownHeld(TaskStatus status) {
        assertThat(status.state()).isEqualTo(TaskStatus.State.PENDING);
        assertThat(status.terminal()).isFalse();
        assertThat(status.billedSeconds()).isNull();
        assertThat(status.outputUrl()).isNull();
        assertThat(status.errorCategory()).isEqualTo("provider_task_unknown");
    }

    /** 用同一个固定响应体答复每个请求，并记录收到的请求内容。 */
    private void respond(int status, String body) throws Exception {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            asyncHeader.set(header(exchange, "X-DashScope-Async"));
            authorization.set(header(exchange, "Authorization"));
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            if (requestBytes.length > 0) {
                requestBody.set(JSON.readTree(requestBytes));
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    private String header(HttpExchange exchange, String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }
}

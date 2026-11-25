package com.yuzhihuihe.apiplatform.gateway.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuzhihuihe.apiplatform.gateway.audio.LipSyncAdapter.LipSyncRequest;
import com.yuzhihuihe.apiplatform.gateway.audio.LipSyncAdapter.LipSyncStatus;
import com.yuzhihuihe.apiplatform.gateway.audio.LipSyncAdapter.SubmittedLipSync;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * 网关与百炼声动人像 VideoRetalk 之间的线上契约，针对厂商的本地桩、
 * 通过真实 HTTP 演练。
 *
 * <p>这些断言让一条真实渠道无需付费调用就可被配置：异步提交端点、轮询端点、
 * 载荷、状态映射与**计费单位**都在此钉住，因此实跑中唯一未经验证的变量
 * 就是厂商自身的行为（以及运维填进去的凭证）。
 *
 * <p><b>本文件里最重要的一条是那条秒与帧的边界。</b>厂商终态同时回报
 * {@code usage.video_duration}（秒）与 {@code usage.fps}，而账单按秒计。
 * 把 fps 乘进去会让一次 25fps 的作业按 25 倍结算——这个错误不会让任何一次
 * 调用失败，只会静默地把客户的钱收多，因此它只能被一条断言钉住。
 */
class BailianLipSyncAdapterContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODEL = "videoretalk";
    private static final String VIDEO_URL = "https://cdn.example.test/portrait.mp4";
    private static final String AUDIO_URL = "https://cdn.example.test/voice.wav";
    private static final String SUBMIT_PATH = "/api/v1/services/aigc/image2video/video-synthesis";
    private static final String TASK_PATH = "/api/v1/tasks/";

    private HttpServer server;
    private String baseUrl;
    private BailianLipSyncAdapter adapter;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final Map<String, List<JsonNode>> bodiesByPath = new ConcurrentHashMap<>();
    private final Map<String, List<String>> methodsByPath = new ConcurrentHashMap<>();
    private final Map<String, List<String>> asyncHeadersByPath = new ConcurrentHashMap<>();
    private final Map<String, List<String>> authorizationsByPath = new ConcurrentHashMap<>();

    @BeforeEach
    void prepareStubVendor() throws Exception {
        // 仅回环地址、临时端口：这里的任何测试都不会发起外部调用。
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        adapter = new BailianLipSyncAdapter(WebClient.builder(), JSON);
    }

    @AfterEach
    void stopStubVendor() {
        if (server != null) {
            server.stop(0);
        }
    }

    // -----------------------------------------------------------------------
    // 异步提交
    // -----------------------------------------------------------------------

    @Test
    void submitsTheConfirmedAsyncPayloadAndSendsTheReferenceImageOnlyWhenGiven()
            throws Exception {
        respond(200, """
                {"output":{"task_id":"t-submit","task_status":"PENDING"},"request_id":"req-1"}
                """);

        adapter.submit(request(null, true)).block(Duration.ofSeconds(5));
        adapter.submit(request("https://cdn.example.test/face.png", false))
                .block(Duration.ofSeconds(5));

        // 两次提交打在同一个路径上，因此按**顺序**取它们各自的请求体，
        // 而不是读「最近一次」——后者会让第一次提交的载荷断言变成恒真。
        List<JsonNode> bodies = bodiesOn(SUBMIT_PATH);
        assertThat(bodies).hasSize(2);
        assertThat(methodsOn(SUBMIT_PATH)).containsExactly("POST", "POST");
        // 端点路径必须恰好是文档给出的那一个：多一层或少一层都只会在生产里 404。
        assertThat(requestPaths()).containsExactly(SUBMIT_PATH, SUBMIT_PATH);
        // 少了这个请求头厂商会直接拒绝调用，因此它与载荷同等重要。
        assertThat(asyncHeadersOn(SUBMIT_PATH)).containsExactly("enable", "enable");
        assertThat(authorizationsOn(SUBMIT_PATH))
                .containsExactly("Bearer sk-vendor-secret", "Bearer sk-vendor-secret");

        JsonNode withoutReference = bodies.get(0);
        // 「恰好这些字段」而不是「包含这些字段」：多一个未确认的字段就等于押注
        // 厂商会忽略它，而厂商未必忽略。
        assertThat(fieldNames(withoutReference))
                .containsExactlyInAnyOrder("model", "input", "parameters");
        // 模型名取自渠道的 upstream_model，绝不取自客户端提交的别名。
        assertThat(withoutReference.path("model").asText()).isEqualTo(MODEL);

        JsonNode firstInput = withoutReference.path("input");
        assertThat(fieldNames(firstInput)).containsExactlyInAnyOrder("video_url", "audio_url");
        assertThat(firstInput.path("video_url").asText()).isEqualTo(VIDEO_URL);
        assertThat(firstInput.path("audio_url").asText()).isEqualTo(AUDIO_URL);
        // 没有参考图时绝不发空字符串：空字符串与「未指定」在上游语义不同，
        // 而厂商会拿它去做人脸匹配。
        assertThat(firstInput.has("ref_image_url")).isFalse();

        JsonNode firstParameters = withoutReference.path("parameters");
        assertThat(fieldNames(firstParameters)).containsExactlyInAnyOrder("video_extension");
        // 这个开关决定「音频比视频长时扩展画面还是截断音频」，两者对客户是
        // 完全不同的产出，因此必须原样转发调用方的选择，而不是由适配器挑默认值。
        assertThat(firstParameters.path("video_extension").asBoolean()).isTrue();

        JsonNode withReference = bodies.get(1);
        assertThat(withReference.path("input").path("ref_image_url").asText())
                .isEqualTo("https://cdn.example.test/face.png");
        assertThat(withReference.path("parameters").path("video_extension").asBoolean()).isFalse();
    }

    @Test
    void submitReturnsTheTaskIdFromTheOutputEnvelope() throws Exception {
        respond(200, """
                {"output":{"task_id":"t-submit","task_status":"PENDING"},"request_id":"req-1"}
                """);

        SubmittedLipSync submitted = adapter.submit(request(null, true))
                .block(Duration.ofSeconds(5));

        // task id 是唯一能换回结果的东西：拿不到它，这次已付费的提交
        // 就再也无法被轮询，也就再也无法被交付。
        assertThat(submitted.upstreamTaskId()).isEqualTo("t-submit");
        assertThat(requestPaths()).containsExactly(SUBMIT_PATH);
    }

    // -----------------------------------------------------------------------
    // 轮询与计费单位
    // -----------------------------------------------------------------------

    /**
     * 计费单位是**秒**，且只读 {@code usage.video_duration}。
     *
     * <p>这是整个适配器最容易出错、也最贵的一处：{@code usage} 里同时有
     * {@code fps} 与 {@code size}，它们是产出的描述而不是计费单位。乘上 fps
     * 会得到 180（25 倍超收），截断会得到 7（少收 0.2 秒，而厂商按 7.2 秒收费）。
     * 前者不会让任何一次调用失败，只会静默地把客户的钱收多，因此这里把两个
     * 错误答案都显式排除，而不只是断言正确答案。
     */
    @Test
    void aSucceededPollBillsRoundedUpSecondsAndIgnoresFpsAndSize() throws Exception {
        serve(Map.of(TASK_PATH + "t-ok", """
                {"output":{"task_id":"t-ok","task_status":"SUCCEEDED",
                 "video_url":"https://cdn.example.test/out.mp4"},
                 "usage":{"video_duration":7.2,"size":"1080*1920","video_ratio":"standard","fps":25}}
                """));

        LipSyncStatus status = adapter.poll(request(null, true), "t-ok")
                .block(Duration.ofSeconds(5));

        assertThat(status.state()).isEqualTo(LipSyncStatus.State.SUCCEEDED);
        assertThat(requestPaths()).containsExactly(TASK_PATH + "t-ok");
        assertThat(status.outputUrl()).isEqualTo("https://cdn.example.test/out.mp4");
        assertThat(status.errorCategory()).isNull();

        int billed = status.billedSeconds();
        assertThat(billed == 180).as("不得把 fps 乘进计费秒数").isFalse();
        assertThat(billed == 7).as("不得把 7.2 秒截断成 7 秒").isFalse();
        // 厂商报 7.2 秒就按 7.2 秒收费，向上取整成 8 秒：
        // 报 7 秒会少收客户 0.2 秒的钱，而那是平台自己吃掉的成本。
        assertThat(billed).isEqualTo(8);
    }

    @Test
    void everyIntermediateVendorStatusMapsToNonTerminalRunning() throws Exception {
        // 四种状态各自的路径不同，因此每条断言读的都是**那个状态自己**的响应，
        // 不会出现「读到别的状态」的恒真断言。
        serve(Map.of(
                TASK_PATH + "t-pending", taskDocument("t-pending", "PENDING"),
                TASK_PATH + "t-pre", taskDocument("t-pre", "PRE-PROCESSING"),
                TASK_PATH + "t-post", taskDocument("t-post", "POST-PROCESSING"),
                TASK_PATH + "t-running", taskDocument("t-running", "RUNNING")));

        for (String taskId : new String[] {"t-pending", "t-pre", "t-post", "t-running"}) {
            LipSyncStatus status = adapter.poll(request(null, true), taskId)
                    .block(Duration.ofSeconds(5));

            // PRE-PROCESSING 与 POST-PROCESSING 是厂商特有的两个中间态，
            // 网关对它们与 RUNNING 的处理完全相同（继续轮询、继续持有预留），
            // 因此它们必须是同一个状态，而不是各占一个几乎一样的分支。
            assertThat(status.state()).as("%s 的状态", taskId)
                    .isEqualTo(LipSyncStatus.State.RUNNING);
            assertThat(status.terminal()).as("%s 不是终态", taskId).isFalse();
            // 非终态还无法知道会被计费多少：这里提前给出一个秒数
            // 就等于凭空向客户收费。
            assertThat(status.billedSeconds()).as("%s 的计费秒数", taskId).isNull();
        }
    }

    @Test
    void aSucceededPollWithoutAVideoUrlStaysOpen() throws Exception {
        // 有些上游会在带签名的结果位置可读之前就发布终态。
        serve(Map.of(TASK_PATH + "t-no-url", """
                {"output":{"task_id":"t-no-url","task_status":"SUCCEEDED"},
                 "usage":{"video_duration":8}}
                """));

        LipSyncStatus status = adapter.poll(request(null, true), "t-no-url")
                .block(Duration.ofSeconds(5));

        // 对它计费会留下一个「成功却永远取不到」的任务，而客户手上的
        // 那个 task id 再也换不回任何东西，因此它必须保持打开继续轮询。
        assertThat(status.terminal()).isFalse();
        assertThat(status.state()).isEqualTo(LipSyncStatus.State.PENDING);
        assertThat(status.errorCategory()).isEqualTo("provider_output_not_ready");
        assertThat(status.outputUrl()).isNull();
        // 位置没到就谈计费为时过早：秒数留空，等真正的终态再结算。
        assertThat(status.billedSeconds()).isNull();
    }

    @Test
    void aFailedPollIsTerminalAndCarriesTheVendorsOwnErrorCode() throws Exception {
        serve(Map.of(
                TASK_PATH + "t-fail", """
                        {"output":{"task_id":"t-fail","task_status":"FAILED",
                         "code":"InvalidParameter","message":"The audio is too long"}}
                        """,
                TASK_PATH + "t-fail-uncoded", taskDocument("t-fail-uncoded", "FAILED")));

        LipSyncStatus failed = adapter.poll(request(null, true), "t-fail")
                .block(Duration.ofSeconds(5));

        assertThat(failed.state()).isEqualTo(LipSyncStatus.State.FAILED);
        assertThat(failed.terminal()).isTrue();
        // 厂商自己的错误码比通用分类具体得多：客服据此才能回答「为什么失败」。
        assertThat(failed.errorCategory()).isEqualTo("InvalidParameter");
        assertThat(failed.outputUrl()).isNull();

        // 厂商没给码时才回退到网关自己的分类，而不是把空串写进日志。
        LipSyncStatus uncoded = adapter.poll(request(null, true), "t-fail-uncoded")
                .block(Duration.ofSeconds(5));
        assertThat(uncoded.state()).isEqualTo(LipSyncStatus.State.FAILED);
        assertThat(uncoded.errorCategory()).isEqualTo("provider_lip_sync_failed");
    }

    @Test
    void aFailedPollWithoutUsageBillsNothing() throws Exception {
        serve(Map.of(TASK_PATH + "t-fail-no-usage", taskDocument("t-fail-no-usage", "FAILED")));

        LipSyncStatus status = adapter.poll(request(null, true), "t-fail-no-usage")
                .block(Duration.ofSeconds(5));

        assertThat(status.state()).isEqualTo(LipSyncStatus.State.FAILED);
        // VideoRetalk 是后付费且按产出计费：厂商没有报告用量，就没有证据
        // 证明这次失败的作业消耗了可计费时间，因此计零——
        // 按预留秒数收费会变成向客户收取一段并不存在的视频。
        assertThat(status.billedSeconds()).isZero();
    }

    @Test
    void aFailedPollWithReportedUsageBillsThoseSeconds() throws Exception {
        serve(Map.of(TASK_PATH + "t-fail-usage", """
                {"output":{"task_id":"t-fail-usage","task_status":"FAILED",
                 "code":"InternalError"},
                 "usage":{"video_duration":5.4}}
                """));

        LipSyncStatus status = adapter.poll(request(null, true), "t-fail-usage")
                .block(Duration.ofSeconds(5));

        // 厂商真的报告了用量时读它自己的数字，而不是假定失败一定是零：
        // 5.4 秒按与成功路径完全相同的规则向上取整成 6（截断会少收 0.4 秒）。
        assertThat(status.state()).isEqualTo(LipSyncStatus.State.FAILED);
        assertThat(status.billedSeconds()).isEqualTo(6);
    }

    @Test
    void anUnknownOrMissingTaskStatusKeepsTheReservationHeld() throws Exception {
        serve(Map.of(
                TASK_PATH + "t-unknown", taskDocument("t-unknown", "UNKNOWN"),
                // 厂商日后新增的状态也必须落在同一条规则上，否则一次
                // 正常的升级就会让在途的作业全部按「已完成」或「已失败」结算。
                TASK_PATH + "t-future", taskDocument("t-future", "PAUSED_BY_VENDOR"),
                TASK_PATH + "t-missing", "{\"output\":{\"task_id\":\"t-missing\"}}"));

        for (String taskId : new String[] {"t-unknown", "t-future", "t-missing"}) {
            LipSyncStatus status = adapter.poll(request(null, true), taskId)
                    .block(Duration.ofSeconds(5));

            // UNKNOWN 无法证明任务被拒绝，缺失的状态同理：在拿到**显式**终态
            // 之前继续持有预留，绝不用一个猜测把客户的预扣款放掉或收掉。
            assertThat(status.terminal()).as("%s 不是终态", taskId).isFalse();
            assertThat(status.state()).as("%s 的状态", taskId)
                    .isEqualTo(LipSyncStatus.State.PENDING);
            assertThat(status.errorCategory()).as("%s 的分类", taskId)
                    .isEqualTo("provider_task_unknown");
            assertThat(status.billedSeconds()).as("%s 的计费秒数", taskId).isNull();
        }
    }

    @Test
    void aBlankTaskIdIsHeldAsUnknownWithoutAnyHttpCall() {
        // 没有 task id 就没有可轮询的对象。桩在这里根本没被启动，
        // 因此任何一次上游调用都会让 requestCount 大于零。
        LipSyncStatus blank = adapter.poll(request(null, true), "   ")
                .block(Duration.ofSeconds(5));
        LipSyncStatus missing = adapter.poll(request(null, true), null)
                .block(Duration.ofSeconds(5));

        for (LipSyncStatus status : List.of(blank, missing)) {
            assertThat(status.terminal()).isFalse();
            assertThat(status.state()).isEqualTo(LipSyncStatus.State.PENDING);
            assertThat(status.errorCategory()).isEqualTo("provider_task_unknown");
        }
        // 空 id 拼进 {@code /api/v1/tasks/} 只会打到任务列表端点，
        // 那里返回的东西与这个作业毫无关系，因此绝不能发出去。
        assertThat(requestCount.get()).isZero();
    }

    // -----------------------------------------------------------------------
    // 本地校验
    // -----------------------------------------------------------------------

    @Test
    void rejectsAnUnlistedModelBeforeAnyHttpCall() throws Exception {
        // 桩是启动着的，因此「没有请求」是一条有内容的断言。
        respond(200, taskDocument("t-unused", "PENDING"));

        LipSyncRequest request = requestForModel("videoretalk-pro", null, true, 10);

        assertThatThrownBy(() -> adapter.validateSubmission(request))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason())
                                .isEqualTo("unsupported_bailian_lip_sync_model"));

        // 未被确认的模型名一律拒绝而不是转发：一次被猜错的调用不只浪费一次
        // 付费提交，还会在上游留下一句与配置无关的错误。
        assertThat(requestCount.get()).isZero();
    }

    @Test
    void enforcesTheVendorsOpenInputDurationInterval() throws Exception {
        // 桩是启动着的，因此「本地就拒绝掉」这件事可以被证明。
        respond(200, taskDocument("t-unused", "PENDING"));

        // 厂商文档给的是**开区间** 2 < duration < 120：恰好 2 秒与恰好 120 秒的
        // 输入都会被厂商拒绝，因此网关用同一区间做前置检查——否则一个 1 秒的
        // 请求会先付掉一次提交，才在上游被拒绝。
        assertThat(BailianLipSyncAdapter.MIN_INPUT_SECONDS).isEqualTo(2);
        assertThat(BailianLipSyncAdapter.MAX_INPUT_SECONDS).isEqualTo(120);

        for (int seconds : new int[] {2, 120}) {
            assertThatThrownBy(() -> adapter.validateSubmission(
                            requestForModel(MODEL, null, true, seconds)))
                    .as("%d 秒落在开区间之外", seconds)
                    .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                        assertThat(error.getReason())
                                .isEqualTo("lip_sync_duration_not_supported");
                        // 422 而不是 5xx：这是调用方自己可以改正的请求画像，
                        // 而不是上游故障。
                        assertThat(error.getStatusCode().value()).isEqualTo(422);
                    });
        }

        for (int seconds : new int[] {3, 119}) {
            assertThatCode(() -> adapter.validateSubmission(
                            requestForModel(MODEL, null, true, seconds)))
                    .as("%d 秒落在开区间之内", seconds)
                    .doesNotThrowAnyException();
        }

        assertThat(requestCount.get()).isZero();
    }

    @Test
    void rejectsRelativeOrNonHttpMediaLocationsAtConstruction() {
        // 厂商要自己去拉这两个文件，因此相对路径、data: URI 或别的协议
        // 只会在上游变成一次难懂的错误——而那时提交已经付过费了。
        assertThatThrownBy(() -> new LipSyncRequest(
                        channel(baseUrl, MODEL), "lip-sync-req-relative", "portrait.mp4",
                        AUDIO_URL, null, true, 10, 60_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid_lip_sync_request");

        assertThatThrownBy(() -> new LipSyncRequest(
                        channel(baseUrl, MODEL), "lip-sync-req-ftp", VIDEO_URL,
                        "ftp://cdn.example.test/voice.wav", null, true, 10, 60_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid_lip_sync_request");

        assertThatThrownBy(() -> new LipSyncRequest(
                        channel(baseUrl, MODEL), "lip-sync-req-blank", VIDEO_URL,
                        "  ", null, true, 10, 60_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid_lip_sync_request");

        // 参考图也一样：厂商会拿它去做人脸匹配，一个拉不到的地址会变成
        // 一次无声的错误匹配。
        assertThatThrownBy(() -> new LipSyncRequest(
                        channel(baseUrl, MODEL), "lip-sync-req-ref", VIDEO_URL, AUDIO_URL,
                        "face.png", true, 10, 60_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid_lip_sync_request");
    }

    @Test
    void advertisesTheLipSyncCapabilityUnderTheBailianProviderType() {
        assertThat(adapter.providerType()).isEqualTo("BAILIAN");
        assertThat(adapter.capabilities()).containsExactly("LIP_SYNC");
    }

    // -----------------------------------------------------------------------
    // URL 解析：运维存的是哪种形态都不该改变端点
    // -----------------------------------------------------------------------

    @Test
    void everyAcceptedBaseUrlFormResolvesToTheSameSubmitAndTaskEndpoints() {
        String origin = "https://dashscope.aliyuncs.com";
        // 运维可能存的是厂商源站、带版本的根、从文档里原样复制的提交端点，
        // 甚至是一个任务查询地址。每一种都必须解析出恰好一个提交端点：
        // 多拼一层路径会让每一次真实提交 404，而这一条只有在这里才会被发现。
        for (String form : new String[] {
                origin,
                origin + "/",
                origin + "/api/v1",
                origin + "/api/v1/",
                origin + SUBMIT_PATH,
                origin + TASK_PATH + "t-9"}) {
            assertThat(BailianLipSyncAdapter.submitUri(channel(form, MODEL)))
                    .as("submit uri for base url form %s", form)
                    .isEqualTo(origin + SUBMIT_PATH);
        }

        // 任务查询：task id 走路径而不是查询串，且父路径推导不会因为
        // base_url 里已经有一段完整路径而多拼一层版本号。
        assertThat(BailianLipSyncAdapter.taskUri(channel(origin, MODEL), "t-1"))
                .isEqualTo(origin + TASK_PATH + "t-1");
        assertThat(BailianLipSyncAdapter.taskUri(channel(origin + SUBMIT_PATH, MODEL), "t-1"))
                .isEqualTo(origin + TASK_PATH + "t-1");
        assertThat(BailianLipSyncAdapter.taskUri(
                        channel(origin + TASK_PATH + "t-9", MODEL), "t-1"))
                .isEqualTo(origin + TASK_PATH + "t-1");
    }

    // -----------------------------------------------------------------------
    // 夹具
    // -----------------------------------------------------------------------

    private LipSyncRequest request(String referenceImageUrl, boolean extendVideo) {
        return requestForModel(MODEL, referenceImageUrl, extendVideo, 10);
    }

    private LipSyncRequest requestForModel(
            String upstreamModel, String referenceImageUrl, boolean extendVideo,
            int reservedSeconds) {
        return new LipSyncRequest(
                channel(baseUrl, upstreamModel),
                "lip-sync-req-" + UUID.randomUUID(),
                VIDEO_URL,
                AUDIO_URL,
                referenceImageUrl,
                extendVideo,
                reservedSeconds,
                60_000);
    }

    private LipSyncAdapter.Channel channel(String url, String upstreamModel) {
        return new LipSyncAdapter.Channel(
                UUID.randomUUID(), UUID.randomUUID(), "BAILIAN", url, "sk-vendor-secret",
                upstreamModel);
    }

    /** 厂商任务文档的最小形态，只有 id 与状态。 */
    private static String taskDocument(String taskId, String taskStatus) {
        return "{\"output\":{\"task_id\":\"" + taskId + "\",\"task_status\":\""
                + taskStatus + "\"}}";
    }

    /** 用同一个固定响应体答复每个请求，并记录收到的请求内容。 */
    private void respond(int status, String body) throws Exception {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            captureRequest(exchange);
            send(exchange, status, response);
        });
        server.start();
    }

    /**
     * 每个路径各自的 200 响应，用于一次测试里演练多个厂商状态。
     *
     * <p>未登记的路径一律 404：这保证一次走错端点的调用会立刻变红，
     * 而不是被一个「什么都能答」的桩掩盖过去。
     */
    private void serve(Map<String, String> responsesByPath) throws Exception {
        server.createContext("/", exchange -> {
            captureRequest(exchange);
            String body = responsesByPath.get(exchange.getRequestURI().getPath());
            send(exchange, body == null ? 404 : 200,
                    (body == null ? "{\"error\":{\"code\":\"NotFound\"}}" : body)
                            .getBytes(StandardCharsets.UTF_8));
        });
        server.start();
    }

    private void send(HttpExchange exchange, int status, byte[] response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    /** 记录一次入站请求：计数、路径、方法与请求头，并按路径归档请求体。 */
    private void captureRequest(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        paths.add(path);
        addTo(methodsByPath, path, exchange.getRequestMethod());
        // 轮询不带这个头，因此这里记录到的 null 本身就是一条断言材料。
        addTo(asyncHeadersByPath, path, exchange.getRequestHeaders().getFirst("X-DashScope-Async"));
        addTo(authorizationsByPath, path, exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        if (requestBytes.length > 0) {
            addTo(bodiesByPath, path, JSON.readTree(requestBytes));
        }
    }

    private static void addTo(Map<String, List<String>> capture, String path, String value) {
        capture.computeIfAbsent(path, key -> new CopyOnWriteArrayList<>()).add(value);
    }

    private static void addTo(Map<String, List<JsonNode>> capture, String path, JsonNode value) {
        capture.computeIfAbsent(path, key -> new CopyOnWriteArrayList<>()).add(value);
    }

    /** 收到的请求路径，用于钉住「只打了这一个端点」以及调用顺序。 */
    private List<String> requestPaths() {
        return paths;
    }

    private List<JsonNode> bodiesOn(String path) {
        List<JsonNode> bodies = bodiesByPath.get(path);
        return bodies == null ? List.<JsonNode>of() : bodies;
    }

    private List<String> methodsOn(String path) {
        List<String> methods = methodsByPath.get(path);
        return methods == null ? List.<String>of() : methods;
    }

    private List<String> asyncHeadersOn(String path) {
        List<String> values = asyncHeadersByPath.get(path);
        return values == null ? List.<String>of() : values;
    }

    private List<String> authorizationsOn(String path) {
        List<String> values = authorizationsByPath.get(path);
        return values == null ? List.<String>of() : values;
    }

    /** 请求体的字段集合，用于钉住「恰好这些字段」。 */
    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}

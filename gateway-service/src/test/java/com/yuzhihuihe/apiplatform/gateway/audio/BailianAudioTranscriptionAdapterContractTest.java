package com.yuzhihuihe.apiplatform.gateway.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuzhihuihe.apiplatform.gateway.audio.AudioTranscriptionAdapter.TranscriptionRequest;
import com.yuzhihuihe.apiplatform.gateway.audio.AudioTranscriptionAdapter.TranscriptionResult;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * 网关与百炼语音转写之间的线上契约，针对厂商的本地桩、通过真实 HTTP 演练。
 *
 * <p>这些断言让一条真实渠道无需付费调用就可被配置：两套端点、提交载荷、
 * 轮询路径、结果文件解析与计费秒数都在此钉住，因此实跑中唯一未经验证的变量
 * 就是厂商自身的行为（以及运维填进去的凭证）。
 *
 * <p><b>其中一条断言是需求问题的证据，不只是契约检查。</b>
 * {@link #theSynchronousModelReturnsTextButNoTimestampsSoThereIsNoSubtitleTrack}
 * 钉住的是：需求点名的 {@code qwen-audio-3.0-asr-flash} 走的是同步端点，
 * 而那个端点**不返回时间戳**。需求同时要求「带时间戳的字幕」，
 * 因此它必须改用 {@code -filetrans} 模型。把这条写成测试而不是注释，
 * 是因为它决定下游能不能拿到字幕，而一个只在文档里存在的警告会被遗忘。
 */
class BailianAudioTranscriptionAdapterContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SYNC_MODEL = "qwen-audio-3.0-asr-flash";
    private static final String FILETRANS_MODEL = "qwen-audio-3.0-asr-flash-filetrans";

    private static final String SYNC_PATH =
            "/api/v1/services/aigc/multimodal-generation/generation";
    private static final String SUBMIT_PATH = "/api/v1/services/audio/asr/transcription";

    private HttpServer server;
    private String baseUrl;
    private BailianAudioTranscriptionAdapter adapter;
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> asyncHeader = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void prepareStubVendor() throws Exception {
        // 仅回环地址、临时端口：这里的任何测试都不会发起外部调用。
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        adapter = new BailianAudioTranscriptionAdapter(WebClient.builder(), JSON);
    }

    @AfterEach
    void stopStubVendor() {
        if (server != null) {
            server.stop(0);
        }
    }

    // -----------------------------------------------------------------------
    // 同步模型：只有文本
    // -----------------------------------------------------------------------

    /**
     * 需求点名的那条路：同步端点。
     *
     * <p>响应体照上游文档给出的**非标准**结构构造
     * （文本在 {@code output.text} 与 {@code output.output.sentence.text}，
     * 没有 {@code choices}）。结果是：文本拿到了，**字幕轨是空的**。
     */
    @Test
    void theSynchronousModelReturnsTextButNoTimestampsSoThereIsNoSubtitleTrack() throws Exception {
        respond(200, """
                {"output":{"output":{"sentence":{"text":"Hello World，这里是阿里巴巴语音实验室。"}},
                 "text":"Hello World，这里是阿里巴巴语音实验室。"},
                 "request_id":"7574ee8f"}
                """);

        TranscriptionResult result = adapter
                .transcribe(request(SYNC_MODEL, "https://cdn.example.test/voice.wav", 12))
                .block(Duration.ofSeconds(5));

        assertThat(result.text()).isEqualTo("Hello World，这里是阿里巴巴语音实验室。");
        // 这就是需求问题的证据：这个模型不产出字幕，因此 hasTimestamps() 为假。
        // 下游必须把「空」读成「本次没有字幕」，而不是「字幕内容为空」。
        assertThat(result.segments()).isEmpty();
        assertThat(result.hasTimestamps()).isFalse();

        assertThat(requestMethod.get()).isEqualTo("POST");
        assertThat(requestPath.get()).isEqualTo(SYNC_PATH);
        assertThat(authorization.get()).isEqualTo("Bearer sk-vendor-secret");

        JsonNode body = requestBody.get();
        assertThat(body.path("model").asText()).isEqualTo(SYNC_MODEL);
        JsonNode content = body.path("input").path("messages").get(0).path("content").get(0);
        // 音频走 input_audio.data，且必须是厂商能自己去拉的绝对地址。
        assertThat(content.path("type").asText()).isEqualTo("input_audio");
        assertThat(content.path("input_audio").path("data").asText())
                .isEqualTo("https://cdn.example.test/voice.wav");
        // 这两个是上游的多模态参数，漏掉会让部分格式的音频识别失败。
        assertThat(body.path("parameters").path("format").asText()).isEqualTo("wav");
        assertThat(body.path("parameters").path("sample_rate").asText()).isEqualTo("16000");
        // 同步端点不接受异步头：发了它反而会被当成异步调用。
        assertThat(asyncHeader.get()).isNull();
    }

    /**
     * 同步端点**不返回音频时长**，因此计费秒数只能取服务层的预留值。
     *
     * <p>这条断言把这个妥协写下来：响应里确实没有时长的任何字段，
     * 所以「客户声明了多长」是此刻唯一存在的长度。换用 {@code -filetrans}
     * 模型就能拿到厂商实测的时长（下一条测试）。
     */
    @Test
    void theSynchronousModelBillsTheReservedSecondsBecauseTheVendorReportsNoDuration()
            throws Exception {
        respond(200, """
                {"output":{"text":"一段话"},"request_id":"r"}
                """);

        TranscriptionResult result = adapter
                .transcribe(request(SYNC_MODEL, "https://cdn.example.test/voice.wav", 37))
                .block(Duration.ofSeconds(5));

        assertThat(result.billedSeconds()).isEqualTo(37);
    }

    @Test
    void anEmptyTranscriptionIsAProtocolFailureRatherThanAnEmptySubtitle() throws Exception {
        respond(200, """
                {"output":{"output":{"sentence":{"text":""}},"text":""},"request_id":"r"}
                """);

        assertThatThrownBy(() -> adapter
                .transcribe(request(SYNC_MODEL, "https://cdn.example.test/voice.wav", 10))
                .block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error -> {
                    assertThat(error.status()).isEqualTo(502);
                    assertThat(error.getMessage()).contains("provider_invalid_transcription");
                });
    }

    // -----------------------------------------------------------------------
    // 异步 filetrans 模型：真正的字幕
    // -----------------------------------------------------------------------

    /**
     * 需求真正需要的那个模型：{@code -filetrans}。
     *
     * <p>三步走完：提交任务 → 轮询到终态 → 下载识别结果文件。
     * 时间戳在**第三个**响应里，因此这条测试覆盖的是完整链路，
     * 而不是只覆盖提交。
     */
    @Test
    void theFiletransModelSubmitsPollsAndReturnsSentenceLevelTimestamps() throws Exception {
        serveTranscriptionFlow("""
                {"file_url":"https://cdn.example.test/voice.wav",
                 "properties":{"original_duration_in_milliseconds":3834},
                 "transcripts":[{"channel_id":0,
                   "text":"Hello World，这里是阿里巴巴语音实验室。欢迎使用。",
                   "sentences":[
                     {"begin_time":760,"end_time":3240,"sentence_id":1,
                      "text":"Hello World，这里是阿里巴巴语音实验室。"},
                     {"begin_time":3400,"end_time":3834,"sentence_id":2,
                      "text":"欢迎使用。"}]}]}
                """);

        TranscriptionResult result = adapter
                .transcribe(request(FILETRANS_MODEL, "https://cdn.example.test/voice.wav", 30))
                .block(Duration.ofSeconds(10));

        assertThat(result.text()).contains("Hello World").contains("欢迎使用");
        assertThat(result.hasTimestamps()).isTrue();
        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().get(0).beginTimeMs()).isEqualTo(760);
        assertThat(result.segments().get(0).endTimeMs()).isEqualTo(3240);
        assertThat(result.segments().get(1).text()).isEqualTo("欢迎使用。");

        // 计费秒数取厂商实测的 3834 毫秒并向上取整：按 3 秒结算会少收
        // 0.834 秒的钱，而厂商确实按 3.834 秒收费。
        assertThat(result.billedSeconds()).isEqualTo(4);
    }

    @Test
    void theFiletransSubmissionDeclaresItselfAsynchronousAndCarriesLanguageHints()
            throws Exception {
        serveTranscriptionFlow("""
                {"properties":{"original_duration_in_milliseconds":5000},
                 "transcripts":[{"text":"你好","sentences":[]}]}
                """);

        adapter.transcribe(new TranscriptionRequest(
                        channel(baseUrl, FILETRANS_MODEL),
                        "asr-req-hints",
                        "https://cdn.example.test/voice.wav",
                        "wav",
                        "16000",
                        List.of("zh", "en"),
                        30,
                        60_000))
                .block(Duration.ofSeconds(10));

        // 断言必须看**提交**那一次请求，而不是最后一次：这条链路后面还有
        // 一次结果文件下载，笼统地读「最近一次」会读到下载请求，
        // 那里的异步头与 body 都是空的，于是断言会变成恒真。
        assertThat(asyncHeaderOn(SUBMIT_PATH)).isEqualTo("enable");
        JsonNode submitted = bodyOn(SUBMIT_PATH);
        assertThat(submitted).isNotNull();
        assertThat(submitted.path("model").asText()).isEqualTo(FILETRANS_MODEL);
        assertThat(submitted.path("input").path("file_urls").get(0).asText())
                .isEqualTo("https://cdn.example.test/voice.wav");
        JsonNode hints = submitted.path("parameters").path("language_hints");
        assertThat(hints.isArray()).isTrue();
        assertThat(hints.get(0).asText()).isEqualTo("zh");
        assertThat(hints.get(1).asText()).isEqualTo("en");

        // 结果文件下载是**签名 URL**，因此它绝不能携带网关的凭证：
        // 把 Authorization 发给一个对象存储域名就是凭证泄漏。
        assertThat(authorizationOn("/result.json")).isNull();
    }

    /**
     * 结果文件的签名地址必须**原样**发出，一个字符都不能改写。
     *
     * <p>厂商把转写结果放在带签名的对象存储地址上，签名覆盖**逐字的**路径与查询串
     * ——真实地址里就有 {@code %3A}（时间里的冒号）、{@code %3D}（签名尾部的等号）
     * 与 {@code %3B}（{@code response-content-disposition} 里的分号）。
     * 用 {@code WebClient.uri(String)} 发这种地址会把它当成 URI **模板**再编码一次
     * （{@code %} → {@code %25}），对象存储于是报 {@code SignatureDoesNotMatch}。
     *
     * <p>它返回的是一份 **XML** 错误体，网关解析不出错误码，最终表现为一条没有错误码的
     * {@code provider_rejected_transcription}——看起来像「上游拒绝」，实际是我们自己
     * 改写了 URL。这条用例用一个真实形态的签名地址，断言桩收到的原始路径与查询串
     * 与给定的完全一致；既有用例的 {@code /result.json} 没有任何需要编码的字符，
     * 因此它证明不了这件事。
     */
    @Test
    void theSignedResultUrlIsFetchedVerbatim() throws Exception {
        String signedPath = "/prod/fun-asr/20261009/11%3A03/abc.json";
        String signedQuery = "Expires=1791601334&Signature=a%3Db"
                + "&response-content-disposition=attachment%3Bfilename%3Dabc.json";
        AtomicReference<String> rawTarget = new AtomicReference<>();

        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (SUBMIT_PATH.equals(path)) {
                    writeJson(exchange, 200,
                            "{\"output\":{\"task_id\":\"t-signed\",\"task_status\":\"PENDING\"}}");
                    return;
                }
                if (path.startsWith("/api/v1/tasks/")) {
                    writeJson(exchange, 200,
                            "{\"output\":{\"task_id\":\"t-signed\",\"task_status\":\"SUCCEEDED\","
                                    + "\"results\":[{\"subtask_status\":\"SUCCEEDED\","
                                    + "\"transcription_url\":\"" + baseUrl + signedPath
                                    + "?" + signedQuery + "\"}]}}");
                    return;
                }
                rawTarget.set(exchange.getRequestURI().getRawPath()
                        + "?" + exchange.getRequestURI().getRawQuery());
                writeJson(exchange, 200,
                        "{\"transcripts\":[{\"text\":\"hi\"}],"
                                + "\"properties\":{\"original_duration_in_milliseconds\":1000}}");
            } catch (Throwable error) {
                System.err.println("[STUB] handler threw: " + error);
            }
        });
        server.start();

        TranscriptionResult result = adapter
                .transcribe(request(FILETRANS_MODEL, "https://cdn.example.test/voice.wav", 60))
                .block(Duration.ofSeconds(10));

        assertThat(result.text()).isEqualTo("hi");
        assertThat(rawTarget.get()).isEqualTo(signedPath + "?" + signedQuery);
    }

    /**
     * 如实声明一段**短**音频，也必须拿到足够多次轮询等厂商排队。
     *
     * <p>厂商的等待时间来自排队与处理，与音频长度**不成比例**：一段 3 秒的音频同样
     * 可能排十几秒。原先的轮询预算是 {@code min(600, max(1, 声明秒数/10))}，
     * 下限是 **1 次**：一次如实声明 5 秒的转写，在厂商仍处于前置处理时就耗尽预算，
     * 被判成 {@code provider_task_unknown}——任务落 FAILED、预留按「结果未知」保留
     * （那是对 5xx/协议异常的保守策略，用在「排队中」是错的）。
     * 客户看到的是上游故障，而厂商其实马上就要给出结果。
     *
     * <p>本用例让桩在**第一次**查询时仍报 RUNNING，第二次才 SUCCEEDED：
     * 旧规则下这次调用必失败，新规则下必须成功。
     */
    @Test
    void aShortDeclarationStillGetsEnoughPollsForTheVendorsQueue() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (SUBMIT_PATH.equals(path)) {
                    writeJson(exchange, 200,
                            "{\"output\":{\"task_id\":\"t-queued\",\"task_status\":\"PENDING\"}}");
                    return;
                }
                if (path.startsWith("/api/v1/tasks/")) {
                    if (polls.incrementAndGet() == 1) {
                        // 厂商还在前置处理：这不是故障，是排队。
                        writeJson(exchange, 200,
                                "{\"output\":{\"task_id\":\"t-queued\",\"task_status\":\"RUNNING\"}}");
                        return;
                    }
                    writeJson(exchange, 200,
                            "{\"output\":{\"task_id\":\"t-queued\",\"task_status\":\"SUCCEEDED\","
                                    + "\"results\":[{\"subtask_status\":\"SUCCEEDED\","
                                    + "\"transcription_url\":\"" + baseUrl + "/result.json\"}]}}");
                    return;
                }
                writeJson(exchange, 200,
                        "{\"transcripts\":[{\"text\":\"queued but done\"}],"
                                + "\"properties\":{\"original_duration_in_milliseconds\":3000}}");
            } catch (Throwable error) {
                System.err.println("[STUB] handler threw: " + error);
            }
        });
        server.start();

        // 3 秒音频如实声明 5 秒——按旧规则这只换来 1 次轮询。
        TranscriptionResult result = adapter
                .transcribe(request(FILETRANS_MODEL, "https://cdn.example.test/voice.wav", 5))
                .block(Duration.ofSeconds(30));

        assertThat(result.text()).isEqualTo("queued but done");
        assertThat(polls.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void aFailedFiletransTaskIsAnUpstreamFaultCarryingTheVendorsCode() throws Exception {
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body;
            if (SUBMIT_PATH.equals(path)) {
                body = "{\"output\":{\"task_id\":\"t-fail\",\"task_status\":\"PENDING\"}}";
            } else {
                body = "{\"output\":{\"task_id\":\"t-fail\",\"task_status\":\"FAILED\","
                        + "\"code\":\"InvalidFile.Format\"}}";
            }
            writeJson(exchange, 200, body);
        });
        server.start();

        assertThatThrownBy(() -> adapter
                .transcribe(request(FILETRANS_MODEL, "https://cdn.example.test/voice.wav", 30))
                .block(Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error -> {
                    assertThat(error.status()).isEqualTo(502);
                    assertThat(error.getMessage()).contains("InvalidFile.Format");
                });
    }

    // -----------------------------------------------------------------------
    // 校验与错误翻译
    // -----------------------------------------------------------------------

    @Test
    void rejectsAnUnlistedModelBeforeAnyHttpCall() {
        assertThatThrownBy(() -> adapter.validateSubmission(
                        request("paraformer-v2", "https://cdn.example.test/voice.wav", 10)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("unsupported_bailian_asr_model"));

        // 没有发出任何请求，因此预留不会被一个注定失败的调用占住。
        assertThat(requestCount.get()).isZero();
    }

    @Test
    void rejectsARelativeAudioLocationBeforeAnyHttpCall() {
        // 厂商要自己去下载这个文件，因此相对路径只会在上游变成一次难懂的错误。
        assertThatThrownBy(() -> request(SYNC_MODEL, "voice.wav", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid_audio_transcription_request");
    }

    @Test
    void anUpstreamRefusalIsReportedAsAnUpstreamFaultWithTheVendorsCode() throws Exception {
        respond(400, """
                {"error":{"code":"InvalidApiKey","message":"Invalid API-key provided."}}
                """);

        assertThatThrownBy(() -> adapter
                .transcribe(request(SYNC_MODEL, "https://cdn.example.test/voice.wav", 10))
                .block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error -> {
                    assertThat(error.status()).isEqualTo(400);
                    assertThat(error.getMessage())
                            .contains("provider_rejected_transcription:InvalidApiKey");
                });
    }

    @Test
    void aTopLevelErrorCodeOnATwoHundredIsAnUpstreamFault() throws Exception {
        // 百炼另有一个顶层 code 形态（成功时为空字符串）。
        respond(200, """
                {"code":"DataInspectionFailed","message":"blocked"}
                """);

        assertThatThrownBy(() -> adapter
                .transcribe(request(SYNC_MODEL, "https://cdn.example.test/voice.wav", 10))
                .block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error -> {
                    assertThat(error.status()).isEqualTo(502);
                    assertThat(error.getMessage())
                            .contains("provider_error_response:DataInspectionFailed");
                });
    }

    @Test
    void advertisesTheTranscriptionCapabilityUnderTheBailianProviderType() {
        assertThat(adapter.providerType()).isEqualTo("BAILIAN");
        assertThat(adapter.capabilities()).containsExactly("AUDIO_TRANSCRIBE");
    }

    // -----------------------------------------------------------------------
    // URL 解析：运维存的是哪种形态都不该改变端点
    // -----------------------------------------------------------------------

    @Test
    void everyAcceptedBaseUrlFormResolvesToTheSameEndpoints() {
        String origin = "https://dashscope.aliyuncs.com";
        for (String form : new String[] {
                origin,
                origin + "/",
                origin + "/api/v1",
                origin + "/api/v1/",
                origin + SYNC_PATH,
                origin + SUBMIT_PATH}) {
            assertThat(BailianAudioTranscriptionAdapter.syncUri(channel(form, SYNC_MODEL)))
                    .as("sync uri for base url form %s", form)
                    .isEqualTo(origin + SYNC_PATH);
            assertThat(BailianAudioTranscriptionAdapter.submitUri(channel(form, FILETRANS_MODEL)))
                    .as("submit uri for base url form %s", form)
                    .isEqualTo(origin + SUBMIT_PATH);
        }
        // 任务查询：task id 走路径，且把完整端点粘进 base_url 也不会多拼一层。
        assertThat(BailianAudioTranscriptionAdapter.taskUri(
                        channel(origin + SUBMIT_PATH, FILETRANS_MODEL), "t-1"))
                .isEqualTo(origin + "/api/v1/tasks/t-1");
        assertThat(BailianAudioTranscriptionAdapter.taskUri(
                        channel(origin + "/api/v1/tasks/t-9", FILETRANS_MODEL), "t-1"))
                .isEqualTo(origin + "/api/v1/tasks/t-1");
    }

    /**
     * 天翼网关（{@code ai.ctaigw.cn}）跑的是**同一套 DashScope 协议**，版本根却是
     * {@code /v1}——少一层 {@code /api}。路径尾部、请求体、以及
     * {@code X-DashScope-Async} / {@code X-DashScope-SSE} 两个头都与百炼逐字相同，
     * 价格也都是 0.00022 元/秒。因此适配器不需要第二份实现，只需要认识这个根。
     *
     * <p>裸域名（不带任何版本根）**不告诉我们是哪个平台**，按百炼的老口径回退，
     * 这样既有渠道的写法不会被新增一个平台改坏。
     */
    @Test
    void acceptsTheTianyiGatewayRootWhichHasNoApiSegment() {
        String origin = "https://ai.ctaigw.cn";

        assertThat(BailianAudioTranscriptionAdapter.syncUri(channel(origin + "/v1", SYNC_MODEL)))
                .isEqualTo(origin + "/v1/services/aigc/multimodal-generation/generation");
        assertThat(BailianAudioTranscriptionAdapter.submitUri(
                        channel(origin + "/v1", FILETRANS_MODEL)))
                .isEqualTo(origin + "/v1/services/audio/asr/transcription");
        assertThat(BailianAudioTranscriptionAdapter.taskUri(
                        channel(origin + "/v1", FILETRANS_MODEL), "t-1"))
                .isEqualTo(origin + "/v1/tasks/t-1");
        // 把整段端点原样粘进 base_url 也不能多拼一层。
        assertThat(BailianAudioTranscriptionAdapter.submitUri(
                        channel(origin + "/v1/services/audio/asr/transcription",
                                FILETRANS_MODEL)))
                .isEqualTo(origin + "/v1/services/audio/asr/transcription");

        // 不带版本根的裸域名回退到百炼口径：既有渠道不受影响。
        assertThat(BailianAudioTranscriptionAdapter.syncUri(channel(origin, SYNC_MODEL)))
                .isEqualTo(origin + "/api/v1/services/aigc/multimodal-generation/generation");
    }

    // -----------------------------------------------------------------------
    // 夹具
    // -----------------------------------------------------------------------

    /**
     * 一条完整的异步链路桩：提交端点返回 task id，任务查询端点返回终态与
     * 一个指向本桩自身的 {@code transcription_url}，因此结果文件也由本桩提供。
     */
    private void serveTranscriptionFlow(String transcriptionJson) throws Exception {
        server.createContext("/", exchange -> {
            // handler 的异常要显式打出来。HttpServer 在 handler 抛异常时只会关掉
            // 连接、什么也不说，于是客户端收到 PrematureCloseException —— 一个把
            // 夹具缺陷伪装成网络问题的错误。这不是假设：结果文件下载刻意不带
            // Authorization，而记录表曾用 ConcurrentHashMap，put(path, null) 抛 NPE，
            // 表现为「连接被提前关闭」，排查花了很久。留着这段就是为了下次一眼可见。
            try {
                String path = exchange.getRequestURI().getPath();
                if (SUBMIT_PATH.equals(path)) {
                    writeJson(exchange, 200,
                            "{\"output\":{\"task_id\":\"t-ok\",\"task_status\":\"PENDING\"}}");
                    return;
                }
                if (path.startsWith("/api/v1/tasks/")) {
                    writeJson(exchange, 200,
                            "{\"output\":{\"task_id\":\"t-ok\",\"task_status\":\"SUCCEEDED\","
                                    + "\"results\":[{\"subtask_status\":\"SUCCEEDED\","
                                    + "\"transcription_url\":\"" + baseUrl + "/result.json\"}]}}");
                    return;
                }
                if ("/result.json".equals(path)) {
                    // 结果文件是签名 URL，因此这里断言它**没有**收到网关的凭证：
                    // 把 Authorization 发给一个对象存储域名就是凭证泄漏。
                    writeJson(exchange, 200, transcriptionJson);
                    return;
                }
                writeJson(exchange, 404, "{\"error\":{\"code\":\"NotFound\"}}");
            } catch (Throwable error) {
                System.err.println("[STUB] handler threw: " + error);
                error.printStackTrace();
                try {
                    exchange.close();
                } catch (Exception ignored) {
                    // 连接已经坏了，没什么可做的。
                }
            }
        });
        server.start();
    }

    private TranscriptionRequest request(String model, String audioUrl, int reservedSeconds) {
        return new TranscriptionRequest(
                channel(baseUrl, model),
                "asr-req-" + UUID.randomUUID(),
                audioUrl,
                "wav",
                "16000",
                List.of(),
                reservedSeconds,
                60_000);
    }

    private AudioTranscriptionAdapter.Channel channel(String url, String upstreamModel) {
        return new AudioTranscriptionAdapter.Channel(
                UUID.randomUUID(), UUID.randomUUID(), "BAILIAN", url, "sk-vendor-secret",
                upstreamModel);
    }

    /** 收到的请求路径集合，用于异步流程的顺序断言。 */
    private final java.util.List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** 按路径记录的异步头与请求体，避免「最近一次」读到后一步的下载请求。 */
    private final java.util.Map<String, String> asyncHeaderByPath =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, JsonNode> bodyByPath =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * 按路径记录的 Authorization。
     *
     * <p>这里用允许 null 值的 {@code synchronizedMap(HashMap)}，而**不是**
     * {@code ConcurrentHashMap}：结果文件的下载刻意不带 Authorization，
     * 而 {@code ConcurrentHashMap.put} 对 null 值直接抛 NullPointerException。
     * 那个异常发生在 HttpServer 的 handler 线程里，服务器只会默默关掉连接，
     * 于是客户端收到的是 {@code PrematureCloseException}——看起来像网络抖动
     * 或适配器的错，实际是夹具自己炸了。测试随后断言该路径的值为 null
     * （{@link #authorizationOn}），因此这张表必须能存 null。
     */
    private final java.util.Map<String, String> authorizationByPath =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    private java.util.List<String> requestPaths() {
        return paths;
    }

    private String asyncHeaderOn(String path) {
        return asyncHeaderByPath.get(path);
    }

    private JsonNode bodyOn(String path) {
        return bodyByPath.get(path);
    }

    private String authorizationOn(String path) {
        return authorizationByPath.get(path);
    }

    private void respond(int status, String body) throws Exception {
        server.createContext("/", exchange -> writeJson(exchange, status, body));
        server.start();
    }

    /**
     * 只声明 {@code IOException}，**不能**声明 {@code Exception}。
     *
     * <p>本方法总是在 {@code HttpServer.createContext} 的 lambda 里被调用，
     * 而那个 lambda 的签名是 {@code HttpHandler.handle(HttpExchange) throws IOException}
     * ——它只接纳 {@code IOException}。声明成 {@code Exception} 会让每一次调用都
     * 编译失败（"未报告的异常错误 java.lang.Exception"），而报错位置指向的是
     * 调用点而不是这里，因此不那么直观。
     *
     * <p>方法体实际只会抛 {@code IOException} 及其子类：
     * {@code readAllBytes()}、Jackson 的 {@code readTree()}、
     * {@code sendResponseHeaders()}、{@code getResponseBody().write()} 与
     * {@code close()}。收紧声明因此不是绕过编译器，而是把真实情况写出来。
     */
    private void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        requestCount.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        requestMethod.set(exchange.getRequestMethod());
        requestPath.set(path);
        paths.add(path);
        String auth = header(exchange, "Authorization");
        authorization.set(auth);
        authorizationByPath.put(path, auth);
        String async = header(exchange, "X-DashScope-Async");
        asyncHeader.set(async);
        if (async != null) {
            asyncHeaderByPath.put(path, async);
        }
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        if (requestBytes.length > 0) {
            JsonNode parsed = JSON.readTree(requestBytes);
            requestBody.set(parsed);
            bodyByPath.put(path, parsed);
        }
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private String header(HttpExchange exchange, String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }
}

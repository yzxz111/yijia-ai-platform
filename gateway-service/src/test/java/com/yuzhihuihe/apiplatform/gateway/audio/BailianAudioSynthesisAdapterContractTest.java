package com.yuzhihuihe.apiplatform.gateway.audio;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.yuzhihuihe.apiplatform.gateway.audio.AudioSynthesisAdapter.SynthesisRequest;
import com.yuzhihuihe.apiplatform.gateway.audio.AudioSynthesisAdapter.SynthesisResult;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * 网关与百炼语音合成之间的线上契约，针对厂商的本地桩、通过真实 HTTP 演练。
 *
 * <p>这些断言让一条真实渠道无需付费调用就可被配置：端点 URL、请求载荷、
 * 响应映射、错误翻译与**计费秒数的来源**都在此钉住，因此实跑中唯一未经验证的
 * 变量就是厂商自身的行为（以及运维填进去的凭证）。
 *
 * <p><b>这个端点的 {@code usage} 回报的是字符数，不是产出音频的时长</b>，
 * 因此本文件的重点是那条折算：字符折秒用的是与费率卡成本折算**同一个常量**。
 * 折算比例一旦与成本侧不一致，毛利就随文案密度漂移；而这类错误不会让任何一次
 * 调用失败，它只会让每个月的账对不上，所以只能在这里钉死。
 */
class BailianAudioSynthesisAdapterContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODEL = "qwen3-tts-flash";
    private static final String VOICE = "Cherry";
    private static final String SYNTHESIS_PATH =
            "/api/v1/services/aigc/multimodal-generation/generation";

    private HttpServer server;
    private String baseUrl;
    private BailianAudioSynthesisAdapter adapter;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final Map<String, List<JsonNode>> bodiesByPath = new ConcurrentHashMap<>();
    private final Map<String, List<String>> methodsByPath = new ConcurrentHashMap<>();
    private final Map<String, List<String>> authorizationsByPath = new ConcurrentHashMap<>();
    private final Map<String, List<String>> asyncHeadersByPath = new ConcurrentHashMap<>();

    @BeforeEach
    void prepareStubVendor() throws Exception {
        // 仅回环地址、临时端口：这里的任何测试都不会发起外部调用。
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        adapter = new BailianAudioSynthesisAdapter(WebClient.builder(), JSON);
    }

    @AfterEach
    void stopStubVendor() {
        if (server != null) {
            server.stop(0);
        }
    }

    // -----------------------------------------------------------------------
    // 提交载荷与结果映射
    // -----------------------------------------------------------------------

    @Test
    void submitsTheConfirmedPayloadAndReturnsTheAudioLocation() throws Exception {
        respond(200, """
                {"output":{"audio":{"url":"https://cdn.example.test/out.wav","data":"",
                 "id":"audio_x","expires_at":1766113409}},"usage":{"characters":195}}
                """);

        SynthesisResult result = adapter.synthesize(request(MODEL, 30, "chinese"))
                .block(Duration.ofSeconds(5));

        assertThat(result.audioUrl()).isEqualTo("https://cdn.example.test/out.wav");
        assertThat(methodOn(SYNTHESIS_PATH)).isEqualTo("POST");
        assertThat(requestPaths()).containsExactly(SYNTHESIS_PATH);
        // 凭证与载荷同等重要：少了它，厂商侧只会得到一次难懂的鉴权失败。
        assertThat(authorizationOn(SYNTHESIS_PATH)).isEqualTo("Bearer sk-vendor-secret");
        // 这是**同步**端点：多发一个异步头反而会被厂商当成另一种调用。
        assertThat(asyncHeaderOn(SYNTHESIS_PATH)).isNull();

        JsonNode body = bodyOn(SYNTHESIS_PATH);
        // 「恰好这些字段」而不是「包含这些字段」：多一个未确认的字段就等于押注
        // 厂商会忽略它，而厂商未必忽略。
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("model", "input");
        // 模型名取自渠道的 upstream_model，绝不取自客户端提交的别名。
        assertThat(body.path("model").asText()).isEqualTo(MODEL);

        JsonNode input = body.path("input");
        assertThat(fieldNames(input)).containsExactlyInAnyOrder("text", "voice", "language_type");
        // text 与 voice 必须在 input 里：放到顶层会在厂商侧变成一次
        // 难懂的「缺少必填参数」，而这里正是唯一能提前发现它的地方。
        assertThat(input.path("text").asText()).isEqualTo("欢迎使用语音合成。");
        assertThat(input.path("voice").asText()).isEqualTo(VOICE);
        // 厂商只认自己枚举的大小写，因此这里钉住归一化后的值。
        assertThat(input.path("language_type").asText()).isEqualTo("Chinese");
    }

    /**
     * 计费秒数由 {@code usage.characters} 折算，且永远标记为估算。
     *
     * <p>这条断言把比例常量与算术都钉住：常量改了、或者有人把向上取整改成截断，
     * 这里都会立刻变红。它防的是一次静默的错价——按字符折算成本的常量与按秒
     * 结算用的常量必须是同一个，否则每一秒都在少收或多收。
     */
    @Test
    void convertsReportedCharactersIntoBilledSecondsAndFlagsTheEstimate() throws Exception {
        respond(200, """
                {"output":{"audio":{"url":"https://cdn.example.test/out.wav"}},
                 "usage":{"characters":195}}
                """);

        SynthesisResult result = adapter.synthesize(request(MODEL, 30, "Chinese"))
                .block(Duration.ofSeconds(5));

        assertThat(AudioSynthesisAdapter.CHARACTERS_PER_SECOND).isEqualTo(2);
        // 195 / 2 = 97.5 → 向上取整 98：不足一秒的配音也是一次付费合成。
        assertThat((int) Math.ceil(
                        195 / (double) AudioSynthesisAdapter.CHARACTERS_PER_SECOND))
                .isEqualTo(98);
        assertThat(result.billedSeconds()).isEqualTo(98);
        // 这个端点从不回报音频时长，因此本次计费**永远是**估算值。
        // 标记一旦丢失，运营报表里就再也分不出「估算」与「实测」，
        // 「为什么这个月的毛利不对」也就无从回答。
        assertThat(result.estimatedDuration()).isTrue();
    }

    /**
     * 调用方未指定语种时，{@code input} 里不得出现 {@code language_type}。
     *
     * <p>厂商的默认行为就是自动判断，而多发一个 {@code "Auto"} 会把
     * 「调用方未指定」与「调用方明确要求自动」混为一谈：日后回看这条记录，
     * 再也分不清这次语种是谁决定的。null 与纯空白是同一件事，因此两者都测。
     */
    @Test
    void neverSendsAnAutoLanguageHintWhenTheCallerStaysSilent() throws Exception {
        respond(200, """
                {"output":{"audio":{"url":"https://cdn.example.test/out.wav"}},
                 "usage":{"characters":20}}
                """);

        adapter.synthesize(request(MODEL, 30, null)).block(Duration.ofSeconds(5));
        adapter.synthesize(request(MODEL, 30, "   ")).block(Duration.ofSeconds(5));

        // 两次调用打在同一个路径上，因此按**顺序**取它们各自的请求体，
        // 而不是读「最近一次」——后者会让第一次调用的断言变成恒真。
        List<JsonNode> bodies = bodiesOn(SYNTHESIS_PATH);
        assertThat(bodies).hasSize(2);
        for (int index = 0; index < bodies.size(); index++) {
            JsonNode input = bodies.get(index).path("input");
            assertThat(fieldNames(input))
                    .as("第 %d 次调用的 input 字段", index + 1)
                    .containsExactlyInAnyOrder("text", "voice");
            assertThat(input.has("language_type"))
                    .as("第 %d 次调用不得携带 language_type", index + 1)
                    .isFalse();
        }
    }

    @Test
    void usesTheInlineBase64DataWhenTheVendorReturnsNoUrl() throws Exception {
        // 位置优于内联数据，但一条既没 url 也没 data 的响应不是音频，
        // 因此这里演练的是「url 为空、data 有值」这一种真实形态。
        respond(200, """
                {"output":{"audio":{"url":"","data":"UklGRiQAAABXQVZFZm10","id":"audio_x"}},
                 "usage":{"characters":4}}
                """);

        SynthesisResult result = adapter.synthesize(request(MODEL, 30, "Chinese"))
                .block(Duration.ofSeconds(5));

        // 丢掉这个回退，一次已经付费的合成就会彻底拿不到音频：
        // base64 与 url 对客户是同一段音频，只是传输方式不同。
        assertThat(result.audioUrl()).isEqualTo("UklGRiQAAABXQVZFZm10");
        assertThat(result.billedSeconds()).isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // 错误翻译
    // -----------------------------------------------------------------------

    @Test
    void aTwoHundredCarryingAnErrorBodyIsAnUpstreamFault() throws Exception {
        respond(200, """
                {"error":{"code":"InvalidParameter","message":"voice is not supported"}}
                """);

        assertThatThrownBy(() -> adapter
                .synthesize(request(MODEL, 30, "Chinese"))
                .block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error -> {
                    // 厂商用 200 回答一次拒绝，因此无法证明它没有接单：
                    // 按上游故障上报（502），预留保持不动，而绝不是把一次成功交给客户。
                    assertThat(error.status()).isEqualTo(502);
                    assertThat(error.getMessage())
                            .contains("provider_error_response:InvalidParameter");
                });
    }

    @Test
    void aTopLevelErrorCodeOnATwoHundredIsAnUpstreamFault() throws Exception {
        // 百炼另有一个顶层 code 形态（成功时为空字符串），与上面的 error 体
        // 是两条不同的路径，因此必须各钉一次：只认一种会让另一种悄悄变成
        // 一个「成功但拿不到音频」的响应。
        respond(200, """
                {"code":"DataInspectionFailed","message":"blocked"}
                """);

        assertThatThrownBy(() -> adapter
                .synthesize(request(MODEL, 30, "Chinese"))
                .block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error -> {
                    assertThat(error.status()).isEqualTo(502);
                    assertThat(error.getMessage())
                            .contains("provider_error_response:DataInspectionFailed");
                });
    }

    @Test
    void aTwoHundredWithoutAnyAudioIsAProtocolFailure() throws Exception {
        // output 在、audio 不在：这是厂商契约变了，不是调用方的错。
        respond(200, """
                {"output":{"task_id":"audio_x"},"usage":{"characters":195}}
                """);

        assertThatThrownBy(() -> adapter
                .synthesize(request(MODEL, 30, "Chinese"))
                .block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error -> {
                    // 一次上报成功却拿不到音频的合成会留下一个永远无法交付的订单，
                    // 因此按协议失败上报，而不是发回一个空地址。
                    assertThat(error.status()).isEqualTo(502);
                    assertThat(error.getMessage()).contains("provider_invalid_synthesis");
                });
    }

    @Test
    void rejectsAnUnlistedModelBeforeAnyHttpCall() throws Exception {
        // 桩是启动着的，因此「没有请求」是一条有内容的断言：
        // 一次被猜错的模型名不会留下一次付费调用，也不会在上游写下一句
        // 与配置无关的错误。
        respond(200, """
                {"output":{"audio":{"url":"https://cdn.example.test/out.wav"}},
                 "usage":{"characters":195}}
                """);

        SynthesisRequest request = request("qwen-audio-3.0-tts", 30, "Chinese");

        assertThatThrownBy(() -> adapter.validateSubmission(request))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("unsupported_bailian_tts_model"));

        // 真正的守卫长在 synthesize 的入口上，因此再走一次完整路径：
        // 直接调用 validateSubmission 本身当然不会发请求，只有走完整路径
        // 才能证明「拒绝」发生在付费调用之前。
        assertThatThrownBy(() -> adapter.synthesize(request).block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("unsupported_bailian_tts_model"));

        assertThat(requestCount.get()).isZero();
    }

    /**
     * 音色名不在硬编码清单里时，在付费调用之前以 422 拒绝，而不是等厂商回
     * 「voice is not supported」。模型名合法、音色名非法，正是要区分这两种
     * 拒绝：前者是配置问题（503），后者是客户端错误（422）。
     */
    @Test
    void rejectsAnUnlistedVoiceBeforeAnyHttpCall() throws Exception {
        respond(200, """
                {"output":{"audio":{"url":"https://cdn.example.test/out.wav"}},
                 "usage":{"characters":195}}
                """);

        SynthesisRequest request = new SynthesisRequest(
                channel(baseUrl, MODEL),
                "audio-synthesis-req-" + UUID.randomUUID(),
                "欢迎使用语音合成。",
                "not-a-real-voice",
                "Chinese",
                "wav",
                30,
                60_000);

        assertThatThrownBy(() -> adapter.validateSubmission(request))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(422);
                    assertThat(error.getReason()).isEqualTo("unsupported_bailian_tts_voice");
                });

        assertThatThrownBy(() -> adapter.synthesize(request).block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("unsupported_bailian_tts_voice"));

        assertThat(requestCount.get()).isZero();
    }

    @Test
    void fallsBackToTheReservedSecondsWhenTheVendorReportsNoCharacters() throws Exception {
        respond(200, """
                {"output":{"audio":{"url":"https://cdn.example.test/out.wav"}}}
                """);

        SynthesisResult result = adapter.synthesize(request(MODEL, 42, "Chinese"))
                .block(Duration.ofSeconds(5));

        // 字符缺失无法区分「本次不计费」与「响应缺字段」，而一次成功的合成
        // 确实产出了音频：因此回退到预留秒数，而不是把这次调用白送出去。
        assertThat(result.billedSeconds()).isEqualTo(42);
        // 回退值同样是估算值，标记不会因为走了另一条分支而变成 false。
        assertThat(result.estimatedDuration()).isTrue();
    }

    @Test
    void anUpstreamRefusalIsReportedAsAnUpstreamFaultWithTheVendorsCode() throws Exception {
        respond(400, """
                {"error":{"code":"InvalidApiKey","message":"Invalid API-key provided."}}
                """);

        assertThatThrownBy(() -> adapter
                .synthesize(request(MODEL, 30, "Chinese"))
                .block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error -> {
                    // 4xx 是确定性的拒绝，服务层据此释放预留；而错误码本身
                    // 就是渠道配错时运维唯一需要看到的东西，因此它必须留在消息里。
                    assertThat(error.status()).isEqualTo(400);
                    assertThat(error.getMessage())
                            .contains("provider_rejected_synthesis:InvalidApiKey");
                });
    }

    @Test
    void advertisesTheSynthesisCapabilityUnderTheBailianProviderType() {
        assertThat(adapter.providerType()).isEqualTo("BAILIAN");
        assertThat(adapter.capabilities()).containsExactly("AUDIO_SYNTHESIS");
    }

    /**
     * 钉住硬编码在适配器里的音色清单：它来自厂商音色列表页「非实时语音合成」
     * 表格里标注支持 {@code qwen3-tts-flash} 的行（2026-10 快照）。
     *
     * <p>逐项断言而不是「含 Cherry 且非空」：音色是厂商属性、会随版本增减，
     * 这份清单一旦悄悄过期，客户端就会拿到一个缺项的下拉框而无人察觉。
     * 让测试把每一个名字都钉死，厂商一变这里就红，逼着人回到厂商页同步。
     */
    @Test
    void advertisesTheVendorVoiceListSoTheCatalogueCanHandItToClients() {
        assertThat(adapter.voices())
                .extracting(AudioSynthesisAdapter.Voice::name)
                .containsExactly(
                        "Cherry", "Serena", "Ethan", "Chelsie", "Momo", "Vivian", "Moon",
                        "Maia", "Kai", "Nofish", "Bella", "Jennifer", "Ryan", "Katerina",
                        "Aiden", "Eldric Sage", "Mia", "Mochi", "Bellona", "Vincent",
                        "Bunny", "Neil", "Elias", "Arthur", "Nini", "Seren", "Pip",
                        "Stella", "Bodega", "Sonrisa", "Alek", "Dolce", "Sohee", "Ono Anna",
                        "Lenn", "Emilien", "Andre", "Radio Gol", "Jada", "Dylan", "Li",
                        "Marcus", "Roy", "Peter", "Sunny", "Eric", "Rocky", "Kiki");
        assertThat(adapter.voices()).allSatisfy(voice ->
                assertThat(voice.description()).isNotBlank());
    }

    // -----------------------------------------------------------------------
    // URL 解析：运维存的是哪种形态都不该改变端点
    // -----------------------------------------------------------------------

    @Test
    void everyAcceptedBaseUrlFormResolvesToTheSameSynthesisEndpoint() {
        String origin = "https://dashscope.aliyuncs.com";
        // 运维可能存的是厂商源站、带版本的根，或者从文档里原样复制的端点。
        // 五种形态都必须解析出恰好一个合成端点：多拼一层 /api/v1 会让每一次
        // 真实合成 404，而少拼一层同样如此——两者都只会在生产里被发现。
        for (String form : new String[] {
                origin,
                origin + "/",
                origin + "/api/v1",
                origin + "/api/v1/",
                origin + SYNTHESIS_PATH}) {
            assertThat(BailianAudioSynthesisAdapter.synthesisUri(channel(form, MODEL)))
                    .as("synthesis uri for base url form %s", form)
                    .isEqualTo(origin + SYNTHESIS_PATH);
            // 父路径推导也不能再多拼一层版本号：它同时决定了日志与对账里
            // 那次调用的归属。
            assertThat(BailianAudioSynthesisAdapter.apiRoot(channel(form, MODEL)))
                    .as("api root for base url form %s", form)
                    .isEqualTo(origin + "/api/v1");
        }
    }

    // -----------------------------------------------------------------------
    // 夹具
    // -----------------------------------------------------------------------

    private SynthesisRequest request(String model, int reservedSeconds, String languageType) {
        return new SynthesisRequest(
                channel(baseUrl, model),
                "audio-synthesis-req-" + UUID.randomUUID(),
                "欢迎使用语音合成。",
                VOICE,
                languageType,
                "wav",
                reservedSeconds,
                60_000);
    }

    private AudioSynthesisAdapter.Channel channel(String url, String upstreamModel) {
        return new AudioSynthesisAdapter.Channel(
                UUID.randomUUID(), UUID.randomUUID(), "BAILIAN", url, "sk-vendor-secret",
                upstreamModel);
    }

    /** 用同一个固定响应体答复每个请求，并记录收到的请求内容。 */
    private void respond(int status, String body) throws Exception {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            captureRequest(exchange);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    /** 记录一次入站请求：计数、路径、方法与请求头，并按路径归档请求体。 */
    private void captureRequest(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        paths.add(path);
        addTo(methodsByPath, path, exchange.getRequestMethod());
        addTo(authorizationsByPath, path, exchange.getRequestHeaders().getFirst("Authorization"));
        addTo(asyncHeadersByPath, path, exchange.getRequestHeaders().getFirst("X-DashScope-Async"));
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

    /** 收到的请求路径，用于钉住「只打了这一个端点」。 */
    private List<String> requestPaths() {
        return paths;
    }

    private List<JsonNode> bodiesOn(String path) {
        List<JsonNode> bodies = bodiesByPath.get(path);
        return bodies == null ? List.<JsonNode>of() : bodies;
    }

    /** 只在确定该路径恰好被请求过一次时使用：多于一次会在这里立刻变红。 */
    private JsonNode bodyOn(String path) {
        List<JsonNode> bodies = bodiesOn(path);
        assertThat(bodies).as("路径 %s 收到的请求体", path).hasSize(1);
        return bodies.get(0);
    }

    private String methodOn(String path) {
        List<String> methods = methodsByPath.get(path);
        assertThat(methods).as("路径 %s 的请求方法", path).hasSize(1);
        return methods.get(0);
    }

    private String authorizationOn(String path) {
        List<String> values = authorizationsByPath.get(path);
        assertThat(values).as("路径 %s 的 Authorization 头", path).hasSize(1);
        return values.get(0);
    }

    private String asyncHeaderOn(String path) {
        List<String> values = asyncHeadersByPath.get(path);
        assertThat(values).as("路径 %s 的 X-DashScope-Async 头", path).hasSize(1);
        return values.get(0);
    }

    /** 请求体的字段集合，用于钉住「恰好这些字段」。 */
    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}

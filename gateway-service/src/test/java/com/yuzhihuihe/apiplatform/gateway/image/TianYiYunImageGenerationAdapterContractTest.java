package com.yuzhihuihe.apiplatform.gateway.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yuzhihuihe.apiplatform.gateway.image.ImageGenerationAdapter.GenerationRequest;
import com.yuzhihuihe.apiplatform.gateway.image.ImageGenerationAdapter.GenerationResult;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 网关与天翼 AI 网关同步图片生成 API 之间的线上契约，针对厂商的本地
 * 桩、通过真实 HTTP 演练。
 *
 * <p>正是这些断言让一条真实渠道无需付费调用就可被配置：提交载荷、
 * 端点 URL、响应映射以及计费张数都在此钉住，因此实跑中唯一未经验证的变量
 * 就是厂商自身的行为（以及运维填进去的凭证）。
 */
class TianYiYunImageGenerationAdapterContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GENERATE_PATH = "/v1/images/generations";
    private static final String MODEL = "doubao-seedream-5.0-pro";

    private HttpServer server;
    private String baseUrl;
    private TianYiYunImageGenerationAdapter adapter;
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();

    @BeforeEach
    void prepareStubVendor() throws Exception {
        // 仅回环地址、临时端口：这里的任何测试都不会发起外部调用。
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        adapter = new TianYiYunImageGenerationAdapter(WebClient.builder(), JSON);
    }

    @AfterEach
    void stopStubVendor() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void submitsTheConfirmedPayloadAndReturnsTheGeneratedImage() throws Exception {
        respond(200, """
                {"created":1758950000,"data":[{"url":"https://cdn.example.test/lighthouse.png"}]}
                """);

        GenerationResult result = adapter.generate(request("黄昏的灯塔", null, "2K", 1))
                .block(Duration.ofSeconds(5));

        assertThat(result.images()).singleElement().satisfies(image ->
                assertThat(image.url()).isEqualTo("https://cdn.example.test/lighthouse.png"));
        assertThat(result.billedImages()).isEqualTo(1);
        assertThat(requestMethod.get()).isEqualTo("POST");
        assertThat(requestPath.get()).isEqualTo(GENERATE_PATH);
        assertThat(contentType.get()).contains("application/json");
        assertThat(authorization.get()).isEqualTo("Bearer sk-vendor-secret");

        JsonNode body = requestBody.get();
        // 模型名取自渠道的 upstream_model，绝不取自客户端提交的别名。
        assertThat(body.path("model").asText()).isEqualTo(MODEL);
        assertThat(body.path("prompt").asText()).isEqualTo("黄昏的灯塔");
        assertThat(body.path("size").asText()).isEqualTo("2K");
        assertThat(body.path("response_format").asText()).isEqualTo("url");
        // 同步接口，绝不流式：网关自己就是那个等待应答的一方。
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("watermark").asBoolean()).isTrue();
        // 一次一张：网关不发送厂商未确认接受的 n，避免整个链路因未知字段失效。
        assertThat(body.has("n")).isFalse();
        // 文生图请求不得夹带图像字段或图层拆分。
        assertThat(body.has("image")).isFalse();
        assertThat(body.has("layer_decomposition")).isFalse();
    }

    @Test
    void sendsAHandPickedWidthHeightSizeAndWatermarkOff() throws Exception {
        respond(200, """
                {"created":1,"data":[{"url":"https://cdn.example.test/a.png"}]}
                """);

        adapter.generate(new GenerationRequest(
                        channel(baseUrl, MODEL),
                        "image-req-size",
                        "a lighthouse at dusk",
                        (String) null,
                        "1024x1024",
                        "1K",
                        1,
                        "url",
                        false,
                        false,
                        60_000))
                .block(Duration.ofSeconds(5));

        JsonNode body = requestBody.get();
        // 尺寸是客户可选的：{@code 宽x高} 原样发给厂商，档位只用于网关侧的定价校验。
        assertThat(body.path("size").asText()).isEqualTo("1024x1024");
        assertThat(body.path("watermark").asBoolean()).isFalse();
        assertThat(body.path("stream").asBoolean()).isFalse();
    }

    @Test
    void sendsTheInputImageWithLayerDecompositionAndNoPrompt() throws Exception {
        respond(200, """
                {"created":1,"data":[{"url":"https://cdn.example.test/layers.png"}]}
                """);

        adapter.generate(request(
                        null, "https://cdn.example.test/source.png", "2K", 1, true))
                .block(Duration.ofSeconds(5));

        JsonNode body = requestBody.get();
        assertThat(body.path("image").asText()).isEqualTo("https://cdn.example.test/source.png");
        assertThat(body.path("layer_decomposition").asBoolean()).isTrue();
        // 没有提示词时不发空字符串：厂商无法区分「空提示词」与「没有提示词」，
        // 而后者才是图层拆分的语义。
        assertThat(body.has("prompt")).isFalse();
    }

    @Test
    void anImageToImageRequestWithAPromptDoesNotAskForLayerDecomposition() throws Exception {
        respond(200, """
                {"created":1,"data":[{"url":"https://cdn.example.test/winter.png"}]}
                """);

        adapter.generate(request(
                        "make it winter", "https://cdn.example.test/source.png", "2K", 1))
                .block(Duration.ofSeconds(5));

        JsonNode body = requestBody.get();
        assertThat(body.path("prompt").asText()).isEqualTo("make it winter");
        assertThat(body.path("image").asText()).isEqualTo("https://cdn.example.test/source.png");
        // 图层拆分只在「有图、没有提示词」时才有意义，因此它不会被附带发送。
        assertThat(body.has("layer_decomposition")).isFalse();
    }

    /**
     * 多参考图生图：≥2 张输入图按调用方顺序作为数组转发。
     *
     * <p>2026-10-10 实测：上游接受 {@code image} 为 URL 数组，并在
     * {@code usage.input_images} 中如实报出吃了几张。单图维持字符串形状不变。
     */
    @Test
    void sendsMultipleReferenceImagesAsAnArrayInOrder() throws Exception {
        respond(200, """
                {"created":1,"data":[{"url":"https://cdn.example.test/multi.png"}]}
                """);

        adapter.generate(new GenerationRequest(
                        channel(baseUrl, MODEL),
                        "image-req-multi-ref",
                        "merge these two styles",
                        List.of("https://cdn.example.test/a.png", "https://cdn.example.test/b.png"),
                        "2K",
                        "2K",
                        1,
                        "url",
                        true,
                        false,
                        60_000))
                .block(Duration.ofSeconds(5));

        JsonNode body = requestBody.get();
        assertThat(body.path("image").isArray()).isTrue();
        assertThat(body.path("image").size()).isEqualTo(2);
        assertThat(body.path("image").get(0).asText())
                .isEqualTo("https://cdn.example.test/a.png");
        assertThat(body.path("image").get(1).asText())
                .isEqualTo("https://cdn.example.test/b.png");
    }

    /** 图层拆分只能拆一张：两张输入图 + 图层拆分在越过厂商边界之前就被拒绝。 */
    @Test
    void rejectsLayerDecompositionWithMultipleInputImages() {
        assertThatThrownBy(() -> new GenerationRequest(
                channel(baseUrl, MODEL),
                "image-req-layer-multi",
                null,
                List.of("https://cdn.example.test/a.png", "https://cdn.example.test/b.png"),
                "2K",
                "2K",
                1,
                "url",
                true,
                true,
                60_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid_image_generation_request");
    }

    @Test
    void mapsInlineBase64Responses() throws Exception {
        respond(200, """
                {"created":1,"data":[{"b64_json":"iVBORw0KGgo="},{"b64_json":"iVBORw0KGgo="}]}
                """);

        GenerationResult result = adapter.generate(request("two images", null, "2K", 2))
                .block(Duration.ofSeconds(5));

        assertThat(result.images()).hasSize(2);
        assertThat(result.images()).allSatisfy(image -> {
            assertThat(image.b64Json()).isEqualTo("iVBORw0KGgo=");
            assertThat(image.url()).isNull();
        });
    }

    @Test
    void prefersTheUrlWhenAnEntryCarriesBothForms() throws Exception {
        respond(200, """
                {"created":1,"data":[{"url":"https://cdn.example.test/both.png","b64_json":"aGk="}]}
                """);

        GenerationResult result = adapter.generate(request("both", null, "2K", 1))
                .block(Duration.ofSeconds(5));

        // 位置更便宜：网关不镜像生成结果，而两者对客户是同一张图。
        assertThat(result.images()).singleElement().satisfies(image ->
                assertThat(image.url()).isEqualTo("https://cdn.example.test/both.png"));
    }

    @Test
    void billsTheCountTheVendorReportsRatherThanTheEntriesItReturned() throws Exception {
        respond(200, """
                {"created":1,"data":[{"url":"https://cdn.example.test/1.png"},
                 {"url":"https://cdn.example.test/2.png"}],"usage":{"generated_images":1}}
                """);

        // imageCount = 2 在这里仍然是合法的输入：适配器的契约是「厂商返回几张」，
        // 服务层才是那个把一次请求限制成一张的地方（MAX_IMAGES = 1）。
        // 本测试要钉的是厂商自报张数优先于返回条目数，因此把两件事分开看。
        GenerationResult result = adapter.generate(request("two for one", null, "2K", 2))
                .block(Duration.ofSeconds(5));

        assertThat(result.images()).hasSize(2);
        assertThat(result.billedImages()).isEqualTo(1);
    }

    /**
     * 图层拆分：厂商可以合法地为**一次调用**返回多张图层，而这次调用只计 1。
     *
     * <p>这是适配器一侧的「按次计费」：服务层另有同一条判断，它读的是持久化在
     * 生成记录上的标记，覆盖重放与补偿路径。两处都答 1 才算把这次调用钉住，
     * 因此这里即使厂商的 {@code usage} 自报三张，计费张数也必须是一张。
     */
    @Test
    void aLayerDecompositionCallBillsOneUnitHoweverManyLayersComeBack() throws Exception {
        respond(200, """
                {"created":1,"data":[{"url":"https://cdn.example.test/layer-1.png"},
                 {"url":"https://cdn.example.test/layer-2.png"},
                 {"url":"https://cdn.example.test/layer-3.png"}],"usage":{"generated_images":3}}
                """);

        GenerationResult result = adapter.generate(request(
                        null, "https://cdn.example.test/source.png", "2K", 1, true))
                .block(Duration.ofSeconds(5));

        // 图层照常交付给客户：三张都在，被压成一张的是**计费单位**，不是结果。
        assertThat(result.images()).hasSize(3);
        assertThat(result.billedImages()).isEqualTo(1);
        // 上游载荷里仍然没有 n，图层标记照旧发出。
        assertThat(requestBody.get().path("layer_decomposition").asBoolean()).isTrue();
        assertThat(requestBody.get().has("n")).isFalse();
    }

    /** 非图层调用不受该规则影响：厂商报几张就是几张。 */
    @Test
    void anOrdinaryCallIsStillBilledByTheCountTheVendorReports() throws Exception {
        respond(200, """
                {"created":1,"data":[{"url":"https://cdn.example.test/1.png"},
                 {"url":"https://cdn.example.test/2.png"}],
                 "usage":{"generated_images":2}}
                """);

        GenerationResult result = adapter.generate(request(
                        "two images", "https://cdn.example.test/source.png", "2K", 2, false))
                .block(Duration.ofSeconds(5));

        assertThat(result.images()).hasSize(2);
        assertThat(result.billedImages()).isEqualTo(2);
    }

    @Test
    void fallsBackToTheReturnedCountWhenUsageIsAbsent() throws Exception {
        respond(200, """
                {"created":1,"data":[{"url":"https://cdn.example.test/1.png"},
                 {"url":"https://cdn.example.test/2.png"}]}
                """);

        // 同样与请求无关：成功但缺少 usage 的响应仍然产出了图片，因此回退到实际
        // 返回的张数，而不是报告零。一次请求最多买一张是服务层的规则。
        GenerationResult result = adapter.generate(request("two", null, "2K", 2))
                .block(Duration.ofSeconds(5));

        assertThat(result.billedImages()).isEqualTo(2);
    }

    @Test
    void aUsageOfZeroDoesNotGiveTheGenerationAway() throws Exception {
        respond(200, """
                {"created":1,"data":[{"url":"https://cdn.example.test/1.png"}],
                 "usage":{"generated_images":0}}
                """);

        GenerationResult result = adapter.generate(request("one", null, "2K", 1))
                .block(Duration.ofSeconds(5));

        assertThat(result.billedImages()).isEqualTo(1);
    }

    @Test
    void aTwoHundredCarryingAnErrorBodyIsAnUpstreamFault() throws Exception {
        respond(200, """
                {"error":{"code":"InvalidParameter","message":"size is invalid"}}
                """);

        assertThatThrownBy(() -> adapter.generate(request("prompt", null, "2K", 1))
                .block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error -> {
                    // 无法据此断定厂商没有接单，因此按上游故障上报（5xx），
                    // 预留保持不动，而绝不是把成功交给客户。
                    assertThat(error.status()).isEqualTo(502);
                    assertThat(error.getMessage()).contains("provider_error_response:InvalidParameter");
                });
    }

    @Test
    void aTwoHundredWithoutImagesIsAProtocolFailure() throws Exception {
        respond(200, """
                {"created":1,"data":[]}
                """);

        assertThatThrownBy(() -> adapter.generate(request("prompt", null, "2K", 1))
                .block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error -> {
                    assertThat(error.status()).isEqualTo(502);
                    assertThat(error.getMessage()).contains("provider_invalid_response");
                });
    }

    @Test
    void anUpstreamRefusalIsReportedAsAnUpstreamFaultWithTheVendorsCode() throws Exception {
        respond(400, """
                {"error":{"code":"InvalidApiKey","message":"Invalid API-key provided."}}
                """);

        assertThatThrownBy(() -> adapter.generate(request("prompt", null, "2K", 1))
                .block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error -> {
                    // 4xx 是确定性的拒绝：服务层据此释放预留。
                    assertThat(error.status()).isEqualTo(400);
                    assertThat(error.getMessage())
                            .contains("provider_rejected_generation:InvalidApiKey");
                    // 渠道密钥错误不是内容审核：不得被标记成「内容违规」。
                    assertThat(error.contentPolicyViolation()).isFalse();
                });
    }

    /**
     * 上游对生成**内容**的审核拒绝必须被标记出来，而不是和渠道故障混在一起。
     *
     * <p>2026-10-10 线上实锤：提示词含版权角色（蜘蛛侠）时，厂商画完约 1 分钟
     * 后返回 {@code OutputImageSensitiveContentDetected.PolicyViolation}。客户端
     * 此前只能拿到一句无法区分的 502，用户无从知道该改提示词。
     */
    @Test
    void marksAContentPolicyRefusalAsAContentPolicyViolation() throws Exception {
        respond(400, """
                {"error":{"code":"OutputImageSensitiveContentDetected.PolicyViolation",
                          "message":"sensitive content"}}
                """);

        assertThatThrownBy(() -> adapter.generate(request("prompt", null, "2K", 1))
                .block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error -> {
                    assertThat(error.status()).isEqualTo(400);
                    assertThat(error.contentPolicyViolation()).isTrue();
                    assertThat(error.getMessage())
                            .contains("provider_rejected_generation:"
                                    + "OutputImageSensitiveContentDetected.PolicyViolation");
                });
    }

    @Test
    void aServerSideRefusalKeepsItsStatusSoTheReservationIsHeld() throws Exception {
        respond(503, """
                {"code":"ServiceUnavailable"}
                """);

        assertThatThrownBy(() -> adapter.generate(request("prompt", null, "2K", 1))
                .block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error -> {
                    assertThat(error.status()).isEqualTo(503);
                    assertThat(error.getMessage()).contains("provider_rejected_generation");
                });
    }

    @Test
    void rejectsProviderSpecificProfilesBeforeAnyHttpCall() {
        assertThatThrownBy(() -> adapter.validateSubmission(new GenerationRequest(
                        channel(baseUrl, "some-unlisted-model"),
                        "image-req-unlisted",
                        "prompt",
                        (String) null,
                        "2K",
                        "2K",
                        1,
                        "url",
                        true,
                        false,
                        60_000)))
                .hasMessageContaining("unsupported_tianyi_image_model");

        // 该厂商不提供的档位：宁可拒绝，也不猜它的阶梯。
        assertThatThrownBy(() -> adapter.validateSubmission(new GenerationRequest(
                        channel(baseUrl, MODEL),
                        "image-req-tier",
                        "prompt",
                        (String) null,
                        "8K",
                        "8K",
                        1,
                        "url",
                        true,
                        false,
                        60_000)))
                .hasMessageContaining("tianyi_image_size_not_supported");

        assertThatThrownBy(() -> adapter.validateSubmission(new GenerationRequest(
                        channel(baseUrl, MODEL),
                        "image-req-format",
                        "prompt",
                        (String) null,
                        "2K",
                        "2K",
                        1,
                        "png",
                        true,
                        false,
                        60_000)))
                .hasMessageContaining("tianyi_image_response_format_not_supported");

        // 厂商需要自己去拉这张图，因此它必须是一个绝对 http(s) 位置。
        assertThatThrownBy(() -> adapter.validateSubmission(new GenerationRequest(
                        channel(baseUrl, MODEL),
                        "image-req-relative",
                        "prompt",
                        "data:image/png;base64,aGk=",
                        "2K",
                        "2K",
                        1,
                        "url",
                        true,
                        false,
                        60_000)))
                .hasMessageContaining("tianyi_image_input_url_not_supported");

        assertThat(requestBody.get()).isNull();
    }

    @Test
    void theApiRootIsNotDuplicatedWhenOperatorsPasteTheVersionedBaseUrl() {
        assertThat(TianYiYunImageGenerationAdapter.apiRoot(channel("https://ai.ctaigw.cn", MODEL)))
                .isEqualTo("https://ai.ctaigw.cn/v1");
        assertThat(TianYiYunImageGenerationAdapter.apiRoot(channel("https://ai.ctaigw.cn/", MODEL)))
                .isEqualTo("https://ai.ctaigw.cn/v1");
        assertThat(TianYiYunImageGenerationAdapter.apiRoot(channel(baseUrl + "/v1", MODEL)))
                .isEqualTo(baseUrl + "/v1");
        assertThat(TianYiYunImageGenerationAdapter.apiRoot(channel(baseUrl + "/v1/", MODEL)))
                .isEqualTo(baseUrl + "/v1");
        assertThat(TianYiYunImageGenerationAdapter.apiRoot(channel(baseUrl + GENERATE_PATH, MODEL)))
                .isEqualTo(baseUrl + "/v1");
        assertThat(TianYiYunImageGenerationAdapter.generateUri(
                        channel(baseUrl + GENERATE_PATH + "/", MODEL)))
                .isEqualTo(baseUrl + GENERATE_PATH);
    }

    @Test
    void everyAcceptedBaseUrlFormResolvesToTheSameGenerationEndpoint() {
        // 运维可能存的是厂商源站、带版本的根，或者从厂商文档里原样复制的端点。
        // 四种形态都必须解析出恰好一个 {@code {origin}/v1/images/generations}：
        // 多拼一个 /v1 会让每一次真实调用 404，而少拼一个同样如此。
        for (String form : new String[] {
                "https://ai.ctaigw.cn",
                "https://ai.ctaigw.cn/",
                "https://ai.ctaigw.cn/v1",
                "https://ai.ctaigw.cn/v1/",
                "https://ai.ctaigw.cn/v1/images/generations",
                "https://ai.ctaigw.cn/v1/images/generations/"}) {
            assertThat(TianYiYunImageGenerationAdapter.generateUri(channel(form, MODEL)))
                    .as("base url form %s", form)
                    .isEqualTo("https://ai.ctaigw.cn/v1/images/generations");
        }
        // 完整端点被粘贴进来时，轮询/父路径的推导也不会再多加一层版本号。
        assertThat(TianYiYunImageGenerationAdapter.apiRoot(
                        channel("https://ai.ctaigw.cn/v1/images/generations", MODEL)))
                .isEqualTo("https://ai.ctaigw.cn/v1");
    }

    @Test
    void usesAnOperatorProvidedFullGenerationEndpoint() throws Exception {
        respond(200, """
                {"created":1,"data":[{"url":"https://cdn.example.test/full.png"}]}
                """);

        adapter.generate(new GenerationRequest(
                        channel(baseUrl + GENERATE_PATH, MODEL),
                        "image-req-full-url",
                        "a lighthouse at dusk",
                        (String) null,
                        "2K",
                        "2K",
                        1,
                        "url",
                        true,
                        false,
                        60_000))
                .block(Duration.ofSeconds(5));

        assertThat(requestPath.get()).isEqualTo(GENERATE_PATH);
    }

    @Test
    void advertisesTheImageGenerationCapabilityUnderTheTianyiProviderType() {
        assertThat(adapter.providerType()).isEqualTo("TIANYIYUN");
        assertThat(adapter.capabilities()).containsExactly("IMAGE_GEN");
    }

    /**
     * 上游不回应时必须翻译成**上游超时**（504），而不是让一个泛化的异常穿到接口层。
     *
     * <p>线上抓到过这个缺陷：读超时既不是 {@code ProviderException} 也不是
     * {@code ResponseStatusException}，于是 {@code OpenAiErrorMapper} 只能回一句
     * 500 {@code internal_error}。客户端据此会去查自己的请求，而它真正该做的是
     * 用同一个幂等键重试。
     */
    @Test
    void translatesAVendorTimeoutIntoAGatewayTimeout() throws Exception {
        respondSlowly(2_000, 200, """
                {"created":1,"data":[{"url":"https://cdn.example.test/late.png"}]}
                """);

        assertThatThrownBy(() -> adapter.generate(new GenerationRequest(
                        channel(baseUrl, MODEL), "image-req-timeout", "a lighthouse at dusk",
                        (String) null, "2K", "2K", 1, "url", true, false, 300))
                .block(Duration.ofSeconds(15)))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error -> {
                    assertThat(error.status()).isEqualTo(504);
                    assertThat(error.getMessage()).isEqualTo("provider_attempt_timeout");
                });
    }

    /**
     * 连不上厂商时必须翻译成**上游不可达**（502），而不是泛化的 500。
     *
     * <p>502 与 504 一样都不算「明确拒绝」，因此预留照旧被保持——
     * 把一次结果未知的调用说成已拒绝，会让重试针对同一套餐再买第二次生成。
     */
    @Test
    void translatesAnUnreachableVendorIntoABadGateway() {
        // 这个端口上没有监听者：连接会被立刻拒绝。
        assertThatThrownBy(() -> adapter.generate(new GenerationRequest(
                        channel("http://127.0.0.1:1", MODEL), "image-req-dead",
                        "a lighthouse at dusk", (String) null, "2K", "2K", 1, "url", true, false, 2_000))
                .block(Duration.ofSeconds(15)))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error -> {
                    assertThat(error.status()).isEqualTo(502);
                    assertThat(error.getMessage()).isEqualTo("provider_unreachable");
                });
    }

    /**
     * 恢复钩子的默认答案必须是「问不出来」。
     *
     * <p>把默认值写成「没生成」会让每一个还没实现这个能力的适配器都谎报军情，
     * 而恢复路径会据此**立刻**释放一次可能已经付费的生成。默认值写错，
     * 代价是真金白银，而不是一条日志。
     */
    @Test
    void theRecoveryHookDefaultsToUnresolved() {
        ImageGenerationAdapter.RecoveryVerdict verdict = adapter.resolve(
                        new ImageGenerationAdapter.RecoveryQuery(
                                UUID.randomUUID(), UUID.randomUUID(), MODEL, "req-1",
                                Instant.now()))
                .block(Duration.ofSeconds(5));

        assertThat(verdict.status())
                .isEqualTo(ImageGenerationAdapter.RecoveryVerdict.Status.UNRESOLVED);
        assertThat(verdict.images()).isEmpty();
    }

    /**
     * 「生成了」却不给图片是契约违规，必须降级成「问不出来」：
     * 一次拿不出图的「已生成」既不能交付也不能计费，把它当成确定答案
     * 会让恢复路径去结算一批客户永远拿不到的图片。
     */
    @Test
    void aProducedVerdictWithoutImagesIsDowngradedToUnresolved() {
        assertThat(ImageGenerationAdapter.RecoveryVerdict.produced(List.of()).status())
                .isEqualTo(ImageGenerationAdapter.RecoveryVerdict.Status.UNRESOLVED);
    }

    /**
     * 渠道预算比上限小的时候，由**它**说了算。
     *
     * <p>这与「渠道超时被静默丢掉」是同一个故事的另一半：光把值传到渠道对象里
     * 还不够，还要确认它真的参与了取小值——否则运维把某条慢渠道调小、
     * 想让它早点失败，照样不会生效。
     */
    @Test
    void theChannelBudgetGovernsWhenItIsSmallerThanTheCap() throws Exception {
        respondSlowly(2_000, 200, """
                {"created":1,"data":[{"url":"https://cdn.example.test/late.png"}]}
                """);

        ImageGenerationAdapter.Channel impatient = new ImageGenerationAdapter.Channel(
                UUID.randomUUID(), UUID.randomUUID(), "TIANYIYUN", baseUrl,
                "sk-vendor-secret", MODEL, 300);

        assertThatThrownBy(() -> adapter.generate(new GenerationRequest(
                        impatient, "image-req-budget", "a lighthouse at dusk",
                        (String) null, "2K", "2K", 1, "url", true, false, 300))
                .block(Duration.ofSeconds(15)))
                .isInstanceOfSatisfying(ProviderAdapter.ProviderException.class, error -> {
                    assertThat(error.status()).isEqualTo(504);
                    assertThat(error.getMessage()).isEqualTo("provider_attempt_timeout");
                });
    }

    /**
     * 渠道预算低于同步生图所需时必须告警，且同一处配置只告警一次。
     *
     * <p>这条告警是线上事故的直接产物：预算被留在列默认值 60 秒，而实测图生图要
     * 74~120.7 秒，于是每一次生成都被网关自己掐断——客户端拿到「结果未知」，
     * 5 分钟后同一个幂等键永久变成 409 {@code image_generation_previously_failed}，
     * 而渠道健康检查只做 DNS/TLS，从来看不出这件事。
     * 取小值的语义不因此改变，但错误配置必须自己喊出来。
     */
    @Test
    void warnsOnceWhenTheChannelBudgetIsShorterThanASynchronousGeneration() throws Exception {
        respond(200, """
                {"created":1,"data":[{"url":"https://cdn.example.test/ok.png"}]}
                """);
        ListAppender<ILoggingEvent> warnings = attachWarningCapture();

        ImageGenerationAdapter.Channel shortBudget = new ImageGenerationAdapter.Channel(
                UUID.randomUUID(), UUID.randomUUID(), "TIANYIYUN", baseUrl,
                "sk-vendor-secret", MODEL, 60_000);

        // 同一个渠道、同一个预算调用两次：告警必须只有一条，不能每个请求刷一行。
        for (int attempt = 0; attempt < 2; attempt++) {
            adapter.generate(new GenerationRequest(
                            shortBudget, "image-req-short-budget-" + attempt, "a lighthouse at dusk",
                            (String) null, "2K", "2K", 1, "url", true, false, 60_000))
                    .block(Duration.ofSeconds(15));
        }

        List<String> raised = warnings.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("image_channel_budget_below_call_cap"))
                .toList();

        assertThat(raised).hasSize(1);
        assertThat(raised.get(0)).contains("configuredMs=60000");
    }

    /** 抓取本适配器的 WARN 日志，用于断言告警确实出现、且只出现一次。 */
    private static ListAppender<ILoggingEvent> attachWarningCapture() {
        Logger logger = (Logger) LoggerFactory.getLogger(TianYiYunImageGenerationAdapter.class);
        ListAppender<ILoggingEvent> warnings = new ListAppender<>();
        warnings.setContext(logger.getLoggerContext());
        warnings.start();
        logger.addAppender(warnings);
        return warnings;
    }

    private GenerationRequest request(
            String prompt, String inputImageUrl, String size, int imageCount) {
        return request(prompt, inputImageUrl, size, imageCount, false);
    }

    private GenerationRequest request(
            String prompt,
            String inputImageUrl,
            String size,
            int imageCount,
            boolean layerDecomposition) {
        return new GenerationRequest(
                channel(baseUrl, MODEL),
                "image-req-1",
                prompt,
                inputImageUrl,
                size,
                size,
                imageCount,
                "url",
                true,
                layerDecomposition,
                60_000);
    }

    private ImageGenerationAdapter.Channel channel(String url, String upstreamModel) {
        return new ImageGenerationAdapter.Channel(
                UUID.randomUUID(), UUID.randomUUID(), "TIANYIYUN", url, "sk-vendor-secret",
                upstreamModel, 150_000);
    }

    /** 用同一个固定响应体答复每个请求，并记录收到的请求内容。 */
    private void respond(int status, String body) throws Exception {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            requestMethod.set(exchange.getRequestMethod());
            requestPath.set(exchange.getRequestURI().getPath());
            authorization.set(header(exchange, "Authorization"));
            contentType.set(header(exchange, "Content-Type"));
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

    /** 延迟一段时间再回应答——用来演练读超时。 */
    private void respondSlowly(long delayMillis, int status, String body) throws Exception {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
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

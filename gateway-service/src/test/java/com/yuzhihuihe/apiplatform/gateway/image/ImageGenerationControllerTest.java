package com.yuzhihuihe.apiplatform.gateway.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.openai.ChatCompletionController;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 图片生成的 HTTP 契约：请求体如何被翻译、生成获得哪个 id、响应长什么样，
 * 以及对于按 OpenAI 图片 API 编写的客户端而言错误长什么样。
 *
 * <p>计费路径本身由 {@link ImageGenerationBillingTest} 针对真实数据库覆盖；本测试锁定的是
 * 其外围的转换层，而正是在这一层，一次重试的调用要么复用已付费的那次生成，
 * 要么又买下一次生成。
 */
class ImageGenerationControllerTest {

    private static final UUID USER_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("60000000-0000-4000-8000-000000000002");
    private static final UUID PACKAGE_ID = UUID.fromString("60000000-0000-4000-8000-000000000003");
    private static final UUID API_KEY_ID = UUID.fromString("60000000-0000-4000-8000-000000000004");
    private static final String TRACE_ID = "req_0123456789abcdef0123456789abcdef";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ImageGenerationService service;
    private ImageGenerationController controller;

    @BeforeEach
    void setUp() {
        service = mock(ImageGenerationService.class);
        controller = new ImageGenerationController(service);
    }

    @Test
    void anIdempotencyKeyBecomesTheGenerationIdSoARetryDoesNotBuyASecondGeneration() {
        when(service.generate(any())).thenReturn(Mono.just(generated("image-task-1")));

        ResponseEntity<Object> response = controller.generate(
                        request("image-basic", "a lighthouse at dusk", null, "2K", 1, "url",
                                true, null),
                        exchange("image-task-1"))
                .block();

        ArgumentCaptor<ImageGenerationService.SubmitCommand> command =
                ArgumentCaptor.forClass(ImageGenerationService.SubmitCommand.class);
        verify(service).generate(command.capture());
        assertThat(command.getValue().requestId()).isEqualTo("image-task-1");
        assertThat(command.getValue().apiKey().userId()).isEqualTo(USER_ID);
        assertThat(command.getValue().modelAlias()).isEqualTo("image-basic");
        assertThat(command.getValue().prompt()).isEqualTo("a lighthouse at dusk");
        assertThat(command.getValue().size()).isEqualTo("2K");
        assertThat(command.getValue().sizeTier()).isEqualTo("2K");
        // 一次调用一张：显式 n = 1 是合法的，并且原样成为预留上限。
        assertThat(command.getValue().imageCount()).isEqualTo(1);
        assertThat(command.getValue().responseFormat()).isEqualTo("url");
        assertThat(command.getValue().watermark()).isTrue();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // trace id 仍保留为网关自己的，因此重放同一个 key 不会与
        // 第一次尝试的请求日志相撞。
        assertThat(response.getHeaders().getFirst(ImageGenerationController.REQUEST_ID_HEADER))
                .isEqualTo(TRACE_ID);
    }

    @Test
    void withoutAnIdempotencyKeyTheGatewayTraceIdIsTheGenerationId() {
        when(service.generate(any())).thenReturn(Mono.just(generated(TRACE_ID)));

        controller.generate(
                        request("image-basic", "a lighthouse at dusk", null, null, null, null,
                                null, null),
                        exchange(null))
                .block();

        ArgumentCaptor<ImageGenerationService.SubmitCommand> command =
                ArgumentCaptor.forClass(ImageGenerationService.SubmitCommand.class);
        verify(service).generate(command.capture());
        assertThat(command.getValue().requestId()).isEqualTo(TRACE_ID);
        // 省略的字段落到网关的默认值：2K、一张、url、带水印。
        assertThat(command.getValue().size()).isEqualTo("2K");
        assertThat(command.getValue().imageCount()).isEqualTo(1);
        assertThat(command.getValue().responseFormat()).isEqualTo("url");
        assertThat(command.getValue().watermark()).isTrue();
        assertThat(command.getValue().layerDecomposition()).isFalse();
    }

    @Test
    void anOverlongIdempotencyKeyIsRejectedBeforeAnyGenerationStarts() {
        // 改为截断会让两个不同的 key 互相重放对方的生成。
        ResponseEntity<Object> response = controller.generate(
                        request("image-basic", "a lighthouse at dusk", null, null, null, null,
                                null, null),
                        exchange("k".repeat(129)))
                .block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(response)).isEqualTo("invalid_idempotency_key");
        verify(service, never()).generate(any());
    }

    /**
     * 一次调用产出一张，因此 {@code n = 1} 与缺省的 {@code n} 是仅有的两种合法形态，
     * 两者都被接受并落到「一次一张」上。
     */
    @Test
    void anExplicitSingleImageRequestIsServed() {
        when(service.generate(any())).thenReturn(Mono.just(generated("image-task-one")));

        ResponseEntity<Object> response = controller.generate(
                        request("image-basic", "a lighthouse at dusk", null, "2K", 1, "url",
                                true, null),
                        exchange("image-task-one"))
                .block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<ImageGenerationService.SubmitCommand> command =
                ArgumentCaptor.forClass(ImageGenerationService.SubmitCommand.class);
        verify(service).generate(command.capture());
        assertThat(command.getValue().imageCount()).isEqualTo(1);
    }

    /**
     * 多张现在是**合法**的：一次请求可以买 1~4 张，由服务层并发扇出。
     *
     * <p>改变这一点的依据是 2026-10-12 的实测：厂商受理但**静默忽略** {@code n}
     * （{@code n=2} 与 {@code n=4} 都回 200 + 恰好一张图），所以过去的
     * "拒绝 n > 1" 是当时唯一诚实的选择。现在网关自己扇出，N 张可以真的交付，
     * 因此这里断言的是「张数被原样交给服务层」——它是钱，不能被悄悄改掉。
     */
    @Test
    void aMultiImageRequestIsPassedThroughToTheService() {
        when(service.generate(any())).thenReturn(Mono.just(generated("image-task-multi")));

        ResponseEntity<Object> response = controller.generate(
                        request("image-basic", "four lighthouses", null, "2K", 4, "url",
                                true, null),
                        exchange("image-task-multi"))
                .block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<ImageGenerationService.SubmitCommand> command =
                ArgumentCaptor.forClass(ImageGenerationService.SubmitCommand.class);
        verify(service).generate(command.capture());
        assertThat(command.getValue().imageCount()).isEqualTo(4);
    }

    /**
     * 超出扇出上限的张数必须**响亮地**拒绝，而不是静默截断。
     *
     * <p>客户要 8 张、只拿到 4 张而没有任何提示，与今天"要 4 张只给 1 张"是同一种病；
     * 而 0 与负数是一个"买零张"的生成，没有可交付的东西。原因没变，所以错误码
     * 也没变：{@code image_count_not_supported}，客户端已有的分支不需要改。
     */
    @Test
    void anImageCountOutsideTheFanoutRangeIsRejectedWithItsOwnReasonCode() {
        int tooMany = ImageGenerationService.MAX_FANOUT + 1;
        for (int n : new int[] {0, -1, tooMany, tooMany + 4}) {
            ResponseEntity<Object> response = controller.generate(
                            request("image-basic", "a lighthouse at dusk", null, "2K", n,
                                    "url", true, null),
                            exchange("image-task-n" + n))
                    .block();

            assertThat(response.getStatusCode())
                    .as("n = %d must not be served", n)
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(errorCode(response))
                    .as("n = %d must carry the stable reason code", n)
                    .isEqualTo("image_count_not_supported");
        }
        verify(service, never()).generate(any());
    }

    /**
     * 多参考图生图：{@code image} 为 URL 数组时按序解析成多张输入图。
     *
     * <p>2026-10-10 实测上游（天翼云 doubao-seedream-5.0-pro）接受数组并在
     * {@code usage.input_images} 中如实报出张数，因此数组不再被拒绝，
     * 而是成为「多参考图」契约的一部分。
     */
    @Test
    void anImageArrayIsParsedAsMultiReferenceInputInOrder() {
        when(service.generate(any())).thenReturn(Mono.just(generated("image-task-array")));

        controller.generate(
                        request("image-basic", "restyle these", array("\"https://a\",\"https://b\""),
                                null, null, null, null, null),
                        exchange("image-task-array"))
                .block();

        ArgumentCaptor<ImageGenerationService.SubmitCommand> command =
                ArgumentCaptor.forClass(ImageGenerationService.SubmitCommand.class);
        verify(service).generate(command.capture());
        assertThat(command.getValue().inputImageUrls())
                .containsExactly("https://a", "https://b");
        assertThat(command.getValue().inputImageFileIds()).isEmpty();
    }

    /** 多参考图上限 8 张：超出在网关侧拒绝，不把超量请求交给厂商。 */
    @Test
    void moreThanEightInputImagesAreRejectedWithTheirOwnCode() {
        String[] urls = new String[9];
        for (int i = 0; i < urls.length; i++) {
            urls[i] = "\"https://cdn.example.test/" + i + ".png\"";
        }
        ResponseEntity<Object> response = controller.generate(
                        request("image-basic", "too many", array(String.join(",", urls)),
                                null, null, null, null, null),
                        exchange("image-task-too-many"))
                .block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(errorCode(response)).isEqualTo("image_input_count_exceeded");
        verify(service, never()).generate(any());
    }

    /** 数组元素必须是 URL 字符串；嵌套对象等形状仍保留既有错误码。 */
    @Test
    void anArrayContainingNonTextualEntriesKeepsItsReasonCode() {
        ResponseEntity<Object> response = controller.generate(
                        request("image-basic", "restyle", array("17, \"https://a\""),
                                null, null, null, null, null),
                        exchange("image-task-mixed"))
                .block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(errorCode(response)).isEqualTo("image_input_array_not_supported");
        verify(service, never()).generate(any());
    }

    @Test
    void aNonTextualImageFieldIsRejectedAsAnInvalidRequest() {
        ResponseEntity<Object> response = controller.generate(
                        request("image-basic", "restyle", json("17"), null, null, null, null, null),
                        exchange("image-task-number"))
                .block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(errorCode(response)).isEqualTo("invalid_image_request");
        verify(service, never()).generate(any());
    }

    @Test
    void theResponseIsOpenAiShapedWithUrls() throws Exception {
        when(service.generate(any())).thenReturn(Mono.just(generated("image-task-2")));

        ResponseEntity<Object> response = controller.generate(
                        request("image-basic", "a lighthouse at dusk", null, null, null, null,
                                null, null),
                        exchange("image-task-2"))
                .block();

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(JSON.writeValueAsString(response.getBody()))
                .isEqualTo("{\"created\":1758950000,"
                        + "\"data\":[{\"url\":\"https://cdn.example.test/0.png\"}]}");
    }

    @Test
    void inlineBase64ResultsKeepTheirOpenAiFieldName() throws Exception {
        when(service.generate(any())).thenReturn(Mono.just(new ImageGenerationService.GenerationView(
                "image-task-3",
                "image-basic",
                1758950000L,
                1,
                1,
                List.of(new ImageGenerationAdapter.GeneratedImage(null, "aGVsbG8=")),
                null,
                false)));

        ResponseEntity<Object> response = controller.generate(
                        request("image-basic", "a lighthouse at dusk", null, null, null, "b64_json",
                                null, null),
                        exchange("image-task-3"))
                .block();

        assertThat(JSON.writeValueAsString(response.getBody()))
                .isEqualTo("{\"created\":1758950000,\"data\":[{\"b64_json\":\"aGVsbG8=\"}]}");
    }

    @Test
    void imageFailuresUseTheOpenAiErrorEnvelope() {
        when(service.generate(any())).thenReturn(Mono.error(new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "image_requires_image_denominated_package")));

        ResponseEntity<Object> response = controller.generate(
                        request("image-basic", "a lighthouse at dusk", null, null, null, null,
                                null, null),
                        exchange("image-task-4"))
                .block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getHeaders().getFirst(ImageGenerationController.REQUEST_ID_HEADER))
                .isEqualTo(TRACE_ID);
        ChatCompletionController.OpenAiErrorResponse body =
                (ChatCompletionController.OpenAiErrorResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.error().code()).isEqualTo("image_requires_image_denominated_package");
        assertThat(body.error().type()).isEqualTo("quota_error");
        assertThat(body.request_id()).isEqualTo(TRACE_ID);
    }

    @Test
    void anUnsupportedSizeIsReportedWithItsOwnCode() {
        when(service.generate(any())).thenReturn(Mono.error(new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "image_size_not_sold_by_model")));

        ResponseEntity<Object> response = controller.generate(
                        request("image-basic", "a 4K render", null, "4K", null, null, null, null),
                        exchange("image-task-5"))
                .block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(errorCode(response)).isEqualTo("image_size_not_sold_by_model");
    }

    @Test
    void anAnonymousCallIsRejectedRatherThanServed() {
        // 过滤器通常会在到达控制器之前拒绝未认证的调用；此处为 null 意味着
        // 路由配置错误，因此这里 fail-closed，而不是放行一次匿名生成。
        MockServerWebExchange anonymous = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/images/generations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .build());

        ResponseEntity<Object> response = controller.generate(
                        request("image-basic", "a lighthouse at dusk", null, null, null, null,
                                null, null),
                        anonymous)
                .block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(response)).isEqualTo("invalid_api_key");
        verify(service, never()).generate(any());
    }

    private static String errorCode(ResponseEntity<Object> response) {
        ChatCompletionController.OpenAiErrorResponse body =
                (ChatCompletionController.OpenAiErrorResponse) response.getBody();
        assertThat(body).isNotNull();
        return body.error().code();
    }

    private static ImageGenerationService.GenerationView generated(String requestId) {
        return new ImageGenerationService.GenerationView(
                requestId,
                "image-basic",
                1758950000L,
                1,
                1,
                List.of(new ImageGenerationAdapter.GeneratedImage(
                        "https://cdn.example.test/0.png", null)),
                null,
                // 单图：交付的就是请求的那一张，因此绝不带 partial 标记。
                false);
    }

    private static ImageGenerationController.ImageRequest request(
            String model,
            String prompt,
            JsonNode image,
            String size,
            Integer n,
            String responseFormat,
            Boolean watermark,
            Boolean layerDecomposition) {
        return new ImageGenerationController.ImageRequest(
                model, prompt, image, null, size, n, responseFormat, watermark, layerDecomposition);
    }

    private static JsonNode array(String elements) {
        try {
            return JSON.readTree("[" + elements + "]");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static JsonNode json(String value) {
        try {
            return JSON.readTree(value);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static MockServerWebExchange exchange(String idempotencyKey) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.post(
                        "/v1/images/generations")
                .header("Authorization", "Bearer sk-yzh-test")
                .contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            request = request.header(
                    ImageGenerationController.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(request.build());
        exchange.getAttributes().put(
                ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE, apiKey());
        exchange.getAttributes().put(ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, TRACE_ID);
        return exchange;
    }

    private static ApiKeyService.ApiKeyContext apiKey() {
        return new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("image-basic"), true);
    }
}

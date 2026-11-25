package com.yuzhihuihe.apiplatform.gateway.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;

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
 * 视频生成的 HTTP 契约：任务获得哪个 id，以及对于按 {@code /v1/chat/completions}
 * 编写的客户端而言错误长什么样。
 *
 * <p>计费路径本身由 {@link VideoGenerationBillingTest} 针对真实数据库覆盖；本测试锁定的是
 * 其外围的转换层，而正是在这一层，一次重试的提交要么复用已付费的那次生成，要么又买下一次生成。
 */
class VideoGenerationControllerTest {

    private static final UUID USER_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("60000000-0000-4000-8000-000000000002");
    private static final UUID PACKAGE_ID = UUID.fromString("60000000-0000-4000-8000-000000000003");
    private static final UUID API_KEY_ID = UUID.fromString("60000000-0000-4000-8000-000000000004");
    private static final String TRACE_ID = "req_0123456789abcdef0123456789abcdef";

    private VideoGenerationService service;
    private VideoGenerationController controller;

    @BeforeEach
    void setUp() {
        service = mock(VideoGenerationService.class);
        controller = new VideoGenerationController(service);
    }

    @Test
    void anIdempotencyKeyBecomesTheTaskIdSoARetryDoesNotBuyASecondGeneration() {
        when(service.submit(any())).thenReturn(Mono.just(task("video-task-1", "SUBMITTED")));

        ResponseEntity<Object> response = controller.submit(
                        new VideoGenerationController.VideoRequest(
                                "video-basic", "a lighthouse at dusk", null, 10, "720P", "16:9", false),
                        exchange("video-task-1"))
                .block();

        ArgumentCaptor<VideoGenerationService.SubmitCommand> command =
                ArgumentCaptor.forClass(VideoGenerationService.SubmitCommand.class);
        verify(service).submit(command.capture());
        assertThat(command.getValue().requestId()).isEqualTo("video-task-1");
        assertThat(command.getValue().apiKey().userId()).isEqualTo(USER_ID);
        assertThat(command.getValue().modelAlias()).isEqualTo("video-basic");
        assertThat(command.getValue().durationSeconds()).isEqualTo(10);
        assertThat(command.getValue().resolutionTier()).isEqualTo("720P");
        assertThat(command.getValue().aspectRatio()).isEqualTo("16:9");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // trace id 仍保留为网关自己的，因此重放同一个 key 不会与
        // 第一次尝试的请求日志相撞。
        assertThat(response.getHeaders().getFirst(VideoGenerationController.REQUEST_ID_HEADER))
                .isEqualTo(TRACE_ID);
    }

    @Test
    void withoutAnIdempotencyKeyTheGatewayTraceIdIsTheTaskId() {
        when(service.submit(any())).thenReturn(Mono.just(task(TRACE_ID, "SUBMITTED")));

        controller.submit(
                        new VideoGenerationController.VideoRequest(
                                "video-basic", "a lighthouse at dusk", null, 10, "720P", null, false),
                        exchange(null))
                .block();

        ArgumentCaptor<VideoGenerationService.SubmitCommand> command =
                ArgumentCaptor.forClass(VideoGenerationService.SubmitCommand.class);
        verify(service).submit(command.capture());
        assertThat(command.getValue().requestId()).isEqualTo(TRACE_ID);
    }

    @Test
    void anOverlongIdempotencyKeyIsRejectedBeforeAnyGenerationStarts() {
        // 改为截断会让两个不同的 key 互相重放对方的生成。
        ResponseEntity<Object> response = controller.submit(
                        new VideoGenerationController.VideoRequest(
                                "video-basic", "a lighthouse at dusk", null, 10, "720P", null, false),
                        exchange("k".repeat(129)))
                .block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(response)).isEqualTo("invalid_idempotency_key");
        verify(service, never()).submit(any());
    }

    @Test
    void videoFailuresUseTheOpenAiErrorEnvelope() {
        when(service.submit(any())).thenReturn(Mono.error(new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "video_requires_second_denominated_package")));

        ResponseEntity<Object> response = controller.submit(
                        new VideoGenerationController.VideoRequest(
                                "video-basic", "a lighthouse at dusk", null, 10, "720P", null, false),
                        exchange("video-task-2"))
                .block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getFirst(VideoGenerationController.REQUEST_ID_HEADER))
                .isEqualTo(TRACE_ID);
        ChatCompletionController.OpenAiErrorResponse body =
                (ChatCompletionController.OpenAiErrorResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.error().code()).isEqualTo("video_requires_second_denominated_package");
        assertThat(body.error().type()).isEqualTo("quota_error");
        assertThat(body.request_id()).isEqualTo(TRACE_ID);
    }

    @Test
    void statusOfAMissingTaskIsAnOpenAiStyleNotFound() {
        when(service.status(any(), any())).thenReturn(Mono.error(new ResponseStatusException(
                HttpStatus.NOT_FOUND, "video_task_not_found")));

        ResponseEntity<Object> response = controller.status("video-task-3", exchange(null)).block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCode(response)).isEqualTo("video_task_not_found");
    }

    @Test
    void contentResolvesAsANonCacheableRedirect() {
        when(service.contentUrl(any(), any()))
                .thenReturn(Mono.just("mock://video/output/succeed/video-task-4"));

        ResponseEntity<Object> response =
                controller.content("video-task-4", exchange(null)).block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation())
                .hasToString("mock://video/output/succeed/video-task-4");
        // 供应商的地址会过期；被缓存的跳转会比对象本身存活得更久。
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void contentThatIsNotReadyIsAConflictRatherThanAMissingTask() {
        when(service.contentUrl(any(), any())).thenReturn(Mono.error(new ResponseStatusException(
                HttpStatus.CONFLICT, "video_output_not_ready")));

        ResponseEntity<Object> response =
                controller.content("video-task-5", exchange(null)).block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCode(response)).isEqualTo("video_output_not_ready");
    }

    private static String errorCode(ResponseEntity<Object> response) {
        ChatCompletionController.OpenAiErrorResponse body =
                (ChatCompletionController.OpenAiErrorResponse) response.getBody();
        assertThat(body).isNotNull();
        return body.error().code();
    }

    private static VideoGenerationService.TaskView task(String requestId, String status) {
        return new VideoGenerationService.TaskView(
                requestId, "video-basic", status, 10, null, "720P", null, null);
    }

    private static MockServerWebExchange exchange(String idempotencyKey) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.post("/v1/videos")
                .header("Authorization", "Bearer sk-yzh-test")
                .contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            request = request.header(
                    VideoGenerationController.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(request.build());
        exchange.getAttributes().put(
                ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE, apiKey());
        exchange.getAttributes().put(ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, TRACE_ID);
        return exchange;
    }

    private static ApiKeyService.ApiKeyContext apiKey() {
        return new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("video-basic"), true);
    }
}

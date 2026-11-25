package com.yuzhihuihe.apiplatform.gateway.video;

import java.net.URI;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.openai.ChatCompletionController;
import com.yuzhihuihe.apiplatform.gateway.openai.OpenAiErrorMapper;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 异步视频生成，形态仿照 OpenAI 视频 API。
 *
 * <p>{@code POST /v1/videos} 负责提交并返回一个任务；{@code GET /v1/videos/{id}}
 * 返回其状态；{@code GET /v1/videos/{id}/content} 解析出已完成的视频。
 * 之所以选择这种形态而不是同步调用，是因为视频
 * 生成需要数十秒到数分钟；之所以不用定制形态，是因为
 * 客户端已经有兼容 OpenAI 的管道——这一点在 OpenAI 自家视频 API
 * 正在被撤下的现在也很重要，因为既有集成只需改动 base URL
 * 就能重新指向本网关。
 *
 * <p>认证、速率限制与模型允许列表由守卫 {@code /v1/chat/completions} 的
 * 同一个 {@link ApiKeyWebFilter} 施加；本控制器只负责
 * 把 HTTP 翻译成 {@link VideoGenerationService} 调用。
 *
 * <p>有两个 id 会流经本端点，它们并不是同一回事。网关
 * 自己的 {@code x-request-id} 是追踪 id，按每次 HTTP 调用生成。任务 id 才是
 * 计费身份：{@code GET /v1/videos/{id}} 取的是它，它在
 * 数据库中唯一，而提供 {@code Idempotency-Key} 的客户端选择的也是它，
 * 因此一次超时后的重试提交会返回已经付过费的生成，
 * 而不是再买一次。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class VideoGenerationController {

    static final String REQUEST_ID_HEADER = "x-request-id";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** 与 {@code video_generation_task.request_id} 匹配：更长的键无法被存储。 */
    private static final int MAX_TASK_ID_LENGTH = 128;

    private final VideoGenerationService service;

    VideoGenerationController(VideoGenerationService service) {
        this.service = service;
    }

    @PostMapping(path = "/v1/videos", consumes = MediaType.APPLICATION_JSON_VALUE)
    Mono<ResponseEntity<Object>> submit(
            @RequestBody VideoRequest body, ServerWebExchange exchange) {
        String traceId = traceId(exchange);
        return Mono.defer(() -> {
                    ApiKeyService.ApiKeyContext apiKey = apiKey(exchange);
                    return service.submit(new VideoGenerationService.SubmitCommand(
                            taskId(exchange, traceId),
                            apiKey,
                            body == null ? null : body.model(),
                            body == null ? null : body.prompt(),
                            body == null ? null : body.inputImageUrl(),
                            body == null || body.durationSeconds() == null
                                    ? 0 : body.durationSeconds(),
                            body == null ? null : body.resolution(),
                            body == null ? null : body.aspectRatio(),
                            body != null && Boolean.TRUE.equals(body.withAudio())))
                            .map(view -> ResponseEntity.status(HttpStatus.CREATED)
                                    .header(REQUEST_ID_HEADER, traceId)
                                    .body((Object) view));
                })
                .onErrorResume(error -> Mono.just(errorResponse(error, traceId)));
    }

    @GetMapping("/v1/videos/{requestId}")
    Mono<ResponseEntity<Object>> status(
            @PathVariable String requestId, ServerWebExchange exchange) {
        String traceId = traceId(exchange);
        return Mono.defer(() -> service.status(apiKey(exchange), requestId))
                .map(view -> ResponseEntity.ok()
                        .header(REQUEST_ID_HEADER, traceId)
                        .body((Object) view))
                .onErrorResume(error -> Mono.just(errorResponse(error, traceId)));
    }

    /**
     * 解析已完成的视频。
     *
     * <p>用重定向而不是字节流：网关不镜像生成结果，
     * 而代理每一次下载都会把产品的出口流量压在网关上，却换不来
     * 任何契约上的收益。因为客户端自己跟随重定向，响应被
     * 显式标记为不可缓存——厂商位置会过期，而缓存下来的重定向
     * 会比它指向的对象活得更久。
     */
    @GetMapping("/v1/videos/{requestId}/content")
    Mono<ResponseEntity<Object>> content(
            @PathVariable String requestId, ServerWebExchange exchange) {
        String traceId = traceId(exchange);
        return Mono.defer(() -> service.contentUrl(apiKey(exchange), requestId))
                .map(location -> ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(location))
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header(REQUEST_ID_HEADER, traceId)
                        .body((Object) null))
                .onErrorResume(error -> Mono.just(errorResponse(error, traceId)));
    }

    private ApiKeyService.ApiKeyContext apiKey(ServerWebExchange exchange) {
        ApiKeyService.ApiKeyContext apiKey =
                exchange.getAttribute(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE);
        if (apiKey == null) {
            // 过滤器通常会在到达此处之前拒绝未认证的调用；
            // 此处为 null 意味着路由配置错误，因此 fail-closed，
            // 而不是放行一次匿名生成。
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_api_key");
        }
        return apiKey;
    }

    private String traceId(ServerWebExchange exchange) {
        return exchange.getAttributeOrDefault(
                ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, "req_" + UUID.randomUUID());
    }

    /**
     * 任务 id：提供了客户端幂等键时用它，否则用网关的追踪 id。
     *
     * <p>该键是可选的，因为只提交一次的客户端没有什么需要重放；
     * 而它被限制在列宽之内、而不是被截断：两个不同的键
     * 若截断成同一个值，就会互相重放对方的生成。
     */
    private String taskId(ServerWebExchange exchange, String traceId) {
        String supplied = exchange.getRequest().getHeaders().getFirst(IDEMPOTENCY_KEY_HEADER);
        if (supplied == null || supplied.isBlank()) {
            return traceId;
        }
        String key = supplied.strip();
        if (key.length() > MAX_TASK_ID_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid_idempotency_key");
        }
        return key;
    }

    private ResponseEntity<Object> errorResponse(Throwable error, String traceId) {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(error);
        return ResponseEntity.status(descriptor.status())
                .headers(OpenAiErrorMapper.retryHeaders(error))
                .header(REQUEST_ID_HEADER, traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChatCompletionController.OpenAiErrorResponse(
                        new ChatCompletionController.OpenAiError(
                                descriptor.message(), descriptor.type(), descriptor.code()),
                        traceId));
    }

    /**
     * 请求体，字段名照搬 OpenAI 视频 API。
     *
     * @param model 逻辑视频别名，例如 {@code video-basic}
     * @param prompt 生成提示词
     * @param inputImageUrl 可选的首帧，提供它即为图生视频
     * @param durationSeconds 请求的片段时长；以秒为单位计费和预留
     * @param resolution 480P / 720P / 1080P 之一，与费率卡档位一致
     * @param aspectRatio 可选的输出画幅比例（例如 {@code 16:9}）
     * @param withAudio 是否请求生成音频
     */
    record VideoRequest(
            String model,
            String prompt,
            String inputImageUrl,
            Integer durationSeconds,
            String resolution,
            String aspectRatio,
            Boolean withAudio) { }
}

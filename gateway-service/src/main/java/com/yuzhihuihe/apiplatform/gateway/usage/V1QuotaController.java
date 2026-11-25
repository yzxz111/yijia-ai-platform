package com.yuzhihuihe.apiplatform.gateway.usage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.openai.ChatCompletionController;
import com.yuzhihuihe.apiplatform.gateway.openai.OpenAiErrorMapper;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * {@code GET /v1/quota} —— 出示的 API key 的套餐配额，面向持有 key 但没有控制台会话的调用方：
 * 桌面客户端与外部集成。在此之前，读取剩余配额的唯一方式是经过会话认证的
 * {@code /console/gateway/quota/summary}。
 *
 * <p>认证由 {@link ApiKeyWebFilter} 负责，它已经覆盖了每一条 {@code /v1/**} 路径，所以未认证的
 * 请求永远不会到达这个控制器；这里的 null 检查只是防御过滤器缺失的情况（切片测试，或未来白名单
 * 变更），并与其他 {@code /v1} 控制器处理缺失上下文的方式保持一致。
 *
 * <p>作用域跟随 key 而不是账号：一个用户可以持有多个套餐实例，而一个 key 只绑定到其中一个，
 * 所以答案是「这个 key 还能花掉什么」。控制台更丰富的摘要仍然是账号级的视图（生效中的套餐加上
 * 排队中的套餐）。
 *
 * <p>直接读取套餐实例，所以这些数字包含每一笔尚未结算的预留 —— 数字会随流量运行而变化，
 * 中间没有任何缓存。
 *
 * <p>错误使用与其他每一条 {@code /v1} 路由相同的 OpenAI 响应格式
 * （{@link OpenAiErrorMapper}），而不是 Spring 默认的错误响应体。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class V1QuotaController {

    private final UsageQueryService service;

    public V1QuotaController(UsageQueryService service) {
        this.service = service;
    }

    @GetMapping("/v1/quota")
    public Mono<ResponseEntity<?>> quota(ServerWebExchange exchange) {
        ApiKeyService.ApiKeyContext context =
                exchange.getAttribute(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE);
        String requestId = exchange.getAttributeOrDefault(
                ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, requestId());
        if (context == null) {
            return Mono.just(errorResponse(
                    new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_api_key"),
                    requestId));
        }
        UUID packageInstanceId = context.packageInstanceId();
        if (packageInstanceId == null) {
            // 一个全零的响应体在客户端看来会是「配额已耗尽」，那是另一个问题、另一种修法，
            // 所以没有套餐的 key 得到显式的 404。
            return Mono.just(errorResponse(
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "package_not_found"),
                    requestId));
        }
        return service.packageQuota(context.tenantId(), packageInstanceId)
                .<ResponseEntity<?>>map(packageQuota -> ResponseEntity.ok()
                        .header("x-request-id", requestId)
                        // 额度是余额类数据：显式禁止任何中间层或客户端缓存，否则「实时」会被一个
                        // 缓存住的旧读数破坏（nginx 侧 /v1/ 本就是 proxy_cache off，这里是第二道）。
                        .header("Cache-Control", "no-store")
                        .body(QuotaView.of(packageQuota)))
                .onErrorResume(error -> Mono.just(errorResponse(error, requestId)));
    }

    private ResponseEntity<ChatCompletionController.OpenAiErrorResponse> errorResponse(
            Throwable error, String requestId) {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(error);
        return ResponseEntity.status(descriptor.status())
                .headers(OpenAiErrorMapper.retryHeaders(error))
                .header("x-request-id", requestId)
                .header("Cache-Control", "no-store")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChatCompletionController.OpenAiErrorResponse(
                        new ChatCompletionController.OpenAiError(
                                descriptor.message(), descriptor.type(), descriptor.code()),
                        requestId));
    }

    private String requestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 公开的数据形状。
     *
     * <p>{@code totalTokens / reservedTokens / usedTokens / remainingTokens} 是以
     * {@code usageDimension} 的单位表示的计数：对 {@code VIDEO_SECOND} 套餐它们是秒，对
     * {@code IMAGE} 套餐它们是图片张数，而不是积分 —— 与控制台使用的约定相同。
     * 客户端必须按 {@code usageDimension} 来标注它们，而不能假定是积分。
     *
     * <p>{@code reservedTokens} 是尚未结算请求的在途占用，所以 {@code remainingTokens} 已经
     * 扣除了它：即将消费的调用方应把 {@code remainingTokens}（而不是
     * {@code totalTokens − usedTokens}）当作可花费的数字。
     * 如果一个请求结算的用量高于预留量，它可能为负数。
     */
    public record QuotaView(
            String packageCode,
            String packageName,
            String status,
            long totalTokens,
            long reservedTokens,
            long usedTokens,
            long remainingTokens,
            String usageDimension,
            /**
             * 方案 C 的第二份额度，或 {@code null} 表示这个套餐不卖语音。
             *
             * <p>它是**加法**的：既有的四个计数器描述主维度那一份（单位由
             * {@code usageDimension} 决定），这一份描述音频秒那一份。客户端必须把
             * {@code null} 读成"这个套餐不包含语音"并**不渲染**，
             * 而不是渲染 0 秒——一个"包含 0 秒语音"的承诺是假的。
             */
            UsageQueryService.AudioSeconds audioSeconds,
            Instant expiresAt,
            Integer dailyRequestLimit,
            List<String> allowedModels) {

        static QuotaView of(UsageQueryService.ActivePackage view) {
            return new QuotaView(
                    view.packageCode(), view.packageName(), view.status(),
                    view.totalTokens(), view.reservedTokens(), view.usedTokens(),
                    view.remainingTokens(), view.usageDimension(), view.audioSeconds(),
                    view.expiresAt(), view.dailyRequestLimit(), view.allowedModels());
        }
    }
}

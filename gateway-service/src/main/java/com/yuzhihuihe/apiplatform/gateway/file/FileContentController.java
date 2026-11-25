package com.yuzhihuihe.apiplatform.gateway.file;

import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.openai.ChatCompletionController;
import com.yuzhihuihe.apiplatform.gateway.openai.OpenAiErrorMapper;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

/**
 * 公网可匿名读取的图片内容端点，供**厂商**拉取图生图的参考图。
 *
 * <pre>
 * GET /v1/files/{fileId}/content?exp={unix秒}&amp;sig={base64url}
 * </pre>
 *
 * <p><b>为什么必须匿名。</b>去拉这张图的是天翼，不是我们的客户端——它不会带
 * {@code Authorization}。而它也不能被无条件放开：URL 里的签名就是这次读取的凭证，
 * 因此 {@code ApiKeyWebFilter} 把这条路径按「GET 且形状匹配」直接放行，
 * 真正的准入判断在 {@link FileContentService} 里。
 *
 * <p><b>限流的顺序是刻意的：先验签，再计数。</b>被签名校验挡掉的请求不该占用
 * 真实调用方的额度，否则任何人都能用垃圾请求把厂商挤下线；反过来，只有验签通过的
 * 请求才值得计入"这次真的会交付字节"。见 {@link FileContentRateLimiter} 的类注释。
 *
 * <p>与目录接口一致，它总是带 {@code Cache-Control} 与 {@code x-request-id}。
 * 缓存上限由文件自身的保留期决定（见 {@code FileContentService.maxAgeSeconds}），
 * 因为一个被缓存到过期的地址会让厂商拿到 404。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class FileContentController {

    private final FileContentService service;
    private final FileContentRateLimiter rateLimiter;

    FileContentController(FileContentService service, FileContentRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/v1/files/{fileId}/content")
    Mono<ResponseEntity<?>> content(
            @PathVariable String fileId,
            @RequestParam(name = "exp", required = false) String expires,
            @RequestParam(name = "sig", required = false) String signature,
            ServerWebExchange exchange) {
        String requestId = requestId(exchange);
        // **顺序：验签 -> 限流 -> 读对象。** 三步都不能换位。
        //
        // 验签最先：伪造的洪流连数据库都不该碰到（服务层原本就这么做）。
        //
        // 限流必须**在读对象之前**。改前是「读完整份对象、拿到内容之后才计数」，
        // 于是超限请求照样把 HMAC 校验、一次 file_object 查询和一份 ≤20 MiB 的
        // 对象读全部做完（并在等待 Redis 的那 ≤1 秒里持有这块 byte[]），然后才 429。
        // 也就是说它只限制了"交付出去的字节"，没有限制后端开销与堆：单个 IP 可以
        // 用并发把对象存储、数据库和内存一起压住，而计数仍然接近 0。设计文档把这条
        // 端点标为"必须限流（否则被当免费图床）"，要挡的正是这部分代价。
        //
        // 仍然"签名通过才计数"：被签名校验挡掉的请求不占真实厂商的额度，也不会把
        // 合法调用方连坐限流——这一点由 requireToken 先跑保证，没有丢。
        return Mono.defer(() -> {
                    service.requireToken(fileId, expires, signature);
                    return rateLimiter.acquire(exchange)
                            .then(service.readAdmitted(fileId));
                })
                .<ResponseEntity<?>>map(content -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(content.mimeType()))
                        .contentLength(content.bytes().length)
                        .header("Cache-Control", "private, max-age=" + content.maxAgeSeconds())
                        // 响应体的类型来自我们自己的白名单，但仍然不允许浏览器
                        // 按内容重新猜一次类型：这张图会被展示在别人的页面上。
                        .header("X-Content-Type-Options", "nosniff")
                        .header("x-request-id", requestId)
                        .body(content.bytes()))
                .onErrorResume(error -> Mono.just(errorResponse(error, requestId)));
    }

    private String requestId(ServerWebExchange exchange) {
        return exchange.getAttributeOrDefault(
                ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE,
                "req_" + UUID.randomUUID().toString().replace("-", ""));
    }

    /**
     * 错误一律走与其它端点同一个 OpenAI 信封。
     *
     * <p>这里不把一切错误都压成 404：一个「对象存储暂时不可用」应当让厂商看到 503
     * 并重试，而不是让它以为这张图永久不存在。「不区分原因」只适用于
     * **准入类**的拒绝（不存在 / 签名错 / 已过期 / 不是图片），而那些都由
     * {@link FileContentService} 统一抛成同一个 404。
     *
     * <p><b>异常自带的响应头必须透传。</b>限流器把 {@code Retry-After} 挂在异常上
     * （见 {@link FileContentRateLimiter}），而这里若只造一个裸响应体，那个头就会丢掉
     * —— 厂商拿不到"等多久"，只会立刻重试并再次被拒，把 429 变成重试风暴。
     */
    private ResponseEntity<ChatCompletionController.OpenAiErrorResponse> errorResponse(
            Throwable error, String requestId) {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(error);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(descriptor.status())
                .header("x-request-id", requestId)
                .contentType(MediaType.APPLICATION_JSON);
        if (error instanceof ResponseStatusException status && status.getHeaders() != null) {
            status.getHeaders().forEach((name, values) -> {
                // 不覆盖我们自己的追踪头：它来自请求上下文，而不是异常。
                if (!"x-request-id".equalsIgnoreCase(name)) {
                    builder.header(name, values.toArray(new String[0]));
                }
            });
        }
        return builder.body(new ChatCompletionController.OpenAiErrorResponse(
                new ChatCompletionController.OpenAiError(
                        descriptor.message(), descriptor.type(), descriptor.code()),
                requestId));
    }
}

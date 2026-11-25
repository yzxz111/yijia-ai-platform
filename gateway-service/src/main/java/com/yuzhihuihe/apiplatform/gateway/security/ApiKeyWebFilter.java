package com.yuzhihuihe.apiplatform.gateway.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ApiKeyWebFilter implements WebFilter, Ordered {

    private static final int API_KEY_PREFIX_LENGTH = "sk-yzh-".length() + 8;
    private static final String SKILL_CATALOG_EVENTS_PATH = "/v1/skill-catalog/events";
    private static final Pattern PUBLIC_SKILL_PRESENTATION_PATH = Pattern.compile(
            "^/v1/skills/[^/]+/(?:expert-presentation|icon|illustration|example-image)$");
    private static final Pattern PUBLIC_NATIVE_CLAWHUB_ICON_PATH = Pattern.compile(
            "^/v1/native-clawhub/skills/[^/]+/icon$");

    /**
     * 公网匿名读图。
     *
     * <p>厂商（天翼）在做图生图时**自己去拉**参考图，它不会带我们的 API key，
     * 因此这条路径必须能在没有 {@code Authorization} 的情况下到达。
     * 准入判断不落在这一层：URL 里的签名才是这次读取的凭证，
     * 由 {@code FileContentService} 校验。
     */
    private static final Pattern PUBLIC_FILE_CONTENT_PATH =
            Pattern.compile("^/v1/files/[^/]+/content$");
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-fA-F]{32}");
    private static final String ZERO_TRACE_ID = "0".repeat(32);

    public static final String API_KEY_CONTEXT_ATTRIBUTE = ApiKeyService.ApiKeyContext.class.getName();
    public static final String REQUEST_ID_ATTRIBUTE = ApiKeyWebFilter.class.getName() + ".requestId";

    private final ApiKeyService apiKeyService;

    public ApiKeyWebFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/v1/")) {
            return chain.filter(exchange);
        }
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String secret = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).strip()
                : null;
        String requestId = requestId();
        exchange.getAttributes().put(REQUEST_ID_ATTRIBUTE, requestId);
        // Catalog, quota and anonymous reads need the same traceability as generation endpoints.
        exchange.getResponse().beforeCommit(() -> {
            if (!exchange.getResponse().getHeaders().containsKey("x-request-id")) {
                exchange.getResponse().getHeaders().set("x-request-id", requestId);
            }
            return Mono.empty();
        });
        if (isAnonymousSkillCatalogRequest(exchange) || isAnonymousFileContentRequest(exchange)) {
            return chain.filter(exchange);
        }
        if (isPlatformTokenSkillCatalogPath(path)
                && (secret == null || !secret.startsWith("sk-yzh-"))) {
            if (secret == null || secret.isBlank()) {
                return authenticationRequired(exchange, requestId);
            }
            // 非 API key 的 bearer token 是平台登录 JWT。保持它原样，
            // 好让 resource-server 过滤器认证它、并让技能目录控制器把它
            // 绑定到已登录的账号。
            return chain.filter(exchange);
        }
        String traceId = traceId(exchange, requestId);
        String apiKeyPrefix = apiKeyPrefix(secret);
        Mono<ApiKeyService.ApiKeyContext> authentication = apiKeyService.authenticate(secret)
                .onErrorMap(error -> new DependencyUnavailableException())
                .switchIfEmpty(Mono.error(new InvalidApiKeyException()));
        return authentication.flatMap(context -> {
                    ServerWebExchange authenticated = exchange.mutate()
                            .request(request -> request.headers(headers -> headers.remove(HttpHeaders.AUTHORIZATION)))
                            .build();
                    authenticated.getAttributes().put(API_KEY_CONTEXT_ATTRIBUTE, context);
                    return chain.filter(authenticated).contextWrite(loggingContext -> loggingContext
                            .put("traceId", traceId)
                            .put("apiKeyPrefix", apiKeyPrefix));
                })
                .onErrorResume(InvalidApiKeyException.class,
                        error -> unauthorized(exchange, requestId))
                .onErrorResume(DependencyUnavailableException.class,
                        error -> dependencyUnavailable(exchange, requestId));
    }

    private boolean isSkillsPath(String path) {
        return "/v1/skills".equals(path) || path.startsWith("/v1/skills/");
    }

    private boolean isPlatformTokenSkillCatalogPath(String path) {
        return isSkillsPath(path)
                || isNativeClawhubSkillsPath(path)
                || isCategoryPath(path)
                || SKILL_CATALOG_EVENTS_PATH.equals(path);
    }

    private static boolean isNativeClawhubSkillsPath(String path) {
        return "/v1/native-clawhub/skills".equals(path)
                || path.startsWith("/v1/native-clawhub/skills/");
    }

    /**
     * 无需凭证的公开读取。
     *
     * 技能目录列表、其精选场景卡片背后的类目词表，以及
     * 已发布的专家展示资产，是桌面端在任何人登录之前就渲染的
     * 店面。事件流只携带不透明的失效 id。匿名 GET 必须能到达
     * 这些只读投影，而不是因为缺少 API key 被拒绝。套餐详情、
     * 下载会话以及所有变更仍然受保护。
     */
    static boolean isAnonymousSkillCatalogRequest(ServerWebExchange exchange) {
        if (!HttpMethod.GET.equals(exchange.getRequest().getMethod())) {
            return false;
        }
        String path = exchange.getRequest().getPath().value();
        if (!"/v1/skills".equals(path)
                && !"/v1/native-clawhub/skills".equals(path)
                && !isCategoryPath(path)
                && !SKILL_CATALOG_EVENTS_PATH.equals(path)
                && !PUBLIC_SKILL_PRESENTATION_PATH.matcher(path).matches()
                && !PUBLIC_NATIVE_CLAWHUB_ICON_PATH.matcher(path).matches()) {
            return false;
        }
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return authorization == null || authorization.isBlank();
    }

    private static boolean isCategoryPath(String path) {
        return "/v1/skill-categories".equals(path) || path.startsWith("/v1/skill-categories/");
    }

    /**
     * 公网匿名读图的路径判定。
     *
     * <p>与技能展示资产那条不同，这里**不要求调用方不带 {@code Authorization}**：
     * 签名本身就是这次读取的凭证，多带一把 API key 不增加任何权限，少带也不减。
     * 让这条路径只认签名，是为了让「厂商怎么拉图」与「客户端怎么预览图」
     * 是同一条规则，而不是两条各自演化的规则。
     */
    static boolean isAnonymousFileContentRequest(ServerWebExchange exchange) {
        return HttpMethod.GET.equals(exchange.getRequest().getMethod())
                && PUBLIC_FILE_CONTENT_PATH.matcher(exchange.getRequest().getPath().value())
                        .matches();
    }

    private Mono<Void> authenticationRequired(ServerWebExchange exchange, String requestId) {
        if (exchange.getResponse().isCommitted()) {
            return exchange.getResponse().setComplete();
        }
        byte[] body = ("{\"error\":{\"message\":\"Authentication required\","
                + "\"type\":\"authentication_error\",\"code\":\"authentication_required\"},"
                + "\"request_id\":\"" + requestId + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set("x-request-id", requestId);
        return exchange.getResponse().writeWith(Mono.just(
                exchange.getResponse().bufferFactory().wrap(body)));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String requestId) {
        if (exchange.getResponse().isCommitted()) {
            return exchange.getResponse().setComplete();
        }
        byte[] body = ("{\"error\":{\"message\":\"Invalid API key\","
                + "\"type\":\"authentication_error\",\"code\":\"invalid_api_key\"},"
                + "\"request_id\":\"" + requestId + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set("x-request-id", requestId);
        return exchange.getResponse().writeWith(Mono.just(
                exchange.getResponse().bufferFactory().wrap(body)));
    }

    private Mono<Void> dependencyUnavailable(ServerWebExchange exchange, String requestId) {
        if (exchange.getResponse().isCommitted()) {
            return exchange.getResponse().setComplete();
        }
        byte[] body = ("{\"error\":{\"message\":\"Dependency unavailable\","
                + "\"type\":\"server_error\",\"code\":\"dependency_unavailable\"},"
                + "\"request_id\":\"" + requestId + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set("x-request-id", requestId);
        return exchange.getResponse().writeWith(Mono.just(
                exchange.getResponse().bufferFactory().wrap(body)));
    }

    private String requestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String traceId(ServerWebExchange exchange, String fallback) {
        String traceparent = exchange.getRequest().getHeaders().getFirst("traceparent");
        if (traceparent == null) {
            return fallback;
        }
        String[] parts = traceparent.split("-", -1);
        if (parts.length != 4 || !TRACE_ID.matcher(parts[1]).matches()
                || ZERO_TRACE_ID.equals(parts[1])) {
            return fallback;
        }
        return parts[1].toLowerCase(Locale.ROOT);
    }

    private String apiKeyPrefix(String secret) {
        if (secret == null || secret.isBlank()) {
            return "unavailable";
        }
        return secret.substring(0, Math.min(secret.length(), API_KEY_PREFIX_LENGTH));
    }

    private static final class InvalidApiKeyException extends RuntimeException {
    }

    private static final class DependencyUnavailableException extends RuntimeException {
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}

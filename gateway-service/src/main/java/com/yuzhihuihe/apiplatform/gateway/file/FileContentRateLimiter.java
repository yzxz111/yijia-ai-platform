package com.yuzhihuihe.apiplatform.gateway.file;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * 匿名读图端点的固定窗口限流器，按**来源 IP** 計数。
 *
 * <p><b>为什么需要它。</b>{@code GET /v1/files/{fileId}/content} 是公网匿名可达的，
 * 每次最多交付 20 MiB。没有限流就等于一个免费图床：一个泄露的签名地址在有效期内
 * 可被任意来源反复拉取。设计文档
 * {@code docs/operations/图生图方案B-网关中转设计.md:71} 把这条写成必做
 * （"必须限流（否则被当免费图床）…超限 429"），但此前只有文档、没有实现，
 * 也没有任何测试钉住它。
 *
 * <p><b>取 IP 的口径。</b>优先 {@code X-Real-IP}，其次 {@code X-Forwarded-For} 的
 * 第一段，最后回落到连接地址。这条链上游的 nginx 是**覆盖**而非追加这两个头
 * （{@code deploy/nginx/nginx.https.conf:95-97} 有明确注释："覆盖，绝不追加：该请求一旦
 * 来自私有代理地址，后端就会信任这个头，因此若追加 {@code $proxy_add_x_forwarded_for}，
 * 客户端就能把自己的条目插到真实地址之前"），因此取到的值是可信的。
 *
 * <p>取到的字符串一律经 {@link InetAddress} 归一化后再入 key：否则
 * {@code ::1} 与 {@code 0:0:0:0:0:0:0:1}、{@code 1.2.3.4} 与带端口的写法会各自
 * 占一个桶，等于把限额乘了几倍。**解析失败一律并入同一个 {@code unknown} 桶，
 * 绝不因为"解析不了"而放行。**
 *
 * <p><b>Redis 不可用时默认 fail closed</b>（503 {@code file_content_rate_limit_unavailable}）。
 * 与 {@code SkillDownloadRateLimiter} 同一口径：限流器不能降级成"全部放行"，
 * 否则一次 Redis 抖动就等于把免费图床的门打开。若运维判断"宁可多花带宽也不能让
 * 厂商拉不到图"，可用 {@code gateway.files.content-token.rate-limit-fail-closed=false}
 * 显式改成放行——但那是**明知的选择**，不是默认。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class FileContentRateLimiter {

    /** 稳定错误码：与 OpenAI 信封里的 429 对应（{@code OpenAiErrorMapper} 已登记）。 */
    static final String RATE_LIMITED = "rate_limited";

    /** Redis 不可用且 fail-closed 时的稳定错误码。 */
    static final String UNAVAILABLE = "file_content_rate_limit_unavailable";

    private static final String KEY_PREFIX = "gateway:file-content:";
    private static final String UNKNOWN_CALLER = "unknown";

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(FileContentRateLimiter.class);

    private static final RedisScript<Long> ACQUIRE = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return count
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final int limit;
    private final Duration window;
    private final boolean failClosed;

    /**
     * 生产构造器。
     *
     * <p><b>{@code @Autowired} 是必需的，不是装饰。</b>本类有两个构造器（这一个与
     * 下面那个包级可见、供测试直接注入的），Spring 在"多个构造器且无一被标注"时
     * 会退回去找**无参构造器**，于是启动直接失败：
     * {@code Failed to instantiate FileContentRateLimiter: No default constructor found}。
     *
     * <p>这个错误**只在完整应用上下文里出现**，直接 new 的单元测试永远看不到它——
     * 而它的后果是整个网关起不来，不只是限流不生效。本次就是靠
     * {@code GatewayServiceApplicationTests}（全上下文启动）才发现并修掉的。
     * {@code SkillDownloadRateLimiter} 用的是同一写法，保持一致性。
     */
    @Autowired
    public FileContentRateLimiter(
            org.springframework.beans.factory.ObjectProvider<ReactiveStringRedisTemplate> redisProvider,
            FileContentTokenProperties properties) {
        this(redisProvider.getIfAvailable(),
                properties.getRateLimitPerIp(),
                properties.getRateLimitWindow(),
                properties.isRateLimitFailClosed());
    }

    FileContentRateLimiter(
            ReactiveStringRedisTemplate redis, int limit, Duration window, boolean failClosed) {
        // **窗口下限是 1 毫秒，不只是"非零"。**
        //
        // 这个值被当成毫秒数交给 Redis 的 `PEXPIRE`（见 ACQUIRE 脚本：只在
        // count==1 时设置过期）。而 `PEXPIRE key 0` 的语义不是"立即过期"而是
        // **删除这个键**：于是每个请求都把键重建为 1，`count <= limit` 永远成立，
        // 限流器**静默地完全失效**——不报错、不打日志、全部 200。
        //
        // 任何 toMillis() == 0 的取值都会踩到它（500us、0.5ms、1ns…），而 Spring
        // 的 Duration 绑定接受这些写法。对一个"必须限流否则会被当免费图床"的端点
        // 来说，配错单位的正确表现是**拒绝启动**，不是悄悄放行。
        if (limit <= 0 || window == null || window.isZero() || window.isNegative()
                || window.toMillis() < 1) {
            throw new IllegalArgumentException("file_content_rate_limit_configuration_invalid");
        }
        this.redis = redis;
        this.limit = limit;
        this.window = window;
        this.failClosed = failClosed;
    }

    /**
     * 消耗调用方窗口内的一个名额。
     *
     * <p>只在**准入通过之后**才应调用（见控制器的顺序说明）：被签名校验挡掉的请求
     * 不应该占用真实厂商的额度，也不应该被用来把别人挤下线。
     */
    public Mono<Void> acquire(ServerWebExchange exchange) {
        String caller = callerKey(exchange);
        if (redis == null) {
            return failClosed ? Mono.error(unavailable()) : Mono.empty();
        }
        return redis.execute(
                        ACQUIRE,
                        List.of(KEY_PREFIX + caller),
                        List.of(Integer.toString(limit), Long.toString(window.toMillis())))
                .next()
                // switchIfEmpty 必须在 flatMap **之前**：这里的 Mono 是 Mono<Long>，
                // "空"只可能表示"Redis 没给出计数"。一旦 flatMap 成 Mono<Void>，
                // "放行"本身也是 empty，switchIfEmpty 就会把合法请求也判成故障
                // —— 这正是本类第一版踩到的坑（限流器对所有请求返回 503）。
                .switchIfEmpty(failClosed
                        ? Mono.error(unavailable())
                        : Mono.just(0L))
                .flatMap(count -> count <= limit
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(new RateLimitExceededException(window)))
                .doOnError(error -> {
                    if (!(error instanceof ResponseStatusException)) {
                        LOG.warn("file_content_rate_limit_failed caller={} error={}",
                                caller, error.getClass().getSimpleName());
                    }
                })
                .onErrorMap(error -> {
                    if (error instanceof ResponseStatusException) {
                        // 我们自己判定的结果（429 超限、503 不可用），原样向上。
                        return error;
                    }
                    if (failClosed) {
                        // 文档承诺的方向：Redis 不可用 -> 503 file_content_rate_limit_unavailable。
                        LOG.warn("file_content_rate_limit_unavailable caller={} error={}",
                                caller, error.getClass().getSimpleName());
                        return unavailable();
                    }
                    // **fail-open 必须真的放行。**
                    //
                    // 改前这里把原始异常直接抛出去（`return error`），而 Redis 故障
                    // 是以 RedisConnectionFailureException / 超时这类**非
                    // ResponseStatusException** 的形式到达的；它们穿过控制器后被
                    // 全局兜底映射成 **500 internal_error**。也就是说：
                    // 运维为了"Redis 抖动时别让厂商拉不到图"而把开关设成 false，
                    // 实际得到的是 500 —— 厂商看到的是"网关坏了"，不是"暂时不可用"，
                    // 而文档（本类、FileContentTokenProperties、compose 注释）都写着
                    // 会放行。只有"空回复"那一支真的放行过，而 INCR 基本不会产生空回复。
                    //
                    // 因此这里显式吞掉故障：记一条 WARN 作为痕迹，然后放行本次请求。
                    LOG.warn("file_content_rate_limit_failed_open caller={} error={}",
                            caller, error.getClass().getSimpleName());
                    return null;
                });
    }

    /**
     * 来源 IP 的稳定归一化 key。
     *
     * <p>包级可见以便直接测试：这条链最容易错的就是"同一个调用方被算成两个 key"。
     */
    static String callerKey(ServerWebExchange exchange) {
        return normalize(firstNonBlank(
                exchange.getRequest().getHeaders().getFirst("X-Real-IP"),
                firstForwardedFor(exchange),
                connectionAddress(exchange)));
    }

    private static String firstForwardedFor(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (header == null || header.isBlank()) {
            return null;
        }
        // 我们的 nginx 覆盖成一个裸地址；但若某天上游改成追加，这里只取第一段，
        // 与 nginx 那条注释的假设保持一致（第一段是它写入的真实地址）。
        int comma = header.indexOf(',');
        return (comma < 0 ? header : header.substring(0, comma)).trim();
    }

    private static String connectionAddress(ServerWebExchange exchange) {
        InetSocketAddress address = exchange.getRequest().getRemoteAddress();
        if (address == null || address.getAddress() == null) {
            return null;
        }
        return address.getAddress().getHostAddress();
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    /**
     * 归一化成稳定的桶名；解析失败并入 {@link #UNKNOWN_CALLER}。
     *
     * <p>刻意不把原始字符串直接当 key：`::1` 与 `0:0:0:0:0:0:0:1` 是同一个地址，
     * 但字符串不同；带方括号或端口的写法同理。不归一化就等于把限额乘了几倍。
     */
    static String normalize(String caller) {
        if (caller == null || caller.isBlank()) {
            return UNKNOWN_CALLER;
        }
        String value = caller.trim();
        // 去掉 IPv6 字面量的方括号与可能的端口，形如 [::1]:54321
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close > 0) {
                value = value.substring(1, close);
            }
        } else {
            int colon = value.lastIndexOf(':');
            // 只有一个冒号才是 host:port；IPv6 有多个冒号，不能按端口切
            if (colon > 0 && value.indexOf(':') == colon) {
                value = value.substring(0, colon);
            }
        }
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (RuntimeException | java.net.UnknownHostException unresolvable) {
            // 解析失败不报错也不放行，而是共同落进一个桶——攻击者无法靠畸形值绕过限额。
            return UNKNOWN_CALLER;
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
    }

    /** 429，并带 {@code Retry-After}，让厂商知道该等多久而不是立刻重试。 */
    static final class RateLimitExceededException extends ResponseStatusException {

        private final HttpHeaders headers = new HttpHeaders();

        private RateLimitExceededException(Duration window) {
            super(HttpStatus.TOO_MANY_REQUESTS, RATE_LIMITED);
            long retryAfterSeconds = window.getSeconds();
            if (window.getNano() > 0 && retryAfterSeconds < Long.MAX_VALUE) {
                retryAfterSeconds++;
            }
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, retryAfterSeconds)));
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }
}

package com.yuzhihuihe.apiplatform.platform.internalapi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 对网关内部 API 的一次签名 GET 调用，由各 console 读取方共享。
 *
 * <p>平台会读取若干由网关拥有的数值（套餐的额度用量、平台已垫付的结算缺口）。
 * 每次读取都必须呈现相同的 {@code X-Internal-*} 信封，
 * 而网关仅对<em>路径</em>验证签名：若客户端把查询字符串并入规范字符串，
 * 则每一个携带参数的请求都会被拒绝。
 * 把信封收敛到一处意味着各种失败形态——密钥未配置、
 * 不可达、超时、非 200、响应体过大——也以相同方式失败：通过抛出
 * {@link GatewayInternalUnavailableException} 而不是返回某个调用方可能误当作数据
 * 的东西。3 秒连接超时与 5 秒单请求超时是有意为之：一个 console
 * 请求绝不能卡在一个僵死的网关上，而一个缓慢的答复比不上一个快速、
 * 诚实的 503。
 */
@Component
public class GatewayInternalGet {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAXIMUM_RESPONSE_BYTES = 1_048_576;

    private final HttpClient http;
    private final URI gatewayBaseUri;
    private final InternalRequestSigner signer;
    private final Clock clock;
    private final Supplier<UUID> requestIds;
    private final Duration readTimeout;
    private final boolean signingConfigured;

    @Autowired
    public GatewayInternalGet(
            @Value("${gateway.internal.base-url:http://gateway-service:8081}") URI gatewayBaseUri,
            @Value("${security.internal.hmac-secret:}") String hmacSecret) {
        this(HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                gatewayBaseUri, hmacSecret, Clock.systemUTC(), UUID::randomUUID, READ_TIMEOUT);
    }

    /**
     * 对测试可见：测试会把客户端指向本地桩服务、冻结签名时钟并限制读超时，
     * 使挂起的桩服务让断言失败，而不是让构建失败。
     */
    public GatewayInternalGet(
            HttpClient http,
            URI gatewayBaseUri,
            String hmacSecret,
            Clock clock,
            Supplier<UUID> requestIds,
            Duration readTimeout) {
        this.http = Objects.requireNonNull(http, "http");
        this.gatewayBaseUri = Objects.requireNonNull(gatewayBaseUri, "gatewayBaseUri");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
        this.readTimeout = positive(readTimeout, "readTimeout");
        String secret = Objects.requireNonNull(hmacSecret, "hmacSecret");
        this.signer = new InternalRequestSigner(secret, clock);
        this.signingConfigured = !secret.isBlank();
    }

    /**
     * @param path 要签名的网关路径，与网关所见完全一致；查询字符串被
     *             有意排除，因为那正是网关所校验的内容
     * @param query 已编码的查询字符串；裸路径时为 {@code null}
     * @return 原始响应体，保证非空且不超过大小上限
     * @throws GatewayInternalUnavailableException 只要未收到可信的响应体就抛出
     */
    public byte[] get(String path, String query) {
        Objects.requireNonNull(path, "path");
        if (!signingConfigured) {
            throw new GatewayInternalUnavailableException(
                    "security.internal.hmac-secret must be configured");
        }
        String target = query == null || query.isBlank() ? path : path + "?" + query;
        UUID requestId = Objects.requireNonNull(requestIds.get(), "requestId");
        InternalRequestSigner.SignedHeaders signed = signer.sign(
                clock.instant().getEpochSecond(), requestId, "GET", path, new byte[0]);
        HttpRequest request = HttpRequest.newBuilder(gatewayBaseUri.resolve(target))
                .timeout(readTimeout)
                .header("X-Internal-Timestamp", signed.timestamp())
                .header("X-Internal-Request-Id", signed.requestId())
                .header("X-Internal-Content-Sha256", signed.contentSha256())
                .header("X-Internal-Signature", signed.signature())
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GatewayInternalUnavailableException(
                    "gateway_internal_request_interrupted", exception);
        } catch (Exception exception) {
            throw new GatewayInternalUnavailableException("gateway_internal_request_failed", exception);
        }
        if (response.statusCode() != 200) {
            throw new GatewayInternalUnavailableException(
                    "gateway_internal_http_status:" + response.statusCode());
        }
        byte[] body = response.body();
        if (body == null || body.length == 0 || body.length > MAXIMUM_RESPONSE_BYTES) {
            throw new GatewayInternalUnavailableException("gateway_internal_response_body_invalid");
        }
        return body;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}

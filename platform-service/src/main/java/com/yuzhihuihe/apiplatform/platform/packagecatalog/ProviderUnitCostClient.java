package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 读取网关价目表中视频别名的每秒成本基准。
 *
 * <p>视频别名刻意不携带 {@code gateway.provider_model_price} 行，
 * 因为这些列是按每百万 token 计的，无法表达每秒成本而不失真。
 * 因此套餐发布毛利率守卫需要这
 * 第二个权威来源：以分表示的实际每秒提供方成本，
 * 且已包含对上游「失败也计费」的生成所预留的重试余量。
 *
 * <p>这与 {@link ProviderPriceClient} 相对应，后者服务于 token 维度。
 */
@Component
public class ProviderUnitCostClient {

    private static final String COST_PATH = "/internal/prices/effective-unit-cost";

    private final java.net.http.HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI gatewayBaseUri;
    private final InternalRequestSigner requestSigner;
    private final boolean signingConfigured;

    public ProviderUnitCostClient(
            ObjectMapper objectMapper,
            @Value("${gateway.internal.base-url:http://gateway-service:8081}") URI gatewayBaseUri,
            @Value("${security.internal.hmac-secret:}") String hmacSecret) {
        this.httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build();
        this.objectMapper = objectMapper;
        this.gatewayBaseUri = gatewayBaseUri;
        this.requestSigner = new InternalRequestSigner(hmacSecret);
        this.signingConfigured = !hmacSecret.isEmpty();
    }

    /**
     * @return 按模型给出的实际单位成本（分），仅针对网关已知其
     *         每秒价格的模型。结果中不存在的模型要么是 token 维度，要么
     *         没有定价，其含义由调用方决定。
     */
    public Map<String, BigDecimal> effectiveUnitCosts(Set<String> modelCodes) {
        if (modelCodes == null || modelCodes.isEmpty()) {
            return Map.of();
        }
        if (!signingConfigured) {
            throw new IllegalStateException("security.internal.hmac-secret must be configured");
        }
        String query = modelCodes.stream().sorted()
                .map(code -> URLEncoder.encode(code, StandardCharsets.UTF_8))
                .collect(Collectors.joining(","));
        URI uri = gatewayBaseUri.resolve(COST_PATH + "?models=" + query);
        InternalRequestSigner.SignedHeaders signed = requestSigner.sign(
                Instant.now().getEpochSecond(), UUID.randomUUID(), "GET", COST_PATH, new byte[0]);
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .header("X-Internal-Timestamp", signed.timestamp())
                .header("X-Internal-Request-Id", signed.requestId())
                .header("X-Internal-Content-Sha256", signed.contentSha256())
                .header("X-Internal-Signature", signed.signature())
                .GET()
                .build();
        try {
            java.net.http.HttpResponse<String> response =
                    httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "gateway_unit_cost_snapshot_failed:" + response.statusCode());
            }
            return objectMapper.readValue(
                    response.body(), new TypeReference<Map<String, BigDecimal>>() { });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("gateway_unit_cost_snapshot_interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("gateway_unit_cost_snapshot_failed", exception);
        }
    }
}

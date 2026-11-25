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
 * 读取网关按别名实际会收取的零售价。
 *
 * <p>这是发布守卫缺失的那一半。守卫按提供方
 * <em>成本</em>为版本定价，但向客户收费依据的是网关的
 * {@code model_billing_price} 表，该表通过另一个管理员端点写入，
 * 可能与守卫使用的成本快照不一致。当两者不一致时，版本会以
 * 永远无法收足的毛利率发布：这些别名以低于成本的价格售出，而
 * 守卫却报告 30-40% 的健康水平。
 *
 * <p>在发布时读取实际收费价格，把这种无声的分歧变成一次拒绝，
 * 该拒绝会指出别名以及两个数值。
 */
@Component
public class ProviderRetailPriceClient {

    private static final String RETAIL_PATH = "/internal/prices/retail-price";

    private final java.net.http.HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI gatewayBaseUri;
    private final InternalRequestSigner requestSigner;
    private final boolean signingConfigured;

    public ProviderRetailPriceClient(
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
     * @return 当前收取的每百万 token 零售价（分），以模型代码为键。结果中
     *         不存在的别名说明它根本没有当前零售价行，调用方
     *         必须将其视为「该别名无法计费」而不是「免费」。
     */
    public Map<String, RetailPrice> currentRetailPrices(Set<String> modelCodes) {
        if (modelCodes == null || modelCodes.isEmpty()) {
            return Map.of();
        }
        if (!signingConfigured) {
            throw new IllegalStateException("security.internal.hmac-secret must be configured");
        }
        String query = modelCodes.stream().sorted()
                .map(code -> URLEncoder.encode(code, StandardCharsets.UTF_8))
                .collect(Collectors.joining(","));
        URI uri = gatewayBaseUri.resolve(RETAIL_PATH + "?models=" + query);
        InternalRequestSigner.SignedHeaders signed = requestSigner.sign(
                Instant.now().getEpochSecond(), UUID.randomUUID(), "GET", RETAIL_PATH, new byte[0]);
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
                        "gateway_retail_price_failed:" + response.statusCode());
            }
            Map<String, RetailPrice> parsed = objectMapper.readValue(
                    response.body(), new TypeReference<Map<String, RetailPrice>>() { });
            return parsed == null ? Map.of() : parsed;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("gateway_retail_price_interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("gateway_retail_price_failed", exception);
        }
    }

    /**
     * @param inputPricePerMillion 网关收取的每百万输入 token 零售价（分）
     * @param outputPricePerMillion 网关收取的每百万输出 token 零售价（分）
     */
    public record RetailPrice(long inputPricePerMillion, long outputPricePerMillion) {

        /** 容忍 JSON 对象带有额外字段，或缺少其中一个配对字段。 */
        public RetailPrice {
            if (inputPricePerMillion < 0 || outputPricePerMillion < 0) {
                throw new IllegalArgumentException("invalid_retail_price");
            }
        }

        public BigDecimal outputAsDecimal() {
            return BigDecimal.valueOf(outputPricePerMillion);
        }
    }
}

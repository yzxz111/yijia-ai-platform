package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

@Component
public class ProviderPriceClient {

    private static final String PRICE_PATH = "/internal/prices/non-promotional-output";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI gatewayBaseUri;
    private final InternalRequestSigner requestSigner;
    private final boolean signingConfigured;

    public ProviderPriceClient(
            ObjectMapper objectMapper,
            @Value("${gateway.internal.base-url:http://gateway-service:8081}") URI gatewayBaseUri,
            @Value("${security.internal.hmac-secret:}") String hmacSecret) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.objectMapper = objectMapper;
        this.gatewayBaseUri = gatewayBaseUri;
        this.requestSigner = new InternalRequestSigner(hmacSecret);
        this.signingConfigured = !hmacSecret.isEmpty();
    }

    public Map<String, BigDecimal> nonPromotionalOutputPrices(Set<String> modelCodes) {
        if (modelCodes == null || modelCodes.isEmpty()) {
            return Map.of();
        }
        if (!signingConfigured) {
            throw new IllegalStateException("security.internal.hmac-secret must be configured");
        }
        String query = modelCodes.stream().sorted()
                .map(code -> URLEncoder.encode(code, StandardCharsets.UTF_8))
                .collect(Collectors.joining(","));
        URI uri = gatewayBaseUri.resolve(PRICE_PATH + "?models=" + query);
        InternalRequestSigner.SignedHeaders signed = requestSigner.sign(
                Instant.now().getEpochSecond(), UUID.randomUUID(), "GET", PRICE_PATH, new byte[0]);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .header("X-Internal-Timestamp", signed.timestamp())
                .header("X-Internal-Request-Id", signed.requestId())
                .header("X-Internal-Content-Sha256", signed.contentSha256())
                .header("X-Internal-Signature", signed.signature())
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("gateway_price_snapshot_failed:" + response.statusCode());
            }
            return objectMapper.readValue(response.body(), new TypeReference<Map<String, BigDecimal>>() { });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("gateway_price_snapshot_interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("gateway_price_snapshot_failed", exception);
        }
    }

}

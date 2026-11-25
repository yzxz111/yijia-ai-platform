package com.yuzhihuihe.apiplatform.platform.reporting;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.security.InternalRequestSigner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProviderPriceSnapshotClient {
    static final String PATH = "/internal/provider-prices/snapshot";
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI gatewayBaseUri;
    private final InternalRequestSigner signer;
    private final boolean configured;

    @Autowired
    public ProviderPriceSnapshotClient(ObjectMapper mapper,
            @Value("${gateway.internal.base-url:http://gateway-service:8081}") URI gatewayBaseUri,
            @Value("${security.internal.hmac-secret:}") String secret) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), mapper,
                gatewayBaseUri, new InternalRequestSigner(secret), !secret.isBlank());
    }

    ProviderPriceSnapshotClient(HttpClient http, ObjectMapper mapper, URI gatewayBaseUri,
            InternalRequestSigner signer, boolean configured) {
        this.http = http; this.mapper = mapper; this.gatewayBaseUri = gatewayBaseUri;
        this.signer = signer; this.configured = configured;
    }

    public List<PriceSnapshot> fetch() {
        if (!configured) throw new IllegalStateException("security.internal.hmac-secret must be configured");
        UUID requestId = UUID.randomUUID();
        InternalRequestSigner.SignedHeaders signed = signer.sign(
                Instant.now().getEpochSecond(), requestId, "GET", PATH, new byte[0]);
        HttpRequest request = HttpRequest.newBuilder(gatewayBaseUri.resolve(PATH))
                .timeout(Duration.ofSeconds(5))
                .header("X-Internal-Timestamp", signed.timestamp())
                .header("X-Internal-Request-Id", signed.requestId())
                .header("X-Internal-Content-Sha256", signed.contentSha256())
                .header("X-Internal-Signature", signed.signature()).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("gateway_price_snapshot_failed:" + response.statusCode());
            return mapper.readValue(response.body(), new TypeReference<List<PriceSnapshot>>() { });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("gateway_price_snapshot_interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("gateway_price_snapshot_failed", exception);
        }
    }

    public record PriceSnapshot(UUID id, String provider, String logicalModel, String upstreamModel,
            BigDecimal inputPrice, BigDecimal outputPrice,
            BigDecimal cacheReadPrice, BigDecimal cacheWritePrice, BigDecimal reasoningPrice,
            String currency, BigDecimal exchangeRate,
            String priceSource, Instant effectiveFrom, Instant effectiveTo) {
        @com.fasterxml.jackson.annotation.JsonCreator
        public static PriceSnapshot fromJson(
                @com.fasterxml.jackson.annotation.JsonProperty("id") UUID id,
                @com.fasterxml.jackson.annotation.JsonProperty("providerCode") String provider,
                @com.fasterxml.jackson.annotation.JsonProperty("modelCode") String model,
                @com.fasterxml.jackson.annotation.JsonProperty("inputPricePerMillion") BigDecimal input,
                @com.fasterxml.jackson.annotation.JsonProperty("outputPricePerMillion") BigDecimal output,
                @com.fasterxml.jackson.annotation.JsonProperty("cacheReadPricePerMillion") BigDecimal cacheRead,
                @com.fasterxml.jackson.annotation.JsonProperty("cacheWritePricePerMillion") BigDecimal cacheWrite,
                @com.fasterxml.jackson.annotation.JsonProperty("reasoningPricePerMillion") BigDecimal reasoning,
                @com.fasterxml.jackson.annotation.JsonProperty("currency") String currency,
                @com.fasterxml.jackson.annotation.JsonProperty("internalExchangeRate") BigDecimal exchangeRate,
                @com.fasterxml.jackson.annotation.JsonProperty("officialSource") String source,
                @com.fasterxml.jackson.annotation.JsonProperty("effectiveFrom") Instant from,
                @com.fasterxml.jackson.annotation.JsonProperty("effectiveTo") Instant to) {
            return new PriceSnapshot(id, provider, model, model, input, output,
                    cacheRead, cacheWrite, reasoning, currency, exchangeRate, source, from, to);
        }
    }
}

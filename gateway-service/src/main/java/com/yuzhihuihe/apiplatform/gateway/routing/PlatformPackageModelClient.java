package com.yuzhihuihe.apiplatform.gateway.routing;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class PlatformPackageModelClient {

    private final WebClient client;
    private final Duration timeout;

    public PlatformPackageModelClient(
            WebClient.Builder builder,
            @Value("${platform.package-catalog.internal-base-url:http://platform-service:8080}")
                    String baseUrl,
            @Value("${platform.package-catalog.timeout:3s}") Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("package_catalog_base_url_required");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("package_catalog_timeout_invalid");
        }
        this.client = builder.baseUrl(baseUrl).build();
        this.timeout = timeout;
    }

    public Mono<ModelReferenceView> references(String modelCode, String accessToken) {
        if (modelCode == null || modelCode.isBlank()
                || accessToken == null || accessToken.isBlank()) {
            return Mono.error(unavailable());
        }
        return client
                .get()
                .uri(uri -> uri
                        .path("/console/platform/admin/package-model-references")
                        .pathSegment(modelCode)
                        .build())
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(ModelReferenceView.class)
                .filter(view -> modelCode.equals(view.modelCode()) && view.referenceCount() >= 0)
                .switchIfEmpty(Mono.error(unavailable()))
                .timeout(timeout)
                .onErrorMap(error -> !(error instanceof ResponseStatusException), error -> unavailable());
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "package_reference_check_unavailable");
    }

    public record ModelReferenceView(
            String modelCode, long referenceCount, List<PackageModelReference> references) {
    }

    public record PackageModelReference(
            String planCode, String planName, int version, String versionStatus) {
    }
}

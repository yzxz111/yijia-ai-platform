package com.yuzhihuihe.apiplatform.gateway.safety;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class SemanticSafetyProviderRegistry {

    private final Map<String, SemanticSafetyProvider> providers;
    private final String configuredProvider;

    public SemanticSafetyProviderRegistry(
            @Qualifier("mockSemanticSafetyProvider") SemanticSafetyProvider mock,
            @Qualifier("httpSemanticSafetyProvider") SemanticSafetyProvider http,
            ContentSafetyProperties properties,
            Environment environment) {
        this.providers = Map.of("MOCK", mock, "HTTP", http);
        this.configuredProvider = validate(properties, environment);
    }

    public SemanticSafetyProvider current() {
        return providers.get(configuredProvider);
    }

    private String validate(ContentSafetyProperties properties, Environment environment) {
        String provider = properties.getSemantic().getProvider();
        String normalized = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        if (!providers.containsKey(normalized)) {
            throw new IllegalStateException("unsupported_content_safety_semantic_provider");
        }
        if ("MOCK".equals(normalized)) {
            if (!properties.getSemantic().isAllowMock() || !localOrTest(environment)) {
                throw new IllegalStateException("content_safety_mock_provider_not_allowed");
            }
            return normalized;
        }
        if (properties.getSemanticCredential() == null
                || properties.getSemanticCredential().isBlank()) {
            throw new IllegalStateException("content_safety_semantic_credential_required");
        }
        URI endpoint;
        try {
            endpoint = URI.create(properties.getSemantic().getBaseUrl());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("invalid_content_safety_semantic_base_url", exception);
        }
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null) {
            throw new IllegalStateException("content_safety_semantic_https_required");
        }
        return normalized;
    }

    private boolean localOrTest(Environment environment) {
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles.length > 0 && Arrays.stream(activeProfiles)
                .allMatch(profile -> "local".equals(profile) || "test".equals(profile));
    }
}

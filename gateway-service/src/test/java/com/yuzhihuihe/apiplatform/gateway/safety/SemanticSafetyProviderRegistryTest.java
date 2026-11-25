package com.yuzhihuihe.apiplatform.gateway.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SemanticSafetyProviderRegistryTest {

    private final SemanticSafetyProvider mockProvider = mock(SemanticSafetyProvider.class);
    private final SemanticSafetyProvider httpProvider = mock(SemanticSafetyProvider.class);

    @Test
    void rejectsUnknownProviderInsteadOfFallingBackToMock() {
        ContentSafetyProperties properties = properties("unexpected", true, "http://wiremock:8080", "local");

        assertThatThrownBy(() -> registry(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("unsupported_content_safety_semantic_provider");
    }

    @Test
    void rejectsMockUnlessItIsExplicitlyAllowed() {
        ContentSafetyProperties properties = properties("MOCK", false, "http://wiremock:8080", "local");

        assertThatThrownBy(() -> registry(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("content_safety_mock_provider_not_allowed");
    }

    @Test
    void acceptsMockWhenExplicitlyAllowed() {
        ContentSafetyProperties properties = properties("mock", true, "http://wiremock:8080", "local");

        assertThat(registry(properties, "local").current()).isSameAs(mockProvider);
    }

    @Test
    void rejectsMockOptInOutsideLocalOrTestProfiles() {
        ContentSafetyProperties properties = properties("MOCK", true, "http://wiremock:8080", "local");

        assertThatThrownBy(() -> registry(properties, "production"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("content_safety_mock_provider_not_allowed");
    }

    @Test
    void rejectsHttpProviderWithoutCredential() {
        ContentSafetyProperties properties = properties("HTTP", false, "https://safety.example.test", " ");

        assertThatThrownBy(() -> registry(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("content_safety_semantic_credential_required");
    }

    @Test
    void rejectsNonHttpsHttpProviderEndpoint() {
        ContentSafetyProperties properties = properties("HTTP", false, "http://safety.example.test", "secret");

        assertThatThrownBy(() -> registry(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("content_safety_semantic_https_required");
    }

    @Test
    void selectsConfiguredHttpsProvider() {
        ContentSafetyProperties properties = properties("http", false, "https://safety.example.test", "secret");

        assertThat(registry(properties).current()).isSameAs(httpProvider);
    }

    private SemanticSafetyProviderRegistry registry(ContentSafetyProperties properties) {
        return registry(properties, "test");
    }

    private SemanticSafetyProviderRegistry registry(
            ContentSafetyProperties properties, String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        return new SemanticSafetyProviderRegistry(
                mockProvider, httpProvider, properties, environment);
    }

    private ContentSafetyProperties properties(
            String provider, boolean allowMock, String baseUrl, String credential) {
        ContentSafetyProperties properties = new ContentSafetyProperties();
        properties.getSemantic().setProvider(provider);
        properties.getSemantic().setAllowMock(allowMock);
        properties.getSemantic().setBaseUrl(baseUrl);
        properties.setSemanticCredential(credential);
        return properties;
    }
}

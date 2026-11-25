package com.yuzhihuihe.apiplatform.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

class ConsoleTokenVersionWebFilterTest {

    @Test
    void allowsTheAnonymousSkillCatalogList() {
        ConsoleTokenValidator validator = mock(ConsoleTokenValidator.class);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/skills").build());
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        new ConsoleTokenVersionWebFilter(validator).filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verifyNoInteractions(validator);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void allowsAnonymousPublishedExpertPresentation() {
        ConsoleTokenValidator validator = mock(ConsoleTokenValidator.class);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(
                        "/v1/skills/content-writing-expert/expert-presentation").build());
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        new ConsoleTokenVersionWebFilter(validator).filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verifyNoInteractions(validator);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void keepsNestedSkillRoutesProtected() {
        ConsoleTokenValidator validator = mock(ConsoleTokenValidator.class);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/skills/article-writer").build());

        new ConsoleTokenVersionWebFilter(validator).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(validator, chain);
    }

    @Test
    void keepsNonGetRequestsToTheCatalogRootProtected() {
        ConsoleTokenValidator validator = mock(ConsoleTokenValidator.class);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/skills").build());

        new ConsoleTokenVersionWebFilter(validator).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(validator, chain);
    }

    @Test
    void allowsAnonymousNativeClawhubCatalogList() {
        ConsoleTokenValidator validator = mock(ConsoleTokenValidator.class);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/native-clawhub/skills").build());
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        new ConsoleTokenVersionWebFilter(validator).filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verifyNoInteractions(validator);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void requiresAnIdentityForNativeClawhubPackageDownload() {
        ConsoleTokenValidator validator = mock(ConsoleTokenValidator.class);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(
                        "/v1/native-clawhub/skills/owner-skill-123456789abc/package").build());

        new ConsoleTokenVersionWebFilter(validator).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(validator, chain);
    }

    @Test
    void validatesPlatformJwtOnNativeClawhubCatalog() {
        ConsoleTokenValidator validator = mock(ConsoleTokenValidator.class);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/native-clawhub/skills")
                        .header("Authorization", "Bearer platform-jwt")
                        .build());
        when(validator.validate("platform-jwt")).thenReturn(Mono.just(true));
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        new ConsoleTokenVersionWebFilter(validator).filter(exchange, chain).block();

        verify(validator).validate("platform-jwt");
        verify(chain).filter(exchange);
    }

    @Test
    void acceptsAnAlreadyAuthenticatedApiKeyForNativeClawhubDownloads() {
        ConsoleTokenValidator validator = mock(ConsoleTokenValidator.class);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(
                        "/v1/native-clawhub/skills/owner-skill-123456789abc/package").build());
        exchange.getAttributes().put(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE, new Object());
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        new ConsoleTokenVersionWebFilter(validator).filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verifyNoInteractions(validator);
    }
}

package com.yuzhihuihe.apiplatform.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

class ApiKeyWebFilterCatalogEventsTest {

    @Test
    void allowsAnonymousCatalogEventStreamsBecauseTheyContainNoCatalogData() {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/skill-catalog/events").build());
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        assertThat(ApiKeyWebFilter.isAnonymousSkillCatalogRequest(exchange)).isTrue();
        new ApiKeyWebFilter(apiKeyService).filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verifyNoInteractions(apiKeyService);
    }

    @Test
    void allowsAnonymousPublishedExpertPresentationAndArtwork() {
        for (String path : new String[] {
                "/v1/skills/content-writing-expert/expert-presentation",
                "/v1/skills/content-writing-expert/icon",
                "/v1/skills/content-writing-expert/illustration",
                "/v1/skills/content-writing-expert/example-image"
        }) {
            ApiKeyService apiKeyService = mock(ApiKeyService.class);
            WebFilterChain chain = mock(WebFilterChain.class);
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(path).build());
            when(chain.filter(exchange)).thenReturn(Mono.empty());

            assertThat(ApiKeyWebFilter.isAnonymousSkillCatalogRequest(exchange)).isTrue();
            new ApiKeyWebFilter(apiKeyService).filter(exchange, chain).block();

            verify(chain).filter(exchange);
            verifyNoInteractions(apiKeyService);
        }
    }

    @Test
    void keepsPackageDetailAndDownloadRoutesProtected() {
        for (String path : new String[] {
                "/v1/skills/content-writing-expert",
                "/v1/skills/content-writing-expert/downloads"
        }) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(path).build());
            assertThat(ApiKeyWebFilter.isAnonymousSkillCatalogRequest(exchange)).isFalse();
        }
    }

    @Test
    void allowsAnonymousNativeClawhubListingAndIconsButKeepsPackagesProtected() {
        MockServerWebExchange catalog = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/native-clawhub/skills").build());
        assertThat(ApiKeyWebFilter.isAnonymousSkillCatalogRequest(catalog)).isTrue();

        MockServerWebExchange icon = MockServerWebExchange.from(
                MockServerHttpRequest.get(
                        "/v1/native-clawhub/skills/owner-skill-123456789abc/icon").build());
        assertThat(ApiKeyWebFilter.isAnonymousSkillCatalogRequest(icon)).isTrue();

        MockServerWebExchange packageDownload = MockServerWebExchange.from(
                MockServerHttpRequest.get(
                        "/v1/native-clawhub/skills/owner-skill-123456789abc/package").build());
        assertThat(ApiKeyWebFilter.isAnonymousSkillCatalogRequest(packageDownload)).isFalse();
    }

    @Test
    void servesAnonymousNativeClawhubIconWithoutApiKeyLookup() {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(
                        "/v1/native-clawhub/skills/owner-skill-123456789abc/icon").build());
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        new ApiKeyWebFilter(apiKeyService).filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verifyNoInteractions(apiKeyService);
    }

    @Test
    void servesAnonymousNativeClawhubCatalogWithoutApiKeyLookup() {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/native-clawhub/skills").build());
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        new ApiKeyWebFilter(apiKeyService).filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verifyNoInteractions(apiKeyService);
    }

    @Test
    void passesPlatformJwtToResourceServerForNativeClawhubReads() {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/native-clawhub/skills")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer platform-jwt")
                        .build());
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        new ApiKeyWebFilter(apiKeyService).filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verifyNoInteractions(apiKeyService);
    }

    @Test
    void passesPlatformTokensThroughForCatalogEventsDuringAuthenticatedFallback() {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/skill-catalog/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer platform-jwt")
                        .build());
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        new ApiKeyWebFilter(apiKeyService).filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verifyNoInteractions(apiKeyService);
    }

    @Test
    void passesPlatformTokensThroughForTheCategoryVocabulary() {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/skill-categories?kind=EXPERT")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer platform-jwt")
                        .build());
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        new ApiKeyWebFilter(apiKeyService).filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verifyNoInteractions(apiKeyService);
    }

    @Test
    void allowsAnonymousImageContentReadsBecauseTheSignatureIsTheCredential() {
        // 图生图时去拉参考图的是厂商（天翼），它不会带我们的 API key，
        // 因此这条路径必须能在没有 Authorization 的情况下到达控制器。
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(
                        "/v1/files/file_0123456789abcdef0123456789abcdef/content?exp=1&sig=x")
                        .build());
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        assertThat(ApiKeyWebFilter.isAnonymousFileContentRequest(exchange)).isTrue();
        new ApiKeyWebFilter(apiKeyService).filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verifyNoInteractions(apiKeyService);
    }

    @Test
    void keepsTheRestOfTheFileApiBehindAnApiKey() {
        // 匿名只开了一条缝：文件上传、列表、详情、上传会话与任何写入都仍然要 API key。
        for (String path : new String[] {
                "/v1/files",
                "/v1/files/file_0123456789abcdef0123456789abcdef",
                "/v1/files/file_0123456789abcdef0123456789abcdef/content/extra",
                "/v1/files/file_0123456789abcdef0123456789abcdef/contentX",
                "/v1/files/upload-sessions"
        }) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(path).build());
            assertThat(ApiKeyWebFilter.isAnonymousFileContentRequest(exchange))
                    .as("路径 %s 必须仍然要求 API key", path)
                    .isFalse();
        }
        MockServerWebExchange write = MockServerWebExchange.from(
                MockServerHttpRequest.post(
                        "/v1/files/file_0123456789abcdef0123456789abcdef/content").build());
        assertThat(ApiKeyWebFilter.isAnonymousFileContentRequest(write))
                .as("只有 GET 走匿名")
                .isFalse();
    }

    @Test
    void stillRejectsAnInvalidApiKeyOnTheEventStream() {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/skill-catalog/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer sk-yzh-invalid")
                        .build());
        when(apiKeyService.authenticate("sk-yzh-invalid")).thenReturn(Mono.empty());

        new ApiKeyWebFilter(apiKeyService).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(
                org.springframework.http.HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(exchange);
    }
}

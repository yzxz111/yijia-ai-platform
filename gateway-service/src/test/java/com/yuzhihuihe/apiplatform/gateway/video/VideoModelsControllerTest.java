package com.yuzhihuihe.apiplatform.gateway.video;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

class VideoModelsControllerTest {

    private static final String REQUEST_ID = "req_video_models_contract";

    @Test
    void returnsStableVideoModelMetadataForAnAuthenticatedApiKey() {
        VideoModelCatalogService catalog = mock(VideoModelCatalogService.class);
        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Set.of("video-basic"));
        when(catalog.availableModels(context)).thenReturn(Flux.just(
                new VideoModelCatalogService.VideoModelView(
                        "video-basic", "Video Basic", "720P", "VIDEO_SECOND", parameters())));
        WebTestClient client = WebTestClient.bindToController(new VideoModelsController(catalog))
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put(
                            ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE, context);
                    exchange.getAttributes().put(
                            ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID);
                    return chain.filter(exchange);
                })
                .build();

        client.get().uri("/v1/video-models").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("x-request-id", REQUEST_ID)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data[0].id").isEqualTo("video-basic")
                .jsonPath("$.data[0].displayName").isEqualTo("Video Basic")
                .jsonPath("$.data[0].resolutionTier").isEqualTo("720P")
                .jsonPath("$.data[0].usageDimension").isEqualTo("VIDEO_SECOND")
                // 客户端要据此渲染分辨率/画幅/时长选择器，
                // 因此这些参数必须有稳定的字段名。
                .jsonPath("$.data[0].parameters.soldResolutionTier").isEqualTo("720P")
                .jsonPath("$.data[0].parameters.platformMaxSeconds").isEqualTo(60)
                .jsonPath("$.data[0].parameters.durationSeconds.min").isEqualTo(1)
                .jsonPath("$.data[0].parameters.durationSeconds.max").isEqualTo(60)
                .jsonPath("$.data[0].parameters.modes[0].mode").isEqualTo("TEXT_TO_VIDEO")
                .jsonPath("$.data[0].parameters.modes[0].resolutions[0]").isEqualTo("720P")
                .jsonPath("$.data[0].parameters.modes[0].aspectRatios[0]").isEqualTo("16:9")
                // 展示目录绝不泄露路由/供应商细节或凭证。
                .jsonPath("$.data[0].provider").doesNotExist()
                .jsonPath("$.data[0].upstreamModel").doesNotExist()
                .jsonPath("$.data[0].apiKey").doesNotExist();
    }

    private static VideoModelCatalogService.VideoParameters parameters() {
        return new VideoModelCatalogService.VideoParameters(
                java.util.List.of(new VideoModelCatalogService.ModeView(
                        "TEXT_TO_VIDEO",
                        java.util.List.of("720P"),
                        new VideoModelCatalogService.DurationView(1, 60, java.util.List.of()),
                        java.util.List.of("16:9", "9:16", "1:1", "4:3", "3:4"))),
                "720P",
                java.util.List.of("480P", "720P", "1080P"),
                new VideoModelCatalogService.DurationView(1, 60, java.util.List.of()),
                60,
                java.util.List.of("16:9", "9:16", "1:1", "4:3", "3:4"),
                true,
                false,
                java.util.List.of());
    }

    @Test
    void rejectsARequestWithoutTheApiKeyContext() {
        VideoModelCatalogService catalog = mock(VideoModelCatalogService.class);
        WebTestClient client = WebTestClient.bindToController(new VideoModelsController(catalog))
                .build();

        client.get().uri("/v1/video-models").exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(catalog);
    }
}

package com.yuzhihuihe.apiplatform.gateway.audio;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

class AudioModelsControllerTest {

    private static final String REQUEST_ID = "req_audio_models_contract";

    @Test
    void returnsStableAudioModelMetadataForAnAuthenticatedApiKey() {
        AudioModelCatalogService catalog = mock(AudioModelCatalogService.class);
        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Set.of("audio-asr-subtitle"));
        when(catalog.availableModels(context)).thenReturn(Flux.just(
                new AudioModelCatalogService.AudioModelView(
                        "audio-asr-subtitle", "语音识别·字幕版",
                        Set.of("AUDIO_TRANSCRIBE"), "SECOND", "AUDIO_SECOND", List.of())));
        WebTestClient client = WebTestClient.bindToController(new AudioModelsController(catalog))
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put(
                            ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE, context);
                    exchange.getAttributes().put(
                            ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID);
                    return chain.filter(exchange);
                })
                .build();

        client.get().uri("/v1/audio-models").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("x-request-id", REQUEST_ID)
                // 权益目录与 /v1/quota、/v1/models 同一规则：不允许缓存。
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data[0].id").isEqualTo("audio-asr-subtitle")
                .jsonPath("$.data[0].displayName").isEqualTo("语音识别·字幕版")
                .jsonPath("$.data[0].capabilities[0]").isEqualTo("AUDIO_TRANSCRIBE")
                .jsonPath("$.data[0].billingUnit").isEqualTo("SECOND")
                .jsonPath("$.data[0].usageDimension").isEqualTo("AUDIO_SECOND")
                // 非合成别名不带音色清单，字段仍存在、为空数组。
                .jsonPath("$.data[0].voices").isEmpty()
                // 展示目录绝不泄露路由/供应商细节或凭证。
                .jsonPath("$.data[0].provider").doesNotExist()
                .jsonPath("$.data[0].upstreamModel").doesNotExist()
                .jsonPath("$.data[0].apiKey").doesNotExist();
    }

    @Test
    void exposesTheVendorVoicesSoTheClientCanRenderAPicker() {
        AudioModelCatalogService catalog = mock(AudioModelCatalogService.class);
        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Set.of("audio-tts-basic"));
        when(catalog.availableModels(context)).thenReturn(Flux.just(
                new AudioModelCatalogService.AudioModelView(
                        "audio-tts-basic", "配音",
                        Set.of("AUDIO_SYNTHESIS"), "SECOND", "AUDIO_SECOND",
                        List.of(
                                new AudioSynthesisAdapter.Voice("Cherry", "阳光积极的小姐姐"),
                                new AudioSynthesisAdapter.Voice("Serena", "温柔的小姐姐")))));
        WebTestClient client = WebTestClient.bindToController(new AudioModelsController(catalog))
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put(
                            ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE, context);
                    exchange.getAttributes().put(
                            ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID);
                    return chain.filter(exchange);
                })
                .build();

        client.get().uri("/v1/audio-models").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].voices[0].name").isEqualTo("Cherry")
                .jsonPath("$.data[0].voices[0].description").isEqualTo("阳光积极的小姐姐")
                .jsonPath("$.data[0].voices[1].name").isEqualTo("Serena");
    }

    @Test
    void rejectsARequestWithoutTheApiKeyContext() {
        AudioModelCatalogService catalog = mock(AudioModelCatalogService.class);
        WebTestClient client = WebTestClient.bindToController(new AudioModelsController(catalog))
                .build();

        client.get().uri("/v1/audio-models").exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(catalog);
    }
}

package com.yuzhihuihe.apiplatform.gateway.routing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ModelsControllerTest {

    private static final String REQUEST_ID = "req_models_contract";

    @Test
    void returnsOpenAiStyleStableModelListForAuthenticatedApiKey() {
        ModelCatalogService catalog = mock(ModelCatalogService.class);
        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Set.of("text-basic"));
        when(catalog.availableModels(context)).thenReturn(Flux.just(new ModelCatalogService.ModelView(
                "text-basic", "model", "yuzhihuihe", "Text Basic", Set.of("TEXT"), 991808, 65536)));
        WebTestClient client = WebTestClient.bindToController(new ModelsController(catalog))
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE, context);
                    exchange.getAttributes().put(ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID);
                    return chain.filter(exchange);
                })
                .build();

        client.get().uri("/v1/models").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("x-request-id", REQUEST_ID)
                // P2-2 回归：权益目录必须显式禁止缓存（与 /v1/quota 同一规则）。
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data[0].id").isEqualTo("text-basic")
                .jsonPath("$.data[0].ownedBy").isEqualTo("yuzhihuihe")
                // 非空目录不带 reason（P1-5：它只在 data 为空时出现）。
                .jsonPath("$.reason").doesNotExist();
    }

    @Test
    void explainsAnEmptyCatalogueWithAStableReasonCode() {
        ModelCatalogService catalog = mock(ModelCatalogService.class);
        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Set.of("video-basic"));
        when(catalog.availableModels(context)).thenReturn(Flux.empty());
        when(catalog.emptyReason(context)).thenReturn(Mono.just("no_chat_models"));
        WebTestClient client = WebTestClient.bindToController(new ModelsController(catalog))
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE, context);
                    exchange.getAttributes().put(ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID);
                    return chain.filter(exchange);
                })
                .build();

        client.get().uri("/v1/models").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data").isEmpty()
                .jsonPath("$.reason").isEqualTo("no_chat_models");
    }
}

package com.yuzhihuihe.apiplatform.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.AdminListQuery;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.CreateChannelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.CreateModelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.ModelRouteConfiguration;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.SaveModelConfigurationCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.SetModelStatusCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.UpdateChannelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests.UpdateModelCommand;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ChannelModelChoiceView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ChannelModelTestView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ChannelTestView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ChannelView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ModelRouteView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ModelView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.PageResult;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ProviderView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AdminGatewayControllerTest {

    @Test
    void forwardsAuthenticatedActorToModelAndChannelMutations() {
        GatewayAdminService admin = mock(GatewayAdminService.class);
        ChannelCircuitRegistry circuits = mock(ChannelCircuitRegistry.class);
        VideoAliasBillingService videoBilling = mock(VideoAliasBillingService.class);
        AdminGatewayController controller = new AdminGatewayController(admin, videoBilling, circuits);
        UUID actorId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject(actorId.toString()).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build();
        CreateModelCommand createModel =
                new CreateModelCommand(
                        "actor-model", "Actor Model", Set.of("TEXT"),
                        8192, 2048, false, true, 20, 2);
        UpdateModelCommand updateModel =
                new UpdateModelCommand(
                        modelId, "Actor Model", Set.of("TEXT"),
                        8192, 2048, false, true, 20, 2);
        CreateChannelCommand createChannel =
                new CreateChannelCommand(
                        providerId, "actor-channel", "Actor Channel", "SANDBOX",
                        "https://example.test/v1", "secret", true, 10, 100, 30000, 20, 2);
        UpdateChannelCommand updateChannel =
                new UpdateChannelCommand(
                        channelId, "Actor Channel", "https://example.test/v1", null,
                        true, 10, 100, 30000, 20, 2);
        SaveModelConfigurationCommand saveConfiguration =
                new SaveModelConfigurationCommand(
                        modelId, "actor-model", "Actor Model", Set.of("actor-model"),
                        Set.of("TEXT"), 8192, 2048, false, true, 20, 2,
                        new ModelRouteConfiguration(
                                channelId, "actor-model", providerId, "SANDBOX",
                                "https://example.test/v1", null, "upstream-model",
                                true, 10, 100, 30000, 20, 2));

        controller.createModel(createModel, jwt);
        controller.updateModel(updateModel, jwt);
        controller.saveModelConfiguration(saveConfiguration, jwt);
        controller.setModelStatus(modelId, new SetModelStatusCommand(false), jwt);
        controller.deleteModel(modelId, jwt);
        controller.createChannel(createChannel, jwt);
        controller.updateChannel(updateChannel, jwt);

        verify(admin).createModel(createModel, actorId);
        verify(admin).updateModel(updateModel, actorId);
        verify(admin).saveModelConfiguration(saveConfiguration, actorId);
        verify(admin).setModelEnabled(modelId, false, actorId);
        verify(admin).deleteModel(modelId, actorId, "token");
        verify(admin).createChannel(createChannel, actorId);
        verify(admin).updateChannel(updateChannel, actorId);
    }

    @Test
    void exposesAdminModelAndChannelRoutesWithoutReturningPlaintextSecrets() {
        GatewayAdminService admin = mock(GatewayAdminService.class);
        ChannelCircuitRegistry circuits = mock(ChannelCircuitRegistry.class);
        UUID modelId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        ProviderView provider = new ProviderView(
                providerId, "bailian", "Alibaba Bailian", "BAILIAN",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", true);
        ModelView model = new ModelView(
                modelId, "text-basic", "Text Basic", Set.of("text-basic"), Set.of("TEXT"),
                991808, 65536, false, true, 300, 50, "DIRECT",
                List.of(new ModelRouteView(
                        "text-basic", "Text Basic", providerId, "bailian", "Alibaba Bailian",
                        channelId, "bailian-primary", "Bailian Primary", "qwen-turbo",
                        "PRODUCTION", true, "HEALTHY", true, 90, 100, true)));
        ChannelView channel = new ChannelView(
                channelId, providerId, "bailian-primary", "Bailian Primary", "PRODUCTION",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", true, 90, 100,
                60000, 300, 50, "HEALTHY", true);
        when(admin.listModels(any())).thenReturn(Mono.just(
                new PageResult<>(List.of(model), 1, 2, 5)));
        when(admin.createModel(any(), any(UUID.class))).thenReturn(Mono.just(model));
        when(admin.updateModel(any(), any(UUID.class))).thenReturn(Mono.just(model));
        when(admin.saveModelConfiguration(any(), any(UUID.class))).thenReturn(Mono.just(model));
        when(admin.setModelEnabled(any(UUID.class), anyBoolean(), any(UUID.class)))
                .thenReturn(Mono.just(model));
        when(admin.deleteModel(any(UUID.class), any(UUID.class), any(String.class)))
                .thenReturn(Mono.empty());
        when(admin.listChannels(any())).thenReturn(Mono.just(
                new PageResult<>(List.of(channel), 1, 3, 7)));
        when(admin.listAllChannels()).thenReturn(Flux.just(channel));
        when(admin.listProviders()).thenReturn(Flux.just(provider));
        when(admin.createChannel(any(), any(UUID.class))).thenReturn(Mono.just(channel));
        when(admin.updateChannel(any(), any(UUID.class))).thenReturn(Mono.just(channel));
        when(admin.testChannel(channelId)).thenReturn(Mono.just(
                new ChannelTestView(channelId, true, "HEALTHY")));
        when(admin.testChannelModel(any(UUID.class), any(), anyBoolean(), any(UUID.class)))
                .thenReturn(Mono.just(new ChannelModelTestView(
                        channelId, "bailian-primary", "BAILIAN", true, true, true, true,
                        "HEALTHY", "text-basic", "qwen-turbo", null, 200, 42L, 7L, 1L, true, null,
                        List.of(new ChannelModelChoiceView(
                                "text-basic", "Text Basic", "qwen-turbo", true, true, true)))));
        ChannelCircuitRegistry.CircuitHealth resetCircuit =
                new ChannelCircuitRegistry.CircuitHealth("test-circuit", "CLOSED", 0, 0);
        when(circuits.reset("test-circuit")).thenReturn(resetCircuit);
        when(circuits.health()).thenReturn(java.util.List.of());
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject(UUID.randomUUID().toString()).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build();
        UUID actorId = UUID.fromString(jwt.getSubject());
        when(admin.auditCircuitReset("test-circuit", resetCircuit, actorId))
                .thenReturn(Mono.empty());
        WebTestClient client = WebTestClient.bindToController(
                        new AdminGatewayController(
                                admin, mock(VideoAliasBillingService.class), circuits))
                .argumentResolvers(configurer -> configurer.addCustomResolver(jwtResolver(jwt)))
                .build();

        client.get().uri("/console/gateway/admin/models?page=2&size=5&query=text&status=enabled"
                        + "&sort=displayName&direction=asc")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.items[0].code").isEqualTo("text-basic")
                .jsonPath("$.items[0].routes[0].providerName").isEqualTo("Alibaba Bailian")
                .jsonPath("$.items[0].routes[0].upstreamModel").isEqualTo("qwen-turbo")
                .jsonPath("$.items[0].routes[0].apiKey").doesNotExist()
                .jsonPath("$.total").isEqualTo(1)
                .jsonPath("$.page").isEqualTo(2)
                .jsonPath("$.size").isEqualTo(5);
        client.post().uri("/console/gateway/admin/models")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"code":"text-basic","displayName":"Text Basic","capabilities":["TEXT"],
                         "contextWindow":991808,"maxOutputTokens":65536,"premium":false,"enabled":true,
                         "requestsPerMinute":300,"maxConcurrency":50}
                        """).exchange().expectStatus().isOk();
        client.patch().uri("/console/gateway/admin/models")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"id":"%s","displayName":"Text Basic","capabilities":["TEXT"],
                         "contextWindow":991808,"maxOutputTokens":65536,"premium":false,"enabled":true,
                         "requestsPerMinute":300,"maxConcurrency":50}
                        """.formatted(modelId)).exchange().expectStatus().isOk();
        client.post().uri("/console/gateway/admin/models/configuration")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"id":"%s","code":"text-basic","displayName":"Text Basic",
                         "aliases":["text-basic"],"capabilities":["TEXT"],
                         "contextWindow":991808,"maxOutputTokens":65536,"premium":false,
                         "enabled":true,"requestsPerMinute":300,"maxConcurrency":50,
                         "route":{"channelId":"%s","componentModelCode":"text-basic",
                           "providerId":"%s","environment":"PRODUCTION",
                           "baseUrl":"https://example.test/v1","upstreamModel":"qwen-turbo",
                           "enabled":true,"priority":90,"weight":100,"timeoutMs":60000,
                           "requestsPerMinute":300,"maxConcurrency":50}}
                        """.formatted(modelId, channelId, providerId))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.routes[0].apiKey").doesNotExist();
        client.patch().uri("/console/gateway/admin/models/" + modelId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"enabled\":false}")
                .exchange().expectStatus().isOk();
        client.delete().uri("/console/gateway/admin/models/" + modelId)
                .exchange().expectStatus().isOk().expectBody().isEmpty();
        client.get().uri("/console/gateway/admin/channels?page=3&size=7&query=bailian&status=ENABLED"
                        + "&sort=provider&direction=desc").exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.items[0].credentialConfigured").isEqualTo(true)
                .jsonPath("$.items[0].apiKey").doesNotExist()
                .jsonPath("$.items[0].encryptedApiKey").doesNotExist()
                .jsonPath("$.total").isEqualTo(1)
                .jsonPath("$.page").isEqualTo(3)
                .jsonPath("$.size").isEqualTo(7);
        client.get().uri("/console/gateway/admin/providers").exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$[0].code").isEqualTo("bailian")
                .jsonPath("$[0].encryptedCredentials").doesNotExist()
                .jsonPath("$[0].credentials").doesNotExist();
        client.post().uri("/console/gateway/admin/channels")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"providerId":"%s","code":"bailian-primary","name":"Bailian Primary",
                         "environment":"PRODUCTION","baseUrl":"https://example.test/v1",
                         "apiKey":"plain-secret","enabled":true,"priority":90,"weight":100,
                         "timeoutMs":60000,"requestsPerMinute":300,"maxConcurrency":50}
                        """.formatted(providerId)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.apiKey").doesNotExist();
        client.patch().uri("/console/gateway/admin/channels")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"id":"%s","name":"Bailian Primary","baseUrl":"https://example.test/v1",
                         "enabled":true,"priority":90,"weight":100,"timeoutMs":60000,
                         "requestsPerMinute":300,"maxConcurrency":50}
                        """.formatted(channelId)).exchange().expectStatus().isOk();
        client.post().uri("/console/gateway/admin/channels/" + channelId + "/test")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.status").isEqualTo("HEALTHY");
        // 真实模型调用测试：结果必须带上游模型名与结论，但绝不回显密钥。
        client.post().uri("/console/gateway/admin/channels/" + channelId + "/model-test")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"upstreamModel\":\"qwen-turbo\"}")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.ok").isEqualTo(true)
                .jsonPath("$.modelOk").isEqualTo(true)
                .jsonPath("$.modelInvoked").isEqualTo(true)
                .jsonPath("$.upstreamModel").isEqualTo("qwen-turbo")
                .jsonPath("$.latencyMs").isEqualTo(42)
                .jsonPath("$.apiKey").doesNotExist()
                .jsonPath("$.encryptedApiKey").doesNotExist();
        client.get().uri("/console/gateway/admin/routing/health")
                .exchange().expectStatus().isOk();
        client.post().uri("/console/gateway/admin/routing/circuits/test-circuit/reset")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.state").isEqualTo("CLOSED");

        ArgumentCaptor<CreateChannelCommand> command =
                ArgumentCaptor.forClass(CreateChannelCommand.class);
        verify(admin).createChannel(command.capture(), any(UUID.class));
        assertThat(command.getValue().apiKey()).isEqualTo("plain-secret");
        ArgumentCaptor<AdminListQuery> modelQuery =
                ArgumentCaptor.forClass(AdminListQuery.class);
        ArgumentCaptor<AdminListQuery> channelQuery =
                ArgumentCaptor.forClass(AdminListQuery.class);
        verify(admin).listModels(modelQuery.capture());
        verify(admin).listChannels(channelQuery.capture());
        assertThat(modelQuery.getValue()).isEqualTo(new AdminListQuery(
                2, 5, "text", "enabled", "displayName", "asc"));
        assertThat(channelQuery.getValue()).isEqualTo(new AdminListQuery(
                3, 7, "bailian", "ENABLED", "provider", "desc"));
        verify(admin).listAllChannels();
        verify(admin).auditCircuitReset("test-circuit", resetCircuit, actorId);
    }

    private HandlerMethodArgumentResolver jwtResolver(Jwt jwt) {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType() == Jwt.class;
            }

            @Override
            public Mono<Object> resolveArgument(
                    MethodParameter parameter, BindingContext bindingContext,
                    ServerWebExchange exchange) {
                return Mono.just(jwt);
            }
        };
    }
}

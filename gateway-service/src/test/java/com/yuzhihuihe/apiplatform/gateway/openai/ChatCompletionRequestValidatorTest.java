package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ChatCompletionRequestValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REQUEST_ID = "req_00000000000000000000000000000001";

    private final ChatCompletionRequestValidator validator =
            new ChatCompletionRequestValidator();
    private final ApiKeyService.ApiKeyContext apiKey = new ApiKeyService.ApiKeyContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Set.of("text-basic", "multimodal-basic"));

    @Test
    void missingApiKeyTakesPrecedenceOverMalformedChatRequest() {
        assertThatThrownBy(() -> validator.validateChat(null, JSON.createArrayNode(), null))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(error.getReason()).isEqualTo("api_key_required");
                });
    }

    @Test
    void derivesTextAndImageCapabilitiesFromChatContent() {
        ObjectNode request = request("text-basic");
        var content = ((ObjectNode) request.withArray("messages").get(0)).putArray("content");
        content.addObject().put("type", "text").put("text", "describe");
        content.addObject()
                .put("type", "image_url")
                .putObject("image_url")
                .put("url", "https://example.test/image.png");

        ChatCompletionRequestValidator.RequestShape shape =
                validator.validateChat(apiKey, request, REQUEST_ID);

        assertThat(shape.publicModel()).isEqualTo("text-basic");
        assertThat(shape.routingModel()).isEqualTo("text-basic");
        assertThat(shape.capabilities()).containsExactlyInAnyOrder(
                ChannelRouter.Capability.TEXT, ChannelRouter.Capability.IMAGE);
    }

    @Test
    void rejectsRecursiveFileFieldsInChatContent() {
        ObjectNode request = request("text-basic");
        var content = ((ObjectNode) request.withArray("messages").get(0)).putArray("content");
        content.addObject()
                .put("type", "text")
                .put("text", "hello")
                .putObject("metadata")
                .put("file-id", "file-1");

        assertBadRequest(
                () -> validator.validateChat(apiKey, request, REQUEST_ID),
                "invalid_chat_request");
    }

    @Test
    void mapsPreparedSanitizationFailuresToResponsesError() {
        ObjectNode request = request("text-basic").put("temperature", 3);

        assertBadRequest(
                () -> validator.validatePrepared(
                        apiKey, request, REQUEST_ID, Set.of(ChannelRouter.Capability.TEXT)),
                "invalid_responses_request");
    }

    @Test
    void keepsRoutedAliasValidationSeparateFromPreparedValidation() {
        assertBadRequest(
                () -> validator.validateRouted(
                        apiKey,
                        request("multimodal-basic"),
                        REQUEST_ID,
                        "another-model",
                        "text-basic",
                        Set.of(ChannelRouter.Capability.TEXT)),
                "invalid_multimodal_request");
    }

    @Test
    void rejectsCompletionCountsAboveTheSupportedBoundAndNonPositiveOnes() {
        // n 会成倍放大预留，因此请求层会拒绝任何预留无法如实覆盖的取值 —— 并给出
        // 与其他所有畸形受支持选项相同的 400 invalid_chat_request 应答。
        assertBadRequest(
                () -> validator.validateChat(
                        apiKey,
                        request("text-basic").put("n", ProviderAdapter.MAX_COMPLETIONS + 1),
                        REQUEST_ID),
                "invalid_chat_request");
        assertBadRequest(
                () -> validator.validateChat(
                        apiKey, request("text-basic").put("n", 0), REQUEST_ID),
                "invalid_chat_request");
        assertBadRequest(
                () -> validator.validateChat(
                        apiKey, request("text-basic").put("n", -1), REQUEST_ID),
                "invalid_chat_request");
        assertBadRequest(
                () -> validator.validateChat(
                        apiKey, request("text-basic").put("n", 1.5), REQUEST_ID),
                "invalid_chat_request");
    }

    @Test
    void acceptsTheLargestCompletionCountTheReservationBoundAllows() {
        ObjectNode request = request("text-basic").put("n", ProviderAdapter.MAX_COMPLETIONS);

        assertThat(validator.validateChat(apiKey, request, REQUEST_ID).publicModel())
                .isEqualTo("text-basic");
    }

    @Test
    void rejectsModelsOutsideTheApiKeyAllowlist() {
        assertThatThrownBy(() -> validator.validateChat(
                        apiKey, request("unavailable-model"), REQUEST_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(error.getReason()).isEqualTo("model_not_allowed");
                });
    }

    @Test
    void keepsVideoAliasesOnTheAsynchronousVideoEndpoint() {
        ApiKeyService.ApiKeyContext videoKey = new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Set.of("video-basic"));

        assertThatThrownBy(() -> validator.validateChat(
                        videoKey, request("video-basic"), REQUEST_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getReason())
                            .isEqualTo("video_model_requires_video_endpoint");
                });
    }

    private static ObjectNode request(String model) {
        ObjectNode request = JSON.createObjectNode();
        request.put("model", model);
        request.put("stream", false);
        request.putArray("messages")
                .addObject()
                .put("role", "user")
                .put("content", "hello");
        return request;
    }

    private static void assertBadRequest(Runnable validation, String reason) {
        assertThatThrownBy(validation::run)
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getReason()).isEqualTo(reason);
                });
    }
}

package com.yuzhihuihe.apiplatform.gateway.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public final class ChatCompletionRequestValidator {

    private static final Set<String> UNSUPPORTED_CHAT_PART_TYPES =
            Set.of("file", "file_url", "input_file", "file_id", "file_data");
    private static final String VIDEO_MODEL_ERROR = "video_model_requires_video_endpoint";

    RequestShape validateChat(ApiKeyService.ApiKeyContext apiKey, JsonNode body, String requestId) {
        requireApiKey(apiKey);
        if (body == null
                || !body.isObject()
                || !body.path("model").isTextual()
                || body.path("model").asText().isBlank()
                || !body.path("messages").isArray()
                || body.path("messages").isEmpty()
                || requestId == null
                || requestId.isBlank()) {
            throw badRequest("invalid_chat_request");
        }
        String model = body.path("model").asText();
        sanitize(body, model, "invalid_chat_request");
        requireAllowedModel(apiKey, model);
        requireChatModel(model);
        return new RequestShape(model, model, capabilities(body));
    }

    void validatePublicAlias(ApiKeyService.ApiKeyContext apiKey, String publicModelAlias) {
        requireApiKey(apiKey);
        if (publicModelAlias == null
                || publicModelAlias.isBlank()
                || publicModelAlias.length() > 128) {
            throw badRequest("invalid_multimodal_request");
        }
        requireAllowedModel(apiKey, publicModelAlias);
        requireChatModel(publicModelAlias);
    }

    RequestShape validatePrepared(
            ApiKeyService.ApiKeyContext apiKey,
            JsonNode body,
            String requestId,
            Set<ChannelRouter.Capability> capabilities) {
        requireApiKey(apiKey);
        if (body == null
                || !body.isObject()
                || !body.path("model").isTextual()
                || body.path("model").asText().isBlank()
                || !body.path("messages").isArray()
                || body.path("messages").isEmpty()
                || requestId == null
                || requestId.isBlank()
                || capabilities == null
                || !capabilities.contains(ChannelRouter.Capability.TEXT)) {
            throw badRequest("invalid_responses_request");
        }
        String model = body.path("model").asText();
        sanitize(body, model, "invalid_responses_request");
        requireAllowedModel(apiKey, model);
        requireChatModel(model);
        return new RequestShape(model, model, Set.copyOf(capabilities));
    }

    RequestShape validateRouted(
            ApiKeyService.ApiKeyContext apiKey,
            JsonNode body,
            String requestId,
            String publicModelAlias,
            String routingModelAlias,
            Set<ChannelRouter.Capability> capabilities) {
        RequestShape validated = validatePrepared(apiKey, body, requestId, capabilities);
        if (publicModelAlias == null
                || publicModelAlias.isBlank()
                || routingModelAlias == null
                || routingModelAlias.isBlank()
                || !validated.publicModel().equals(publicModelAlias)) {
            throw badRequest("invalid_multimodal_request");
        }
        return new RequestShape(publicModelAlias, routingModelAlias, validated.capabilities());
    }

    private void requireApiKey(ApiKeyService.ApiKeyContext apiKey) {
        if (apiKey == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "api_key_required");
        }
    }

    private void requireAllowedModel(ApiKeyService.ApiKeyContext apiKey, String model) {
        if (!apiKey.allowedModels().contains(model)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "model_not_allowed");
        }
    }

    /** 视频别名有自己的异步协议与秒数维度。即便某个 key 或 陈旧的目录条目仍带着视频别名，也要让该端点边界保持显式； 数据库目录会施加能力/维度守卫，作为最终的事实来源检查。 */
    private void requireChatModel(String model) {
        String normalized = model.strip().toLowerCase(Locale.ROOT);
        if (normalized.equals("video") || normalized.startsWith("video-")) {
            throw badRequest(VIDEO_MODEL_ERROR);
        }
    }

    private void sanitize(JsonNode body, String model, String reason) {
        try {
            ProviderAdapter.sanitize(body, model, body.path("stream").asBoolean(false));
        } catch (IllegalArgumentException invalidRequest) {
            throw badRequest(reason);
        }
    }

    private Set<ChannelRouter.Capability> capabilities(JsonNode body) {
        Set<ChannelRouter.Capability> capabilities = new LinkedHashSet<>();
        capabilities.add(ChannelRouter.Capability.TEXT);
        body.path("messages")
                .forEach(
                        message ->
                                message.path("content")
                                        .forEach(
                                                part -> {
                                                    String type = part.path("type").asText();
                                                    if (UNSUPPORTED_CHAT_PART_TYPES.contains(type)
                                                            || containsFileField(part)) {
                                                        throw badRequest("invalid_chat_request");
                                                    }
                                                    if ("image_url".equals(type)
                                                            || "input_image".equals(type)) {
                                                        capabilities.add(
                                                                ChannelRouter.Capability.IMAGE);
                                                    }
                                                }));
        return Set.copyOf(capabilities);
    }

    private boolean containsFileField(JsonNode node) {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String normalized =
                        field.getKey().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
                if (normalized.startsWith("file") || containsFileField(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsFileField(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    record RequestShape(
            String publicModel, String routingModel, Set<ChannelRouter.Capability> capabilities) {}
}

package com.yuzhihuihe.apiplatform.gateway.provider;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class BailianAdapter extends OpenAiCompatibleProviderAdapter {

    private static final Set<ChannelRouter.Capability> CAPABILITIES = Set.of(
            ChannelRouter.Capability.TEXT, ChannelRouter.Capability.IMAGE, ChannelRouter.Capability.FILE);

    public BailianAdapter(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        super(webClientBuilder, objectMapper);
    }

    /** Spring 用的构造器：带上「首帧」与「空闲」两个等待窗口（见 {@link ChatStreamTimeoutPolicy}）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public BailianAdapter(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            ChatStreamTimeoutPolicy timeouts) {
        super(webClientBuilder, objectMapper, timeouts);
    }

    public static Set<ChannelRouter.Capability> supportedCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public String providerType() {
        return "BAILIAN";
    }

    @Override
    public Set<ChannelRouter.Capability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    protected JsonNode prepareProviderBody(JsonNode body, String upstreamModel) {
        if (!"qwen-long".equalsIgnoreCase(upstreamModel)) {
            return body;
        }
        ObjectNode copy = ((ObjectNode) body).deepCopy();
        ArrayNode sourceMessages = copy.withArray("messages");
        ArrayNode messagesWithoutFiles = copy.arrayNode();
        Set<String> fileIds = new LinkedHashSet<>();
        for (JsonNode messageNode : sourceMessages) {
            ObjectNode message = ((ObjectNode) messageNode).deepCopy();
            JsonNode content = message.get("content");
            if (content != null && content.isArray()) {
                ArrayNode retained = copy.arrayNode();
                boolean removedFile = false;
                for (JsonNode part : content) {
                    if ("input_file".equals(part.path("type").asText())) {
                        JsonNode fileIdNode = part.get("file_id");
                        if (fileIdNode == null || !fileIdNode.isTextual()
                                || fileIdNode.asText().isBlank()) {
                            throw new IllegalArgumentException("invalid_file_id");
                        }
                        fileIds.add(fileIdNode.asText());
                        removedFile = true;
                    } else {
                        retained.add(part.deepCopy());
                    }
                }
                if (removedFile && retained.isEmpty()) {
                    throw new IllegalArgumentException("invalid_message_content");
                }
                if (onlyTextParts(retained)) {
                    message.put("content", joinText(retained));
                } else {
                    message.set("content", retained);
                }
            }
            messagesWithoutFiles.add(message);
        }
        copy.set("messages", messagesWithoutFiles);
        if (fileIds.isEmpty()) {
            return copy;
        }

        ArrayNode transformed = copy.arrayNode();
        int index = 0;
        while (index < messagesWithoutFiles.size()
                && "system".equals(messagesWithoutFiles.get(index).path("role").asText())) {
            transformed.add(messagesWithoutFiles.get(index++));
        }
        transformed.addObject().put("role", "system")
                .put("content", "fileid://" + String.join(",", fileIds));
        while (index < messagesWithoutFiles.size()) {
            transformed.add(messagesWithoutFiles.get(index++));
        }
        copy.set("messages", transformed);
        return copy;
    }

    private boolean onlyTextParts(ArrayNode parts) {
        if (parts.isEmpty()) {
            return false;
        }
        for (JsonNode part : parts) {
            if (!"text".equals(part.path("type").asText())
                    || !part.path("text").isTextual()) {
                return false;
            }
        }
        return true;
    }

    private String joinText(ArrayNode parts) {
        List<String> text = new ArrayList<>();
        parts.forEach(part -> text.add(part.path("text").asText()));
        return String.join("\n", text);
    }

    @Override
    protected void addProviderHeaders(WebClient.RequestBodySpec spec, boolean stream) {
        spec.header("X-DashScope-SSE", stream ? "enable" : "disable");
    }
}

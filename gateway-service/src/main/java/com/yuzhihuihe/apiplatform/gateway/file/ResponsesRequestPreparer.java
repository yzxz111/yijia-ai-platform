package com.yuzhihuihe.apiplatform.gateway.file;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ResponsesRequestPreparer {

    private static final Set<String> COPIED_OPTIONS = Set.of(
            "temperature", "top_p", "tools", "tool_choice", "response_format",
            "seed", "frequency_penalty", "presence_penalty", "stop");

    private final ObjectMapper objectMapper;
    private final FileContentValidator contentValidator;
    private final RemoteImageFetcher imageFetcher;
    private final FileService fileService;
    private final long maxImageBytes;
    private final int maxImages;
    private final long maxTotalImageBytes;
    private final Scheduler preprocessingScheduler;

    @Autowired
    public ResponsesRequestPreparer(
            ObjectMapper objectMapper,
            FileContentValidator contentValidator,
            RemoteImageFetcher imageFetcher,
            FileService fileService,
            @Value("${gateway.responses.max-image-bytes:20971520}") long maxImageBytes,
            @Value("${gateway.responses.max-images:8}") int maxImages,
            @Value("${gateway.responses.max-total-image-bytes:20971520}") long maxTotalImageBytes,
            @Qualifier("multimodalPreprocessingScheduler") Scheduler preprocessingScheduler) {
        if (maxImageBytes <= 0 || maxImages <= 0 || maxTotalImageBytes <= 0) {
            throw new IllegalArgumentException("invalid_image_limits");
        }
        this.objectMapper = objectMapper;
        this.contentValidator = contentValidator;
        this.imageFetcher = imageFetcher;
        this.fileService = fileService;
        this.maxImageBytes = maxImageBytes;
        this.maxImages = maxImages;
        this.maxTotalImageBytes = maxTotalImageBytes;
        this.preprocessingScheduler = preprocessingScheduler;
    }

    public ResponsesRequestPreparer(
            ObjectMapper objectMapper,
            FileContentValidator contentValidator,
            RemoteImageFetcher imageFetcher,
            FileService fileService,
            long maxImageBytes,
            int maxImages,
            long maxTotalImageBytes) {
        this(objectMapper, contentValidator, imageFetcher, fileService, maxImageBytes,
                maxImages, maxTotalImageBytes, Schedulers.immediate());
    }

    ResponsesRequestPreparer(
            ObjectMapper objectMapper,
            FileContentValidator contentValidator,
            RemoteImageFetcher imageFetcher,
            FileService fileService,
            long maxImageBytes) {
        this(objectMapper, contentValidator, imageFetcher, fileService,
                maxImageBytes, 8, maxImageBytes, Schedulers.immediate());
    }

    public Mono<PreparedResponseRequest> prepare(
            ApiKeyService.ApiKeyContext context, JsonNode body) {
        return Mono.defer(() -> {
            RequestSeed seed = validate(context, body);
            ObjectNode chatBody = objectMapper.createObjectNode();
            chatBody.put("model", seed.model());
            chatBody.put("stream", seed.stream());
            copyOptions(body, chatBody);
            ArrayNode messages = chatBody.putArray("messages");
            if (body.path("instructions").isTextual()
                    && !body.path("instructions").asText().isBlank()) {
                messages.addObject().put("role", "system")
                        .put("content", body.path("instructions").asText());
            }
            List<FileObjectRepository.FileObject> files = new ArrayList<>();
            Set<ChannelRouter.Capability> capabilities = new LinkedHashSet<>();
            capabilities.add(ChannelRouter.Capability.TEXT);
            return appendInput(context, body.get("input"), messages, files, capabilities)
                    .then(Mono.fromSupplier(() -> {
                        if (imageParts(chatBody).size() > maxImages) {
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST, "too_many_images");
                        }
                        return new PreparedResponseRequest(
                                chatBody, List.copyOf(files), Set.copyOf(capabilities), seed.stream());
                    }));
        });
    }

    public Mono<JsonNode> hydrateImages(JsonNode body) {
        return Mono.defer(() -> {
            ObjectNode hydrated = ((ObjectNode) body).deepCopy();
            List<ObjectNode> images = imageParts(hydrated);
            if (images.size() > maxImages) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "too_many_images"));
            }
            AtomicLong totalBytes = new AtomicLong();
            return Flux.fromIterable(images)
                    .concatMap(image -> hydrateImage(image.path("url").asText())
                            .doOnNext(value -> {
                                long total;
                                try {
                                    total = Math.addExact(totalBytes.get(), value.sizeBytes());
                                } catch (ArithmeticException overflow) {
                                    throw imagesTooLarge();
                                }
                                if (total > maxTotalImageBytes) {
                                    throw imagesTooLarge();
                                }
                                totalBytes.set(total);
                                image.put("url", value.dataUri());
                            }))
                    .then(Mono.just(hydrated));
        });
    }

    private RequestSeed validate(ApiKeyService.ApiKeyContext context, JsonNode body) {
        if (context == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "api_key_required");
        }
        if (body == null || !body.isObject()
                || !body.path("model").isTextual() || body.path("model").asText().isBlank()
                || body.get("input") == null
                || body.has("stream") && !body.path("stream").isBoolean()) {
            throw invalidRequest();
        }
        String model = body.path("model").asText();
        if (!context.allowedModels().contains(model)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "model_not_allowed");
        }
        return new RequestSeed(model, body.path("stream").asBoolean(false));
    }

    private void copyOptions(JsonNode source, ObjectNode target) {
        for (String option : COPIED_OPTIONS) {
            if (source.has(option)) {
                target.set(option, source.get(option).deepCopy());
            }
        }
        JsonNode maxOutput = source.get("max_output_tokens");
        if (maxOutput != null) {
            target.set("max_tokens", maxOutput.deepCopy());
        }
        if (source.has("stream_options")) {
            target.set("stream_options", source.get("stream_options").deepCopy());
        }
    }

    private Mono<Void> appendInput(
            ApiKeyService.ApiKeyContext context,
            JsonNode input,
            ArrayNode messages,
            List<FileObjectRepository.FileObject> files,
            Set<ChannelRouter.Capability> capabilities) {
        if (input.isTextual()) {
            if (input.asText().isBlank()) {
                return Mono.error(invalidRequest());
            }
            messages.addObject().put("role", "user").put("content", input.asText());
            return Mono.empty();
        }
        if (!input.isArray() || input.isEmpty()) {
            return Mono.error(invalidRequest());
        }
        return Flux.fromIterable(input)
                .concatMap(item -> appendMessage(context, item, messages, files, capabilities))
                .then();
    }

    private Mono<Void> appendMessage(
            ApiKeyService.ApiKeyContext context,
            JsonNode item,
            ArrayNode messages,
            List<FileObjectRepository.FileObject> files,
            Set<ChannelRouter.Capability> capabilities) {
        if (!item.isObject() || !item.path("role").isTextual()
                || item.path("role").asText().isBlank()) {
            return Mono.error(invalidRequest());
        }
        JsonNode content = item.get("content");
        if (content == null || content.isNull()) {
            return Mono.error(invalidRequest());
        }
        ObjectNode message = messages.addObject().put("role", item.path("role").asText());
        if (content.isTextual()) {
            if (content.asText().isBlank()) {
                return Mono.error(invalidRequest());
            }
            message.put("content", content.asText());
            return Mono.empty();
        }
        if (!content.isArray() || content.isEmpty()) {
            return Mono.error(invalidRequest());
        }
        ArrayNode targetParts = message.putArray("content");
        return Flux.fromIterable(content)
                .concatMap(part -> appendPart(context, part, targetParts, files, capabilities))
                .then();
    }

    private Mono<Void> appendPart(
            ApiKeyService.ApiKeyContext context,
            JsonNode part,
            ArrayNode targetParts,
            List<FileObjectRepository.FileObject> files,
            Set<ChannelRouter.Capability> capabilities) {
        if (!part.isObject() || !part.path("type").isTextual()) {
            return Mono.error(invalidRequest());
        }
        return switch (part.path("type").asText()) {
            case "input_text" -> appendText(part, targetParts);
            case "input_image" -> appendImage(part, targetParts, capabilities);
            case "input_file" -> appendFile(context, part, targetParts, files, capabilities);
            default -> Mono.error(invalidRequest());
        };
    }

    private Mono<Void> appendText(JsonNode part, ArrayNode targetParts) {
        if (!part.path("text").isTextual() || part.path("text").asText().isBlank()) {
            return Mono.error(invalidRequest());
        }
        targetParts.addObject().put("type", "text").put("text", part.path("text").asText());
        return Mono.empty();
    }

    private Mono<Void> appendImage(
            JsonNode part, ArrayNode targetParts, Set<ChannelRouter.Capability> capabilities) {
        JsonNode source = part.get("image_url");
        if (source == null || !source.isTextual() || source.asText().isBlank()) {
            return Mono.error(invalidRequest());
        }
        capabilities.add(ChannelRouter.Capability.IMAGE);
        String value = source.asText();
        validateImageReference(value);
        targetParts.addObject().put("type", "image_url")
                .putObject("image_url").put("url", value);
        return Mono.empty();
    }

    private Mono<Void> appendFile(
            ApiKeyService.ApiKeyContext context,
            JsonNode part,
            ArrayNode targetParts,
            List<FileObjectRepository.FileObject> files,
            Set<ChannelRouter.Capability> capabilities) {
        if (!part.path("file_id").isTextual() || part.path("file_id").asText().isBlank()) {
            return Mono.error(invalidRequest());
        }
        String externalFileId = part.path("file_id").asText();
        return fileService.requireUsable(context, externalFileId)
                .doOnNext(file -> {
                    files.add(file);
                    capabilities.add(ChannelRouter.Capability.FILE);
                    targetParts.addObject().put("type", "input_file").put("file_id", externalFileId);
                })
                .then();
    }

    private void validateImageReference(String value) {
        if (!value.startsWith("data:")) {
            try {
                URI uri = URI.create(value);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                    throw invalidRequest();
                }
                return;
            } catch (IllegalArgumentException invalidUri) {
                throw invalidRequest();
            }
        }
        int separator = value.indexOf(";base64,");
        if (!value.startsWith("data:") || separator <= 5) {
            throw invalidContent();
        }
        String mimeType = value.substring(5, separator);
        String encoded = value.substring(separator + 8);
        long maximumEncodedLength = ((maxImageBytes + 2) / 3) * 4;
        if (encoded.isBlank() || encoded.length() > maximumEncodedLength) {
            throw tooLarge();
        }
        if (!isBase64Syntax(encoded)) {
            throw invalidContent();
        }
    }

    private Mono<HydratedImage> hydrateImage(String value) {
        if (value.startsWith("data:")) {
            return Mono.fromCallable(() -> validateDataUri(value))
                    .subscribeOn(preprocessingScheduler);
        }
        URI uri = URI.create(value);
        return imageFetcher.fetch(uri)
                .flatMap(image -> Mono.fromCallable(() -> {
                    FileContentValidator.ValidatedContent validated = contentValidator.validateImage(
                            image.mediaType(), image.body(), maxImageBytes);
                    return new HydratedImage(
                            dataUri(validated.mimeType(), image.body()), validated.sizeBytes());
                }).subscribeOn(preprocessingScheduler));
    }

    private HydratedImage validateDataUri(String value) {
        int separator = value.indexOf(";base64,");
        String mimeType = value.substring(5, separator);
        String encoded = value.substring(separator + 8);
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            FileContentValidator.ValidatedContent validated =
                    contentValidator.validateImage(mimeType, decoded, maxImageBytes);
            return new HydratedImage(dataUri(validated.mimeType(), decoded), validated.sizeBytes());
        } catch (IllegalArgumentException invalidBase64) {
            throw invalidContent();
        }
    }

    private boolean isBase64Syntax(String encoded) {
        if (encoded.length() % 4 != 0) {
            return false;
        }
        int paddingStart = encoded.length();
        while (paddingStart > 0 && encoded.charAt(paddingStart - 1) == '=') {
            paddingStart--;
        }
        if (encoded.length() - paddingStart > 2) {
            return false;
        }
        for (int index = 0; index < paddingStart; index++) {
            char value = encoded.charAt(index);
            if (!(value >= 'A' && value <= 'Z')
                    && !(value >= 'a' && value <= 'z')
                    && !(value >= '0' && value <= '9')
                    && value != '+' && value != '/') {
                return false;
            }
        }
        return true;
    }

    private List<ObjectNode> imageParts(JsonNode body) {
        List<ObjectNode> images = new ArrayList<>();
        body.path("messages").forEach(message -> message.path("content").forEach(part -> {
            if (part.isObject() && "image_url".equals(part.path("type").asText())
                    && part.path("image_url").isObject()) {
                images.add((ObjectNode) part.path("image_url"));
            }
        }));
        return images;
    }

    private String dataUri(String mimeType, byte[] content) {
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(content);
    }

    private ResponseStatusException invalidRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_responses_request");
    }

    private ResponseStatusException invalidContent() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_file_content");
    }

    private ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large");
    }

    private ResponseStatusException imagesTooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "images_too_large");
    }

    private record RequestSeed(String model, boolean stream) { }

    private record HydratedImage(String dataUri, long sizeBytes) { }

    public record PreparedResponseRequest(
            ObjectNode chatBody,
            List<FileObjectRepository.FileObject> files,
            Set<ChannelRouter.Capability> capabilities,
            boolean stream) {
        public PreparedResponseRequest {
            files = List.copyOf(files);
            capabilities = Set.copyOf(capabilities);
        }
    }
}

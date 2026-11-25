package com.yuzhihuihe.apiplatform.gateway.multimodal;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.Base64;
import java.util.Locale;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectRepository;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import com.yuzhihuihe.apiplatform.gateway.safety.FileSafetyResult;
import com.yuzhihuihe.apiplatform.gateway.safety.FileSafetyScanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.core.scheduler.Scheduler;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class MultimodalRequestNormalizer {

    private final MultimodalInputClassifier classifier;
    private final DocumentExtractionService extractionService;
    private final FileSafetyScanner fileSafetyScanner;
    private final Scheduler preprocessingScheduler;

    @Autowired
    public MultimodalRequestNormalizer(
            MultimodalInputClassifier classifier,
            DocumentExtractionService extractionService,
            FileSafetyScanner fileSafetyScanner,
            @Qualifier("multimodalPreprocessingScheduler") Scheduler preprocessingScheduler) {
        this.classifier = classifier;
        this.extractionService = extractionService;
        this.fileSafetyScanner = fileSafetyScanner;
        this.preprocessingScheduler = preprocessingScheduler;
    }

    public MultimodalRequestNormalizer(
            MultimodalInputClassifier classifier,
            DocumentExtractionService extractionService,
            FileSafetyScanner fileSafetyScanner) {
        this(classifier, extractionService, fileSafetyScanner, Schedulers.immediate());
    }

    public Mono<NormalizedRequest> normalize(
            ObjectNode body, ApiKeyService.ApiKeyContext context) {
        return classifier.classifyResolved(body, context)
                .flatMap(classification -> Flux.fromIterable(classification.files().values())
                        .concatMap(file -> extractionService.extract(file)
                                .map(result -> Map.entry(file.externalFileId(), result)))
                        .collect(LinkedHashMap<String, NormalizedAttachment>::new,
                                (results, entry) -> results.put(entry.getKey(), entry.getValue()))
                        .flatMap(results -> Mono.fromCallable(
                                        () -> normalize(body, classification, results))
                                .subscribeOn(preprocessingScheduler)));
    }

    private NormalizedRequest normalize(
            ObjectNode source,
            MultimodalInputClassifier.Classification classification,
            Map<String, NormalizedAttachment> attachments) {
        ObjectNode normalized = source.deepCopy();
        boolean finalImage = false;
        for (JsonNode messageNode : normalized.withArray("messages")) {
            ObjectNode message = (ObjectNode) messageNode;
            JsonNode content = message.get("content");
            if (!content.isArray()) {
                continue;
            }
            ArrayNode replacement = normalized.arrayNode();
            for (JsonNode part : content) {
                String type = part.path("type").asText();
                switch (type) {
                    case "text", "input_text" -> replacement.addObject()
                            .put("type", "text").put("text", part.path("text").asText());
                    case "image_url", "input_image" -> {
                        replacement.add(originalImagePart(part));
                        finalImage = true;
                    }
                    case "input_file", "file_id" -> {
                        String fileId = part.path("file_id").asText();
                        FileObjectRepository.FileObject file = classification.files().get(fileId);
                        NormalizedAttachment attachment = attachments.get(fileId);
                        if (file == null || attachment == null) {
                            throw new ResponseStatusException(
                                    HttpStatus.NOT_FOUND, "file_not_found");
                        }
                        if (!attachment.normalizedText().isBlank()) {
                            replacement.addObject().put("type", "text")
                                    .put("text", delimited(file, attachment.normalizedText()));
                        }
                        for (String dataUri : attachment.imageDataUris()) {
                            if (!dataUri.startsWith("data:image/")) {
                                throw invalidContent();
                            }
                            replacement.add(validatedImagePart(dataUri));
                            finalImage = true;
                        }
                        if (attachment.normalizedText().isBlank()
                                && attachment.imageDataUris().isEmpty()) {
                            throw invalidContent();
                        }
                    }
                    default -> throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "invalid_multimodal_request");
                }
            }
            message.set("content", replacement);
        }

        Set<ChannelRouter.Capability> capabilities = new LinkedHashSet<>();
        capabilities.add(ChannelRouter.Capability.TEXT);
        if (finalImage) {
            capabilities.add(ChannelRouter.Capability.IMAGE);
        }
        MultimodalInputClassifier.InputProfile effectiveProfile = classification.profile();
        if (finalImage && effectiveProfile == MultimodalInputClassifier.InputProfile.TEXT_FILE) {
            effectiveProfile = MultimodalInputClassifier.InputProfile.TEXT_IMAGE_FILE;
        }
        String routingModel = finalImage ? "vision-basic" : "text-basic";
        return new NormalizedRequest(
                normalized, effectiveProfile, source.path("model").asText(), routingModel,
                Set.copyOf(capabilities));
    }

    private ObjectNode imagePart(String dataUri) {
        ObjectNode part = JsonNodeFactory.instance.objectNode();
        part.put("type", "image_url").putObject("image_url").put("url", dataUri);
        return part;
    }

    private ObjectNode originalImagePart(JsonNode sourcePart) {
        ObjectNode normalized = validatedImagePart(imageUrl(sourcePart));
        JsonNode sourceImageUrl = sourcePart.get("image_url");
        if (sourceImageUrl.isObject() && sourceImageUrl.has("detail")) {
            ((ObjectNode) normalized.get("image_url"))
                    .put("detail", sourceImageUrl.path("detail").asText());
        }
        return normalized;
    }

    private String imageUrl(JsonNode part) {
        JsonNode value = part.get("image_url");
        String url = value.isTextual() ? value.asText() : value.path("url").asText();
        if (!url.startsWith("data:image/")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid_multimodal_request");
        }
        return url;
    }

    private ObjectNode validatedImagePart(String dataUri) {
        InlineImage image = decodeImage(dataUri);
        FileSafetyResult result = fileSafetyScanner.scan(
                image.bytes(), "inline." + image.extension(), image.mimeType());
        if (result.result() == FileSafetyResult.Result.UNAVAILABLE) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "content_safety_unavailable");
        }
        if (result.result() != FileSafetyResult.Result.ALLOW) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, result.reasonCode());
        }
        return imagePart(dataUri);
    }

    private InlineImage decodeImage(String dataUri) {
        int separator = dataUri.indexOf(',');
        if (separator <= 0 || separator == dataUri.length() - 1) {
            throw invalidContent();
        }
        String metadata = dataUri.substring(5, separator).toLowerCase(Locale.ROOT);
        String mimeType;
        String extension;
        switch (metadata) {
            case "image/png;base64" -> {
                mimeType = "image/png";
                extension = "png";
            }
            case "image/jpeg;base64", "image/jpg;base64" -> {
                mimeType = "image/jpeg";
                extension = "jpg";
            }
            case "image/gif;base64" -> {
                mimeType = "image/gif";
                extension = "gif";
            }
            case "image/webp;base64" -> {
                mimeType = "image/webp";
                extension = "webp";
            }
            default -> throw invalidContent();
        }
        try {
            return new InlineImage(
                    Base64.getDecoder().decode(dataUri.substring(separator + 1)),
                    mimeType,
                    extension);
        } catch (IllegalArgumentException invalidBase64) {
            throw invalidContent();
        }
    }

    private String delimited(
            FileObjectRepository.FileObject file, String normalizedText) {
        return "BEGIN_UNTRUSTED_DOCUMENT file_id=" + safe(file.externalFileId())
                + " filename=" + safe(file.originalFilename()) + "\n"
                + normalizedText
                + "\nEND_UNTRUSTED_DOCUMENT";
    }

    private String safe(String value) {
        return value == null ? "unknown"
                : value.replace('\r', ' ').replace('\n', ' ').strip();
    }

    private ResponseStatusException invalidContent() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_file_content");
    }

    private record InlineImage(byte[] bytes, String mimeType, String extension) {
        private InlineImage {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record NormalizedRequest(
            ObjectNode body,
            MultimodalInputClassifier.InputProfile profile,
            String publicModel,
            String routingModel,
            Set<ChannelRouter.Capability> finalCapabilities) {

        public NormalizedRequest {
            body = body.deepCopy();
            finalCapabilities = Set.copyOf(finalCapabilities);
        }
    }
}

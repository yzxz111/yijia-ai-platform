package com.yuzhihuihe.apiplatform.gateway.multimodal;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.file.FileContentValidator;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectRepository;
import com.yuzhihuihe.apiplatform.gateway.file.FileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
public class MultimodalInputClassifier {

    private static final Set<String> DOCUMENT_MIME_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/csv",
            "application/csv",
            "text/plain",
            "text/markdown",
            "text/x-markdown");
    private static final Set<String> IMAGE_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp");

    private final FileService fileService;
    private final MultimodalProperties properties;
    private final FileContentValidator contentValidator;
    private final Scheduler preprocessingScheduler;

    @Autowired
    public MultimodalInputClassifier(
            FileService fileService,
            MultimodalProperties properties,
            FileContentValidator contentValidator,
            @Qualifier("multimodalPreprocessingScheduler") Scheduler preprocessingScheduler) {
        this.fileService = fileService;
        this.properties = properties;
        this.contentValidator = contentValidator;
        this.preprocessingScheduler = preprocessingScheduler;
    }

    public MultimodalInputClassifier(
            FileService fileService,
            MultimodalProperties properties,
            FileContentValidator contentValidator) {
        this(fileService, properties, contentValidator, Schedulers.immediate());
    }

    MultimodalInputClassifier(FileService fileService, MultimodalProperties properties) {
        this(fileService, properties, new FileContentValidator(), Schedulers.immediate());
    }

    public Mono<InputProfile> classify(
            ObjectNode body, ApiKeyService.ApiKeyContext context) {
        return classifyResolved(body, context).map(Classification::profile);
    }

    Mono<Classification> classifyResolved(
            ObjectNode body, ApiKeyService.ApiKeyContext context) {
        return Mono.defer(() -> {
            Scan scan = scan(body);
            if (scan.attachmentCount() > properties.getMaxAttachments()) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "file_complexity_limit"));
            }
            return Flux.fromIterable(scan.fileIds())
                    .distinct()
                    .concatMap(fileId -> fileService.requireUsable(context, fileId)
                            .map(file -> Map.entry(fileId, file)))
                    .collect(LinkedHashMap<String, FileObjectRepository.FileObject>::new,
                            (files, entry) -> files.put(entry.getKey(), entry.getValue()))
                    .map(files -> classify(scan, files));
        }).subscribeOn(preprocessingScheduler);
    }

    private Scan scan(ObjectNode body) {
        if (body == null
                || !"multimodal-basic".equals(body.path("model").asText())
                || !body.path("messages").isArray()
                || body.path("messages").isEmpty()) {
            throw invalidRequest();
        }
        boolean image = false;
        int attachments = 0;
        java.util.ArrayList<String> fileIds = new java.util.ArrayList<>();
        for (JsonNode message : body.path("messages")) {
            if (!message.isObject()
                    || !message.path("role").isTextual()
                    || message.path("role").asText().isBlank()) {
                throw invalidRequest();
            }
            JsonNode content = message.get("content");
            if (content == null || content.isNull()) {
                throw invalidRequest();
            }
            if (content.isTextual()) {
                if (content.asText().isBlank()) {
                    throw invalidRequest();
                }
                continue;
            }
            if (!content.isArray() || content.isEmpty()) {
                throw invalidRequest();
            }
            for (JsonNode part : content) {
                if (!part.isObject() || !part.path("type").isTextual()) {
                    throw invalidRequest();
                }
                String type = part.path("type").asText();
                switch (type) {
                    case "text", "input_text" -> validateTextPart(part);
                    case "image_url", "input_image" -> {
                        validateImagePart(part);
                        image = true;
                        attachments++;
                    }
                    case "input_file", "file_id" -> {
                        validateFilePart(part);
                        fileIds.add(part.path("file_id").asText());
                        attachments++;
                    }
                    default -> throw invalidRequest();
                }
            }
        }
        return new Scan(image, attachments, List.copyOf(fileIds));
    }

    private Classification classify(
            Scan scan, Map<String, FileObjectRepository.FileObject> files) {
        boolean image = scan.image();
        boolean document = false;
        for (String fileId : scan.fileIds()) {
            FileObjectRepository.FileObject file = files.get(fileId);
            if (file == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "file_not_found");
            }
            String mimeType = normalizeMime(file.mimeType());
            if (IMAGE_MIME_TYPES.contains(mimeType)) {
                image = true;
            } else if (DOCUMENT_MIME_TYPES.contains(mimeType)) {
                document = true;
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_file_content");
            }
        }
        InputProfile profile = image
                ? document ? InputProfile.TEXT_IMAGE_FILE : InputProfile.TEXT_IMAGE
                : document ? InputProfile.TEXT_FILE : InputProfile.TEXT;
        return new Classification(profile, files, scan.fileIds());
    }

    private void validateTextPart(JsonNode part) {
        if (!hasOnly(part, "type", "text")
                || !part.path("text").isTextual()
                || part.path("text").asText().isBlank()) {
            throw invalidRequest();
        }
    }

    private void validateImagePart(JsonNode part) {
        if (!hasOnly(part, "type", "image_url")) {
            throw invalidRequest();
        }
        JsonNode value = part.get("image_url");
        String url;
        if (value != null && value.isTextual()) {
            url = value.asText();
        } else if (value != null && value.isObject()
                && hasOnly(value, "url", "detail") && value.path("url").isTextual()) {
            JsonNode detail = value.get("detail");
            if (detail != null && (!detail.isTextual() || detail.asText().isBlank())) {
                throw invalidRequest();
            }
            url = value.path("url").asText();
        } else {
            throw invalidRequest();
        }
        validateImageDataUri(url);
    }

    private void validateFilePart(JsonNode part) {
        if (!hasOnly(part, "type", "file_id")
                || !part.path("file_id").isTextual()
                || part.path("file_id").asText().isBlank()) {
            throw invalidRequest();
        }
    }

    private void validateImageDataUri(String value) {
        if (value == null || !value.startsWith("data:image/")) {
            throw invalidRequest();
        }
        int separator = value.indexOf(";base64,");
        if (separator <= "data:image/".length()) {
            throw invalidRequest();
        }
        String mimeType = value.substring(5, separator).toLowerCase(java.util.Locale.ROOT);
        if (!IMAGE_MIME_TYPES.contains(mimeType)) {
            throw invalidRequest();
        }
        try {
            byte[] content = Base64.getDecoder().decode(value.substring(separator + 8));
            contentValidator.validateImage(
                    mimeType, content, properties.getMaxConvertedOutput().toBytes());
        } catch (IllegalArgumentException invalidBase64) {
            throw invalidRequest();
        }
    }

    private boolean hasOnly(JsonNode object, String... fields) {
        Set<String> allowed = Set.of(fields);
        java.util.Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                return false;
            }
        }
        return true;
    }

    private String normalizeMime(String value) {
        if (value == null) {
            return "";
        }
        int parameters = value.indexOf(';');
        return (parameters < 0 ? value : value.substring(0, parameters))
                .strip().toLowerCase(java.util.Locale.ROOT);
    }

    private ResponseStatusException invalidRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_multimodal_request");
    }

    public enum InputProfile {
        TEXT,
        TEXT_IMAGE,
        TEXT_FILE,
        TEXT_IMAGE_FILE
    }

    record Classification(
            InputProfile profile,
            Map<String, FileObjectRepository.FileObject> files,
            List<String> fileIds) {

        Classification {
            files = Map.copyOf(files);
            fileIds = List.copyOf(fileIds);
        }
    }

    private record Scan(boolean image, int attachmentCount, List<String> fileIds) { }
}

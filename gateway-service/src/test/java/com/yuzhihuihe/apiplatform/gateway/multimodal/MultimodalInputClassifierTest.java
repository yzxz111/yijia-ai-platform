package com.yuzhihuihe.apiplatform.gateway.multimodal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectRepository;
import com.yuzhihuihe.apiplatform.gateway.file.FileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class MultimodalInputClassifierTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final ApiKeyService.ApiKeyContext CONTEXT = new ApiKeyService.ApiKeyContext(
            UUID.fromString("30000000-0000-4000-8000-000000000001"),
            TENANT_ID, USER_ID,
            UUID.fromString("40000000-0000-4000-8000-000000000001"),
            Set.of("multimodal-basic"));

    @Test
    void classifiesTextImageFileAndMixedAcrossCompleteHistory() {
        FileService files = mock(FileService.class);
        when(files.requireUsable(CONTEXT, "file_pdf"))
                .thenReturn(Mono.just(file("file_pdf", "report.pdf", "application/pdf")));
        MultimodalInputClassifier classifier = classifier(files, new MultimodalProperties());

        assertThat(classifier.classify(textRequest(), CONTEXT).block())
                .isEqualTo(MultimodalInputClassifier.InputProfile.TEXT);
        assertThat(classifier.classify(imageRequest(), CONTEXT).block())
                .isEqualTo(MultimodalInputClassifier.InputProfile.TEXT_IMAGE);
        assertThat(classifier.classify(fileRequest("file_pdf"), CONTEXT).block())
                .isEqualTo(MultimodalInputClassifier.InputProfile.TEXT_FILE);

        ObjectNode mixed = fileRequest("file_pdf");
        ArrayNode current = mixed.withArray("messages").addObject()
                .put("role", "user").putArray("content");
        current.addObject().put("type", "text").put("text", "compare it");
        current.addObject().put("type", "image_url")
                .putObject("image_url").put("url", pngDataUri());
        assertThat(classifier.classify(mixed, CONTEXT).block())
                .isEqualTo(MultimodalInputClassifier.InputProfile.TEXT_IMAGE_FILE);
    }

    @Test
    void acceptsObjectImageUrlDetailWithoutMutatingRequest() {
        ObjectNode imagePart = JSON.createObjectNode().put("type", "image_url");
        imagePart.putObject("image_url")
                .put("url", pngDataUri())
                .put("detail", "low");
        ObjectNode request = requestWithPart(imagePart);
        ObjectNode original = request.deepCopy();

        assertThat(classifier(mock(FileService.class), new MultimodalProperties())
                .classify(request, CONTEXT).block())
                .isEqualTo(MultimodalInputClassifier.InputProfile.TEXT_IMAGE);
        assertThat(request).isEqualTo(original);
    }

    @Test
    void acceptsNonstandardNonblankObjectImageUrlDetail() {
        ObjectNode imagePart = JSON.createObjectNode().put("type", "image_url");
        imagePart.putObject("image_url")
                .put("url", pngDataUri())
                .put("detail", "vendor-specific");

        assertThat(classifier(mock(FileService.class), new MultimodalProperties())
                .classify(requestWithPart(imagePart), CONTEXT).block())
                .isEqualTo(MultimodalInputClassifier.InputProfile.TEXT_IMAGE);
    }

    @Test
    void rejectsInvalidObjectImageUrlDetailsAndUnknownFields() {
        ObjectNode nullDetail = JSON.createObjectNode().put("url", pngDataUri()).putNull("detail");
        ObjectNode numericDetail = JSON.createObjectNode().put("url", pngDataUri()).put("detail", 1);
        ObjectNode blankDetail = JSON.createObjectNode().put("url", pngDataUri()).put("detail", " ");
        ObjectNode unknownField = JSON.createObjectNode().put("url", pngDataUri()).put("extra", true);
        MultimodalInputClassifier classifier = classifier(
                mock(FileService.class), new MultimodalProperties());

        for (ObjectNode imageUrl : java.util.List.of(
                nullDetail, numericDetail, blankDetail, unknownField)) {
            ObjectNode imagePart = JSON.createObjectNode().put("type", "image_url");
            imagePart.set("image_url", imageUrl);
            assertError(
                    () -> classifier.classify(requestWithPart(imagePart), CONTEXT).block(),
                    HttpStatus.BAD_REQUEST,
                    "invalid_multimodal_request");
        }
    }

    @Test
    void classifiesUploadedImageFilesByStoredMimeRatherThanPartLabel() {
        FileService files = mock(FileService.class);
        when(files.requireUsable(CONTEXT, "file_image"))
                .thenReturn(Mono.just(file("file_image", "diagram.png", "image/png")));
        MultimodalInputClassifier classifier = classifier(files, new MultimodalProperties());

        assertThat(classifier.classify(fileRequest("file_image"), CONTEXT).block())
                .isEqualTo(MultimodalInputClassifier.InputProfile.TEXT_IMAGE);
    }

    @Test
    void rejectsMalformedAmbiguousRawLocalAndRemotePartsWithoutDowngrade() {
        MultimodalInputClassifier classifier = classifier(
                mock(FileService.class), new MultimodalProperties());
        ObjectNode missingText = requestWithPart(JSON.createObjectNode().put("type", "text"));
        ObjectNode rawBytes = requestWithPart(JSON.createObjectNode()
                .put("type", "input_file").put("file_id", "file_1").put("file_data", "AAAA"));
        ObjectNode localPath = requestWithPart(JSON.createObjectNode()
                .put("type", "input_image").put("image_url", "file:///tmp/private.png"));
        ObjectNode remoteUrl = requestWithPart(JSON.createObjectNode()
                .put("type", "image_url").putObject("image_url")
                .put("url", "https://example.test/private.png"));
        ObjectNode unknown = requestWithPart(JSON.createObjectNode()
                .put("type", "audio").put("url", "data:audio/wav;base64,AAAA"));

        for (ObjectNode invalid : java.util.List.of(
                missingText, rawBytes, localPath, remoteUrl, unknown)) {
            assertError(
                    () -> classifier.classify(invalid, CONTEXT).block(),
                    HttpStatus.BAD_REQUEST,
                    "invalid_multimodal_request");
        }
    }

    @Test
    void propagatesExpiredOrForeignFileAsFileNotFound() {
        FileService files = mock(FileService.class);
        when(files.requireUsable(CONTEXT, "file_foreign"))
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "file_not_found")));

        assertError(
                () -> classifier(files, new MultimodalProperties())
                        .classify(fileRequest("file_foreign"), CONTEXT).block(),
                HttpStatus.NOT_FOUND,
                "file_not_found");
    }

    @Test
    void rejectsTooManyAttachmentsBeforeResolvingFiles() {
        MultimodalProperties properties = new MultimodalProperties();
        properties.setMaxAttachments(2);
        ObjectNode request = textRequest();
        ArrayNode parts = (ArrayNode) request.withArray("messages").get(0).path("content");
        parts.addObject().put("type", "input_image").put("image_url", pngDataUri());
        parts.addObject().put("type", "input_image").put("image_url", pngDataUri());
        parts.addObject().put("type", "input_image").put("image_url", pngDataUri());

        assertError(
                () -> classifier(mock(FileService.class), properties)
                        .classify(request, CONTEXT).block(),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "file_complexity_limit");
    }

    @Test
    void rejectsUnknownStoredMimeInsteadOfTreatingItAsText() {
        FileService files = mock(FileService.class);
        when(files.requireUsable(CONTEXT, "file_binary"))
                .thenReturn(Mono.just(file(
                        "file_binary", "archive.bin", "application/octet-stream")));

        assertError(
                () -> classifier(files, new MultimodalProperties())
                        .classify(fileRequest("file_binary"), CONTEXT).block(),
                HttpStatus.BAD_REQUEST,
                "invalid_file_content");
    }

    @Test
    void rejectsDataImagesWhoseDeclaredMimeDoesNotMatchTheSignature() {
        ObjectNode mismatched = requestWithPart(JSON.createObjectNode()
                .put("type", "input_image")
                .put("image_url", "data:image/jpeg;base64,iVBORw0KGgo="));

        assertError(
                () -> classifier(mock(FileService.class), new MultimodalProperties())
                        .classify(mismatched, CONTEXT).block(),
                HttpStatus.BAD_REQUEST,
                "invalid_file_content");
    }

    private static MultimodalInputClassifier classifier(
            FileService files, MultimodalProperties properties) {
        return new MultimodalInputClassifier(files, properties);
    }

    private static ObjectNode textRequest() {
        ObjectNode body = JSON.createObjectNode().put("model", "multimodal-basic");
        ArrayNode parts = body.putArray("messages").addObject()
                .put("role", "user").putArray("content");
        parts.addObject().put("type", "text").put("text", "hello");
        return body;
    }

    private static ObjectNode imageRequest() {
        ObjectNode body = textRequest();
        ArrayNode parts = (ArrayNode) body.withArray("messages").get(0).path("content");
        parts.addObject().put("type", "image_url")
                .putObject("image_url").put("url", pngDataUri());
        return body;
    }

    private static ObjectNode fileRequest(String fileId) {
        ObjectNode body = textRequest();
        ArrayNode previous = (ArrayNode) body.withArray("messages").get(0).path("content");
        previous.addObject().put("type", "input_file").put("file_id", fileId);
        body.withArray("messages").addObject().put("role", "user").put("content", "continue");
        return body;
    }

    private static ObjectNode requestWithPart(ObjectNode part) {
        ObjectNode body = JSON.createObjectNode().put("model", "multimodal-basic");
        body.putArray("messages").addObject().put("role", "user")
                .putArray("content").add(part);
        return body;
    }

    private static FileObjectRepository.FileObject file(
            String externalId, String filename, String mimeType) {
        return new FileObjectRepository.FileObject(
                UUID.randomUUID(), externalId, TENANT_ID, USER_ID,
                "files/" + UUID.randomUUID(), filename, "0".repeat(64), mimeType, 10,
                "READY", Instant.now().plus(Duration.ofHours(1)), null, Instant.now());
    }

    private static String pngDataUri() {
        return "data:image/png;base64,iVBORw0KGgo=";
    }

    private static void assertError(Runnable action, HttpStatus status, String reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(status);
                    assertThat(response.getReason()).isEqualTo(reason);
                });
    }
}

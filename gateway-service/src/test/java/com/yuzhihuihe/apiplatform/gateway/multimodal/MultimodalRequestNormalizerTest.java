package com.yuzhihuihe.apiplatform.gateway.multimodal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectRepository;
import com.yuzhihuihe.apiplatform.gateway.file.FileService;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import com.yuzhihuihe.apiplatform.gateway.safety.FileSafetyResult;
import com.yuzhihuihe.apiplatform.gateway.safety.FileSafetyScanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class MultimodalRequestNormalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final ApiKeyService.ApiKeyContext CONTEXT = new ApiKeyService.ApiKeyContext(
            UUID.fromString("30000000-0000-4000-8000-000000000001"),
            TENANT_ID, USER_ID,
            UUID.fromString("40000000-0000-4000-8000-000000000001"),
            Set.of("multimodal-basic"));

    @Test
    void deepCopiesAndNormalizesMixedHistoryWithoutReorderingMessagesOrParts() {
        FileService files = mock(FileService.class);
        DocumentExtractionService extraction = mock(DocumentExtractionService.class);
        FileObjectRepository.FileObject pdf = file("file_pdf", "scan.pdf", "application/pdf");
        when(files.requireUsable(CONTEXT, "file_pdf")).thenReturn(Mono.just(pdf));
        when(extraction.extract(pdf)).thenReturn(Mono.just(new NormalizedAttachment(
                "file_pdf", "scan.pdf", NormalizedAttachment.Kind.TEXT_AND_IMAGE,
                "[file=scan.pdf page=1]\ninvoice total 42",
                List.of("data:image/png;base64,iVBORw0KGgo="),
                Map.of("pages", 1))));
        ObjectNode original = mixedRequest();
        ObjectNode snapshot = original.deepCopy();

        MultimodalRequestNormalizer.NormalizedRequest result = normalizer(files, extraction)
                .normalize(original, CONTEXT).block();

        assertThat(original).isEqualTo(snapshot);
        assertThat(result.profile())
                .isEqualTo(MultimodalInputClassifier.InputProfile.TEXT_IMAGE_FILE);
        assertThat(result.publicModel()).isEqualTo("multimodal-basic");
        assertThat(result.routingModel()).isEqualTo("vision-basic");
        assertThat(result.finalCapabilities()).containsExactlyInAnyOrder(
                ChannelRouter.Capability.TEXT, ChannelRouter.Capability.IMAGE);
        assertThat(result.body().path("messages")).hasSize(2);
        ArrayNode priorParts = (ArrayNode) result.body().path("messages").get(0).path("content");
        assertThat(priorParts.get(0).path("text").asText()).isEqualTo("review");
        assertThat(priorParts.get(1).path("type").asText()).isEqualTo("text");
        assertThat(priorParts.get(1).path("text").asText())
                .contains("BEGIN_UNTRUSTED_DOCUMENT")
                .contains("invoice total 42")
                .contains("END_UNTRUSTED_DOCUMENT");
        assertThat(priorParts.get(2).path("image_url").path("url").asText())
                .startsWith("data:image/png;base64,");
        assertThat(priorParts.get(2).path("image_url").has("detail")).isFalse();
        JsonNode currentImage = result.body().path("messages").get(1)
                .path("content").get(1).path("image_url");
        assertThat(currentImage.path("url").asText()).isEqualTo(pngDataUri());
        assertThat(currentImage.path("detail").asText()).isEqualTo(" vendor-specific ");
    }

    @Test
    void preservesDetailedOriginalImageWithoutAddingDetailToUrlOnlyOriginalImage() {
        FileService files = mock(FileService.class);
        DocumentExtractionService extraction = mock(DocumentExtractionService.class);
        ObjectNode body = JSON.createObjectNode().put("model", "multimodal-basic");
        ArrayNode first = body.putArray("messages").addObject()
                .put("role", "user").putArray("content");
        first.addObject().put("type", "image_url").putObject("image_url")
                .put("url", pngDataUri()).put("detail", "low");
        ArrayNode second = body.withArray("messages").addObject()
                .put("role", "user").putArray("content");
        second.addObject().put("type", "input_image").put("image_url", pngDataUri());

        MultimodalRequestNormalizer.NormalizedRequest result = normalizer(files, extraction)
                .normalize(body, CONTEXT).block();

        JsonNode detailed = result.body().path("messages").get(0)
                .path("content").get(0).path("image_url");
        assertThat(detailed.path("url").asText()).isEqualTo(pngDataUri());
        assertThat(detailed.path("detail").asText()).isEqualTo("low");
        JsonNode urlOnly = result.body().path("messages").get(1)
                .path("content").get(0).path("image_url");
        assertThat(urlOnly.path("url").asText()).isEqualTo(pngDataUri());
        assertThat(urlOnly.has("detail")).isFalse();
    }

    @Test
    void mapsFileOnlyFinalStageToTextModelAndRemovesFileCapability() {
        FileService files = mock(FileService.class);
        DocumentExtractionService extraction = mock(DocumentExtractionService.class);
        FileObjectRepository.FileObject document = file("file_doc", "notes.txt", "text/plain");
        when(files.requireUsable(CONTEXT, "file_doc")).thenReturn(Mono.just(document));
        when(extraction.extract(document)).thenReturn(Mono.just(new NormalizedAttachment(
                "file_doc", "notes.txt", NormalizedAttachment.Kind.TEXT,
                "[file=notes.txt]\nhello", List.of(), Map.of())));
        ObjectNode body = textWithFile("file_doc");

        MultimodalRequestNormalizer.NormalizedRequest result = normalizer(files, extraction)
                .normalize(body, CONTEXT).block();

        assertThat(result.profile())
                .isEqualTo(MultimodalInputClassifier.InputProfile.TEXT_FILE);
        assertThat(result.routingModel()).isEqualTo("text-basic");
        assertThat(result.finalCapabilities()).containsExactly(ChannelRouter.Capability.TEXT);
        assertThat(result.body().findValues("file_id")).isEmpty();
    }

    @Test
    void replacesUploadedImageFileWithValidatedDataUri() {
        FileService files = mock(FileService.class);
        DocumentExtractionService extraction = mock(DocumentExtractionService.class);
        FileObjectRepository.FileObject image = file("file_image", "plot.png", "image/png");
        when(files.requireUsable(CONTEXT, "file_image")).thenReturn(Mono.just(image));
        when(extraction.extract(image)).thenReturn(Mono.just(new NormalizedAttachment(
                "file_image", "plot.png", NormalizedAttachment.Kind.IMAGE,
                "", List.of(pngDataUri()), Map.of())));

        MultimodalRequestNormalizer.NormalizedRequest result = normalizer(files, extraction)
                .normalize(textWithFile("file_image"), CONTEXT).block();

        assertThat(result.profile())
                .isEqualTo(MultimodalInputClassifier.InputProfile.TEXT_IMAGE);
        assertThat(result.routingModel()).isEqualTo("vision-basic");
        JsonNode imageUrl = result.body().path("messages").get(0)
                .path("content").get(1).path("image_url");
        assertThat(imageUrl.path("url").asText()).isEqualTo(pngDataUri());
        assertThat(imageUrl.has("detail")).isFalse();
    }

    @Test
    void blocksUnsafeInlineImagesBeforeForwarding() {
        FileService files = mock(FileService.class);
        DocumentExtractionService extraction = mock(DocumentExtractionService.class);
        FileSafetyScanner scanner = mock(FileSafetyScanner.class);
        when(scanner.scan(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("image/png")))
                .thenReturn(FileSafetyResult.block("unsafe_file", 8));
        ObjectNode body = JSON.createObjectNode().put("model", "multimodal-basic");
        body.putArray("messages").addObject().put("role", "user").putArray("content")
                .addObject().put("type", "input_image").put("image_url", pngDataUri());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        normalizer(files, extraction, scanner).normalize(body, CONTEXT).block())
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        error -> {
                            assertThat(error.getStatusCode()).isEqualTo(
                                    org.springframework.http.HttpStatus.BAD_REQUEST);
                            assertThat(error.getReason()).isEqualTo("unsafe_file");
                        });
    }

    private static MultimodalRequestNormalizer normalizer(
            FileService files, DocumentExtractionService extraction) {
        FileSafetyScanner scanner = mock(FileSafetyScanner.class);
        when(scanner.scan(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> FileSafetyResult.allow(
                        ((byte[]) invocation.getArgument(0)).length));
        return normalizer(files, extraction, scanner);
    }

    private static MultimodalRequestNormalizer normalizer(
            FileService files,
            DocumentExtractionService extraction,
            FileSafetyScanner scanner) {
        MultimodalProperties properties = new MultimodalProperties();
        return new MultimodalRequestNormalizer(
                new MultimodalInputClassifier(files, properties), extraction, scanner);
    }

    private static ObjectNode mixedRequest() {
        ObjectNode body = JSON.createObjectNode().put("model", "multimodal-basic");
        ArrayNode prior = body.putArray("messages").addObject()
                .put("role", "user").putArray("content");
        prior.addObject().put("type", "text").put("text", "review");
        prior.addObject().put("type", "input_file").put("file_id", "file_pdf");
        ArrayNode current = body.withArray("messages").addObject()
                .put("role", "user").putArray("content");
        current.addObject().put("type", "input_text").put("text", "compare");
        current.addObject().put("type", "input_image").putObject("image_url")
                .put("url", pngDataUri()).put("detail", " vendor-specific ");
        return body;
    }

    private static ObjectNode textWithFile(String fileId) {
        ObjectNode body = JSON.createObjectNode().put("model", "multimodal-basic");
        ArrayNode parts = body.putArray("messages").addObject()
                .put("role", "user").putArray("content");
        parts.addObject().put("type", "text").put("text", "summarize");
        parts.addObject().put("type", "input_file").put("file_id", fileId);
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
}

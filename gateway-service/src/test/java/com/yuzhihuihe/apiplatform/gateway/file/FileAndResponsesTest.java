package com.yuzhihuihe.apiplatform.gateway.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.openai.OpenAiExceptionHandler;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import com.yuzhihuihe.apiplatform.gateway.safety.ContentSafetyOrchestrator;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import com.yuzhihuihe.apiplatform.gateway.security.RemoteUrlPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

class FileAndResponsesTest {

    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID PACKAGE_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-31T00:00:00Z");
    private static final ApiKeyService.ApiKeyContext API_CONTEXT = new ApiKeyService.ApiKeyContext(
            UUID.fromString("40000000-0000-4000-8000-000000000001"),
            TENANT_ID, USER_ID, PACKAGE_ID, Set.of("text-basic"));

    @Test
    void acceptsAFileOnlyWhenExtensionMimeAndSignatureAgree() {
        byte[] pdf = "%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII);

        FileContentValidator.ValidatedContent validated = new FileContentValidator()
                .validateDocument("contract.pdf", "application/pdf", pdf, 1024);

        assertThat(validated.mimeType()).isEqualTo("application/pdf");
        assertThat(validated.sizeBytes()).isEqualTo(pdf.length);
        assertThat(validated.sha256()).hasSize(64);
    }

    @Test
    void rejectsDocumentExtensionMimeOrSignatureMismatches() {
        FileContentValidator validator = new FileContentValidator();
        byte[] pdf = "%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII);

        assertInvalidContent(() -> validator.validateDocument(
                "contract.txt", "application/pdf", pdf, 1024));
        assertInvalidContent(() -> validator.validateDocument(
                "contract.pdf", "text/plain", pdf, 1024));
        assertInvalidContent(() -> validator.validateDocument(
                "contract.pdf", "application/pdf", "not a pdf".getBytes(StandardCharsets.UTF_8), 1024));
    }

    @Test
    void rejectsDocxArchivesWithExcessiveEntryTraversal() {
        byte[] docx = docxWithEntries(257);

        assertInvalidContent(() -> new FileContentValidator().validateDocument(
                "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docx, 1024 * 1024));
    }

    @Test
    void acceptsLegacyOfficeXlsxAndCsvWhenContainerAndMetadataAgree() {
        FileContentValidator validator = new FileContentValidator();
        byte[] doc = oleWithStream("WordDocument");
        byte[] xls = oleWithStream("Workbook");
        byte[] xlsx = officeArchive("xl/workbook.xml");
        byte[] csv = "name,amount\nAda,12\n".getBytes(StandardCharsets.UTF_8);

        assertThat(validator.validateDocument(
                "legacy.doc", "application/msword", doc, 1024 * 1024).mimeType())
                .isEqualTo("application/msword");
        assertThat(validator.validateDocument(
                "legacy.xls", "application/vnd.ms-excel", xls, 1024 * 1024).mimeType())
                .isEqualTo("application/vnd.ms-excel");
        assertThat(validator.validateDocument(
                "workbook.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsx, 1024 * 1024).sizeBytes()).isEqualTo(xlsx.length);
        assertThat(validator.validateDocument("data.csv", "text/csv", csv, 1024).mimeType())
                .isEqualTo("text/csv");
        assertThat(validator.validateDocument("data.csv", "application/csv", csv, 1024).mimeType())
                .isEqualTo("application/csv");
    }

    @Test
    void rejectsLegacyOfficeContainersWithoutTheExpectedDirectoryStream() {
        FileContentValidator validator = new FileContentValidator();

        assertInvalidContent(() -> validator.validateDocument(
                "legacy.doc", "application/msword", oleWithStream("Workbook"), 1024 * 1024));
        assertInvalidContent(() -> validator.validateDocument(
                "legacy.xls", "application/vnd.ms-excel", oleWithStream("WordDocument"), 1024 * 1024));
    }

    @Test
    void rejectsOoxmlMacrosAndExternalRelationships() {
        FileContentValidator validator = new FileContentValidator();
        byte[] macro = officeArchive(
                "word/document.xml",
                "word/vbaProject.bin", "macro");
        byte[] externalRelationship = officeArchive(
                "word/document.xml",
                "word/_rels/document.xml.rels",
                "<Relationships><Relationship TargetMode=\"External\" "
                        + "Target=\"https://example.test/payload\"/></Relationships>");

        assertInvalidContent(() -> validator.validateDocument(
                "macro.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                macro, 1024 * 1024));
        assertInvalidContent(() -> validator.validateDocument(
                "linked.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                externalRelationship, 1024 * 1024));
    }

    @Test
    void acceptsImageUploadsOnlyWhenExtensionMimeAndSignatureAgree() throws Exception {
        FileObjectRepository repository = mock(FileObjectRepository.class);
        FileObjectStore objectStore = mock(FileObjectStore.class);
        when(repository.packageEntitlement(TENANT_ID, USER_ID, PACKAGE_ID)).thenReturn(Mono.just(
                new FileObjectRepository.PackageEntitlement("FREE", 5L * 1024 * 1024)));
        when(repository.insert(any())).thenReturn(Mono.empty());
        when(repository.markReady(any())).thenReturn(Mono.empty());
        when(objectStore.put(anyString(), any(), anyString())).thenReturn(Mono.empty());
        FileService service = fileService(repository, objectStore);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", encoded);
        byte[] png = encoded.toByteArray();

        FileObjectRepository.FileObject uploaded = service.upload(
                context(), "diagram.png", "image/png", png).block(Duration.ofSeconds(1));

        assertThat(uploaded).isNotNull();
        assertThat(uploaded.mimeType()).isEqualTo("image/png");
        assertThatThrownBy(() -> service.upload(
                        context(), "diagram.jpg", "image/png", png).block(Duration.ofSeconds(1)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getReason())
                        .isEqualTo("invalid_file_content"));
    }

    @Test
    void offloadsUploadValidationFromTheCallingThread() {
        FileObjectRepository repository = mock(FileObjectRepository.class);
        FileObjectStore store = mock(FileObjectStore.class);
        FileContentValidator validator = mock(FileContentValidator.class);
        byte[] pdf = "%PDF-data".getBytes(StandardCharsets.US_ASCII);
        AtomicReference<String> validationThread = new AtomicReference<>();
        when(repository.packageEntitlement(TENANT_ID, USER_ID, PACKAGE_ID)).thenReturn(Mono.just(
                new FileObjectRepository.PackageEntitlement("FREE", 5L * 1024 * 1024)));
        when(validator.validateUpload("report.pdf", "application/pdf", pdf, 5L * 1024 * 1024))
                .thenAnswer(invocation -> {
                    validationThread.set(Thread.currentThread().getName());
                    return new FileContentValidator.ValidatedContent(
                            "application/pdf", "0".repeat(64), pdf.length);
                });
        when(repository.insert(any())).thenReturn(Mono.empty());
        when(store.put(anyString(), eq(pdf), eq("application/pdf"))).thenReturn(Mono.empty());
        when(repository.markReady(any())).thenReturn(Mono.empty());
        FileService service = fileService(repository, store, validator);

        service.upload(context(), "report.pdf", "application/pdf", pdf)
                .block(Duration.ofSeconds(2));

        assertThat(validationThread.get()).startsWith("boundedElastic-");
    }

    @Test
    void validatesDecodedImageBytesAgainstDeclaredMimeAndLimit() {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1};
        FileContentValidator validator = new FileContentValidator();

        assertThat(validator.validateImage("image/png", png, 32).mimeType())
                .isEqualTo("image/png");
        assertInvalidContent(() -> validator.validateImage("image/jpeg", png, 32));
        assertTooLarge(() -> validator.validateImage("image/png", png, 8));
    }

    @Test
    void enforcesFreeUploadLimitBeforeWritingObjectStorage() {
        FileObjectRepository repository = mock(FileObjectRepository.class);
        FileObjectStore objectStore = mock(FileObjectStore.class);
        when(repository.packageEntitlement(TENANT_ID, USER_ID, PACKAGE_ID)).thenReturn(Mono.just(
                new FileObjectRepository.PackageEntitlement("FREE", 5L * 1024 * 1024)));
        FileService service = fileService(repository, objectStore);
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, oversized, 0, 5);

        assertThatThrownBy(() -> service.upload(
                        context(), "original.pdf", "application/pdf", oversized).block(Duration.ofSeconds(1)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getReason())
                        .isEqualTo("file_too_large"));
        verify(objectStore, never()).put(anyString(), any(), anyString());
    }

    @Test
    void allowsMultipartUploadWithWalletOnlyEntitlement() {
        FileObjectRepository repository = mock(FileObjectRepository.class);
        FileObjectStore objectStore = mock(FileObjectStore.class);
        UploadEntitlementService entitlementService = mock(UploadEntitlementService.class);
        ContentSafetyOrchestrator safety = mock(ContentSafetyOrchestrator.class);
        ApiKeyService.ApiKeyContext walletContext = new ApiKeyService.ApiKeyContext(
                API_CONTEXT.apiKeyId(), TENANT_ID, USER_ID, null, Set.of("text-basic"));
        byte[] pdf = "%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII);
        when(entitlementService.resolve(walletContext)).thenReturn(Mono.just(
                new UploadEntitlementService.UploadEntitlement("WALLET", 20L * 1024 * 1024, null)));
        when(safety.inspectFile(eq(walletContext), eq(pdf), eq("wallet.pdf"),
                eq("application/pdf"), anyString())).thenReturn(Mono.just(
                        new ContentSafetyOrchestrator.FileDecision(true, false, "ALLOW")));
        when(repository.insert(any())).thenReturn(Mono.empty());
        when(repository.markReady(any())).thenReturn(Mono.empty());
        when(objectStore.put(anyString(), eq(pdf), eq("application/pdf"))).thenReturn(Mono.empty());
        FileService service = new FileService(repository, objectStore, new FileContentValidator(),
                new FileUploadMetadataValidator(), entitlementService, safety,
                Clock.fixed(NOW, ZoneOffset.UTC));

        FileObjectRepository.FileObject uploaded = service.upload(
                walletContext, "wallet.pdf", "application/pdf", pdf).block(Duration.ofSeconds(1));

        assertThat(uploaded).isNotNull();
        assertThat(uploaded.status()).isEqualTo("READY");
        assertThat(uploaded.objectKey()).startsWith("clean/");
    }

    @Test
    void storesUploadsWithRandomObjectKeysAndTwentyFourHourExpiry() {
        FileObjectRepository repository = mock(FileObjectRepository.class);
        FileObjectStore objectStore = mock(FileObjectStore.class);
        when(repository.packageEntitlement(TENANT_ID, USER_ID, PACKAGE_ID)).thenReturn(Mono.just(
                new FileObjectRepository.PackageEntitlement("STARTER", 20L * 1024 * 1024)));
        when(repository.insert(any())).thenReturn(Mono.empty());
        when(repository.markReady(any())).thenReturn(Mono.empty());
        when(objectStore.put(anyString(), any(), anyString())).thenReturn(Mono.empty());
        FileService service = fileService(repository, objectStore);
        byte[] pdf = "%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII);

        FileObjectRepository.FileObject file = service.upload(
                context(), "customer-name.pdf", "application/pdf", pdf).block(Duration.ofSeconds(1));

        assertThat(file).isNotNull();
        assertThat(file.objectKey()).matches("clean/[0-9a-f-]{36}");
        assertThat(file.objectKey()).doesNotContain("customer-name");
        assertThat(file.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(file.status()).isEqualTo("READY");
        verify(objectStore).put(file.objectKey(), pdf, "application/pdf");
    }

    @Test
    void scopesEveryFileReadAndDeleteByTenantAndOwner() {
        FileObjectRepository repository = mock(FileObjectRepository.class);
        FileObjectStore objectStore = mock(FileObjectStore.class);
        FileObjectRepository.FileObject file = readyFile();
        FileObjectRepository.FileObject deleting = file.withStatus("DELETING")
                .withDeletionClaimToken(UUID.randomUUID());
        when(repository.find("file_owned", TENANT_ID, USER_ID)).thenReturn(Mono.just(file));
        when(repository.list(TENANT_ID, USER_ID)).thenReturn(Flux.just(file));
        when(repository.claimDelete("file_owned", TENANT_ID, USER_ID)).thenReturn(Mono.just(deleting));
        when(repository.markDeleted(file.id(), deleting.deletionClaimToken(), NOW))
                .thenReturn(Mono.empty());
        when(objectStore.delete(file.objectKey())).thenReturn(Mono.empty());
        FileService service = fileService(repository, objectStore);

        service.get(context(), "file_owned").block(Duration.ofSeconds(1));
        service.list(context()).collectList().block(Duration.ofSeconds(1));
        service.delete(context(), "file_owned").block(Duration.ofSeconds(1));

        verify(repository).find("file_owned", TENANT_ID, USER_ID);
        verify(repository).list(TENANT_ID, USER_ID);
        verify(repository).claimDelete("file_owned", TENANT_ID, USER_ID);
    }

    @Test
    void exposesOwnedUnexpiredProcessingMetadataButOnlyUsesReadyCleanObjects() {
        FileObjectRepository repository = mock(FileObjectRepository.class);
        FileObjectStore objectStore = mock(FileObjectStore.class);
        FileObjectRepository.FileObject pending = file("PENDING_UPLOAD", "quarantine/pending", NOW.plusSeconds(60));
        FileObjectRepository.FileObject uploaded = file("UPLOADED", "quarantine/uploaded", NOW.plusSeconds(60));
        FileObjectRepository.FileObject readyClean = file("READY", "clean/ready", NOW.plusSeconds(60));
        FileObjectRepository.FileObject readyQuarantine = file("READY", "quarantine/ready", NOW.plusSeconds(60));
        FileObjectRepository.FileObject expired = file("READY", "clean/expired", NOW);
        FileObjectRepository.FileObject legacyUploading = file(
                "UPLOADING", "files/legacy", NOW.plusSeconds(60));
        FileObjectRepository.FileObject deleting = file(
                "DELETING", "clean/deleting", NOW.plusSeconds(60));
        when(repository.find(pending.externalFileId(), TENANT_ID, USER_ID)).thenReturn(Mono.just(pending));
        when(repository.find(readyClean.externalFileId(), TENANT_ID, USER_ID)).thenReturn(Mono.just(readyClean));
        when(repository.find(readyQuarantine.externalFileId(), TENANT_ID, USER_ID))
                .thenReturn(Mono.just(readyQuarantine));
        when(repository.find(legacyUploading.externalFileId(), TENANT_ID, USER_ID))
                .thenReturn(Mono.just(legacyUploading));
        when(repository.find(deleting.externalFileId(), TENANT_ID, USER_ID)).thenReturn(Mono.just(deleting));
        when(repository.list(TENANT_ID, USER_ID)).thenReturn(
                Flux.just(pending, uploaded, readyClean, expired, legacyUploading, deleting));
        FileService service = fileService(repository, objectStore);

        assertThat(service.get(context(), pending.externalFileId()).block()).isEqualTo(pending);
        assertThat(service.list(context()).collectList().block()).containsExactly(pending, uploaded, readyClean);
        assertThat(service.requireUsable(context(), readyClean.externalFileId()).block()).isEqualTo(readyClean);
        assertReason(service.requireUsable(context(), pending.externalFileId()), "file_processing");
        assertReason(service.requireUsable(context(), readyQuarantine.externalFileId()), "file_processing");
        assertReason(service.get(context(), legacyUploading.externalFileId()), "file_not_found");
        assertReason(service.get(context(), deleting.externalFileId()), "file_not_found");
    }

    @Test
    void mapsEveryInternalProcessingStateToThePublicFileStatus() {
        FileService service = mock(FileService.class);
        List<FileObjectRepository.FileObject> files = List.of(
                file("PENDING_UPLOAD", "quarantine/1", NOW.plusSeconds(60)),
                file("UPLOADED", "quarantine/2", NOW.plusSeconds(60)),
                file("SCANNING", "quarantine/3", NOW.plusSeconds(60)),
                file("READY", "clean/4", NOW.plusSeconds(60)),
                file("REJECTED", "quarantine/5", NOW.plusSeconds(60)),
                file("FAILED", "quarantine/6", NOW.plusSeconds(60)));
        when(service.list(context())).thenReturn(Flux.fromIterable(files));

        filesClient(service).get().uri("/v1/files").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].status").isEqualTo("pending_upload")
                .jsonPath("$.data[1].status").isEqualTo("uploaded")
                .jsonPath("$.data[2].status").isEqualTo("scanning")
                .jsonPath("$.data[3].status").isEqualTo("processed")
                .jsonPath("$.data[4].status").isEqualTo("rejected")
                .jsonPath("$.data[5].status").isEqualTo("failed");
    }

    @Test
    void failsClosedInsteadOfExposingAnUnknownInternalFileStatus() {
        FileService service = mock(FileService.class);
        when(service.list(context())).thenReturn(Flux.just(
                file("DELETING", "clean/deleting", NOW.plusSeconds(60))));

        filesClient(service).get().uri("/v1/files").exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("internal_error")
                .consumeWith(response -> assertThat(new String(
                        response.getResponseBody(), StandardCharsets.UTF_8))
                        .doesNotContain("DELETING", "deleting"));
    }

    @Test
    void exposesOpenAiCompatibleUploadAndMetadataEndpoints() {
        FileService service = mock(FileService.class);
        FileObjectRepository.FileObject file = readyFile();
        byte[] pdf = "%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII);
        when(service.upload(eq(context()), eq("contract.pdf"), eq("application/pdf"), any(), anyString()))
                .thenReturn(Mono.just(file));
        when(service.get(context(), "file_owned")).thenReturn(Mono.just(file));
        WebTestClient web = filesClient(service);
        MultipartBodyBuilder multipart = new MultipartBodyBuilder();
        multipart.part("purpose", "assistants");
        multipart.part("file", new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return "contract.pdf";
            }
        }).contentType(MediaType.APPLICATION_PDF);

        web.post().uri("/v1/files")
                .body(BodyInserters.fromMultipartData(multipart.build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("file_owned")
                .jsonPath("$.object").isEqualTo("file")
                .jsonPath("$.bytes").isEqualTo(10)
                .jsonPath("$.purpose").isEqualTo("assistants")
                .jsonPath("$.status").isEqualTo("processed");

        web.get().uri("/v1/files/file_owned").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("file_owned");
    }

    @Test
    void exposesOwnerScopedListAndDeleteEndpoints() {
        FileService service = mock(FileService.class);
        FileObjectRepository.FileObject file = readyFile();
        when(service.list(context())).thenReturn(Flux.just(file));
        when(service.delete(context(), "file_owned")).thenReturn(Mono.empty());
        WebTestClient web = filesClient(service);

        web.get().uri("/v1/files").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.object").isEqualTo("list")
                .jsonPath("$.data[0].id").isEqualTo("file_owned");

        web.delete().uri("/v1/files/file_owned").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("file_owned")
                .jsonPath("$.deleted").isEqualTo(true);
    }

    @Test
    void returnsTheExistingOpenAiErrorEnvelopeForFileFailures() {
        FileService service = mock(FileService.class);
        when(service.get(context(), "file_other")).thenReturn(Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "file_not_found")));

        filesClient(service).get().uri("/v1/files/file_other").exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("invalid_request_error")
                .jsonPath("$.error.code").isEqualTo("file_not_found")
                .jsonPath("$.request_id").isEqualTo("req_files_test");
    }

    @ParameterizedTest
    @CsvSource({
            "400, unsupported_file_type",
            "400, invalid_file_content",
            "413, file_too_large",
            "422, file_complexity_limit",
            "422, encrypted_file_not_supported",
            "422, document_conversion_failed",
            "404, file_not_found"
    })
    void exposesStableSanitizedFileErrorCodes(int sourceStatus, String reason) {
        FileService service = mock(FileService.class);
        when(service.get(context(), "file_failure")).thenReturn(Mono.error(
                new ResponseStatusException(HttpStatus.valueOf(sourceStatus), reason,
                        new IllegalStateException("sensitive-parser-detail"))));

        filesClient(service).get().uri("/v1/files/file_failure").exchange()
                .expectStatus().isEqualTo(sourceStatus)
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("invalid_request_error")
                .jsonPath("$.error.code").isEqualTo(reason)
                .jsonPath("$.error.message").value(message ->
                        assertThat(message.toString()).doesNotContain("sensitive-parser-detail"));
    }

    @Test
    void missingMultipartFileUsesOpenAiInvalidRequestEnvelope() {
        FileService service = mock(FileService.class);
        MultipartBodyBuilder multipart = new MultipartBodyBuilder();
        multipart.part("purpose", "assistants");

        filesClient(service).post().uri("/v1/files")
                .body(BodyInserters.fromMultipartData(multipart.build()))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("x-request-id", "req_files_test")
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("invalid_request_error")
                .jsonPath("$.error.code").isEqualTo("invalid_request")
                .jsonPath("$.request_id").isEqualTo("req_files_test");
    }

    @Test
    void writesToMinioWithTheExactRandomKeyUsingTheS3AsyncClient() {
        S3AsyncClient s3 = mock(S3AsyncClient.class);
        when(s3.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
                .thenReturn(CompletableFuture.completedFuture(PutObjectResponse.builder().build()));
        S3FileObjectStore store = new S3FileObjectStore(s3, "private-files");
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);

        store.put("files/11111111-1111-4111-8111-111111111111", content, "text/plain")
                .block(Duration.ofSeconds(1));

        org.mockito.ArgumentCaptor<PutObjectRequest> request =
                org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(AsyncRequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("private-files");
        assertThat(request.getValue().key()).isEqualTo("files/11111111-1111-4111-8111-111111111111");
        assertThat(request.getValue().contentType()).isEqualTo("text/plain");
        assertThat(request.getValue().contentLength()).isEqualTo(content.length);
    }

    @Test
    void retriesExpiredDeletionWithoutRestoringReady() {
        FileObjectRepository repository = mock(FileObjectRepository.class);
        FileObjectStore store = mock(FileObjectStore.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        FileObjectRepository.FileObject file = readyFile().withStatus("DELETING")
                .withDeletionClaimToken(UUID.randomUUID());
        when(repository.claimExpired(100)).thenReturn(Flux.just(file), Flux.just(file));
        when(store.delete(file.objectKey()))
                .thenReturn(Mono.error(new IllegalStateException("minio_unavailable")), Mono.empty());
        when(repository.markDeleted(file.id(), file.deletionClaimToken(), NOW)).thenReturn(Mono.empty());
        ExpiredFileCleanupJob job = new ExpiredFileCleanupJob(
                repository, store, Clock.fixed(NOW, ZoneOffset.UTC), meterRegistry, 100);

        job.cleanup().block(Duration.ofSeconds(1));
        job.cleanup().block(Duration.ofSeconds(1));

        verify(repository, never()).restoreReady(file.id(), file.deletionClaimToken());
        verify(repository).markDeleted(file.id(), file.deletionClaimToken(), NOW);
        verify(store, times(2)).delete(file.objectKey());
        assertThat(meterRegistry.get("gateway.file.cleanup")
                .tag("outcome", "failure").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("gateway.file.cleanup")
                .tag("outcome", "deleted").counter().count()).isEqualTo(1);
    }

    @Test
    void restoresAnUnexpiredReadyFileWhenManualStorageDeleteFails() {
        FileObjectRepository repository = mock(FileObjectRepository.class);
        FileObjectStore store = mock(FileObjectStore.class);
        FileObjectRepository.FileObject file = readyFile().withStatus("DELETING")
                .withDeletionClaimToken(UUID.randomUUID());
        when(repository.claimDelete("file_owned", TENANT_ID, USER_ID)).thenReturn(Mono.just(file));
        when(store.delete(file.objectKey()))
                .thenReturn(Mono.error(new IllegalStateException("storage_unavailable")));
        when(repository.restoreReady(file.id(), file.deletionClaimToken())).thenReturn(Mono.empty());

        assertThatThrownBy(() -> fileService(repository, store)
                        .delete(context(), "file_owned").block(Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("storage_unavailable");

        verify(repository).restoreReady(file.id(), file.deletionClaimToken());
        verify(repository, never()).markDeleted(eq(file.id()), eq(file.deletionClaimToken()), any());
    }

    @Test
    void leavesManualDeleteClaimedWhenMetadataFinalizationFailsAfterStorageDelete() {
        FileObjectRepository repository = mock(FileObjectRepository.class);
        FileObjectStore store = mock(FileObjectStore.class);
        FileObjectRepository.FileObject file = readyFile().withStatus("DELETING")
                .withDeletionClaimToken(UUID.randomUUID());
        when(repository.claimDelete("file_owned", TENANT_ID, USER_ID)).thenReturn(Mono.just(file));
        when(store.delete(file.objectKey())).thenReturn(Mono.empty());
        when(repository.markDeleted(file.id(), file.deletionClaimToken(), NOW))
                .thenReturn(Mono.error(new IllegalStateException("database_unavailable")));
        when(repository.restoreReady(file.id(), file.deletionClaimToken())).thenReturn(Mono.empty());
        FileService service = fileService(repository, store);

        assertThatThrownBy(() -> service.delete(context(), "file_owned")
                        .block(Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database_unavailable");

        verify(repository, never()).restoreReady(file.id(), file.deletionClaimToken());
    }

    @Test
    void leavesCleanupClaimedWhenMetadataFinalizationFailsAfterStorageDelete() {
        FileObjectRepository repository = mock(FileObjectRepository.class);
        FileObjectStore store = mock(FileObjectStore.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        FileObjectRepository.FileObject file = readyFile().withStatus("DELETING")
                .withDeletionClaimToken(UUID.randomUUID());
        when(repository.claimExpired(100)).thenReturn(Flux.just(file));
        when(store.delete(file.objectKey())).thenReturn(Mono.empty());
        when(repository.markDeleted(file.id(), file.deletionClaimToken(), NOW))
                .thenReturn(Mono.error(new IllegalStateException("database_unavailable")));
        when(repository.restoreReady(file.id(), file.deletionClaimToken())).thenReturn(Mono.empty());
        ExpiredFileCleanupJob job = new ExpiredFileCleanupJob(
                repository, store, Clock.fixed(NOW, ZoneOffset.UTC), meterRegistry, 100);

        job.cleanup().block(Duration.ofSeconds(1));

        verify(repository, never()).restoreReady(file.id(), file.deletionClaimToken());
        assertThat(meterRegistry.get("gateway.file.cleanup")
                .tag("outcome", "failure").counter().count()).isEqualTo(1);
    }

    @Test
    void delaysTheFirstScheduledCleanupRun() throws Exception {
        org.springframework.scheduling.annotation.Scheduled scheduled = ExpiredFileCleanupJob.class
                .getDeclaredMethod("scheduledCleanup")
                .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);

        assertThat(scheduled.initialDelayString())
                .isEqualTo("${storage.files.cleanup-initial-delay-ms:60000}");
    }

    @Test
    void preparesTextBase64ImagesAndOwnedFilesBeforeOrchestration() {
        FileService fileService = mock(FileService.class);
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        when(fileService.requireUsable(context(), "file_owned")).thenReturn(Mono.just(readyFile()));
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1};
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        ObjectMapper json = new ObjectMapper();
        ObjectNode body = json.createObjectNode();
        body.put("model", "text-basic");
        ArrayNode content = body.putArray("input").addObject()
                .put("role", "user").putArray("content");
        content.addObject().put("type", "input_text").put("text", "summarize");
        content.addObject().put("type", "input_image").put("image_url", dataUri);
        content.addObject().put("type", "input_file").put("file_id", "file_owned");

        ResponsesRequestPreparer.PreparedResponseRequest prepared = new ResponsesRequestPreparer(
                json, new FileContentValidator(), fetcher, fileService, 1024)
                .prepare(context(), body).block(Duration.ofSeconds(1));

        assertThat(prepared).isNotNull();
        assertThat(prepared.capabilities()).containsExactlyInAnyOrder(
                ChannelRouter.Capability.TEXT,
                ChannelRouter.Capability.IMAGE,
                ChannelRouter.Capability.FILE);
        assertThat(prepared.files()).extracting(FileObjectRepository.FileObject::externalFileId)
                .containsExactly("file_owned");
        assertThat(prepared.chatBody().path("messages").get(0).path("content").get(1)
                .path("image_url").path("url").asText()).isEqualTo(dataUri);
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void translatesResponsesOutputLimitToProviderCompatibleMaxTokens(boolean stream) {
        ObjectMapper json = new ObjectMapper();
        ObjectNode body = json.createObjectNode();
        body.put("model", "text-basic");
        body.put("input", "reply briefly");
        body.put("max_output_tokens", 7);
        body.put("stream", stream);

        ResponsesRequestPreparer.PreparedResponseRequest prepared = new ResponsesRequestPreparer(
                json, new FileContentValidator(), mock(RemoteImageFetcher.class),
                mock(FileService.class), 1024)
                .prepare(context(), body).block(Duration.ofSeconds(1));

        assertThat(prepared).isNotNull();
        assertThat(prepared.chatBody().path("max_tokens").asInt()).isEqualTo(7);
        assertThat(prepared.chatBody().has("max_completion_tokens")).isFalse();
        assertThat(body.path("max_output_tokens").asInt()).isEqualTo(7);
    }

    @Test
    void fetchesAndValidatesRemoteImagesBeforeProducingProviderInput() {
        FileService fileService = mock(FileService.class);
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1};
        when(fetcher.fetch(URI.create("https://images.example/p.png")))
                .thenReturn(Mono.just(new RemoteImageFetcher.FetchedImage("image/png", png)));
        ObjectMapper json = new ObjectMapper();
        ObjectNode body = json.createObjectNode();
        body.put("model", "text-basic");
        body.putArray("input").addObject().put("role", "user").putArray("content")
                .addObject().put("type", "input_image")
                .put("image_url", "https://images.example/p.png");

        ResponsesRequestPreparer preparer = new ResponsesRequestPreparer(
                json, new FileContentValidator(), fetcher, fileService, 1024);
        ResponsesRequestPreparer.PreparedResponseRequest prepared = preparer
                .prepare(context(), body).block(Duration.ofSeconds(1));

        String parsed = prepared.chatBody().path("messages").get(0).path("content").get(0)
                .path("image_url").path("url").asText();
        assertThat(parsed).isEqualTo("https://images.example/p.png");
        verify(fetcher, never()).fetch(any());

        JsonNode hydrated = preparer.hydrateImages(prepared.chatBody())
                .block(Duration.ofSeconds(1));
        String normalized = hydrated.path("messages").get(0).path("content").get(0)
                .path("image_url").path("url").asText();
        assertThat(normalized).startsWith("data:image/png;base64,");
        verify(fetcher).fetch(URI.create("https://images.example/p.png"));
    }

    @Test
    void rejectsImageCountBeforeFetchingRemoteContent() {
        FileService fileService = mock(FileService.class);
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        ObjectMapper json = new ObjectMapper();
        ObjectNode body = responseWithRemoteImages(json, 3);
        ResponsesRequestPreparer preparer = new ResponsesRequestPreparer(
                json, new FileContentValidator(), fetcher, fileService, 1024, 2, 2048);

        assertThatThrownBy(() -> preparer.prepare(context(), body).block(Duration.ofSeconds(1)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getReason())
                        .isEqualTo("too_many_images"));
        verify(fetcher, never()).fetch(any());
    }

    @Test
    void rejectsAggregateImageBytesDuringDeferredHydration() {
        FileService fileService = mock(FileService.class);
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1};
        when(fetcher.fetch(any())).thenReturn(Mono.just(
                new RemoteImageFetcher.FetchedImage("image/png", png)));
        ObjectMapper json = new ObjectMapper();
        ResponsesRequestPreparer preparer = new ResponsesRequestPreparer(
                json, new FileContentValidator(), fetcher, fileService, 1024, 2, 12);
        ResponsesRequestPreparer.PreparedResponseRequest prepared = preparer
                .prepare(context(), responseWithRemoteImages(json, 2))
                .block(Duration.ofSeconds(1));

        assertThatThrownBy(() -> preparer.hydrateImages(prepared.chatBody())
                        .block(Duration.ofSeconds(1)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getReason())
                        .isEqualTo("images_too_large"));
    }

    @Test
    void rejectsInvalidBase64AndCrossOwnerFileIdsDuringPreparation() {
        FileService fileService = mock(FileService.class);
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        when(fileService.requireUsable(context(), "file_other")).thenReturn(Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "file_not_found")));
        ObjectMapper json = new ObjectMapper();
        ObjectNode invalidImage = json.createObjectNode();
        invalidImage.put("model", "text-basic");
        invalidImage.putArray("input").addObject().put("role", "user").putArray("content")
                .addObject().put("type", "input_image")
                .put("image_url", "data:image/png;base64,not-base64!");
        ObjectNode foreignFile = json.createObjectNode();
        foreignFile.put("model", "text-basic");
        foreignFile.putArray("input").addObject().put("role", "user").putArray("content")
                .addObject().put("type", "input_file").put("file_id", "file_other");
        ResponsesRequestPreparer preparer = new ResponsesRequestPreparer(
                json, new FileContentValidator(), fetcher, fileService, 1024);

        assertThatThrownBy(() -> preparer.prepare(context(), invalidImage).block(Duration.ofSeconds(1)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> preparer.prepare(context(), foreignFile).block(Duration.ofSeconds(1)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getReason())
                        .isEqualTo("file_not_found"));
    }

    @Test
    void reusesAReadyProviderFileBindingWithoutReadingOrUploadingContent() {
        ProviderFileBindingRepository repository = mock(ProviderFileBindingRepository.class);
        FileObjectStore store = mock(FileObjectStore.class);
        ProviderAdapter adapter = mock(ProviderAdapter.class);
        ProviderAdapter.Channel channel = providerChannel();
        FileObjectRepository.FileObject file = readyFile();
        when(repository.findReady(file.id(), channel.id())).thenReturn(Mono.just(
                new ProviderFileBindingRepository.Binding(
                        UUID.randomUUID(), file.id(), channel.id(),
                        "upstream-existing", "READY", null)));

        String upstreamId = new ProviderFileBindingService(repository, store)
                .bind(file, adapter, channel).block(Duration.ofSeconds(1));

        assertThat(upstreamId).isEqualTo("upstream-existing");
        verify(store, never()).get(anyString(), anyLong());
        verify(adapter, never()).bindFile(any(), any());
    }

    @Test
    void atomicallyClaimsAndPersistsAProviderFileUploadForRetryableIdempotency() {
        ProviderFileBindingRepository repository = mock(ProviderFileBindingRepository.class);
        FileObjectStore store = mock(FileObjectStore.class);
        ProviderAdapter adapter = mock(ProviderAdapter.class);
        ProviderAdapter.Channel channel = providerChannel();
        byte[] content = "%PDF-data".getBytes(StandardCharsets.US_ASCII);
        FileObjectRepository.FileObject file = fileForContent(content);
        UUID claimToken = UUID.randomUUID();
        ProviderFileBindingRepository.Binding claimed = new ProviderFileBindingRepository.Binding(
                UUID.randomUUID(), file.id(), channel.id(), null, "UPLOADING", claimToken);
        when(repository.findReady(file.id(), channel.id())).thenReturn(Mono.empty());
        when(repository.claim(file.id(), channel.id())).thenReturn(Mono.just(claimed));
        when(store.get(file.objectKey(), file.sizeBytes())).thenReturn(Mono.just(content));
        when(adapter.bindFile(any(), eq(channel)))
                .thenReturn(Mono.just(new ProviderAdapter.ProviderFileRef("upstream-new")));
        when(repository.markReady(claimed.id(), claimToken, "upstream-new")).thenReturn(Mono.empty());

        String upstreamId = new ProviderFileBindingService(repository, store)
                .bind(file, adapter, channel).block(Duration.ofSeconds(1));

        assertThat(upstreamId).isEqualTo("upstream-new");
        verify(repository).claim(file.id(), channel.id());
        verify(repository).markReady(claimed.id(), claimToken, "upstream-new");
    }

    @Test
    void rejectsNonHttpsAndNonStandardPorts() {
        RemoteUrlPolicy policy = policy(List.of(address("93.184.216.34")));

        assertUnsafe(policy, "http://example.test/image.png");
        assertUnsafe(policy, "https://example.test:8443/image.png");
    }

    @Test
    void rejectsMixedDnsAndCloudMetadataAddresses() {
        RemoteUrlPolicy mixed = policy(List.of(
                address("93.184.216.34"), address("127.0.0.1")));
        RemoteUrlPolicy metadata = policy(List.of(address("169.254.169.254")));

        assertUnsafe(mixed, "https://mixed.example/image.png");
        assertUnsafe(metadata, "https://metadata.example/latest/meta-data");
    }

    @Test
    void returnsTheValidatedAddressThatMustBeUsedForDialing() {
        InetAddress expected = address("93.184.216.34");
        RemoteUrlPolicy.ValidatedRemote validated = policy(List.of(expected))
                .validate(URI.create("https://example.test/image.png"))
                .block(Duration.ofSeconds(1));

        assertThat(validated).isNotNull();
        assertThat(validated.uri()).isEqualTo(URI.create("https://example.test/image.png"));
        assertThat(validated.address()).isEqualTo(expected);
        assertThat(validated.port()).isEqualTo(443);
    }

    @Test
    void buildsAPinnedDialThatPreservesHostAndTlsSniWithoutAutomaticRedirects() {
        InetAddress address = address("93.184.216.34");
        AtomicInteger exchanges = new AtomicInteger();
        PinnedReactorNettyTransport transport = new PinnedReactorNettyTransport(request -> {
            exchanges.incrementAndGet();
            assertThat(request.remoteAddress()).isEqualTo(new InetSocketAddress(address, 443));
            assertThat(request.uri()).isEqualTo(URI.create("https://images.example/a.png"));
            assertThat(request.hostHeader()).isEqualTo("images.example");
            assertThat(request.tlsServerName()).isEqualTo("images.example");
            assertThat(request.followRedirects()).isFalse();
            assertThat(request.maxBytes()).isEqualTo(1024);
            return Mono.just(RemoteImageFetcher.TransportResponse.success(
                    "image/png", Flux.just(new byte[] {1})));
        });

        transport.get(
                new RemoteUrlPolicy.ValidatedRemote(
                        URI.create("https://images.example/a.png"), address, 443),
                1024, Duration.ofSeconds(2)).block(Duration.ofSeconds(1));

        assertThat(exchanges).hasValue(1);
    }

    @Test
    void revalidatesEveryRedirectBeforeTheNextPinnedRequest() {
        InetAddress publicAddress = address("93.184.216.34");
        RemoteUrlPolicy policy = new RemoteUrlPolicy((host, timeout) ->
                "private.example".equals(host)
                        ? List.of(address("10.0.0.7")) : List.of(publicAddress), Duration.ofMillis(250));
        AtomicInteger requests = new AtomicInteger();
        RemoteImageFetcher fetcher = new RemoteImageFetcher(policy, (target, maxBytes, timeout) -> {
            requests.incrementAndGet();
            assertThat(target.address()).isEqualTo(publicAddress);
            return Mono.just(RemoteImageFetcher.TransportResponse.redirect(
                    URI.create("https://private.example/secret.png")));
        }, 1024, 3, Duration.ofSeconds(1));

        assertThatThrownBy(() -> fetcher.fetch(URI.create("https://public.example/image.png"))
                .block(Duration.ofSeconds(2)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getReason())
                        .isEqualTo("unsafe_remote_url"));
        assertThat(requests).hasValue(1);
    }

    @Test
    void pinsTheValidatedAddressOnEverySafeRedirect() {
        InetAddress first = address("93.184.216.34");
        InetAddress second = address("93.184.216.35");
        RemoteUrlPolicy policy = new RemoteUrlPolicy((host, timeout) ->
                "cdn.example".equals(host) ? List.of(second) : List.of(first), Duration.ofMillis(250));
        List<InetAddress> dialed = new ArrayList<>();
        RemoteImageFetcher fetcher = new RemoteImageFetcher(policy, (target, maxBytes, timeout) -> {
            dialed.add(target.address());
            return target.uri().getHost().equals("origin.example")
                    ? Mono.just(RemoteImageFetcher.TransportResponse.redirect(
                            URI.create("https://cdn.example/image.png")))
                    : Mono.just(RemoteImageFetcher.TransportResponse.success(
                            "image/png", Flux.just(new byte[] {1, 2, 3})));
        }, 1024, 3, Duration.ofSeconds(1));

        RemoteImageFetcher.FetchedImage image = fetcher
                .fetch(URI.create("https://origin.example/image.png"))
                .block(Duration.ofSeconds(2));

        assertThat(dialed).containsExactly(first, second);
        assertThat(image.mediaType()).isEqualTo("image/png");
        assertThat(image.body()).containsExactly(1, 2, 3);
    }

    @Test
    void rejectsAnOversizedStreamingImageBody() {
        RemoteImageFetcher fetcher = new RemoteImageFetcher(
                policy(List.of(address("93.184.216.34"))),
                (target, maxBytes, timeout) -> Mono.just(
                        RemoteImageFetcher.TransportResponse.success("image/png", Flux.just(
                                "1234".getBytes(StandardCharsets.UTF_8),
                                "5678".getBytes(StandardCharsets.UTF_8)))),
                7, 0, Duration.ofSeconds(1));

        assertThatThrownBy(() -> fetcher.fetch(URI.create("https://example.test/image.png"))
                .block(Duration.ofSeconds(2)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getReason())
                        .isEqualTo("remote_image_too_large"));
    }

    @Test
    void rejectsAnOversizedRedirectResponseBody() {
        assertThatThrownBy(() -> PinnedReactorNettyTransport.collectResponse(
                        302,
                        URI.create("https://cdn.example/image.png"),
                        null,
                        Flux.just(
                                "1234".getBytes(StandardCharsets.UTF_8),
                                "5678".getBytes(StandardCharsets.UTF_8)),
                        7).block(Duration.ofSeconds(1)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getReason())
                        .isEqualTo("remote_image_too_large"));
    }

    private static RemoteUrlPolicy policy(List<InetAddress> addresses) {
        return new RemoteUrlPolicy((host, timeout) -> addresses, Duration.ofMillis(250));
    }

    private static FileService fileService(
            FileObjectRepository repository, FileObjectStore objectStore) {
        return fileService(repository, objectStore, new FileContentValidator());
    }

    private static FileService fileService(
            FileObjectRepository repository, FileObjectStore objectStore,
            FileContentValidator validator) {
        UploadEntitlementService entitlementService = mock(UploadEntitlementService.class);
        ContentSafetyOrchestrator safety = mock(ContentSafetyOrchestrator.class);
        when(entitlementService.resolve(any())).thenReturn(Mono.just(
                new UploadEntitlementService.UploadEntitlement(
                        "PACKAGE", FileService.FREE_MAX_BYTES, "FREE")));
        when(safety.inspectFile(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(new ContentSafetyOrchestrator.FileDecision(
                        true, false, "ALLOW")));
        return new FileService(repository, objectStore, validator,
                new FileUploadMetadataValidator(), entitlementService, safety,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static WebTestClient filesClient(FileService service) {
        return WebTestClient.bindToController(new FilesController(service))
                .controllerAdvice(new OpenAiExceptionHandler())
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE, context());
                    exchange.getAttributes().put(ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE, "req_files_test");
                    return chain.filter(exchange);
                })
                .build();
    }

    private static ApiKeyService.ApiKeyContext context() {
        return API_CONTEXT;
    }

    private static FileObjectRepository.FileObject readyFile() {
        return new FileObjectRepository.FileObject(
                UUID.randomUUID(), "file_owned", TENANT_ID, USER_ID,
                "clean/" + UUID.randomUUID(), "owned.pdf", "0".repeat(64),
                "application/pdf", 10, "READY", NOW.plus(Duration.ofHours(24)), null, NOW);
    }

    private static FileObjectRepository.FileObject file(
            String status, String objectKey, Instant expiresAt) {
        return new FileObjectRepository.FileObject(
                UUID.randomUUID(), "file_" + UUID.randomUUID().toString().replace("-", ""),
                TENANT_ID, USER_ID, objectKey, "owned.pdf", "0".repeat(64),
                "application/pdf", 10, status, expiresAt, null, NOW);
    }

    private static void assertReason(Mono<?> result, String reason) {
        assertThatThrownBy(result::block)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getReason())
                .isEqualTo(reason);
    }

    private static FileObjectRepository.FileObject fileForContent(byte[] content) {
        FileContentValidator.ValidatedContent validated = new FileContentValidator()
                .validateDocument("owned.pdf", "application/pdf", content, 1024);
        return new FileObjectRepository.FileObject(
                UUID.randomUUID(), "file_owned", TENANT_ID, USER_ID,
                "files/" + UUID.randomUUID(), "owned.pdf", validated.sha256(),
                validated.mimeType(), validated.sizeBytes(), "READY",
                NOW.plus(Duration.ofHours(24)), null, NOW);
    }

    private static ProviderAdapter.Channel providerChannel() {
        return new ProviderAdapter.Channel(
                UUID.fromString("50000000-0000-4000-8000-000000000001"),
                UUID.fromString("60000000-0000-4000-8000-000000000001"),
                "BAILIAN", "https://dashscope.example/v1", "secret", 5_000, "qwen-long");
    }

    private static ObjectNode responseWithRemoteImages(ObjectMapper json, int count) {
        ObjectNode body = json.createObjectNode();
        body.put("model", "text-basic");
        ArrayNode content = body.putArray("input").addObject()
                .put("role", "user").putArray("content");
        for (int index = 0; index < count; index++) {
            content.addObject().put("type", "input_image")
                    .put("image_url", "https://images.example/" + index + ".png");
        }
        return body;
    }

    private static byte[] docxWithEntries(int fillerEntries) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                for (int index = 0; index < fillerEntries; index++) {
                    zip.putNextEntry(new ZipEntry("word/filler-" + index + ".xml"));
                    zip.write('x');
                    zip.closeEntry();
                }
                zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
                zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("word/document.xml"));
                zip.write("<document/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return output.toByteArray();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static byte[] oleWithStream(String streamName) {
        try (POIFSFileSystem filesystem = new POIFSFileSystem();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            filesystem.getRoot().createDocument(
                    streamName, new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));
            filesystem.writeFilesystem(output);
            return output.toByteArray();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static byte[] officeArchive(String documentEntry, String... additionalEntries) {
        if (additionalEntries.length % 2 != 0) {
            throw new IllegalArgumentException("additionalEntries must contain name/content pairs");
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                writeZipEntry(zip, "[Content_Types].xml", "<Types/>");
                writeZipEntry(zip, documentEntry, "<document/>");
                for (int index = 0; index < additionalEntries.length; index += 2) {
                    writeZipEntry(zip, additionalEntries[index], additionalEntries[index + 1]);
                }
            }
            return output.toByteArray();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void writeZipEntry(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void assertUnsafe(RemoteUrlPolicy policy, String url) {
        assertThatThrownBy(() -> policy.validate(URI.create(url)).block(Duration.ofSeconds(1)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getReason()).isEqualTo("unsafe_remote_url");
                });
    }

    private static void assertInvalidContent(Runnable validation) {
        assertThatThrownBy(validation::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getReason())
                        .isEqualTo("invalid_file_content"));
    }

    private static void assertTooLarge(Runnable validation) {
        assertThatThrownBy(validation::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getReason())
                        .isEqualTo("file_too_large"));
    }

    private static InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}

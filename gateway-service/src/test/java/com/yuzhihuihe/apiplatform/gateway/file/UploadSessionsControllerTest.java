package com.yuzhihuihe.apiplatform.gateway.file;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class UploadSessionsControllerTest {

    private static final String SECRET = "sk-yzh-test-secret";
    private static final String IDEMPOTENCY_KEY = "79cf1c54-239c-48dd-89dd-dd03a680d689";
    private UploadSessionService service;
    private ApiKeyService apiKeyService;
    private ApiKeyService.ApiKeyContext context;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(UploadSessionService.class);
        apiKeyService = mock(ApiKeyService.class);
        context = new ApiKeyService.ApiKeyContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Set.of("vision-standard"));
        when(apiKeyService.authenticate(SECRET)).thenReturn(Mono.just(context));
        when(apiKeyService.authenticate(null)).thenReturn(Mono.empty());
        client = WebTestClient.bindToController(new UploadSessionsController(service))
                .webFilter(new ApiKeyWebFilter(apiKeyService))
                .build();
    }

    @Test
    void requiresApiKeyAuthentication() {
        client.post().uri("/v1/files/upload-sessions")
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("invalid_api_key")
                .jsonPath("$.request_id").exists();
    }

    @Test
    void acceptsExplicitSnakeCaseAndReturnsSignedSessionWithoutSecret() {
        Instant uploadExpiry = Instant.parse("2026-08-10T02:08:04Z");
        Instant fileExpiry = Instant.parse("2026-08-11T02:03:04Z");
        when(service.create(eq(context), any(), eq(IDEMPOTENCY_KEY))).thenReturn(Mono.just(
                new UploadSessionService.UploadSessionResult(
                        "file_abc", "upl_abc", "pending_upload",
                        new UploadFormSigner.SignedUploadForm(
                                "POST", URI.create("https://files.example.test/"),
                                Map.of("key", "quarantine/exact", "policy", "opaque", "Signature", "signed"),
                                uploadExpiry),
                        uploadExpiry, fileExpiry, false)));

        client.post().uri("/v1/files/upload-sessions")
                .header("Authorization", "Bearer " + SECRET)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("x-request-id")
                .expectBody()
                .jsonPath("$.file_id").isEqualTo("file_abc")
                .jsonPath("$.upload_session_id").isEqualTo("upl_abc")
                .jsonPath("$.status").isEqualTo("pending_upload")
                .jsonPath("$.upload.method").isEqualTo("POST")
                .jsonPath("$.upload.url").isEqualTo("https://files.example.test/")
                .jsonPath("$.upload.fields.key").isEqualTo("quarantine/exact")
                .jsonPath("$.upload_expires_at").isEqualTo(uploadExpiry.getEpochSecond())
                .jsonPath("$.file_expires_at").isEqualTo(fileExpiry.getEpochSecond())
                .jsonPath("$.mimeType").doesNotExist()
                .jsonPath("$.secret").doesNotExist();

        verify(service).create(eq(context), eq(new UploadSessionService.CreateUploadSessionCommand(
                "report.pdf", 1024, "application/pdf", "0123456789abcdef".repeat(4), "assistants")),
                eq(IDEMPOTENCY_KEY));
    }

    @Test
    void missingIdempotencyKeyUsesOpenAiErrorEnvelope() {
        client.post().uri("/v1/files/upload-sessions")
                .header("Authorization", "Bearer " + SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request())
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("x-request-id")
                .expectBody()
                .jsonPath("$.error.message").isEqualTo("Invalid idempotency key")
                .jsonPath("$.error.type").isEqualTo("invalid_request_error")
                .jsonPath("$.error.code").isEqualTo("invalid_idempotency_key")
                .jsonPath("$.request_id").exists();
    }

    @Test
    void mapsStableUploadErrorsWithoutStorageDetails() {
        when(service.create(eq(context), any(), eq(IDEMPOTENCY_KEY))).thenReturn(Mono.error(
                new ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "billing_eligibility_unavailable")));

        client.post().uri("/v1/files/upload-sessions")
                .header("Authorization", "Bearer " + SECRET)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("billing_eligibility_unavailable")
                .jsonPath("$.error.message").isEqualTo("Billing eligibility is unavailable")
                .consumeWith(response -> org.assertj.core.api.Assertions.assertThat(
                        new String(response.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8))
                        .doesNotContain("bucket", "quarantine", "provider", "secret"));
    }

    @Test
    void completesAnUploadWithAcceptedStatusAndCurrentFileView() {
        Instant now = Instant.parse("2026-08-10T02:03:04Z");
        FileObjectRepository.FileObject uploaded = new FileObjectRepository.FileObject(
                UUID.randomUUID(), "file_abc", context.tenantId(), context.userId(),
                "quarantine/exact", "report.pdf", "0".repeat(64), "application/pdf", 1024,
                "UPLOADED", now.plusSeconds(3600), null, now);
        when(service.complete(context, "upl_abc", IDEMPOTENCY_KEY)).thenReturn(Mono.just(uploaded));

        client.post().uri("/v1/files/upload-sessions/upl_abc/complete")
                .header("Authorization", "Bearer " + SECRET)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().exists("x-request-id")
                .expectBody()
                .jsonPath("$.id").isEqualTo("file_abc")
                .jsonPath("$.status").isEqualTo("uploaded")
                .jsonPath("$.filename").isEqualTo("report.pdf");

        verify(service).complete(context, "upl_abc", IDEMPOTENCY_KEY);
    }

    @Test
    void completeRequiresIdempotencyKeyAndMapsMissingObject() {
        client.post().uri("/v1/files/upload-sessions/upl_abc/complete")
                .header("Authorization", "Bearer " + SECRET)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.code").isEqualTo("invalid_idempotency_key");

        when(service.complete(context, "upl_abc", IDEMPOTENCY_KEY)).thenReturn(Mono.error(
                new ResponseStatusException(
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "upload_object_missing")));
        client.post().uri("/v1/files/upload-sessions/upl_abc/complete")
                .header("Authorization", "Bearer " + SECRET)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("upload_object_missing")
                .jsonPath("$.error.message").isEqualTo("Uploaded object was not found");
    }

    @Test
    void completeMapsStorageFailureToSanitizedServiceUnavailableError() {
        when(service.complete(context, "upl_abc", IDEMPOTENCY_KEY)).thenReturn(Mono.error(
                new ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "file_storage_unavailable",
                        new IllegalStateException("bucket-secret-provider-detail"))));

        client.post().uri("/v1/files/upload-sessions/upl_abc/complete")
                .header("Authorization", "Bearer " + SECRET)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("file_storage_unavailable")
                .jsonPath("$.error.message").isEqualTo("File storage is unavailable")
                .consumeWith(response -> org.assertj.core.api.Assertions.assertThat(
                        new String(response.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8))
                        .doesNotContain("bucket-secret", "provider-detail"));
    }

    private static Map<String, Object> request() {
        return Map.of(
                "filename", "report.pdf",
                "bytes", 1024,
                "mime_type", "application/pdf",
                "sha256", "0123456789abcdef".repeat(4),
                "purpose", "assistants");
    }
}

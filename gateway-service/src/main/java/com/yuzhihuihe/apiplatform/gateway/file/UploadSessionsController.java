package com.yuzhihuihe.apiplatform.gateway.file;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.openai.ChatCompletionController;
import com.yuzhihuihe.apiplatform.gateway.openai.OpenAiErrorMapper;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/files/upload-sessions")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class UploadSessionsController {

    private final UploadSessionService service;

    public UploadSessionsController(UploadSessionService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> create(
            @RequestBody CreateUploadSessionRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            ServerWebExchange exchange) {
        String requestId = requestId(exchange);
        Mono<UploadSessionService.UploadSessionResult> creation = idempotencyKey == null
                ? Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "invalid_idempotency_key"))
                : service.create(context(exchange), request.command(), idempotencyKey);
        return creation
                .<ResponseEntity<?>>map(result -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-request-id", requestId)
                        .body(response(result)))
                .onErrorResume(error -> Mono.just(errorResponse(error, requestId)));
    }

    @PostMapping(path = "/{uploadSessionId}/complete", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> complete(
            @PathVariable String uploadSessionId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            ServerWebExchange exchange) {
        String requestId = requestId(exchange);
        Mono<FileObjectRepository.FileObject> completion = idempotencyKey == null
                ? Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "invalid_idempotency_key"))
                : service.complete(context(exchange), uploadSessionId, idempotencyKey);
        return completion
                .<ResponseEntity<?>>map(file -> ResponseEntity.accepted()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-request-id", requestId)
                        .body(FilesController.view(file, "assistants")))
                .onErrorResume(error -> Mono.just(errorResponse(error, requestId)));
    }

    private static UploadSessionResponse response(UploadSessionService.UploadSessionResult result) {
        return new UploadSessionResponse(
                result.fileId(), result.uploadSessionId(), result.status(),
                new UploadResponse(
                        result.upload().method(), result.upload().url(), result.upload().fields()),
                result.uploadExpiresAt().getEpochSecond(), result.fileExpiresAt().getEpochSecond());
    }

    private static ApiKeyService.ApiKeyContext context(ServerWebExchange exchange) {
        return exchange.getAttribute(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE);
    }

    private static String requestId(ServerWebExchange exchange) {
        return exchange.getAttributeOrDefault(
                ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE,
                "req_" + UUID.randomUUID().toString().replace("-", ""));
    }

    private static ResponseEntity<ChatCompletionController.OpenAiErrorResponse> errorResponse(
            Throwable error, String requestId) {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(error);
        return ResponseEntity.status(descriptor.status())
                .headers(OpenAiErrorMapper.retryHeaders(error))
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-request-id", requestId)
                .body(new ChatCompletionController.OpenAiErrorResponse(
                        new ChatCompletionController.OpenAiError(
                                descriptor.message(), descriptor.type(), descriptor.code()),
                        requestId));
    }

    public record CreateUploadSessionRequest(
            String filename,
            long bytes,
            @JsonProperty("mime_type") String mimeType,
            String sha256,
            String purpose) {

        UploadSessionService.CreateUploadSessionCommand command() {
            return new UploadSessionService.CreateUploadSessionCommand(
                    filename, bytes, mimeType, sha256, purpose);
        }
    }

    public record UploadSessionResponse(
            @JsonProperty("file_id") String fileId,
            @JsonProperty("upload_session_id") String uploadSessionId,
            String status,
            UploadResponse upload,
            @JsonProperty("upload_expires_at") long uploadExpiresAt,
            @JsonProperty("file_expires_at") long fileExpiresAt) { }

    public record UploadResponse(String method, URI url, Map<String, String> fields) { }
}

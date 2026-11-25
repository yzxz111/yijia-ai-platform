package com.yuzhihuihe.apiplatform.gateway.file;

import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.openai.ChatCompletionController;
import com.yuzhihuihe.apiplatform.gateway.openai.OpenAiErrorMapper;
import com.yuzhihuihe.apiplatform.gateway.security.ApiKeyWebFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/files")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class FilesController {

    private static final int MULTIPART_MAX_BYTES = (int) FileService.PAID_MAX_BYTES + 1;
    private final FileService service;

    public FilesController(FileService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<?>> upload(
            @RequestPart("file") FilePart file,
            @RequestPart(name = "purpose", required = false) String purpose,
            ServerWebExchange exchange) {
        String requestId = requestId(exchange);
        if (file == null || file.headers().getContentType() == null) {
            return Mono.just(errorResponse(invalid("invalid_file_content"), requestId));
        }
        return DataBufferUtils.join(file.content(), MULTIPART_MAX_BYTES)
                .map(buffer -> {
                    byte[] content = new byte[buffer.readableByteCount()];
                    buffer.read(content);
                    DataBufferUtils.release(buffer);
                    return content;
                })
                .flatMap(content -> service.upload(
                        context(exchange), file.filename(),
                        file.headers().getContentType().toString(), content, requestId))
                .<ResponseEntity<?>>map(stored -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-request-id", requestId)
                        .body(view(stored, normalizedPurpose(purpose))))
                .onErrorResume(DataBufferLimitException.class, error -> Mono.just(errorResponse(
                        new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large"), requestId)))
                .onErrorResume(error -> Mono.just(errorResponse(error, requestId)));
    }

    @GetMapping
    public Mono<ResponseEntity<?>> list(ServerWebExchange exchange) {
        String requestId = requestId(exchange);
        return service.list(context(exchange))
                .map(file -> view(file, "assistants"))
                .collectList()
                .<ResponseEntity<?>>map(files -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-request-id", requestId)
                        .body(new FileList("list", files)))
                .onErrorResume(error -> Mono.just(errorResponse(error, requestId)));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<?>> get(@PathVariable String id, ServerWebExchange exchange) {
        String requestId = requestId(exchange);
        return service.get(context(exchange), id)
                .<ResponseEntity<?>>map(file -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-request-id", requestId)
                        .body(view(file, "assistants")))
                .onErrorResume(error -> Mono.just(errorResponse(error, requestId)));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<?>> delete(@PathVariable String id, ServerWebExchange exchange) {
        String requestId = requestId(exchange);
        return service.delete(context(exchange), id)
                .then(Mono.<ResponseEntity<?>>fromSupplier(() -> ResponseEntity.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("x-request-id", requestId)
                                .body(new DeletedFile(id, "file", true))))
                .onErrorResume(error -> Mono.just(errorResponse(error, requestId)));
    }

    private ApiKeyService.ApiKeyContext context(ServerWebExchange exchange) {
        return exchange.getAttribute(ApiKeyWebFilter.API_KEY_CONTEXT_ATTRIBUTE);
    }

    private String requestId(ServerWebExchange exchange) {
        return exchange.getAttributeOrDefault(
                ApiKeyWebFilter.REQUEST_ID_ATTRIBUTE,
                "req_" + UUID.randomUUID().toString().replace("-", ""));
    }

    private String normalizedPurpose(String purpose) {
        return purpose == null || purpose.isBlank() ? "assistants" : purpose.strip();
    }

    static FileView view(FileObjectRepository.FileObject file, String purpose) {
        return new FileView(
                file.externalFileId(), "file", file.sizeBytes(),
                file.createdAt().getEpochSecond(), file.originalFilename(), purpose,
                publicStatus(file.status()), file.expiresAt().getEpochSecond());
    }

    private static String publicStatus(String status) {
        return switch (status) {
            case "PENDING_UPLOAD" -> "pending_upload";
            case "UPLOADED" -> "uploaded";
            case "SCANNING" -> "scanning";
            case "READY" -> "processed";
            case "REJECTED" -> "rejected";
            case "FAILED" -> "failed";
            default -> throw new IllegalStateException("unsupported_public_file_status");
        };
    }

    private ResponseEntity<ChatCompletionController.OpenAiErrorResponse> errorResponse(
            Throwable error, String requestId) {
        OpenAiErrorMapper.ErrorDescriptor descriptor = OpenAiErrorMapper.descriptor(error);
        return ResponseEntity.status(descriptor.status())
                .headers(OpenAiErrorMapper.retryHeaders(error))
                .header("x-request-id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChatCompletionController.OpenAiErrorResponse(
                        new ChatCompletionController.OpenAiError(
                                descriptor.message(), descriptor.type(), descriptor.code()),
                        requestId));
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    public record FileView(
            String id, String object, long bytes, long created_at, String filename,
            String purpose, String status, long expires_at) { }

    public record FileList(String object, List<FileView> data) { }

    public record DeletedFile(String id, String object, boolean deleted) { }
}

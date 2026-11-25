package com.yuzhihuihe.apiplatform.gateway.skill;

import java.nio.ByteBuffer;

import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillClientApi.DownloadReport;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillClientApi.DownloadRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * JWT-or-API-key protected skill download endpoints (design 5.2 C3-C6).
 *
 * <p>C5 streams the package straight from the object store: {@code Range} is answered with
 * {@code 206} + {@code Content-Range}, an unsatisfiable range with {@code 416}, a mismatching
 * {@code If-Range} (or a multi-range request) falls back to a full {@code 200}. Nothing is ever
 * buffered whole in memory (design R-26/R-32).
 */
@RestController
@RequestMapping("/v1/skills")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillDownloadsController {

    private static final String CLIENT_VERSION_HEADER = "X-Client-Version";
    private static final String DEVICE_ID_HEADER = "X-Device-Id";
    private static final String PACKAGE_CONTENT_TYPE = "application/vnd.yuzhi.ycps+zip";

    private final SkillDownloadService downloads;
    private final SkillDeviceFingerprint fingerprints;
    private final FileObjectStore objectStore;

    public SkillDownloadsController(
            SkillDownloadService downloads,
            SkillDeviceFingerprint fingerprints,
            FileObjectStore objectStore) {
        this.downloads = downloads;
        this.fingerprints = fingerprints;
        this.objectStore = objectStore;
    }

    @GetMapping("/{code}/versions")
    Mono<org.springframework.http.ResponseEntity<?>> versions(
            @PathVariable String code,
            @RequestHeader(name = CLIENT_VERSION_HEADER, required = false) String clientVersion,
            ServerWebExchange exchange) {
        String requestId = SkillClientErrors.requestId(exchange);
        return SkillAccessIdentity.resolve(exchange)
                .flatMap(identity -> downloads.versions(code, identity, clientVersion))
                .<org.springframework.http.ResponseEntity<?>>map(list -> org.springframework.http.ResponseEntity
                        .ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(SkillClientErrors.REQUEST_ID_HEADER, requestId)
                        .body(list))
                .onErrorResume(error -> Mono.just(SkillClientErrors.error(error, requestId)));
    }

    @PostMapping("/{code}/downloads")
    Mono<org.springframework.http.ResponseEntity<?>> create(
            @PathVariable String code,
            @RequestBody(required = false) DownloadRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = CLIENT_VERSION_HEADER, required = false) String clientVersion,
            @RequestHeader(name = DEVICE_ID_HEADER, required = false) String deviceId,
            @RequestHeader(name = "X-Client-Platform", required = false) String clientPlatform,
            ServerWebExchange exchange) {
        String requestId = SkillClientErrors.requestId(exchange);
        SkillDownloadService.RequestMeta meta = new SkillDownloadService.RequestMeta(
                fingerprints.hash(deviceId), clientVersion, clientPlatform, requestId, idempotencyKey);
        return SkillAccessIdentity.resolve(exchange)
                .flatMap(identity -> downloads.create(code, identity, meta, body))
                .<org.springframework.http.ResponseEntity<?>>map(session -> org.springframework.http.ResponseEntity
                        .ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(SkillClientErrors.REQUEST_ID_HEADER, requestId)
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(session))
                .onErrorResume(error -> Mono.just(SkillClientErrors.error(error, requestId)));
    }

    @PostMapping("/downloads/{downloadId}/complete")
    Mono<org.springframework.http.ResponseEntity<?>> complete(
            @PathVariable String downloadId,
            @RequestBody(required = false) DownloadReport report,
            ServerWebExchange exchange) {
        String requestId = SkillClientErrors.requestId(exchange);
        DownloadReport body = report == null
                ? new DownloadReport("completed", null, null, null, null, null, null, null)
                : report;
        return SkillAccessIdentity.resolve(exchange)
                .flatMap(identity -> downloads.complete(downloadId, identity, body))
                .<org.springframework.http.ResponseEntity<?>>map(result -> org.springframework.http.ResponseEntity
                        .ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(SkillClientErrors.REQUEST_ID_HEADER, requestId)
                        .body(result))
                .onErrorResume(error -> Mono.just(SkillClientErrors.error(error, requestId)));
    }

    @GetMapping("/downloads/{downloadId}")
    Mono<Void> download(
            @PathVariable String downloadId,
            @RequestHeader(name = HttpHeaders.RANGE, required = false) String rangeHeader,
            @RequestHeader(name = HttpHeaders.IF_RANGE, required = false) String ifRange,
            ServerWebExchange exchange) {
        String requestId = SkillClientErrors.requestId(exchange);
        return SkillAccessIdentity.resolve(exchange)
                .flatMap(identity -> downloads.resolve(downloadId, identity))
                .flatMap(resolved -> stream(exchange, resolved, rangeHeader, ifRange, requestId))
                .onErrorResume(error -> SkillClientErrors.write(
                        exchange, SkillDownloadService.mapStorageFailure(error), requestId, null));
    }

    private Mono<Void> stream(
            ServerWebExchange exchange,
            SkillDownloadService.Resolved resolved,
            String rangeHeader,
            String ifRange,
            String requestId) {
        ServerHttpResponse response = exchange.getResponse();
        long size = resolved.sizeBytes();
        String etag = "\"" + resolved.sha256() + "\"";
        ByteRange range = null;
        if (rangeHeader != null && !rangeHeader.isBlank() && ifRangeMatches(ifRange, etag)) {
            range = parseRange(rangeHeader, size);
        }
        if (range == null) {
            writeHeaders(response, resolved, etag, requestId, size);
            return response
                    // Pass the real size: FileObjectStoreValidator.requireRange rejects a negative
                    // length, and throwing after the headers were written truncates the 200 body.
                    .writeWith(body(response, resolved, 0L, size))
                    .then(downloads.recordServed(resolved.sessionId(), size, null, null));
        }
        long length = range.length();
        response.setStatusCode(HttpStatus.PARTIAL_CONTENT);
        writeHeaders(response, resolved, etag, requestId, length);
        response.getHeaders().set(HttpHeaders.CONTENT_RANGE,
                "bytes " + range.from() + "-" + range.to() + "/" + size);
        long from = range.from();
        long to = range.to();
        return response
                .writeWith(body(response, resolved, from, length))
                .then(downloads.recordServed(resolved.sessionId(), length, from, to));
    }

    private Flux<DataBuffer> body(
            ServerHttpResponse response,
            SkillDownloadService.Resolved resolved,
            long from,
            long length) {
        byte[] frozen = resolved.content();
        if (frozen != null) {
            int start = Math.toIntExact(from);
            int end = Math.toIntExact(from + length);
            byte[] selected = start == 0 && end == frozen.length
                    ? frozen
                    : java.util.Arrays.copyOfRange(frozen, start, end);
            return Flux.just(response.bufferFactory().wrap(selected));
        }
        return objectStore
                .getRange(resolved.objectKey(), from, length)
                .map(SkillDownloadsController::copy)
                .map(buffer -> response.bufferFactory().wrap(buffer));
    }

    /** Copies each chunk because the transport may recycle the publisher's buffers. */
    private static byte[] copy(ByteBuffer source) {
        ByteBuffer buffer = source.duplicate();
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        return chunk;
    }

    private void writeHeaders(
            ServerHttpResponse response,
            SkillDownloadService.Resolved resolved,
            String etag,
            String requestId,
            long contentLength) {
        HttpHeaders headers = response.getHeaders();
        headers.setContentType(MediaType.parseMediaType(PACKAGE_CONTENT_TYPE));
        headers.setContentLength(contentLength);
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.ETAG, etag);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + resolved.code() + "-" + resolved.version()
                        + ".ycps.zip\"");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Skill-Code", resolved.code());
        headers.set("X-Skill-Version", resolved.version());
        if (resolved.md5() != null) {
            headers.set("X-Skill-Md5", resolved.md5());
        }
        headers.set(SkillClientErrors.REQUEST_ID_HEADER, requestId);
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    private static boolean ifRangeMatches(String ifRange, String etag) {
        if (ifRange == null || ifRange.isBlank()) {
            return true;
        }
        // R-17: a stale validator means "start over", so the range is ignored and 200 is served.
        return ifRange.strip().equals(etag);
    }

    /** Parses a single {@code bytes=} range; multi-range requests are ignored (design 6.6). */
    private static ByteRange parseRange(String header, long size) {
        String value = header.strip();
        if (!value.toLowerCase(java.util.Locale.ROOT).startsWith("bytes=")) {
            return null;
        }
        String spec = value.substring("bytes=".length()).strip();
        if (spec.isEmpty() || spec.contains(",")) {
            return null;
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String startText = spec.substring(0, dash).strip();
        String endText = spec.substring(dash + 1).strip();
        long from;
        long to;
        try {
            if (startText.isEmpty()) {
                long suffix = Long.parseLong(endText);
                if (suffix <= 0) {
                    throw unsatisfiable(size);
                }
                from = Math.max(0, size - suffix);
                to = size - 1;
            } else {
                from = Long.parseLong(startText);
                to = endText.isEmpty() ? size - 1 : Long.parseLong(endText);
            }
        } catch (NumberFormatException exception) {
            return null;
        }
        if (from >= size || (size > 0 && to < from)) {
            throw unsatisfiable(size);
        }
        return new ByteRange(from, Math.min(to, size - 1));
    }

    private static ResponseStatusException unsatisfiable(long size) {
        // ResponseStatusException#getHeaders is immutable, so the Content-Range of R-16 needs a
        // dedicated instance (same pattern as ClientBootstrapRateLimiter's Retry-After).
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
        return new ResponseStatusException(
                HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "invalid_range") {
            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }

    private record ByteRange(long from, long to) {
        long length() {
            return to - from + 1;
        }
    }
}

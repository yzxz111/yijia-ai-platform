package com.yuzhihuihe.apiplatform.gateway.file;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import com.yuzhihuihe.apiplatform.gateway.security.RemoteUrlPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class RemoteImageFetcher {

    private final RemoteUrlPolicy policy;
    private final PinnedTransport transport;
    private final long maxBytes;
    private final int maxRedirects;
    private final Duration totalTimeout;

    public RemoteImageFetcher(
            RemoteUrlPolicy policy,
            PinnedTransport transport,
            long maxBytes,
            int maxRedirects,
            Duration totalTimeout) {
        if (policy == null || transport == null || maxBytes <= 0 || maxRedirects < 0
                || totalTimeout == null || totalTimeout.isZero() || totalTimeout.isNegative()) {
            throw new IllegalArgumentException("invalid_remote_image_fetcher");
        }
        this.policy = policy;
        this.transport = transport;
        this.maxBytes = maxBytes;
        this.maxRedirects = maxRedirects;
        this.totalTimeout = totalTimeout;
    }

    public Mono<FetchedImage> fetch(URI uri) {
        return fetch(uri, 0, Instant.now().plus(totalTimeout));
    }

    private Mono<FetchedImage> fetch(URI uri, int redirects, Instant deadline) {
        Duration remaining = remaining(deadline);
        if (remaining.isZero()) {
            return Mono.error(failure(HttpStatus.GATEWAY_TIMEOUT, "remote_image_timeout"));
        }
        return policy.validate(uri)
                .timeout(remaining, Mono.error(failure(HttpStatus.GATEWAY_TIMEOUT, "remote_image_timeout")))
                .flatMap(target -> transport.get(target, maxBytes, remaining))
                .timeout(remaining(deadline),
                        Mono.error(failure(HttpStatus.GATEWAY_TIMEOUT, "remote_image_timeout")))
                .flatMap(response -> {
                    if (response.redirectLocation() != null) {
                        if (redirects >= maxRedirects) {
                            return Mono.error(failure(HttpStatus.BAD_REQUEST, "too_many_redirects"));
                        }
                        return fetch(uri.resolve(response.redirectLocation()), redirects + 1, deadline);
                    }
                    if (response.status() < 200 || response.status() >= 300
                            || response.mediaType() == null || response.mediaType().isBlank()) {
                        return Mono.error(failure(HttpStatus.BAD_GATEWAY, "remote_image_invalid_response"));
                    }
                    return collect(response.mediaType(), response.body());
                });
    }

    private Mono<FetchedImage> collect(String mediaType, Flux<byte[]> body) {
        AtomicLong received = new AtomicLong();
        return body.handle((chunk, sink) -> {
                    if (chunk == null) {
                        return;
                    }
                    long total;
                    try {
                        total = Math.addExact(received.get(), chunk.length);
                    } catch (ArithmeticException overflow) {
                        sink.error(failure(HttpStatus.PAYLOAD_TOO_LARGE, "remote_image_too_large"));
                        return;
                    }
                    if (total > maxBytes) {
                        sink.error(failure(HttpStatus.PAYLOAD_TOO_LARGE, "remote_image_too_large"));
                        return;
                    }
                    received.set(total);
                    sink.next(chunk);
                })
                .cast(byte[].class)
                .reduce(new ByteArrayOutputStream(), (output, chunk) -> {
                    output.writeBytes(chunk);
                    return output;
                })
                .map(output -> output.toByteArray())
                .filter(bytes -> bytes.length > 0)
                .switchIfEmpty(Mono.error(failure(
                        HttpStatus.BAD_GATEWAY, "remote_image_invalid_response")))
                .map(bytes -> new FetchedImage(mediaType, bytes));
    }

    private Duration remaining(Instant deadline) {
        Duration remaining = Duration.between(Instant.now(), deadline);
        return remaining.isNegative() || remaining.isZero() ? Duration.ZERO : remaining;
    }

    private ResponseStatusException failure(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }

    @FunctionalInterface
    public interface PinnedTransport {
        Mono<TransportResponse> get(
                RemoteUrlPolicy.ValidatedRemote target, long maxBytes, Duration timeout);
    }

    public record TransportResponse(
            int status, String mediaType, URI redirectLocation, Flux<byte[]> body) {

        public TransportResponse {
            body = body == null ? Flux.empty() : body;
        }

        public static TransportResponse redirect(URI location) {
            return new TransportResponse(302, null, location, Flux.empty());
        }

        public static TransportResponse success(String mediaType, Flux<byte[]> body) {
            return new TransportResponse(200, mediaType, null, body);
        }
    }

    public record FetchedImage(String mediaType, byte[] body) {
        public FetchedImage {
            body = body == null ? null : body.clone();
        }

        @Override
        public byte[] body() {
            return body == null ? null : body.clone();
        }
    }
}

package com.yuzhihuihe.apiplatform.gateway.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.net.URI;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.yuzhihuihe.apiplatform.gateway.limit.DefaultRequestLimitService;
import com.yuzhihuihe.apiplatform.gateway.limit.RequestLimitService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class GatewaySmsSignalTest {

    private static final String SECRET = "gateway-platform-sms-test-secret";
    private static final Instant NOW = Instant.parse("2026-08-04T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void signsProofConsumeUsingInternalV2AndSendsOnlyRequiredProofScope() throws Exception {
        AtomicReference<byte[]> capturedBody = new AtomicReference<>();
        AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
        startServer("/internal/platform/sms/proofs/consume", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            capturedBody.set(body);
            capturedHeaders.set(Map.of(
                    "timestamp", exchange.getRequestHeaders().getFirst("X-Internal-Timestamp"),
                    "requestId", exchange.getRequestHeaders().getFirst("X-Internal-Request-Id"),
                    "contentSha256", exchange.getRequestHeaders().getFirst("X-Internal-Content-Sha256"),
                    "signature", exchange.getRequestHeaders().getFirst("X-Internal-Signature")));
            respondSigned(exchange, false);
        });
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String proof = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        PlatformSmsClient client = client(requestId);

        client.consumeApiKeyResetProof(tenantId, userId, operationId, proof).block();

        JsonNode body = JSON.readTree(capturedBody.get());
        assertThat(iterable(body.fieldNames())).containsExactlyInAnyOrder(
                "proof", "tenantId", "userId", "operationId");
        assertThat(body.path("proof").asText()).isEqualTo(proof);
        assertThat(body.path("operationId").asText()).isEqualTo(operationId.toString());
        Map<String, String> headers = capturedHeaders.get();
        assertThat(headers.get("timestamp")).isEqualTo(Long.toString(NOW.getEpochSecond()));
        assertThat(headers.get("requestId")).isEqualTo(requestId.toString());
        assertThat(headers.get("contentSha256")).isEqualTo(sha256(capturedBody.get()));
        assertThat(headers.get("signature")).isEqualTo(hmac(
                headers.get("timestamp") + "\n" + requestId + "\nPOST\n"
                        + "/internal/platform/sms/proofs/consume\n"
                        + headers.get("contentSha256")));
    }

    @Test
    void rejectsTamperedSignedResponseEnvelope() throws Exception {
        startServer("/internal/platform/sms/signals/rate-limit",
                exchange -> respondSigned(exchange, true));
        PlatformSmsClient client = client(UUID.randomUUID());

        assertThatThrownBy(() -> client.reportRateLimit(
                        UUID.randomUUID(), UUID.randomUUID(), "text-basic", 17L).block())
                .hasMessageContaining("platform_sms_response_signature_invalid");
    }

    @Test
    void rejectsUnsignedErrorStatusBeforeSemanticMapping() throws Exception {
        startServer("/internal/platform/sms/proofs/consume", exchange -> {
            byte[] body = "{\"error\":\"invalid_sms_proof\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(422, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        PlatformSmsClient client = client(UUID.randomUUID());

        assertThatThrownBy(() -> client.consumeApiKeyResetProof(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "proof").block())
                .isInstanceOf(PlatformSmsClient.PlatformSmsClientException.class)
                .hasMessageContaining("platform_sms_response_envelope_invalid")
                .satisfies(error -> assertThat(
                        ((PlatformSmsClient.PlatformSmsClientException) error).httpStatus())
                        .isNull());
    }

    @Test
    void mapsSignedUnprocessableEntityAfterVerification() throws Exception {
        startServer("/internal/platform/sms/proofs/consume",
                exchange -> respondSigned(exchange, 422, false));
        PlatformSmsClient client = client(UUID.randomUUID());

        assertThatThrownBy(() -> client.consumeApiKeyResetProof(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "proof").block())
                .isInstanceOf(PlatformSmsClient.PlatformSmsClientException.class)
                .hasMessageContaining("platform_sms_http_status")
                .satisfies(error -> assertThat(
                        ((PlatformSmsClient.PlatformSmsClientException) error).httpStatus())
                        .isEqualTo(422));
    }

    @Test
    void mapsSignedServiceUnavailableAfterVerification() throws Exception {
        startServer("/internal/platform/sms/signals/rate-limit",
                exchange -> respondSigned(exchange, 503, false));
        PlatformSmsClient client = client(UUID.randomUUID());

        assertThatThrownBy(() -> client.reportRateLimit(
                        UUID.randomUUID(), UUID.randomUUID(), "text-basic", 17L).block())
                .isInstanceOf(PlatformSmsClient.PlatformSmsClientException.class)
                .hasMessageContaining("platform_sms_http_status")
                .satisfies(error -> assertThat(
                        ((PlatformSmsClient.PlatformSmsClientException) error).httpStatus())
                        .isEqualTo(503));
    }

    @Test
    void rejectsTamperedSignedErrorBeforeSemanticMapping() throws Exception {
        startServer("/internal/platform/sms/proofs/consume",
                exchange -> respondSigned(exchange, 422, true));
        PlatformSmsClient client = client(UUID.randomUUID());

        assertThatThrownBy(() -> client.consumeApiKeyResetProof(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "proof").block())
                .isInstanceOf(PlatformSmsClient.PlatformSmsClientException.class)
                .hasMessageContaining("platform_sms_response_signature_invalid")
                .satisfies(error -> assertThat(
                        ((PlatformSmsClient.PlatformSmsClientException) error).httpStatus())
                        .isNull());
    }

    @Test
    void cancelsChunkedResponseAsSoonAsItExceedsOneMiB() throws Exception {
        int responseSize = 8 * 1_048_576;
        AtomicLong bytesWritten = new AtomicLong();
        startServer("/internal/platform/sms/signals/rate-limit", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[8_192];
            try {
                while (bytesWritten.get() < responseSize) {
                    exchange.getResponseBody().write(chunk);
                    exchange.getResponseBody().flush();
                    bytesWritten.addAndGet(chunk.length);
                }
            } catch (IOException clientCancelled) {
                // 有界订阅者达到其上限后即属预期。
            } finally {
                exchange.close();
            }
        });
        PlatformSmsClient client = client(UUID.randomUUID());

        assertThatThrownBy(() -> client.reportRateLimit(
                        UUID.randomUUID(), UUID.randomUUID(), "text-basic", 1L).block())
                .hasMessageContaining("platform_sms_response_envelope_invalid");

        assertThat(bytesWritten.get()).isLessThan(responseSize);
    }

    @Test
    void rejectsOversizedContentLengthBeforeReadingTheResponseBody() throws Exception {
        CountDownLatch releaseServer = new CountDownLatch(1);
        startServer("/internal/platform/sms/signals/rate-limit", exchange -> {
            exchange.sendResponseHeaders(200, 1_048_577);
            try {
                releaseServer.await(5, TimeUnit.SECONDS);
            } finally {
                exchange.close();
            }
        });
        PlatformSmsClient client = client(UUID.randomUUID());
        long startedAt = System.nanoTime();

        try {
            assertThatThrownBy(() -> client.reportRateLimit(
                            UUID.randomUUID(), UUID.randomUUID(), "text-basic", 1L).block())
                    .hasMessageContaining("platform_sms_response_envelope_invalid");
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                    .isLessThan(Duration.ofSeconds(1));
        } finally {
            releaseServer.countDown();
        }
    }

    @Test
    void reportsDueAndExpiredPackagesWithMinimalSignalPayload() {
        PlatformSmsClient client = mock(PlatformSmsClient.class);
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID dueId = UUID.randomUUID();
        UUID expiredId = UUID.randomUUID();
        when(client.reportPackageExpiry(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.empty());
        PackageExpirySmsJob job = new PackageExpirySmsJob(client, () -> Flux.just(
                new PackageExpirySmsJob.PackageExpiryCandidate(
                        dueId, tenantId, userId, NOW.plus(Duration.ofDays(2))),
                new PackageExpirySmsJob.PackageExpiryCandidate(
                        expiredId, tenantId, userId, NOW.minus(Duration.ofDays(1)))));

        assertThat(job.reportOnce().block()).isEqualTo(2);

        verify(client).reportPackageExpiry(
                tenantId, userId, dueId, NOW.plus(Duration.ofDays(2)));
        verify(client).reportPackageExpiry(
                tenantId, userId, expiredId, NOW.minus(Duration.ofDays(1)));
    }

    @Test
    void reportsOnlyAtConfiguredSustainedRejectionThresholdWithinSixHourBucket() {
        PlatformSmsClient client = mock(PlatformSmsClient.class);
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID apiKeyId = UUID.randomUUID();
        AtomicLong rejections = new AtomicLong();
        when(client.reportRateLimit(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Mono.empty());
        RateLimitSmsReporter.RejectionCounter sharedCounter =
                (user, model, bucket, ttl) -> Mono.just(rejections.incrementAndGet());
        RateLimitSmsReporter firstInstance = new RateLimitSmsReporter(
                client,
                sharedCounter,
                (key, user) -> Mono.just(tenantId),
                CLOCK, 3, Duration.ofHours(6));
        RateLimitSmsReporter restartedSecondInstance = new RateLimitSmsReporter(
                client, sharedCounter, (key, user) -> Mono.just(tenantId),
                CLOCK, 3, Duration.ofHours(6));
        RequestLimitService.LimitCommand command = command(apiKeyId, userId);

        firstInstance.report(command).block();
        restartedSecondInstance.report(command).block();
        firstInstance.report(command).block();
        restartedSecondInstance.report(command).block();

        long bucket = NOW.toEpochMilli() / Duration.ofHours(6).toMillis();
        verify(client).reportRateLimit(tenantId, userId, "text-basic", bucket);
    }

    @Test
    void smsReporterFailureDoesNotChangeTheOriginalRateLimitRejection() {
        RateLimitSmsReporter reporter = mock(RateLimitSmsReporter.class);
        RequestLimitService.LimitStore rejected = new RequestLimitService.LimitStore() {
            @Override
            public Mono<RequestLimitService.LimitLease> acquire(
                    RequestLimitService.EffectiveLimit command) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS, "rate_limited"));
            }

            @Override
            public Mono<Void> release(RequestLimitService.LimitLease lease) {
                return Mono.empty();
            }
        };
        when(reporter.report(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.error(new IllegalStateException("sms unavailable")));
        DefaultRequestLimitService service = new DefaultRequestLimitService(
                rejected, rejected, 2, Duration.ofMillis(100), reporter);
        RequestLimitService.LimitCommand command = command(UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> service.acquire(command).block())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(reporter).report(command);
    }

    @Test
    void disabledReporterDoesNotTouchSharedCounterOrSignalClient() {
        PlatformSmsClient client = mock(PlatformSmsClient.class);
        AtomicLong counterCalls = new AtomicLong();
        RateLimitSmsReporter reporter = new RateLimitSmsReporter(
                client,
                (user, model, bucket, ttl) -> {
                    counterCalls.incrementAndGet();
                    return Mono.just(1L);
                },
                (key, user) -> Mono.just(UUID.randomUUID()),
                CLOCK, 1, Duration.ofHours(6), false);

        reporter.report(command(UUID.randomUUID(), UUID.randomUUID())).block();

        assertThat(counterCalls).hasValue(0L);
        verify(client, org.mockito.Mockito.never()).reportRateLimit(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void databaseFallbackRateLimitIsReportedWithoutChangingOriginalRejection() {
        RateLimitSmsReporter reporter = mock(RateLimitSmsReporter.class);
        RequestLimitService.LimitStore unavailable = new RequestLimitService.LimitStore() {
            @Override
            public Mono<RequestLimitService.LimitLease> acquire(
                    RequestLimitService.EffectiveLimit command) {
                return Mono.error(new IllegalStateException("redis unavailable"));
            }
            @Override
            public Mono<Void> release(RequestLimitService.LimitLease lease) {
                return Mono.empty();
            }
        };
        RequestLimitService.LimitStore rejectedFallback = new RequestLimitService.LimitStore() {
            @Override
            public Mono<RequestLimitService.LimitLease> acquire(
                    RequestLimitService.EffectiveLimit command) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS, "rate_limited"));
            }
            @Override
            public Mono<Void> release(RequestLimitService.LimitLease lease) {
                return Mono.empty();
            }
        };
        when(reporter.report(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        DefaultRequestLimitService service = new DefaultRequestLimitService(
                unavailable, rejectedFallback, 20, Duration.ofMillis(100), reporter);
        RequestLimitService.LimitCommand command = command(UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> service.acquire(command).block())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(reporter).report(command);
    }

    @Test
    void acquireRebindAndActivateShareOneUserModelRejectionThreshold() {
        PlatformSmsClient client = mock(PlatformSmsClient.class);
        UUID tenantId = UUID.randomUUID();
        UUID apiKeyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AtomicLong rejections = new AtomicLong();
        when(client.reportRateLimit(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Mono.empty());
        RateLimitSmsReporter reporter = new RateLimitSmsReporter(
                client,
                (user, model, bucket, ttl) -> Mono.just(rejections.incrementAndGet()),
                (key, user) -> Mono.just(tenantId),
                CLOCK, 3, Duration.ofHours(6));
        RequestLimitService.LimitStore rejected = rejectingStore();
        DefaultRequestLimitService service = new DefaultRequestLimitService(
                rejected, rejected, 2, Duration.ofMillis(100), reporter);
        RequestLimitService.LimitCommand command = command(apiKeyId, userId);
        RequestLimitService.LimitLease lease = lease(command);

        assertTooManyRequests(service.acquire(command));
        assertTooManyRequests(service.rebindChannel(lease, UUID.randomUUID()));
        assertTooManyRequests(service.activateRoute(lease, UUID.randomUUID(), 5, 5));

        assertThat(rejections).hasValue(3L);
        long bucket = NOW.toEpochMilli() / Duration.ofHours(6).toMillis();
        verify(client).reportRateLimit(tenantId, userId, "text-basic", bucket);
    }

    @Test
    void activateRouteReporterFailureKeepsTheOriginalRateLimitRejection() {
        PlatformSmsClient client = mock(PlatformSmsClient.class);
        UUID tenantId = UUID.randomUUID();
        UUID apiKeyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        long bucket = NOW.toEpochMilli() / Duration.ofHours(6).toMillis();
        when(client.reportRateLimit(tenantId, userId, "text-basic", bucket))
                .thenReturn(Mono.error(new IllegalStateException("sms unavailable")));
        RateLimitSmsReporter reporter = new RateLimitSmsReporter(
                client,
                (user, model, cooldownBucket, ttl) -> Mono.just(1L),
                (key, user) -> Mono.just(tenantId),
                CLOCK, 1, Duration.ofHours(6));
        RequestLimitService.LimitStore rejected = rejectingStore();
        DefaultRequestLimitService service = new DefaultRequestLimitService(
                rejected, rejected, 2, Duration.ofMillis(100), reporter);
        RequestLimitService.LimitLease lease = lease(command(apiKeyId, userId));

        assertTooManyRequests(service.activateRoute(lease, UUID.randomUUID(), 5, 5));

        verify(client, times(1)).reportRateLimit(tenantId, userId, "text-basic", bucket);
    }

    @Test
    void successfulAcquireAndRebindDoNotDoubleCountAnActivateRejection() {
        PlatformSmsClient client = mock(PlatformSmsClient.class);
        UUID tenantId = UUID.randomUUID();
        UUID apiKeyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AtomicLong rejections = new AtomicLong();
        when(client.reportRateLimit(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Mono.empty());
        RateLimitSmsReporter reporter = new RateLimitSmsReporter(
                client,
                (user, model, bucket, ttl) -> Mono.just(rejections.incrementAndGet()),
                (key, user) -> Mono.just(tenantId),
                CLOCK, 1, Duration.ofHours(6));
        RequestLimitService.LimitStore activateRejected = new RequestLimitService.LimitStore() {
            @Override
            public Mono<RequestLimitService.LimitLease> acquire(
                    RequestLimitService.EffectiveLimit command) {
                return Mono.just(new RequestLimitService.LimitLease(
                        UUID.randomUUID(), command.apiKeyId(), command.userId(),
                        command.modelCode(), command.channelId(), command.rpmLimit(),
                        command.concurrencyLimit(), RequestLimitService.Store.REDIS));
            }

            @Override
            public Mono<RequestLimitService.LimitLease> rebindChannel(
                    RequestLimitService.LimitLease lease, UUID channelId) {
                return Mono.just(new RequestLimitService.LimitLease(
                        lease.leaseId(), lease.apiKeyId(), lease.userId(), lease.modelCode(),
                        channelId, lease.rpmLimit(), lease.concurrencyLimit(), lease.store()));
            }

            @Override
            public Mono<RequestLimitService.LimitLease> activateRoute(
                    RequestLimitService.LimitLease lease,
                    UUID channelId,
                    int rpmLimit,
                    int concurrencyLimit) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS, "rate_limited"));
            }

            @Override
            public Mono<Void> release(RequestLimitService.LimitLease lease) {
                return Mono.empty();
            }
        };
        DefaultRequestLimitService service = new DefaultRequestLimitService(
                activateRejected, activateRejected, 2, Duration.ofMillis(100), reporter);
        RequestLimitService.LimitCommand command = command(apiKeyId, userId);

        Mono<RequestLimitService.LimitLease> request = service.acquire(command)
                .flatMap(acquired -> service.rebindChannel(acquired, UUID.randomUUID()))
                .flatMap(rebound -> service.activateRoute(rebound, UUID.randomUUID(), 5, 5));

        assertTooManyRequests(request);
        assertThat(rejections).hasValue(1L);
    }

    private RequestLimitService.LimitCommand command(UUID apiKeyId, UUID userId) {
        RequestLimitService.Thresholds limits = RequestLimitService.Thresholds.all(10);
        return new RequestLimitService.LimitCommand(
                apiKeyId, userId, "text-basic", UUID.randomUUID(), limits, limits,
                Duration.ofSeconds(30));
    }

    private RequestLimitService.LimitLease lease(RequestLimitService.LimitCommand command) {
        return new RequestLimitService.LimitLease(
                UUID.randomUUID(), command.apiKeyId(), command.userId(), command.modelCode(),
                command.channelId(), command.rpm().minimum(), command.concurrency().minimum(),
                RequestLimitService.Store.REDIS);
    }

    private RequestLimitService.LimitStore rejectingStore() {
        return new RequestLimitService.LimitStore() {
            @Override
            public Mono<RequestLimitService.LimitLease> acquire(
                    RequestLimitService.EffectiveLimit command) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS, "rate_limited"));
            }

            @Override
            public Mono<RequestLimitService.LimitLease> rebindChannel(
                    RequestLimitService.LimitLease lease, UUID channelId) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS, "rate_limited"));
            }

            @Override
            public Mono<RequestLimitService.LimitLease> activateRoute(
                    RequestLimitService.LimitLease lease,
                    UUID channelId,
                    int rpmLimit,
                    int concurrencyLimit) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS, "rate_limited"));
            }

            @Override
            public Mono<Void> release(RequestLimitService.LimitLease lease) {
                return Mono.empty();
            }
        };
    }

    private void assertTooManyRequests(Mono<?> operation) {
        assertThatThrownBy(operation::block)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    private PlatformSmsClient client(UUID requestId) {
        return new PlatformSmsClient(
                JSON, URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                SECRET, CLOCK, Duration.ofSeconds(2), () -> requestId);
    }

    private void startServer(String path, ThrowingHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception error) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        });
        server.start();
    }

    private void respondSigned(HttpExchange exchange, boolean tampered) throws Exception {
        respondSigned(exchange, 200, tampered);
    }

    private void respondSigned(HttpExchange exchange, int status, boolean tampered) throws Exception {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        if (requestBody.length > 0) {
            // 第一个测试在调用此辅助方法之前已经消费了请求体。
        }
        UUID requestId = UUID.fromString(
                exchange.getRequestHeaders().getFirst("X-Internal-Request-Id"));
        byte[] payload = "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
        String contentSha256 = sha256(payload);
        String signature = hmac(
                NOW.getEpochSecond() + "\n" + requestId + "\n" + status + "\n"
                        + contentSha256);
        if (tampered) {
            signature = "0".repeat(64);
        }
        byte[] envelope = JSON.writeValueAsBytes(Map.of(
                "requestId", requestId,
                "timestamp", NOW.getEpochSecond(),
                "contentSha256", contentSha256,
                "signature", signature,
                "payloadBase64", Base64.getEncoder().encodeToString(payload)));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, envelope.length);
        exchange.getResponseBody().write(envelope);
        exchange.close();
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private String hmac(String canonical) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private Set<String> iterable(java.util.Iterator<String> iterator) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        iterator.forEachRemaining(result::add);
        return result;
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}

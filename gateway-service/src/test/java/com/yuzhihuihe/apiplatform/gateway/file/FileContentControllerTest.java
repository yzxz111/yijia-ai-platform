package com.yuzhihuihe.apiplatform.gateway.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import reactor.core.publisher.Mono;

/**
 * 公网匿名读图端点的对外契约。
 *
 * <p>它是这套网关里**唯一**一个不需要 API key 就能读到文件内容的入口，
 * 因此这里钉的是「什么一定拿不到」：没有签名、签名属于别的文件、文件还没过扫描、
 * 文件已经不是图片。少任何一条，这个端点都会变成对象存储的公开下载口。
 *
 * <p>另外钉两条与限流相关的契约：**限流必须发生在准入之后**（否则垃圾请求能耗光
 * 真实厂商的额度），以及**超限返回对外信封里的 429**。
 */
@Testcontainers
class FileContentControllerTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String FILE_ID = "file_0123456789abcdef0123456789abcdef";
    private static final String OTHER_FILE_ID = "file_fedcba9876543210fedcba9876543210";
    private static final String OBJECT_KEY = "clean/files/" + FILE_ID + ".png";
    private static final UUID TENANT_ID = UUID.fromString("61000000-0000-4000-8000-0000000000aa");
    private static final UUID USER_ID = UUID.fromString("71000000-0000-4000-8000-0000000000aa");
    private static final byte[] PNG_BYTES =
            new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4};

    private FileObjectRepository repository;
    private FileObjectStore objectStore;
    private FileContentTokenSigner signer;
    private WebTestClient client;
    private LettuceConnectionFactory connectionFactory;
    private ReactiveStringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        repository = mock(FileObjectRepository.class);
        objectStore = mock(FileObjectStore.class);
        signer = signer();
        FileContentService service = new FileContentService(
                repository, objectStore, signer, 20L * 1024 * 1024,
                Clock.fixed(NOW, ZoneOffset.UTC));
        // 这些用例钉的是**准入**契约（签名、扫描状态、类型），不是限流语义；
        // 限流另由 FileContentRateLimiterTest 用真 Redis 覆盖。
        // 这里默认放行（fail-open），免得每个"应该 404"的断言都被限流器的 503 抢先。
        client = WebTestClient.bindToController(new FileContentController(
                service, new FileContentRateLimiter(null, 1000, Duration.ofMinutes(1), false)))
                .build();

        // 需要验证限流本身的用例用真 Redis（与 FileContentRateLimiterTest 同一口径：
        // 被测语义就是"固定窗口计数"，mock 掉 Redis 等于把被测对象删掉）。
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connectionFactory);
        redis.execute(connection -> connection.serverCommands().flushAll()).then().block();
    }

    @AfterEach
    void disconnectRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void rateLimiterRunsAfterAdmissionSoRejectedRequestsDoNotConsumeQuota() {
        // 顺序契约：签名错的请求**不该**被计数。否则任何人都能用垃圾请求
        // 把真实厂商的额度耗光（见控制器类注释与 FileContentRateLimiter）。
        // 做法：限流器只允许 1 次，先打两次签名错的请求，再打一次合法请求——
        // 合法请求必须仍然成功。若限流被放在准入之前，这里会拿到 429。
        FileContentService service = new FileContentService(
                repository, objectStore, signer, 20L * 1024 * 1024,
                Clock.fixed(NOW, ZoneOffset.UTC));
        FileContentRateLimiter limiter = new FileContentRateLimiter(
                redis, 1, Duration.ofMinutes(1), true);
        WebTestClient tight = WebTestClient.bindToController(
                new FileContentController(service, limiter)).build();

        tight.get().uri("/v1/files/" + FILE_ID + "/content?sig=bogus").exchange()
                .expectStatus().isNotFound();
        tight.get().uri("/v1/files/" + FILE_ID + "/content?sig=bogus").exchange()
                .expectStatus().isNotFound();

        when(repository.findReadyForRead(FILE_ID)).thenReturn(Mono.just(image("image/png")));
        when(objectStore.get(eq(OBJECT_KEY), anyLong())).thenReturn(Mono.just(PNG_BYTES));

        tight.get().uri(pathFor(FILE_ID)).exchange().expectStatus().isOk();
    }

    @Test
    void returns429InTheOpenAiEnvelopeOnceTheCallerExceedsTheLimit() {
        FileContentService service = new FileContentService(
                repository, objectStore, signer, 20L * 1024 * 1024,
                Clock.fixed(NOW, ZoneOffset.UTC));
        FileContentRateLimiter limiter = new FileContentRateLimiter(
                redis, 1, Duration.ofMinutes(1), true);
        WebTestClient tight = WebTestClient.bindToController(
                new FileContentController(service, limiter)).build();
        when(repository.findReadyForRead(FILE_ID)).thenReturn(Mono.just(image("image/png")));
        when(objectStore.get(eq(OBJECT_KEY), anyLong())).thenReturn(Mono.just(PNG_BYTES));

        tight.get().uri(pathFor(FILE_ID)).exchange().expectStatus().isOk();

        // 超限必须是对外契约里的 429 + rate_limit_error，且带 Retry-After。
        tight.get().uri(pathFor(FILE_ID)).exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("rate_limited")
                .jsonPath("$.error.type").isEqualTo("rate_limit_error");
    }

    @Test
    void servesTheImageBytesForAValidSignature() {
        when(repository.findReadyForRead(FILE_ID)).thenReturn(Mono.just(image("image/png")));
        when(objectStore.get(eq(OBJECT_KEY), anyLong())).thenReturn(Mono.just(PNG_BYTES));

        client.get().uri(pathFor(FILE_ID)).exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Content-Type", "image/png")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("Cache-Control", "private, max-age=300")
                .expectBody(byte[].class).isEqualTo(PNG_BYTES);
    }

    @Test
    void returns404WhenTheSignatureIsMissing() {
        client.get().uri("/v1/files/" + FILE_ID + "/content").exchange()
                .expectStatus().isNotFound();

        // 没有签名时连数据库都不该被碰：一次纯扫描不应变成对 file_object 的压力。
        verifyNoInteractions(repository);
        verifyNoInteractions(objectStore);
    }

    @Test
    void returns404WhenTheSignatureBelongsToAnotherFile() {
        when(repository.findReadyForRead(OTHER_FILE_ID)).thenReturn(Mono.just(image("image/png")));

        // 用 FILE_ID 签出来的地址去读另一个文件：签名与 fileId 绑死，必须被拒。
        client.get().uri(pathFor(FILE_ID).replace(FILE_ID, OTHER_FILE_ID)).exchange()
                .expectStatus().isNotFound();

        verifyNoInteractions(objectStore);
    }

    @Test
    void returns404WhenTheFileIsNotReadyOrAlreadyGone() {
        // 还没过安全扫描、被拒、已删除、已过期——仓库层一条都不返回。
        when(repository.findReadyForRead(FILE_ID)).thenReturn(Mono.empty());

        client.get().uri(pathFor(FILE_ID)).exchange()
                .expectStatus().isNotFound();

        verifyNoInteractions(objectStore);
    }

    @Test
    void returns404ForANonImageMimeType() {
        when(repository.findReadyForRead(FILE_ID))
                .thenReturn(Mono.just(image("application/pdf")));

        // 少这一条，这个端点就会变成对象存储的通用下载口。
        client.get().uri(pathFor(FILE_ID)).exchange()
                .expectStatus().isNotFound();

        verifyNoInteractions(objectStore);
    }

    @Test
    void returns404WhenTheStoredObjectIsMissingEvenThoughTheRowSaysReady() {
        when(repository.findReadyForRead(FILE_ID)).thenReturn(Mono.just(image("image/png")));
        when(objectStore.get(eq(OBJECT_KEY), anyLong()))
                .thenReturn(Mono.error(new FileObjectMissingException()));

        client.get().uri(pathFor(FILE_ID)).exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void neverCachesLongerThanTheFileRetention() {
        // 文件 60 秒后到期：缓存上限必须跟着它走，否则厂商会从一个被缓存到
        // 过期的地址上拿到 404。
        when(repository.findReadyForRead(FILE_ID))
                .thenReturn(Mono.just(image("image/jpeg", NOW.plusSeconds(60))));
        when(objectStore.get(eq(OBJECT_KEY), anyLong())).thenReturn(Mono.just(PNG_BYTES));

        client.get().uri(pathFor(FILE_ID)).exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "private, max-age=60");
    }

    @Test
    void exposesNoObjectKeyOrOriginalFilenameInTheResponse() {
        when(repository.findReadyForRead(FILE_ID)).thenReturn(Mono.just(image("image/png")));
        when(objectStore.get(eq(OBJECT_KEY), anyLong())).thenReturn(Mono.just(PNG_BYTES));

        String headers = client.get().uri(pathFor(FILE_ID)).exchange()
                .expectStatus().isOk()
                .returnResult(byte[].class)
                .getResponseHeaders()
                .toString();

        assertThat(headers).doesNotContain("clean/").doesNotContain("reference.png");
        // 地址本身也不携带它们——这是它会被转发给第三方的前提。
        assertThat(pathFor(FILE_ID)).doesNotContain("clean/").doesNotContain("reference.png");
    }

    private static FileObjectRepository.FileObject image(String mimeType) {
        return image(mimeType, NOW.plus(Duration.ofHours(24)));
    }

    private static FileObjectRepository.FileObject image(String mimeType, Instant expiresAt) {
        return new FileObjectRepository.FileObject(
                UUID.randomUUID(), FILE_ID, TENANT_ID, USER_ID, OBJECT_KEY,
                "reference.png", "sha256", mimeType, PNG_BYTES.length, "READY",
                expiresAt, null, NOW.minusSeconds(60));
    }

    private String pathFor(String fileId) {
        URI uri = URI.create(signer.signedUrl(fileId, NOW).orElseThrow());
        return uri.getPath() + "?" + uri.getQuery();
    }

    private static FileContentTokenSigner signer() {
        FileContentTokenProperties properties = new FileContentTokenProperties();
        properties.setSecret(Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII)));
        properties.setTtl(TTL);
        properties.setPublicBaseUrl(URI.create("https://api.yuzhh.com"));
        return new FileContentTokenSigner(properties);
    }
}

package com.yuzhihuihe.apiplatform.gateway.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.default-schema=gateway",
                "spring.flyway.schemas=gateway",
                "spring.data.redis.repositories.enabled=false",
                "security.api-key.pepper=test-api-key-pepper",
                "security.internal.hmac-secret=test-internal-secret"
        })
class ProviderPriceControllerTest {

    private static final String PRICE_PATH = "/internal/prices/non-promotional-output";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    // Signed internal requests must exercise the real, isolated Redis replay fence.
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void seedPrices() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO gateway.provider(id, code, name, provider_type) VALUES
            ('10000000-0000-4000-8000-000000000001', 'provider-a', 'Provider A', 'MOCK'),
            ('10000000-0000-4000-8000-000000000002', 'provider-b', 'Provider B', 'MOCK');
            INSERT INTO gateway.model(
                id, code, display_name, capabilities, context_window, max_output_tokens,
                premium, enabled, routing_strategy, routing_policy) VALUES
            ('20000000-0000-4000-8000-000000000001', 'price-test-model', 'Price Test', '[]',
             8192, 2048, false, true, 'DIRECT', '{}'::jsonb),
            ('20000000-0000-4000-8000-000000000002', 'price-test-unpriced', 'Price Test Unpriced', '[]',
             8192, 2048, false, true, 'DIRECT', '{}'::jsonb),
            ('20000000-0000-4000-8000-000000000003', 'price-test-composite-incomplete',
             'Price Test Composite Incomplete', '[]', 8192, 2048, false, true, 'COMPOSITE',
             '{"textModel":"price-test-model","imageModel":"price-test-unpriced","fileModel":"price-test-model","mixedFinalModel":"price-test-model","extractorVersion":"price-test-v1"}'::jsonb),
            ('20000000-0000-4000-8000-000000000004', 'price-test-composite-malformed',
             'Price Test Composite Malformed', '[]', 8192, 2048, false, true, 'COMPOSITE',
             '{"textModel":123,"imageModel":"price-test-model","fileModel":"price-test-model","mixedFinalModel":"price-test-model","extractorVersion":"price-test-v1"}'::jsonb);
            INSERT INTO gateway.model_alias(id, model_id, alias) VALUES
            ('21000000-0000-4000-8000-000000000001',
             '20000000-0000-4000-8000-000000000001', 'price-test-model'),
            ('21000000-0000-4000-8000-000000000002',
             '20000000-0000-4000-8000-000000000002', 'price-test-unpriced'),
            ('21000000-0000-4000-8000-000000000003',
             '20000000-0000-4000-8000-000000000001', '123');
                    INSERT INTO gateway.provider_model_price(
                        id, provider_id, model_id, input_price_per_million,
                        output_price_per_million, internal_exchange_rate, effective_from) VALUES
                    ('30000000-0000-4000-8000-000000000001',
                     '10000000-0000-4000-8000-000000000001',
                     '20000000-0000-4000-8000-000000000001', 20, 100, 1, now() - interval '1 day'),
                    ('30000000-0000-4000-8000-000000000002',
                     '10000000-0000-4000-8000-000000000002',
                     '20000000-0000-4000-8000-000000000001', 30, 150, 1.2, now() - interval '1 day');
                    """);
        }
    }

    @Autowired
    WebTestClient webTestClient;

    @Test
    void returnsHighestCurrentOutputPriceForRequestedModels() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());

        signedGet(PRICE_PATH + "?models=price-test-model,missing-model", PRICE_PATH, timestamp)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.price-test-model").isEqualTo(180)
                .jsonPath("$.missing-model").doesNotExist();
    }

    @Test
    void derivesCompositeOutputPriceFromMostExpensiveConfiguredComponent() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());

        // multimodal-basic 是 text-basic、vision-basic 和 file-basic 的复合模型，因此它的
        // 价格是三者中最贵的。V48 把 text-basic 改为按已开票的
        // deepseek-flash 基准计价（输出 800 分/M），这现在成了上限，高于
        // vision-basic（720）和 file-basic（200）。
        signedGet(PRICE_PATH + "?models=multimodal-basic", PRICE_PATH, timestamp)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.multimodal-basic").isEqualTo(800);
    }

    @Test
    void omitsCompositeOutputPriceWhenAnyConfiguredComponentLacksCurrentPrice() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());

        signedGet(PRICE_PATH + "?models=price-test-model,price-test-composite-incomplete", PRICE_PATH, timestamp)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.price-test-model").isEqualTo(180)
                .jsonPath("$.price-test-composite-incomplete").doesNotExist();
    }

    @Test
    void omitsCompositeOutputPriceWhenAConfiguredComponentIsNotAString() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());

        signedGet(PRICE_PATH + "?models=price-test-composite-malformed", PRICE_PATH, timestamp)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.price-test-composite-malformed").doesNotExist();
    }

    @Test
    void returnsProviderPriceSnapshotWithOfficialSourceAndEffectiveTime() throws Exception {
        String path = "/internal/provider-prices/snapshot";
        String timestamp = Long.toString(Instant.now().getEpochSecond());

        // 快照必须携带当前行发布时所依据的来源，这样运维人员
        // 就能看到是哪份文档确定了成本：V48 把 text-standard 重新基准化为
        // DeepSeek 发票加上供应商价格表，而不是旧的文档 URL。
        signedGet(path, path, timestamp)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[?(@.modelCode == 'text-standard')].officialSource")
                .isEqualTo("invoice:amount-2026-08-15_2026-09-14.xlsx:deepseek-v4-flash"
                        + " + pdf:大模型Token价格对比表(2):DeepSeek V4 Flash")
                .jsonPath("$[0].effectiveFrom").exists();
    }

    @Test
    void resolvesThePerImageCostBasisForAnImageAlias() throws Exception {
        String path = "/internal/prices/effective-unit-cost";
        String timestamp = Long.toString(Instant.now().getEpochSecond());

        // 平台把 VIDEO_SECOND 与 IMAGE 都送到这同一条查询上（PackageVersionService
        // 对两个维度一视同仁）。图片别名解析不到成本时发布守卫会以
        // provider_price_missing fail-closed，于是图片版本能靠迁移写入，
        // 却永远无法在控制台里创建或重发——这正是这条联合查询存在的原因。
        // 单位不同：video-basic 是分/秒（69），image-basic 是分/张（62，
        // 即 V82 之后的最坏情况基准：输出图 60 分 + 输入图 2 分，
        // 与 platform 镜像登记的 effective_unit_cost 一致）。
        signedGet(path + "?models=video-basic,image-basic", path, timestamp)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.video-basic").isEqualTo(69)
                .jsonPath("$.image-basic").isEqualTo(62);
    }

    @Test
    void omitsAnAliasThatIsPricedInBothGenerationDimensions() throws Exception {
        String path = "/internal/prices/effective-unit-cost";
        String timestamp = Long.toString(Instant.now().getEpochSecond());

        // 一个别名同时持有分/秒与分/张的当前行时，返回任意一行都是静默的定价错误，
        // 因为两个维度的单位不同。它因此被整体丢弃，调用方随之 fail-closed。
        try {
            execute("""
                    INSERT INTO gateway.model_usage_price(
                        id, model_code, usage_dimension, resolution_tier,
                        minimum_units, minimum_charge, unit_price,
                        provider_unit_cost, retry_rate, target_margin_ratio, min_absolute_margin,
                        price_version, effective_from, official_source)
                    VALUES (gen_random_uuid(), 'image-basic', 'VIDEO_SECOND', '720P',
                        1, 100, 100, 69, 0.15000, 0.30000, 30, 'ambiguous-v1', now(), 'test')
                    """);

            signedGet(path + "?models=image-basic", path, timestamp)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.image-basic").doesNotExist();
        } finally {
            execute("""
                    DELETE FROM gateway.model_usage_price
                    WHERE model_code = 'image-basic' AND usage_dimension = 'VIDEO_SECOND'
                      AND price_version = 'ambiguous-v1'
                    """);
        }
    }

    @Test
    void resolvesTheWorstCaseTierWhenOneAliasPricesMoreThanOneImageSize() throws Exception {
        String path = "/internal/prices/effective-unit-cost";
        String timestamp = Long.toString(Instant.now().getEpochSecond());

        // V115 起 image-basic 同时持有 1.5K 与 2K 两行当前费率（两行同价，62）。
        // 同一维度内的多个**尺寸档位**不是"混用单位"：把它们一并丢弃会让
        // 每一个图片版本都无法上架（provider_price_missing），而那与真正的
        // 故障（一个别名同时按秒与按张计价）完全不同。
        //
        // 取的是 max()：**最贵的一档**，也就是平台实际承担的最坏情况。这里刻意
        // 插入一个更贵的档位，证明答案跟着最贵的那一行走，而不是取最便宜的。
        try {
            execute("""
                    INSERT INTO gateway.model_usage_price(
                        id, model_code, usage_dimension, resolution_tier,
                        minimum_units, minimum_charge, unit_price,
                        provider_unit_cost, retry_rate, target_margin_ratio, min_absolute_margin,
                        price_version, effective_from, official_source)
                    VALUES (gen_random_uuid(), 'image-basic', 'IMAGE', '4K',
                        1, 142, 142, 99, 0.00000, 0.30000, 25, 'worst-case-v1', now(), 'test')
                    """);

            signedGet(path + "?models=image-basic", path, timestamp)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.image-basic").isEqualTo(99);
        } finally {
            execute("""
                    DELETE FROM gateway.model_usage_price
                    WHERE model_code = 'image-basic' AND usage_dimension = 'IMAGE'
                      AND resolution_tier = '4K' AND price_version = 'worst-case-v1'
                    """);
        }

        // 删掉之后回到两行同价的最坏情况 62。
        signedGet(path + "?models=image-basic", path, timestamp)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.image-basic").isEqualTo(62);
    }

    private WebTestClient.RequestHeadersSpec<?> signedGet(String uri, String path, String timestamp)
            throws Exception {
        UUID requestId = UUID.randomUUID();
        String contentSha256 = sha256(new byte[0]);
        return webTestClient.get().uri(uri)
                .header("X-Internal-Timestamp", timestamp)
                .header("X-Internal-Request-Id", requestId.toString())
                .header("X-Internal-Content-Sha256", contentSha256)
                .header("X-Internal-Signature", signature(
                        timestamp, requestId, "GET", path, contentSha256));
    }

    private String signature(
            String timestamp, UUID requestId, String method, String path, String contentSha256)
            throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-internal-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(
                (timestamp + "\n" + requestId + "\n" + method + "\n" + path + "\n" + contentSha256)
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private String sha256(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}

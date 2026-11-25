package com.yuzhihuihe.apiplatform.gateway.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.quota.ReservationRecoveryJob;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;

/**
 * 针对真实数据库的端到端视频计费。
 *
 * <p>这些测试之所以存在，是因为视频的金钱行为恰恰是无法靠读代码
 * 检查的那部分：任务会被反复轮询，厂商对失败的生成可能计费也可能不
 * 计费，而客户端可能完全停止轮询。每个测试把其中一种结果
 * 钉到一个具体的配额效果上。
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=gateway",
        "spring.flyway.schemas=gateway",
        "spring.data.redis.repositories.enabled=false",
        "security.api-key.pepper=test-api-key-pepper",
        "security.internal.hmac-secret=test-internal-secret",
        "gateway.video.allow-mock=true",
        "gateway.video.recovery.fixed-delay-ms=3600000",
        "gateway.video.recovery.initial-delay-ms=3600000"
})
class VideoGenerationBillingTest {

    private static final UUID USER_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final UUID PACKAGE_ID = UUID.fromString("50000000-0000-4000-8000-000000000003");
    private static final UUID API_KEY_ID = UUID.fromString("50000000-0000-4000-8000-000000000004");
    private static final UUID PROVIDER_ID = UUID.fromString("50000000-0000-4000-8000-000000000005");
    private static final UUID CHANNEL_ID = UUID.fromString("50000000-0000-4000-8000-000000000006");
    private static final UUID VIDEO_MODEL_ID = UUID.fromString("50000000-0000-4000-8000-000000000007");

    private static final int PACKAGE_SECONDS = 60;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void seedPackageAndChannel() throws Exception {
        channelBaseUrl = "mock://video/succeed";
        execute("""
                TRUNCATE gateway.video_generation_task, gateway.quota_ledger,
                         gateway.quota_reservation, gateway.package_instance_model_quota,
                         gateway.package_instance, gateway.api_key, gateway.channel_model,
                         gateway.channel, gateway.provider, gateway.model_alias, gateway.model
                         CASCADE
                """);

        // 一个视频别名、一条声明 VIDEO 能力的厂商渠道，以及一个
        // 以秒计量、授予其中 60 秒的套餐。
        execute("""
                INSERT INTO gateway.model(id, code, display_name, capabilities,
                                          context_window, max_output_tokens, enabled)
                VALUES ('%s', 'video-basic', 'Video Basic', '["TEXT","IMAGE","VIDEO"]',
                        1, 1, true)
                """.formatted(VIDEO_MODEL_ID));
        execute("""
                INSERT INTO gateway.model_alias(id, model_id, alias)
                VALUES (gen_random_uuid(), '%s', 'video-basic')
                """.formatted(VIDEO_MODEL_ID));
        execute("""
                INSERT INTO gateway.video_alias_tier(model_code, resolution_tier)
                VALUES ('video-basic', '720P')
                """);
        execute("""
                INSERT INTO gateway.provider(id, code, name, provider_type, base_url, enabled)
                VALUES ('%s', 'mock-video', 'Mock Video', 'MOCK', 'mock://video', true)
                """.formatted(PROVIDER_ID));
        execute("""
                INSERT INTO gateway.channel(id, provider_id, code, name, environment, base_url,
                                            enabled, priority, weight, timeout_ms, last_health_status)
                VALUES ('%s', '%s', 'mock-video-primary', 'Mock Video Primary', 'PRODUCTION',
                        '%s', true, 100, 100, 60000, 'HEALTHY')
                """.formatted(CHANNEL_ID, PROVIDER_ID, channelBaseUrl));
        execute("""
                INSERT INTO gateway.channel_model(id, channel_id, model_id, upstream_model,
                                                  enabled, capabilities)
                VALUES (gen_random_uuid(), '%s', '%s', 'mock-video-1', true, '["TEXT","IMAGE","VIDEO"]')
                """.formatted(CHANNEL_ID, VIDEO_MODEL_ID));

        execute("""
                INSERT INTO gateway.package_instance(
                    id, grant_event_id, grant_request_id, tenant_id, user_id, identity_key,
                    package_plan_version_id, package_code, package_name, package_kind,
                    price_amount, currency, allowed_models, total_token_quota,
                    max_api_keys, requests_per_minute, tokens_per_minute, max_concurrency,
                    duration_days, status, starts_at, activated_at, expires_at, usage_dimension)
                VALUES ('%s', gen_random_uuid(), gen_random_uuid(), '%s', '%s', 'identity-key',
                        gen_random_uuid(), 'VIDEO_TEST', 'Video Test', 'STANDARD',
                        12900, 'CNY',
                        '[{"modelCode":"video-basic","modelTokenQuota":null,"capabilities":["TEXT","IMAGE","VIDEO"]}]'::jsonb,
                        %d, 2, 20, %d, 2, 30, 'ACTIVE',
                        now(), now(), now() + interval '30 days', 'VIDEO_SECOND')
                """.formatted(PACKAGE_ID, TENANT_ID, USER_ID, PACKAGE_SECONDS, PACKAGE_SECONDS));
        execute("""
                INSERT INTO gateway.package_instance_model_quota(
                    id, package_instance_id, model_code, usage_dimension, token_quota,
                    seconds_quota, reserved_tokens, used_tokens)
                VALUES (gen_random_uuid(), '%s', 'video-basic', 'VIDEO_SECOND', NULL,
                        %d, 0, 0)
                """.formatted(PACKAGE_ID, PACKAGE_SECONDS));
        execute("""
                INSERT INTO gateway.api_key(id, tenant_id, user_id, package_instance_id, name,
                                            key_prefix, key_hash, allowed_models, status)
                VALUES ('%s', '%s', '%s', '%s', 'video-key', 'vk_test', 'hash-video-key',
                        '["video-basic"]'::jsonb, 'ACTIVE')
                """.formatted(API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID));
    }

    /**
     * 该渠道被配置为哪种 mock 结果。每次路由解析时都会重新读取，
     * 因此测试可以在调用服务之前改动它。
     */
    static String channelBaseUrl = "mock://video/succeed";

    @Autowired
    VideoGenerationService service;

    @Autowired
    ReservationRecoveryJob reservationRecoveryJob;

    @Test
    void pollingAFinishedTaskRepeatedlyBillsTheSecondsExactlyOnce() throws Exception {
        // “succeed” 需要两次轮询：第一次报告 RUNNING，第二次 SUCCEEDED。
        VideoGenerationService.TaskView submitted = submit("req-succeed", 8);
        assertThat(submitted.status()).isEqualTo("SUBMITTED");
        assertThat(usedSeconds()).isZero();
        assertThat(reservedSeconds()).isEqualTo(8);

        assertThat(service.status(apiKey(), "req-succeed").block().status()).isEqualTo("RUNNING");
        assertThat(usedSeconds()).isZero();

        VideoGenerationService.TaskView done = service.status(apiKey(), "req-succeed").block();
        assertThat(done.status()).isEqualTo("SUCCEEDED");
        assertThat(done.billedSeconds()).isEqualTo(8);
        assertThat(usedSeconds()).isEqualTo(8);
        assertThat(reservedSeconds()).isZero();
        // 客户端拿到的是一个网关路径，而该路径解析到厂商的
        // 位置——那个位置绝不是客户端看到的 URL。
        assertThat(done.outputUrl()).isEqualTo("/v1/videos/req-succeed/content");
        assertThat(service.contentUrl(apiKey(), "req-succeed").block())
                .startsWith("mock://video/output/succeed/req-succeed/");

        // 整件事的关键：后续轮询绝不能再计费。
        for (int i = 0; i < 3; i++) {
            assertThat(service.status(apiKey(), "req-succeed").block().billedSeconds()).isEqualTo(8);
        }
        assertThat(usedSeconds()).isEqualTo(8);
        assertThat(ledgerSettleCount()).isEqualTo(1);
    }

    @Test
    void anUnfinishedOrFailedTaskHasNoRetrievableOutput() throws Exception {
        // 在厂商产出视频之前，什么都不得被提供：运行中的任务还没有
        // 位置，失败的任务则完全没有，即便厂商对这次尝试
        // 计了费。数据库检查为此兜底，因此该列无法声称别的。
        submit("req-pending", 4);
        assertThat(service.status(apiKey(), "req-pending").block().outputUrl()).isNull();
        assertThat(contentFailure("req-pending")).isEqualTo("video_output_not_ready");

        VideoGenerationService.TaskView failed = submitAndPollToTerminal(
                "req-failed", 4, "mock://video/fail");
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.outputUrl()).isNull();
        assertThat(contentFailure("req-failed")).isEqualTo("video_output_not_ready");
        assertThat(vendorOutputUrl("req-failed")).isNull();
    }

    @Test
    void anotherUsersKeyCannotReadOrFetchTheTask() throws Exception {
        // 请求 id 不是一种能力凭证。陌生人得到的 404 与未知 id 相同，
        // 因此这些端点无法被用来发现哪些任务 id 存在。
        submit("req-owned", 4);
        ApiKeyService.ApiKeyContext stranger = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, UUID.randomUUID(), PACKAGE_ID, Set.of("video-basic"), true);

        assertThatThrownBy(() -> service.status(stranger, "req-owned").block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.contentUrl(stranger, "req-owned").block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.contentUrl(apiKey(), "req-never-existed").block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        // 所有者不受这些尝试的影响。
        assertThat(service.status(apiKey(), "req-owned").block().status()).isEqualTo("RUNNING");
    }

    @Test
    void aVendorThatBillsFailedGenerationsChargesTheAttempt() throws Exception {
        // mock://video/fail 把请求的时长报告为可计费，这正是
        // Kling 的行为。客户为这次尝试付费。
        VideoGenerationService.TaskView view = submitAndPollToTerminal(
                "req-fail-billed", 6, "mock://video/fail");

        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.billedSeconds()).isEqualTo(6);
        assertThat(usedSeconds()).isEqualTo(6);
        assertThat(ledgerSettleCount()).isEqualTo(1);
    }

    @Test
    void aVendorThatDoesNotBillFailedGenerationsChargesNothing() throws Exception {
        // mock://video/fail-free 报告可计费秒数为零，这正是 Veo 的
        // 行为。客户绝不应为他们从未拿到的视频付费。
        VideoGenerationService.TaskView view = submitAndPollToTerminal(
                "req-fail-free", 6, "mock://video/fail-free");

        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.billedSeconds()).isZero();
        assertThat(usedSeconds()).isZero();
        assertThat(reservedSeconds()).isZero();
    }

    @Test
    void resubmittingTheSameRequestIdDoesNotBuyASecondGeneration() throws Exception {
        VideoGenerationService.TaskView first = submit("req-idempotent", 5);
        VideoGenerationService.TaskView second = submit("req-idempotent", 5);

        assertThat(second.requestId()).isEqualTo(first.requestId());
        assertThat(count("gateway.video_generation_task")).isEqualTo(1);
        // 只有一条预留，因此秒数只被划出过一次。
        assertThat(reservedSeconds()).isEqualTo(5);
    }

    @Test
    void anotherAccountsTaskIdCannotBeReplayedIntoTheirTask() throws Exception {
        // 幂等键由客户端选定，因此与另一个账号的任务
        // 相撞的键必须 fail-closed：既不返回那个任务，也不在
        // 第一个账号已拥有的 id 下再买第二次生成。
        submit("req-taken", 4);
        ApiKeyService.ApiKeyContext stranger = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, UUID.randomUUID(), PACKAGE_ID, Set.of("video-basic"), true);

        assertThatThrownBy(() -> service.submit(new VideoGenerationService.SubmitCommand(
                        "req-taken", stranger, "video-basic", "another prompt", null,
                        4, "720P", null, false))
                .block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getReason()).isEqualTo("video_request_id_conflict");
                });

        assertThat(count("gateway.video_generation_task")).isEqualTo(1);
        assertThat(reservedSeconds()).isEqualTo(4);
    }

    @Test
    void recoveryPollsOnceBeforeReleasingAStuckTask() throws Exception {
        // 清扫不是白送：它会先向上游做最后一次轮询，因此在客户端
        // 停止轮询之后才完成的生成会被计费，而不是被
        // 释放。这就是客户拿到他们付过费的东西，
        // 与平台白白为厂商付费之间的区别。
        execute("UPDATE gateway.channel SET base_url = 'mock://video/succeed-immediately' "
                + "WHERE id = '" + CHANNEL_ID + "'");
        submit("req-recovered", 5);
        assertThat(reservedSeconds()).isEqualTo(5);

        service.recoverExpired(taskRow("req-recovered")).block();

        assertThat(statusOf("req-recovered")).isEqualTo("SUCCEEDED");
        assertThat(usedSeconds()).isEqualTo(5);
        assertThat(reservedSeconds()).isZero();
        assertThat(ledgerSettleCount()).isEqualTo(1);
    }

    /**
     * 上游仍报非终态时，恢复流程会在 TTL 之后**收尾**，而不是无限期保持预留。
     *
     * <p>本测试此前断言的是相反的规则（「绝不释放」）。旧理由依然成立：存在任务行
     * 意味着供应商接受了提交，而最后返回 {@code RUNNING} 并不能证明该生成被放弃，
     * 所以**不能**在第一时间释放——否则客户端会重试并针对同一套餐余额买第二次。
     *
     * <p>但那条规则没有上界，而没有上界的持有会变成一个客户看不见的故障：
     * 线上用户 {@code 23837b95} 的 5 条视频预留停在 {@code RESERVED} 整整 17 天，
     * 合计恰好等于他视频套餐全部剩余额度，他一秒视频都生成不出来。
     *
     * <p>现在的规则是**有上界的持有**：本方法的调用条件是任务已过 TTL
     * （见 {@code VideoTaskRecoveryJob} 的 {@code expires_at} 过滤），所以到这里
     * 就已经是「再等也不会有新信息」的时刻。「有界」由调用方保证。
     */
    @Test
    void expiryRecoveryReleasesAfterTheTtlEvenWhenTheProviderStillReportsNonTerminal()
            throws Exception {
        execute("UPDATE gateway.channel SET base_url = 'mock://video/stuck' WHERE id = '"
                + CHANNEL_ID + "'");
        submit("req-recovery-stuck", 7);
        assertThat(reservedSeconds()).isEqualTo(7);

        service.recoverExpired(taskRow("req-recovery-stuck")).block();

        assertThat(statusOf("req-recovery-stuck")).isEqualTo("CANCELLED");
        assertThat(usedSeconds()).isZero();
        // 秒数回到了客户手里：他什么都没拿到，因此也不该被扣。
        assertThat(reservedSeconds()).isZero();
    }

    @Test
    void genericReservationRecoveryDoesNotReleaseVideoSecondsWithoutTerminalEvidence()
            throws Exception {
        submit("req-recovery-unknown", 7);
        execute("UPDATE gateway.quota_reservation SET expires_at = now() - interval '1 minute' "
                + "WHERE request_id = 'req-recovery-unknown'");

        int recovered = reservationRecoveryJob.recoverOnce().block();

        assertThat(recovered).isZero();
        assertThat(reservedSeconds()).isEqualTo(7);
        assertThat(queryText("SELECT status FROM gateway.quota_reservation "
                + "WHERE request_id = 'req-recovery-unknown'")).isEqualTo("RESERVED");
    }

    /**
     * 但那个「保持」现在是**有上界**的：过了宽限期就收尾并释放。
     *
     * <p>线上实证逼出了这条：一条音频预留的**任务行已经是 {@code FAILED}**，
     * 因此各领域的恢复扫描（只扫非终态任务）永远看不见它，而通用清扫此前拒不放行
     * ——它就那样卡了一天多。任何「任务已终态、但释放没成功」的行都会永久锁住
     * 客户的额度，而且没有任何东西会来纠正。
     *
     * <p>所以上一条规则与这一条并不矛盾，它们合起来才是完整语义：
     * **宽限期内保持（避免重复扣费），之后不再拖（避免永久锁死）。**
     */
    @Test
    void genericReservationRecoveryReleasesAfterTheGraceWindowWithoutTerminalEvidence()
            throws Exception {
        submit("req-recovery-orphan", 7);
        execute("UPDATE gateway.quota_reservation SET expires_at = now() - interval '3 hours' "
                + "WHERE request_id = 'req-recovery-orphan'");

        int recovered = reservationRecoveryJob.recoverOnce().block();

        assertThat(recovered).isEqualTo(1);
        // 秒数回到了客户手里。
        assertThat(reservedSeconds()).isZero();
        assertThat(queryText("SELECT status FROM gateway.quota_reservation "
                + "WHERE request_id = 'req-recovery-orphan'")).isNotEqualTo("RESERVED");
    }

    @Test
    void anExplicitReleaseCanCloseAStuckTaskForOperatorReconciliation() throws Exception {
        // mock://video/stuck 永远不会进入终态。这里直接调用显式的
        // 释放钩子；自动过期恢复有意不调用它，
        // 除非有证据表明上游从未接受该生成。
        channelBaseUrl = "mock://video/stuck";
        execute("UPDATE gateway.channel SET base_url = 'mock://video/stuck' WHERE id = '"
                + CHANNEL_ID + "'");
        VideoGenerationService.TaskView submitted = submit("req-stuck", 7);
        assertThat(submitted.status()).isEqualTo("SUBMITTED");
        assertThat(reservedSeconds()).isEqualTo(7);

        VideoTaskRepository.TaskRow task = taskRow("req-stuck");
        service.releaseExpired(task).block();

        assertThat(usedSeconds()).isZero();
        assertThat(reservedSeconds()).isZero();
        assertThat(statusOf("req-stuck")).isEqualTo("CANCELLED");
    }

    @Test
    void reservesSecondsAgainstThePackageSoAnOverlongRequestCannotStart() throws Exception {
        // 该套餐授予 60 秒，而第一个请求划出了其中 50 秒，因此
        // 只剩 10 秒。以秒计的套餐是纯预付费：没有钱包可以
        // 兜底，而放行该请求会买下套餐无法覆盖的上游 GPU 时间。
        // 它必须作为配额问题失败，而不是作为未定价模型失败。
        submit("req-first", 50);
        assertThat(reservedSeconds()).isEqualTo(50);

        assertThatThrownBy(() -> submit("req-too-long", 20))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("quota_exhausted");
                });

        // 超出 API 自身的上限时，请求根本到不了配额层。
        assertThatThrownBy(() -> submit("req-over-api-limit", 80))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("invalid_video_request"));

        assertThat(count("gateway.video_generation_task")).isEqualTo(1);
        assertThat(usedSeconds()).isZero();
        assertThat(reservedSeconds()).isEqualTo(50);
    }

    // ---- 辅助方法 ----------------------------------------------------------------

    private VideoGenerationService.TaskView submitAndPollToTerminal(String requestId, int seconds) {
        submit(requestId, seconds);
        // “succeed”/“fail” 两个系列在结算之前都需要一次中间轮询。
        service.status(apiKey(), requestId).block();
        return service.status(apiKey(), requestId).block();
    }

    /**
     * @param baseUrl mock 结果，例如 {@code mock://video/fail-free}。在提交之前
     *        施加，因为渠道在每次路由查找时都会被重新读取。
     */
    private VideoGenerationService.TaskView submitAndPollToTerminal(
            String requestId, int seconds, String baseUrl) throws Exception {
        channelBaseUrl = baseUrl;
        execute("UPDATE gateway.channel SET base_url = '" + baseUrl + "' WHERE id = '"
                + CHANNEL_ID + "'");
        return submitAndPollToTerminal(requestId, seconds);
    }

    private VideoGenerationService.TaskView submit(String requestId, int seconds) {
        return service.submit(new VideoGenerationService.SubmitCommand(
                requestId,
                apiKey(),
                "video-basic",
                "a lighthouse at dusk",
                null,
                seconds,
                "720P",
                null,
                false)).block();
    }

    private ApiKeyService.ApiKeyContext apiKey() {
        return new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("video-basic"), true);
    }

    private VideoTaskRepository.TaskRow taskRow(String requestId) throws Exception {
        return service.openTasks(50)
                .filter(row -> requestId.equals(row.requestId()))
                .blockFirst();
    }

    private long usedSeconds() throws Exception {
        return queryLong("SELECT used_tokens FROM gateway.package_instance WHERE id = '"
                + PACKAGE_ID + "'");
    }

    private long reservedSeconds() throws Exception {
        return queryLong("SELECT reserved_tokens FROM gateway.package_instance WHERE id = '"
                + PACKAGE_ID + "'");
    }

    private String statusOf(String requestId) throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT status FROM gateway.video_generation_task WHERE request_id = '"
                                + requestId + "'")) {
            result.next();
            return result.getString(1);
        }
    }

    private long ledgerSettleCount() throws Exception {
        return queryLong("SELECT count(*) FROM gateway.quota_ledger WHERE entry_type = 'SETTLE'");
    }

    /** @return 内容请求被拒绝的原因，解析成功时为 null */
    private String contentFailure(String requestId) {
        try {
            service.contentUrl(apiKey(), requestId).block();
            return null;
        } catch (ResponseStatusException expected) {
            return expected.getReason();
        }
    }

    private String vendorOutputUrl(String requestId) throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT output_url FROM gateway.video_generation_task WHERE request_id = '"
                                + requestId + "'")) {
            result.next();
            return result.getString(1);
        }
    }

    private long queryLong(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private String queryText(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private int count(String table) throws Exception {
        return (int) queryLong("SELECT count(*) FROM " + table);
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}

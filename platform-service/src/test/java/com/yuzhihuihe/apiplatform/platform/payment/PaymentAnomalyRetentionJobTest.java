package com.yuzhihuihe.apiplatform.platform.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 限制无效回调异常证据的增长。
 *
 * <p>以前每一次无效的支付回调都会插入一行以 {@code sha256(body)} 为键的永久
 * {@code payment_anomaly} 记录，于是一个未认证的调用方每改动一个字节就能
 * 永久新增一行。这里固定了两道控制：同一提供方在
 * {@code payment.callback.anomaly-window} 内的无效回调会合并成一行 OPEN 记录并统计尝试次数，
 * 而保留任务会以有界批次删除早于 {@code payment.anomaly.retention} 的
 * 证据。
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false",
        "payment.callback.anomaly-window=1h",
        "payment.anomaly.retention=30d",
        "payment.anomaly.cleanup-batch-size=2",
        "payment.anomaly.cleanup-delay-ms=3600000",
        "outbox.publisher.initial-delay-ms=3600000"
})
class PaymentAnomalyRetentionJobTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestJwtKeyRing.configure(registry);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PaymentPostingService postingService;

    @Autowired
    PaymentAnomalyRetentionJob retentionJob;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("TRUNCATE platform.payment_anomaly RESTART IDENTITY CASCADE");
    }

    @Test
    void repeatedInvalidCallbacksInOneWindowCollapseIntoOneCountedAnomaly() {
        for (int index = 0; index < 25; index++) {
            postingService.recordInvalidCallback(
                    PaymentProvider.ALIPAY,
                    ("tampered-body-" + index).getBytes(StandardCharsets.UTF_8));
        }

        assertThat(singleLong("SELECT count(*) FROM platform.payment_anomaly"))
                .as("25 distinct bodies from one provider in one window")
                .isEqualTo(1);
        assertThat(singleLong("SELECT attempt_count FROM platform.payment_anomaly"))
                .as("collapsed attempts are still counted")
                .isEqualTo(25);
        assertThat(singleLong("""
                SELECT count(*) FROM platform.payment_anomaly
                WHERE anomaly_type = 'CALLBACK_SIGNATURE_INVALID'
                  AND provider = 'ALIPAY'
                  AND status = 'OPEN'
                  AND detail = '{"reason":"SIGNATURE_INVALID"}'::jsonb
                """)).isEqualTo(1);
    }

    @Test
    void providersDoNotShareACollapsedAnomaly() {
        postingService.recordInvalidCallback(
                PaymentProvider.ALIPAY, "body-a".getBytes(StandardCharsets.UTF_8));
        postingService.recordInvalidCallback(
                PaymentProvider.WECHAT_PAY, "body-b".getBytes(StandardCharsets.UTF_8));

        assertThat(singleLong("SELECT count(*) FROM platform.payment_anomaly")).isEqualTo(2);
    }

    @Test
    void retentionDeletesOnlyAnomaliesOlderThanTheConfiguredPeriod() {
        UUID old = insertAnomaly("a".repeat(64), Instant.now().minus(java.time.Duration.ofDays(31)));
        UUID recent = insertAnomaly("b".repeat(64), Instant.now().minus(java.time.Duration.ofDays(1)));

        assertThat(retentionJob.runOnce()).isEqualTo(1);

        assertThat(exists(old)).as("31-day-old anomaly is deleted").isFalse();
        assertThat(exists(recent)).as("1-day-old anomaly is kept").isTrue();
    }

    @Test
    void retentionDeletesAtMostOneBoundedBatchPerRun() {
        for (int index = 0; index < 5; index++) {
            insertAnomaly(
                    String.format("%064d", index),
                    Instant.now().minus(java.time.Duration.ofDays(40 + index)));
        }

        // 在本上下文中 payment.anomaly.cleanup-batch-size 为 2。
        assertThat(retentionJob.runOnce()).isEqualTo(2);
        assertThat(singleLong("SELECT count(*) FROM platform.payment_anomaly")).isEqualTo(3);
        assertThat(retentionJob.runOnce()).isEqualTo(2);
        assertThat(retentionJob.runOnce()).isEqualTo(1);
        assertThat(retentionJob.runOnce()).isZero();
    }

    private UUID insertAnomaly(String fingerprint, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO platform.payment_anomaly(
                    id, fingerprint, anomaly_type, provider, status, detail,
                    created_at, updated_at)
                VALUES (?, ?, 'CALLBACK_SIGNATURE_INVALID', 'ALIPAY', 'OPEN',
                        '{"reason":"SIGNATURE_INVALID"}'::jsonb, ?, ?)
                """, id, fingerprint, Timestamp.from(createdAt), Timestamp.from(createdAt));
        return id;
    }

    private boolean exists(UUID id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM platform.payment_anomaly WHERE id = ?)",
                Boolean.class, id));
    }

    private long singleLong(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }
}

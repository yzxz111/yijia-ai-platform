package com.yuzhihuihe.apiplatform.platform.sms;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.security.SecretEncryptionService;

abstract class SmsPostgresTestSupport {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");
    private static final DataSource DATA_SOURCE;

    static {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        DATA_SOURCE = dataSource;
        Flyway.configure()
                .dataSource(DATA_SOURCE)
                .defaultSchema("platform")
                .schemas("platform")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    final JdbcTemplate jdbc = new JdbcTemplate(DATA_SOURCE);
    final PlatformTransactionManager transactions = new DataSourceTransactionManager(DATA_SOURCE);
    final ObjectMapper objectMapper = new ObjectMapper();
    final SecretEncryptionService encryption = new SecretEncryptionService(
            Base64.getEncoder().encodeToString(new byte[32]));
    final PhoneProtectionService phones = new PhoneProtectionService(
            encryption, Base64.getEncoder().encodeToString(keyBytes((byte) 0x31)));
    final MutableClock clock = new MutableClock(Instant.parse("2026-08-04T00:00:00Z"));

    @BeforeEach
    void resetSmsState() {
        jdbc.execute("""
                TRUNCATE TABLE platform.sms_delivery, platform.sms_proof,
                    platform.sms_verification_challenge, platform.app_user CASCADE
                """);
        clock.set(Instant.parse("2026-08-04T00:00:00Z"));
    }

    void insertUser(UUID tenantId, UUID userId, boolean notificationsEnabled) {
        jdbc.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name,
                    sms_notifications_enabled)
                VALUES (?, ?, ?, 'test-hash', 'SMS test user', ?)
                """, userId, tenantId, userId + "@example.invalid", notificationsEnabled);
    }

    void bindPhone(UUID userId, String phone) {
        PhoneProtectionService.ProtectedPhone protectedPhone = phones.protect(phone);
        jdbc.update("""
                UPDATE platform.app_user
                SET phone_ciphertext = ?, phone_lookup_hash = ?, phone_last4 = ?,
                    phone_verified_at = now()
                WHERE id = ?
                """, protectedPhone.ciphertext(), protectedPhone.lookupHash(),
                protectedPhone.last4(), userId);
    }

    SmsProofService proofs(java.security.SecureRandom random) {
        return new SmsProofService(
                new SmsProofRepository(jdbc), transactions, clock, random, Duration.ofMinutes(5));
    }

    SmsNotificationService notifications() {
        return new SmsNotificationService(
                new SmsNotificationRepository(jdbc), objectMapper, encryption, clock);
    }

    static byte[] keyBytes(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

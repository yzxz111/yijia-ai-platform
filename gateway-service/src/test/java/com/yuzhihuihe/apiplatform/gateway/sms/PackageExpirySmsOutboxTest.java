package com.yuzhihuihe.apiplatform.gateway.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import reactor.core.publisher.Mono;

@Testcontainers
class PackageExpirySmsOutboxTest {

    private static final String GATEWAY_USER = "gateway_expiry_test";
    private static final String GATEWAY_PASSWORD = "gateway-expiry-test-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private static DatabaseClient database;

    @BeforeAll
    static void migrateGateway() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + GATEWAY_USER + " LOGIN PASSWORD '"
                    + GATEWAY_PASSWORD + "'");
            statement.execute("CREATE SCHEMA gateway AUTHORIZATION " + GATEWAY_USER);
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), GATEWAY_USER, GATEWAY_PASSWORD)
                .defaultSchema("gateway")
                .schemas("gateway")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        String r2dbcUrl = "r2dbc:postgresql://" + GATEWAY_USER + ":" + GATEWAY_PASSWORD
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
                + "/" + POSTGRES.getDatabaseName();
        database = DatabaseClient.create(ConnectionFactories.get(r2dbcUrl));
    }

    @BeforeEach
    void clearPackages() {
        database.sql("TRUNCATE gateway.package_instance CASCADE")
                .fetch().rowsUpdated().block(Duration.ofSeconds(5));
    }

    @Test
    void successfulBatchAdvancesToLaterPackagesOnTheNextRun() {
        PlatformSmsClient client = mock(PlatformSmsClient.class);
        when(client.reportPackageExpiry(any(), any(), any(), any())).thenReturn(Mono.empty());
        Instant baseExpiry = Instant.now().truncatedTo(ChronoUnit.MICROS)
                .minus(Duration.ofDays(10));
        PackageRow first = insertExpiredPackage(baseExpiry);
        PackageRow second = insertExpiredPackage(baseExpiry.plusSeconds(1));
        PackageRow third = insertExpiredPackage(baseExpiry.plusSeconds(2));
        PackageExpirySmsJob job = new PackageExpirySmsJob(database, client, 2);

        assertThat(job.reportOnce().block(Duration.ofSeconds(5))).isEqualTo(2);
        assertThat(job.reportOnce().block(Duration.ofSeconds(5))).isEqualTo(1);

        verify(client).reportPackageExpiry(
                first.tenantId(), first.userId(), first.id(), first.expiresAt());
        verify(client).reportPackageExpiry(
                second.tenantId(), second.userId(), second.id(), second.expiresAt());
        verify(client).reportPackageExpiry(
                third.tenantId(), third.userId(), third.id(), third.expiresAt());
    }

    @Test
    void failedOldPackageBacksOffWithoutBlockingLaterPackages() {
        PlatformSmsClient client = mock(PlatformSmsClient.class);
        when(client.reportPackageExpiry(any(), any(), any(), any())).thenReturn(Mono.empty());
        Instant baseExpiry = Instant.now().truncatedTo(ChronoUnit.MICROS)
                .minus(Duration.ofDays(10));
        PackageRow first = insertExpiredPackage(baseExpiry);
        insertExpiredPackage(baseExpiry.plusSeconds(1));
        PackageRow third = insertExpiredPackage(baseExpiry.plusSeconds(2));
        when(client.reportPackageExpiry(
                eq(first.tenantId()), eq(first.userId()), eq(first.id()), eq(first.expiresAt())))
                .thenReturn(Mono.error(new IllegalStateException("platform unavailable")));
        PackageExpirySmsJob job = new PackageExpirySmsJob(database, client, 2);

        assertThat(job.reportOnce().block(Duration.ofSeconds(5))).isEqualTo(1);
        assertThat(job.reportOnce().block(Duration.ofSeconds(5))).isEqualTo(1);

        verify(client, times(1)).reportPackageExpiry(
                first.tenantId(), first.userId(), first.id(), first.expiresAt());
        verify(client).reportPackageExpiry(
                third.tenantId(), third.userId(), third.id(), third.expiresAt());
    }

    @Test
    void warningAndExpiredStatesAreEachReportedSuccessfullyOnlyOnce() {
        PlatformSmsClient client = mock(PlatformSmsClient.class);
        when(client.reportPackageExpiry(any(), any(), any(), any())).thenReturn(Mono.empty());
        Instant warningExpiry = Instant.now().truncatedTo(ChronoUnit.MICROS)
                .plus(Duration.ofDays(2));
        PackageRow row = insertPackage("ACTIVE", warningExpiry);
        PackageExpirySmsJob job = new PackageExpirySmsJob(database, client, 10);

        assertThat(job.reportOnce().block(Duration.ofSeconds(5))).isEqualTo(1);
        Instant expiredAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
                .minus(Duration.ofHours(1));
        database.sql("""
                        UPDATE gateway.package_instance
                        SET status = 'EXPIRED', expires_at = :expires_at, updated_at = now()
                        WHERE id = :id
                        """)
                .bind("expires_at", expiredAt)
                .bind("id", row.id())
                .fetch().rowsUpdated().block(Duration.ofSeconds(5));
        assertThat(job.reportOnce().block(Duration.ofSeconds(5))).isEqualTo(1);
        assertThat(job.reportOnce().block(Duration.ofSeconds(5))).isZero();

        verify(client).reportPackageExpiry(
                row.tenantId(), row.userId(), row.id(), warningExpiry);
        verify(client).reportPackageExpiry(
                row.tenantId(), row.userId(), row.id(), expiredAt);
        assertThat(database.sql("""
                        SELECT signal_state || ':' || status AS state
                        FROM gateway.package_expiry_sms_outbox
                        WHERE package_instance_id = :package_id
                        ORDER BY signal_state
                        """)
                .bind("package_id", row.id())
                .map((result, metadata) -> result.get("state", String.class))
                .all().collectList().block(Duration.ofSeconds(5)))
                .containsExactly("EXPIRED:SENT", "WARNING:SENT");
    }

    private PackageRow insertExpiredPackage(Instant expiresAt) {
        return insertPackage("EXPIRED", expiresAt);
    }

    private PackageRow insertPackage(String status, Instant expiresAt) {
        PackageRow row = new PackageRow(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), expiresAt);
        database.sql("""
                        INSERT INTO gateway.package_instance(
                            id, grant_event_id, grant_request_id, tenant_id, user_id,
                            package_plan_version_id, package_code, package_name,
                            price_amount, currency, allowed_models, total_token_quota,
                            max_api_keys, requests_per_minute, tokens_per_minute,
                            max_concurrency, duration_days, status, starts_at, expires_at,
                            identity_key, created_at, updated_at)
                        VALUES (
                            :id, :grant_event_id, :grant_request_id, :tenant_id, :user_id,
                            :plan_id, 'EXPIRY_TEST', 'Expiry test', 0, 'CNY', '[]'::jsonb,
                            1000, 1, 10, 1000, 1, 30, :status, :starts_at, :expires_at,
                            :identity_key, :created_at, :created_at)
                        """)
                .bind("id", row.id())
                .bind("grant_event_id", UUID.randomUUID())
                .bind("grant_request_id", UUID.randomUUID())
                .bind("tenant_id", row.tenantId())
                .bind("user_id", row.userId())
                .bind("plan_id", UUID.randomUUID())
                .bind("status", status)
                .bind("starts_at", expiresAt.minus(Duration.ofDays(30)))
                .bind("expires_at", expiresAt)
                .bind("identity_key", row.userId().toString())
                .bind("created_at", expiresAt.minus(Duration.ofDays(30)))
                .fetch().rowsUpdated().block(Duration.ofSeconds(5));
        return row;
    }

    private record PackageRow(
            UUID id, UUID tenantId, UUID userId, Instant expiresAt) {
    }
}

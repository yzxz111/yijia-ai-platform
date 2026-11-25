package com.yuzhihuihe.apiplatform.platform.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false",
        "outbox.publisher.initial-delay-ms=3600000"
})
class RefundPolicyServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("41000000-0000-4000-8000-000000000001");
    private static final UUID ADMIN_ID =
            UUID.fromString("41000000-0000-4000-8000-000000000002");

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
    RefundPolicyService service;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE platform.refund, platform.refund_policy_version CASCADE");
        jdbcTemplate.update("DELETE FROM platform.admin_audit_log");
        jdbcTemplate.update("DELETE FROM platform.user_role WHERE user_id = ?", ADMIN_ID);
        jdbcTemplate.update("DELETE FROM platform.app_user WHERE id = ?", ADMIN_ID);
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, 'refund-admin@example.com', 'hash', 'Refund Admin', 'ACTIVE')
                """, ADMIN_ID, TENANT_ID);
    }

    @Test
    void selectsLatestPublishedPolicyEffectiveAtRequestedTime() {
        insertPolicy(1, 100, 0, 7, 1000, "2030-01-01T00:00:00Z", true);
        insertPolicy(2, 200, 10, 8, 900, "2030-02-01T00:00:00Z", false);
        insertPolicy(3, 300, 20, 9, 800, "2030-02-01T00:00:00Z", true);
        insertPolicy(4, 400, 30, 10, 700, "2030-03-01T00:00:00Z", true);

        RefundPolicyService.RefundPolicy policy =
                service.effectiveAt(Instant.parse("2030-02-15T00:00:00Z"));

        assertThat(policy.version()).isEqualTo(3);
        assertThat(policy.feeBps()).isEqualTo(300);
        assertThat(service.listPublished(0, 100).items())
                .extracting(RefundPolicyService.RefundPolicy::version)
                .containsExactly(4, 3, 1);
    }

    @Test
    void listsPublishedPoliciesInDescendingPagesWithTotalAndValidatedBounds() {
        insertPolicy(1, 100, 0, 7, 1000, "2030-01-01T00:00:00Z", true);
        insertPolicy(2, 200, 0, 7, 1000, "2030-02-01T00:00:00Z", true);
        insertPolicy(3, 300, 0, 7, 1000, "2030-03-01T00:00:00Z", false);
        insertPolicy(4, 400, 0, 7, 1000, "2030-04-01T00:00:00Z", true);
        insertPolicy(5, 500, 0, 7, 1000, "2030-05-01T00:00:00Z", true);

        RefundPolicyService.PageResult<RefundPolicyService.RefundPolicy> result =
                service.listPublished(1, 2);

        assertThat(result.items()).extracting(RefundPolicyService.RefundPolicy::version)
                .containsExactly(2, 1);
        assertThat(result.total()).isEqualTo(4);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(2);
        assertThatThrownBy(() -> service.listPublished(-1, 20))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("invalid_refund_policy_page");
                });
        assertThatThrownBy(() -> service.listPublished(0, 0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_refund_policy_page");
        assertThatThrownBy(() -> service.listPublished(0, 101))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_refund_policy_page");
    }

    @Test
    void rejectsWhenNoPublishedPolicyIsEffective() {
        insertPolicy(1, 100, 0, 7, 1000, "2030-01-01T00:00:00Z", true);

        assertThatThrownBy(() -> service.effectiveAt(Instant.parse("2029-12-31T23:59:59Z")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("refund_policy_not_found");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void publishesFuturePolicyAndWritesAudit() {
        int version = 42;
        Instant effectiveAt = Instant.now().plusSeconds(3600);

        RefundPolicyService.RefundPolicy published = service.publish(
                ADMIN_ID,
                new RefundPolicyService.PublishPolicyCommand(
                        version, 500, 100, 7, 1000, effectiveAt));

        assertThat(published.version()).isEqualTo(version);
        assertThat(published.published()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.admin_audit_log
                WHERE actor_user_id = ? AND action = 'REFUND_POLICY_PUBLISHED'
                  AND resource_id = ? AND outcome = 'SUCCESS'
                """, Long.class, ADMIN_ID, Integer.toString(version))).isEqualTo(1);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsDuplicateVersionAndInvalidBoundsOrNonFutureEffectiveTime() {
        insertPolicy(7, 100, 0, 7, 1000, "2030-01-01T00:00:00Z", true);
        Instant future = Instant.now().plusSeconds(3600);

        assertRejected(new RefundPolicyService.PublishPolicyCommand(
                7, 500, 100, 7, 1000, future), "refund_policy_version_exists");
        assertRejected(new RefundPolicyService.PublishPolicyCommand(
                8, -1, 100, 7, 1000, future), "invalid_refund_policy");
        assertRejected(new RefundPolicyService.PublishPolicyCommand(
                8, 10_001, 100, 7, 1000, future), "invalid_refund_policy");
        assertRejected(new RefundPolicyService.PublishPolicyCommand(
                8, 500, -1, 7, 1000, future), "invalid_refund_policy");
        assertRejected(new RefundPolicyService.PublishPolicyCommand(
                8, 500, 100, 0, 1000, future), "invalid_refund_policy");
        assertRejected(new RefundPolicyService.PublishPolicyCommand(
                8, 500, 100, 7, 10_001, future), "invalid_refund_policy");
        assertRejected(new RefundPolicyService.PublishPolicyCommand(
                8, 500, 100, 7, 1000, Instant.now().minusSeconds(1)),
                "refund_policy_effective_at_must_be_future");
    }

    @Test
    @WithMockUser(roles = "USER")
    void nonAdministratorCannotPublish() {
        assertThatThrownBy(() -> service.publish(
                ADMIN_ID,
                new RefundPolicyService.PublishPolicyCommand(
                        9, 500, 100, 7, 1000, Instant.now().plusSeconds(3600))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void databaseKeepsPublishedRowsImmutable() {
        insertPolicy(10, 100, 0, 7, 1000, "2030-01-01T00:00:00Z", true);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE platform.refund_policy_version SET fee_bps = 200 WHERE version = 10"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("published_refund_policy_immutable");
    }

    @Test
    void concurrentDuplicatePublicationReturnsOneStableVersionConflict() throws Exception {
        int version = 77;
        Instant effectiveAt = Instant.now().plusSeconds(3600);
        RefundPolicyService.PublishPolicyCommand command =
                new RefundPolicyService.PublishPolicyCommand(
                        version, 500, 100, 7, 1000, effectiveAt);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = List.of(
                    executor.submit(() -> publishAsAdmin(start, command)),
                    executor.submit(() -> publishAsAdmin(start, command)));
            start.countDown();
            List<Object> results = List.of(futures.get(0).get(), futures.get(1).get());

            assertThat(results.stream().filter(RefundPolicyService.RefundPolicy.class::isInstance))
                    .hasSize(1);
            assertThat(results.stream().filter(ResponseStatusException.class::isInstance))
                    .singleElement()
                    .asString()
                    .contains("refund_policy_version_exists");
        }
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.refund_policy_version WHERE version = ?
                """, Long.class, version)).isEqualTo(1);
    }

    private Object publishAsAdmin(
            CountDownLatch start, RefundPolicyService.PublishPolicyCommand command) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new TestingAuthenticationToken(
                "admin", "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(context);
        try {
            start.await();
            return service.publish(ADMIN_ID, command);
        } catch (RuntimeException exception) {
            return exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void assertRejected(
            RefundPolicyService.PublishPolicyCommand command, String reason) {
        assertThatThrownBy(() -> service.publish(ADMIN_ID, command))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(reason);
    }

    private void insertPolicy(
            int version,
            int feeBps,
            long minimumFee,
            int refundWindowDays,
            int maximumUsageBps,
            String effectiveAt,
            boolean published) {
        jdbcTemplate.update("""
                INSERT INTO platform.refund_policy_version(
                    version, fee_bps, minimum_fee, refund_window_days,
                    maximum_usage_bps, effective_at, published)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, version, feeBps, minimumFee, refundWindowDays,
                maximumUsageBps, Timestamp.from(Instant.parse(effectiveAt)), published);
    }
}

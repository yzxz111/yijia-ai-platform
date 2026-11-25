package com.yuzhihuihe.apiplatform.platform.wallet;

import static org.assertj.core.api.Assertions.assertThat;

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

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false"
})
class WalletAccountServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("81000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("81000000-0000-4000-8000-000000000002");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestJwtKeyRing.configure(registry);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    WalletAccountService service;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("TRUNCATE platform.app_user RESTART IDENTITY CASCADE");
        jdbcTemplate.update("""
                INSERT INTO platform.app_user(
                    id, tenant_id, email, password_hash, display_name, status)
                VALUES (?, ?, 'wallet-account@example.test', 'hash', 'Wallet Account', 'ACTIVE')
                """, USER_ID, TENANT_ID);
    }

    @Test
    void provisionCreatesOneZeroCnyAccountAndSeedsOptionsIdempotently() {
        WalletAccountService.WalletAccount first = service.provision(TENANT_ID, USER_ID);
        WalletAccountService.WalletAccount replay = service.provision(TENANT_ID, USER_ID);

        assertThat(first.tenantId()).isEqualTo(TENANT_ID);
        assertThat(first.userId()).isEqualTo(USER_ID);
        assertThat(first.currency()).isEqualTo("CNY");
        assertThat(first.availableAmount()).isZero();
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM platform.account_balance
                WHERE tenant_id = ? AND user_id = ? AND currency = 'CNY'
                """, Integer.class, TENANT_ID, USER_ID)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
                SELECT amount FROM platform.recharge_option
                WHERE tenant_id = ? ORDER BY amount
                """, Long.class, TENANT_ID)).containsExactly(
                        1000L, 3000L, 5000L, 10000L, 20000L, 30000L, 50000L);
    }
}

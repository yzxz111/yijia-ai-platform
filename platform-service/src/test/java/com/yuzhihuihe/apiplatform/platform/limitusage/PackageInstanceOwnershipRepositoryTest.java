package com.yuzhihuihe.apiplatform.platform.limitusage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 归属校验是挡在调用方与他人套餐计数之间的唯一屏障，
 * 而它是一个数据库谓词 —— 因此它针对真实的 Postgres 进行验证，
 * 而不是只针对 mock 验证。
 *
 * <p>这里手工创建表，只包含查询声称需要的那些列，这会使测试在
 * SQL 一旦开始读取不存在的列、或关联到归属校验不该触碰的表时失败。
 * 不应用任何 platform 迁移，也不应用任何 gateway 迁移，因此
 * 在别人编辑任一套迁移时这个测试都不会被破坏。
 */
@Testcontainers(disabledWithoutDocker = true)
class PackageInstanceOwnershipRepositoryTest {

    private static final UUID TENANT_ID = UUID.fromString("91000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("91000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("91000000-0000-4000-8000-000000000003");
    private static final UUID OTHER_TENANT_ID =
            UUID.fromString("91000000-0000-4000-8000-000000000004");
    private static final UUID INSTANCE_ID = UUID.fromString("91000000-0000-4000-8000-000000000005");
    private static final UUID UNKNOWN_INSTANCE_ID =
            UUID.fromString("91000000-0000-4000-8000-000000000006");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private PackageInstanceOwnershipRepository repository;

    @BeforeEach
    void createGatewayTable() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS gateway");
        jdbc.execute("DROP TABLE IF EXISTS gateway.package_instance");
        jdbc.execute("""
                CREATE TABLE gateway.package_instance (
                    id uuid PRIMARY KEY,
                    tenant_id uuid NOT NULL,
                    user_id uuid NOT NULL)
                """);
        jdbc.update("""
                INSERT INTO gateway.package_instance(id, tenant_id, user_id) VALUES (?, ?, ?)
                """, INSTANCE_ID, TENANT_ID, USER_ID);
        repository = new PackageInstanceOwnershipRepository(jdbc);
    }

    @Test
    void recognisesOnlyTheInstanceOwnedByTheCallersTenantAndUser() {
        assertThat(repository.isOwnedByCaller(INSTANCE_ID, TENANT_ID, USER_ID)).isTrue();
        assertThat(repository.isOwnedByCaller(INSTANCE_ID, TENANT_ID, OTHER_USER_ID)).isFalse();
        assertThat(repository.isOwnedByCaller(INSTANCE_ID, OTHER_TENANT_ID, USER_ID)).isFalse();
        assertThat(repository.isOwnedByCaller(UNKNOWN_INSTANCE_ID, TENANT_ID, USER_ID)).isFalse();
    }

    @Test
    void treatsAnIncompleteIdentityAsNotOwnedWithoutQuerying() {
        assertThat(repository.isOwnedByCaller(null, TENANT_ID, USER_ID)).isFalse();
        assertThat(repository.isOwnedByCaller(INSTANCE_ID, null, USER_ID)).isFalse();
        assertThat(repository.isOwnedByCaller(INSTANCE_ID, TENANT_ID, null)).isFalse();
    }
}

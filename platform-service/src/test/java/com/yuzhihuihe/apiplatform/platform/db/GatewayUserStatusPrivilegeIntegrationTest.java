package com.yuzhihuihe.apiplatform.platform.db;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class GatewayUserStatusPrivilegeIntegrationTest {

    private static final String GATEWAY_USER = "gateway_app";
    private static final String GATEWAY_PASSWORD = "gateway-test-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeAll
    static void migrateWithGatewayRolePresent() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE ROLE gateway_app LOGIN PASSWORD 'gateway-test-password'");
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                // V1 的第一条语句就是 CREATE TABLE platform.app_user，它假定
                // platform 这个 schema 已经存在；本仓库其余每一个直接调用
                // Flyway 的测试都显式声明了 defaultSchema/schemas，只有这里
                // 漏了，于是 Flyway 从不在这个干净的 postgres:17-alpine 容器里
                // 建 schema，迁移在第一条语句就以 3F000 "schema platform does
                // not exist" 失败——而这与它真正要验证的东西（gateway_app
                // 能读 platform.gateway_user_status 但读不了 platform.app_user）
                // 毫无关系。生产侧对应的是 spring.flyway.default-schema /
                // spring.flyway.schemas 两个属性。
                .defaultSchema("platform")
                .schemas("platform")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void gatewayRoleCanReadStatusViewButNotBaseUserTable() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), GATEWAY_USER, GATEWAY_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.executeQuery(
                    "SELECT tenant_id, id, status FROM platform.gateway_user_status LIMIT 1");

            assertThatThrownBy(() -> statement.executeQuery(
                    "SELECT tenant_id, id, status FROM platform.app_user LIMIT 1"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }
}

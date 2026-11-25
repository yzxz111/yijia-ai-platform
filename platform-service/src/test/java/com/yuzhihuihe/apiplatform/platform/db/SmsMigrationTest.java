package com.yuzhihuihe.apiplatform.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class SmsMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("platform")
                .schemas("platform")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void approvedNotificationTemplatesAreActive() throws SQLException {
        assertThat(templateStatus("BALANCE_LOW")).isEqualTo("ACTIVE");
        assertThat(templateStatus("SUPPORT_TICKET_COMPLETED")).isEqualTo("ACTIVE");
        assertThat(templateStatus("LOCATION_LOGIN_WARNING")).isEqualTo("ACTIVE");
    }

    private static String templateStatus(String templateKey) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement("""
                        SELECT integration_status FROM platform.sms_template WHERE template_key=?
                        """)) {
            statement.setString(1, templateKey);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }
}

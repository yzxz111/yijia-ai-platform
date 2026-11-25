package com.yuzhihuihe.apiplatform.platform.db;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

class GatewayUserStatusAccessMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V47__gateway_user_status_least_privilege.sql";

    @Test
    void exposesOnlyStatusColumnsAndRevokesBaseTableAccess() throws IOException {
        try (InputStream migration = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(migration).as("migration %s exists", MIGRATION).isNotNull();

            String sql = new String(migration.readAllBytes(), UTF_8)
                    .replaceAll("\\s+", " ")
                    .trim();
            assertThat(sql)
                    .contains("CREATE OR REPLACE VIEW platform.gateway_user_status")
                    .contains("SELECT tenant_id, id, status FROM platform.app_user")
                    .contains("REVOKE SELECT ON platform.app_user FROM gateway_app")
                    .contains("GRANT SELECT ON platform.gateway_user_status TO gateway_app")
                    .doesNotContain("GRANT SELECT ON platform.app_user TO gateway_app");
        }
    }
}

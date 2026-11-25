package com.yuzhihuihe.apiplatform.platform.db;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

class GlobalAdministratorMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V33__global_administrator.sql";

    @Test
    void createsExplicitProtectedGlobalAdministratorIdentity() throws IOException {
        try (InputStream migration = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(migration).as("migration %s exists", MIGRATION).isNotNull();

            String sql = new String(migration.readAllBytes(), UTF_8)
                    .replaceAll("\\s+", " ")
                    .trim();
            assertThat(sql)
                    .contains("ADD COLUMN global_admin boolean NOT NULL DEFAULT false")
                    .contains("70000000-0000-4000-8000-000000000001")
                    .contains("SET status = 'ACTIVE', global_admin = true")
                    .contains("CREATE UNIQUE INDEX uq_app_user_single_global_admin")
                    .contains("CHECK (NOT global_admin OR status = 'ACTIVE')")
                    .contains("app_role.code = 'ADMIN'")
                    .contains("global_admin_immutable")
                    .contains("global_admin_role_immutable");
        }
    }
}

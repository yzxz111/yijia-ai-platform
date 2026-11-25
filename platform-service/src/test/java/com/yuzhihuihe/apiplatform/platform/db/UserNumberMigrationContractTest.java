package com.yuzhihuihe.apiplatform.platform.db;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

class UserNumberMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V32__randomize_new_user_numbers.sql";

    @Test
    void randomizesNewUserNumbersWithoutInvalidatingLegacyValues() throws IOException {
        try (InputStream migration = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(migration).as("migration %s exists", MIGRATION).isNotNull();

            String sql = new String(migration.readAllBytes(), UTF_8)
                    .replaceAll("\\s+", " ")
                    .trim();
            assertThat(sql)
                    .contains("ALTER COLUMN user_no TYPE varchar(9)")
                    .contains("DROP TRIGGER IF EXISTS trg_app_user_user_no_immutable ON platform.app_user")
                    .contains("U[0-9A-Z]{8}")
                    .contains("uuid_send(gen_random_uuid())")
                    .contains("LOOP")
                    .contains("NOT EXISTS")
                    .contains("OWNED BY NONE")
                    .contains("CREATE TRIGGER trg_app_user_user_no_immutable");
        }
    }
}

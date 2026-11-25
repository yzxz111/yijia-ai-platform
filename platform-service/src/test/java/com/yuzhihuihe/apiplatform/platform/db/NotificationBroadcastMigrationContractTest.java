package com.yuzhihuihe.apiplatform.platform.db;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

class NotificationBroadcastMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V31__notification_broadcast_business_no_unique.sql";

    @Test
    void createsUniqueIndexForProviderBusinessNumber() throws IOException {
        try (InputStream migration = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(migration).as("migration %s exists", MIGRATION).isNotNull();

            String sql = new String(migration.readAllBytes(), UTF_8)
                    .replaceAll("\\s+", " ")
                    .trim();
            assertThat(sql).isEqualTo("""
                    CREATE UNIQUE INDEX uq_notification_broadcast_provider_business_no ON \
                    platform.notification_broadcast(provider_business_no);
                    """.replaceAll("\\s+", " ").trim());
        }
    }
}

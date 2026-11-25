package com.yuzhihuihe.apiplatform.platform.payment;

import java.sql.Timestamp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class PaymentAvailabilityRepository {

    private final JdbcTemplate jdbc;

    PaymentAvailabilityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean isEnabled(PaymentProvider provider, PaymentEnvironment environment) {
        return jdbc.query("""
                        SELECT enabled
                        FROM platform.payment_config
                        WHERE provider = ? AND environment = ?
                        """,
                (result, rowNumber) -> result.getBoolean("enabled"),
                provider.name(), environment.name())
                .stream().findFirst().orElse(false);
    }

    int synchronizeConfiguration(
            PaymentProvider provider,
            PaymentEnvironment environment,
            PaymentAdapter.AdapterConfiguration configuration) {
        Timestamp validatedAt = configuration.validatedAt() == null
                ? null : Timestamp.from(configuration.validatedAt());
        return jdbc.update("""
                        UPDATE platform.payment_config
                        SET credential_fingerprint = ?, last_validation_status = ?,
                            last_validated_at = ?, updated_at = now()
                        WHERE provider = ? AND environment = ?
                          AND (credential_fingerprint IS DISTINCT FROM ?
                            OR last_validation_status IS DISTINCT FROM ?
                            OR last_validated_at IS DISTINCT FROM ?)
                        """,
                configuration.credentialFingerprint(), configuration.validationStatus(),
                validatedAt, provider.name(), environment.name(),
                configuration.credentialFingerprint(), configuration.validationStatus(),
                validatedAt);
    }
}

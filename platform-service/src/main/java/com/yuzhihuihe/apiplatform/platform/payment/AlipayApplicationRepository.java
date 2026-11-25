package com.yuzhihuihe.apiplatform.platform.payment;

import com.yuzhihuihe.apiplatform.platform.payment.alipay.AlipayApplicationAdapter;

import org.springframework.jdbc.core.JdbcTemplate;

/** Application bindings contain public identifiers only, never payment credentials. */
final class AlipayApplicationRepository implements AlipayApplicationAdapter.ApplicationBindings {
    private final JdbcTemplate jdbc;
    private final String legacyAppId;

    AlipayApplicationRepository(JdbcTemplate jdbc, String legacyAppId) {
        this.jdbc = jdbc;
        this.legacyAppId = legacyAppId;
    }

    @Override
    public String bind(String transactionNo, String appId) {
        jdbc.update(
                """
                UPDATE platform.payment_transaction
                SET provider_app_id = ?, updated_at = now()
                WHERE transaction_no = ? AND provider = 'ALIPAY'
                  AND provider_app_id IS NULL AND status = 'CREATED'
                """,
                appId,
                transactionNo);
        return find(transactionNo);
    }

    @Override
    public String find(String transactionNo) {
        return jdbc
                .query(
                        """
                        SELECT COALESCE(provider_app_id, payment_action #>> '{fields,app_id}', ?)
                        FROM platform.payment_transaction
                        WHERE transaction_no = ? AND provider = 'ALIPAY'
                        """,
                        (result, row) -> result.getString(1),
                        legacyAppId,
                        transactionNo)
                .stream()
                .findFirst()
                .orElseThrow(
                        () -> new IllegalStateException("alipay_payment_application_not_found"));
    }

    @Override
    public boolean hasPayments(String appId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                        SELECT EXISTS(SELECT 1 FROM platform.payment_transaction
                          WHERE provider = 'ALIPAY' AND provider_app_id = ?)
                        """,
                        Boolean.class,
                        appId));
    }
}

package com.yuzhihuihe.apiplatform.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class NotificationTypeRegistryTest {

    @Test
    void registryContainsTheCompleteImmutableNotificationContract() {
        Map<String, NotificationTypeRegistry.TypeDefinition> types =
                NotificationTypeRegistry.types();

        assertThat(types).containsOnlyKeys(
                "WALLET_USAGE_SETTLED", "WALLET_USAGE_DAILY_SUMMARY", "BALANCE_LOW",
                "ACCOUNT_FROZEN", "ACCOUNT_BANNED", "ACCOUNT_UNFROZEN", "RISK_LOGIN",
                "DESKTOP_LOGIN_SWITCHED",
                "PLATFORM_MAINTENANCE", "SUPPORT_TICKET_COMPLETED",
                "SUPPORT_TICKET_REPLIED",
                "ORDER_PAYMENT_FAILED", "ORDER_TIMED_OUT", "PACKAGE_PAYMENT_SUCCEEDED",
                "BALANCE_RECHARGE_SUCCEEDED", "REFUND_SUCCEEDED",
                "PACKAGE_EXPIRES_IN_3_DAYS", "PACKAGE_EXPIRED", "API_RATE_LIMITED",
                "SUBSCRIPTION_RENEWAL_DUE", "SUBSCRIPTION_RENEWED",
                "SUBSCRIPTION_PERIOD_STARTED", "SUBSCRIPTION_RENEWAL_FAILED",
                "SUBSCRIPTION_CANCELED", "SUBSCRIPTION_PRICE_CHANGED");
        assertType(types, "WALLET_USAGE_SETTLED", "USAGE", null, false,
                "IMMEDIATE", Set.of("logicalModel", "amountFen", "availableBalanceFen",
                        "inputTokens", "outputTokens", "requestId"));
        assertType(types, "WALLET_USAGE_DAILY_SUMMARY", "USAGE", "API_USAGE_DEDUCTED", false,
                "DAILY_DIGEST", Set.of("costMoney", "balance"));
        assertType(types, "BALANCE_LOW", "BILLING", "BALANCE_LOW", false,
                "IMMEDIATE", Set.of("warnMoney"));
        assertType(types, "ACCOUNT_FROZEN", "SECURITY", "ACCOUNT_ANOMALY_FROZEN", true,
                "IMMEDIATE", Set.of("reasonCode", "caseNo"));
        assertType(types, "ACCOUNT_BANNED", "SECURITY", "ACCOUNT_BANNED", true,
                "IMMEDIATE", Set.of("reasonCode", "caseNo"));
        assertType(types, "ACCOUNT_UNFROZEN", "SECURITY", "ACCOUNT_UNFROZEN", true,
                "IMMEDIATE", Set.of("reasonCode", "caseNo"));
        assertType(types, "RISK_LOGIN", "SECURITY", "LOCATION_LOGIN_WARNING", true,
                "IMMEDIATE", Set.of("loginAddr", "riskLevel"));
        assertType(types, "DESKTOP_LOGIN_SWITCHED", "SECURITY", null, false,
                "IMMEDIATE", Set.of("switchTime", "region", "clientSummary", "handoffMode"));
        assertType(types, "PLATFORM_MAINTENANCE", "OPERATIONS", "PLATFORM_MAINTENANCE", false,
                "IMMEDIATE", Set.of("maintainTime", "title", "summary", "broadcastKey"));
        assertThat(types.get("PLATFORM_MAINTENANCE").titleTemplate()).isEqualTo("{title}");
        assertThat(types.get("PLATFORM_MAINTENANCE").summaryTemplate()).isEqualTo("{summary}");
        assertType(types, "SUPPORT_TICKET_COMPLETED", "SUPPORT", "SUPPORT_TICKET_COMPLETED", false,
                "IMMEDIATE", Set.of("ticketNo"));
        assertType(types, "ORDER_PAYMENT_FAILED", "BILLING", "ORDER_PAYMENT_FAILED", false,
                "IMMEDIATE", Set.of("payMoney"));
        assertType(types, "ORDER_TIMED_OUT", "BILLING", "ORDER_TIMED_OUT", false,
                "IMMEDIATE", Set.of("orderNo"));
        assertType(types, "PACKAGE_PAYMENT_SUCCEEDED", "BILLING", "PACKAGE_PAYMENT_SUCCEEDED", false,
                "IMMEDIATE", Set.of("payMoney", "expireDate"));
        assertType(types, "BALANCE_RECHARGE_SUCCEEDED", "BILLING", "BALANCE_RECHARGE_SUCCEEDED", false,
                "IMMEDIATE", Set.of("payMoney", "balance"));
        assertType(types, "REFUND_SUCCEEDED", "BILLING", "REFUND_SUCCEEDED", false,
                "IMMEDIATE", Set.of("refundMoney"));
        assertType(types, "PACKAGE_EXPIRES_IN_3_DAYS", "BILLING", "PACKAGE_EXPIRES_IN_3_DAYS", false,
                "IMMEDIATE", Set.of("expireDate"));
        assertType(types, "PACKAGE_EXPIRED", "BILLING", "PACKAGE_EXPIRED", false,
                "IMMEDIATE", Set.of());
        assertType(types, "API_RATE_LIMITED", "USAGE", "API_RATE_LIMITED", false,
                "IMMEDIATE", Set.of());

        // 自动续费。扣款预告必须强制短信，且不得在扣款时宣称有效期
        // （那时实例还是 QUEUED、expires_at 为 NULL，日期算不出来）。
        assertType(types, "SUBSCRIPTION_RENEWAL_DUE", "SUBSCRIPTION", "SUBSCRIPTION_RENEWAL_DUE", true,
                "IMMEDIATE", Set.of("renewMoney", "renewDate", "planName"));
        assertType(types, "SUBSCRIPTION_RENEWED", "SUBSCRIPTION", "SUBSCRIPTION_RENEWED", false,
                "IMMEDIATE", Set.of("payMoney"));
        assertType(types, "SUBSCRIPTION_PERIOD_STARTED", "SUBSCRIPTION", null, false,
                "IMMEDIATE", Set.of("expireDate"));
        assertType(types, "SUBSCRIPTION_RENEWAL_FAILED", "SUBSCRIPTION", "SUBSCRIPTION_RENEWAL_FAILED",
                false, "IMMEDIATE", Set.of("failReason", "retryDate"));
        assertType(types, "SUBSCRIPTION_CANCELED", "SUBSCRIPTION", null, false,
                "IMMEDIATE", Set.of("expireDate"));
        // 白名单只列能被可靠提供的字段；多列一个填不上的必填键会让通知事务失败，
        // 而失败的通知事务会把外层的订阅记账一起回滚（见 NotificationTypeRegistry 的注释）。
        assertType(types, "SUBSCRIPTION_PRICE_CHANGED", "SUBSCRIPTION", "SUBSCRIPTION_PRICE_CHANGED",
                false, "IMMEDIATE", Set.of("oldMoney"));

        assertThatThrownBy(() -> types.put("ADMIN_SECURITY", types.get("ACCOUNT_FROZEN")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> NotificationTypeRegistry.require("ADMIN_SECURITY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown_notification_type");
    }

    /**
     * 强制短信是「用户无法关闭」的能力，必须被限制在合规必需的两类通知上。
     *
     * <p>本用例是双向的：{@code SUBSCRIPTION} 必须允许（扣款前通知按合规要求不可关闭），
     * 而 {@code BILLING} 等其余分类必须继续被拒绝——否则日后任何账单类事件都能顺手
     * 加上强制短信，造成对用户的过度打扰与短信成本失控。
     */
    @Test
    void mandatorySmsIsLimitedToComplianceCategories() {
        assertThat(new NotificationTypeRegistry.TypeDefinition(
                "TEST_SUBSCRIPTION", NotificationTypeRegistry.Category.SUBSCRIPTION,
                null, true, NotificationTypeRegistry.DeliveryMode.IMMEDIATE, "t", "s", Set.of())
                .mandatorySms()).isTrue();
        assertThat(new NotificationTypeRegistry.TypeDefinition(
                "TEST_SECURITY", NotificationTypeRegistry.Category.SECURITY,
                null, true, NotificationTypeRegistry.DeliveryMode.IMMEDIATE, "t", "s", Set.of())
                .mandatorySms()).isTrue();

        for (NotificationTypeRegistry.Category category : new NotificationTypeRegistry.Category[] {
                NotificationTypeRegistry.Category.BILLING,
                NotificationTypeRegistry.Category.USAGE,
                NotificationTypeRegistry.Category.OPERATIONS,
                NotificationTypeRegistry.Category.SUPPORT}) {
            assertThatThrownBy(() -> new NotificationTypeRegistry.TypeDefinition(
                    "TEST_" + category.name(), category, null, true,
                    NotificationTypeRegistry.DeliveryMode.IMMEDIATE, "t", "s", Set.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("mandatory_sms_requires_compliance_category");
        }
    }

    /**
     * 只有扣款预告是强制的。若日后有人给别的订阅事件也加上强制短信，
     * 这里会拦下来——「续费成功」这类通知用户应当有权关闭。
     */
    @Test
    void onlyThePreChargeNoticeIsMandatoryAmongSubscriptionTypes() {
        Map<String, NotificationTypeRegistry.TypeDefinition> types = NotificationTypeRegistry.types();
        assertThat(types.entrySet().stream()
                .filter(entry -> entry.getValue().category()
                        == NotificationTypeRegistry.Category.SUBSCRIPTION)
                .filter(entry -> entry.getValue().mandatorySms())
                .map(Map.Entry::getKey))
                .containsExactly("SUBSCRIPTION_RENEWAL_DUE");
    }

    @Test
    void builtInUserFacingTemplatesAreChinese() {
        NotificationTypeRegistry.types().values().stream()
                .filter(definition -> !definition.eventType().equals("PLATFORM_MAINTENANCE"))
                .forEach(definition -> {
                    assertThat(definition.titleTemplate()).containsPattern("[\\u4e00-\\u9fff]");
                    assertThat(definition.summaryTemplate()).containsPattern("[\\u4e00-\\u9fff]");
                });
    }

    private static void assertType(
            Map<String, NotificationTypeRegistry.TypeDefinition> types,
            String eventType,
            String category,
            String smsTemplateKey,
            boolean mandatorySms,
            String deliveryMode,
            Set<String> allowedPayloadKeys) {
        NotificationTypeRegistry.TypeDefinition definition = types.get(eventType);
        assertThat(definition.category().name()).isEqualTo(category);
        assertThat(definition.smsTemplateKey()).isEqualTo(smsTemplateKey);
        assertThat(definition.mandatorySms()).isEqualTo(mandatorySms);
        assertThat(definition.security()).isEqualTo("SECURITY".equals(category));
        assertThat(definition.deliveryMode().name()).isEqualTo(deliveryMode);
        assertThat(definition.allowedPayloadKeys()).isEqualTo(allowedPayloadKeys);
        assertThat(definition.titleTemplate()).isNotBlank();
        assertThat(definition.summaryTemplate()).isNotBlank();
    }
}

package com.yuzhihuihe.apiplatform.platform.notification;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class NotificationTypeRegistry {

    private static final Map<String, TypeDefinition> TYPES = buildTypes();

    private NotificationTypeRegistry() {
    }

    public static Map<String, TypeDefinition> types() {
        return TYPES;
    }

    public static Optional<TypeDefinition> find(String eventType) {
        return Optional.ofNullable(TYPES.get(eventType));
    }

    public static TypeDefinition require(String eventType) {
        TypeDefinition definition = TYPES.get(eventType);
        if (definition == null) {
            throw new IllegalArgumentException("unknown_notification_type");
        }
        return definition;
    }

    private static Map<String, TypeDefinition> buildTypes() {
        Map<String, TypeDefinition> types = new LinkedHashMap<>();
        add(types, "WALLET_USAGE_SETTLED", Category.USAGE, null, false,
                DeliveryMode.IMMEDIATE, "API 用量已结算",
                "模型 {logicalModel} 本次消费 {amountFen} 分，可用余额 {availableBalanceFen} 分",
                "logicalModel", "amountFen", "availableBalanceFen",
                "inputTokens", "outputTokens", "requestId");
        add(types, "WALLET_USAGE_DAILY_SUMMARY", Category.USAGE, "API_USAGE_DEDUCTED", false,
                DeliveryMode.DAILY_DIGEST, "每日用量汇总",
                "今日 API 调用费用 {costMoney}，当前余额 {balance}", "costMoney", "balance");
        add(types, "BALANCE_LOW", Category.BILLING, "BALANCE_LOW", false,
                DeliveryMode.IMMEDIATE, "余额不足", "可用余额已低于 {warnMoney}",
                "warnMoney");
        add(types, "ACCOUNT_FROZEN", Category.SECURITY, "ACCOUNT_ANOMALY_FROZEN", true,
                DeliveryMode.IMMEDIATE, "账户已冻结", "您的账户已冻结并进入安全审核",
                "reasonCode", "caseNo");
        add(types, "ACCOUNT_BANNED", Category.SECURITY, "ACCOUNT_BANNED", true,
                DeliveryMode.IMMEDIATE, "账户已封禁", "您的账户已被封禁",
                "reasonCode", "caseNo");
        add(types, "ACCOUNT_UNFROZEN", Category.SECURITY, "ACCOUNT_UNFROZEN", true,
                DeliveryMode.IMMEDIATE, "账户已恢复", "您的账户已解除冻结",
                "reasonCode", "caseNo");
        add(types, "RISK_LOGIN", Category.SECURITY, "LOCATION_LOGIN_WARNING", true,
                DeliveryMode.IMMEDIATE, "异常登录提醒", "检测到来自 {loginAddr} 的登录",
                "loginAddr", "riskLevel");
        add(types, "DESKTOP_LOGIN_SWITCHED", Category.SECURITY, null, false,
                DeliveryMode.IMMEDIATE, "电脑端登录已切换",
                "{switchTime} 已从 {region} 的 {clientSummary} 完成登录切换",
                "switchTime", "region", "clientSummary", "handoffMode");
        add(types, "PLATFORM_MAINTENANCE", Category.OPERATIONS, "PLATFORM_MAINTENANCE", false,
                DeliveryMode.IMMEDIATE, "{title}", "{summary}",
                "maintainTime", "title", "summary", "broadcastKey");
        add(types, "SUPPORT_TICKET_COMPLETED", Category.SUPPORT, "SUPPORT_TICKET_COMPLETED", false,
                DeliveryMode.IMMEDIATE, "支持工单已完成", "工单 {ticketNo} 已处理完成",
                "ticketNo");
        add(types, "SUPPORT_TICKET_REPLIED", Category.SUPPORT, null, false,
                DeliveryMode.IMMEDIATE, "支持工单有新回复", "工单 {ticketNo}：{reply}",
                "ticketNo", "reply");
        add(types, "ORDER_PAYMENT_FAILED", Category.BILLING, "ORDER_PAYMENT_FAILED", false,
                DeliveryMode.IMMEDIATE, "支付失败", "金额 {payMoney} 的支付未完成", "payMoney");
        add(types, "ORDER_TIMED_OUT", Category.BILLING, "ORDER_TIMED_OUT", false,
                DeliveryMode.IMMEDIATE, "订单已超时", "订单 {orderNo} 未在有效期内完成支付", "orderNo");
        add(types, "PACKAGE_PAYMENT_SUCCEEDED", Category.BILLING, "PACKAGE_PAYMENT_SUCCEEDED", false,
                DeliveryMode.IMMEDIATE, "套餐已生效",
                "支付 {payMoney} 成功，套餐有效期至 {expireDate}", "payMoney", "expireDate");
        add(types, "BALANCE_RECHARGE_SUCCEEDED", Category.BILLING, "BALANCE_RECHARGE_SUCCEEDED", false,
                DeliveryMode.IMMEDIATE, "余额充值成功",
                "充值 {payMoney} 成功，当前余额 {balance}", "payMoney", "balance");
        add(types, "REFUND_SUCCEEDED", Category.BILLING, "REFUND_SUCCEEDED", false,
                DeliveryMode.IMMEDIATE, "退款已完成", "退款 {refundMoney} 已到账", "refundMoney");
        add(types, "PACKAGE_EXPIRES_IN_3_DAYS", Category.BILLING, "PACKAGE_EXPIRES_IN_3_DAYS", false,
                DeliveryMode.IMMEDIATE, "套餐即将到期", "您的套餐将于 {expireDate} 到期", "expireDate");
        add(types, "PACKAGE_EXPIRED", Category.BILLING, "PACKAGE_EXPIRED", false,
                DeliveryMode.IMMEDIATE, "套餐已到期", "您的套餐已到期");
        add(types, "API_RATE_LIMITED", Category.USAGE, "API_RATE_LIMITED", false,
                DeliveryMode.IMMEDIATE, "API 调用受限", "检测到持续的 API 限流");
        // 自动续费。分类刻意独立于 BILLING：扣款前通知按合规要求不可被用户关闭，
        // 而强制短信只允许出现在合规分类上（见 TypeDefinition 的构造器断言）。
        add(types, "SUBSCRIPTION_RENEWAL_DUE", Category.SUBSCRIPTION,
                "SUBSCRIPTION_RENEWAL_DUE", true,
                DeliveryMode.IMMEDIATE, "订阅即将自动续费",
                "您的{planName}将于 {renewDate} 自动续费 {renewMoney}，可在订阅管理中关闭",
                "renewMoney", "renewDate", "planName");
        add(types, "SUBSCRIPTION_RENEWED", Category.SUBSCRIPTION,
                "SUBSCRIPTION_RENEWED", false,
                DeliveryMode.IMMEDIATE, "订阅已续费",
                "已扣款 {payMoney}，将在当前周期结束后自动生效", "payMoney");
        add(types, "SUBSCRIPTION_PERIOD_STARTED", Category.SUBSCRIPTION, null, false,
                DeliveryMode.IMMEDIATE, "新的订阅周期已开始",
                "您的套餐有效期至 {expireDate}", "expireDate");
        add(types, "SUBSCRIPTION_RENEWAL_FAILED", Category.SUBSCRIPTION,
                "SUBSCRIPTION_RENEWAL_FAILED", false,
                DeliveryMode.IMMEDIATE, "订阅续费失败",
                "续费未能完成（{failReason}），将在 {retryDate} 重试，请检查余额",
                "failReason", "retryDate");
        add(types, "SUBSCRIPTION_CANCELED", Category.SUBSCRIPTION, null, false,
                DeliveryMode.IMMEDIATE, "订阅已关闭",
                "已关闭自动续费，当前周期有效期至 {expireDate}", "expireDate");
        // 涨价待确认。
        //
        // 白名单只列**能被可靠提供**的字段：这个事件在调度任务里发出时，手里只有
        // 上一次实际扣款的金额（订阅行），新价格由决策对象携带但通知服务拿不到。
        // 最初把 newMoney/effectiveDate 也写进白名单、却在载荷里漏传，结果是
        // TEMPLATE_VARIABLES_MISSING 把通知事务标记成 rollback-only，进而静默回滚
        // 外层的订阅记账——一个"少传一个键"就让整条续费状态丢失的连锁反应。
        // 需要完整金额对比时应当把新价透传给通知服务，而不是在白名单里留一个
        // 没人能填的必填字段。
        add(types, "SUBSCRIPTION_PRICE_CHANGED", Category.SUBSCRIPTION,
                "SUBSCRIPTION_PRICE_CHANGED", false,
                DeliveryMode.IMMEDIATE, "订阅价格将调整",
                "续费价格已由 {oldMoney} 调整，请到订阅管理确认后继续",
                "oldMoney");
        return Collections.unmodifiableMap(types);
    }

    private static void add(
            Map<String, TypeDefinition> types,
            String eventType,
            Category category,
            String smsTemplateKey,
            boolean mandatorySms,
            DeliveryMode deliveryMode,
            String titleTemplate,
            String summaryTemplate,
            String... allowedPayloadKeys) {
        TypeDefinition definition = new TypeDefinition(
                eventType, category, smsTemplateKey, mandatorySms, deliveryMode,
                titleTemplate, summaryTemplate, Set.of(allowedPayloadKeys));
        if (types.put(eventType, definition) != null) {
            throw new IllegalStateException("duplicate_notification_type");
        }
    }

    public enum Category {
        SECURITY,
        BILLING,
        USAGE,
        OPERATIONS,
        SUPPORT,
        /**
         * 自动续费与订阅生命周期。
         *
         * <p>单独成类而不是并入 {@code BILLING}：扣款前通知按合规要求
         * 不可被用户关闭，而 {@code mandatorySms} 只允许用在合规分类上。
         * 若并入 BILLING，就得放宽那条断言，从而让「任何账单通知都能强制短信」
         * 成为全局能力，日后必然造成过度打扰与短信成本失控。
         *
         * <p>新增本值时必须同步三处，否则会出现「偏好行缺失 → 静默不发短信」：
         * 本枚举、{@code NotificationPreferenceRepository.ensureDefaults} 的分类清单、
         * 以及数据库的 {@code ck_notification_event_category} /
         * {@code ck_notification_preference_category}（见 V71 迁移）。
         */
        SUBSCRIPTION
    }

    public enum DeliveryMode {
        IMMEDIATE,
        DAILY_DIGEST
    }

    public record TypeDefinition(
            String eventType,
            Category category,
            String smsTemplateKey,
            boolean mandatorySms,
            DeliveryMode deliveryMode,
            String titleTemplate,
            String summaryTemplate,
            Set<String> allowedPayloadKeys) {

        public TypeDefinition {
            allowedPayloadKeys = Set.copyOf(allowedPayloadKeys);
            // 强制短信意味着「用户关不掉」。它必须只出现在合规要求不允许用户关闭的
            // 分类上，否则任何账单类事件都能顺手加上它并绕过用户的偏好设置。
            if (mandatorySms && category != Category.SECURITY && category != Category.SUBSCRIPTION) {
                throw new IllegalArgumentException("mandatory_sms_requires_compliance_category");
            }
        }

        public boolean security() {
            return category == Category.SECURITY;
        }
    }
}

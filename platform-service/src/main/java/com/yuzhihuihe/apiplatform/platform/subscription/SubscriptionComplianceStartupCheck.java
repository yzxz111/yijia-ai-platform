package com.yuzhihuihe.apiplatform.platform.subscription;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 启动期校验：**不允许在生产环境关掉「涨价必须先获得用户确认」**（设计 §7.3）。
 *
 * <p><b>为什么必须是独立的启动期组件，而不是写在 {@code SubscriptionRenewalJob} 的构造函数里。</b>
 * 我最初就写在构造函数里，实测发现它**根本不生效**：{@code SubscriptionRenewalJob} 是
 * {@code @Scheduled} 组件且没有启动期依赖，Spring 不会在 refresh 阶段实例化它——构造函数要等到
 * **第一次调度触发**才执行。那时服务早已对外提供服务，配置错误会变成「服务正常启动、两周后
 * 静默按新价扣款」。把校验放进一个 {@code @PostConstruct} 组件后，配置错误在启动时就拒绝启动。
 *
 * <p>这条不是「多一层防御」，而是把一句注释变成一条不变量：{@code deploy/env/.env.example} 里写着
 * 「必须是 true」，但注释拦不住任何人；系统此前不会给出任何提示，也没有人会收到告警。
 */
@Component
class SubscriptionComplianceStartupCheck {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(SubscriptionComplianceStartupCheck.class);

    @Value("${subscription.jobs.price-increase-requires-confirmation:true}")
    private boolean priceIncreaseRequiresConfirmation;

    /**
     * 允许关掉该开关的 profile 列表，**只应为本地/测试环境**。
     *
     * <p>这个开关存在的唯一理由是本地开发时不方便造涨价场景。
     */
    @Value("${subscription.jobs.price-increase-override-allowed-profiles:local,test,integration}")
    private List<String> priceIncreaseOverrideAllowedProfiles;

    private final Environment environment;

    SubscriptionComplianceStartupCheck(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void verify() {
        requirePriceConfirmationUnlessOverridable(
                priceIncreaseRequiresConfirmation, priceIncreaseOverrideAllowedProfiles, environment);
        if (!priceIncreaseRequiresConfirmation) {
            // 允许归允许，但仍然留一条响亮日志：本地/测试环境里也不该「悄悄」处于这个状态。
            LOGGER.atWarn()
                    .addKeyValue("profiles", String.join(",", environment.getActiveProfiles()))
                    .log("price confirmation is DISABLED for automatic renewal "
                            + "(subscription.jobs.price-increase-requires-confirmation=false); "
                            + "this is a compliance requirement and must never be disabled in production");
        }
    }

    /**
     * 校验逻辑本身。
     *
     * <p>抽成静态方法是为了能直接对这条不变量写测试，而不必启动 Spring 上下文。
     * 它值得单独可测：这是防止「改一行配置就静默按新价扣款」的最后一道闸门。
     *
     * @throws IllegalStateException 开关为 false 且当前 profile 不在允许覆盖的列表里
     */
    static void requirePriceConfirmationUnlessOverridable(
            boolean priceIncreaseRequiresConfirmation,
            List<String> allowedProfiles,
            Environment environment) {
        if (priceIncreaseRequiresConfirmation) {
            return;
        }
        boolean overrideAllowed = allowedProfiles != null
                && allowedProfiles.stream()
                        .filter(profile -> profile != null && !profile.isBlank())
                        .anyMatch(profile -> environment.acceptsProfiles(Profiles.of(profile.trim())));
        if (!overrideAllowed) {
            throw new IllegalStateException(
                    "subscription.jobs.price-increase-requires-confirmation=false is only allowed in "
                            + allowedProfiles
                            + "; automatic renewal must not raise the price without the user's "
                            + "confirmation (design 7.3)");
        }
    }
}

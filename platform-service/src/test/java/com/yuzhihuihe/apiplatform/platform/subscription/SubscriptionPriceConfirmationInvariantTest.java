package com.yuzhihuihe.apiplatform.platform.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/**
 * 「涨价必须先获得用户确认」是一条**合规要求**（设计 §7.3），不是可调项。
 *
 * <p>它此前只是一个普通的 {@code @Value}，默认 true，但运维在 `.env` 里设成 false 就能静默关闭
 * 「先通知、用户确认后才按新价扣款」。`deploy/env/.env.example` 里写着「必须是 true」——可是
 * <b>注释拦不住任何人</b>：系统不会有任何提示，也没有人会收到告警，直到用户被按新价扣了款。
 *
 * <p>因此这条不变量被提升为代码级检查：非本地/测试 profile 下把它关掉，服务**直接拒绝启动**。
 * 启动失败是响亮的、立刻会被发现的；静默多扣款不是。本用例锁住这一点。
 *
 * <p><b>为什么还要一个 Spring 上下文用例。</b>我最初把校验写在 {@code SubscriptionRenewalJob} 的
 * 构造函数里，纯单元测试照样能过，但它<b>在真实启动时根本不执行</b>——那个 bean 是懒实例化的
 * {@code @Scheduled} 组件，构造器要等到第一次调度才跑。也就是说：单元测试绿着，生产却毫无保护。
 * 因此必须有一个用例证明这个检查**确实在上下文启动阶段执行**，而不只是「逻辑正确」。
 */
class SubscriptionPriceConfirmationInvariantTest {

    private static final List<String> LOCAL_PROFILES = List.of("local", "test", "integration");

    private static MockEnvironment environmentWith(String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        return environment;
    }

    /** 默认配置（开关为 true）在任何 profile 下都必须通过。 */
    @Test
    void anEnabledConfirmationSwitchAlwaysPasses() {
        assertThatCode(() -> SubscriptionComplianceStartupCheck.requirePriceConfirmationUnlessOverridable(
                true, LOCAL_PROFILES, environmentWith("prod")))
                .doesNotThrowAnyException();
        assertThatCode(() -> SubscriptionComplianceStartupCheck.requirePriceConfirmationUnlessOverridable(
                true, LOCAL_PROFILES, environmentWith()))
                .doesNotThrowAnyException();
    }

    /**
     * 关掉它、且当前是生产 profile —— **必须拒绝启动**。
     *
     * <p>这是本用例存在的理由：这一条如果失效，自动续费就能在用户未确认的情况下按新价扣款。
     */
    @Test
    void disablingItOutsideLocalProfilesRefusesToStart() {
        assertThatThrownBy(() -> SubscriptionComplianceStartupCheck.requirePriceConfirmationUnlessOverridable(
                false, LOCAL_PROFILES, environmentWith("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("price-increase-requires-confirmation");
        // 空 profile 同样按生产对待：忘记设置 profile 不该成为绕过合规的捷径。
        assertThatThrownBy(() -> SubscriptionComplianceStartupCheck.requirePriceConfirmationUnlessOverridable(
                false, LOCAL_PROFILES, environmentWith()))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 本地/测试环境允许关掉，否则没法方便地造涨价场景。 */
    @Test
    void disablingItInLocalProfilesIsAllowed() {
        for (String profile : LOCAL_PROFILES) {
            assertThatCode(() -> SubscriptionComplianceStartupCheck.requirePriceConfirmationUnlessOverridable(
                    false, LOCAL_PROFILES, environmentWith(profile)))
                    .doesNotThrowAnyException();
        }
    }

    /** 覆盖白名单本身可以被收紧：配置里只允许 local 时，test 也不再被放行。 */
    @Test
    void theOverrideListItselfIsConfigurable() {
        assertThatThrownBy(() -> SubscriptionComplianceStartupCheck.requirePriceConfirmationUnlessOverridable(
                false, List.of("local"), environmentWith("test")))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> SubscriptionComplianceStartupCheck.requirePriceConfirmationUnlessOverridable(
                false, List.of("local"), environmentWith("local")))
                .doesNotThrowAnyException();
    }

    /**
     * 报价待确认的时限不能短到「通知刚发出就终止订阅」。
     *
     * <p>这个值此前只校验「为正数」，因此 {@code 1s} 也能通过：用户还没看到涨价通知，订阅就已经被
     * 终止了——而且是静默终止。下界取 24h 的依据是流程时序：扣款发生在周期结束前
     * {@code CHARGE_LEAD_TIME}（默认 24h），用户**在扣款之后**仍需要一段可回应的时间。
     */
    @Test
    void thePriceConfirmationWindowCannotBeShorterThanADay() {
        java.time.Duration floor = SubscriptionRenewalJob.MIN_PRICE_CONFIRMATION_TIMEOUT;
        assertThat(floor).isEqualTo(java.time.Duration.ofHours(24));
        // 下界必须低于默认值（30 天），否则默认配置自己就会启动失败。
        assertThat(floor).isLessThan(java.time.Duration.ofDays(30));

        // 短于下界：必须被拒绝，而不是「接受了但行为诡异」。
        assertThatThrownBy(() -> SubscriptionRenewalJob.requireAtLeast(
                java.time.Duration.ofSeconds(1), floor, "price confirmation timeout"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least PT24H");
        assertThatThrownBy(() -> SubscriptionRenewalJob.requireAtLeast(
                java.time.Duration.ofHours(23), floor, "price confirmation timeout"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionRenewalJob.requireAtLeast(
                null, floor, "price confirmation timeout"))
                .isInstanceOf(IllegalArgumentException.class);

        // 恰好等于下界、以及更长，都应当通过。
        assertThatCode(() -> SubscriptionRenewalJob.requireAtLeast(
                floor, floor, "price confirmation timeout"))
                .doesNotThrowAnyException();
        assertThatCode(() -> SubscriptionRenewalJob.requireAtLeast(
                java.time.Duration.ofDays(30), floor, "price confirmation timeout"))
                .doesNotThrowAnyException();
    }

    /** 配置项写成空白不该被当成「允许」：那会是一个静默的口子。 */
    @Test
    void aBlankProfileEntryDoesNotGrantAnOverride() {
        assertThatThrownBy(() -> SubscriptionComplianceStartupCheck.requirePriceConfirmationUnlessOverridable(
                false, List.of("", "   "), environmentWith("prod")))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------
    // 证明这个检查**确实在容器启动阶段执行** —— 上面的纯单元用例做不到这一点
    // ------------------------------------------------------------------

    /**
     * 一个真实场景：把开关关掉、profile 不在允许列表里，然后**真的去启动容器**。
     *
     * <p>这是本类里最重要的用例。纯单元用例只能证明「校验逻辑正确」；我最初把校验写在
     * {@code SubscriptionRenewalJob} 的构造函数里时，纯单元用例同样全绿，但真实启动时**根本不会
     * 执行**（那个 bean 要到第一次调度才实例化）。所以必须有这样一个用例把「检查是否真的在启动
     * 阶段跑」也钉住。
     */
    @Test
    void theCheckActuallyRunsDuringContainerStartup() {
        // 只装载被测组件，避免拉进数据源/安全/JPA。
        // 属性必须放进 Environment：@Value 读的是 PropertySource，而不是容器里的 bean
        // （最初写成 registerBean 一个 Boolean，结果 @Value 解析不到、检查根本没跑，用例假绿）。
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("subscription.jobs.price-increase-requires-confirmation", "false");
        environment.setProperty("subscription.jobs.price-increase-override-allowed-profiles", "local,test");

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.register(SubscriptionComplianceStartupCheck.class);
            // 没有 placeholder 解析器时 @Value 不会被替换，检查会拿着默认值 true 通过。
            context.register(
                    org.springframework.context.support.PropertySourcesPlaceholderConfigurer.class);

            assertThatThrownBy(context::refresh)
                    .as("starting the container with the compliance switch off in production must fail")
                    .hasStackTraceContaining("price-increase-requires-confirmation");
        }
    }
}

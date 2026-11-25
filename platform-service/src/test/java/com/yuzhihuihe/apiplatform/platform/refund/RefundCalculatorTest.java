package com.yuzhihuihe.apiplatform.platform.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;

/**
 * 退款仅在套餐被激活之前可用。
 *
 * <p>变化所在：原先的规则是「未消耗任何令牌」，而对一个已激活、
 * 但其流量结算在非令牌维度上的套餐而言，网关恰好满足该规则 —— 于是客户
 * 可以在整个窗口期内持有一个套餐方案，用它生成视频或图片，却仍然
 * 拿到全额退款。现在任何形式的消耗都是激活的证明，两份权威激活记录
 * 中的任意一份也是。这使部分退款分支从可达集合中被移除：下面那些
 * 过去断言按比例退款的用例，现在都断言拒绝，
 * 因为消耗过任何内容的套餐都已被激活。
 */
class RefundCalculatorTest {

    private static final Instant PAID_AT = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant CALCULATED_AT = Instant.parse("2026-08-02T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-01T00:00:00Z");

    private RefundCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new RefundCalculator(Clock.fixed(CALCULATED_AT, ZoneOffset.UTC));
    }

    @Test
    void neverActivatedPackageProducesAFullRefundWithoutFee() {
        var result = calculator.calculate(input(10_001, 1_000_000, 0, PAID_AT, false));

        assertThat(result.refundType()).isEqualTo(RefundCalculator.RefundType.FULL);
        assertThat(result.remainingTokens()).isEqualTo(1_000_000);
        assertThat(result.baseRefund()).isEqualTo(10_001);
        assertThat(result.fee()).isZero();
        assertThat(result.refundAmount()).isEqualTo(10_001);
        assertThat(result.rounding()).isEqualTo("BASE_FLOOR_FEE_HALF_UP");
        assertThat(result.policyVersion()).isEqualTo(1);
        assertThat(result.calculatedAt()).isEqualTo(CALCULATED_AT);
    }

    /**
     * 该规则存在就是为了阻止这种滥用：已激活、令牌计数器上没有任何结算，
     * 却在旧判定条件下拿到全额退款。仅凭激活记录就必须拒绝它，
     * 哪怕其他所有数字看起来都没被动过。
     */
    @Test
    void activatedButUnusedPackageIsRefused() {
        assertReason(input(10_001, 1_000_000, 0, PAID_AT, true), "PACKAGE_ACTIVATED");
    }

    /**
     * 用量本身就是激活的证明 —— 网关只会对已激活的
     * 实例结算用量 —— 因此任何程度的用量都会被拒绝，包括
     * 旧策略视为可部分退款的那些程度（最高为额度的 10%）。
     */
    @ParameterizedTest
    @CsvSource({"1", "100000", "100001", "1000000"})
    void partiallyUsedPackageIsRefused(long usedTokens) {
        assertReason(
                input(10_001, 1_000_000, usedTokens, PAID_AT, false),
                "PACKAGE_ACTIVATED");
    }

    /** 已使用的套餐仅凭用量就会被拒绝，无需任何激活记录。 */
    @Test
    void usageAloneRefusesEvenWhenNoActivationRecordWasSupplied() {
        assertReason(input(10_001, 1_000_000, 1, PAID_AT, false), "PACKAGE_ACTIVATED");
    }

    @Test
    void aNeverActivatedPackageWithoutExpiryCanBeRefunded() {
        var input = new RefundCalculator.RefundInput(
                10_001, 1_000_000, 0, 500, 100, 1000, 7, 1,
                PAID_AT, CALCULATED_AT, null, false);

        assertThat(calculator.calculate(input).refundType())
                .isEqualTo(RefundCalculator.RefundType.FULL);
    }

    /**
     * 「从未激活」是两份独立记录的与运算，其中任意一份
     * 不利 —— 或未知 —— 就足以拒绝。白名单正是关键：缺失的
     * 或无法识别的状态都会读作已激活，因此模式变更或
     * 只写了一半的行都无法换来退款。
     */
    @Test
    void neverActivatedRequiresBothRecordsToSaySo() {
        Instant confirmedAt = Instant.parse("2026-08-01T01:00:00Z");

        // 从未激活：该实例仍在排队，且激活握手从未
        // 报告过 ACTIVE。FAILED 也属于这一类 —— 激活被拒绝意味着
        // 客户从未收到该套餐。
        assertThat(RefundCalculator.activated("QUEUED", "QUEUED", null)).isFalse();
        assertThat(RefundCalculator.activated("QUEUED", "FAILED", null)).isFalse();

        // 网关称该实例是存活的。
        assertThat(RefundCalculator.activated("ACTIVE", "QUEUED", null)).isTrue();
        // 平台记录了这次激活。
        assertThat(RefundCalculator.activated("QUEUED", "ACTIVE", null)).isTrue();
        // 已撤销意味着它先前被激活过（发件箱仅在 ACTIVE 时才完成权益发放）。
        assertThat(RefundCalculator.activated("QUEUED", "REVOKED", null)).isTrue();
        assertThat(RefundCalculator.activated("QUEUED", "REVOKED", confirmedAt)).isTrue();
        // 未知或缺失的状态在两侧都失败即关闭（fail-closed）。
        assertThat(RefundCalculator.activated("QUEUED", null, null)).isTrue();
        assertThat(RefundCalculator.activated(null, "QUEUED", null)).isTrue();
        assertThat(RefundCalculator.activated("EXPIRED", "QUEUED", null)).isTrue();
        assertThat(RefundCalculator.activated("QUEUED", "SOMETHING_NEW", null)).isTrue();
    }

    @Test
    void refundWindowIsInclusiveToTheNanosecond() {
        calculator.calculate(input(10_001, 1_000_000, 0, PAID_AT.plus(Duration.ofDays(7)), false));

        assertReason(
                input(10_001, 1_000_000, 0,
                        PAID_AT.plus(Duration.ofDays(7)).plusNanos(1), false),
                "REFUND_WINDOW_EXPIRED");
    }

    @Test
    void expiredPackageIsIneligible() {
        var input = new RefundCalculator.RefundInput(
                10_001, 1_000_000, 0, 500, 100, 1000, 7, 1,
                PAID_AT, CALCULATED_AT, CALCULATED_AT, false);

        assertReason(input, "PACKAGE_EXPIRED");
    }

    /**
     * 退款窗口与套餐到期在激活之前检查，因此一个已过期的
     * 申请会以运维可以据以行动的原因被拒绝，而不是被
     * 报告为激活问题。
     */
    @Test
    void anExpiredWindowIsReportedBeforeActivation() {
        assertReason(
                input(10_001, 1_000_000, 0, PAID_AT.plus(Duration.ofDays(8)), true),
                "REFUND_WINDOW_EXPIRED");
    }

    @Test
    void nearLongMaximumCalculationsDoNotOverflow() {
        var input = new RefundCalculator.RefundInput(
                Long.MAX_VALUE, Long.MAX_VALUE, 0, 1, 0, 1000, 7, 99,
                PAID_AT, CALCULATED_AT, EXPIRES_AT, false);

        var result = calculator.calculate(input);

        assertThat(result.refundType()).isEqualTo(RefundCalculator.RefundType.FULL);
        assertThat(result.remainingTokens()).isEqualTo(Long.MAX_VALUE);
        assertThat(result.baseRefund()).isEqualTo(Long.MAX_VALUE);
        assertThat(result.fee()).isZero();
        assertThat(result.refundAmount()).isEqualTo(Long.MAX_VALUE);
        assertThat(result.policyVersion()).isEqualTo(99);
    }

    @Test
    void invalidInputIsRejectedWithABadRequestRatherThanARefusal() {
        var zeroPaid = new RefundCalculator.RefundInput(
                0, 1_000, 0, 500, 100, 1000, 7, 1,
                PAID_AT, CALCULATED_AT, EXPIRES_AT, false);

        assertThatThrownBy(() -> calculator.calculate(zeroPaid))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("INVALID_REFUND_INPUT");
    }

    private RefundCalculator.RefundInput input(
            long paidAmount, long totalTokens, long usedTokens, Instant requestedAt,
            boolean activated) {
        return new RefundCalculator.RefundInput(
                paidAmount, totalTokens, usedTokens, 500, 100, 1000, 7, 1,
                PAID_AT, requestedAt, EXPIRES_AT, activated);
    }

    private void assertReason(RefundCalculator.RefundInput input, String reason) {
        assertThatThrownBy(() -> calculator.calculate(input))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(reason);
    }
}

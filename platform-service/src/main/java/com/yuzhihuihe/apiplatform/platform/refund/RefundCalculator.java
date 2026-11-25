package com.yuzhihuihe.apiplatform.platform.refund;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 判定已付款的套餐订单是否可以退款。
 *
 * <p>仅当套餐从未被激活时才可退款。过去的规则是「未消耗任何 token」，
 * 这两者并不相同：网关对已激活、但其流量在非 token 维度
 * （视频秒数、图片）上结算的套餐会报告 {@code used_tokens = 0}，
 * 因此客户可以持有一个套餐方案度过整个退款窗口，针对它生成视频或图片，
 * 然后仍然为该已经让平台付出成本的套餐申请全额退款。
 * 真正重要的是激活这一事实，
 * 而不是 token 计数器。
 *
 * <p>本类过去承载的部分退款路径（按比例退款减去
 * 手续费，并以额度的 {@code maximum_usage_bps} 为上限）因此对新申请
 * 不再可达：只要有任何消耗就是激活的证明。
 * 策略的手续费与用量上限条款仍会被读取、校验并记录到
 * 不可变的计算快照中，因为已发布的策略行与历史退款
 * 行会引用它们，且 {@link RefundType#PARTIAL} 仍是每一笔已记录的部分
 * 退款的类型。按此规则批准的退款始终是全额，
 * 因此舍入标签保留，手续费为零。
 */
public final class RefundCalculator {

    private static final String ROUNDING = "BASE_FLOOR_FEE_HALF_UP";

    /**
     * 平台激活握手从未报告 ACTIVE 的权益发放状态。
     * {@code platform.grant_request} 由发件箱发布器依据网关自己的
     * 激活响应写入：激活待定时它保持 QUEUED，
     * 仅当网关确认后才变为 ACTIVE（并设置 {@code completed_at}）。
     * FAILED 表示激活被明确拒绝，这是唯一能
     * 证明客户从未收到该套餐的状态。
     */
    private static final Set<String> NEVER_ACTIVATED_GRANT_STATUSES = Set.of("QUEUED", "FAILED");

    /** 表示套餐实例从未离开激活队列的套餐实例状态。 */
    private static final String NEVER_ACTIVATED_INSTANCE_STATUS = "QUEUED";

    private final Clock clock;

    public RefundCalculator() {
        this(Clock.systemUTC());
    }

    public RefundCalculator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 是否有任何权威记录表明该套餐已被激活。
     *
     * <p>ACTIVATED 意味着套餐已交付给客户，并且本可以承载
     * 流量。只要下列任一项成立即为真，仅当两个来源都
     * 明确表示该套餐从未离开队列时才为假：
     *
     * <ul>
     *   <li>{@code grantCompletedAt} 已设置 —— 平台记录了网关的
     *       激活确认（{@code OutboxPublisher} 仅在网关报告 ACTIVE 时才
     *       完成权益发放）；
     *   <li>{@code grantStatus} 是 QUEUED 或 FAILED 以外的任何值 —— 尤其是
     *       ACTIVE 与 REVOKED 都意味着该套餐曾在某个时刻是生效的；
     *   <li>{@code packageInstanceStatus} 是 QUEUED 以外的任何值 —— 这是网关
     *       自身对该实例的状态，取自退款快照的
     *       {@code originalStatus}。处于 ACTIVE 的实例已被激活。
     * </ul>
     *
     * <p>未知或缺省的值会被读作已激活。「无法证明它从未
     * 被激活」绝不能换来退款，因此上面两个允许列表是唯一的
     * 通过途径。
     *
     * <p>RESERVATIONS 与 GENERATIONS 无需单独的条件。它们仅由
     * 承载流量而创建，而流量只能由已激活的实例承载，因此
     * 要求实例为 QUEUED 在构造上就排除了两者。退款
     * 快照不暴露它们 —— 它携带九个字段，其中既没有
     * {@code reserved_tokens}，也没有生成计数 —— 而网关
     * 自身的撤销路径会独立拒绝撤销仍持有
     * 预留的套餐，因此在本检查之后发起的预留无法悄悄
     * 完成退款。
     */
    public static boolean activated(
            String packageInstanceStatus, String grantStatus, Instant grantCompletedAt) {
        if (grantCompletedAt != null) {
            return true;
        }
        if (grantStatus == null || !NEVER_ACTIVATED_GRANT_STATUSES.contains(grantStatus)) {
            return true;
        }
        return !NEVER_ACTIVATED_INSTANCE_STATUS.equals(packageInstanceStatus);
    }

    public RefundCalculation calculate(RefundInput input) {
        validate(input);
        if (input.requestedAt().isAfter(input.paidAt().plus(Duration.ofDays(input.refundWindowDays())))) {
            throw ineligible("REFUND_WINDOW_EXPIRED");
        }
        if (input.packageExpiresAt() != null
                && !input.packageExpiresAt().isAfter(input.requestedAt())) {
            throw ineligible("PACKAGE_EXPIRED");
        }
        // 任何消耗本身就是激活的证明：网关只会对
        // 它已激活的实例结算用量。任一信号都会拒绝退款，
        // 两者都需要，因为非 token 维度结算时不会推动
        // token 计数器。
        if (input.activated() || input.usedTokens() > 0) {
            throw ineligible("PACKAGE_ACTIVATED");
        }

        return new RefundCalculation(
                RefundType.FULL,
                input.totalTokens(),
                input.paidAmount(),
                0,
                input.paidAmount(),
                ROUNDING,
                input.policyVersion(),
                clock.instant());
    }

    private void validate(RefundInput input) {
        if (input == null
                || input.paidAmount() <= 0
                || input.totalTokens() <= 0
                || input.usedTokens() < 0
                || input.usedTokens() > input.totalTokens()
                || input.feeBps() < 0
                || input.feeBps() > 10_000
                || input.minimumFee() < 0
                || input.maximumUsageBps() < 0
                || input.maximumUsageBps() > 10_000
                || input.refundWindowDays() <= 0
                || input.policyVersion() <= 0
                || input.paidAt() == null
                || input.requestedAt() == null
                || input.requestedAt().isBefore(input.paidAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_REFUND_INPUT");
        }
    }

    private ResponseStatusException ineligible(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    public enum RefundType {
        FULL,
        PARTIAL
    }

    /**
     * @param activated {@link #activated} 是否找到了任何激活记录；
     *                  由调用方从退款快照和平台自身的
     *                  权益发放行计算得出，从而使判定本身保持在一处
     * @param feeBps 策略的手续费率，为校验和不可变
     *               快照而携带。它不再改变退款金额，因为退款
     *               仅在激活之前可用，因此始终是全额。
     * @param minimumFee 策略的最低手续费，出于同样的原因携带
     * @param maximumUsageBps 策略的用量上限，出于同样的原因携带
     */
    public record RefundInput(
            long paidAmount,
            long totalTokens,
            long usedTokens,
            int feeBps,
            long minimumFee,
            int maximumUsageBps,
            int refundWindowDays,
            int policyVersion,
            Instant paidAt,
            Instant requestedAt,
            Instant packageExpiresAt,
            boolean activated) {
    }

    public record RefundCalculation(
            RefundType refundType,
            long remainingTokens,
            long baseRefund,
            long fee,
            long refundAmount,
            String rounding,
            int policyVersion,
            Instant calculatedAt) {
    }
}

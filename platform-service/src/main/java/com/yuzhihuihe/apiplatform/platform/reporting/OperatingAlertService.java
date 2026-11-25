package com.yuzhihuihe.apiplatform.platform.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperatingAlertService {
    static final BigDecimal PRICE_CHANGE_THRESHOLD = new BigDecimal("0.05");
    static final BigDecimal LOW_MARGIN_THRESHOLD = new BigDecimal("0.30");
    static final BigDecimal COST_MULTIPLIER = new BigDecimal("3");
    /** 监控所观察的条件已消除，因此该告警不再成立。 */
    public static final String RESOLUTION_RECOVERED = "RECOVERED";
    /** 该告警所描述的版本已不再销售，因此该告警已无意义。 */
    public static final String RESOLUTION_NO_LONGER_PUBLISHED = "NO_LONGER_PUBLISHED";
    /**
     * 定时对账可以用来关闭告警的原因。这是一个封闭集合，因为
     * 控制台会把存储的值直接映射进自己的词汇表，而自由文本的
     * 原因会是一个任何读者都无法渲染的值。
     */
    static final List<String> RESOLUTION_REASONS =
            List.of(RESOLUTION_RECOVERED, RESOLUTION_NO_LONGER_PUBLISHED);
    private final Store store;
    private final Supplier<Instant> now;

    @Autowired
    OperatingAlertService(OperatingAlertRepository repository) {
        this(new RepositoryStore(repository), Instant::now);
    }
    OperatingAlertService(Store store, Supplier<Instant> now) {
        this.store = Objects.requireNonNull(store); this.now = Objects.requireNonNull(now);
    }

    @Transactional
    public void providerPriceChanged(ProviderPriceSnapshotClient.PriceSnapshot before,
            ProviderPriceSnapshotClient.PriceSnapshot after, BigDecimal ratio, Instant observedAt) {
        String fingerprint = "provider-price:" + after.provider() + ":" + after.upstreamModel();
        store.open(new Alert(UUID.randomUUID(), "PROVIDER_PRICE_CHANGED", fingerprint, "OPEN", null,
                after.logicalModel(), after.provider(), PRICE_CHANGE_THRESHOLD, ratio, after.currency(),
                observedAt.minus(1, ChronoUnit.HOURS), observedAt, List.of(), observedAt));
    }

    @Transactional
    public void evaluatePackageMargin(UUID packageVersionId, BigDecimal allocatedRevenue,
            BigDecimal providerCost, List<UUID> evidenceFactIds) {
        if (packageVersionId == null || allocatedRevenue == null || allocatedRevenue.signum() <= 0
                || providerCost == null) return;
        BigDecimal margin = allocatedRevenue.subtract(providerCost)
                .divide(allocatedRevenue, 8, RoundingMode.HALF_UP);
        if (margin.compareTo(LOW_MARGIN_THRESHOLD) < 0) {
            Instant observedAt = now.get();
            store.open(new Alert(UUID.randomUUID(), "LOW_MARGIN_PACKAGE",
                    "package-margin:" + packageVersionId, "OPEN", null, null, null,
                    LOW_MARGIN_THRESHOLD, margin, "CNY", observedAt.minus(1, ChronoUnit.DAYS),
                    observedAt, immutable(evidenceFactIds), observedAt));
        }
    }

    /**
     * 抛出与 {@link #evaluatePackageMargin} 相同的
     * {@code LOW_MARGIN_PACKAGE} 告警，但来自预检，而不是来自流量。
     *
     * <p>现有的毛利告警是由事实数据驱动的：它仅在已结算的用量
     * 到达时才触发，因此某个版本的毛利若被成本变化摧毁，就会一直保持沉默，
     * 直到真的有人使用它 —— 而无人购买的套餐，恰恰就是其
     * 毛利无人察觉的那一个。这个入口让定时重新校验能够在完全无流量的
     * 情况下报告同一条件，因此无论由哪条路径触发，告警类型及其阈值都表示
     * 同一件事。
     *
     * <p>所观察到的毛利是成本基准现在所隐含的最坏情况，而不是已实现的
     * 毛利；覆盖阈值把这一点明确表达出来。
     */
    @Transactional
    public void evaluatePublishedPlanMargin(UUID packageVersionId, String planCode,
            BigDecimal worstCaseMargin, BigDecimal floor, Instant observedAt) {
        if (packageVersionId == null || worstCaseMargin == null || floor == null) return;
        if (worstCaseMargin.compareTo(floor) < 0) {
            store.open(new Alert(UUID.randomUUID(), "LOW_MARGIN_PACKAGE",
                    "package-margin:" + packageVersionId, "OPEN", null, planCode, null,
                    floor, worstCaseMargin, "CNY", observedAt.minus(1, ChronoUnit.DAYS),
                    observedAt, List.of(), observedAt));
        }
    }

    /**
     * 每个仍处于打开状态的毛利告警所对应的版本 id，以便重新测量
     * 目录的调用方能够找出其条件它尚未（或已无法）观察到的那些告警。
     */
    @Transactional(readOnly = true)
    public List<UUID> openPublishedPlanMarginVersions() {
        return store.openLowMarginPackageVersions();
    }

    /**
     * 关闭某个版本上仍处于打开状态的 {@code LOW_MARGIN_PACKAGE} 告警，该版本的条件已
     * 不再成立；走的是与管理员的解决操作相同的生命周期：
     * 该行会置为 RESOLVED 并设置 {@code resolved_at}，绝不进入某个新状态。
     *
     * <p>操作者为 null，因为没有任何人执行该操作，审计动作也如实说明了这一点
     * （{@code OPERATING_ALERT_AUTO_RESOLVE}），而不是借用某个管理员的
     * 身份 —— 查阅审计轨迹的调查者必须能够分辨出由监控驱动的
     * 解决与人工解决。这一区别正是
     * {@link OperatingAlertQueryService} 读回为 {@code resolutionOrigin} 的内容，因此它必须
     * 始终是这两条路径之间唯一的差异。
     *
     * <p>这里必须给出原因，并且它存储在告警行本身上：监控
     * 知道它两个条件中的哪一个已解除，而因
     * {@code NO_LONGER_PUBLISHED} 关闭的告警，与因毛利恢复而关闭的
     * 告警是两个不同的事实。
     *
     * @param reason {@link #RESOLUTION_RECOVERED} 或
     *     {@link #RESOLUTION_NO_LONGER_PUBLISHED} 之一
     * @return 实际关闭了告警时为 1，没有任何告警处于打开状态时为 0。调用方
     *     报告 "resolved" 时，不得把已经解决的告警计入其中。
     */
    @Transactional
    public int resolvePublishedPlanMargin(UUID packageVersionId, String reason, Instant observedAt) {
        if (packageVersionId == null || observedAt == null) return 0;
        if (!RESOLUTION_REASONS.contains(reason)) {
            throw new IllegalArgumentException("invalid_alert_resolution_reason");
        }
        return store.openLowMarginPackageAlert(packageVersionId)
                .map(alertId -> {
                    if (store.transition(alertId, "RESOLVED", null, observedAt, reason)
                            != TransitionResult.UPDATED) {
                        return 0;
                    }
                    store.audit(alertId, null, "AUTO_RESOLVE", observedAt);
                    return 1;
                })
                .orElse(0);
    }

    @Transactional
    public void evaluateProviderCost(String model, String provider, BigDecimal requestCost,
            BigDecimal sevenDayP95, boolean priceMissing, List<UUID> evidenceFactIds) {
        if (model == null || provider == null) return;
        if (!priceMissing && (requestCost == null || sevenDayP95 == null)) return;
        BigDecimal observed = requestCost == null ? BigDecimal.ZERO : requestCost;
        BigDecimal threshold = sevenDayP95 == null
                ? BigDecimal.ZERO : sevenDayP95.multiply(COST_MULTIPLIER);
        if (priceMissing || observed.compareTo(threshold) > 0) {
            Instant observedAt = now.get();
            store.open(new Alert(UUID.randomUUID(), "ABNORMAL_PROVIDER_COST",
                    "provider-cost:" + provider + ":" + model + ":" + (priceMissing ? "missing" : "p95"),
                    "OPEN", null, model, provider, threshold, observed, "CNY",
                    observedAt.minus(7, ChronoUnit.DAYS), observedAt,
                    immutable(evidenceFactIds), observedAt));
        }
    }

    @Transactional public TransitionResult acknowledge(UUID alertId, UUID adminId) {
        Instant at = now.get();
        TransitionResult result = store.transition(alertId, "ACKED", adminId, at, null);
        if (result == TransitionResult.UPDATED) store.audit(alertId, adminId, "ACK", at);
        return result;
    }
    @Transactional public TransitionResult resolve(UUID alertId, UUID adminId) {
        Instant at = now.get();
        // 不给原因：监控的两个原因描述的是监控自身的条件，把其中
        // 之一归到某个具体人身上只能算猜测。审计动作
        //（OPERATING_ALERT_RESOLVE，且带有真实操作者）已经说明了是谁关闭的它。
        TransitionResult result = store.transition(alertId, "RESOLVED", adminId, at, null);
        if (result == TransitionResult.UPDATED) store.audit(alertId, adminId, "RESOLVE", at);
        return result;
    }
    private static List<UUID> immutable(List<UUID> ids) { return ids == null ? List.of() : List.copyOf(ids); }

    public enum TransitionResult { UPDATED, NOT_FOUND, INVALID_STATE }

    public record Alert(UUID id, String type, String fingerprint, String status, UUID tenantId,
            String logicalModel, String provider, BigDecimal threshold, BigDecimal observed,
            String currency, Instant windowStart, Instant windowEnd, List<UUID> evidenceFactIds,
            Instant observedAt) { }
    interface Store {
        boolean open(Alert value);
        TransitionResult transition(UUID id, String status, UUID actor, Instant at, String resolutionReason);
        void audit(UUID id, UUID actor, String action, Instant at);
        List<UUID> openLowMarginPackageVersions();
        Optional<UUID> openLowMarginPackageAlert(UUID packageVersionId);
    }

    private static final class RepositoryStore implements Store {
        private final OperatingAlertRepository repository;

        private RepositoryStore(OperatingAlertRepository repository) {
            this.repository = repository;
        }

        @Override
        public boolean open(Alert alert) {
            return repository.open(new OperatingAlertRepository.AlertRow(
                    alert.id(), alert.type(), alert.fingerprint(), alert.tenantId(),
                    alert.logicalModel(), alert.provider(), alert.threshold(), alert.observed(),
                    alert.currency(), alert.windowStart(), alert.windowEnd(),
                    alert.evidenceFactIds(), alert.observedAt()));
        }

        @Override
        public TransitionResult transition(
                UUID id, String status, UUID actor, Instant at, String resolutionReason) {
            return TransitionResult.valueOf(
                    repository.transition(id, status, actor, at, resolutionReason).name());
        }

        @Override
        public void audit(UUID id, UUID actor, String action, Instant at) {
            repository.audit(id, actor, action, at);
        }

        @Override
        public List<UUID> openLowMarginPackageVersions() {
            return repository.openLowMarginPackageVersions();
        }

        @Override
        public Optional<UUID> openLowMarginPackageAlert(UUID packageVersionId) {
            return repository.openLowMarginPackageAlert(packageVersionId);
        }
    }
}

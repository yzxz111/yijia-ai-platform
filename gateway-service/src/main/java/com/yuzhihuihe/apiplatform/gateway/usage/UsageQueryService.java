package com.yuzhihuihe.apiplatform.gateway.usage;

import static com.yuzhihuihe.apiplatform.gateway.usage.UsageQueryRows.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class UsageQueryService {

    private final UsageQueryRepository repository;
    private final ObjectMapper objectMapper;

    public UsageQueryService(UsageQueryRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Mono<QuotaSummary> summary(UUID tenantId, UUID userId) {
        requireIdentity(tenantId, userId);
        return repository.summary(tenantId, userId).map(this::toQuotaSummary);
    }

    /**
     * 一个套餐实例的配额快照，用于 API key 端（{@code GET /v1/quota}）。
     *
     * <p>刻意复用 {@link #toActivePackage}，这样控制台汇总与公开端点在对 "remaining" 的
     * 定义上（总量 − 预留 − 已用）永远不会出现分歧。实例不存在时返回 404 而不是空的 200：
     * 该端点没有有意义的 "no package" 载荷，而静默返回零会被客户端读成 "quota exhausted"。
     */
    public Mono<ActivePackage> packageQuota(UUID tenantId, UUID packageInstanceId) {
        requireIdentity(tenantId, packageInstanceId);
        return repository.packageInstanceQuota(tenantId, packageInstanceId)
                .flatMap(row -> row
                        .map(value -> Mono.just(toActivePackage(value)))
                        .orElseGet(() -> Mono.error(new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "package_not_found"))));
    }

    public Mono<PageResult<LedgerEntry>> ledger(
            UUID tenantId, UUID userId, PageRequest page, String modelCode, String entryType) {
        requireIdentity(tenantId, userId);
        return repository.ledger(tenantId, userId, pageSpec(page), modelCode, entryType)
                .map(rows -> toPage(rows, this::toLedgerEntry));
    }

    public Mono<PageResult<UsageEntry>> usage(
            UUID tenantId, UUID userId, PageRequest page,
            String modelCode, String outcome, String requestId) {
        return usage(tenantId, userId, page, modelCode, outcome, requestId, null, null);
    }

    public Mono<PageResult<UsageEntry>> usage(
            UUID tenantId, UUID userId, PageRequest page,
            String modelCode, String outcome, String requestId, Instant from, Instant to) {
        requireIdentity(tenantId, userId);
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "usage time range is invalid");
        }
        return repository.usage(
                        tenantId, userId, pageSpec(page), modelCode, outcome, requestId, from, to)
                .map(rows -> toPage(rows, this::toUsageEntry));
    }

    public Mono<UsageMetrics> metrics(UUID tenantId, UUID userId) {
        requireIdentity(tenantId, userId);
        return repository.metrics(tenantId, userId)
                .map(row -> new UsageMetrics(row.sampleCount(), row.platformAddedP95Ms()));
    }

    public Mono<PageResult<UsageEntry>> adminUsage(
            PageRequest page, UUID userId, String modelCode, String outcome, String requestId,
            String sort, String direction) {
        return repository.adminUsage(
                        pageSpec(page), userId, modelCode, outcome, requestId, sort, direction)
                .map(rows -> toPage(rows, this::toUsageEntry));
    }

    public Mono<PageResult<AdminLedgerEntry>> adminLedger(
            PageRequest page, UUID userId, String modelCode, String entryType,
            String sort, String direction) {
        return repository.adminLedger(pageSpec(page), userId, modelCode, entryType, sort, direction)
                .map(rows -> toPage(rows, this::toAdminLedgerEntry));
    }

    public Mono<PageResult<ReservationEntry>> adminReservations(
            PageRequest page, UUID userId, String status, String requestId, boolean anomalyOnly,
            String sort, String direction) {
        return repository.adminReservations(
                        pageSpec(page), userId, status, requestId, anomalyOnly, sort, direction)
                .map(rows -> toPage(rows, this::toReservationEntry));
    }

    public Mono<PageResult<ProviderPriceEntry>> providerPrices(
            PageRequest page, String providerCode, String modelCode,
            String sort, String direction) {
        return repository.providerPrices(pageSpec(page), providerCode, modelCode, sort, direction)
                .map(rows -> toPage(rows, this::toProviderPriceEntry));
    }

    private QuotaSummary toQuotaSummary(SummaryRow row) {
        ActivePackage activePackage = row.activePackage() == null
                ? null
                : toActivePackage(row.activePackage());
        List<ActivePackage> activePackages = row.activePackages().stream()
                .map(this::toActivePackage)
                .toList();
        List<QueuedPackage> queuedPackages = row.queuedPackages().stream()
                .map(this::toQueuedPackage)
                .toList();
        return new QuotaSummary(activePackage, activePackages, queuedPackages);
    }

    private ActivePackage toActivePackage(ActivePackageRow row) {
        long remaining = row.totalTokens() - row.reservedTokens() - row.usedTokens();
        List<ModelQuota> modelQuotas = row.modelQuotas().stream()
                .map(this::toModelQuota)
                .toList();
        return new ActivePackage(
                row.packageInstanceId(), row.packageCode(), row.packageName(), row.status(),
                row.totalTokens(), row.reservedTokens(), row.usedTokens(), remaining,
                row.dailyRequestLimit(), row.expiresAt(),
                allowedModels(row.allowedModelsJson()), row.usageDimension(), modelQuotas,
                audioSeconds(row));
    }

    /**
     * 第二份额度（音频秒），或 {@code null} 表示这个实例不卖语音（方案 C）。
     *
     * <p>与主计数器同一套算术（剩余 = 总额 − 在途 − 已用，**不做下限截断**：
     * 一次结算高于预留时它可以是负数，这正是主计数器已经采用的约定），
     * 理由也一样——客户要据它判断还能不能发下一次调用。
     */
    private static AudioSeconds audioSeconds(ActivePackageRow row) {
        Long quota = row.audioSecondsQuota();
        if (quota == null) {
            return null;
        }
        long reserved = row.audioSecondsReserved() == null ? 0L : row.audioSecondsReserved();
        long used = row.audioSecondsUsed() == null ? 0L : row.audioSecondsUsed();
        return new AudioSeconds(quota, reserved, used, quota - reserved - used);
    }

    private ModelQuota toModelQuota(ModelQuotaRow row) {
        long remaining = row.totalTokens() - row.reservedTokens() - row.usedTokens();
        return new ModelQuota(
                row.modelCode(), row.totalTokens(), row.reservedTokens(), row.usedTokens(), remaining);
    }

    private QueuedPackage toQueuedPackage(QueuedPackageRow row) {
        return new QueuedPackage(
                row.packageInstanceId(), row.packageCode(), row.packageName(),
                row.totalTokens(), row.createdAt(), row.usageDimension());
    }

    private LedgerEntry toLedgerEntry(LedgerRow row) {
        return new LedgerEntry(
                row.id(), row.requestId(), row.reservationId(), row.packageInstanceId(),
                row.modelCode(), row.entryType(), row.totalTokenDelta(), row.modelTokenDelta(),
                row.balanceAfter(), row.modelBalanceAfter(), row.createdAt());
    }

    private AdminLedgerEntry toAdminLedgerEntry(AdminLedgerRow row) {
        return new AdminLedgerEntry(
                row.id(), row.requestId(), row.tenantId(), row.userId(), row.reservationId(),
                row.packageInstanceId(), row.modelCode(), row.entryType(), row.totalTokenDelta(),
                row.modelTokenDelta(), row.balanceAfter(), row.modelBalanceAfter(), row.createdAt());
    }

    private UsageEntry toUsageEntry(UsageRow row) {
        List<UsageStageEntry> stages = row.stages().stream()
                .map(this::toUsageStageEntry)
                .toList();
        return new UsageEntry(
                row.id(), row.requestId(), row.reservationId(), row.tenantId(), row.userId(),
                row.apiKeyId(), row.apiKeyPrefix(), row.packageInstanceId(), row.modelCode(),
                row.providerCode(), row.channelCode(), row.upstreamModel(), row.inputTokens(),
                row.outputTokens(), row.totalTokens(), row.usageDimension(), row.usageUnits(),
                row.usageEstimated(), row.stream(),
                row.firstTokenMs(), row.durationMs(), row.httpStatus(), row.outcome(),
                row.errorCategory(), row.errorSummary(),
                row.generationStatus(), row.finishReason(),
                row.effectiveMaxOutputTokens(), row.ceilingSource(), row.attemptCount(),
                row.createdAt(), stages,
                quotaCharge(row.quotaCharge(), row.usageDimension()));
    }

    /**
     * 一次调用真实扣掉的额度。
     *
     * <p><b>为什么不能拿手边的数字凑。</b>一条用量记录上有 {@code usageUnits}（计量用量，
     * 可能同时由套餐与钱包承担）、{@code totalTokens}（对视频/图片恒为 0）、以及当前
     * 参考价，三者没有一个是"套餐实扣"。钱包补扣、并发请求和历史价格变化都会让任何
     * 反推失真，所以这里只读结算账本写下的三个事实，缺一个就报 {@code null}。
     *
     * <p><b>{@code null} 与 {@code 0} 的分工。</b>{@code 0} 是"已确认没有扣减"，
     * {@code null} 是"未知、未结算或不适用"。释放与过期之所以报 {@code 0} 而不是
     * {@code null}，是因为账本上有 RESERVE 行却不该有 SETTLE 行——预占被原样放回，
     * 这个"没有扣"是被记录下来的事实，不是查不到。反过来，一次**只有钱包**承担的调用
     * 仍然报 {@code packageChargedUnits: 0}：套餐池确实扣了零，客户端要能据此判断
     * 它没有花套餐额度。
     *
     * <p>模型子额度单独走 {@link #modelChargedUnits}，因为它的"无"与"零"还有第三种
     * 形态：预留时就没占独立额度池的调用，全程只有 {@code null}。
     */
    static QuotaCharge quotaCharge(UsageChargeRow row, String usageDimension) {
        if (row == null) {
            return null;
        }
        String unit = quotaUnit(usageDimension);
        String status = row.status() == null ? "" : row.status().strip().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "SETTLED" -> new QuotaCharge(
                    status, unit, row.packageSettledTokens(),
                    modelChargedUnits(row), row.walletSettledAmount(), row.settledAt());
            // 在途：真实扣减还没发生，三个实扣字段一律未知——把预占数报出来正是
            // "把预占当实扣"那类事故的形态。
            case "RESERVED" -> new QuotaCharge(status, unit, null, null, null, null);
            // 释放与过期：预占已原样放回，确认没有扣减。
            case "RELEASED", "EXPIRED" -> new QuotaCharge(
                    status, unit, 0L,
                    row.hasReserveModelDelta() ? 0L : null, 0L, null);
            // 预留表上的状态受 CHECK 约束，走到这里说明读到了协议之外的值。
            // 报未知比猜一个状态更安全：客户端会显示"未知"而不是"已扣 0"。
            default -> new QuotaCharge(
                    row.status(), unit, null, null, null, null);
        };
    }

    /**
     * 独立模型子额度的实扣量：{@code 预占 − 结算差额}。
     *
     * <p>这个减法就是账本自己的恒等式——{@code QuotaReservationRepository.settle}
     * 写入的 SETTLE 行差额正是 {@code 预占 − 实扣}（预占 80、实扣 60 时记 +20），
     * 所以反解出来的就是实扣。
     *
     * <p>与套餐实扣取自同一笔消费、同一份预占，**两者不可相加**：它们描述的是
     * 一次扣减在两个受约束额度池上的投影，而不是两笔消费。
     *
     * <p>{@code null} 覆盖三种"没有依据"的情形：预留时本来就没占独立模型额度、
     * 结算没有写账本行、或账本行上的差额为空。三者都会让减法失去被减数，
     * 而默认成 0 会把"这个模型没有独立额度"说成"模型额度一分没扣"。
     */
    private static Long modelChargedUnits(UsageChargeRow row) {
        if (!row.hasSettleLedger() || row.settleModelDelta() == null) {
            return null;
        }
        return row.packageReservedTokens() - row.settleModelDelta();
    }

    /** 用量维度 → 额度单位；未知或缺失维度为 {@code null}，绝不默认成积分。 */
    static String quotaUnit(String usageDimension) {
        if (usageDimension == null) {
            return null;
        }
        return switch (usageDimension.strip().toUpperCase(Locale.ROOT)) {
            case "TOKEN" -> "CREDIT";
            case "IMAGE" -> "IMAGE";
            case "VIDEO_SECOND", "AUDIO_SECOND" -> "SECOND";
            default -> null;
        };
    }

    private UsageStageEntry toUsageStageEntry(UsageStageRow row) {
        return new UsageStageEntry(
                row.stageIndex(), row.stageType(), row.logicalModelCode(), row.providerCode(),
                row.channelCode(), row.upstreamModel(), row.inputTokens(), row.outputTokens(),
                row.totalTokens(), row.durationMs(), row.outcome(), row.providerCostAmount(),
                row.providerCostCurrency(), row.priceEstimated());
    }

    private ReservationEntry toReservationEntry(ReservationRow row) {
        return new ReservationEntry(
                row.id(), row.requestId(), row.tenantId(), row.userId(), row.apiKeyId(),
                row.packageInstanceId(), row.modelCode(), row.reservedTokens(),
                row.actualInputTokens(), row.actualOutputTokens(), row.actualTotalTokens(),
                row.uncoveredTokens(), row.usageEstimated(), row.status(), row.expiresAt(),
                row.settledAt(), row.releasedAt(), row.createdAt());
    }

    private ProviderPriceEntry toProviderPriceEntry(ProviderPriceRow row) {
        return new ProviderPriceEntry(
                row.id(), row.providerCode(), row.modelCode(), row.inputPricePerMillion(),
                row.outputPricePerMillion(), row.cacheReadPricePerMillion(),
                row.cacheWritePricePerMillion(), row.reasoningPricePerMillion(), row.currency(),
                row.internalExchangeRate(), row.officialSource(), row.effectiveFrom(), row.effectiveTo());
    }

    private List<String> allowedModels(String json) {
        try {
            List<String> models = new ArrayList<>();
            objectMapper.readTree(json).forEach(node -> {
                String modelCode = node.path("modelCode").asText("");
                if (!modelCode.isBlank()) {
                    models.add(modelCode);
                }
            });
            return List.copyOf(models);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("package allowed_models is invalid", exception);
        }
    }

    private static PageSpec pageSpec(PageRequest page) {
        PageRequest valid = page.validated();
        return new PageSpec(valid.page(), valid.size());
    }

    private static <R, T> PageResult<T> toPage(PageRows<R> rows, Function<R, T> mapper) {
        return new PageResult<>(
                rows.items().stream().map(mapper).toList(),
                rows.total(), rows.page(), rows.size());
    }

    private static void requireIdentity(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_identity");
        }
    }

    public record PageRequest(int page, int size) {
        public PageRequest validated() {
            if (page < 0 || size < 1 || size > 100) {
                throw invalidPagination();
            }
            try {
                offset();
            } catch (ArithmeticException error) {
                throw invalidPagination();
            }
            return this;
        }

        int offset() {
            return Math.multiplyExact(page, size);
        }

        private ResponseStatusException invalidPagination() {
            return new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_pagination");
        }
    }

    public record PageResult<T>(List<T> items, long total, int page, int size) {}

    public record UsageMetrics(long sampleCount, long platformAddedP95Ms) {}

    /**
     * {@code activePackage} 是兼容字段（文本实例优先，否则唯一那个）。
     * {@code activePackages} 才是完整载荷：自 V80 起一个用户可以每个维度各持有一个
     * 生效实例，控制台必须逐个渲染，而不能只显示其中一个。
     */
    public record QuotaSummary(
            ActivePackage activePackage,
            List<ActivePackage> activePackages,
            List<QueuedPackage> queuedPackages) {}

    public record ActivePackage(
            UUID packageInstanceId, String packageCode, String packageName, String status,
            long totalTokens, long reservedTokens, long usedTokens, long remainingTokens,
            Integer dailyRequestLimit, Instant expiresAt,
            List<String> allowedModels,
            /**
             * TOKEN、VIDEO_SECOND、IMAGE 或 AUDIO_SECOND —— 上面每个计数器所用的单位。
             *
             * <p>VIDEO_SECOND 与 AUDIO_SECOND 套餐的计数器以**秒**为单位。客户端必须读这个
             * 字段，并且读它就是为了**判断单位**：把秒标成 credits 是这个接口最容易出的事故
             * （600 会被渲染成 600 积分）。
             *
             * <p>读得到 ≠ 要显示。产品口径是视频/音频套餐的秒数额度**不在界面上打印**，
             * 只写「按秒计费」；那是客户端的渲染约定，不是接口的取数约定——本接口与
             * {@code /quota/entitlements} 都照常返回秒数，因为"这次请求还能不能提交"
             * 只能由它判断。别把这条读成"可以忽略这些数字"。
             */
            String usageDimension,
            List<ModelQuota> modelQuotas,
            /**
             * 方案 C 的第二份额度，或 {@code null} 表示这个套餐不卖语音。
             * 这两个字段一起才描述完一个套餐的额度：{@code usageDimension} 那份额度
             * 与音频秒那一份。控制台必须为 {@code null} 时**不渲染**，
             * 而不是显示 0 秒。
             */
            AudioSeconds audioSeconds) {}

    /**
     * 混合套餐里的音频秒那一份额度。
     *
     * <p>四个字段**永远是秒**，不随 {@code usageDimension} 变化——第二份桶只属于
     * {@code AUDIO_SECOND}（网关 V97 的 CHECK 就是这么写的），因此这里不必再带一个
     * 单位字段，也就不会出现"读了单位却读错"的空间。
     */
    public record AudioSeconds(
            long totalSeconds, long reservedSeconds, long usedSeconds, long remainingSeconds) {}

    public record ModelQuota(
            String modelCode, long totalTokens, long reservedTokens,
            long usedTokens, long remainingTokens) {}

    /**
     * 队列现在按维度推进，因此排队项也带 {@code usageDimension}：控制台需要据此
     * 决定单位（秒 vs credits），否则一个排队的视频套餐会被渲染成 token 套餐。
     */
    public record QueuedPackage(
            UUID packageInstanceId, String packageCode, String packageName,
            long totalTokens, Instant createdAt, String usageDimension) {}

    public record LedgerEntry(
            UUID id, String requestId, UUID reservationId, UUID packageInstanceId,
            String modelCode, String entryType, long totalTokenDelta, Long modelTokenDelta,
            long balanceAfter, Long modelBalanceAfter, Instant createdAt) {}

    public record AdminLedgerEntry(
            UUID id, String requestId, UUID tenantId, UUID userId,
            UUID reservationId, UUID packageInstanceId, String modelCode, String entryType,
            long totalTokenDelta, Long modelTokenDelta,
            long balanceAfter, Long modelBalanceAfter, Instant createdAt) {}

    public record UsageEntry(
            UUID id, String requestId, UUID reservationId, UUID tenantId, UUID userId,
            UUID apiKeyId, String apiKeyPrefix, UUID packageInstanceId, String modelCode,
            String providerCode, String channelCode, String upstreamModel,
            long inputTokens, long outputTokens,
            long totalTokens,
            /**
             * 计量消耗所用的单位，以及在该单位下消耗的数量。VIDEO_SECOND 调用按秒结算，其
             * token 计数器受约束为零，因此仅凭 {@code totalTokens} 会把一次视频生成报告成
             * "0 credits" —— 控制台对这些行必须读取 {@code usageUnits}。
             */
            String usageDimension, long usageUnits,
            boolean usageEstimated, boolean stream, Long firstTokenMs,
            long durationMs, int httpStatus, String outcome, String errorCategory,
            String errorSummary,
            /**
             * 生成终态：{@code completed} / {@code failed} / {@code truncated}；历史行为 {@code null}。
             *
             * <p><b>为什么它与 {@code outcome} 同时存在。</b>{@code outcome} 说的是"这次调用在
             * 计量上怎么处置"，{@code httpStatus} 说的是"传输层结果"，而这一列说的是
             * "这次生成完成了没有"。被输出上限截断的请求三者分别是
             * {@code SUCCEEDED} / {@code 200} / {@code truncated}——只看前两个字段，它与一次
             * 自然结束的请求完全一样，而两者的处置完全不同（前者要收窄需求或换更长套餐）。
             */
            String generationStatus,
            /** 上游给出的结束原因（{@code stop} / {@code length} …）；未观察到为 {@code null}。 */
            String finishReason,
            /** 本次请求被准入的输出上限；{@code null} 表示未知（历史行，或上限尚未算出）。 */
            Long effectiveMaxOutputTokens,
            /**
             * 上限是被哪一层压出来的：{@code client} / {@code model} / {@code package} / {@code system}。
             *
             * <p>只有数字回答不了运维真正要问的问题。同一个 4096，来自客户端
             * {@code max_tokens} 与来自套餐配置的处置完全不同：前者要客户端改请求，
             * 后者要改套餐。因此这两个字段必须一起读。
             */
            String ceilingSource,
            /** 这次请求产生了几次供应商调用（含重试与换渠道）；历史行为 {@code null}。 */
            Integer attemptCount,
            Instant createdAt, List<UsageStageEntry> stages,
            /**
             * 这次模型调用真实扣掉的额度，见 {@link QuotaCharge}。
             *
             * <p>{@code null} 只表示这条用量记录关联不到预占／结算记录（历史行、
             * 或 {@code reservation_id} 为空）。**缺这个键与它是 {@code null} 不是一回事**：
             * 新服务的响应里这个键永远在，旧服务的响应里可能完全没有它，
             * 客户端不得把"键不存在"读成"没有消耗"。
             */
            QuotaCharge quotaCharge) {

        public UsageEntry {
            stages = List.copyOf(stages);
        }
    }

    /**
     * 一次模型调用的真实套餐扣量、独立模型扣量、钱包扣款与结算状态。
     *
     * <p><b>三个数量字段不属于同一个量纲，禁止相加。</b>
     * {@code packageChargedUnits} 与 {@code modelChargedUnits} 是同一次消费在套餐池和
     * 模型子池上的两个投影（同一次扣减，不是两笔），{@code walletChargedAmount} 是金额
     * （分），而上面用量行上的 {@code usageUnits} 才是计量用量——它可能由套餐和钱包
     * 共同承担，因此**不能**当作套餐实扣。
     *
     * @param status               {@code RESERVED} 仅预占 / {@code SETTLED} 已结算 /
     *                             {@code RELEASED} 已释放 / {@code EXPIRED} 已过期。
     *                             取自预留行，**不是**用量行的 {@code outcome}：
     *                             一次失败请求释放预占要以结算记录为准
     * @param quotaUnit            按用量维度映射的单位：{@code CREDIT} / {@code IMAGE} /
     *                             {@code SECOND}；未知或缺失维度为 {@code null}
     * @param packageChargedUnits  套餐实扣量（单位见 {@code quotaUnit}）。{@code null} =
     *                             尚未结算或未知；{@code 0} = 确认没有扣套餐额度
     * @param modelChargedUnits    独立模型子额度实扣量。{@code null} = 这次消费不涉及
     *                             独立模型额度，或结算账本没给出依据；{@code 0} = 曾占用
     *                             但已全额放回
     * @param walletChargedAmount  钱包实扣金额，固定为人民币分
     * @param settledAt            实际结算时间；未结算为 {@code null}
     */
    public record QuotaCharge(
            String status,
            String quotaUnit,
            Long packageChargedUnits,
            Long modelChargedUnits,
            Long walletChargedAmount,
            Instant settledAt) {}

    public record UsageStageEntry(
            int stageIndex, String stageType, String logicalModelCode,
            String providerCode, String channelCode, String upstreamModel,
            long inputTokens, long outputTokens, long totalTokens,
            long durationMs, String outcome,
            Long providerCostAmount, String providerCostCurrency, boolean priceEstimated) {}

    public record ReservationEntry(
            UUID id, String requestId, UUID tenantId, UUID userId, UUID apiKeyId,
            UUID packageInstanceId, String modelCode, long reservedTokens,
            Long actualInputTokens, Long actualOutputTokens, Long actualTotalTokens,
            long uncoveredTokens, boolean usageEstimated, String status, Instant expiresAt, Instant settledAt,
            Instant releasedAt, Instant createdAt) {}

    public record ProviderPriceEntry(
            UUID id, String providerCode, String modelCode, long inputPricePerMillion,
            long outputPricePerMillion, Long cacheReadPricePerMillion,
            Long cacheWritePricePerMillion, Long reasoningPricePerMillion,
            String currency, BigDecimal internalExchangeRate,
            String officialSource, Instant effectiveFrom, Instant effectiveTo) {}
}

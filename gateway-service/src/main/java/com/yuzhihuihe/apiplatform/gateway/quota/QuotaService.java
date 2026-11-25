package com.yuzhihuihe.apiplatform.gateway.quota;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;

import com.yuzhihuihe.apiplatform.gateway.billing.ModelBillingPriceCalculator;
import com.yuzhihuihe.apiplatform.gateway.billing.ModelBillingPriceService;
import com.yuzhihuihe.apiplatform.gateway.billing.WalletUsageClient;

import reactor.core.publisher.Mono;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class QuotaService {

    private static final Logger LOG = LoggerFactory.getLogger(QuotaService.class);

    private final QuotaReservationRepository repository;
    private final PackageActivationService packageActivationService;
    private final TransactionalOperator transactions;
    private final ModelBillingPriceService priceService;
    private final WalletUsageClient walletClient;

    public QuotaService(
            QuotaReservationRepository repository,
            PackageActivationService packageActivationService,
            TransactionalOperator transactions,
            ModelBillingPriceService priceService,
            WalletUsageClient walletClient) {
        this.repository = repository;
        this.packageActivationService = packageActivationService;
        this.transactions = transactions;
        this.priceService = priceService;
        this.walletClient = walletClient;
    }

    public Mono<Reservation> reserve(ReserveCommand command) {
        validate(command);
        Mono<Reservation> reservation = transactions.transactional(
                        repository.lockExisting(command.reservationId(), command.requestId())
                                .flatMap(existing -> existingReservation(command, existing))
                                .switchIfEmpty(Mono.defer(() -> reserveNew(command))))
                .onErrorMap(QuotaReservationRepository.RefundHoldInProgressException.class,
                        error -> conflict("package_refund_in_progress"));
        return reservation.flatMap(result -> enforceRequiredDimension(command, result));
    }

    /**
     * 调用方可以在配额边界上要求特定的用量单位。对话预留用它来
     * 在一个陈旧的模型/套餐准入带着以秒计量的套餐抵达 token 账本时 fail-closed；
     * 视频生成则不设该要求，继续通过同一账本预留其 VIDEO_SECOND 单位。
     */
    private Mono<Reservation> enforceRequiredDimension(
            ReserveCommand command, Reservation reservation) {
        String required = command.requiredUsageDimension();
        if (required == null || required.equals(reservation.usageDimension())) {
            return Mono.just(reservation);
        }
        // 不匹配的既有预留可能属于一个已被接受的视频任务。
        // 绝不要在对话边界上释放它：保留一个未知的已付费结果
        // 比放出可被花在重复生成上的秒数更安全。
        return Mono.error(unprocessable("usage_dimension_mismatch"));
    }

    public Mono<Settlement> settle(
            UUID reservationId,
            TokenUsage usage,
            UsageQuality quality) {
        return Mono.defer(() -> {
            validate(reservationId, usage, quality);
            Mono<Settlement> settlement = repository.findReservation(reservationId)
                    .switchIfEmpty(Mono.error(notFound("reservation_not_found")))
                    .flatMap(observed -> repository.advisoryLock(observed.userId())
                            .then(Mono.defer(() -> {
                                if (observed.packageInstanceId() == null) {
                                    return repository.lockReservation(reservationId)
                                            .switchIfEmpty(Mono.error(notFound("reservation_not_found")))
                                            .flatMap(reservation -> settleWalletOnly(
                                                    reservation, usage, quality));
                                }
                                return lockQuota(observed).flatMap(quota -> repository.lockReservation(reservationId)
                                        .switchIfEmpty(Mono.error(notFound("reservation_not_found")))
                                        .flatMap(reservation -> settleLocked(
                                                reservation, usage, quality, quota)));
                            })));
            return transactions.transactional(settlement);
        });
    }

    public Mono<Void> release(UUID reservationId, ReleaseReason reason) {
        return release(reservationId, reason, false);
    }

    Mono<Void> expire(UUID reservationId) {
        return release(reservationId, ReleaseReason.RECOVERY_TIMEOUT, true);
    }

    private Mono<Reservation> existingReservation(
            ReserveCommand command,
            QuotaReservationRepository.ReservationRow existing) {
        boolean same = existing.id().equals(command.reservationId())
                && existing.requestId().equals(command.requestId())
                && existing.tenantId().equals(command.tenantId())
                && existing.userId().equals(command.userId())
                && existing.apiKeyId().equals(command.apiKeyId())
                && existing.modelCode().equals(command.modelCode())
                && existing.reservedTokens() == command.tokens();
        return same
                ? Mono.just(toReservation(existing))
                : Mono.error(conflict("reservation_idempotency_mismatch"));
    }

    private Mono<Reservation> reserveNew(ReserveCommand command) {
        return repository.idempotencyLock(command.reservationId(), command.requestId())
                .then(repository.lockExisting(command.reservationId(), command.requestId())
                        .flatMap(existing -> existingReservation(command, existing))
                        .switchIfEmpty(Mono.defer(() -> repository.advisoryLock(command.userId())
                                .then(repository.hasOpenRefundHold(command))
                                .flatMap(open -> open
                                        ? Mono.error(conflict("package_refund_in_progress"))
                                        : repository.lockActivePackage(command)
                                                .flatMap(lockedPackage -> reserveLocked(command, lockedPackage))
                                                .switchIfEmpty(Mono.defer(() -> reserveWalletOnly(
                                                        command, command.requiredUsageDimension())))))));
    }

    private Mono<Reservation> reserveLocked(
            ReserveCommand command,
            QuotaReservationRepository.LockedPackage lockedPackage) {
        return repository.lockModelQuota(lockedPackage.id(), command.modelCode())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(optional -> reserveWithModelQuota(
                        command, lockedPackage, optional.orElse(null)));
    }

    /**
     * 本次预留扣哪一个桶（方案 C）。
     *
     * <p>三个信息源，优先级与交叉校验都是刻意的：
     *
     * <ol>
     *   <li><b>调用方的声明</b>（{@code requiredUsageDimension}）——"我买的是
     *       什么单位"。对话路径声明 TOKEN，视频/图片/音频各自声明自己的维度。
     *   <li><b>该模型自额度行的维度</b>——平台下发的 {@code model_usage_dimension}
     *       （随权益快照逐模型到来）。它就是需求里说的"本次服务的模型的维度"。
     *   <li><b>实例的主维度</b>——两者都没有时的兜底，语义与 V42 完全一致。
     * </ol>
     *
     * <p>前两者**同时存在且不一致**时直接拒绝：那说明准入信息已经陈旧，或者
     * 平台把某个别名的维度改过而网关的额度行还是旧的。此时不能猜——猜错的
     * 方向是用音频秒去花视频秒的额度，而它不会报错，只会算错钱。
     *
     * <p>最后还要实例真的支持这个维度（{@link
     * QuotaReservationRepository.LockedPackage#supports}）：一个只卖视频秒的
     * 实例收到一次音频请求，必须在这里就失败，而不是落进某个桶的算术里。
     */
    private Mono<String> billingDimension(
            ReserveCommand command,
            QuotaReservationRepository.LockedPackage lockedPackage,
            QuotaReservationRepository.ModelQuota modelQuota) {
        String required = command.requiredUsageDimension();
        String declared = modelQuota == null ? null : modelQuota.usageDimension();
        if (required != null && declared != null && !required.equals(declared)) {
            return Mono.error(unprocessable("usage_dimension_mismatch"));
        }
        String dimension = required != null ? required
                : declared != null ? declared : lockedPackage.usageDimension();
        return lockedPackage.supports(dimension)
                ? Mono.just(dimension)
                : Mono.error(unprocessable("usage_dimension_mismatch"));
    }

    private Mono<Reservation> reserveWithModelQuota(
            ReserveCommand command,
            QuotaReservationRepository.LockedPackage lockedPackage,
            QuotaReservationRepository.ModelQuota modelQuota) {
        return billingDimension(command, lockedPackage, modelQuota).flatMap(dimension -> {
            long packageTokens = Math.min(
                    command.tokens(), lockedPackage.availableFor(dimension));
            if (modelQuota != null) {
                packageTokens = Math.min(packageTokens, Math.max(0, modelQuota.available()));
            }
            final long reservedPackageTokens = packageTokens;
            if (reservedPackageTokens < command.tokens() && !"TOKEN".equals(dimension)) {
                // 只有在 token 维度之外才做预付。按秒计费的别名没有按 token 的价格，
                // 因此没有可以用来收取缺额的对象：该请求要么稍后作为未定价的模型失败，
                // 要么更糟——启动一次套餐无法覆盖的生成。按它本来的性质报成配额问题，
                // 客户端看到的是 422 package_unavailable。
                //
                // 注意这里判的是**本次请求所属的维度**，不是实例的主维度：一个
                // 视频秒套餐里的音频别名同样是按秒计的，缺额同样没有对象可收。
                return Mono.error(unprocessable("quota_exhausted"));
            }
            if (reservedPackageTokens == 0) {
                return reserveWalletOnly(command, dimension);
            }
            if (reservedPackageTokens == command.tokens()) {
                // 套餐覆盖了整个请求，因此不产生任何钱包计费。
                //
                // 这里判断的是预留数量，而不是输入/输出拆分，因为非 token 维度（视频秒数）
                // 把数量放在 `tokens` 里，没有 token 形态的输入或输出。若依据该拆分来判断，
                // 会把一个已被完全覆盖的视频请求当作完全没有覆盖，进而要求为按秒计费的
                // 别名给出按 token 的价格。这同样覆盖了零价格的免费套餐，
                // 它绝不允许走钱包计费。
                return repository.addReservation(command, lockedPackage, modelQuota,
                                reservedPackageTokens, 0, null, dimension)
                        .thenReturn(new Reservation(command.reservationId(), command.requestId(),
                                lockedPackage.id(), command.modelCode(), command.tokens(),
                                "RESERVED", command.expiresAt(), dimension));
            }
            long packageInput = Math.min(command.inputTokens(), reservedPackageTokens);
            long packageOutput = Math.min(command.outputTokens(), Math.max(0, reservedPackageTokens - packageInput));
            long walletInput = command.inputTokens() - packageInput;
            long walletOutput = command.outputTokens() - packageOutput;
            if (walletInput == 0 && walletOutput == 0) {
                // 在 token 拆分上也已被完全覆盖。
                return repository.addReservation(command, lockedPackage, modelQuota,
                                reservedPackageTokens, 0, null, dimension)
                        .thenReturn(new Reservation(command.reservationId(), command.requestId(),
                                lockedPackage.id(), command.modelCode(), command.tokens(),
                                "RESERVED", command.expiresAt(), dimension));
            }
            return priceService.current(command.modelCode()).flatMap(price -> {
                // 按最坏情况（WORST CASE）预留，而不是按请求自身的类别拆分。供应商只有在
                // 为其付费之后，才会报告这次 prompt 是缓存命中、缓存写入还是未命中，
                // 因此钱包预留把每个钱包 token 都按它可能落到的最贵费率定价。
                // 参见 ReservationCeiling。
                long walletAmount = ReservationCeiling.worstCaseAmount(price, walletInput, walletOutput);
                Mono<Void> walletReserve = walletAmount == 0 ? Mono.empty()
                        : walletClient.reserve(new WalletUsageClient.WalletRequest(
                                command.reservationId(), command.requestId(), command.tenantId(), command.userId(),
                                command.modelCode(), walletAmount, walletInput, walletOutput,
                                price.priceVersion(), "model-usage:" + command.reservationId(),
                                command.expiresAt())).then();
                Mono<Void> localReservation = repository.addReservation(
                        command, lockedPackage, modelQuota, reservedPackageTokens,
                        walletAmount, price.priceVersion(), dimension);
                return walletReserve.then(walletAmount == 0
                                ? localReservation
                                : localReservation.onErrorResume(error ->
                                        compensateWalletReservation(command.reservationId(), error)))
                        .thenReturn(new Reservation(command.reservationId(), command.requestId(),
                                reservedPackageTokens == 0 ? null : lockedPackage.id(),
                                command.modelCode(), command.tokens(),
                                "RESERVED", command.expiresAt(), dimension));
            }).onErrorMap(WalletUsageClient.WalletUsageClientException.class, this::mapReserveWalletError);
        });
    }

    private Mono<Reservation> reserveWalletOnly(ReserveCommand command, String dimension) {
        if (dimension != null && !"TOKEN".equals(dimension)) {
            // 一个非 token 维度（VIDEO_SECOND / IMAGE / AUDIO_SECOND）没有钱包价格可依据。
            // 钱包是按 gateway.model_billing_price 计价的，而那张表的列全是「每百万 token」，
            // 用它给一次按秒或按张计的生成定价就是把单位搞错；这些别名也根本没有
            // 那样的价格行，于是这条路径今天会以一个与配额无关的 500 结束
            // （model_price_unavailable）。没有生效套餐时在这里 fail-closed 成配额问题，
            // 与 reserveWithModelQuota 中「非 token 维度纯预付」的规则一致。
            return Mono.error(unprocessable("quota_exhausted"));
        }
        return priceService.current(command.modelCode()).flatMap(price -> {
            long walletAmount = ReservationCeiling.worstCaseAmount(
                    price, command.inputTokens(), command.outputTokens());
            if (walletAmount <= 0) {
                return Mono.error(unprocessable("model_price_unavailable"));
            }
            return walletClient.reserve(new WalletUsageClient.WalletRequest(
                            command.reservationId(), command.requestId(), command.tenantId(), command.userId(),
                            command.modelCode(), walletAmount, command.inputTokens(), command.outputTokens(),
                            price.priceVersion(), "model-usage:" + command.reservationId(),
                            command.expiresAt()))
                    .then(repository.addWalletReservation(command, walletAmount, price.priceVersion())
                            .onErrorResume(error ->
                                    compensateWalletReservation(command.reservationId(), error)))
                    .thenReturn(new Reservation(command.reservationId(), command.requestId(), null,
                            command.modelCode(), command.tokens(), "RESERVED", command.expiresAt()));
        }).onErrorMap(WalletUsageClient.WalletUsageClientException.class, this::mapReserveWalletError);
    }

    private Mono<Void> compensateWalletReservation(UUID reservationId, Throwable originalError) {
        return walletClient.release(reservationId)
                .doOnError(compensationError -> LOG.error(
                        "wallet reservation compensation failed for {}",
                        reservationId,
                        compensationError))
                .onErrorResume(compensationError -> Mono.empty())
                .then(Mono.error(originalError));
    }

    private Mono<LockedQuota> lockQuota(
            QuotaReservationRepository.ReservationRow reservation) {
        return repository.lockPackage(reservation.packageInstanceId())
                .switchIfEmpty(Mono.error(new IllegalStateException("reservation_package_missing")))
                .flatMap(lockedPackage -> repository
                        .lockModelQuota(lockedPackage.id(), reservation.modelCode())
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty())
                        .map(optional -> new LockedQuota(lockedPackage, optional.orElse(null))));
    }

    private Mono<Settlement> settleLocked(
            QuotaReservationRepository.ReservationRow reservation,
            TokenUsage usage,
            UsageQuality quality,
            LockedQuota quota) {
        if ("SETTLED".equals(reservation.status())) {
            return existingSettlement(reservation);
        }
        if (!"RESERVED".equals(reservation.status())) {
            return Mono.error(conflict("reservation_not_settleable"));
        }
        return applySettlement(
                reservation, usage, quality, quota.lockedPackage(), quota.modelQuota());
    }

    private Mono<Settlement> settleWalletOnly(
            QuotaReservationRepository.ReservationRow reservation,
            TokenUsage usage,
            UsageQuality quality) {
        if ("SETTLED".equals(reservation.status())) {
            return existingSettlement(reservation);
        }
        if (!"RESERVED".equals(reservation.status())) {
            return Mono.error(conflict("reservation_not_settleable"));
        }
        return walletSettlementAmount(reservation, usage, 0)
                .flatMap(claim -> walletClient.settle(reservation.walletUsageReservationId(),
                                new WalletUsageClient.WalletSettlement(reservation.tenantId(), reservation.userId(),
                                        claim.claimableAmount(), usage.inputTokens(), usage.outputTokens(),
                                        walletBillingMode(0, claim.claimableAmount())))
                        .then(repository.settleWallet(reservation, usage, claim.claimableAmount(), quality,
                                claim.deficitAmount(), claim.deficitTokens()))
                        .then(settleOverage(reservation, claim))
                        .thenReturn(new Settlement(reservation.id(), 0, 0,
                                usage.totalTokens(), "SETTLED", quality)))
                .onErrorMap(WalletUsageClient.WalletUsageClientException.class, this::mapWalletError);
    }

    private Mono<Settlement> applySettlement(
            QuotaReservationRepository.ReservationRow reservation,
            TokenUsage usage,
            UsageQuality quality,
            QuotaReservationRepository.LockedPackage lockedPackage,
            QuotaReservationRepository.ModelQuota modelQuota) {
        // 容量取自**这笔预留自己的桶**（方案 C）：一次 ASR 请求的可结算上限是
        // 音频秒那一列的剩余量，视频秒的剩余量与它无关。用一个桶的容量去夹另一个
        // 桶的请求，会把一次已经完全覆盖的转写结算成 0 秒（或反过来超出音频额度）。
        String dimension = reservation.usageDimension();
        long packageCapacity = lockedPackage.totalFor(dimension) - lockedPackage.usedFor(dimension)
                - (lockedPackage.reservedFor(dimension) - reservation.packageReservedTokens());
        long packageCharge = Math.min(usage.totalTokens(), Math.max(0, packageCapacity));
        long modelCharge = packageCharge;
        if (modelQuota != null) {
            long modelCapacity = modelQuota.totalTokens() - modelQuota.usedTokens()
                    - (modelQuota.reservedTokens() - reservation.packageReservedTokens());
            modelCharge = Math.min(usage.totalTokens(), Math.max(0, modelCapacity));
        }
        final long settledModelCharge = modelCharge;
        long uncovered = usage.totalTokens() - packageCharge;
        Settlement result = new Settlement(
                reservation.id(), packageCharge, settledModelCharge, uncovered, "SETTLED", quality);
        return walletSettlementAmount(reservation, usage, packageCharge).flatMap(claim -> {
            Mono<Void> walletSettlement = reservation.walletUsageReservationId() == null
                    ? Mono.empty()
                    : walletClient.settle(
                            reservation.walletUsageReservationId(),
                            new WalletUsageClient.WalletSettlement(
                                    reservation.tenantId(), reservation.userId(),
                                    claim.claimableAmount(), usage.inputTokens(), usage.outputTokens(),
                                    walletBillingMode(packageCharge, claim.claimableAmount())))
                            .then();
            return walletSettlement.then(repository.settle(
                            reservation, usage, quality, lockedPackage, modelQuota,
                            packageCharge, settledModelCharge, uncovered, claim.claimableAmount(),
                            claim.deficitAmount(), claim.deficitTokens()))
                    .then(settleOverage(reservation, claim))
                    .then(activateIfTerminal(lockedPackage.id()))
                    .thenReturn(result);
        }).onErrorMap(WalletUsageClient.WalletUsageClientException.class, this::mapWalletError);
    }

    static String walletBillingMode(long packageSettledTokens, long walletSettledAmount) {
        return packageSettledTokens > 0 && walletSettledAmount > 0 ? "HYBRID" : "WALLET_ONLY";
    }

    private Mono<Void> release(UUID reservationId, ReleaseReason reason, boolean expired) {
        if (reservationId == null || reason == null) {
            return Mono.error(unprocessable("invalid_release_request"));
        }
        Mono<Void> release = repository.findReservation(reservationId)
                .switchIfEmpty(Mono.error(notFound("reservation_not_found")))
                .flatMap(observed -> repository.advisoryLock(observed.userId())
                        .then(Mono.defer(() -> {
                            if (observed.packageInstanceId() == null) {
                                return repository.lockReservation(reservationId)
                                        .switchIfEmpty(Mono.error(notFound("reservation_not_found")))
                                        .flatMap(reservation -> releaseLocked(
                                                reservation, reason, expired, null));
                            }
                            return lockQuota(observed).flatMap(quota -> repository.lockReservation(reservationId)
                                    .switchIfEmpty(Mono.error(notFound("reservation_not_found")))
                                    .flatMap(reservation -> releaseLocked(
                                            reservation, reason, expired, quota)));
                        })));
        return transactions.transactional(release);
    }

    private Mono<Void> releaseLocked(
            QuotaReservationRepository.ReservationRow reservation,
            ReleaseReason reason,
            boolean expired,
            LockedQuota quota) {
        if ("RELEASED".equals(reservation.status()) || "EXPIRED".equals(reservation.status())) {
            return Mono.empty();
        }
        if (!"RESERVED".equals(reservation.status())) {
            return Mono.error(conflict("reservation_not_releasable"));
        }
        Mono<Void> walletRelease = reservation.walletUsageReservationId() == null
                ? Mono.empty() : walletClient.release(reservation.walletUsageReservationId()).then();
        Mono<Void> gatewayRelease = reservation.packageInstanceId() == null
                ? repository.releaseWallet(reservation, reason, expired)
                : repository.release(reservation, quota.lockedPackage(), quota.modelQuota(), reason, expired)
                        .then(activateIfTerminal(quota.lockedPackage().id()));
        return walletRelease.then(gatewayRelease);
    }

    private Mono<Void> activateIfTerminal(UUID packageId) {
        return repository.closeTerminalPackage(packageId)
                .flatMap(terminal -> packageActivationService
                        .activateNext(terminal.userId(), terminal.usageDimension()).then())
                .then();
    }

    private Mono<Settlement> existingSettlement(QuotaReservationRepository.ReservationRow reservation) {
        long actual = Objects.requireNonNullElse(reservation.actualTotalTokens(), 0L);
        UsageQuality quality = reservation.usageQuality() == null
                ? UsageQuality.ESTIMATED
                : UsageQuality.valueOf(reservation.usageQuality());
        if (reservation.packageInstanceId() == null) {
            return Mono.just(new Settlement(
                    reservation.id(), 0, 0, actual, "SETTLED", quality));
        }
        return repository.settledModelCharge(reservation)
                .map(modelCharged -> new Settlement(
                        reservation.id(), reservation.packageSettledTokens(), modelCharged,
                        reservation.uncoveredTokens(),
                        "SETTLED", quality));
    }

    /**
     * 结算可以收取的金额，以及它无法收取的金额。
     *
     * <p>索取额以钱包预留为上限。在此上限存在之前，只要实际金额超过预留，结算就会抛出
     * {@code billing_reconciliation_required} (409)，使该预留永远停留在
     * {@code RESERVED}——钱既未被收取也未被释放，供应商的成本则由平台吸收。预留就是承诺；
     * 结算不得凭空造出一个更大的承诺，但也不得把已经预留的部分弃之不顾。
     *
     * @param claimableAmount 现有钱包预留所覆盖的零售金额
     * @param deficitAmount 预留未覆盖、但该请求已产生的零售金额
     * @param deficitTokens 套餐无法计费的套餐 token，因为其容量在该请求的预留量之下
     *        就已耗尽
     */
    private record SettlementClaim(long claimableAmount, long deficitAmount, long deficitTokens) {

        static final SettlementClaim NONE = new SettlementClaim(0L, 0L, 0L);

        boolean hasDeficit() {
            return deficitAmount > 0 || deficitTokens > 0;
        }
    }

    /**
     * 钱包在实际用量中承担的份额（以预留为上限），再加上缺口。
     *
     * <p>{@code packageCharge} 是套餐实际支付的量，它至多等于为该套餐预留的量。差额是
     * 预留中套餐容量不足的那部分：那些 token 已经提供给了客户并由平台付费，
     * 因此它们应计入缺口，而不是被悄悄丢弃。
     */
    private Mono<SettlementClaim> walletSettlementAmount(
            QuotaReservationRepository.ReservationRow reservation,
            TokenUsage usage,
            long packageCharge) {
        long deficitTokens = Math.max(0, reservation.packageReservedTokens() - packageCharge);
        boolean walletHeld = reservation.walletUsageReservationId() != null
                && reservation.walletReservedAmount() > 0;
        if (!walletHeld && deficitTokens == 0) {
            return Mono.just(SettlementClaim.NONE);
        }
        return priceFor(reservation)
                .map(price -> {
                    long claimable = 0L;
                    long deficit = 0L;
                    if (walletHeld) {
                        long packageInput = Math.min(usage.inputTokens(), reservation.packageReservedTokens());
                        long packageOutput = Math.min(
                                usage.outputTokens(),
                                Math.max(0, reservation.packageReservedTokens() - packageInput));
                        long walletInput = usage.inputTokens() - packageInput;
                        long walletOutput = usage.outputTokens() - packageOutput;
                        long actualAmount = price.amount(
                                walletInput, walletOutput,
                                shareOf(walletInput, usage.inputTokens(), usage.cacheHitOrZero()),
                                shareOf(walletInput, usage.inputTokens(), usage.cacheWriteOrZero()),
                                shareOf(walletOutput, usage.outputTokens(), usage.reasoningOrZero()));
                        claimable = Math.min(actualAmount, reservation.walletReservedAmount());
                        deficit = actualAmount - claimable;
                    }
                    if (deficitTokens > 0) {
                        long unbilledInput = shareOf(deficitTokens, usage.totalTokens(), usage.inputTokens());
                        deficit = Math.addExact(
                                deficit, price.amount(unbilledInput, deficitTokens - unbilledInput));
                    }
                    return new SettlementClaim(claimable, deficit, deficitTokens);
                })
                // 没有价格版本可供定价的未计费套餐余量仍然是一笔缺口；它以 token 记账，
                // 无法折算成金额。绝不能让这一点阻止结算。
                .onErrorResume(error -> !walletHeld && deficitTokens > 0,
                        error -> Mono.just(new SettlementClaim(0L, 0L, deficitTokens)));
    }

    private Mono<ModelBillingPriceCalculator.Price> priceFor(
            QuotaReservationRepository.ReservationRow reservation) {
        if (reservation.billingPriceVersion() == null || reservation.billingPriceVersion().isBlank()) {
            return priceService.current(reservation.modelCode());
        }
        return priceService.byVersion(reservation.modelCode(), reservation.billingPriceVersion());
    }

    /**
     * 超额路径：请求的开销超过了客户预付余额所覆盖的范围。
     *
     * <p>结算已经提交了预留所持有的金额。本方法尝试从客户可用的钱包余额中
     * <em>收取</em>其余部分——先为缺口做一笔预留，随后立即按该金额结算，这是平台钱包
     * API 所暴露的唯一一种扣款方式——并把无法收取的部分记录在预留上，使这笔钱在用量/
     * 成本证据中可见，而不是被无声地吸收。
     *
     * <p>它绝不会让结算失败：本方法运行在与结算相同的事务内，这里的失败不得撤销一笔
     * 已经向客户收费的结算。
     */
    private Mono<Void> settleOverage(
            QuotaReservationRepository.ReservationRow reservation, SettlementClaim claim) {
        if (!claim.hasDeficit()) {
            return Mono.empty();
        }
        LOG.error(
                "gateway_settlement_overage reservation={} user={} model={} amountAtRisk={} uncoveredTokens={}",
                reservation.id(), reservation.userId(), reservation.modelCode(),
                claim.deficitAmount(), claim.deficitTokens());
        Mono<Boolean> collected = claim.deficitAmount() > 0
                ? debitAvailableBalance(reservation, claim.deficitAmount())
                        .thenReturn(true)
                        .onErrorResume(error -> {
                            LOG.error(
                                    "gateway_settlement_deficit reservation={} user={} model={} "
                                            + "amountAtRisk={} uncoveredTokens={} collected=false",
                                    reservation.id(), reservation.userId(), reservation.modelCode(),
                                    claim.deficitAmount(), claim.deficitTokens(), error);
                            return Mono.just(false);
                        })
                : Mono.just(true);
        return collected
                .flatMap(debited -> repository.recordDeficit(
                        reservation.id(), debited ? 0L : claim.deficitAmount(), claim.deficitTokens()))
                .onErrorResume(error -> {
                    LOG.error(
                            "gateway_settlement_deficit_record_failed reservation={} amountAtRisk={}",
                            reservation.id(), claim.deficitAmount(), error);
                    return Mono.empty();
                });
    }

    /**
     * 从客户可用余额中收取预留并未持有的金额。
     *
     * <p>钱包 API 没有裸扣款，因此这次收取就是为恰好等于缺口的金额做一笔预留，
     * 并立即按恰好该金额结算——客户只被收取这笔缺口，别无其他。当余额无法覆盖它时，
     * 会以 {@code wallet_insufficient_balance} 失败，这正是调用方记录下来的
     * 「无法收取」情形。
     */
    private Mono<Void> debitAvailableBalance(
            QuotaReservationRepository.ReservationRow reservation, long amount) {
        UUID overageReservationId = UUID.nameUUIDFromBytes(
                (reservation.id() + ":overage").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return walletClient.reserve(new WalletUsageClient.WalletRequest(
                        overageReservationId, reservation.requestId() + ":overage",
                        reservation.tenantId(), reservation.userId(), reservation.modelCode(),
                        amount, amount, 0L, reservation.billingPriceVersion(),
                        "model-usage-overage:" + reservation.id(),
                        Instant.now().plus(Duration.ofMinutes(10))))
                .then(walletClient.settle(
                        overageReservationId,
                        new WalletUsageClient.WalletSettlement(
                                reservation.tenantId(), reservation.userId(),
                                amount, amount, 0L, "WALLET_ONLY")))
                .then();
    }

    /**
     * 在把请求按比例拆分为套餐覆盖用量与钱包覆盖用量的前提下，
     * 钱包应负责的各类别 token。
     *
     * <p>账本按 token 数量而不是按类别拆分请求，因此类别计数也按同样方式分摊。
     * 向下取整可使钱包份额不会超过它的来源计数。
     */
    private static long shareOf(long walletPart, long totalPart, long categoryTokens) {
        if (totalPart <= 0 || categoryTokens <= 0) {
            return 0L;
        }
        return categoryTokens * walletPart / totalPart;
    }

    /**
     * 客户余额无法覆盖的预留属于支付问题，而不是无效请求：请求格式正确且模型在售，
     * 只是客户已经没有可花的钱了。在调用任何供应商之前，会以 402 拒绝它。
     *
     * <p>只有预留路径这样映射。结算路径保持 {@link #mapWalletError} 不变，因为结算
     * 不再索取超过其持有量的金额，那里的 {@code insufficient_balance} 会意味着别的含义。
     */
    ResponseStatusException mapReserveWalletError(WalletUsageClient.WalletUsageClientException error) {
        if ("wallet_insufficient_balance".equals(error.code())) {
            return new ResponseStatusException(
                    HttpStatus.PAYMENT_REQUIRED, "insufficient_balance", error);
        }
        return mapWalletError(error);
    }

    ResponseStatusException mapWalletError(WalletUsageClient.WalletUsageClientException error) {
        String code = error.code() == null ? "" : error.code();
        return switch (code) {
            case "wallet_insufficient_balance" -> new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "wallet_insufficient_balance", error);
            case "wallet_not_found" -> new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "billing_source_required", error);
            case "wallet_usage_idempotency_mismatch" -> new ResponseStatusException(
                    HttpStatus.CONFLICT, "wallet_usage_idempotency_mismatch", error);
            case "billing_reconciliation_required" -> new ResponseStatusException(
                    HttpStatus.CONFLICT, "billing_reconciliation_required", error);
            case "wallet_usage_not_releasable", "wallet_usage_not_settleable" ->
                    new ResponseStatusException(
                            HttpStatus.CONFLICT, "wallet_usage_state_conflict", error);
            case "invalid_wallet_usage_reservation", "invalid_wallet_usage_settlement" ->
                    new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE, "wallet_billing_request_invalid", error);
            default -> new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "wallet_billing_unavailable", error);
        };
    }

    private Reservation toReservation(QuotaReservationRepository.ReservationRow row) {
        return new Reservation(
                row.id(), row.requestId(), row.packageInstanceId(), row.modelCode(),
                row.reservedTokens(), row.status(), row.expiresAt(), row.usageDimension());
    }

    private void validate(ReserveCommand command) {
        if (command == null || command.reservationId() == null || blank(command.requestId())
                || command.requestId().length() > 128 || command.tenantId() == null
                || command.userId() == null || command.apiKeyId() == null
                || blank(command.modelCode()) || command.modelCode().length() > 128
                || command.tokens() <= 0 || command.expiresAt() == null
                || !command.expiresAt().isAfter(Instant.now())) {
            throw unprocessable("invalid_reservation_request");
        }
    }

    private void validate(UUID reservationId, TokenUsage usage, UsageQuality quality) {
        if (reservationId == null || usage == null || quality == null
                || usage.inputTokens() < 0 || usage.outputTokens() < 0 || usage.totalTokens() < 0) {
            throw unprocessable("invalid_settlement_request");
        }
        long calculatedTotal;
        try {
            calculatedTotal = Math.addExact(usage.inputTokens(), usage.outputTokens());
        } catch (ArithmeticException overflow) {
            throw unprocessable("invalid_settlement_request");
        }
        if (usage.totalTokens() < calculatedTotal) {
            throw unprocessable("invalid_settlement_request");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseStatusException unprocessable(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private ResponseStatusException notFound(String reason) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
    }

    /**
     * 单个请求的准入：它覆盖了什么，以及在套餐和钱包之间如何拆分。
     *
     * <p>{@code tokens} 是针对套餐持有的数量，{@code inputTokens} /
     * {@code outputTokens} 则是它的拆分。{@code outputTokens} 是该请求的有效最大输出——
     * {@code min(client max_tokens, the plan's cap when it sells one, the
     * model cap, ReservationCeiling.SYSTEM_MAX_OUTPUT_TOKENS)}——绝不是对模型实际会产出
     * 多少的估计，因为预留必须覆盖该请求可能产生的开销，而不是它预期会产生的开销。
     *
     * <p>缓存与推理计数器只为留证而携带，不再为预留定价：钱包金额是该请求可能达到的
     * 最坏情况，它把每个 token 都按它可能落到的最贵类别费率定价，因此并不依赖于网关在
     * 供应商应答之前无法得知的拆分。参见
     * {@link ReservationCeiling#worstCaseAmount}。
     *
     * @param cacheHitTokens 估计的缓存命中输入 token，仅供参考
     * @param reasoningTokens 估计的推理输出 token，仅供参考
     * @param cacheWriteTokens 估计的缓存写入输入 token，仅供参考
     */
    public record ReserveCommand(
            UUID reservationId,
            String requestId,
            UUID tenantId,
            UUID userId,
            UUID apiKeyId,
            String modelCode,
            long tokens,
            Instant expiresAt,
            long inputTokens,
            long outputTokens,
            Long cacheHitTokens,
            Long reasoningTokens,
            Long cacheWriteTokens,
            String requiredUsageDimension) {

        public ReserveCommand {
            cacheHitTokens = cacheHitTokens == null ? 0L : cacheHitTokens;
            reasoningTokens = reasoningTokens == null ? 0L : reasoningTokens;
            cacheWriteTokens = cacheWriteTokens == null ? 0L : cacheWriteTokens;
            requiredUsageDimension = requiredUsageDimension == null || requiredUsageDimension.isBlank()
                    ? null : requiredUsageDimension.strip();
        }

        public ReserveCommand(UUID reservationId, String requestId, UUID tenantId, UUID userId,
                UUID apiKeyId, String modelCode, long tokens, Instant expiresAt) {
            this(reservationId, requestId, tenantId, userId, apiKeyId, modelCode, tokens,
                    expiresAt, tokens, 0, null, null, null, null);
        }

        public ReserveCommand(UUID reservationId, String requestId, UUID tenantId, UUID userId,
                UUID apiKeyId, String modelCode, long tokens, Instant expiresAt,
                long inputTokens, long outputTokens) {
            this(reservationId, requestId, tenantId, userId, apiKeyId, modelCode, tokens,
                    expiresAt, inputTokens, outputTokens, null, null, null, null);
        }

        public ReserveCommand(UUID reservationId, String requestId, UUID tenantId, UUID userId,
                UUID apiKeyId, String modelCode, long tokens, Instant expiresAt,
                long inputTokens, long outputTokens, String requiredUsageDimension) {
            this(reservationId, requestId, tenantId, userId, apiKeyId, modelCode, tokens,
                    expiresAt, inputTokens, outputTokens, null, null, null,
                    requiredUsageDimension);
        }

        /** 缓存写入出现之前的形式：五参数的类别拆分。 */
        public ReserveCommand(UUID reservationId, String requestId, UUID tenantId, UUID userId,
                UUID apiKeyId, String modelCode, long tokens, Instant expiresAt,
                long inputTokens, long outputTokens,
                Long cacheHitTokens, Long reasoningTokens) {
            this(reservationId, requestId, tenantId, userId, apiKeyId, modelCode, tokens,
                    expiresAt, inputTokens, outputTokens, cacheHitTokens, reasoningTokens, null, null);
        }
    }

    public record Reservation(
            UUID id,
            String requestId,
            UUID packageInstanceId,
            String modelCode,
            long reservedTokens,
            String status,
            Instant expiresAt,
            String usageDimension) {

        /** 向后兼容的形式：token 预留。 */
        public Reservation(
                UUID id,
                String requestId,
                UUID packageInstanceId,
                String modelCode,
                long reservedTokens,
                String status,
                Instant expiresAt) {
            this(id, requestId, packageInstanceId, modelCode, reservedTokens, status,
                    expiresAt, "TOKEN");
        }
    }

    /**
     * 单个请求中客户可见的用量。
     *
     * <p>类别计数器是可选的，这样所有现有调用方都能继续编译且行为完全一致：
     * 计数缺失表示「没有类别拆分」，此时计费把每个输入都当作未命中、每个输出都当作
     * 普通输出，这正是按类别定价出现之前网关的计费方式。
     */
    public record TokenUsage(
            long inputTokens,
            long outputTokens,
            long totalTokens,
            Long cacheHitTokens,
            Long reasoningTokens,
            Long cacheWriteTokens) {

        public TokenUsage(long inputTokens, long outputTokens, long totalTokens) {
            this(inputTokens, outputTokens, totalTokens, null, null, null);
        }

        /** 缓存写入出现之前的形式：不报告写入拆分的供应商。 */
        public TokenUsage(
                long inputTokens,
                long outputTokens,
                long totalTokens,
                Long cacheHitTokens,
                Long reasoningTokens) {
            this(inputTokens, outputTokens, totalTokens, cacheHitTokens, reasoningTokens, null);
        }

        public TokenUsage {
            if (cacheHitTokens != null && (cacheHitTokens < 0 || cacheHitTokens > inputTokens)) {
                throw new IllegalArgumentException("invalid_settlement_request");
            }
            if (reasoningTokens != null && (reasoningTokens < 0 || reasoningTokens > outputTokens)) {
                throw new IllegalArgumentException("invalid_settlement_request");
            }
            if (cacheWriteTokens != null && (cacheWriteTokens < 0 || cacheWriteTokens > inputTokens)) {
                throw new IllegalArgumentException("invalid_settlement_request");
            }
            // 命中和写入都是从同一个输入计数中划出的，因此两者之和不能超过它。
            // 没有这一检查，这两个类别会被重复计数，同一批 token 会被计费两次。
            if (cacheHitTokens != null && cacheWriteTokens != null
                    && cacheHitTokens + cacheWriteTokens > inputTokens) {
                throw new IllegalArgumentException("invalid_settlement_request");
            }
        }

        long cacheHitOrZero() {
            return cacheHitTokens == null ? 0L : cacheHitTokens;
        }

        long reasoningOrZero() {
            return reasoningTokens == null ? 0L : reasoningTokens;
        }

        long cacheWriteOrZero() {
            return cacheWriteTokens == null ? 0L : cacheWriteTokens;
        }
    }

    public record Settlement(
            UUID reservationId,
            long chargedTokens,
            long modelChargedTokens,
            long uncoveredTokens,
            String status,
            UsageQuality quality) {
    }

    public enum UsageQuality {
        ACTUAL,
        ESTIMATED
    }

    public enum ReleaseReason {
        UPSTREAM_FAILURE,
        CLIENT_CANCELLED,
        RECOVERY_TIMEOUT
    }

    private record LockedQuota(
            QuotaReservationRepository.LockedPackage lockedPackage,
            QuotaReservationRepository.ModelQuota modelQuota) {
    }
}

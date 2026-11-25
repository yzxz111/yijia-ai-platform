package com.yuzhihuihe.apiplatform.gateway.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * 把「已经到期、状态却还停在 {@code ACTIVE}」的套餐实例物化掉，并推进同维度的队列。
 *
 * <p><b>它补的是一个自锁。</b>实例状态此前只由事件翻转：发放激活、结算/释放后的
 * {@link QuotaReservationRepository#closeTerminalPackage}、以及退款。没有任何按时间的
 * 物化，而准入读的是 {@code status = 'ACTIVE' AND expires_at > now()}。于是：
 *
 * <pre>
 * 套餐到 expires_at → status 仍是 ACTIVE → 新调用在准入处被 422 active_package_required
 * 拒绝 → 被拒的调用不产生结算 → 永不触发 closeTerminalActive → 实例永远停在 ACTIVE，
 * 同维度排队实例也永远轮不到
 * </pre>
 *
 * <p>它同时是 {@code /v1/quota} 那个 {@code usable} 字段的兜底：接口在读取时已经把
 * {@code expires_at} 算进结论，所以客户端不会因为这一分钟级延迟看到错的可花费额度，
 * 但控制台、运营报表和队列推进仍然依赖库里的状态如实。
 *
 * <p><b>为什么调 {@code activateNext} 而不是自己写一条 UPDATE。</b>那条路径已经包含了这次
 * 清扫需要的全部语义：按用户加事务级建议锁（与发放事件、结算并发安全）、
 * {@link PackageActivationRepository#closeTerminalActive}（两个桶都用尽才判 EXHAUSTED，
 * 且尊重退款审批中的 {@code refund_hold}）、以及「关掉旧的之后激活该维度最旧的排队实例」。
 * 在这里重写一遍只会多出第二份口径。
 *
 * <p><b>失败语义是逐条隔离的。</b>单条实例失败只记一条 WARN，不中断本轮也不影响其他实例；
 * 它下一轮会被重新选中（状态没变），因此这是一个自愈的重试而不是丢弃。
 *
 * <p><b>多实例部署是安全的。</b>候选集与加锁之间没有 claim，因此两个实例可能选中同一条；
 * 但真正关旧实例与激活排队实例都在 {@code activateNext} 的用户级建议锁里，只有一个能改。
 * 落败的那个不会重复关闭、也不会重复激活，只是它的日志会多一行（它看到的是对方刚激活的
 * 新实例，或退款挡住的同一实例）。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(name = "quota.expiry.enabled", havingValue = "true", matchIfMissing = true)
public final class PackageExpiryMaterializationJob {

    private static final Logger LOG = LoggerFactory.getLogger(PackageExpiryMaterializationJob.class);

    private final PackageActivationRepository repository;
    private final PackageActivationService activationService;
    private final int batchSize;

    public PackageExpiryMaterializationJob(
            PackageActivationRepository repository,
            PackageActivationService activationService,
            @Value("${quota.expiry.batch-size:100}") int batchSize) {
        if (batchSize <= 0 || batchSize > 1000) {
            throw new IllegalArgumentException("invalid_package_expiry_batch_size");
        }
        this.repository = repository;
        this.activationService = activationService;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${quota.expiry.initial-delay:60s}",
            fixedDelayString = "${quota.expiry.fixed-delay:60s}")
    Mono<Void> scheduledMaterialization() {
        return Mono.defer(this::materializeOnce).then();
    }

    /**
     * 跑一轮清扫，返回**成功处理**的实例数 —— 失败的那几条不计入，它们下一轮会被重新选中。
     */
    public Mono<Integer> materializeOnce() {
        return repository.expiredActivePackages(batchSize)
                .concatMap(candidate -> materializeOne(candidate)
                        .thenReturn(true)
                        .onErrorResume(error -> {
                            LOG.warn("package expiry materialization failed user={} dimension={}: {}",
                                    candidate.userId(), candidate.usageDimension(),
                                    error.toString());
                            return Mono.just(false);
                        }))
                .filter(Boolean::booleanValue)
                .count()
                .map(Math::toIntExact);
    }

    private Mono<Void> materializeOne(PackageActivationRepository.ExpiredPackageRow candidate) {
        return activationService.activateNext(candidate.userId(), candidate.usageDimension())
                .doOnNext(result -> logReplaced(candidate, result))
                // 关掉了旧实例但该维度没有排队实例：这是最常见的一种结局（单套餐用户），
                // 必须留一行痕迹，否则"清扫做了什么"在日志里完全看不出来。
                .switchIfEmpty(Mono.fromRunnable(() -> LOG.info(
                        "expired package closed without successor user={} dimension={} closed={}",
                        candidate.userId(), candidate.usageDimension(),
                        candidate.packageInstanceId())))
                .then();
    }

    private static void logReplaced(
            PackageActivationRepository.ExpiredPackageRow candidate,
            PackageActivationService.ActivationResult result) {
        if (result.packageInstanceId().equals(candidate.packageInstanceId())) {
            // 实例仍是原来那一个：它没被关掉（例如退款审批中的 refund_hold 挡住了）。
            // 这不是故障，也不该每分钟刷一行 WARN。
            LOG.debug("expired package left active user={} dimension={} package={}",
                    candidate.userId(), candidate.usageDimension(), candidate.packageInstanceId());
            return;
        }
        LOG.info("expired package replaced user={} dimension={} closed={} activated={}",
                candidate.userId(), candidate.usageDimension(),
                candidate.packageInstanceId(), result.packageInstanceId());
    }
}

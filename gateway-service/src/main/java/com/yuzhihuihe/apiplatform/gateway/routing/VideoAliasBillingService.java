package com.yuzhihuihe.apiplatform.gateway.routing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Set;

import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminRequests;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 让运营人员可以从控制台把某个视频别名变成可售。
 *
 * <p>视频别名要能售卖，必须先存在两行数据，而在此之前只有迁移脚本能写入其中任意一行：
 *
 * <ul>
 *   <li>它售卖的分辨率（{@code gateway.video_alias_tier}）——没有它，
 *       {@code VideoGenerationService} 会按设计用
 *       {@code video_model_resolution_unregistered} 拒绝每一个请求；
 *   <li>它的每秒成本与零售价（{@code gateway.model_usage_price}）——没有它，
 *       平台就无法为授予该别名的套餐定价，发布校验也拿不到成本依据。
 * </ul>
 *
 * <p>这里自行强制执行毛利规则，而不是留给数据库约束，这样运营人员得到的是具名错误
 * 而不是约束冲突，并且零售价永远不会被写到低于产品 30% 的下限。
 * 费率卡行采用取代方式（关闭当前行、插入新的 {@code price_version}）而不是原地更新，
 * 与 V46/V47/V50 保持一致：已经在按旧版本运行的结算必须仍能完成。
 */
@Service
class VideoAliasBillingService {

    /** 产品在「秒」这个维度上的硬下限（V58 把 VIDEO_SECOND 提升到了该值）。 */
    static final BigDecimal MARGIN_FLOOR = new BigDecimal("0.30000");

    /** 每售出一秒 0.10 元人民币，即设计中每单位的绝对贡献下限。 */
    static final int MIN_ABSOLUTE_MARGIN = 10;

    /** Kling 会对失败的生成计费，因此 15% 是费率卡所依据的默认值。 */
    static final BigDecimal DEFAULT_RETRY_RATE = new BigDecimal("0.15000");

    static final Set<String> RESOLUTION_TIERS = Set.of("480P", "720P", "1080P");

    private final VideoAliasBillingRepository repository;
    private final TransactionalOperator transactions;

    VideoAliasBillingService(
            VideoAliasBillingRepository repository, TransactionalOperator transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    Flux<GatewayAdminResponses.VideoAliasBillingView> list() {
        return repository.all();
    }

    Mono<GatewayAdminResponses.VideoAliasBillingView> find(String modelCode) {
        if (blank(modelCode)) {
            return Mono.error(invalid("invalid_model"));
        }
        return repository.one(modelCode.strip());
    }

    Mono<GatewayAdminResponses.VideoAliasBillingView> save(
            String modelCode, GatewayAdminRequests.SaveVideoAliasBillingCommand command) {
        if (blank(modelCode) || command == null) {
            return Mono.error(invalid("invalid_model"));
        }
        String code = modelCode.strip();
        String tier = command.resolutionTier() == null ? "" : command.resolutionTier().strip();
        if (!RESOLUTION_TIERS.contains(tier)) {
            return Mono.error(invalid("invalid_video_resolution_tier"));
        }
        if (command.providerUnitCost() <= 0 || command.unitPrice() <= 0) {
            return Mono.error(invalid("invalid_video_price"));
        }
        BigDecimal retryRate = command.retryRate() == null
                ? DEFAULT_RETRY_RATE
                : command.retryRate();
        if (retryRate.signum() < 0 || retryRate.compareTo(BigDecimal.ONE) >= 0) {
            return Mono.error(invalid("invalid_video_retry_rate"));
        }
        // 零售价是下限而非建议值：低于它，售卖该别名的套餐就会亏钱，
        // 而运营人员只能从数据库约束报错中才发现这一点。
        int floor = requiredUnitPrice(command.providerUnitCost());
        if (command.unitPrice() < floor) {
            return Mono.error(invalid("video_price_below_margin_floor"));
        }
        if (command.unitPrice() - command.providerUnitCost() < MIN_ABSOLUTE_MARGIN) {
            return Mono.error(invalid("video_price_below_absolute_margin"));
        }

        String priceVersion = "console-" + Instant.now().toEpochMilli();
        Mono<GatewayAdminResponses.VideoAliasBillingView> mutation =
                repository
                        .videoAlias(code)
                        .switchIfEmpty(Mono.error(invalid("video_alias_not_found")))
                        .flatMap(ignored -> writeBilling(
                                code, tier, command, retryRate, priceVersion))
                        // 延迟执行：被拒绝的别名连后面那次读取都不应该构建，
                        // 所有协作方调用只在确认别名有效之后才发生。
                        .then(Mono.defer(() -> repository.one(code)));

        return transactions.transactional(mutation);
    }

    private Mono<Void> writeBilling(
            String code,
            String tier,
            GatewayAdminRequests.SaveVideoAliasBillingCommand command,
            BigDecimal retryRate,
            String priceVersion) {
        return repository
                .closeCurrentBilling(code)
                .then(Mono.defer(() -> repository.insertBilling(
                        code,
                        tier,
                        command.providerUnitCost(),
                        command.unitPrice(),
                        retryRate,
                        MARGIN_FLOOR,
                        MIN_ABSOLUTE_MARGIN,
                        priceVersion)))
                .then(Mono.defer(() -> repository.registerTier(code, tier)))
                .then();
    }

    /**
     * 在该成本下越过产品毛利下限所需的最低零售价。
     *
     * <p>以 static 暴露，是为了让控制台展示它将被要求遵守的同一个数值：
     * {@code ceil(cost / (1 - 0.30))}。
     */
    static int requiredUnitPrice(int providerUnitCost) {
        return BigDecimal.valueOf(providerUnitCost)
                .divide(BigDecimal.ONE.subtract(MARGIN_FLOOR), 0, RoundingMode.CEILING)
                .intValueExact();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }
}

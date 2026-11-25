package com.yuzhihuihe.apiplatform.gateway.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Mono;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ModelBillingPriceService {

    private final ModelBillingPriceRepository repository;
    private final TransactionalOperator transactions;

    ModelBillingPriceService(
            ModelBillingPriceRepository repository,
            TransactionalOperator transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    public Mono<ModelBillingPriceCalculator.Price> current(String modelCode) {
        return repository.findCurrent(modelCode)
                .switchIfEmpty(Mono.error(new IllegalStateException("model_price_unavailable")));
    }

    public Mono<ModelBillingPriceCalculator.Price> byVersion(String modelCode, String priceVersion) {
        if (priceVersion == null || priceVersion.isBlank()) {
            return Mono.error(new IllegalStateException("model_price_unavailable"));
        }
        return repository.findByVersion(modelCode, priceVersion)
                .switchIfEmpty(Mono.error(new IllegalStateException("model_price_unavailable")));
    }

    /** 每个模型当前的零售价，便于管理员审计实际收取的费用。 */
    public reactor.core.publisher.Flux<ModelBillingPriceCalculator.Price> listCurrent() {
        return repository.findAllCurrent();
    }

    public Mono<ModelBillingPriceCalculator.Price> publish(PublishCommand command) {
        validate(command);
        Instant effectiveFrom = command.effectiveFrom() == null
                ? Instant.now()
                : command.effectiveFrom();
        ModelBillingPriceRepository.PublishedPrice published =
                new ModelBillingPriceRepository.PublishedPrice(
                        UUID.randomUUID(),
                        command.modelCode().strip(),
                        command.inputPricePerMillion(),
                        command.outputPricePerMillion(),
                        command.providerInputCostPerMillion(),
                        command.providerOutputCostPerMillion(),
                        command.targetMargin(),
                        command.priceVersion().strip(),
                        effectiveFrom,
                        command.publishedBy(),
                        blank(command.officialSource()) ? null : command.officialSource().strip(),
                        command.cacheHitPricePerMillion(),
                        command.cacheWritePricePerMillion(),
                        command.reasoningPricePerMillion(),
                        command.providerCacheHitCostPerMillion());
        Mono<ModelBillingPriceCalculator.Price> publication = repository
                .lockModel(published.modelCode())
                .then(repository.closeCurrent(published.modelCode(), effectiveFrom))
                .then(repository.insert(published))
                .thenReturn(new ModelBillingPriceCalculator.Price(
                        published.modelCode(),
                        published.inputPricePerMillion(),
                        published.outputPricePerMillion(),
                        published.priceVersion(),
                        published.cacheHitPricePerMillion(),
                        published.cacheWritePricePerMillion(),
                        published.reasoningPricePerMillion()));
        return transactions.transactional(publication);
    }

    private void validate(PublishCommand command) {
        if (command == null
                || blank(command.modelCode())
                || command.modelCode().length() > 128
                || blank(command.priceVersion())
                || command.priceVersion().length() > 64
                || command.inputPricePerMillion() <= 0
                || command.outputPricePerMillion() <= 0
                || command.providerInputCostPerMillion() < 0
                || command.providerOutputCostPerMillion() < 0
                || command.providerCacheHitCostPerMillion() < 0
                || (command.officialSource() != null && command.officialSource().length() > 500)) {
            throw new IllegalArgumentException("invalid_model_billing_values");
        }
        ModelBillingPriceCalculator.validateMargin(
                command.inputPricePerMillion(),
                command.outputPricePerMillion(),
                command.providerInputCostPerMillion(),
                command.providerOutputCostPerMillion(),
                command.targetMargin());
        validateCategory(command, "cache_hit_price",
                command.cacheHitPricePerMillion(), command.providerCacheHitCostPerMillion());
        validateCategory(command, "cache_write_price",
                command.cacheWritePricePerMillion(), command.providerInputCostPerMillion());
        validateCategory(command, "reasoning_price",
                command.reasoningPricePerMillion(), command.providerOutputCostPerMillion());
    }

    /**
     * 已配置的分类价格必须为正，且必须覆盖其自身的供应商成本。下限刻意按分类设置：
     * 缓存读取价格视供应商不同，为未命中价格的 1/5 到 1/50，因此按未命中成本给它定价，
     * 要么亏钱，要么让所有有竞争力的缓存价格都无法发布。
     */
    private void validateCategory(
            PublishCommand command, String category, Long price, long providerCost) {
        if (price == null) {
            return;
        }
        if (price <= 0 || price < providerCost) {
            throw new IllegalArgumentException("model_price_margin_invalid:" + category);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * @param cacheHitPricePerMillion 缓存输入 token 的零售价；为 null 时保持迁移前的行为，即按输入价格计费
     * @param cacheWritePricePerMillion 缓存写入 token 的零售价；为 null 时回退到输入价格
     * @param reasoningPricePerMillion 推理 token 的零售价；为 null 时回退到输出价格
     * @param providerCacheHitCostPerMillion 缓存命中价格的供应商成本下限
     */
    public record PublishCommand(            String modelCode,
            long inputPricePerMillion,
            long outputPricePerMillion,
            long providerInputCostPerMillion,
            long providerOutputCostPerMillion,
            BigDecimal targetMargin,
            String priceVersion,
            Instant effectiveFrom,
            UUID publishedBy,
            String officialSource,
            Long cacheHitPricePerMillion,
            Long cacheWritePricePerMillion,
            Long reasoningPricePerMillion,
            long providerCacheHitCostPerMillion) {

        /** 向后兼容的形式：发布时不带按分类定价。 */
        public PublishCommand(
                String modelCode,
                long inputPricePerMillion,
                long outputPricePerMillion,
                long providerInputCostPerMillion,
                long providerOutputCostPerMillion,
                BigDecimal targetMargin,
                String priceVersion,
                Instant effectiveFrom,
                UUID publishedBy,
                String officialSource) {
            this(modelCode, inputPricePerMillion, outputPricePerMillion,
                    providerInputCostPerMillion, providerOutputCostPerMillion, targetMargin,
                    priceVersion, effectiveFrom, publishedBy, officialSource,
                    null, null, null, 0L);
        }
    }

    /**
     * 当前价格的读取视图。
     *
     * <p>可为 null 的分类价格按原样暴露，而不是解析为基础价格，这样管理员就能把“未配置”
     * 与“配置为等于输入价格”区分开 —— 两者在计费时行为完全相同，但对维护价目表的人
     * 而言含义不同。
     */
    public record PriceView(
            String modelCode,
            long inputPricePerMillion,
            long outputPricePerMillion,
            Long cacheHitPricePerMillion,
            Long cacheWritePricePerMillion,
            Long reasoningPricePerMillion,
            String priceVersion) {

        static PriceView of(ModelBillingPriceCalculator.Price price) {
            return new PriceView(
                    price.modelCode(),
                    price.inputPricePerMillion(),
                    price.outputPricePerMillion(),
                    price.cacheHitPricePerMillion(),
                    price.cacheWritePricePerMillion(),
                    price.reasoningPricePerMillion(),
                    price.priceVersion());
        }
    }
}

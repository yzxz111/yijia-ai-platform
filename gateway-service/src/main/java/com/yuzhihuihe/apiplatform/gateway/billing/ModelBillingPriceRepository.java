package com.yuzhihuihe.apiplatform.gateway.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class ModelBillingPriceRepository {

    private final DatabaseClient database;

    ModelBillingPriceRepository(DatabaseClient database) {
        this.database = database;
    }

    Mono<ModelBillingPriceCalculator.Price> findCurrent(String modelCode) {
        return database.sql("""
                        SELECT model_code, input_price_per_million, output_price_per_million,
                               price_version, cache_hit_price_per_million,
                               cache_write_price_per_million, reasoning_price_per_million
                        FROM gateway.model_billing_price
                        WHERE model_code = :model_code AND effective_from <= now()
                          AND (effective_to IS NULL OR effective_to > now())
                        ORDER BY effective_from DESC, id DESC LIMIT 1
                        """)
                .bind("model_code", modelCode)
                .map((row, metadata) -> price(row))
                .one();
    }

    Mono<ModelBillingPriceCalculator.Price> findByVersion(String modelCode, String priceVersion) {
        return database.sql("""
                        SELECT model_code, input_price_per_million, output_price_per_million,
                               price_version, cache_hit_price_per_million,
                               cache_write_price_per_million, reasoning_price_per_million
                        FROM gateway.model_billing_price
                        WHERE model_code = :model_code AND price_version = :price_version
                        LIMIT 1
                        """)
                .bind("model_code", modelCode)
                .bind("price_version", priceVersion)
                .map((row, metadata) -> price(row))
                .one();
    }

    Mono<Void> lockModel(String modelCode) {
        return database.sql("SELECT pg_advisory_xact_lock(hashtextextended(:model_code, 0))")
                .bind("model_code", modelCode)
                .map((row, metadata) -> Boolean.TRUE)
                .one()
                .then();
    }

    Mono<Void> closeCurrent(String modelCode, Instant effectiveTo) {
        return database.sql("""
                        UPDATE gateway.model_billing_price
                        SET effective_to = :effective_to
                        WHERE model_code = :model_code
                          AND effective_to IS NULL
                          AND effective_from < :effective_to
                        """)
                .bind("model_code", modelCode)
                .bind("effective_to", effectiveTo)
                .fetch().rowsUpdated().then();
    }

    Mono<Void> insert(PublishedPrice price) {
        DatabaseClient.GenericExecuteSpec statement = database.sql("""
                        INSERT INTO gateway.model_billing_price(
                            id, model_code, input_price_per_million, output_price_per_million,
                            provider_cost_input_per_million, provider_cost_output_per_million,
                            target_margin_ratio, price_version, effective_from, published_by,
                            official_source, cache_hit_price_per_million,
                            cache_write_price_per_million, reasoning_price_per_million,
                            provider_cache_hit_cost_per_million)
                        VALUES (
                            :id, :model_code, :input_price, :output_price,
                            :provider_input_cost, :provider_output_cost, :target_margin,
                            :price_version, :effective_from, :published_by, :official_source,
                            :cache_hit_price, :cache_write_price, :reasoning_price,
                            :provider_cache_hit_cost)
                        """)
                .bind("id", price.id())
                .bind("model_code", price.modelCode())
                .bind("input_price", price.inputPricePerMillion())
                .bind("output_price", price.outputPricePerMillion())
                .bind("provider_input_cost", price.providerInputCostPerMillion())
                .bind("provider_output_cost", price.providerOutputCostPerMillion())
                .bind("target_margin", price.targetMargin())
                .bind("price_version", price.priceVersion())
                .bind("effective_from", price.effectiveFrom())
                .bind("provider_cache_hit_cost", price.providerCacheHitCostPerMillion());
        statement = bindNullable(statement, "cache_hit_price", price.cacheHitPricePerMillion());
        statement = bindNullable(statement, "cache_write_price", price.cacheWritePricePerMillion());
        statement = bindNullable(statement, "reasoning_price", price.reasoningPricePerMillion());
        statement = price.publishedBy() == null
                ? statement.bindNull("published_by", UUID.class)
                : statement.bind("published_by", price.publishedBy());
        statement = price.officialSource() == null
                ? statement.bindNull("official_source", String.class)
                : statement.bind("official_source", price.officialSource());
        return statement.fetch().rowsUpdated()
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("model_price_insert_failed")))
                .then();
    }

    /**
     * 每个模型当前的零售价，最新的在前。
     *
     * <p>之所以加上它，是因为此前没有办法观察网关实际在收多少费：
     * 这张表由迁移一次性写入种子数据后便只读，因此运维人员无从
     * 看到引导用的 1 分/2 分 行是否仍然生效。
     */
    Flux<ModelBillingPriceCalculator.Price> findAllCurrent() {
        return database.sql("""
                        SELECT model_code, input_price_per_million, output_price_per_million,
                               price_version, cache_hit_price_per_million,
                               cache_write_price_per_million, reasoning_price_per_million
                        FROM gateway.model_billing_price
                        WHERE effective_from <= now()
                          AND (effective_to IS NULL OR effective_to > now())
                        ORDER BY model_code
                        """)
                .map((row, metadata) -> price(row))
                .all();
    }

    private static DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, Long value) {
        return value == null ? spec.bindNull(name, Long.class) : spec.bind(name, value);
    }

    private static ModelBillingPriceCalculator.Price price(io.r2dbc.spi.Row row) {
        return new ModelBillingPriceCalculator.Price(
                row.get("model_code", String.class),
                row.get("input_price_per_million", Long.class),
                row.get("output_price_per_million", Long.class),
                row.get("price_version", String.class),
                row.get("cache_hit_price_per_million", Long.class),
                row.get("cache_write_price_per_million", Long.class),
                row.get("reasoning_price_per_million", Long.class));
    }

    record PublishedPrice(
            UUID id,
            String modelCode,
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

        /** 迁移前的形态：未配置按类别区分的零售价。 */
        PublishedPrice(
                UUID id,
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
            this(id, modelCode, inputPricePerMillion, outputPricePerMillion,
                    providerInputCostPerMillion, providerOutputCostPerMillion, targetMargin,
                    priceVersion, effectiveFrom, publishedBy, officialSource,
                    null, null, null, 0L);
        }
    }
}

package com.yuzhihuihe.apiplatform.gateway.internal;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ProviderPriceQueryService {

    private final ProviderPriceQueryRepository repository;

    public ProviderPriceQueryService(ProviderPriceQueryRepository repository) {
        this.repository = repository;
    }

    Mono<Map<String, BigDecimal>> nonPromotionalOutputPrices(Set<String> modelCodes) {
        return repository.nonPromotionalOutputPrices(modelCodes);
    }

    Mono<Map<String, BigDecimal>> effectiveUnitCosts(Set<String> modelCodes) {
        return repository.effectiveUnitCosts(modelCodes);
    }

    /**
     * @param inputPricePerMillion 每百万输入 token 实际收取的零售价，单位为分
     * @param outputPricePerMillion 每百万输出 token 实际收取的零售价，单位为分
     */
    public record RetailPriceView(long inputPricePerMillion, long outputPricePerMillion) {}

    Mono<Map<String, RetailPriceView>> retailPrices(Set<String> modelCodes) {
        return repository
                .retailPrices(modelCodes)
                .map(
                        prices -> {
                            Map<String, RetailPriceView> views = new java.util.LinkedHashMap<>();
                            prices.forEach(
                                    (modelCode, price) ->
                                            views.put(
                                                    modelCode,
                                                    new RetailPriceView(
                                                            price.inputPricePerMillion(),
                                                            price.outputPricePerMillion())));
                            return views;
                        });
    }

    Flux<ProviderPriceSnapshot> snapshots() {
        return repository.snapshots();
    }
}

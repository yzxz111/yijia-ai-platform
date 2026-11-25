package com.yuzhihuihe.apiplatform.gateway.internal;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/internal/prices")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ProviderPriceController {

    private static final int MAX_MODELS_PER_REQUEST = 100;

    private final ProviderPriceQueryService service;

    public ProviderPriceController(ProviderPriceQueryService service) {
        this.service = service;
    }

    @GetMapping("/non-promotional-output")
    Mono<Map<String, BigDecimal>> nonPromotionalOutputPrices(@RequestParam String models) {
        Set<String> modelCodes = parseModelCodes(models);
        return service.nonPromotionalOutputPrices(modelCodes);
    }

    /**
     * 生成别名（视频秒数 / 图片张数）的有效单位成本，单位为分。
     *
     * <p>与 {@link #nonPromotionalOutputPrices(String)} 分开，是因为单位不同： 那些值是每百万
     * token，而这些值是每个生成单位——{@code VIDEO_SECOND} 别名是每秒， {@code IMAGE} 别名是每张。把二者合并进同一个数值序列会让调用方
     * 悄悄地把每秒成本当作每百万 token 成本，因此这里只暴露生成维度， 并要求调用方知道自己正在定价的别名属于哪个维度（平台的发布守卫正是如此）。
     *
     * <p>两个维度都经由此端点解析：平台把 {@code VIDEO_SECOND} 与 {@code IMAGE} 一视同仁地
     * 送进来。一个别名若同时持有两个维度的当前费率卡行，会被下游查询整体丢弃， 端点便报不出它的成本——那正是 fail-closed 想要的结果，因为两个维度的单位不同，
     * 任选一个都是静默的定价错误。
     */
    @GetMapping("/effective-unit-cost")
    Mono<Map<String, BigDecimal>> effectiveUnitCosts(@RequestParam String models) {
        Set<String> modelCodes = parseModelCodes(models);
        return service.effectiveUnitCosts(modelCodes);
    }

    /**
     * 网关针对每个别名实际会收取的零售价。
     *
     * <p>它存在的目的，是让平台的发布守卫能够核实：它正在批准的价格就是网关将要收取的 价格。成本与零售价位于不同的表、由不同的管理端点维护；没有它，守卫就可能批准一个
     * 零售价目表从未挣到的利润率——正是一组别名被以低于成本的价格售出、而守卫却报告 利润率健康的原因。
     */
    @GetMapping("/retail-price")
    Mono<Map<String, ProviderPriceQueryService.RetailPriceView>> retailPrices(
            @RequestParam String models) {
        Set<String> modelCodes = parseModelCodes(models);
        return service.retailPrices(modelCodes);
    }

    private Set<String> parseModelCodes(String models) {
        if (models == null || models.isBlank()) {
            throw invalidModels();
        }
        Set<String> modelCodes = new LinkedHashSet<>();
        for (String rawCode : models.split(",", -1)) {
            String code = rawCode.trim();
            if (code.isEmpty() || code.length() > 128 || !modelCodes.add(code)) {
                throw invalidModels();
            }
        }
        if (modelCodes.size() > MAX_MODELS_PER_REQUEST) {
            throw invalidModels();
        }
        return modelCodes;
    }

    private ResponseStatusException invalidModels() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_model_codes");
    }
}

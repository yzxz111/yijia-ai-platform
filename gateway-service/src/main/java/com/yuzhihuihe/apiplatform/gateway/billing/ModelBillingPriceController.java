package com.yuzhihuihe.apiplatform.gateway.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 发布并审计按模型计费的零售 token 价格。
 *
 * <p>该端点是模型计费缺失的另一半。在此之前
 * {@link ModelBillingPriceService#publish} 只能从其单元测试中调用——
 * 既没有控制器，也没有定时任务和监听器——因此
 * {@code gateway.model_billing_price} 在生产环境中实际上是只读的，
 * 每个模型一直在按迁移 V21 播种的 {@code 1 分 / 2 分} 行计费，
 * 而对每一个可路由别名来说该价格都低于供应商成本。没有它，任何定价策略
 * 都无法生效。
 *
 * <p>该路径位于控制台网关管理前缀之下，
 * {@code SecurityConfiguration} 已将其限制为 {@code hasRole("ADMIN")}，因此
 * 该控制器只解析 HTTP 并向下委派。
 *
 * <p>一次发布会关闭当前行并插入新行，因此一个已经按旧版本预留的请求
 * 仍按旧版本结算——
 * {@code QuotaService} 在预留时锁定 {@code billing_price_version}。
 */
@RestController
@RequestMapping("/console/gateway/admin/model-prices")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ModelBillingPriceController {

    private final ModelBillingPriceService service;

    ModelBillingPriceController(ModelBillingPriceService service) {
        this.service = service;
    }

    /** 每个模型当前的零售价格，便于管理员审计计费。 */
    @GetMapping
    Flux<ModelBillingPriceService.PriceView> list() {
        return service.listCurrent().map(ModelBillingPriceService.PriceView::of);
    }

    /** 单个模型当前的零售价格。 */
    @GetMapping("/{modelCode}")
    Mono<ModelBillingPriceService.PriceView> current(@PathVariable String modelCode) {
        return service.current(modelCode).map(ModelBillingPriceService.PriceView::of);
    }

    /**
     * 为某个模型发布新的价格版本。上一个版本在同一时刻被关闭，
     * 因此每个模型永远不会有一个以上的当前价格。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Mono<ModelBillingPriceService.PriceView> publish(
            @RequestBody PublishPriceRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.publish(request.toCommand(publishedBy(jwt)))
                .map(ModelBillingPriceService.PriceView::of);
    }

    private UUID publishedBy(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            return null;
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /**
     * 价格以每 1,000,000 tokens 多少分来表示，与列的单位一致。
     *
     * @param cacheHitPricePerMillion 可选；省略时缓存命中仍按输入价格计费，
     *        这正是按类别定价之前的行为
     * @param cacheWritePricePerMillion 可选；省略时回退到输入价格
     * @param reasoningPricePerMillion 可选；省略时回退到输出价格
     * @param providerCacheHitCostPerMillion 缓存命中价格的供应商成本下限
     */
    record PublishPriceRequest(
            String modelCode,
            long inputPricePerMillion,
            long outputPricePerMillion,
            long providerInputCostPerMillion,
            long providerOutputCostPerMillion,
            BigDecimal targetMargin,
            String priceVersion,
            Instant effectiveFrom,
            String officialSource,
            Long cacheHitPricePerMillion,
            Long cacheWritePricePerMillion,
            Long reasoningPricePerMillion,
            Long providerCacheHitCostPerMillion) {

        ModelBillingPriceService.PublishCommand toCommand(UUID publishedBy) {
            return new ModelBillingPriceService.PublishCommand(
                    modelCode,
                    inputPricePerMillion,
                    outputPricePerMillion,
                    providerInputCostPerMillion,
                    providerOutputCostPerMillion,
                    targetMargin,
                    priceVersion,
                    effectiveFrom,
                    publishedBy,
                    officialSource,
                    cacheHitPricePerMillion,
                    cacheWritePricePerMillion,
                    reasoningPricePerMillion,
                    providerCacheHitCostPerMillion == null ? 0L : providerCacheHitCostPerMillion);
        }
    }
}

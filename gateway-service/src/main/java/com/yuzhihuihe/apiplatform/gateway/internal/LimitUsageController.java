package com.yuzhihuihe.apiplatform.gateway.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * 某个套餐实例购买了哪些限额，以及它已经用掉了其中的多少。
 *
 * <p>仅供内部调用方使用：认证由每条 {@code /internal/} 路由都会经过的 HMAC 过滤器
 * （{@code InternalHmacWebFilter}）负责，因此本控制器自身不含任何认证代码——
 * 与价格和退款控制器完全一样。
 *
 * <p>契约：{@code GET /internal/limit-usage?packageInstanceId=<uuid>} 返回下面这个 JSON：
 * {@code {"packageInstanceId":...,"usageDimension":...,"perMinute":{"limit":...,"used":...},
 * "daily":{"limit":...,"used":...},"blockedLast24h":...,"blockedLast24HoursByReason":{...}}}。
 * 其中 {@code null} 限额表示该套餐方案不售卖该维度的速率。未知的套餐实例返回
 * {@code 404 package_instance_not_found}；id 缺失或格式错误时返回
 * {@code 400 invalid_package_instance_id}。
 */
@RestController
@RequestMapping("/internal/limit-usage")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class LimitUsageController {

    private final LimitUsageService service;

    LimitUsageController(LimitUsageService service) {
        this.service = service;
    }

    @GetMapping
    Mono<LimitUsageService.LimitUsageView> limitUsage(
            @RequestParam(name = "packageInstanceId", required = false) String packageInstanceId) {
        return service.usage(packageInstanceId);
    }
}

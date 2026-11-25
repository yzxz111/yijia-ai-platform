package com.yuzhihuihe.apiplatform.gateway.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * 某个时间窗内结算无法收回的金额——即「平台承担了多少」这个数字。
 *
 * <p>仅限内部调用方：认证由每个 {@code /internal/} 路由都会经过的 HMAC 过滤器
 * （{@code InternalHmacWebFilter}）完成。仅管理员可访问由调用此接口的平台侧强制执行；
 * 网关服务于内部调用方，并不猜测它们的角色。
 *
 * <p>契约：{@code GET /internal/settlement-deficit?from=<iso>&to=<iso>} 返回
 * {@code {"totalAmount":...,"totalTokens":...,"count":...,"truncated":...,"items":[{"reservationId":
 * ...,"amount":...,"tokens":...,"occurredAt":...,"planCode":...,"userId":...}]}}，金额以分计。
 * 省略 {@code from} 和 {@code to} 时报告最近 30 天；{@code items} 最多保存最近 200 条。
 */
@RestController
@RequestMapping("/internal/settlement-deficit")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SettlementDeficitController {

    private final SettlementDeficitService service;

    SettlementDeficitController(SettlementDeficitService service) {
        this.service = service;
    }

    @GetMapping
    Mono<SettlementDeficitService.SettlementDeficitView> settlementDeficit(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {
        return service.deficit(from, to);
    }
}

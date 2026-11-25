package com.yuzhihuihe.apiplatform.platform.limitusage;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「我的套餐现在离它的限额有多近？」，面向已认证的调用方。
 *
 * <p>{@code /console/platform/packages} 是调用方自有的套餐接口面，因此租户与
 * 用户始终来自已验证的 token，路径也只能指向调用方自己的某个
 * 实例；他人的或未知的 id 一律是 404，绝不触达网关。租户声明
 * 的读取方式与 {@code OrderController} 相同，而查询本身位于服务层，
 * 因此此类不可能意外地用自己的数据库读取来作答。
 */
@RestController
@RequestMapping("/console/platform/packages")
public class PackageLimitUsageController {

    private final PackageLimitUsageService limitUsageService;

    public PackageLimitUsageController(PackageLimitUsageService limitUsageService) {
        this.limitUsageService = limitUsageService;
    }

    @GetMapping("/{packageInstanceId}/limit-usage")
    GatewayLimitUsageClient.LimitUsage limitUsage(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID packageInstanceId) {
        return limitUsageService.limitUsage(
                UUID.fromString(jwt.getClaimAsString("tenant_id")),
                UUID.fromString(jwt.getSubject()),
                packageInstanceId);
    }
}

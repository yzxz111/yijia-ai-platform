package com.yuzhihuihe.apiplatform.platform.limitusage;

import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.internalapi.GatewayInternalUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 决定调用方自己的套餐界面可以看到什么，以及以何种顺序看到。
 *
 * <p>先确定归属，依据是平台自身的受限读取：属于其它租户或用户的实例
 * 完全不产生网关调用，并以 404 上报，与一个从未存在过的实例完全一样。
 * 只有在此之后才会询问网关，且网关处的任何失败都变成 503
 * （{@code gateway_limit_usage_unavailable}）而不是一个带计数器的响应体——
 * 调用方无法分辨真实的零与编造出来的零。
 */
@Service
public class PackageLimitUsageService {

    private final PackageInstanceOwnershipRepository packageInstances;
    private final GatewayLimitUsageClient limitUsageClient;

    public PackageLimitUsageService(
            PackageInstanceOwnershipRepository packageInstances,
            GatewayLimitUsageClient limitUsageClient) {
        this.packageInstances = packageInstances;
        this.limitUsageClient = limitUsageClient;
    }

    public GatewayLimitUsageClient.LimitUsage limitUsage(
            UUID tenantId, UUID userId, UUID packageInstanceId) {
        if (!packageInstances.isOwnedByCaller(packageInstanceId, tenantId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "package_instance_not_found");
        }
        try {
            return limitUsageClient.fetch(packageInstanceId);
        } catch (GatewayInternalUnavailableException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "gateway_limit_usage_unavailable", unavailable);
        }
    }
}

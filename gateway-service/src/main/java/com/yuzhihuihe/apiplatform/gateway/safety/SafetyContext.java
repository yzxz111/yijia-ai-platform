package com.yuzhihuihe.apiplatform.gateway.safety;

import java.util.UUID;

public record SafetyContext(UUID requestId, UUID tenantId, long policyVersion) {
}

package com.yuzhihuihe.apiplatform.gateway.safety;

import java.util.UUID;

public record SafetyEventPayload(
        String businessId, String eventType, String requestId, UUID tenantId, UUID userId,
        String direction, String source, String category, String severity, String action,
        long policyVersion, String evidenceSha256, String redactedExcerpt,
        String providerCode, String modelAlias, String provider, String channelId) {
}

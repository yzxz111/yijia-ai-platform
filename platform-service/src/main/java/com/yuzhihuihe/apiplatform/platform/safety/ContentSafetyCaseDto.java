package com.yuzhihuihe.apiplatform.platform.safety;

import java.time.Instant;
import java.util.UUID;

public record ContentSafetyCaseDto(
        String caseNo, UUID tenantId, UUID userId, String requestId,
        String direction, String category, String severity, String action, String status,
        long policyVersion, String evidenceSha256, String redactedExcerpt,
        Instant createdAt, Instant updatedAt, Instant reviewedAt, String reviewNote,
        String resolutionAction) {
}

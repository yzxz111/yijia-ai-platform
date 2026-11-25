package com.yuzhihuihe.apiplatform.platform.safety;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ContentSafetyCaseService {

    private static final HexFormat HEX = HexFormat.of();
    private final ContentSafetyCaseRepository repository;
    private final ObjectMapper objectMapper;

    ContentSafetyCaseService(
            ContentSafetyCaseRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ContentSafetyCaseDto ingest(SafetyEventPayload event) {
        String payloadJson = json(event);
        String payloadHash = sha256(payloadJson);
        ContentSafetyCaseDto existing = replayIfPresent(event.businessId(), payloadHash);
        if (existing != null) {
            return existing;
        }
        repository.ensurePolicyVersion(event.policyVersion());
        int inserted = repository.insertInbox(
                UUID.randomUUID(), eventRow(event), payloadHash, payloadJson);
        if (inserted == 0) {
            return replayRequired(event.businessId(), payloadHash);
        }
        String caseNo = "CS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        repository.insertCase(
                UUID.randomUUID(), caseNo, eventRow(event),
                hashOrZero(event.evidenceSha256()), redact(event.redactedExcerpt()),
                sha256(event.businessId()));
        return findByCaseNo(caseNo);
    }

    private ContentSafetyCaseDto replayIfPresent(String businessId, String payloadHash) {
        String storedHash = repository.findInboxPayloadHash(businessId);
        return storedHash == null ? null : replayRequired(businessId, payloadHash, storedHash);
    }

    private ContentSafetyCaseDto replayRequired(String businessId, String payloadHash) {
        String storedHash = repository.findInboxPayloadHash(businessId);
        if (storedHash == null) {
            throw new IllegalStateException("content_safety_event_receipt_missing");
        }
        return replayRequired(businessId, payloadHash, storedHash);
    }

    private ContentSafetyCaseDto replayRequired(
            String businessId, String payloadHash, String storedHash) {
        if (!payloadHash.equals(storedHash)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "content_safety_event_payload_mismatch");
        }
        ContentSafetyCaseDto existing = findByBusinessId(businessId);
        if (existing == null) {
            throw new IllegalStateException("content_safety_case_missing");
        }
        return existing;
    }

    @Transactional
    public ContentSafetyCaseDto transition(
            String caseNo, String targetStatus, UUID actorUserId, String note) {
        ContentSafetyCaseDto current = findByCaseNo(caseNo);
        if (current == null || !allowed(current.status(), targetStatus)) {
            throw new IllegalArgumentException("invalid_content_safety_case_transition");
        }
        String resolutionAction = "DISMISSED".equals(targetStatus) ? "FALSE_POSITIVE" : null;
        repository.transition(
                caseNo, targetStatus, actorUserId, redact(note), resolutionAction);
        return findByCaseNo(caseNo);
    }

    @Transactional
    public ContentSafetyCaseDto resolve(
            String caseNo, String resolutionAction, UUID actorUserId, String note) {
        ContentSafetyCaseDto current = findByCaseNo(caseNo);
        if (current == null || !"CONFIRMED".equals(current.status())) {
            throw new IllegalArgumentException("invalid_content_safety_case_resolution");
        }
        if (!Set.of("ACCOUNT_BANNED", "RECORDED").contains(resolutionAction)) {
            throw new IllegalArgumentException("invalid_content_safety_resolution_action");
        }
        int updated = repository.resolve(
                caseNo, resolutionAction, actorUserId, redact(note));
        if (updated != 1) {
            throw new IllegalArgumentException("invalid_content_safety_case_resolution");
        }
        return findByCaseNo(caseNo);
    }

    public ContentSafetyCaseDto findByBusinessId(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return null;
        }
        String caseNo = repository.findCaseNoByFingerprint(sha256(businessId));
        return caseNo == null ? null : findByCaseNo(caseNo);
    }

    public ContentSafetyCaseDto findByCaseNo(String caseNo) {
        if (caseNo == null) {
            return null;
        }
        return caseDto(repository.findByCaseNo(caseNo));
    }

    private boolean allowed(String current, String target) {
        return ("OPEN".equals(current) && "INVESTIGATING".equals(target))
                || ("INVESTIGATING".equals(current)
                && Set.of("CONFIRMED", "DISMISSED").contains(target));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("content_safety_payload_invalid", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("content_safety_hash_failed", exception);
        }
    }

    private String hashOrZero(String value) {
        return value != null && value.matches("[0-9a-f]{64}") ? value : "0".repeat(64);
    }

    private String redact(String value) {
        return value == null ? null : value.replaceAll("[\\p{Cntrl}]", "")
                .substring(0, Math.min(value.length(), 1000));
    }

    private ContentSafetyCaseRepository.SafetyEventRow eventRow(SafetyEventPayload event) {
        return new ContentSafetyCaseRepository.SafetyEventRow(
                event.businessId(), event.eventType(), event.requestId(),
                event.tenantId(), event.userId(), event.direction(), event.source(),
                event.category(), event.severity(), event.action(), event.policyVersion(),
                event.providerCode(), event.modelAlias(), event.provider(), event.channelId());
    }

    private ContentSafetyCaseDto caseDto(ContentSafetyCaseRepository.CaseRow row) {
        return row == null ? null : new ContentSafetyCaseDto(
                row.caseNo(), row.tenantId(), row.userId(), row.requestId(), row.direction(),
                row.category(), row.severity(), row.action(), row.status(), row.policyVersion(),
                row.evidenceSha256(), row.redactedExcerpt(), row.createdAt(), row.updatedAt(),
                row.reviewedAt(), row.reviewNote(), row.resolutionAction());
    }

    public record SafetyEventPayload(
            String businessId, String eventType, String requestId, UUID tenantId, UUID userId,
            String direction, String source, String category, String severity, String action,
            long policyVersion, String evidenceSha256, String redactedExcerpt,
            String providerCode, String modelAlias, String provider, String channelId) {
    }
}

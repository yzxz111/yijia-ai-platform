package com.yuzhihuihe.apiplatform.platform.safety;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.yuzhihuihe.apiplatform.platform.notification.NotificationEventService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ContentSafetyAdminService {

    private static final Pattern APPEAL_CASE_NO = Pattern.compile("^CS-[0-9a-fA-F]{20}$");

    private final ContentSafetyAdminRepository repository;
    private final ContentSafetyCaseService cases;
    private final NotificationEventService notificationEvents;

    ContentSafetyAdminService(
            ContentSafetyAdminRepository repository,
            ContentSafetyCaseService cases,
            NotificationEventService notificationEvents) {
        this.repository = repository;
        this.cases = cases;
        this.notificationEvents = notificationEvents;
    }

    public List<CaseSummary> listCases(String status, int limit) {
        return listCases(status, "", 0, Math.min(Math.max(limit, 1), 100)).items();
    }

    public PageResult<CaseSummary> listCases(
            String status, String query, int page, int size) {
        Page bounds = page(page, size);
        String normalizedStatus = status == null || status.isBlank()
                ? null : status.trim().toUpperCase(Locale.ROOT);
        ContentSafetyAdminRepository.PageRows<ContentSafetyAdminRepository.CaseRow> result =
                repository.listCases(
                        normalizedStatus, search(query), bounds.size(), bounds.offset());
        List<CaseSummary> items = result.items().stream().map(row -> new CaseSummary(
                row.caseNo(), row.tenantId(), row.userId(), row.requestId(), row.direction(),
                row.category(), row.severity(), row.action(), row.status(), row.policyVersion(),
                row.redactedExcerpt(), row.createdAt(), row.reviewedAt(), row.reviewNote(),
                row.resolutionAction(), row.enforcementState(), row.enforcementReasonCode()))
                .toList();
        return new PageResult<>(List.copyOf(items), result.total(),
                bounds.number(), bounds.size());
    }

    @Transactional
    public ContentSafetyCaseDto transition(String caseNo, String target, UUID actor, String note) {
        return cases.transition(caseNo, target, actor, note);
    }

    @Transactional
    public ContentSafetyCaseDto resolve(String caseNo, String action, UUID actor, String note) {
        ContentSafetyCaseDto current = cases.findByCaseNo(caseNo);
        if (current == null || !"CONFIRMED".equals(current.status())) {
            throw new IllegalArgumentException("invalid_content_safety_case_resolution");
        }
        String normalizedAction = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if ("ACCOUNT_BANNED".equals(normalizedAction)) {
            enforce(current.tenantId(), current.userId(), "BANNED",
                    "content_safety_violation", caseNo, actor);
        }
        return cases.resolve(caseNo, normalizedAction, actor, note);
    }

    @Transactional
    public EnforcementView enforce(UUID tenantId, UUID userId, String state, String reason,
            String caseNo, UUID actor) {
        if (!"FROZEN".equals(state) && !"BANNED".equals(state)) {
            throw new IllegalArgumentException("invalid_enforcement_state");
        }
        lockEnforcementOwner(tenantId, userId);
        ContentSafetyAdminRepository.ResolvedCaseRow resolvedCase =
                resolveCase(tenantId, userId, caseNo);
        ContentSafetyAdminRepository.CurrentEnforcementRow current =
                repository.lockCurrentEnforcement(tenantId, userId);
        if (current != null && state.equals(current.state())) {
            return enforcement(tenantId, userId);
        }

        String reasonCode = safeReasonCode(reason);
        long version = current == null ? 1 : current.version() + 1;
        if (current != null && active(current.state())) {
            repository.updateEnforcement(
                    current.id(), state, reasonCode, resolvedCase.id(), actor, version);
        } else {
            repository.insertEnforcement(
                    UUID.randomUUID(), tenantId, userId, state,
                    reasonCode, resolvedCase.id(), actor, version);
        }
        recordEnforcementEvent(
                tenantId, userId, version, state, reasonCode, resolvedCase.caseNo());
        repository.syncGatewayCache(
                UUID.randomUUID(), tenantId, userId, state,
                reasonCode, resolvedCase.caseNo(), version);
        return enforcement(tenantId, userId);
    }

    @Transactional
    public EnforcementView clear(UUID tenantId, UUID userId, UUID actor, String reason) {
        lockEnforcementOwner(tenantId, userId);
        ContentSafetyAdminRepository.CurrentEnforcementRow current =
                repository.lockCurrentEnforcement(tenantId, userId);
        if (current == null || !active(current.state())) {
            return enforcement(tenantId, userId);
        }
        long version = current.version() + 1;
        String reasonCode = safeReasonCode(reason);
        repository.clearEnforcement(current.id(), reasonCode, actor, version);
        recordEnforcementEvent(
                tenantId, userId, version, "CLEARED", reasonCode, current.caseNo());
        repository.syncGatewayCache(
                UUID.randomUUID(), tenantId, userId, "CLEARED", reasonCode, null, version);
        return enforcement(tenantId, userId);
    }

    public EnforcementView enforcement(UUID tenantId, UUID userId) {
        ContentSafetyAdminRepository.EnforcementRow row =
                repository.enforcement(tenantId, userId);
        return row == null ? null : new EnforcementView(
                tenantId, userId, row.state(), row.reasonCode(), row.caseId(), row.version());
    }

    public List<AppealView> appeals(UUID tenantId, UUID userId) {
        return appeals(tenantId, userId, "", 0, 100).items();
    }

    public PageResult<AppealView> appeals(
            UUID tenantId, UUID userId, String query, int page, int size) {
        Page bounds = page(page, size);
        ContentSafetyAdminRepository.PageRows<ContentSafetyAdminRepository.AppealRow> result =
                repository.appeals(
                        tenantId, userId, search(query), bounds.size(), bounds.offset());
        List<AppealView> items = result.items().stream().map(this::appeal).toList();
        return new PageResult<>(List.copyOf(items), result.total(),
                bounds.number(), bounds.size());
    }

    public List<UserCaseView> userCases(UUID tenantId, UUID userId) {
        return userCases(tenantId, userId, 0, 100).items();
    }

    public PageResult<UserCaseView> userCases(
            UUID tenantId, UUID userId, int page, int size) {
        Page bounds = page(page, size);
        ContentSafetyAdminRepository.PageRows<ContentSafetyAdminRepository.UserCaseRow> result =
                repository.userCases(tenantId, userId, bounds.size(), bounds.offset());
        List<UserCaseView> items = result.items().stream().map(row -> new UserCaseView(
                row.caseNo(), row.category(), row.severity(), row.action(), row.status(),
                row.redactedExcerpt(), row.createdAt())).toList();
        return new PageResult<>(List.copyOf(items), result.total(),
                bounds.number(), bounds.size());
    }

    @Transactional
    public AppealView createAppeal(UUID tenantId, UUID userId, String caseNo, String reason) {
        String normalizedCaseNo = normalizeCaseNo(caseNo);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("invalid_appeal_reason");
        }
        UUID caseId = repository.findCaseId(tenantId, userId, normalizedCaseNo);
        if (caseId == null) {
            throw new IllegalArgumentException("content_safety_case_not_found");
        }
        String businessNo = "AP-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        repository.insertAppeal(
                UUID.randomUUID(), caseId, tenantId, userId,
                businessNo, safeReason(reason));
        return appeal(repository.appealByBusinessNo(businessNo));
    }

    @Transactional
    public AppealView withdrawAppeal(UUID tenantId, UUID userId, String businessNo) {
        validateAppealBusinessNo(businessNo);
        ContentSafetyAdminRepository.AppealRow current =
                repository.lockUserAppeal(tenantId, userId, businessNo);
        requireWithdrawable(current);
        repository.withdrawUserAppeal(tenantId, userId, businessNo);
        return appeal(repository.appealByBusinessNo(businessNo));
    }

    @Transactional
    public AppealView processAppeal(String businessNo, String status, UUID actor, String note) {
        if (!"APPROVED".equals(status) && !"REJECTED".equals(status)) {
            throw new IllegalArgumentException("invalid_appeal_status");
        }
        return appeal(repository.processAppeal(
                businessNo, status, actor, safeReason(note)));
    }

    @Transactional
    public AppealView withdrawAppealAsAdmin(String businessNo, UUID actor) {
        validateAppealBusinessNo(businessNo);
        ContentSafetyAdminRepository.AppealRow current = repository.lockAppeal(businessNo);
        requireWithdrawable(current);
        repository.withdrawAppealAsAdmin(businessNo, actor);
        return appeal(repository.appealByBusinessNo(businessNo));
    }

    private void lockEnforcementOwner(UUID tenantId, UUID userId) {
        if (!repository.lockEnforcementOwner(tenantId, userId)) {
            throw new IllegalArgumentException("account_not_found");
        }
    }

    private ContentSafetyAdminRepository.ResolvedCaseRow resolveCase(
            UUID tenantId, UUID userId, String caseNo) {
        if (caseNo == null) {
            return new ContentSafetyAdminRepository.ResolvedCaseRow(null, null);
        }
        ContentSafetyAdminRepository.ResolvedCaseRow resolved =
                repository.resolveCase(tenantId, userId, caseNo);
        if (resolved == null) {
            throw new IllegalArgumentException("content_safety_case_not_found");
        }
        return resolved;
    }

    private void recordEnforcementEvent(
            UUID tenantId, UUID userId, long version, String state,
            String reasonCode, String caseNo) {
        String eventType = switch (state) {
            case "FROZEN" -> "ACCOUNT_FROZEN";
            case "BANNED" -> "ACCOUNT_BANNED";
            case "CLEARED" -> "ACCOUNT_UNFROZEN";
            default -> throw new IllegalArgumentException("invalid_enforcement_state");
        };
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reasonCode", reasonCode);
        payload.put("caseNo", caseNo);
        notificationEvents.recordAuthoritativeBusinessEvent(
                "account-enforcement:%s:%s:%d:%s".formatted(
                        tenantId, userId, version, state),
                eventType, tenantId, userId, "ACCOUNT_ENFORCEMENT", userId.toString(),
                payload, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private String normalizeCaseNo(String caseNo) {
        if (caseNo == null || !APPEAL_CASE_NO.matcher(caseNo.trim()).matches()) {
            throw new IllegalArgumentException("invalid_appeal_case_no");
        }
        String normalized = caseNo.trim();
        return "CS-" + normalized.substring(3).toLowerCase(Locale.ROOT);
    }

    private void validateAppealBusinessNo(String businessNo) {
        if (businessNo == null || !businessNo.matches("AP-[0-9a-fA-F]{20}")) {
            throw new IllegalArgumentException("invalid_appeal_business_no");
        }
    }

    private void requireWithdrawable(ContentSafetyAdminRepository.AppealRow current) {
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "appeal_not_found");
        }
        if (!"PENDING".equals(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "appeal_not_withdrawable");
        }
    }

    private AppealView appeal(ContentSafetyAdminRepository.AppealRow row) {
        return row == null ? null : new AppealView(
                row.businessNo(), row.caseId(), row.userId(), row.status(),
                row.reason(), row.processingNote());
    }

    private static String search(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("invalid_safety_query");
        }
        return "%" + normalized.replace("!", "!!")
                .replace("%", "!%").replace("_", "!_") + "%";
    }

    private static Page page(int number, int size) {
        if (number < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_safety_page");
        }
        long offset = (long) number * size;
        if (offset > Integer.MAX_VALUE) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_safety_page");
        }
        return new Page(number, size, (int) offset);
    }

    private String safeReason(String value) {
        if (value == null || value.isBlank()) {
            return "unspecified";
        }
        String cleaned = value.replaceAll("[\\p{Cntrl}]", "");
        return cleaned.substring(0, Math.min(cleaned.length(), 2000));
    }

    private String safeReasonCode(String value) {
        if (value == null) {
            return "unspecified";
        }
        String normalized = value.trim();
        return normalized.matches("[A-Za-z0-9._:-]{1,128}")
                ? normalized : "unspecified";
    }

    private boolean active(String state) {
        return "FROZEN".equals(state) || "BANNED".equals(state);
    }

    public record CaseSummary(
            String caseNo, UUID tenantId, UUID userId, String requestId,
            String direction, String category, String severity, String action, String status,
            long policyVersion, String redactedExcerpt, java.time.Instant createdAt,
            java.time.Instant reviewedAt, String reviewNote, String resolutionAction,
            String enforcementState, String enforcementReasonCode) {
    }

    public record EnforcementView(
            UUID tenantId, UUID userId, String state,
            String reasonCode, UUID caseId, long version) {
    }

    public record AppealView(
            String businessNo, UUID caseId, UUID userId,
            String status, String reason, String processingNote) {
    }

    public record UserCaseView(
            String caseNo, String category, String severity, String action,
            String status, String redactedExcerpt, java.time.Instant createdAt) {
    }

    public record PageResult<T>(List<T> items, long total, int page, int size) {
    }

    private record Page(int number, int size, int offset) {
    }
}

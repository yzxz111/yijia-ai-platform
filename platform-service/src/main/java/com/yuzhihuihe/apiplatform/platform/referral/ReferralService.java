package com.yuzhihuihe.apiplatform.platform.referral;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.referral.ReferralRepository.CodeRow;
import com.yuzhihuihe.apiplatform.platform.referral.ReferralRepository.Cursor;
import com.yuzhihuihe.apiplatform.platform.referral.ReferralRepository.InviteeRow;
import com.yuzhihuihe.apiplatform.platform.referral.ReferralRepository.Owner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReferralService {

    private final ReferralRepository repository;
    private final ReferralCodeGenerator codeGenerator;

    ReferralService(ReferralRepository repository, ReferralCodeGenerator codeGenerator) {
        this.repository = repository;
        this.codeGenerator = codeGenerator;
    }

    /** Called before claiming the pending registration challenge, in the account transaction. */
    public AttributionTarget resolveOptional(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return null;
        }
        String code = rawCode.strip();
        if (!codeGenerator.valid(code)) {
            throw ReferralApiException.invalidCode();
        }
        Owner owner = repository.ownerByCode(code)
                .orElseThrow(ReferralApiException::invalidCode);
        if (!repository.lockActiveInviterForAttribution(owner.tenantId(), owner.userId())) {
            throw ReferralApiException.invalidCode();
        }
        CodeRow row = repository.lockByCode(code)
                .orElseThrow(ReferralApiException::invalidCode);
        if (row.revokedAt() != null) {
            throw ReferralApiException.revokedCode();
        }
        return new AttributionTarget(row.id(), row.inviterTenantId(), row.inviterUserId());
    }

    /** Writes only after app_user and its password hash have been inserted in the same transaction. */
    public void record(AttributionTarget target, UUID inviteeUserId) {
        if (target != null) {
            repository.insertAttribution(inviteeUserId, target.inviterTenantId(),
                    target.inviterUserId(), target.codeId());
        }
    }

    public boolean hasAttribution(UUID inviteeUserId) {
        return repository.hasAttribution(inviteeUserId);
    }

    @Transactional(readOnly = true)
    public Overview overview(UUID tenantId, UUID userId) {
        String activeCode = repository.current(tenantId, userId)
                .map(CodeRow::code)
                .orElse(null);
        return new Overview(activeCode, repository.successCount(tenantId, userId));
    }

    @Transactional
    public Overview ensureCode(UUID tenantId, UUID userId) {
        lockOwner(tenantId, userId);
        if (repository.current(tenantId, userId).isEmpty()) {
            insertCode(tenantId, userId);
        }
        return overview(tenantId, userId);
    }

    @Transactional
    public Overview rotateCode(UUID tenantId, UUID userId) {
        lockOwner(tenantId, userId);
        repository.revokeCurrent(tenantId, userId);
        insertCode(tenantId, userId);
        return overview(tenantId, userId);
    }

    @Transactional(readOnly = true)
    public InviteePage invitees(UUID tenantId, UUID userId, int limit, String rawCursor) {
        if (limit < 1 || limit > 100) {
            throw ReferralApiException.invalidLimit();
        }
        Cursor cursor = decodeCursor(rawCursor, tenantId, userId);
        List<InviteeRow> rows = repository.invitees(tenantId, userId, limit + 1, cursor);
        boolean hasMore = rows.size() > limit;
        List<Invitee> items = new ArrayList<>(Math.min(rows.size(), limit));
        for (int i = 0; i < Math.min(rows.size(), limit); i++) {
            InviteeRow row = rows.get(i);
            items.add(new Invitee(row.displayName(), row.registeredAt()));
        }
        InviteeRow last = hasMore ? rows.get(limit - 1) : null;
        return new InviteePage(List.copyOf(items), last == null ? null
                : encodeCursor(tenantId, userId, last));
    }

    private void insertCode(UUID tenantId, UUID userId) {
        repository.insertCode(UUID.randomUUID(), tenantId, userId,
                codeGenerator.generate());
    }

    private void lockOwner(UUID tenantId, UUID userId) {
        if (!repository.lockActiveOwner(tenantId, userId)) {
            throw ReferralApiException.ownerUnavailable();
        }
    }

    private String encodeCursor(UUID tenantId, UUID userId, InviteeRow row) {
        String value = tenantId + "|" + userId + "|" + row.registeredAt()
                + "|" + row.inviteeUserId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String raw, UUID tenantId, UUID userId) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.length() > 512) {
            throw ReferralApiException.invalidCursor();
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(raw),
                    StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length != 4 || !tenantId.equals(UUID.fromString(parts[0]))
                    || !userId.equals(UUID.fromString(parts[1]))) {
                throw ReferralApiException.invalidCursor();
            }
            return new Cursor(Instant.parse(parts[2]), UUID.fromString(parts[3]));
        } catch (RuntimeException error) {
            throw ReferralApiException.invalidCursor();
        }
    }

    public record AttributionTarget(UUID codeId, UUID inviterTenantId, UUID inviterUserId) { }

    public record Overview(String activeCode, long successCount) { }

    public record Invitee(String displayName, Instant registeredAt) { }

    public record InviteePage(List<Invitee> items, String nextCursor) { }
}

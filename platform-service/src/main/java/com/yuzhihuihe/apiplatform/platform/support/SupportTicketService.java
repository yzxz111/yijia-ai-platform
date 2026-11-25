package com.yuzhihuihe.apiplatform.platform.support;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.notification.NotificationEventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SupportTicketService {
    static final int MAX_ATTACHMENT_COUNT = 5;
    static final int MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;
    private static final Set<String> ATTACHMENT_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp",
            "application/pdf", "text/plain", "application/zip",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final SupportTicketRepository repository;
    private final NotificationEventService notifications;
    private final Clock clock;

    @Autowired
    SupportTicketService(
            SupportTicketRepository repository, NotificationEventService notifications) {
        this(repository, notifications, Clock.systemUTC());
    }

    SupportTicketService(
            SupportTicketRepository repository,
            NotificationEventService notifications,
            Clock clock) {
        this.repository = repository;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public TicketView create(UUID tenantId, UUID userId, String title, String body) {
        return create(tenantId, userId, title, body, List.of());
    }

    @Transactional
    public TicketView create(UUID tenantId, UUID userId, String title, String body,
            List<AttachmentUpload> attachments) {
        String validTitle = text(title, 120, "invalid_ticket_title");
        String validBody = text(body, 5000, "invalid_ticket_body");
        List<AttachmentUpload> validAttachments = validateAttachments(attachments);
        UUID id = UUID.randomUUID();
        String ticketNo = "TK-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase(Locale.ROOT);
        repository.insertTicket(id, ticketNo, tenantId, userId, validTitle, validBody);
        insertAttachments(tenantId, id, null, userId, validAttachments);
        return detailUser(tenantId, userId, id);
    }

    @Transactional(readOnly = true)
    public List<TicketView> listUser(UUID tenantId, UUID userId) {
        return listUser(tenantId, userId, 0, 20).items();
    }

    @Transactional(readOnly = true)
    public PageResult<TicketView> listUser(UUID tenantId, UUID userId, int page, int size) {
        Page bounds = page(page, size);
        SupportTicketRepository.PageRows<SupportTicketRepository.TicketRow> result =
                repository.listUser(tenantId, userId, bounds.size(), bounds.offset());
        List<TicketView> items = result.items().stream().map(this::ticket).toList();
        return new PageResult<>(List.copyOf(items), result.total(),
                page, bounds.size());
    }

    @Transactional(readOnly = true)
    public TicketView detailUser(UUID tenantId, UUID userId, UUID ticketId) {
        return required(repository.findUser(tenantId, userId, ticketId));
    }

    @Transactional
    public TicketView addUserComment(UUID tenantId, UUID userId, UUID ticketId, String body) {
        return addUserComment(tenantId, userId, ticketId, body, List.of());
    }

    @Transactional
    public TicketView addUserComment(UUID tenantId, UUID userId, UUID ticketId, String body,
            List<AttachmentUpload> attachments) {
        String validBody = text(body, 5000, "invalid_ticket_comment");
        List<AttachmentUpload> validAttachments = validateAttachments(attachments);
        TicketView current = lockedUser(tenantId, userId, ticketId);
        if (current.status() == Status.CLOSED) throw conflict("ticket_closed");
        UUID commentId = insertComment(tenantId, ticketId, userId, "USER", validBody);
        insertAttachments(tenantId, ticketId, commentId, userId, validAttachments);
        return detailUser(tenantId, userId, ticketId);
    }

    @Transactional(readOnly = true)
    public List<TicketView> listAdmin(String status) {
        return listAdmin(status, 0, 20).items();
    }

    @Transactional(readOnly = true)
    public PageResult<TicketView> listAdmin(String status, int page, int size) {
        return listAdmin(status, null, null, null, page, size);
    }

    @Transactional(readOnly = true)
    public PageResult<TicketView> listAdmin(
            String status, String query, Instant from, Instant to, int page, int size) {
        Page bounds = page(page, size);
        String validStatus = null;
        if (status != null && !status.isBlank()) {
            validStatus = status(status).name();
        }
        String search = null;
        if (query != null && !query.isBlank()) {
            search = "%" + query.strip().toLowerCase(Locale.ROOT)
                    .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        }
        SupportTicketRepository.PageRows<SupportTicketRepository.TicketRow> result =
                repository.listAdmin(new SupportTicketRepository.AdminFilter(
                        validStatus, search, from, to, bounds.size(), bounds.offset()));
        List<TicketView> items = result.items().stream().map(this::ticket).toList();
        return new PageResult<>(List.copyOf(items), result.total(),
                page, bounds.size());
    }

    @Transactional(readOnly = true)
    public TicketView detailAdmin(UUID ticketId) {
        return required(repository.findAdmin(ticketId));
    }

    @Transactional
    public TicketView claim(UUID actorId, UUID ticketId) {
        TicketView current = locked(ticketId);
        UUID tenantId = current.tenantId();
        if (current.status() == Status.COMPLETED || current.status() == Status.CLOSED) {
            throw conflict("ticket_not_claimable");
        }
        repository.claim(actorId, ticketId, tenantId);
        audit(tenantId, actorId, ticketId, "CLAIMED", current.status());
        return detailAdmin(ticketId);
    }

    @Transactional
    public TicketView process(UUID actorId, UUID ticketId) {
        TicketView current = locked(ticketId);
        UUID tenantId = current.tenantId();
        if (current.status() != Status.OPEN) throw conflict("invalid_ticket_transition");
        repository.process(actorId, ticketId, tenantId);
        audit(tenantId, actorId, ticketId, "PROCESSING", Status.PROCESSING);
        return detailAdmin(ticketId);
    }

    @Transactional
    public TicketView reply(UUID actorId, UUID ticketId, String body) {
        return reply(actorId, ticketId, body, List.of());
    }

    @Transactional
    public List<TicketView> batchClaim(UUID actorId, List<UUID> ticketIds) {
        if (actorId == null || ticketIds == null || ticketIds.isEmpty() || ticketIds.size() > 100
                || ticketIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_ticket_batch");
        }
        List<TicketView> claimed = new ArrayList<>();
        for (UUID ticketId : ticketIds.stream().distinct().toList()) {
            TicketView current = locked(ticketId);
            if (current.status() != Status.OPEN || current.assigneeUserId() != null) {
                throw conflict("ticket_not_claimable");
            }
            repository.claim(actorId, ticketId, current.tenantId());
            audit(current.tenantId(), actorId, ticketId, "CLAIMED", current.status());
            claimed.add(detailAdmin(ticketId));
        }
        return List.copyOf(claimed);
    }

    @Transactional
    public TicketView reply(UUID actorId, UUID ticketId, String body,
            List<AttachmentUpload> attachments) {
        String validBody = text(body, 5000, "invalid_ticket_comment");
        List<AttachmentUpload> validAttachments = validateAttachments(attachments);
        TicketView current = locked(ticketId);
        UUID tenantId = current.tenantId();
        if (current.status() == Status.COMPLETED || current.status() == Status.CLOSED) {
            throw conflict("ticket_not_replyable");
        }
        UUID commentId = insertComment(tenantId, ticketId, actorId, "ADMIN", validBody);
        insertAttachments(tenantId, ticketId, commentId, actorId, validAttachments);
        OffsetDateTime repliedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        notifications.recordAuthoritativeBusinessEvent(
                "support-ticket-replied:" + commentId, "SUPPORT_TICKET_REPLIED",
                tenantId, current.userId(), "SUPPORT_TICKET", ticketId.toString(),
                Map.of("ticketNo", current.ticketNo(), "reply", notificationExcerpt(validBody)),
                repliedAt);
        audit(tenantId, actorId, ticketId, "REPLIED", current.status());
        return detailAdmin(ticketId);
    }

    @Transactional
    public TicketView complete(
            UUID actorId, UUID ticketId, String resolution, String businessNo) {
        String validResolution = text(resolution, 5000, "invalid_ticket_resolution");
        String validBusinessNo = text(businessNo, 128, "invalid_completion_business_no");
        TicketView current = locked(ticketId);
        UUID tenantId = current.tenantId();
        if (current.status() == Status.COMPLETED || current.status() == Status.CLOSED) {
            if (!validBusinessNo.equals(current.completionBusinessNo())) {
                throw conflict("ticket_completion_business_no_mismatch");
            }
            return current;
        }
        if (current.status() != Status.OPEN && current.status() != Status.PROCESSING) {
            throw conflict("invalid_ticket_transition");
        }
        OffsetDateTime completedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        repository.complete(
                validResolution, validBusinessNo, completedAt, actorId, ticketId, tenantId);
        notifications.recordAuthoritativeBusinessEvent(
                "support-ticket-completed:" + validBusinessNo, "SUPPORT_TICKET_COMPLETED",
                tenantId, current.userId(), "SUPPORT_TICKET", ticketId.toString(),
                Map.of("ticketNo", current.ticketNo()), completedAt);
        audit(tenantId, actorId, ticketId, "COMPLETED", Status.COMPLETED);
        return detailAdmin(ticketId);
    }

    @Transactional
    public TicketView closeUser(UUID tenantId, UUID userId, UUID ticketId) {
        TicketView current = lockedUser(tenantId, userId, ticketId);
        return close(tenantId, current);
    }

    @Transactional
    public TicketView closeAdmin(UUID actorId, UUID ticketId) {
        TicketView current = locked(ticketId);
        UUID tenantId = current.tenantId();
        if (current.status() != Status.COMPLETED && current.status() != Status.CLOSED) {
            throw conflict("invalid_ticket_transition");
        }
        TicketView closed = close(tenantId, current);
        audit(tenantId, actorId, ticketId, "CLOSED", Status.CLOSED);
        return closed;
    }

    private TicketView close(UUID tenantId, TicketView current) {
        if (current.status() == Status.CLOSED) return current;
        OffsetDateTime closedAt = current.completedAt() == null
                ? OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
                : current.completedAt();
        String resolution = current.resolution() == null ? "用户主动关闭" : current.resolution();
        String businessNo = current.completionBusinessNo() == null
                ? "USER-CLOSE:" + current.id()
                : current.completionBusinessNo();
        repository.close(resolution, businessNo, closedAt, current.id(), tenantId);
        return detailForTenant(tenantId, current.id());
    }

    private TicketView locked(UUID ticketId) {
        return required(repository.lock(ticketId));
    }

    private TicketView lockedUser(UUID tenantId, UUID userId, UUID ticketId) {
        return required(repository.lockUser(tenantId, userId, ticketId));
    }

    private UUID insertComment(UUID tenant, UUID ticket, UUID author, String role, String body) {
        UUID commentId = UUID.randomUUID();
        repository.insertComment(commentId, tenant, ticket, author, role, body);
        return commentId;
    }

    private static String notificationExcerpt(String body) {
        int maxCodePoints = 900;
        if (body.codePointCount(0, body.length()) <= maxCodePoints) return body;
        int end = body.offsetByCodePoints(0, maxCodePoints - 3);
        return body.substring(0, end) + "...";
    }

    private void audit(UUID tenant, UUID actor, UUID ticket, String action, Status status) {
        repository.audit(
                tenant, actor, ticket, "SUPPORT_TICKET_" + action, status.name());
    }

    public List<CommentView> comments(UUID tenantId, UUID ticketId) {
        detailForTenant(tenantId, ticketId);
        return new ArrayList<>(repository.comments(tenantId, ticketId).stream()
                .map(row -> new CommentView(
                        row.id(), row.authorUserId(), row.authorRole(), row.body(), row.createdAt()))
                .toList());
    }

    @Transactional(readOnly = true)
    public List<AttachmentView> attachmentsUser(UUID tenantId, UUID userId, UUID ticketId) {
        detailUser(tenantId, userId, ticketId);
        return attachments(tenantId, ticketId);
    }

    @Transactional(readOnly = true)
    public List<AttachmentView> attachmentsAdmin(UUID ticketId) {
        TicketView ticket = detailAdmin(ticketId);
        return attachments(ticket.tenantId(), ticketId);
    }

    @Transactional(readOnly = true)
    public AttachmentContent attachmentUser(
            UUID tenantId, UUID userId, UUID ticketId, UUID attachmentId) {
        detailUser(tenantId, userId, ticketId);
        return attachment(tenantId, ticketId, attachmentId);
    }

    @Transactional(readOnly = true)
    public AttachmentContent attachmentAdmin(UUID ticketId, UUID attachmentId) {
        TicketView ticket = detailAdmin(ticketId);
        return attachment(ticket.tenantId(), ticketId, attachmentId);
    }

    private List<AttachmentView> attachments(UUID tenantId, UUID ticketId) {
        return new ArrayList<>(repository.attachments(tenantId, ticketId).stream()
                .map(row -> new AttachmentView(
                        row.id(), row.commentId(), row.filename(), row.contentType(),
                        row.sizeBytes(), row.createdAt()))
                .toList());
    }

    private AttachmentContent attachment(UUID tenantId, UUID ticketId, UUID attachmentId) {
        SupportTicketRepository.AttachmentContentRow row =
                repository.attachment(tenantId, ticketId, attachmentId);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "support_ticket_attachment_not_found");
        }
        return new AttachmentContent(row.filename(), row.contentType(), row.bytes());
    }

    private void insertAttachments(UUID tenantId, UUID ticketId, UUID commentId,
            UUID uploaderUserId, List<AttachmentUpload> attachments) {
        for (AttachmentUpload attachment : attachments) {
            repository.insertAttachment(
                    UUID.randomUUID(), ticketId, tenantId, commentId, uploaderUserId,
                    attachment.filename(), attachment.contentType(), attachment.bytes());
        }
    }

    private static List<AttachmentUpload> validateAttachments(List<AttachmentUpload> attachments) {
        if (attachments == null || attachments.isEmpty()) return List.of();
        if (attachments.size() > MAX_ATTACHMENT_COUNT) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "too_many_ticket_attachments");
        }
        return attachments.stream().map(candidate -> {
            if (candidate == null || candidate.bytes() == null || candidate.bytes().length == 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "ticket_attachment_required");
            }
            if (candidate.bytes().length > MAX_ATTACHMENT_BYTES) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "ticket_attachment_too_large");
            }
            String filename = safeFilename(candidate.filename());
            String contentType = candidate.contentType() == null ? ""
                    : candidate.contentType().trim().toLowerCase(Locale.ROOT);
            if (!ATTACHMENT_CONTENT_TYPES.contains(contentType)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "ticket_attachment_type_not_supported");
            }
            return new AttachmentUpload(filename, contentType, candidate.bytes().clone());
        }).toList();
    }

    private static String safeFilename(String value) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid_ticket_attachment_filename");
        }
        String normalized = value.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "").trim();
        if (normalized.isEmpty() || normalized.length() > 255) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid_ticket_attachment_filename");
        }
        return normalized;
    }

    private TicketView detailForTenant(UUID tenantId, UUID ticketId) {
        return required(repository.findForTenant(tenantId, ticketId));
    }

    private static String text(String value, int max, String reason) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(reason);
        return value.trim();
    }

    private static Status status(String value) {
        try { return Status.valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("invalid_ticket_status"); }
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static Page page(int number, int size) {
        if (number < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_ticket_page");
        }
        long offset = (long) number * size;
        if (offset > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_ticket_page");
        }
        return new Page(size, (int) offset);
    }

    private TicketView required(SupportTicketRepository.TicketRow row) {
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "support_ticket_not_found");
        }
        return ticket(row);
    }

    private TicketView ticket(SupportTicketRepository.TicketRow row) {
        return new TicketView(
                row.id(), row.ticketNo(), row.tenantId(), row.userId(), row.assigneeUserId(),
                row.title(), row.body(), Status.valueOf(row.status()), row.resolution(),
                row.completionBusinessNo(), row.completedAt(), row.createdAt(), row.updatedAt(),
                List.of());
    }

    public enum Status { OPEN, PROCESSING, COMPLETED, CLOSED }

    public record TicketView(UUID id, String ticketNo, UUID tenantId, UUID userId,
            UUID assigneeUserId, String title, String body, Status status, String resolution,
            String completionBusinessNo, OffsetDateTime completedAt, OffsetDateTime createdAt,
            OffsetDateTime updatedAt, List<CommentView> comments) { }

    public record CommentView(UUID id, UUID authorUserId, String authorRole, String body,
            OffsetDateTime createdAt) { }
    public record AttachmentUpload(String filename, String contentType, byte[] bytes) { }
    public record AttachmentView(UUID id, UUID commentId, String filename, String contentType,
            int sizeBytes, OffsetDateTime createdAt) { }
    public record AttachmentContent(String filename, String contentType, byte[] bytes) { }
    public record PageResult<T>(List<T> items, long total, int page, int size) { }
    private record Page(int size, int offset) { }
}

package com.yuzhihuihe.apiplatform.platform.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.notification.NotificationEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class SupportTicketServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SupportTicketService service = new SupportTicketService(
            new SupportTicketRepository(jdbc),
            mock(NotificationEventService.class), Clock.systemUTC());

    @Test
    void productionConstructorIsExplicitlySelectedForSpringInjection() throws Exception {
        org.assertj.core.api.Assertions.assertThat(SupportTicketService.class
                .getDeclaredConstructor(
                        SupportTicketRepository.class, NotificationEventService.class)
                .getAnnotation(Autowired.class)).isNotNull();
    }

    @Test
    void rejectsInvalidUserContentBeforeDatabaseAccess() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        assertThatThrownBy(() -> service.create(tenant, user, " ", "body"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid_ticket_title");
        assertThatThrownBy(() -> service.create(tenant, user, "x".repeat(121), "body"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid_ticket_title");
        assertThatThrownBy(() -> service.create(tenant, user, "title", "x".repeat(5001)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid_ticket_body");
        assertThatThrownBy(() -> service.addUserComment(tenant, user, UUID.randomUUID(), " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid_ticket_comment");

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsUnsafeOrOversizedAttachmentsBeforeDatabaseAccess() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        assertThatThrownBy(() -> service.create(tenant, user, "title", "body", List.of(
                new SupportTicketService.AttachmentUpload("page.html", "text/html", new byte[] {1}))))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("ticket_attachment_type_not_supported"));
        assertThatThrownBy(() -> service.create(tenant, user, "title", "body", List.of(
                new SupportTicketService.AttachmentUpload("large.pdf", "application/pdf",
                        new byte[SupportTicketService.MAX_ATTACHMENT_BYTES + 1]))))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("ticket_attachment_too_large"));
        assertThatThrownBy(() -> service.create(tenant, user, "title", "body", List.of(
                new SupportTicketService.AttachmentUpload("1.txt", "text/plain", new byte[] {1}),
                new SupportTicketService.AttachmentUpload("2.txt", "text/plain", new byte[] {1}),
                new SupportTicketService.AttachmentUpload("3.txt", "text/plain", new byte[] {1}),
                new SupportTicketService.AttachmentUpload("4.txt", "text/plain", new byte[] {1}),
                new SupportTicketService.AttachmentUpload("5.txt", "text/plain", new byte[] {1}),
                new SupportTicketService.AttachmentUpload("6.txt", "text/plain", new byte[] {1}))))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("too_many_ticket_attachments"));

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsInvalidCompletionBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.complete(
                UUID.randomUUID(), UUID.randomUUID(), " ", "business-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid_ticket_resolution");
        assertThatThrownBy(() -> service.complete(
                UUID.randomUUID(), UUID.randomUUID(), "resolved", " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid_completion_business_no");
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsInvalidPaginationAsAnUnprocessableRequest() {
        assertThatThrownBy(() -> service.listAdmin("OPEN", 2, 500))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("invalid_ticket_page");
                });
        assertThatThrownBy(() -> service.listUser(UUID.randomUUID(), UUID.randomUUID(), -1, 20))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verifyNoInteractions(jdbc);
    }

    @Test
    void usesAUniqueTieBreakerForEveryPaginatedTicketQuery() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(java.util.List.of());
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
                .thenReturn(java.util.List.of());
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenReturn(java.util.List.of());

        service.listUser(tenant, user, 0, 20);
        service.listAdmin(null, 0, 20);
        service.listAdmin("OPEN", 0, 20);

        verify(jdbc).query(argThat(sql -> sql.contains("ORDER BY t.created_at DESC, t.id DESC")),
                any(RowMapper.class), org.mockito.ArgumentMatchers.eq(tenant),
                org.mockito.ArgumentMatchers.eq(user), org.mockito.ArgumentMatchers.eq(20),
                org.mockito.ArgumentMatchers.eq(0));
        verify(jdbc).query(argThat(sql -> sql.contains("ORDER BY t.updated_at DESC, t.id DESC")),
                any(RowMapper.class), org.mockito.ArgumentMatchers.eq(20),
                org.mockito.ArgumentMatchers.eq(0));
        verify(jdbc).query(argThat(sql -> sql.contains("ORDER BY t.updated_at DESC, t.id DESC")),
                any(RowMapper.class), org.mockito.ArgumentMatchers.eq("OPEN"),
                org.mockito.ArgumentMatchers.eq(20), org.mockito.ArgumentMatchers.eq(0));
    }

    @Test
    void listsAllTenantsWhenAdminStatusFilterIsEmpty() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
                .thenReturn(java.util.List.of());

        service.listAdmin(null, 0, 20);

        verify(jdbc).query(argThat(sql -> !sql.contains("tenant_id=?")
                        && sql.contains("ORDER BY t.updated_at DESC")),
                any(RowMapper.class), org.mockito.ArgumentMatchers.eq(20),
                org.mockito.ArgumentMatchers.eq(0));
    }

    @Test
    void countsTheSameFilteredAdminTicketSetUsedByThePageQuery() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenReturn(java.util.List.of());

        service.listAdmin("OPEN", 0, 20);

        verify(jdbc).queryForObject(argThat(sql -> sql.contains("COUNT(*)")
                        && sql.contains("t.status=?")),
                org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.eq("OPEN"));
    }

    @Test
    void countsOnlyTheCurrentUsersTickets() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(java.util.List.of());

        service.listUser(tenant, user, 0, 20);

        verify(jdbc).queryForObject(argThat(sql -> sql.contains("COUNT(*)")
                        && sql.contains("t.tenant_id=?") && sql.contains("t.user_id=?")),
                org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.eq(tenant), org.mockito.ArgumentMatchers.eq(user));
    }

    @Test
    void userCanCloseANewlyCreatedOpenTicket() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        SupportTicketService.TicketView open = new SupportTicketService.TicketView(
                ticketId, "TK-OPEN", tenant, user, null, "Title", "Body",
                SupportTicketService.Status.OPEN, null, null, null, now, now, java.util.List.of());
        SupportTicketService.TicketView closed = new SupportTicketService.TicketView(
                ticketId, "TK-OPEN", tenant, user, null, "Title", "Body",
                SupportTicketService.Status.CLOSED, "用户主动关闭", "USER-CLOSE:" + ticketId,
                now, now, now, java.util.List.of());
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenReturn(java.util.List.of(row(open)));
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
                .thenReturn(java.util.List.of(row(closed)));

        assertThat(service.closeUser(tenant, user, ticketId).status())
                .isEqualTo(SupportTicketService.Status.CLOSED);
    }

    @Test
    void adminStillCannotCloseAnOpenTicketBeforeCompletion() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        SupportTicketService.TicketView open = new SupportTicketService.TicketView(
                ticketId, "TK-OPEN", tenant, user, null, "Title", "Body",
                SupportTicketService.Status.OPEN, null, null, null, now, now, java.util.List.of());
        SupportTicketService.TicketView closed = new SupportTicketService.TicketView(
                ticketId, "TK-OPEN", tenant, user, null, "Title", "Body",
                SupportTicketService.Status.CLOSED, "用户主动关闭", "USER-CLOSE:" + ticketId,
                now, now, now, java.util.List.of());
        when(jdbc.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(java.util.List.of(row(open)));
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
                .thenReturn(java.util.List.of(row(closed)));

        assertThatThrownBy(() -> service.closeAdmin(UUID.randomUUID(), ticketId))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("invalid_ticket_transition");
    }

    private SupportTicketRepository.TicketRow row(SupportTicketService.TicketView ticket) {
        return new SupportTicketRepository.TicketRow(
                ticket.id(), ticket.ticketNo(), ticket.tenantId(), ticket.userId(),
                ticket.assigneeUserId(), ticket.title(), ticket.body(), ticket.status().name(),
                ticket.resolution(), ticket.completionBusinessNo(), ticket.completedAt(),
                ticket.createdAt(), ticket.updatedAt());
    }
}

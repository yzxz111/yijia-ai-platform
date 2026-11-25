package com.yuzhihuihe.apiplatform.platform.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Arrays;
import java.time.Instant;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.notification.NotificationEventService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class ContentSafetyAdminServiceTest {

    @Test
    void listCasesWithoutStatusDoesNotBindNullableStatusPredicate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
        ContentSafetyAdminService service = service(jdbc);

        service.listCases(null, 50);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> limit = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> offset = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), limit.capture(), offset.capture());
        assertThat(sql.getValue()).doesNotContain("? IS NULL").contains("LIMIT ? OFFSET ?");
        assertThat(limit.getValue()).isEqualTo(50);
        assertThat(offset.getValue()).isEqualTo(0);
    }

    @Test
    void listCasesWithStatusBindsStatusAndLimitOnly() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
        ContentSafetyAdminService service = service(jdbc);

        service.listCases("OPEN", 500);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> status = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> limit = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> offset = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), status.capture(), limit.capture(), offset.capture());
        assertThat(sql.getValue()).contains("WHERE status = ?").doesNotContain("? IS NULL");
        assertThat(status.getValue()).isEqualTo("OPEN");
        assertThat(limit.getValue()).isEqualTo(100);
        assertThat(offset.getValue()).isEqualTo(0);
    }

    @Test
    void rejectsAppealWithInvalidCaseNumberBeforeQueryingDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ContentSafetyAdminService service = service(jdbc);

        assertThatThrownBy(() -> service.createAppeal(
                UUID.randomUUID(), UUID.randomUUID(), "11", "reason"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_appeal_case_no");
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsWithdrawalWithInvalidBusinessNumberBeforeQueryingDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ContentSafetyAdminService service = service(jdbc);

        assertThatThrownBy(() -> service.withdrawAppeal(
                UUID.randomUUID(), UUID.randomUUID(), "AP-123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_appeal_business_no");
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsAdminWithdrawalWithInvalidBusinessNumberBeforeQueryingDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ContentSafetyAdminService service = service(jdbc);

        assertThatThrownBy(() -> service.withdrawAppealAsAdmin(
                "AP-invalid", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_appeal_business_no");
        verifyNoInteractions(jdbc);
    }

    @Test
    void exposesIndependentPageMethodsForEverySafetyCollection() {
        assertThat(Arrays.stream(ContentSafetyAdminService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("listCases")))
                .anyMatch(method -> method.getParameterCount() == 4
                        && method.getReturnType().getSimpleName().equals("PageResult"));
        assertThat(Arrays.stream(ContentSafetyAdminService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("appeals")))
                .anyMatch(method -> method.getParameterCount() == 5
                        && method.getReturnType().getSimpleName().equals("PageResult"));
        assertThat(Arrays.stream(ContentSafetyAdminService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("userCases")))
                .anyMatch(method -> method.getParameterCount() == 4
                        && method.getReturnType().getSimpleName().equals("PageResult"));
    }

    @Test
    void rejectsInvalidPageBoundsAsAnUnprocessableRequest() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ContentSafetyAdminService service = service(jdbc);

        assertThatThrownBy(() -> service.listCases(null, "", 0, 101))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("invalid_safety_page");
                });
        assertThatThrownBy(() -> service.userCases(UUID.randomUUID(), UUID.randomUUID(), -1, 20))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verifyNoInteractions(jdbc);
    }

    @Test
    void resolvesConfirmedCaseAsRecordedWithoutAccountEnforcement() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ContentSafetyCaseService cases = mock(ContentSafetyCaseService.class);
        NotificationEventService notifications = mock(NotificationEventService.class);
        ContentSafetyAdminService service = new ContentSafetyAdminService(
                new ContentSafetyAdminRepository(jdbc), cases, notifications);
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        String caseNo = "CS-0123456789abcdef0123";
        ContentSafetyCaseDto current = new ContentSafetyCaseDto(
                caseNo, tenantId, userId, "request-1", "INPUT", "UNSAFE_CONTENT", "HIGH", "BLOCK",
                "CONFIRMED", 1, "0".repeat(64), "redacted", Instant.now(), Instant.now(),
                null, null, null);
        when(cases.findByCaseNo(caseNo)).thenReturn(current);
        when(cases.resolve(caseNo, "RECORDED", actor, "manual review complete")).thenReturn(current);

        assertThat(service.resolve(caseNo, "RECORDED", actor, "manual review complete"))
                .isSameAs(current);
        verify(cases).resolve(caseNo, "RECORDED", actor, "manual review complete");
        verifyNoInteractions(jdbc);
    }

    @Test
    void countsEverySafetyCollectionInsteadOfSilentlyTruncatingIt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
        ContentSafetyAdminService service = service(jdbc);
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        service.listCases(null, 20);
        service.appeals(null, null);
        service.userCases(tenant, user);

        verify(jdbc).queryForObject(
                "SELECT COUNT(*) FROM platform.content_safety_case", Long.class);
        verify(jdbc).queryForObject(
                "SELECT COUNT(*) FROM platform.content_safety_appeal", Long.class);
        verify(jdbc).queryForObject(
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("COUNT(*)")
                        && sql.contains("c.tenant_id = ?") && sql.contains("c.user_id = ?")),
                org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.eq(tenant), org.mockito.ArgumentMatchers.eq(user));
    }

    private ContentSafetyAdminService service(JdbcTemplate jdbc) {
        return new ContentSafetyAdminService(
                new ContentSafetyAdminRepository(jdbc),
                mock(ContentSafetyCaseService.class), mock(NotificationEventService.class));
    }
}

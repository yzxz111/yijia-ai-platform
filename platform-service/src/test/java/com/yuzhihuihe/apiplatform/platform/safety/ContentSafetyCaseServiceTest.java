package com.yuzhihuihe.apiplatform.platform.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ContentSafetyCaseServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("91000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("91000000-0000-4000-8000-000000000002");

    @Mock
    private ContentSafetyCaseRepository repository;

    private ObjectMapper objectMapper;
    private ContentSafetyCaseService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new ContentSafetyCaseService(repository, objectMapper);
    }

    @Test
    void replaysExistingCaseWhenBusinessIdAndPayloadMatch() throws Exception {
        ContentSafetyCaseService.SafetyEventPayload event = event("event-001", "BLOCK");
        stubExistingCase(event, payloadHash(event));

        ContentSafetyCaseDto result = service.ingest(event);

        assertThat(result.caseNo()).isEqualTo("CS-EXISTING");
        verify(repository, never()).insertInbox(any(), any(), anyString(), anyString());
        verify(repository, never()).insertCase(any(), anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void rejectsReusedBusinessIdWithDifferentPayload() {
        ContentSafetyCaseService.SafetyEventPayload event = event("event-001", "BLOCK");
        when(repository.findInboxPayloadHash(event.businessId())).thenReturn("0".repeat(64));

        assertThatThrownBy(() -> service.ingest(event))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getReason()).isEqualTo("content_safety_event_payload_mismatch");
                });
        verify(repository, never()).insertInbox(any(), any(), anyString(), anyString());
        verify(repository, never()).insertCase(any(), anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void rechecksPayloadAfterConcurrentInboxInsert() throws Exception {
        ContentSafetyCaseService.SafetyEventPayload event = event("event-002", "REVIEW");
        String payloadHash = payloadHash(event);
        when(repository.findInboxPayloadHash(event.businessId())).thenReturn(null, payloadHash);
        when(repository.insertInbox(any(), any(), anyString(), anyString())).thenReturn(0);
        stubCaseLookup(event);

        ContentSafetyCaseDto result = service.ingest(event);

        assertThat(result.caseNo()).isEqualTo("CS-EXISTING");
        verify(repository, never()).insertCase(any(), anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void rejectsDifferentPayloadAfterConcurrentInboxInsert() {
        ContentSafetyCaseService.SafetyEventPayload event = event("event-003", "BLOCK");
        when(repository.findInboxPayloadHash(event.businessId()))
                .thenReturn(null, "0".repeat(64));
        when(repository.insertInbox(any(), any(), anyString(), anyString())).thenReturn(0);

        assertThatThrownBy(() -> service.ingest(event))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getReason()).isEqualTo("content_safety_event_payload_mismatch");
                });
        verify(repository, never()).insertCase(any(), anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void createsCaseWhenBusinessIdIsNew() {
        ContentSafetyCaseService.SafetyEventPayload event = event("event-004", "REVIEW");
        when(repository.findInboxPayloadHash(event.businessId())).thenReturn(null);
        when(repository.insertInbox(any(), any(), anyString(), anyString())).thenReturn(1);
        when(repository.findByCaseNo(anyString())).thenAnswer(invocation ->
                caseRow(event, invocation.getArgument(0)));

        ContentSafetyCaseDto result = service.ingest(event);

        assertThat(result.caseNo()).startsWith("CS-");
        verify(repository).ensurePolicyVersion(event.policyVersion());
        verify(repository).insertCase(
                any(), anyString(), any(), anyString(), anyString(), anyString());
    }

    private void stubExistingCase(
            ContentSafetyCaseService.SafetyEventPayload event, String payloadHash) {
        when(repository.findInboxPayloadHash(event.businessId())).thenReturn(payloadHash);
        stubCaseLookup(event);
    }

    private void stubCaseLookup(ContentSafetyCaseService.SafetyEventPayload event) {
        when(repository.findCaseNoByFingerprint(sha256(event.businessId())))
                .thenReturn("CS-EXISTING");
        when(repository.findByCaseNo("CS-EXISTING")).thenReturn(caseRow(event));
    }

    private ContentSafetyCaseRepository.CaseRow caseRow(
            ContentSafetyCaseService.SafetyEventPayload event) {
        return caseRow(event, "CS-EXISTING");
    }

    private ContentSafetyCaseRepository.CaseRow caseRow(
            ContentSafetyCaseService.SafetyEventPayload event, String caseNo) {
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        return new ContentSafetyCaseRepository.CaseRow(
                caseNo, event.tenantId(), event.userId(), event.requestId(),
                event.direction(), event.category(), event.severity(), event.action(), "OPEN",
                event.policyVersion(), "0".repeat(64), event.redactedExcerpt(), now, now,
                null, null, null);
    }

    private ContentSafetyCaseService.SafetyEventPayload event(String businessId, String action) {
        return new ContentSafetyCaseService.SafetyEventPayload(
                businessId, "MODEL_CONTENT_SAFETY", "request-001", TENANT_ID, USER_ID,
                "INPUT", "LOCAL", "ABUSE", "HIGH", action, 1L, "0".repeat(64),
                "redacted excerpt", "provider-001", "text-basic", "openai", "channel-001");
    }

    private String payloadHash(ContentSafetyCaseService.SafetyEventPayload event) throws Exception {
        return sha256(objectMapper.writeValueAsString(event));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

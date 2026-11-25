package com.yuzhihuihe.apiplatform.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.legal.LegalDocumentService;
import com.yuzhihuihe.apiplatform.platform.sms.SmsApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class IdentityConsentServiceTest {

    private final IdentityConsentRepository repository = mock(IdentityConsentRepository.class);
    private final ConsentProperties properties = new ConsentProperties();
    private final IdentityConsentService service =
            new IdentityConsentService(repository, properties);
    private final LegalDocumentService legalDocuments = mock(LegalDocumentService.class);

    @Test
    void rejectsAccountCreationWithoutExplicitAcceptance() {
        for (Boolean accepted : new Boolean[] {null, Boolean.FALSE}) {
            assertThatThrownBy(() -> service.requireAcceptance(accepted, "2026-09-15"))
                    .isInstanceOf(SmsApiException.class)
                    .hasMessage("terms_acceptance_required")
                    .extracting(error -> ((SmsApiException) error).status().value())
                    .isEqualTo(422);
        }
    }

    @Test
    void recordsBothDocumentsWithTheAcceptedVersionAndSource() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        service.recordAcceptance(
                tenantId, userId, "2026-09-15", IdentityConsentService.SOURCE_EMAIL_IDENTITY);

        verify(repository).record(
                tenantId, userId, "TERMS", "2026-09-15", "EMAIL_IDENTITY");
        verify(repository).record(
                tenantId, userId, "PRIVACY", "2026-09-15", "EMAIL_IDENTITY");
    }

    @Test
    void fallsBackToThePublishedVersionWhenTheClientOmitsIt() {
        assertThat(service.requireAcceptance(true, null)).isEqualTo(service.currentVersion());
        assertThat(service.requireAcceptance(true, "   ")).isEqualTo(service.currentVersion());
        assertThat(service.currentVersion()).isEqualTo(ConsentProperties.DEFAULT_VERSION);
    }

    @Test
    void rejectsAnImplausiblyLongVersionInsteadOfPersistingIt() {
        assertThatThrownBy(() -> service.requireAcceptance(true, "v".repeat(33)))
                .isInstanceOf(SmsApiException.class)
                .hasMessage("invalid_consent_version");
    }

    @Test
    void keepsTheDocumentedDefaultVersionWhenConfigurationIsBlank() {
        properties.setVersion("   ");

        assertThat(properties.getVersion()).isEqualTo(ConsentProperties.DEFAULT_VERSION);
    }

    @Test
    void staysInertWhenBuiltWithoutPersistence() {
        IdentityConsentService inert = IdentityConsentService.unavailable();

        assertThat(inert.requireAcceptance(true, null)).isEqualTo(ConsentProperties.DEFAULT_VERSION);
        inert.recordAcceptance(
                UUID.randomUUID(), UUID.randomUUID(), "2026-09-15", "REGISTRATION");
    }

    @Test
    void recordsThePublishedDocumentVersionRatherThanTheConfiguredDefault() {
        service.applyLegalDocuments(legalDocuments);
        when(legalDocuments.currentVersion(LegalDocumentService.KIND_TERMS))
                .thenReturn(Optional.of("2026-10-01"));

        assertThat(service.requireAcceptance(true, null)).isEqualTo("2026-10-01");
        assertThat(service.currentVersion()).isEqualTo("2026-10-01");
    }

    @Test
    void fallsBackToTheDefaultWhenNothingIsPublishedYet() {
        service.applyLegalDocuments(legalDocuments);
        when(legalDocuments.currentVersion(LegalDocumentService.KIND_TERMS))
                .thenReturn(Optional.empty());

        assertThat(service.currentVersion()).isEqualTo(ConsentProperties.DEFAULT_VERSION);
    }

    @Test
    void rejectsAnAcceptanceOfTextThatIsNoLongerPublished() {
        service.applyLegalDocuments(legalDocuments);
        doThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "stale"))
                .when(legalDocuments)
                .requireCurrentVersion(eq(LegalDocumentService.KIND_TERMS), any());

        assertThatThrownBy(() -> service.requireAcceptance(true, "2026-09-15"))
                .isInstanceOf(SmsApiException.class)
                .hasMessage("legal_document_version_stale");
    }

    @Test
    void acceptsTheVersionThatIsCurrentlyPublished() {
        service.applyLegalDocuments(legalDocuments);

        assertThat(service.requireAcceptance(true, "2026-10-01")).isEqualTo("2026-10-01");
        verify(legalDocuments).requireCurrentVersion(LegalDocumentService.KIND_TERMS, "2026-10-01");
    }
}

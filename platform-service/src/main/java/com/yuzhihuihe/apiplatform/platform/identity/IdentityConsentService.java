package com.yuzhihuihe.apiplatform.platform.identity;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.yuzhihuihe.apiplatform.platform.legal.LegalDocumentService;
import com.yuzhihuihe.apiplatform.platform.sms.SmsApiException;

/**
 * Enforces and records the service-terms and privacy-policy consent that the browser asks for on
 * the login page. A new phone identity remains pending after code verification and is created only
 * after password setup, so both steps enforce consent server-side before an account can exist.
 */
@Service
public class IdentityConsentService {

    static final String TERMS_DOCUMENT = "TERMS";
    static final String PRIVACY_DOCUMENT = "PRIVACY";
    static final String SOURCE_PHONE_IDENTITY = "PHONE_IDENTITY";
    static final String SOURCE_EMAIL_IDENTITY = "EMAIL_IDENTITY";
    static final String SOURCE_REGISTRATION = "REGISTRATION";

    private static final int MAX_VERSION_LENGTH = 32;

    private final IdentityConsentRepository repository;
    private final ConsentProperties properties;
    /**
     * Optional: reads the currently published document versions. Absent in hand-built test
     * fixtures, which then fall back to the configured default version.
     */
    private LegalDocumentService legalDocuments;

    public IdentityConsentService(
            IdentityConsentRepository repository, ConsentProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** Consent-inert instance used when an identity service is built without persistence. */
    public static IdentityConsentService unavailable() {
        return new IdentityConsentService(null, null);
    }

    @Autowired(required = false)
    void applyLegalDocuments(LegalDocumentService service) {
        this.legalDocuments = service;
    }

    /**
     * Version recorded for a newly created account. Prefers the published document version so the
     * consent ledger always points at text an administrator actually published; falls back to the
     * configured default when no document is published (or in hand-built test fixtures).
     */
    public String currentVersion() {
        if (legalDocuments != null) {
            String published = legalDocuments
                    .currentVersion(LegalDocumentService.KIND_TERMS)
                    .orElse(null);
            if (published != null) {
                return published;
            }
        }
        ConsentProperties current = properties;
        return current == null ? ConsentProperties.DEFAULT_VERSION : current.getVersion();
    }

    /**
     * Verifies that a request that may create an account carried an explicit acceptance and returns
     * the version to persist. A version that no longer matches the published document is rejected so
     * that a user can never be recorded as accepting text they were not shown.
     */
    public String requireAcceptance(Boolean termsAccepted, String requestedVersion) {
        if (!Boolean.TRUE.equals(termsAccepted)) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "terms_acceptance_required");
        }
        if (requestedVersion == null || requestedVersion.isBlank()) {
            return currentVersion();
        }
        String version = requestedVersion.strip();
        if (version.length() > MAX_VERSION_LENGTH) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_consent_version");
        }
        if (legalDocuments != null) {
            try {
                legalDocuments.requireCurrentVersion(LegalDocumentService.KIND_TERMS, version);
            } catch (ResponseStatusException stale) {
                throw new SmsApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "legal_document_version_stale");
            }
        }
        return version;
    }

    /** Persists the acceptance of both documents for a newly created account. */
    public void recordAcceptance(UUID tenantId, UUID userId, String version, String source) {
        if (repository == null) {
            return;
        }
        for (String document : List.of(TERMS_DOCUMENT, PRIVACY_DOCUMENT)) {
            repository.record(tenantId, userId, document, version, source);
        }
    }
}

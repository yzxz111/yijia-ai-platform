-- Records explicit user consent to the platform service terms and privacy policy.
-- The unified identity login creates accounts on first verification-code login, so
-- consent must be enforced and persisted server-side rather than only in the browser.
CREATE TABLE platform.user_consent (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES platform.app_user(id) ON DELETE CASCADE,
    document varchar(16) NOT NULL,
    version varchar(32) NOT NULL,
    source varchar(32) NOT NULL,
    accepted_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_consent_document CHECK (document IN ('TERMS', 'PRIVACY')),
    CONSTRAINT ck_user_consent_source
        CHECK (source IN ('PHONE_IDENTITY', 'EMAIL_IDENTITY', 'REGISTRATION')),
    CONSTRAINT uq_user_consent_document UNIQUE (user_id, document)
);

CREATE INDEX ix_user_consent_tenant ON platform.user_consent(tenant_id, document);

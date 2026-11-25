CREATE TABLE platform.content_safety_policy_version (
    version bigint PRIMARY KEY,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    config jsonb NOT NULL DEFAULT '{}'::jsonb,
    config_sha256 varchar(64) NOT NULL,
    published_by uuid REFERENCES platform.app_user(id) ON DELETE SET NULL,
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_content_safety_policy_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_content_safety_policy_hash
        CHECK (config_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_content_safety_policy_publication
        CHECK ((status = 'PUBLISHED' AND published_at IS NOT NULL)
            OR status <> 'PUBLISHED')
);

CREATE UNIQUE INDEX uq_content_safety_published_policy
    ON platform.content_safety_policy_version (status)
    WHERE status = 'PUBLISHED';

CREATE TABLE platform.content_safety_rule (
    id uuid PRIMARY KEY,
    policy_version bigint NOT NULL
        REFERENCES platform.content_safety_policy_version(version) ON DELETE RESTRICT,
    rule_code varchar(128) NOT NULL,
    rule_type varchar(32) NOT NULL,
    category varchar(64) NOT NULL,
    severity varchar(16) NOT NULL,
    action varchar(32) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    encrypted_definition bytea NOT NULL,
    rule_sha256 varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_content_safety_rule_version_code UNIQUE (policy_version, rule_code),
    CONSTRAINT ck_content_safety_rule_type
        CHECK (rule_type IN ('SENSITIVE_WORD', 'REGEX', 'PROMPT_INJECTION', 'FILE', 'SEMANTIC')),
    CONSTRAINT ck_content_safety_rule_severity
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_content_safety_rule_action
        CHECK (action IN ('ALLOW', 'REVIEW', 'BLOCK')),
    CONSTRAINT ck_content_safety_rule_hash
        CHECK (rule_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_content_safety_rule_policy_enabled
    ON platform.content_safety_rule (policy_version, enabled, category, severity);

CREATE TABLE platform.content_safety_case (
    id uuid PRIMARY KEY,
    case_no varchar(64) NOT NULL UNIQUE,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES platform.app_user(id) ON DELETE RESTRICT,
    request_id varchar(128) NOT NULL,
    direction varchar(16) NOT NULL,
    source varchar(16) NOT NULL,
    category varchar(64) NOT NULL,
    severity varchar(16) NOT NULL,
    action varchar(32) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'OPEN',
    policy_version bigint NOT NULL
        REFERENCES platform.content_safety_policy_version(version) ON DELETE RESTRICT,
    provider_code varchar(64),
    model_alias varchar(128),
    provider varchar(64),
    channel_id varchar(128),
    evidence_sha256 varchar(64) NOT NULL,
    redacted_excerpt varchar(1000),
    case_fingerprint varchar(64) NOT NULL,
    reviewed_by uuid REFERENCES platform.app_user(id) ON DELETE SET NULL,
    reviewed_at timestamptz,
    review_note varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_content_safety_case_direction
        CHECK (direction IN ('INPUT', 'OUTPUT', 'FILE')),
    CONSTRAINT ck_content_safety_case_source
        CHECK (source IN ('LOCAL', 'SEMANTIC', 'CLAMAV', 'SYSTEM')),
    CONSTRAINT ck_content_safety_case_severity
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_content_safety_case_action
        CHECK (action IN ('ALLOW', 'REVIEW', 'BLOCK')),
    CONSTRAINT ck_content_safety_case_status
        CHECK (status IN ('OPEN', 'INVESTIGATING', 'CONFIRMED', 'DISMISSED', 'RESOLVED')),
    CONSTRAINT ck_content_safety_case_hashes
        CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$' AND case_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_content_safety_case_fingerprint
    ON platform.content_safety_case (case_fingerprint);
CREATE INDEX ix_content_safety_case_queue
    ON platform.content_safety_case (status, severity, created_at DESC);
CREATE INDEX ix_content_safety_case_owner
    ON platform.content_safety_case (tenant_id, user_id, created_at DESC);

CREATE TABLE platform.account_enforcement (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES platform.app_user(id) ON DELETE RESTRICT,
    state varchar(16) NOT NULL,
    reason_code varchar(128) NOT NULL,
    case_id uuid REFERENCES platform.content_safety_case(id) ON DELETE SET NULL,
    created_by uuid REFERENCES platform.app_user(id) ON DELETE SET NULL,
    lifted_by uuid REFERENCES platform.app_user(id) ON DELETE SET NULL,
    starts_at timestamptz NOT NULL DEFAULT now(),
    ends_at timestamptz,
    lifted_at timestamptz,
    version bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_account_enforcement_state
        CHECK (state IN ('FROZEN', 'BANNED', 'CLEARED')),
    CONSTRAINT ck_account_enforcement_version CHECK (version > 0),
    CONSTRAINT ck_account_enforcement_lift
        CHECK ((state = 'CLEARED' AND lifted_at IS NOT NULL)
            OR (state <> 'CLEARED' AND lifted_at IS NULL))
);

CREATE UNIQUE INDEX uq_account_active_enforcement
    ON platform.account_enforcement (tenant_id, user_id)
    WHERE state IN ('FROZEN', 'BANNED');
CREATE INDEX ix_account_enforcement_version
    ON platform.account_enforcement (tenant_id, user_id, version DESC);

CREATE TABLE platform.content_safety_appeal (
    id uuid PRIMARY KEY,
    case_id uuid NOT NULL REFERENCES platform.content_safety_case(id) ON DELETE RESTRICT,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES platform.app_user(id) ON DELETE RESTRICT,
    business_no varchar(64) NOT NULL UNIQUE,
    reason varchar(2000) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    processed_by uuid REFERENCES platform.app_user(id) ON DELETE SET NULL,
    processing_note varchar(1000),
    processed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_content_safety_appeal_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_content_safety_appeal_processing
        CHECK ((status = 'PENDING' AND processed_at IS NULL)
            OR (status <> 'PENDING' AND processed_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_content_safety_appeal_pending_case
    ON platform.content_safety_appeal (case_id)
    WHERE status = 'PENDING';
CREATE INDEX ix_content_safety_appeal_owner
    ON platform.content_safety_appeal (tenant_id, user_id, created_at DESC);

CREATE TABLE platform.content_safety_event_inbox (
    id uuid PRIMARY KEY,
    business_id varchar(128) NOT NULL,
    event_type varchar(128) NOT NULL,
    request_id varchar(128) NOT NULL,
    payload_hash varchar(64) NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    last_error varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_content_safety_inbox_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_content_safety_inbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED')),
    CONSTRAINT ck_content_safety_inbox_attempts CHECK (attempt_count >= 0)
);

CREATE UNIQUE INDEX uq_content_safety_inbox_business_id
    ON platform.content_safety_event_inbox (business_id);
CREATE INDEX ix_content_safety_inbox_delivery
    ON platform.content_safety_event_inbox (status, next_attempt_at, created_at);

CREATE OR REPLACE FUNCTION platform.reject_published_content_safety_policy_mutation()
RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'published_content_safety_policy_immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER published_content_safety_policy_immutable
    BEFORE UPDATE OR DELETE ON platform.content_safety_policy_version
    FOR EACH ROW EXECUTE FUNCTION platform.reject_published_content_safety_policy_mutation();

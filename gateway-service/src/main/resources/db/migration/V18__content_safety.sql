CREATE TABLE gateway.content_safety_check (
    id uuid PRIMARY KEY,
    request_id varchar(128) NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    api_key_id uuid,
    direction varchar(16) NOT NULL,
    result varchar(16) NOT NULL,
    policy_version bigint NOT NULL,
    local_result jsonb NOT NULL DEFAULT '{}'::jsonb,
    semantic_result jsonb NOT NULL DEFAULT '{}'::jsonb,
    file_result jsonb NOT NULL DEFAULT '{}'::jsonb,
    duration_ms bigint NOT NULL DEFAULT 0,
    safety_fingerprint varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_content_safety_check_direction
        CHECK (direction IN ('INPUT', 'OUTPUT', 'FILE')),
    CONSTRAINT ck_content_safety_check_result
        CHECK (result IN ('ALLOW', 'BLOCK', 'UNAVAILABLE', 'TIMEOUT')),
    CONSTRAINT ck_content_safety_check_duration CHECK (duration_ms >= 0),
    CONSTRAINT ck_content_safety_check_hash CHECK (safety_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_content_safety_check_request
    ON gateway.content_safety_check (request_id, created_at DESC);
CREATE INDEX ix_content_safety_check_result
    ON gateway.content_safety_check (direction, result, created_at DESC);

CREATE TABLE gateway.content_safety_event_outbox (
    id uuid PRIMARY KEY,
    event_type varchar(128) NOT NULL,
    target_service varchar(64) NOT NULL,
    event_fingerprint varchar(64) NOT NULL,
    payload jsonb NOT NULL,
    payload_hash varchar(64) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    claim_token uuid,
    claimed_until timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    delivered_at timestamptz,
    last_error varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_content_safety_outbox_fingerprint
        CHECK (event_fingerprint ~ '^[0-9a-f]{64}$' AND payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_content_safety_outbox_status
        CHECK (status IN ('PENDING', 'DELIVERING', 'DELIVERED', 'FAILED')),
    CONSTRAINT ck_content_safety_outbox_attempts CHECK (attempt_count >= 0)
);

CREATE UNIQUE INDEX uq_content_safety_event_outbox_fingerprint
    ON gateway.content_safety_event_outbox (event_fingerprint);
CREATE INDEX ix_content_safety_event_outbox_delivery
    ON gateway.content_safety_event_outbox (status, next_attempt_at, created_at);

CREATE TABLE gateway.safety_enforcement_cache (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    state varchar(16) NOT NULL,
    reason_code varchar(128),
    case_no varchar(64),
    event_version bigint NOT NULL,
    effective_at timestamptz NOT NULL,
    expires_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_safety_enforcement_cache_state
        CHECK (state IN ('FROZEN', 'BANNED', 'CLEARED')),
    CONSTRAINT ck_safety_enforcement_cache_version CHECK (event_version > 0)
);

CREATE UNIQUE INDEX uq_safety_enforcement_cache_owner
    ON gateway.safety_enforcement_cache (tenant_id, user_id);
CREATE INDEX ix_safety_enforcement_cache_expiry
    ON gateway.safety_enforcement_cache (expires_at)
    WHERE expires_at IS NOT NULL;

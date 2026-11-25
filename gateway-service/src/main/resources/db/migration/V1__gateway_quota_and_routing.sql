CREATE TABLE gateway.provider (
    id uuid PRIMARY KEY,
    code varchar(32) NOT NULL UNIQUE,
    name varchar(100) NOT NULL,
    provider_type varchar(32) NOT NULL,
    base_url varchar(1000),
    encrypted_credentials bytea,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_provider_type CHECK (provider_type IN ('DEEPSEEK', 'BAILIAN', 'MOCK'))
);

CREATE TABLE gateway.model (
    id uuid PRIMARY KEY,
    code varchar(128) NOT NULL UNIQUE,
    display_name varchar(128) NOT NULL,
    description text,
    capabilities jsonb NOT NULL DEFAULT '["TEXT"]'::jsonb,
    context_window integer NOT NULL,
    max_output_tokens integer NOT NULL,
    premium boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_model_limits CHECK (context_window > 0 AND max_output_tokens > 0)
);

CREATE TABLE gateway.channel (
    id uuid PRIMARY KEY,
    provider_id uuid NOT NULL REFERENCES gateway.provider(id) ON DELETE RESTRICT,
    code varchar(64) NOT NULL UNIQUE,
    name varchar(100) NOT NULL,
    environment varchar(20) NOT NULL DEFAULT 'LOCAL',
    base_url varchar(1000) NOT NULL,
    encrypted_api_key bytea,
    enabled boolean NOT NULL DEFAULT true,
    priority integer NOT NULL DEFAULT 0,
    weight integer NOT NULL DEFAULT 100,
    timeout_ms integer NOT NULL DEFAULT 60000,
    requests_per_minute integer,
    tokens_per_minute bigint,
    max_concurrency integer,
    last_health_status varchar(20) NOT NULL DEFAULT 'UNKNOWN',
    last_health_checked_at timestamptz,
    settings jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_channel_environment CHECK (environment IN ('LOCAL', 'SANDBOX', 'PRODUCTION')),
    CONSTRAINT ck_channel_weight CHECK (weight > 0),
    CONSTRAINT ck_channel_timeout CHECK (timeout_ms > 0),
    CONSTRAINT ck_channel_limits CHECK (
        (requests_per_minute IS NULL OR requests_per_minute > 0)
        AND (tokens_per_minute IS NULL OR tokens_per_minute > 0)
        AND (max_concurrency IS NULL OR max_concurrency > 0)
    ),
    CONSTRAINT ck_channel_health CHECK (last_health_status IN ('UNKNOWN', 'HEALTHY', 'DEGRADED', 'UNHEALTHY'))
);

CREATE INDEX ix_channel_route ON gateway.channel (enabled, priority DESC, weight DESC);

CREATE TABLE gateway.model_alias (
    id uuid PRIMARY KEY,
    model_id uuid NOT NULL REFERENCES gateway.model(id) ON DELETE CASCADE,
    alias varchar(128) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE gateway.channel_model (
    id uuid PRIMARY KEY,
    channel_id uuid NOT NULL REFERENCES gateway.channel(id) ON DELETE CASCADE,
    model_id uuid NOT NULL REFERENCES gateway.model(id) ON DELETE CASCADE,
    upstream_model varchar(128) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    priority_override integer,
    weight_override integer,
    capabilities jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (channel_id, model_id),
    CONSTRAINT ck_channel_model_weight CHECK (weight_override IS NULL OR weight_override > 0)
);

CREATE TABLE gateway.provider_model_price (
    id uuid PRIMARY KEY,
    provider_id uuid NOT NULL REFERENCES gateway.provider(id) ON DELETE CASCADE,
    model_id uuid NOT NULL REFERENCES gateway.model(id) ON DELETE CASCADE,
    input_price_per_million bigint NOT NULL,
    output_price_per_million bigint NOT NULL,
    currency char(3) NOT NULL DEFAULT 'CNY',
    internal_exchange_rate numeric(20, 8) NOT NULL DEFAULT 1,
    official_source varchar(1000),
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider_id, model_id, effective_from),
    CONSTRAINT ck_provider_price CHECK (
        input_price_per_million >= 0 AND output_price_per_million >= 0
        AND internal_exchange_rate > 0
        AND (effective_to IS NULL OR effective_to > effective_from)
    )
);

CREATE INDEX ix_provider_price_effective
    ON gateway.provider_model_price (provider_id, model_id, effective_from DESC);

CREATE TABLE gateway.package_instance (
    id uuid PRIMARY KEY,
    grant_event_id uuid NOT NULL UNIQUE,
    grant_request_id uuid NOT NULL UNIQUE,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    package_plan_version_id uuid NOT NULL,
    package_code varchar(64) NOT NULL,
    package_name varchar(100) NOT NULL,
    price_amount bigint NOT NULL,
    currency char(3) NOT NULL DEFAULT 'CNY',
    allowed_models jsonb NOT NULL DEFAULT '[]'::jsonb,
    total_token_quota bigint NOT NULL,
    reserved_tokens bigint NOT NULL DEFAULT 0,
    used_tokens bigint NOT NULL DEFAULT 0,
    max_api_keys integer NOT NULL,
    requests_per_minute integer NOT NULL,
    tokens_per_minute bigint NOT NULL,
    max_concurrency integer NOT NULL,
    duration_days integer NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'QUEUED',
    starts_at timestamptz,
    expires_at timestamptz,
    activated_at timestamptz,
    ended_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    lock_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_package_instance_values CHECK (
        price_amount >= 0 AND total_token_quota > 0 AND reserved_tokens >= 0 AND used_tokens >= 0
        AND reserved_tokens + used_tokens <= total_token_quota
        AND max_api_keys > 0 AND requests_per_minute > 0
        AND tokens_per_minute > 0 AND max_concurrency > 0 AND duration_days > 0
    ),
    CONSTRAINT ck_package_instance_status CHECK (status IN ('QUEUED', 'ACTIVE', 'EXHAUSTED', 'EXPIRED', 'REVOKED')),
    CONSTRAINT ck_package_instance_dates CHECK (
        (status = 'QUEUED' AND starts_at IS NULL AND expires_at IS NULL)
        OR status <> 'QUEUED'
    )
);

CREATE UNIQUE INDEX uq_active_package_per_user
    ON gateway.package_instance (user_id)
    WHERE status = 'ACTIVE';
CREATE INDEX ix_package_queue ON gateway.package_instance (user_id, status, created_at, id);

CREATE TABLE gateway.package_instance_model_quota (
    id uuid PRIMARY KEY,
    package_instance_id uuid NOT NULL REFERENCES gateway.package_instance(id) ON DELETE CASCADE,
    model_code varchar(128) NOT NULL,
    token_quota bigint NOT NULL,
    reserved_tokens bigint NOT NULL DEFAULT 0,
    used_tokens bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (package_instance_id, model_code),
    CONSTRAINT ck_model_quota_values CHECK (
        token_quota > 0 AND reserved_tokens >= 0 AND used_tokens >= 0
        AND reserved_tokens + used_tokens <= token_quota
    )
);

CREATE TABLE gateway.api_key (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    package_instance_id uuid NOT NULL REFERENCES gateway.package_instance(id) ON DELETE RESTRICT,
    name varchar(100) NOT NULL,
    key_prefix varchar(24) NOT NULL,
    key_hash varchar(128) NOT NULL UNIQUE,
    allowed_models jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    last_used_at timestamptz,
    expires_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_api_key_status CHECK (status IN ('ACTIVE', 'DISABLED', 'REVOKED'))
);

CREATE INDEX ix_api_key_user_status ON gateway.api_key (user_id, status);
CREATE INDEX ix_api_key_prefix ON gateway.api_key (key_prefix);

CREATE TABLE gateway.quota_reservation (
    id uuid PRIMARY KEY,
    request_id varchar(128) NOT NULL UNIQUE,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    api_key_id uuid NOT NULL REFERENCES gateway.api_key(id) ON DELETE RESTRICT,
    package_instance_id uuid NOT NULL REFERENCES gateway.package_instance(id) ON DELETE RESTRICT,
    model_code varchar(128) NOT NULL,
    reserved_tokens bigint NOT NULL,
    actual_input_tokens bigint,
    actual_output_tokens bigint,
    actual_total_tokens bigint,
    usage_estimated boolean NOT NULL DEFAULT false,
    status varchar(20) NOT NULL DEFAULT 'RESERVED',
    expires_at timestamptz NOT NULL,
    settled_at timestamptz,
    released_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_reservation_tokens CHECK (
        reserved_tokens > 0
        AND (actual_input_tokens IS NULL OR actual_input_tokens >= 0)
        AND (actual_output_tokens IS NULL OR actual_output_tokens >= 0)
        AND (actual_total_tokens IS NULL OR actual_total_tokens >= 0)
    ),
    CONSTRAINT ck_reservation_state CHECK (status IN ('RESERVED', 'SETTLED', 'RELEASED', 'EXPIRED'))
);

CREATE INDEX ix_reservation_recovery ON gateway.quota_reservation (status, expires_at);

CREATE TABLE gateway.quota_ledger (
    id uuid PRIMARY KEY,
    business_no varchar(128) NOT NULL UNIQUE,
    reservation_id uuid NOT NULL REFERENCES gateway.quota_reservation(id) ON DELETE RESTRICT,
    package_instance_id uuid NOT NULL REFERENCES gateway.package_instance(id) ON DELETE RESTRICT,
    model_code varchar(128) NOT NULL,
    entry_type varchar(24) NOT NULL,
    total_token_delta bigint NOT NULL,
    model_token_delta bigint,
    balance_after bigint NOT NULL,
    model_balance_after bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (reservation_id, entry_type),
    CONSTRAINT ck_ledger_type CHECK (entry_type IN ('RESERVE', 'SETTLE', 'RELEASE', 'EXPIRE')),
    CONSTRAINT ck_ledger_balances CHECK (balance_after >= 0 AND (model_balance_after IS NULL OR model_balance_after >= 0))
);

CREATE INDEX ix_ledger_package_created ON gateway.quota_ledger (package_instance_id, created_at DESC);

CREATE TABLE gateway.file_object (
    id uuid PRIMARY KEY,
    external_file_id varchar(128) NOT NULL UNIQUE,
    tenant_id uuid NOT NULL,
    owner_user_id uuid NOT NULL,
    object_key varchar(512) NOT NULL UNIQUE,
    original_filename varchar(255) NOT NULL,
    sha256 varchar(64) NOT NULL,
    mime_type varchar(255) NOT NULL,
    size_bytes bigint NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'UPLOADING',
    expires_at timestamptz NOT NULL,
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_file_size CHECK (size_bytes > 0),
    CONSTRAINT ck_file_status CHECK (status IN ('UPLOADING', 'READY', 'DELETING', 'DELETED', 'FAILED'))
);

CREATE INDEX ix_file_cleanup ON gateway.file_object (status, expires_at);

CREATE TABLE gateway.provider_file_binding (
    id uuid PRIMARY KEY,
    file_object_id uuid NOT NULL REFERENCES gateway.file_object(id) ON DELETE CASCADE,
    channel_id uuid NOT NULL REFERENCES gateway.channel(id) ON DELETE CASCADE,
    upstream_file_id varchar(256),
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (file_object_id, channel_id),
    CONSTRAINT ck_provider_file_status CHECK (status IN ('PENDING', 'UPLOADING', 'READY', 'FAILED', 'DELETED'))
);

CREATE TABLE gateway.usage_log (
    id uuid PRIMARY KEY,
    request_id varchar(128) NOT NULL UNIQUE,
    reservation_id uuid NOT NULL UNIQUE REFERENCES gateway.quota_reservation(id) ON DELETE RESTRICT,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    api_key_id uuid NOT NULL REFERENCES gateway.api_key(id) ON DELETE RESTRICT,
    package_instance_id uuid NOT NULL REFERENCES gateway.package_instance(id) ON DELETE RESTRICT,
    model_id uuid REFERENCES gateway.model(id) ON DELETE SET NULL,
    model_code varchar(128) NOT NULL,
    provider_id uuid REFERENCES gateway.provider(id) ON DELETE SET NULL,
    channel_id uuid REFERENCES gateway.channel(id) ON DELETE SET NULL,
    input_tokens bigint NOT NULL DEFAULT 0,
    output_tokens bigint NOT NULL DEFAULT 0,
    total_tokens bigint NOT NULL DEFAULT 0,
    usage_estimated boolean NOT NULL DEFAULT false,
    stream boolean NOT NULL DEFAULT false,
    first_token_ms bigint,
    duration_ms bigint NOT NULL,
    http_status integer NOT NULL,
    outcome varchar(20) NOT NULL,
    error_category varchar(64),
    error_summary varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_usage_tokens CHECK (input_tokens >= 0 AND output_tokens >= 0 AND total_tokens >= 0),
    CONSTRAINT ck_usage_duration CHECK (duration_ms >= 0 AND (first_token_ms IS NULL OR first_token_ms >= 0)),
    CONSTRAINT ck_usage_outcome CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'CANCELLED'))
);

CREATE INDEX ix_usage_user_created ON gateway.usage_log (user_id, created_at DESC);
CREATE INDEX ix_usage_channel_created ON gateway.usage_log (channel_id, created_at DESC);

CREATE TABLE gateway.provider_cost_record (
    id uuid PRIMARY KEY,
    usage_log_id uuid NOT NULL UNIQUE REFERENCES gateway.usage_log(id) ON DELETE RESTRICT,
    provider_id uuid NOT NULL REFERENCES gateway.provider(id) ON DELETE RESTRICT,
    channel_id uuid NOT NULL REFERENCES gateway.channel(id) ON DELETE RESTRICT,
    model_code varchar(128) NOT NULL,
    input_tokens bigint NOT NULL,
    output_tokens bigint NOT NULL,
    amount bigint NOT NULL,
    currency char(3) NOT NULL DEFAULT 'CNY',
    price_estimated boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_cost_values CHECK (input_tokens >= 0 AND output_tokens >= 0 AND amount >= 0)
);

CREATE TABLE gateway.consumed_event (
    event_id uuid PRIMARY KEY,
    event_type varchar(128) NOT NULL,
    payload_hash varchar(128) NOT NULL,
    consumed_at timestamptz NOT NULL DEFAULT now()
);

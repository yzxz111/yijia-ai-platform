SET LOCAL search_path = platform, public;

ALTER TABLE platform.wallet_usage_reservation
    DROP CONSTRAINT ck_wallet_usage_reservation_billing_mode,
    ADD CONSTRAINT ck_wallet_usage_reservation_billing_mode CHECK (
        billing_mode IN ('PACKAGE_ONLY', 'HYBRID', 'WALLET_ONLY', 'UNKNOWN'));

CREATE OR REPLACE FUNCTION platform.notification_payload_has_only_safe_keys(candidate jsonb)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
DECLARE
    entry record;
    normalized_key text;
BEGIN
    IF jsonb_typeof(candidate) = 'object' THEN
        FOR entry IN SELECT key, value FROM jsonb_each(candidate)
        LOOP
            normalized_key := lower(regexp_replace(entry.key, '[^a-zA-Z0-9]', '', 'g'));
            IF normalized_key = ANY(ARRAY[
                'phone', 'phonenumber', 'mobile', 'password', 'token', 'accesstoken',
                'refreshtoken', 'apikey', 'secret', 'prompt', 'response', 'content',
                'attachment', 'filebytes', 'ip', 'rawip', 'ipaddress', 'useragent',
                'device', 'deviceid']) THEN
                RETURN false;
            END IF;
            IF NOT platform.notification_payload_has_only_safe_keys(entry.value) THEN
                RETURN false;
            END IF;
        END LOOP;
    ELSIF jsonb_typeof(candidate) = 'array' THEN
        FOR entry IN SELECT value FROM jsonb_array_elements(candidate)
        LOOP
            IF NOT platform.notification_payload_has_only_safe_keys(entry.value) THEN
                RETURN false;
            END IF;
        END LOOP;
    END IF;
    RETURN true;
END;
$$;

CREATE TABLE platform.notification_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_key varchar(160) NOT NULL,
    category varchar(24) NOT NULL,
    event_type varchar(64) NOT NULL,
    tenant_id uuid,
    user_id uuid,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id varchar(160) NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    claim_token uuid,
    claimed_until timestamptz,
    last_error varchar(500),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_notification_event_category CHECK (
        category IN ('SECURITY', 'BILLING', 'USAGE', 'OPERATIONS', 'SUPPORT')),
    CONSTRAINT ck_notification_event_scope CHECK (
        user_id IS NULL OR tenant_id IS NOT NULL),
    CONSTRAINT fk_notification_event_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE SET NULL (user_id),
    CONSTRAINT ck_notification_event_payload CHECK (
        jsonb_typeof(payload) = 'object'
        AND platform.notification_payload_has_only_safe_keys(payload)),
    CONSTRAINT ck_notification_event_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED', 'DEAD')),
    CONSTRAINT ck_notification_event_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_notification_event_claim CHECK (
        (status = 'PROCESSING' AND claim_token IS NOT NULL AND claimed_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND claim_token IS NULL AND claimed_until IS NULL)),
    CONSTRAINT ck_notification_event_processed CHECK (
        status <> 'PROCESSED' OR processed_at IS NOT NULL),
    CONSTRAINT uq_notification_event_scope UNIQUE (tenant_id, user_id, id)
);

CREATE UNIQUE INDEX uq_notification_event_key
    ON platform.notification_event(event_key);
CREATE INDEX ix_notification_event_claim
    ON platform.notification_event(status, next_attempt_at, claimed_until, created_at)
    WHERE status IN ('PENDING', 'PROCESSING', 'FAILED');
CREATE INDEX ix_notification_event_user_created
    ON platform.notification_event(user_id, created_at DESC)
    WHERE user_id IS NOT NULL;

CREATE OR REPLACE FUNCTION platform.protect_notification_event_business_fields()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE'
        OR NEW.event_key IS DISTINCT FROM OLD.event_key
        OR NEW.category IS DISTINCT FROM OLD.category
        OR NEW.event_type IS DISTINCT FROM OLD.event_type
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.user_id IS DISTINCT FROM OLD.user_id
        OR NEW.aggregate_type IS DISTINCT FROM OLD.aggregate_type
        OR NEW.aggregate_id IS DISTINCT FROM OLD.aggregate_id
        OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'notification_event_business_fields_immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_notification_event_business_fields_immutable
BEFORE UPDATE OR DELETE ON platform.notification_event
FOR EACH ROW EXECUTE FUNCTION platform.protect_notification_event_business_fields();

CREATE TABLE platform.in_app_notification (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    title varchar(200) NOT NULL,
    summary varchar(1000) NOT NULL,
    read_at timestamptz,
    archived_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_in_app_notification_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_in_app_notification_event_scope FOREIGN KEY (tenant_id, user_id, event_id)
        REFERENCES platform.notification_event(tenant_id, user_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_in_app_notification_timestamps CHECK (
        read_at IS NULL OR read_at >= created_at)
);

CREATE UNIQUE INDEX uq_in_app_notification_event_user
    ON platform.in_app_notification(event_id, user_id);
CREATE INDEX ix_in_app_notification_user_created
    ON platform.in_app_notification(user_id, created_at DESC);
CREATE INDEX ix_in_app_notification_user_read
    ON platform.in_app_notification(user_id, read_at, created_at DESC);
CREATE INDEX ix_in_app_notification_user_archived
    ON platform.in_app_notification(user_id, archived_at, created_at DESC);

CREATE TABLE platform.notification_preference (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    category varchar(24) NOT NULL,
    channel varchar(16) NOT NULL,
    enabled boolean NOT NULL,
    quiet_start time NOT NULL DEFAULT TIME '22:00',
    quiet_end time NOT NULL DEFAULT TIME '08:00',
    timezone varchar(64) NOT NULL DEFAULT 'Asia/Shanghai',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_notification_preference_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_notification_preference_category CHECK (
        category IN ('SECURITY', 'BILLING', 'USAGE', 'OPERATIONS', 'SUPPORT')),
    CONSTRAINT ck_notification_preference_channel CHECK (channel IN ('IN_APP', 'SMS')),
    CONSTRAINT ck_notification_preference_security_sms CHECK (
        category <> 'SECURITY' OR channel <> 'SMS' OR enabled),
    CONSTRAINT ck_notification_preference_timezone CHECK (length(trim(timezone)) > 0)
);

CREATE UNIQUE INDEX uq_notification_preference_user_category_channel
    ON platform.notification_preference(user_id, category, channel);

INSERT INTO platform.notification_preference(
    tenant_id, user_id, category, channel, enabled, quiet_start, quiet_end, timezone)
SELECT app_user.tenant_id, app_user.id, category.code, channel.code,
    CASE
        WHEN channel.code = 'IN_APP' THEN true
        WHEN category.code = 'SECURITY' THEN true
        WHEN category.code = 'OPERATIONS' THEN false
        ELSE app_user.sms_notifications_enabled
    END,
    TIME '22:00', TIME '08:00', 'Asia/Shanghai'
FROM platform.app_user
CROSS JOIN (VALUES
    ('SECURITY'), ('BILLING'), ('USAGE'), ('OPERATIONS'), ('SUPPORT')) category(code)
CROSS JOIN (VALUES ('IN_APP'), ('SMS')) channel(code)
ON CONFLICT (user_id, category, channel) DO NOTHING;

CREATE TABLE platform.notification_channel_delivery (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    channel varchar(16) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    sms_delivery_id uuid REFERENCES platform.sms_delivery(id) ON DELETE RESTRICT,
    suppression_reason varchar(128),
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    claim_token uuid,
    claimed_until timestamptz,
    last_error varchar(500),
    delivered_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_notification_channel_delivery_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_notification_channel_delivery_event_scope FOREIGN KEY (tenant_id, user_id, event_id)
        REFERENCES platform.notification_event(tenant_id, user_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_notification_channel_delivery_channel CHECK (channel IN ('IN_APP', 'SMS')),
    CONSTRAINT ck_notification_channel_delivery_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'SENT', 'SUPPRESSED', 'FAILED', 'DEAD')),
    CONSTRAINT ck_notification_channel_delivery_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_notification_channel_delivery_claim CHECK (
        (status = 'PROCESSING' AND claim_token IS NOT NULL AND claimed_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND claim_token IS NULL AND claimed_until IS NULL)),
    CONSTRAINT ck_notification_channel_delivery_sms CHECK (
        sms_delivery_id IS NULL OR channel = 'SMS'),
    CONSTRAINT ck_notification_channel_delivery_suppression CHECK (
        (status = 'SUPPRESSED' AND suppression_reason IS NOT NULL)
        OR (status <> 'SUPPRESSED' AND suppression_reason IS NULL)),
    CONSTRAINT ck_notification_channel_delivery_delivered CHECK (
        status <> 'DELIVERED' OR delivered_at IS NOT NULL)
);

CREATE UNIQUE INDEX uq_notification_channel_delivery_event_user_channel
    ON platform.notification_channel_delivery(event_id, user_id, channel);
CREATE UNIQUE INDEX uq_notification_channel_delivery_sms_delivery
    ON platform.notification_channel_delivery(sms_delivery_id)
    WHERE sms_delivery_id IS NOT NULL;
CREATE INDEX ix_notification_channel_delivery_claim
    ON platform.notification_channel_delivery(status, next_attempt_at, claimed_until, created_at)
    WHERE status IN ('PENDING', 'PROCESSING', 'FAILED');

CREATE TABLE platform.notification_trigger_state (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    trigger_type varchar(32) NOT NULL,
    state_key varchar(160) NOT NULL DEFAULT 'DEFAULT',
    last_triggered_at timestamptz,
    next_eligible_at timestamptz,
    last_digest_date date,
    state jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_notification_trigger_state_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_notification_trigger_state_type CHECK (
        trigger_type IN ('LOW_BALANCE', 'DAILY_DIGEST')),
    CONSTRAINT ck_notification_trigger_state_payload CHECK (jsonb_typeof(state) = 'object')
);

CREATE UNIQUE INDEX uq_notification_trigger_state_user_type_key
    ON platform.notification_trigger_state(user_id, trigger_type, state_key);
CREATE INDEX ix_notification_trigger_state_eligible
    ON platform.notification_trigger_state(trigger_type, next_eligible_at);

CREATE TABLE platform.login_activity (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    ip_hmac char(64) NOT NULL,
    subnet_hmac char(64) NOT NULL,
    device_hash char(64) NOT NULL,
    user_agent_hash char(64) NOT NULL,
    region varchar(128),
    outcome varchar(20) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_login_activity_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_login_activity_hashes CHECK (
        ip_hmac ~ '^[0-9a-f]{64}$' AND subnet_hmac ~ '^[0-9a-f]{64}$'
        AND device_hash ~ '^[0-9a-f]{64}$' AND user_agent_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_login_activity_outcome CHECK (outcome IN ('SUCCEEDED', 'FAILED'))
);

CREATE INDEX ix_login_activity_user_occurred
    ON platform.login_activity(user_id, occurred_at DESC);
CREATE INDEX ix_login_activity_user_device
    ON platform.login_activity(user_id, device_hash, occurred_at DESC);

CREATE TABLE platform.trusted_login_device (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    ip_hmac char(64) NOT NULL,
    subnet_hmac char(64) NOT NULL,
    device_hash char(64) NOT NULL,
    user_agent_hash char(64) NOT NULL,
    region varchar(128),
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    trusted_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_trusted_login_device_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_trusted_login_device_hashes CHECK (
        ip_hmac ~ '^[0-9a-f]{64}$' AND subnet_hmac ~ '^[0-9a-f]{64}$'
        AND device_hash ~ '^[0-9a-f]{64}$' AND user_agent_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_trusted_login_device_seen CHECK (last_seen_at >= first_seen_at)
);

CREATE UNIQUE INDEX uq_trusted_login_device_user_device
    ON platform.trusted_login_device(user_id, device_hash);
CREATE INDEX ix_trusted_login_device_active
    ON platform.trusted_login_device(user_id, last_seen_at DESC)
    WHERE revoked_at IS NULL;

CREATE TABLE platform.notification_broadcast (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    broadcast_key varchar(160) NOT NULL UNIQUE,
    event_type varchar(64) NOT NULL DEFAULT 'PLATFORM_MAINTENANCE',
    title varchar(200) NOT NULL,
    summary varchar(1000) NOT NULL,
    maintain_time varchar(200) NOT NULL,
    provider_business_no varchar(128) NOT NULL,
    audience_type varchar(32) NOT NULL DEFAULT 'ALL_ACTIVE_USERS',
    tenant_id uuid,
    audience_filter jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(20) NOT NULL DEFAULT 'DRAFT',
    scheduled_at timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    paused_at timestamptz,
    job_claim_token uuid,
    job_claimed_until timestamptz,
    created_by uuid REFERENCES platform.app_user(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_notification_broadcast_audience CHECK (jsonb_typeof(audience_filter) = 'object'),
    CONSTRAINT ck_notification_broadcast_event_type CHECK (
        event_type = 'PLATFORM_MAINTENANCE'),
    CONSTRAINT ck_notification_broadcast_audience_type CHECK (
        audience_type IN ('ALL_ACTIVE_USERS', 'USER_IDS')),
    CONSTRAINT ck_notification_broadcast_required_text CHECK (
        length(trim(maintain_time)) > 0 AND length(trim(provider_business_no)) > 0),
    CONSTRAINT ck_notification_broadcast_status CHECK (
        status IN ('DRAFT', 'SCHEDULED', 'SENDING', 'COMPLETED', 'CANCELED')),
    CONSTRAINT ck_notification_broadcast_claim CHECK (
        (job_claim_token IS NULL AND job_claimed_until IS NULL)
        OR (job_claim_token IS NOT NULL AND job_claimed_until IS NOT NULL)),
    CONSTRAINT ck_notification_broadcast_completed CHECK (
        status <> 'COMPLETED' OR completed_at IS NOT NULL)
);

CREATE INDEX ix_notification_broadcast_claim
    ON platform.notification_broadcast(status, scheduled_at, job_claimed_until, created_at);

CREATE TABLE platform.notification_broadcast_recipient (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    broadcast_id uuid NOT NULL REFERENCES platform.notification_broadcast(id) ON DELETE RESTRICT,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    recipient_snapshot jsonb NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    notification_event_id uuid,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    job_claim_token uuid,
    job_claimed_until timestamptz,
    last_error varchar(500),
    processed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_notification_broadcast_recipient_snapshot CHECK (
        jsonb_typeof(recipient_snapshot) = 'object'),
    CONSTRAINT ck_notification_broadcast_recipient_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD', 'CANCELED')),
    CONSTRAINT ck_notification_broadcast_recipient_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_notification_broadcast_recipient_claim CHECK (
        (status = 'PROCESSING' AND job_claim_token IS NOT NULL AND job_claimed_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND job_claim_token IS NULL AND job_claimed_until IS NULL)),
    CONSTRAINT ck_notification_broadcast_recipient_processed CHECK (
        status NOT IN ('COMPLETED', 'DEAD', 'CANCELED') OR processed_at IS NOT NULL),
    CONSTRAINT fk_notification_broadcast_recipient_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_notification_broadcast_recipient_event_scope
        FOREIGN KEY (tenant_id, user_id, notification_event_id)
        REFERENCES platform.notification_event(tenant_id, user_id, id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_notification_broadcast_recipient_broadcast_user
    ON platform.notification_broadcast_recipient(broadcast_id, user_id);
CREATE INDEX ix_notification_broadcast_recipient_claim
    ON platform.notification_broadcast_recipient(
        broadcast_id, status, next_attempt_at, job_claimed_until, created_at);

CREATE OR REPLACE FUNCTION platform.protect_notification_broadcast_recipient_snapshot()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE'
        OR NEW.broadcast_id IS DISTINCT FROM OLD.broadcast_id
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.user_id IS DISTINCT FROM OLD.user_id
        OR NEW.recipient_snapshot IS DISTINCT FROM OLD.recipient_snapshot
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'notification_broadcast_recipient_snapshot_immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_notification_broadcast_recipient_snapshot_immutable
BEFORE UPDATE OR DELETE ON platform.notification_broadcast_recipient
FOR EACH ROW EXECUTE FUNCTION platform.protect_notification_broadcast_recipient_snapshot();

CREATE TABLE platform.support_ticket (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_number varchar(32) NOT NULL UNIQUE,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    assignee_user_id uuid,
    subject varchar(120) NOT NULL,
    description text NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'OPEN',
    resolution text,
    completion_business_no varchar(128),
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_support_ticket_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_support_ticket_assignee FOREIGN KEY (tenant_id, assignee_user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_support_ticket_status CHECK (
        status IN ('OPEN', 'PROCESSING', 'COMPLETED', 'CLOSED')),
    CONSTRAINT ck_support_ticket_text CHECK (
        length(trim(subject)) BETWEEN 1 AND 120
        AND length(trim(description)) BETWEEN 1 AND 5000),
    CONSTRAINT ck_support_ticket_completion CHECK (
        (status IN ('COMPLETED', 'CLOSED')
            AND resolution IS NOT NULL AND length(trim(resolution)) BETWEEN 1 AND 5000
            AND completion_business_no IS NOT NULL
            AND length(trim(completion_business_no)) BETWEEN 1 AND 128
            AND completed_at IS NOT NULL)
        OR (status IN ('OPEN', 'PROCESSING')
            AND resolution IS NULL AND completion_business_no IS NULL AND completed_at IS NULL)),
    CONSTRAINT uq_support_ticket_tenant_id UNIQUE (tenant_id, id)
);

CREATE INDEX ix_support_ticket_user_created
    ON platform.support_ticket(user_id, created_at DESC);
CREATE INDEX ix_support_ticket_assignee_status
    ON platform.support_ticket(assignee_user_id, status, updated_at DESC);
CREATE INDEX ix_support_ticket_tenant_created
    ON platform.support_ticket(tenant_id, created_at DESC);
CREATE INDEX ix_support_ticket_tenant_status_updated
    ON platform.support_ticket(tenant_id, status, updated_at DESC);

CREATE TABLE platform.support_ticket_comment (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    author_user_id uuid NOT NULL,
    author_role varchar(16) NOT NULL,
    body text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_support_ticket_comment_ticket FOREIGN KEY (tenant_id, ticket_id)
        REFERENCES platform.support_ticket(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_support_ticket_comment_author FOREIGN KEY (tenant_id, author_user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_support_ticket_comment_author_role CHECK (author_role IN ('USER', 'ADMIN')),
    CONSTRAINT ck_support_ticket_comment_body CHECK (length(trim(body)) BETWEEN 1 AND 5000)
);

CREATE INDEX ix_support_ticket_comment_ticket_created
    ON platform.support_ticket_comment(ticket_id, created_at);

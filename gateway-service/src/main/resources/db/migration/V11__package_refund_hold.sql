CREATE TABLE gateway.package_refund_hold (
    id uuid PRIMARY KEY,
    business_no varchar(128) NOT NULL UNIQUE,
    package_instance_id uuid NOT NULL REFERENCES gateway.package_instance(id) ON DELETE RESTRICT,
    grant_request_id uuid NOT NULL,
    original_status varchar(20) NOT NULL,
    status varchar(20) NOT NULL,
    payload_hash varchar(64) NOT NULL,
    total_tokens bigint,
    used_tokens bigint,
    remaining_tokens bigint,
    package_expires_at timestamptz,
    snapshot_at timestamptz,
    released_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_refund_hold_status CHECK (status IN ('WAITING', 'READY', 'RELEASED', 'REVOKED')),
    CONSTRAINT ck_refund_hold_tokens CHECK (
        (total_tokens IS NULL AND used_tokens IS NULL AND remaining_tokens IS NULL)
        OR (total_tokens > 0 AND used_tokens >= 0 AND remaining_tokens >= 0
            AND used_tokens + remaining_tokens = total_tokens))
);

CREATE UNIQUE INDEX uq_refund_hold_open_per_package
    ON gateway.package_refund_hold(package_instance_id)
    WHERE status IN ('WAITING', 'READY');

CREATE INDEX ix_reservation_package_status_expiry
    ON gateway.quota_reservation(package_instance_id, status, expires_at);

CREATE TABLE gateway.package_lifecycle_event (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL UNIQUE,
    business_no varchar(128) NOT NULL UNIQUE,
    package_instance_id uuid NOT NULL REFERENCES gateway.package_instance(id) ON DELETE RESTRICT,
    event_type varchar(32) NOT NULL,
    source_business_no varchar(128) NOT NULL,
    payload_hash varchar(64) NOT NULL,
    activated_package_instance_id uuid REFERENCES gateway.package_instance(id) ON DELETE RESTRICT,
    activated_package_status varchar(20),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_package_lifecycle_type CHECK (
        event_type IN ('REFUND_HOLD', 'REFUND_RELEASE', 'REFUND_REVOKE'))
);

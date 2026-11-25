ALTER TABLE platform.content_safety_appeal
    DROP CONSTRAINT ck_content_safety_appeal_status,
    ADD CONSTRAINT ck_content_safety_appeal_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'));

CREATE TABLE platform.support_ticket_attachment (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    comment_id uuid,
    uploader_user_id uuid NOT NULL,
    filename varchar(255) NOT NULL,
    content_type varchar(128) NOT NULL,
    size_bytes integer NOT NULL,
    content bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_support_ticket_attachment_ticket FOREIGN KEY (tenant_id, ticket_id)
        REFERENCES platform.support_ticket(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_support_ticket_attachment_comment FOREIGN KEY (comment_id)
        REFERENCES platform.support_ticket_comment(id) ON DELETE CASCADE,
    CONSTRAINT fk_support_ticket_attachment_uploader FOREIGN KEY (uploader_user_id)
        REFERENCES platform.app_user(id) ON DELETE RESTRICT,
    CONSTRAINT ck_support_ticket_attachment_filename
        CHECK (length(trim(filename)) BETWEEN 1 AND 255),
    CONSTRAINT ck_support_ticket_attachment_content_type
        CHECK (length(trim(content_type)) BETWEEN 1 AND 128),
    CONSTRAINT ck_support_ticket_attachment_size
        CHECK (size_bytes BETWEEN 1 AND 5242880 AND octet_length(content) = size_bytes)
);

CREATE INDEX ix_support_ticket_attachment_ticket_created
    ON platform.support_ticket_attachment(ticket_id, created_at, id);
CREATE INDEX ix_support_ticket_attachment_comment_created
    ON platform.support_ticket_attachment(comment_id, created_at, id)
    WHERE comment_id IS NOT NULL;

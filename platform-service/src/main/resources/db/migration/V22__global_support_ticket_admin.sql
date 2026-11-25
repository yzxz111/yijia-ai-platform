ALTER TABLE platform.support_ticket
    DROP CONSTRAINT fk_support_ticket_assignee,
    ADD CONSTRAINT fk_support_ticket_assignee
        FOREIGN KEY (assignee_user_id)
        REFERENCES platform.app_user(id) ON DELETE RESTRICT;

ALTER TABLE platform.support_ticket_comment
    DROP CONSTRAINT fk_support_ticket_comment_author,
    ADD CONSTRAINT fk_support_ticket_comment_author
        FOREIGN KEY (author_user_id)
        REFERENCES platform.app_user(id) ON DELETE RESTRICT;

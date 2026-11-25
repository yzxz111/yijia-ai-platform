CREATE INDEX ix_app_user_created_analytics
    ON platform.app_user (created_at DESC) INCLUDE (status);

CREATE INDEX ix_payment_succeeded_paid_analytics
    ON platform.payment_transaction (paid_at DESC) INCLUDE (amount)
    WHERE status = 'SUCCEEDED' AND paid_at IS NOT NULL;

CREATE INDEX ix_refund_completed_analytics
    ON platform.refund (completed_at DESC) INCLUDE (amount)
    WHERE status = 'COMPLETED' AND completed_at IS NOT NULL;

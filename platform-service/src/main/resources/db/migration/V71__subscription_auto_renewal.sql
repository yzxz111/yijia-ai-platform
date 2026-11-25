-- 连续包月与自动续费（订阅制）。
--
-- 设计依据：docs/superpowers/specs/2026-09-22-subscription-auto-renewal-design.md
--
-- 本迁移只新增表与约束、只增不改既有枚举取值；既有的
-- package_purchase_policy（MAX_PURCHASES_PER_PACKAGE = 3）与一次性购买链路一字不动。
-- 续费走新的平行策略 SubscriptionRenewalPolicyService。

-- ---------------------------------------------------------------------------
-- 1. platform.subscription —— 一行 = 一份连续包月协议（用户 × 套餐计划）
-- ---------------------------------------------------------------------------

CREATE TABLE platform.subscription (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- 绑「计划」而不是「版本」：版本会随发布演进（V61/V63/V64 都是替换版本），
    -- 成交版本在执行续费时取当时的 PUBLISHED 版本。
    package_plan_id uuid NOT NULL,
    auto_renew boolean NOT NULL DEFAULT true,
    cancel_at_period_end boolean NOT NULL DEFAULT false,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    current_period_start timestamptz NOT NULL,
    -- 注意：这是「订阅账期」的边界，不是套餐实例的 expires_at。
    -- 续期实例以 QUEUED 落库、expires_at 为 NULL，有效期从激活时刻才起算
    -- （设计 §5.5 / §5.7.1 H2/H4），因此本列由 durationDays 推进并由激活回传校正。
    current_period_end timestamptz NOT NULL,
    -- 调度水位：唯一的扫描口径，不允许为空（设计 §5.1）。
    next_charge_at timestamptz NOT NULL,
    -- 调度租约。**放在订阅行而不是账期行**是刻意的：一个订阅在任何时刻最多只有一个
    -- 在途扣款，把它做成订阅行上的单一租约，让「同一订阅被两个调度实例同时扣款」
    -- 在结构上不可能发生，而不是靠应用层记得去查「有没有未结账期」。
    claim_token uuid,
    claimed_until timestamptz,
    grace_until timestamptz,
    consecutive_failures integer NOT NULL DEFAULT 0,
    renewal_count integer NOT NULL DEFAULT 0,
    -- 上一次成功扣款的金额（分）。用于 §7.3 的涨价检测：价格上调时不得静默按新价扣款，
    -- 必须先取得用户确认。为 NULL 表示尚未扣过款（首期由开通流程完成）。
    last_charged_amount bigint,
    -- 正常跳过不写 subscription_cycle 行，原因只能挂在订阅上，
    -- 否则后台无从解释「这个月为什么没扣款」（设计 §7.5）。
    last_skip_reason varchar(64),
    last_evaluated_at timestamptz,
    canceled_at timestamptz,
    expired_at timestamptz,
    row_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_subscription_user_scope FOREIGN KEY (tenant_id, user_id)
        REFERENCES platform.app_user(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_plan FOREIGN KEY (package_plan_id)
        REFERENCES platform.package_plan(id) ON DELETE RESTRICT,
    CONSTRAINT ck_subscription_status CHECK (
        status IN ('ACTIVE', 'PAST_DUE', 'CANCELED', 'EXPIRED', 'TERMINATED')),
    CONSTRAINT ck_subscription_period CHECK (current_period_end > current_period_start),
    CONSTRAINT ck_subscription_counters CHECK (
        consecutive_failures >= 0 AND renewal_count >= 0 AND row_version >= 0),
    CONSTRAINT ck_subscription_last_charged CHECK (
        last_charged_amount IS NULL OR last_charged_amount >= 0),
    -- 持有租约与未持有租约都必须是完整的两种状态之一，让「忘了清租约」直接违约。
    CONSTRAINT ck_subscription_claim CHECK (
        (claim_token IS NULL AND claimed_until IS NULL)
        OR (claim_token IS NOT NULL AND claimed_until IS NOT NULL)),
    -- 解约是「当期用满不再扣」，取消标记与关闭自动续费必须同向，
    -- 否则会出现 auto_renew=true 但已请求取消的自相矛盾状态。
    CONSTRAINT ck_subscription_cancel_marker CHECK (NOT cancel_at_period_end OR NOT auto_renew)
);

-- 「一个用户一个计划至多一份活跃订阅」由数据库兜底，不靠应用层判断。
CREATE UNIQUE INDEX uq_subscription_active_plan
    ON platform.subscription (tenant_id, user_id, package_plan_id)
    WHERE status IN ('ACTIVE', 'PAST_DUE');

-- 调度扫描索引，与 ix_order_pending_expiry 同构。
CREATE INDEX ix_subscription_due
    ON platform.subscription (next_charge_at, id)
    WHERE status IN ('ACTIVE', 'PAST_DUE') AND auto_renew;

COMMENT ON TABLE platform.subscription IS
    '连续包月订阅协议。一行 = 一个用户对一个套餐计划的一份持续订阅。只新增，不影响一次性购买链路。';
COMMENT ON COLUMN platform.subscription.package_plan_id IS
    '绑计划不绑版本：续费时取当时的 PUBLISHED 版本，因此计划换版本不需要迁移订阅。';
COMMENT ON COLUMN platform.subscription.current_period_end IS
    '订阅账期边界（非套餐实例 expires_at）。续期实例以 QUEUED 落库且 expires_at 为 NULL，'
    '有效期从激活时刻起算，故本列由 durationDays 推进、由激活回传向后校正。';
COMMENT ON COLUMN platform.subscription.last_skip_reason IS
    '最近一次正常跳过原因（不是失败）。跳过时不写 subscription_cycle 行，原因只能挂在这里。';

-- ---------------------------------------------------------------------------
-- 2. platform.subscription_cycle —— 一行 = 一个账期的扣款尝试
-- ---------------------------------------------------------------------------

CREATE TABLE platform.subscription_cycle (
    id uuid PRIMARY KEY,
    subscription_id uuid NOT NULL,
    period_index integer NOT NULL,
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    -- 确定性幂等键 "renewal:<subscription_id>:<period_index>"。
    -- 它同时充当下单的 Idempotency-Key 与账本幂等键，因此调度器重复执行
    -- 不可能重复扣款 —— 由既有 uq_order_user_idempotency 与
    -- uq_balance_ledger_idempotency 在数据库层保证，不依赖任务不重复跑。
    business_key varchar(128) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING',
    amount bigint NOT NULL,
    currency char(3) NOT NULL DEFAULT 'CNY',
    package_plan_version_id uuid,
    order_id uuid,
    order_no varchar(64),
    grant_request_id uuid,
    -- 重试计数留在账期行上（订阅行持有租约，账期行记录这次尝试的历史）。
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    -- 只记失败，不记跳过（跳过见 subscription.last_skip_reason）。
    failure_reason varchar(64),
    -- 不得写入任何凭据、用户内容或上游原文。
    last_error varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    charged_at timestamptz,
    CONSTRAINT fk_subscription_cycle_subscription FOREIGN KEY (subscription_id)
        REFERENCES platform.subscription(id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_cycle_version FOREIGN KEY (package_plan_version_id)
        REFERENCES platform.package_plan_version(id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_cycle_order FOREIGN KEY (order_id)
        REFERENCES platform.customer_order(id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_cycle_grant FOREIGN KEY (grant_request_id)
        REFERENCES platform.grant_request(id) ON DELETE RESTRICT,
    -- 账期行没有租约列：租约在 subscription 行上（见 ck_subscription_claim）。
    -- 一个订阅至多一个在途扣款，因此这里也不需要 CHARGING 这种「正在扣」的状态。
    CONSTRAINT ck_subscription_cycle_status CHECK (
        status IN ('PENDING', 'CHARGED', 'FAILED', 'GRANTED')),
    CONSTRAINT ck_subscription_cycle_period CHECK (period_end > period_start),
    CONSTRAINT ck_subscription_cycle_index CHECK (period_index > 0),
    CONSTRAINT ck_subscription_cycle_amount CHECK (amount >= 0),
    CONSTRAINT ck_subscription_cycle_counter CHECK (attempt_count >= 0)
);

CREATE UNIQUE INDEX uq_subscription_cycle_business_key
    ON platform.subscription_cycle (business_key);
CREATE UNIQUE INDEX uq_subscription_cycle_period
    ON platform.subscription_cycle (subscription_id, period_index);
CREATE INDEX ix_subscription_cycle_subscription
    ON platform.subscription_cycle (subscription_id, period_index DESC);

COMMENT ON TABLE platform.subscription_cycle IS
    '订阅账期的扣款尝试记录。只追加、状态可更新，不删除；失败原因写入 failure_reason。';
COMMENT ON COLUMN platform.subscription_cycle.business_key IS
    '确定性幂等键 "renewal:<subscription_id>:<period_index>"，同时用作下单 Idempotency-Key 与账本幂等键。';
COMMENT ON COLUMN platform.subscription_cycle.amount IS
    '分（minor unit）。取自下单时的 package_plan_version.price_amount 快照。';

-- ---------------------------------------------------------------------------
-- 3. 通知分类新增 SUBSCRIPTION
-- ---------------------------------------------------------------------------
--
-- 为什么需要新分类而不是复用 BILLING：扣款前通知按合规要求不可被用户关闭，
-- 而 NotificationTypeRegistry 只允许 SECURITY 分类使用 mandatorySms
-- （NotificationTypeRegistry.java:134-136 抛 mandatory_sms_requires_security_category）。
-- 放宽该断言会让「BILLING 也能强制短信」成为全局能力，日后必然造成过度打扰与
-- 短信成本失控；新增分类把该能力限制在自动续费这一件事上。
--
-- 只增不改：既有 5 个取值全部保留。

ALTER TABLE platform.notification_event
    DROP CONSTRAINT ck_notification_event_category,
    ADD CONSTRAINT ck_notification_event_category CHECK (
        category IN ('SECURITY', 'BILLING', 'USAGE', 'OPERATIONS', 'SUPPORT', 'SUBSCRIPTION'));

ALTER TABLE platform.notification_preference
    DROP CONSTRAINT ck_notification_preference_category,
    ADD CONSTRAINT ck_notification_preference_category CHECK (
        category IN ('SECURITY', 'BILLING', 'USAGE', 'OPERATIONS', 'SUPPORT', 'SUBSCRIPTION'));

-- 为存量用户回填 SUBSCRIPTION 偏好。
--
-- 这一步是必需的，不是可选的：NotificationSmsDispatcher.preference() 在偏好行缺失时
-- 回退为 Preference(globalEnabled=false, categoryEnabled=null)（:126-139），
-- 而 enabled() 对非 OPERATIONS 分类返回 globalEnabled && categoryEnabled ——
-- 缺行即「不发短信」。若不回填，扣款预告会被静默抑制，直接违反「扣款前通知」要求。
--
-- 默认值与 V21 的回填口径一致（IN_APP 恒开；其余渠道取用户的 sms_notifications_enabled），
-- 这里同样不为 SUBSCRIPTION 强制打开 SMS：真正的强制由 mandatorySms 在派发时完成，
-- 偏好行只用于承载免打扰时段与用户的显式开关。
INSERT INTO platform.notification_preference(
    tenant_id, user_id, category, channel, enabled, quiet_start, quiet_end, timezone)
SELECT app_user.tenant_id, app_user.id, 'SUBSCRIPTION', channel.code,
    CASE WHEN channel.code = 'IN_APP' THEN true ELSE app_user.sms_notifications_enabled END,
    TIME '22:00', TIME '08:00', 'Asia/Shanghai'
FROM platform.app_user
CROSS JOIN (VALUES ('IN_APP'), ('SMS')) channel(code)
ON CONFLICT (user_id, category, channel) DO NOTHING;

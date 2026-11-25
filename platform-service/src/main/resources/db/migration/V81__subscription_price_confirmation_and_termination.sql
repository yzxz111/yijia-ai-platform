-- 订阅的两项收尾能力：
--
--   1. 退款成功后终止订阅（设计 §10）。此前退款被批准后订阅仍是 ACTIVE/auto_renew=true，
--      用户既拿到退款又会被继续扣下一期——设计第 447-449 行明确禁止。
--   2. 涨价确认路径（设计 §7.3）。此前只有「暂缓」那半：检测到涨价就停手，
--      但没有任何确认入口，且 markChargeDeferred 不推进水位，于是每轮都再暂缓一次，
--      订阅永久卡在「ACTIVE 但永不扣款」——用户以为在续费、实际早停了，且无人收到告警。
--
-- 版本号选用 V81 而不是 V73：platform 与 gateway 各自持有独立的 flyway_schema_history，
-- 因此**只需要平台侧唯一**；但 V73~V80 已分别被 gateway 侧占用，而本仓库的常态是两侧迁移
-- 会一起合并，所以刻意跳过那一段，避免重演 V70 那种「合到同一条分支后校验和冲突」。

-- ---------------------------------------------------------------------------
-- 1. 终止时刻（与既有的 canceled_at / expired_at 对称）
-- ---------------------------------------------------------------------------
--
-- 此前 status 可以是 TERMINATED（CHECK 早已允许），却没有记录**何时**终止。
-- 排查「这单为什么不再续费」时需要它。
ALTER TABLE platform.subscription
    ADD COLUMN terminated_at timestamptz;

-- ---------------------------------------------------------------------------
-- 2. 待用户确认的续费价格（设计 §7.3）
-- ---------------------------------------------------------------------------
--
-- pending_price_amount 是**发现涨价时的报价**。它必须落库而不是只发通知，原因有两个：
--   a) 用户确认时要能校验「你确认的价格」与「当前已发布价」是否仍然是同一个，
--      否则会出现「用户确认 A 价、系统扣 B 价」；
--   b) 运维要能回答「这个订阅卡在什么价格上、卡了多久」。
--
-- detected_at 同时是超时兜底的计时起点：长期未确认必须终止订阅，不能让订阅无限期占着
-- 「ACTIVE 但永不扣款」的状态。
ALTER TABLE platform.subscription
    ADD COLUMN pending_price_amount bigint;

ALTER TABLE platform.subscription
    ADD COLUMN price_change_detected_at timestamptz;

-- 用户确认接受当前价之后写入，作为「本订阅的价格异议已了结」的取证。
ALTER TABLE platform.subscription
    ADD COLUMN price_confirmed_at timestamptz;

-- pending_price_amount 只在「等待确认」期间有值。
--
-- 用 CHECK 把「金额与发现时刻必须成对」钉死：只有金额没有时刻会让超时兜底算不出起点，
-- 只有时刻没有金额则等于没有报价可确认——这两种半成品状态都是 bug，不该能写进库里。
ALTER TABLE platform.subscription
    ADD CONSTRAINT ck_subscription_pending_price_paired
        CHECK ((pending_price_amount IS NULL) = (price_change_detected_at IS NULL));

-- 报价必须是正数：零价或负价意味着取到的版本不该走续费（由策略层 PLAN_UNAVAILABLE 处理），
-- 真写进来就是上游算错了。
ALTER TABLE platform.subscription
    ADD CONSTRAINT ck_subscription_pending_price_positive
        CHECK (pending_price_amount IS NULL OR pending_price_amount > 0);

-- ---------------------------------------------------------------------------
-- 3. 让「待确认」可被运维发现
-- ---------------------------------------------------------------------------
--
-- 超时兜底要按 detected_at 扫描「等待确认过久」的订阅。部分索引只覆盖真正待确认的行，
-- 在正常运营下这个集合很小（甚至为空），因此代价可以忽略。
CREATE INDEX ix_subscription_awaiting_price_confirmation
    ON platform.subscription (price_change_detected_at, id)
    WHERE pending_price_amount IS NOT NULL;

COMMENT ON COLUMN platform.subscription.pending_price_amount IS
    '等待用户确认的续费报价（分）。非空即表示该订阅因涨价处于暂缓状态（设计 §7.3）。';
COMMENT ON COLUMN platform.subscription.price_change_detected_at IS
    '发现涨价的时刻。既是通知取证的时点，也是「长期未确认则终止」的计时起点。';
COMMENT ON COLUMN platform.subscription.price_confirmed_at IS
    '用户确认接受调整后价格（或确认无需调整）的时刻。';
COMMENT ON COLUMN platform.subscription.terminated_at IS
    '终止时刻。退款成功终止、涨价长期未确认终止都会写它，具体原因见 last_skip_reason。';

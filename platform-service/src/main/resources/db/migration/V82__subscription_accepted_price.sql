-- 涨价确认的**缺失一半**：确认必须改变「会不会再次暂缓」的判定依据。
--
-- V81 加了 pending 报价与确认入口，但实测发现确认之后调度**又立刻暂缓了一次**：
-- 策略拿「已发布价」与 `last_charged_amount` 比较，而确认并没有改变后者，于是
--   检测到涨价 → 暂缓 → 用户确认 → 水位推进 → 下一轮又检测到同一个涨价 → 再暂缓
-- 变成死循环，用户点了确认却永远等不到扣款。
--
-- 修法不能是「确认时改写 last_charged_amount」：那一列的语义是**上次实际扣款金额**，
-- 后台对账、失败文案与退款口径都读它，把「用户同意支付」混进去会让它同时表示两件事。
-- 因此单列一列，语义精确：**用户已明确同意支付的续费价格**。
--
-- 策略侧的比较基准随之变为 max(last_charged_amount, accepted_price_amount)：
--   a) 正常续费后两者一致，行为与从前完全相同（本列默认为 NULL，取 max 即取 last_charged_amount）；
--   b) 用户确认涨价后，基准抬到已接受价，因此不再误判为「又涨价了」；
--   c) 若管理员在用户确认之后**再**涨价，基准低于新价，仍会正确地再次要求确认——
--      这是必须保留的性质，否则确认一次就等于永久放弃价格保护。
ALTER TABLE platform.subscription
    ADD COLUMN accepted_price_amount bigint;

-- 与 pending 报价同样的取值约束：它表示一个真实价格，零或负值意味着上游算错了。
ALTER TABLE platform.subscription
    ADD CONSTRAINT ck_subscription_accepted_price_positive
        CHECK (accepted_price_amount IS NULL OR accepted_price_amount > 0);

COMMENT ON COLUMN platform.subscription.accepted_price_amount IS
    '用户已明确同意支付的续费价格（分）。涨价确认时写入，是价格比较基准的一部分；'
    '与 last_charged_amount 分开是因为后者只能表示「实际扣过多少」。';

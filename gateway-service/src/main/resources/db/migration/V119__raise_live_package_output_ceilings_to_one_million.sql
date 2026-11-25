-- ---------------------------------------------------------------------------
-- 把在售套餐实例的单次输出上限抬到 100 万
--
-- 为什么数据层还要动一次：客户端能要到多少输出，是四层取最小——
--
--     min(客户端 max_tokens, 套餐实例的 max_output_tokens, 模型的 max_output_tokens,
--         系统上限)
--
-- V117 把模型那一层抬到了 100 万，网关的系统上限也在同一次发布里抬到 100 万，
-- 但**已经发放的套餐实例**把它记在自己的行里：V2/V45/V89 在激活时把套餐出售的上限写进
-- gateway.package_instance.max_output_tokens，此后没有任何代码会再改它。线上 ACTIVE
-- 实例当前卖的是 2 000 / 4 096 / 8 192，因此不改这张表，客户端无论怎么要都被压在
-- 2 000～8 192——"模型改成 100 万"对已购用户一次也不会生效。
--
-- 为什么不是全部行：
--   * 只改 QUEUED / ACTIVE。EXPIRED / REVOKED 的实例不能再服务请求，改它们等于篡改
--     "这位客户当初买的是什么"的记录（V79 已经按同样的理由把这类行留在原地）。
--   * NULL 保持 NULL。NULL 的含义是"这个套餐方案不出售输出上限"，运行时它会让位给
--     模型与系统上限（现在都是 100 万）。把它写成 100 万等于凭空给这份合约加了一个
--     它从未声称的数字。
--   * 只写真正需要抬的行：已经不低于 100 万的实例保持原值与 updated_at。
--
-- 发布中的套餐方案（platform.package_plan_version）**不在本迁移范围内**：已发布版本由
-- trg_package_version_immutable 钉死，改它的 max_output_tokens 会被拒绝。新购用户拿到的
-- 上限来自套餐目录，要同样是 100 万，需要在控制台发布一个新版本——那是产品动作，
-- 不是数据修补。
-- ---------------------------------------------------------------------------

UPDATE gateway.package_instance instance
   SET max_output_tokens = 1000000,
       updated_at = now()
 WHERE instance.status IN ('QUEUED', 'ACTIVE')
   AND instance.max_output_tokens IS NOT NULL
   AND instance.max_output_tokens < 1000000;

-- ---------------------------------------------------------------------------
-- 断言：没有任何一个在服务中的套餐实例还压着 100 万以下的上限
--
-- 这条迁移的全部意义就是"套餐这一层不再封顶"。留一行在 4 096，客户端看到的就是
-- 两套说法，而这种不一致不会报错，只会让排查照着一个不存在的系统上限去找问题。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    below integer;
BEGIN
    SELECT count(*) INTO below
    FROM gateway.package_instance
    WHERE status IN ('QUEUED', 'ACTIVE')
      AND max_output_tokens IS NOT NULL
      AND max_output_tokens < 1000000;

    IF below > 0 THEN
        RAISE EXCEPTION 'live_package_instance_still_caps_output_below_one_million:count=%', below;
    END IF;
END $$;

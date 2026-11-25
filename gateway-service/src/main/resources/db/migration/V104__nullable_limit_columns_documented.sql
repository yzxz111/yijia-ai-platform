-- V103: 把「限流列可以为空」这件事写在表定义上
--
-- 背景（2026-10-09 生产故障）：V102 插入 chat-standard / chat-deep 时没有写
-- requests_per_minute 与 max_concurrency，两行为 NULL；管理端列表把它们读成
-- 原始类型 int，拆箱抛 NPE，整个 /console/gateway/admin/models 变成 500 ——
-- 后台看起来「模型全没了」。代码侧已经修好（GatewayAdminRepository.limitOr
-- 容忍 NULL，并有一条回归测试钉住），这条迁移只补文档，让下一个写 INSERT 的人
-- 不必再读一遍那次事故。
--
-- 为什么**不**把 NULL 回填成 60/10、也不加 NOT NULL：
-- 这两列的 NULL 是有语义的 —— 它表示「这一层不设限」。客户端路径的有效限流是
-- 各层 COALESCE 之后取**最小值**（DatabaseGatewayCatalog 的 COALESCE 链 +
-- RequestLimitService.Thresholds 的 Math.min），而生产上现有 7 条渠道、
-- 6 个模型的限流高于 60/10。把 NULL 一律回填成 60/10 会静默收紧那些行 ——
-- 那是借一次「格式修复」改掉线上限流，不能混在 bug 修复里做。
-- 需要显式限流的行由控制台写入（创建/编辑表单始终带这两个值，且要求 > 0）。

COMMENT ON COLUMN gateway.model.requests_per_minute IS
    '模型级每分钟请求上限。NULL = 该层不设限：有效值取套餐/key/用户/渠道/模型各层的最小值，'
    '全为 NULL 时兜底 60。管理端视图对 NULL 显示 60，但执行路径读的是原始列。';

COMMENT ON COLUMN gateway.model.max_concurrency IS
    '模型级最大并发。NULL = 该层不设限：有效值取各层最小值，全为 NULL 时兜底 10。'
    '管理端视图对 NULL 显示 10。';

COMMENT ON COLUMN gateway.channel.requests_per_minute IS
    '渠道级每分钟请求上限。NULL = 该层不设限：有效值取各层最小值（候选路由之间再取最小值），'
    '全为 NULL 时兜底 60。管理端视图对 NULL 显示 60。';

COMMENT ON COLUMN gateway.channel.max_concurrency IS
    '渠道级最大并发。NULL = 该层不设限：有效值取各层最小值（候选路由之间再取最小值），'
    '全为 NULL 时兜底 10。管理端视图对 NULL 显示 10。';

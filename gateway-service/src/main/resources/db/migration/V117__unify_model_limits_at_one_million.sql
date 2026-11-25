-- 把所有模型的上下文窗口与单次最大输出统一声明为 100 万 token。
--
-- 为什么是数据变更而不是代码变更：模型目录就是 gateway.model 里的行。网关每次请求
-- 都按行读取（ModelCatalogRepository.findEntitledModels，进程内没有缓存），所以这个
-- 数字写进表里就等于写进行为里。种子迁移（V4 / V13 / V40 / V97 / …）只做 INSERT，
-- 重复部署不会把运维手工改过的值写回去，但**新建的库**会重新种回旧值，因此这次调整
-- 必须在迁移链里留下自己的一条记录，而不是只存在于某一台服务器的数据库里。
--
-- 两列的含义并不对称，这正是要写清楚的地方：
--
--   * context_window 是**对客户端的能力声明**。网关不用它做准入——请求的提示词长度
--     没有任何按它校验的路径——它只经由 GET /v1/models 和后台模型页展示。
--
--   * max_output_tokens 参与真实的准入算术。ReservationCeiling.ceiling() 取
--         min(客户端 max_tokens, 套餐出售上限, 模型上限, 系统上限 65,536)
--     作为本次请求的输出上界：既用它改写发给上游的 max_tokens，也用它给钱包做
--     最坏情况预留。所以本迁移会把低于系统上限的模型（chat-* 的 8,192、
--     vision-tianyi 的 8,192 等）抬到 65,536，但**不会**让任何一次请求真的产出
--     100 万 token —— SYSTEM_MAX_OUTPUT_TOKENS 是代码里的常量，不是这张表。
--
--     这个取舍是有意的：目录宣称 100 万、实际封顶 65,536，与"把常量一起抬到 100 万"
--     相比，后者会让网关对 DeepSeek / 百炼 / 天翼的每次请求都发 max_tokens=1000000，
--     上游会直接 400，并且钱包要按 100 万 token 冻结预留额。真正放开单次输出上限，
--     需要同时改那个常量并确认每个上游都接受，是一次单独的变更，不混在这里。
--
-- 图片 / 视频 / 音频模型（V40:148、V97:23 已注明不携带 token 语义）也一并写成同一个
-- 数，目的只是让目录里不再出现 1 这种占位值；这些路径按张 / 按秒计费，不读这两列。
--
-- 只写真正变化的行：已经是 100 万的模型保持原值与原 updated_at（chat-deep、
-- chat-standard 是运维在 2026-10-16 手工设的，保留那个时间戳才有审计意义）。

UPDATE gateway.model
   SET context_window = 1000000,
       max_output_tokens = 1000000,
       updated_at = now()
 WHERE context_window IS DISTINCT FROM 1000000
    OR max_output_tokens IS DISTINCT FROM 1000000;

-- ---------------------------------------------------------------------------
-- 断言：没有一行还停在旧值上
--
-- 一条 UPDATE 之后本不该有残留，但"没有残留"是这次变更的全部内容——目录里只要还有
-- 一行是 8,192，客户端看到的就是两套说法。把这件事变成迁移的失败，而不是等运维比对。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    remaining integer;
BEGIN
    SELECT count(*) INTO remaining
    FROM gateway.model
    WHERE context_window IS DISTINCT FROM 1000000
       OR max_output_tokens IS DISTINCT FROM 1000000;

    IF remaining > 0 THEN
        RAISE EXCEPTION 'model_limits_not_uniform_at_one_million:count=%', remaining;
    END IF;
END $$;

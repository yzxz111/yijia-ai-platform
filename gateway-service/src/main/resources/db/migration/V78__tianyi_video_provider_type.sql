-- 天翼 AI 网关（ai.ctaigw.cn）视频上游需要一个专属的供应商类型。
--
-- 视频路由是按 gateway.provider.provider_type 挑选适配器的
-- （VideoAdapterRegistry），而百炼适配器在 VideoRouteRepository 里被
-- 钉死在 DashScope 源站上：
--
--     provider.provider_type <> 'BAILIAN'
--     OR channel.base_url ~ '^https://dashscope\.aliyuncs\.com...'
--
-- 因此一个非阿里云的上游既无法复用 BAILIAN（源站正则不通过），
-- 也无法复用 MOCK（那会返回假的成功结果）。放宽百炼的源站白名单是
-- 更糟的选择：那会让任何 URL 都能冒充百炼。新增一个类型是唯一
-- 既能把请求送到天翼、又不削弱既有校验的做法。
--
-- TIANYIYUN 由 TianYiYunVideoGenerationAdapter 服务。

ALTER TABLE gateway.provider DROP CONSTRAINT ck_provider_type;

ALTER TABLE gateway.provider ADD CONSTRAINT ck_provider_type
    CHECK (provider_type IN ('DEEPSEEK', 'BAILIAN', 'MOCK', 'TIANYIYUN'));

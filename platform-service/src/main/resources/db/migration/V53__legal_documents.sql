-- Versioned service terms and privacy policy, editable from the admin console.
--
-- One row per (kind, version). Exactly one published row per kind is enforced by a partial unique
-- index, and published rows are immutable: editing happens on a draft row, and publishing inserts
-- a new version. That keeps the consent ledger (platform.user_consent.version) auditable: the
-- version a user accepted always resolves to the exact text that was published at that time.
CREATE TABLE platform.legal_document_version (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind varchar(16) NOT NULL,
    version varchar(32) NOT NULL,
    title varchar(120) NOT NULL,
    content jsonb NOT NULL,
    published boolean NOT NULL DEFAULT false,
    published_at timestamptz,
    published_by uuid REFERENCES platform.app_user(id) ON DELETE SET NULL,
    unpublished_at timestamptz,
    created_by uuid REFERENCES platform.app_user(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_legal_document_kind CHECK (kind IN ('TERMS', 'PRIVACY')),
    CONSTRAINT uq_legal_document_version UNIQUE (kind, version)
);

-- Only one published version per document kind.
CREATE UNIQUE INDEX uq_legal_document_published
    ON platform.legal_document_version (kind) WHERE published;

CREATE INDEX ix_legal_document_history
    ON platform.legal_document_version (kind, COALESCE(unpublished_at, published_at, created_at) DESC);

-- Published content is frozen: a correction must ship as a new version, because
-- platform.user_consent.version points at the text a user accepted. Only the publication state
-- (unpublishing when a newer version takes over) may still change.
CREATE OR REPLACE FUNCTION platform.reject_published_legal_document_change()
RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.published THEN
            RAISE EXCEPTION 'published legal document versions cannot be deleted'
                USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.published AND (
            NEW.kind IS DISTINCT FROM OLD.kind
            OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.title IS DISTINCT FROM OLD.title
            OR NEW.content IS DISTINCT FROM OLD.content
            OR NEW.created_by IS DISTINCT FROM OLD.created_by
            OR NEW.created_at IS DISTINCT FROM OLD.created_at) THEN
        RAISE EXCEPTION 'published legal document content is immutable'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_legal_document_published_immutable
    BEFORE UPDATE OR DELETE ON platform.legal_document_version
    FOR EACH ROW EXECUTE FUNCTION platform.reject_published_legal_document_change();

-- Seed: the text currently shipped in the web console, so the API and the bundled fallback agree.
INSERT INTO platform.legal_document_version(
    kind, version, title, content, published, created_at)
VALUES (
    'TERMS',
    '2026-09-15',
    '平台服务协议',
    jsonb_build_object(
        'placeholder', '说明：本文为本地开发环境的占位正文，正式对外发布前须由法务审定并替换为最终条款。',
        'sections', '[{"heading":"一、协议范围","paragraphs":["本协议是您与宇智汇和科技有限公司（以下简称“本平台”）就使用宇智 AI 中台服务所订立的约定。您在登录页勾选同意并完成账号创建或登录，即表示您已阅读、理解并接受本协议全部内容。"]},{"heading":"二、账号与安全","paragraphs":["您应使用真实、合法、有效的手机号或邮箱注册账号，并妥善保管账号、密码、短信验证码与 API 密钥。因您保管不善导致的账号被盗用、密钥泄露、额度被消耗等损失，由您自行承担。","平台按账号维度限制同时登录的终端数量；同一账号在新的终端登录时，可能需要已有终端确认。"]},{"heading":"三、服务内容与计费","paragraphs":["平台提供模型 API 转发、套餐与 Token 额度、余额钱包、API 密钥管理、文件存储与调用日志等能力。套餐 Token 额度与钱包余额是两套彼此独立的账本：钱包有余额不代表存在可调用 Token，套餐有剩余 Token 也不代表钱包可用于支付。","计费以平台记录的用量为准。若上游模型供应商返回的官方用量与平台记录存在差异，平台以对账结果为准并保留调整权利。"]},{"heading":"四、使用规范","paragraphs":["您承诺不利用本服务从事以下行为："],"items":["生成、传播违反中华人民共和国法律法规的内容；","侵害他人知识产权、商业秘密、肖像权、隐私权等合法权益；","对平台或上游服务实施攻击、扫描、爬取、绕过限流或额度控制；","转售、出租平台账号或 API 密钥，或以其他方式向第三方提供本服务。"]},{"heading":"五、内容安全","paragraphs":["平台对调用内容执行内容安全检测。触发风控的账号可能被限制或停用，您可通过“安全申诉”入口提交申诉。"]},{"heading":"六、服务变更、中断与终止","paragraphs":["平台可能对服务内容、模型范围、计费方式进行调整，并通过控制台公告或站内通知告知。","因不可抗力、上游供应商故障、网络攻击或必要维护导致的服务中断，平台不承担赔偿责任，但会尽力恢复并在必要时退还相应未消耗额度。"]},{"heading":"七、免责与责任限制","paragraphs":["模型输出由人工智能自动生成，可能存在错误、遗漏或不适用于特定用途，您应自行核验并承担基于输出作出决策的后果。","平台在法律允许的最大范围内承担责任，累计赔偿上限不超过您在过去十二个月内实际支付的服务费用。"]},{"heading":"八、协议更新与联系方式","paragraphs":["本协议更新后，平台将更新版本号并在登录页提示重新确认。继续使用服务即视为接受更新后的协议。","如有疑问，可通过控制台“支持工单”联系我们。"]}]'::jsonb),
    true,
    now());

INSERT INTO platform.legal_document_version(
    kind, version, title, content, published, created_at)
VALUES (
    'PRIVACY',
    '2026-09-15',
    '隐私政策',
    jsonb_build_object(
        'placeholder', '说明：本文为本地开发环境的占位正文，正式对外发布前须由法务审定并替换为最终条款。',
        'sections', '[{"heading":"一、我们收集的信息","paragraphs":["为提供账号与服务能力，我们收集以下信息："],"items":["账号信息：手机号（加密存储，列表仅展示后四位）、邮箱、显示名称、头像。","认证信息：密码哈希、短信与邮箱验证码、刷新令牌与终端登录记录。","调用信息：模型调用时间、模型编码、Token 用量、结算金额、请求标识与调用结果。","上传内容：您主动提交的图片与文档，以及由此提取的文本片段。","安全信息：登录时间、客户端指纹、IP 归属地（用于异地登录提示与风控）。"]},{"heading":"二、我们如何使用这些信息","paragraphs":["用于账号创建与登录、套餐与余额结算、配额与限流控制、异常登录与滥用检测、内容安全审核、工单处理、经营与用量统计，以及法律法规要求的安全审计。","平台不会将您的提示词、模型回答、附件或 API 密钥出售给第三方；调用日志中的展示内容经过脱敏处理。"]},{"heading":"三、信息共享与第三方处理","paragraphs":["为完成模型调用，您的请求内容会转发给您所调用模型对应的上游供应商（例如阿里云百炼、DeepSeek）。这些供应商按其自身条款处理数据。","除此之外，仅在法律法规要求、司法机关依法调取或您明确授权时共享。"]},{"heading":"四、存储期限与安全措施","paragraphs":["上传文件默认保留 24 小时，到期自动清理；账号、订单、账本与审计记录按法律与财务要求保留。","平台对密码使用加盐哈希、对手机号与渠道凭据加密存储、对 API 密钥仅保存摘要，并通过网络隔离、最小权限与访问审计降低泄露风险。"]},{"heading":"五、您的权利","paragraphs":["您可以在“个人中心”查看和修改账号资料、绑定或更换手机号与邮箱、管理登录设备与终端会话、设置密码。","您也可以提交工单申请查阅、更正或删除个人信息。账号停用后，我们将按保留期限清理可识别信息。"]},{"heading":"六、未成年人保护","paragraphs":["本服务面向企业与成年开发者。若您为未成年人，请在监护人陪同下阅读本政策并决定是否使用本服务。"]},{"heading":"七、政策更新与联系方式","paragraphs":["本政策更新后，平台将更新版本号并在登录页提示重新确认。","如对本政策有疑问或需要行使上述权利，请通过控制台“支持工单”联系我们。"]}]'::jsonb),
    true,
    now());

-- Drafts start as copies of the published text so an edit never begins from an empty page.
INSERT INTO platform.legal_document_version(
    kind, version, title, content, published, created_at, updated_at)
SELECT kind, version || '-draft', title, content, false, now(), now()
FROM platform.legal_document_version
WHERE published;

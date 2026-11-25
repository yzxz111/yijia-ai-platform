-- 自动续费的短信模板登记。
--
-- 结构 = V10__sms_notifications.sql:234-260 的既有写法：本仓库只登记
-- template_key / 阿里云 template_code / variable_names / integration_status，
-- 短信正文本身在阿里云控制台，仓库内不存、不渲染、不比对（见
-- docs/operations/sms-notifications.md:85-86）。
--
-- 为什么先以 RESERVED 登记而不是等审核：模板的「存在性」与「可用性」是两件事。
-- 登记之后，NotificationSmsDispatcher 能区分两种失败 —— 模板不存在会走
-- TEMPLATE_VARIABLES_MISSING/NOT_FOUND 这类结构性错误，而 integration_status
-- 非 ACTIVE 会走 TEMPLATE_INACTIVE 这条明确的抑制原因。只靠前者会让「还没送审」
-- 与「代码写错了」在运维上无法区分。
--
-- P-2 完成后的激活流程（不改本迁移）：
--   UPDATE platform.sms_template
--   SET template_code = '<阿里云审核通过的模板ID>',
--       integration_status = 'PENDING_INTEGRATION'
--   WHERE template_key = 'SUBSCRIPTION_RENEWAL_DUE';
-- 然后走既有管理端 POST /console/platform/admin/sms/templates/activate
-- （SmsTemplateActivationService 会校验模板已在 NotificationTypeRegistry 注册）。

INSERT INTO platform.sms_template(
    template_key, template_code, template_type, variable_names, integration_status)
VALUES
    -- 占位码必须 <= 32 字符（ck 约束 template_code varchar(32)），且前缀统一，
    -- 便于用一条 LIKE 'RESERVED_SUB_%' 找出所有待替换的模板。
    -- 变量与 NotificationTypeRegistry 的 allowedPayloadKeys 一致。
    ('SUBSCRIPTION_RENEWAL_DUE', 'RESERVED_SUB_RENEWAL_DUE', 'NOTIFICATION',
        '["renewMoney","renewDate","planName"]'::jsonb, 'RESERVED'),
    ('SUBSCRIPTION_RENEWED', 'RESERVED_SUB_RENEWED', 'NOTIFICATION',
        '["payMoney"]'::jsonb, 'RESERVED'),
    ('SUBSCRIPTION_RENEWAL_FAILED', 'RESERVED_SUB_RENEWAL_FAILED', 'NOTIFICATION',
        '["failReason","retryDate"]'::jsonb, 'RESERVED'),
    ('SUBSCRIPTION_PRICE_CHANGED', 'RESERVED_SUB_PRICE_CHANGED', 'NOTIFICATION',
        '["oldMoney","newMoney","effectiveDate"]'::jsonb, 'RESERVED')
ON CONFLICT (template_key) DO NOTHING;

-- 断言：4 行必须都存在，否则应用会以「模板不存在」而不是「模板未激活」失败，
-- 两者的运维含义完全不同（前者是代码缺陷，后者是待办事项）。
DO $$
DECLARE
    inserted integer;
BEGIN
    SELECT count(*) INTO inserted
    FROM platform.sms_template
    WHERE template_key IN (
        'SUBSCRIPTION_RENEWAL_DUE', 'SUBSCRIPTION_RENEWED',
        'SUBSCRIPTION_RENEWAL_FAILED', 'SUBSCRIPTION_PRICE_CHANGED');

    IF inserted <> 4 THEN
        RAISE EXCEPTION 'subscription_sms_templates_missing:%', inserted;
    END IF;
END $$;

UPDATE platform.app_role AS role
SET name = localized.name
FROM (VALUES
    ('USER', 'User', '普通用户'),
    ('ADMIN', 'Administrator', '管理员')
) AS localized(code, original_name, name)
WHERE role.code = localized.code
  AND role.name = localized.original_name;

UPDATE platform.package_plan AS plan
SET name = localized.name,
    updated_at = now()
FROM (VALUES
    ('FREE', 'Free', '免费版'),
    ('STARTER', 'Starter', '入门版'),
    ('ADVANCED', 'Advanced', '高级版'),
    ('ENTERPRISE', 'Enterprise', '企业版')
) AS localized(code, original_name, name)
WHERE plan.code = localized.code
  AND plan.name = localized.original_name;

UPDATE platform.package_plan AS plan
SET description = localized.description,
    updated_at = now()
FROM (VALUES
    ('FREE', 'One-time introductory package', '适合首次体验，快速了解基础调用能力。'),
    ('STARTER', 'Starter text package', '适合文字模型调用和轻量业务接入。'),
    ('ADVANCED', 'Advanced multimodal package', '适合多模态模型与更高频率的业务调用。'),
    ('ENTERPRISE', 'Enterprise base package', '适合企业级高并发和规模化业务使用。')
) AS localized(code, original_description, description)
WHERE plan.code = localized.code
  AND plan.description = localized.original_description;

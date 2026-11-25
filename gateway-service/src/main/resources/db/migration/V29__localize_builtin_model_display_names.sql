UPDATE gateway.model AS model
SET display_name = localized.display_name,
    updated_at = now()
FROM (VALUES
    ('file-basic', 'File Basic', '文件基础版'),
    ('multimodal-basic', 'Multimodal Basic', '多模态基础版'),
    ('text-basic', 'Text Basic', '文本基础版'),
    ('text-max', 'Text Max', '文本旗舰版'),
    ('text-pro', 'Text Pro', '文本专业版'),
    ('text-standard', 'Text Standard', '文本标准版'),
    ('vision-basic', 'Vision Basic', '视觉基础版'),
    ('vision-pro', 'Vision Pro', '视觉专业版')
) AS localized(code, original_display_name, display_name)
WHERE model.code = localized.code
  AND model.display_name = localized.original_display_name;

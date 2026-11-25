-- Replace the generic package-format marker on the six curated experts with the
-- business capabilities shown in the desktop directory. Preserve any record an
-- administrator has already customized by touching only the exact legacy {ycps} value.

UPDATE gateway.skill
SET tags = ARRAY['办公文件处理', '表格汇总核验', '跨文件分析', '格式整理']::text[],
    row_version = row_version + 1,
    updated_at = now()
WHERE code = 'ai-office-assistant'
  AND kind = 'EXPERT'
  AND tags = ARRAY['ycps']::text[];

UPDATE gateway.skill
SET tags = ARRAY['文章摘要', '会议纪要', '网页提炼', '行动项整理']::text[],
    row_version = row_version + 1,
    updated_at = now()
WHERE code = 'content-summarizer'
  AND kind = 'EXPERT'
  AND tags = ARRAY['ycps']::text[];

UPDATE gateway.skill
SET tags = ARRAY['文章写作', '营销文案', '社交媒体内容', '选题策划']::text[],
    row_version = row_version + 1,
    updated_at = now()
WHERE code = 'content-writing-expert'
  AND kind = 'EXPERT'
  AND tags = ARRAY['ycps']::text[];

UPDATE gateway.skill
SET tags = ARRAY['Word/PDF 版本与条款比对', '数值与表格核查', '保留结构的指定修订']::text[],
    row_version = row_version + 1,
    updated_at = now()
WHERE code = 'document-review-expert'
  AND kind = 'EXPERT'
  AND tags = ARRAY['ycps']::text[];

UPDATE gateway.skill
SET tags = ARRAY['销售月报与周期对比', '销售数据汇总', '销售指标与异常核验']::text[],
    row_version = row_version + 1,
    updated_at = now()
WHERE code = 'sales-analysis-expert'
  AND kind = 'EXPERT'
  AND tags = ARRAY['ycps']::text[];

UPDATE gateway.skill
SET tags = ARRAY['广告文案与事实检查', '短视频策划、脚本、分镜与包装文案', '内容运营包与日历', '社媒平台文案', '文案优化与发布前检查']::text[],
    row_version = row_version + 1,
    updated_at = now()
WHERE code = 'social-media-operations-expert'
  AND kind = 'EXPERT'
  AND tags = ARRAY['ycps']::text[];

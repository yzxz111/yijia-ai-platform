-- Business labels moved to gateway.skill_expert_profile.task_types in V68/V73.  V67 temporarily
-- copied those labels into gateway.skill.tags as well, which makes a later WYSIWYG save confuse
-- the operational package marker with user-facing task types.  Restore the six curated rows only
-- when they still contain the exact legacy business array; any other administrator customization
-- remains untouched.
WITH repaired AS (
    UPDATE gateway.skill
    SET tags = ARRAY['ycps']::text[],
        row_version = row_version + 1,
        updated_at = now()
    WHERE kind = 'EXPERT'
      AND deleted_at IS NULL
      AND (
          (code = 'ai-office-assistant'
              AND tags = ARRAY['办公文件处理', '表格汇总核验', '跨文件分析', '格式整理']::text[])
          OR (code = 'content-summarizer'
              AND tags = ARRAY['文章摘要', '会议纪要', '网页提炼', '行动项整理']::text[])
          OR (code = 'content-writing-expert'
              AND tags = ARRAY['文章写作', '营销文案', '社交媒体内容', '选题策划']::text[])
          OR (code = 'document-review-expert'
              AND tags = ARRAY['Word/PDF 版本与条款比对', '数值与表格核查', '保留结构的指定修订']::text[])
          OR (code = 'sales-analysis-expert'
              AND tags = ARRAY['销售月报与周期对比', '销售数据汇总', '销售指标与异常核验']::text[])
          OR (code = 'social-media-operations-expert'
              AND tags = ARRAY['广告文案与事实检查', '短视频策划、脚本、分镜与包装文案',
                                '内容运营包与日历', '社媒平台文案', '文案优化与发布前检查']::text[])
      )
    RETURNING id
)
SELECT count(*) AS repaired_curated_expert_operational_tags
FROM repaired;

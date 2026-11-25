-- Restore the semantic tables used by the three curated Expert detail pages.
--
-- V73 preserved the old pages as pipe-delimited output paragraphs so older clients could still
-- display every word.  Once the WYSIWYG/table protocol became available, that compatibility form
-- stopped being sufficient: an explicit `table: null` from a later editor save suppresses the
-- desktop's legacy parser, and the table can no longer be edited as cells in the console.
--
-- Keep this migration deliberately narrow.  It only upgrades the exact legacy table signature
-- while no semantic table exists, preserves all unrelated preview keys, and advances the parent
-- aggregate row_version exactly once.  Administrator-authored semantic tables are never replaced.
WITH seed(
    code,
    legacy_header_zh,
    legacy_header_en,
    intro_zh,
    intro_en,
    table_zh,
    table_en,
    output_paragraphs_zh,
    output_paragraphs_en
) AS (
    VALUES
    (
        'ai-office-assistant',
        '姓名｜应出勤｜实际出勤｜差额',
        'Name | Expected | Actual | Difference',
        $json$ {"tone":"muted","text":"单位：天"}$json$::jsonb,
        $json$ {"tone":"muted","text":"Unit: days"}$json$::jsonb,
        $json$ {
          "headers": ["姓名", "应出勤", "实际出勤", "差额"],
          "columnWidths": [25, 25, 25, 25],
          "rows": [
            ["林然", "22", "21", "1"],
            ["周宁", "22", "22", "0"],
            ["合计", "44", "43", "1"]
          ],
          "totalRow": true
        }$json$::jsonb,
        $json$ {
          "headers": ["Name", "Expected", "Actual", "Difference"],
          "columnWidths": [25, 25, 25, 25],
          "rows": [
            ["Lin Ran", "22", "21", "1"],
            ["Zhou Ning", "22", "22", "0"],
            ["Total", "44", "43", "1"]
          ],
          "totalRow": true
        }$json$::jsonb,
        $json$[
          "核对结果：两个表各 2 人，姓名均可对应，未发现重名记录。",
          "需要核实：林然有 1 天差额，需结合请假或漏打卡记录确认原因。",
          "差额＝应出勤－实际出勤，不直接认定为旷工。"
        ]$json$::jsonb,
        $json$[
          "Check: Both sheets contain 2 people. Names match and no duplicate names were found.",
          "To verify: Lin Ran has a 1-day difference. Check leave records or missing clock-ins to establish the reason.",
          "Difference = expected - actual days; this alone does not establish an unauthorized absence."
        ]$json$::jsonb
    ),
    (
        'content-summarizer',
        '待办事项｜负责人｜截止日期',
        'Action item | Owner | Due date',
        $json$ {"tone":"body","text":"已确认决策：首版定于 9 月 18 日上线。"}$json$::jsonb,
        $json$ {"tone":"body","text":"Confirmed: The first release launches on September 18."}$json$::jsonb,
        $json$ {
          "headers": ["待办事项", "负责人", "截止日期"],
          "columnWidths": [52, 19, 29],
          "rows": [
            ["补齐注册流程", "李明", "9 月 15 日"],
            ["完成回归测试", "王悦", "9 月 16 日"],
            ["跟进短信服务报价", "陈晨", "9 月 14 日"]
          ],
          "totalRow": false
        }$json$::jsonb,
        $json$ {
          "headers": ["Action item", "Owner", "Due date"],
          "columnWidths": [52, 19, 29],
          "rows": [
            ["Complete sign-up flow", "Li Ming", "Sep 15"],
            ["Finish regression testing", "Wang Yue", "Sep 16"],
            ["Follow up on SMS pricing", "Chen Chen", "Sep 14"]
          ],
          "totalRow": false
        }$json$::jsonb,
        $json$["待确认：短信服务报价尚未确定。"]$json$::jsonb,
        $json$["Pending: SMS service pricing is still unconfirmed."]$json$::jsonb
    ),
    (
        'sales-analysis-expert',
        '产品｜7 月（万元）｜8 月（万元）｜环比',
        'Product | Jul (CNY 10k) | Aug (CNY 10k) | MoM',
        $json$ {"tone":"body","text":"净销售额 54 万元，环比增长 8%"}$json$::jsonb,
        $json$ {"tone":"body","text":"Net sales: CNY 540,000, up 8% month over month"}$json$::jsonb,
        $json$ {
          "headers": ["产品", "7 月（万元）", "8 月（万元）", "环比"],
          "columnWidths": [18, 30, 30, 22],
          "rows": [
            ["A", "30", "36", "+20%"],
            ["B", "20", "18", "−10%"],
            ["合计", "50", "54", "+8%"]
          ],
          "totalRow": true
        }$json$::jsonb,
        $json$ {
          "headers": ["Product", "Jul (CNY 10k)", "Aug (CNY 10k)", "MoM"],
          "columnWidths": [18, 30, 30, 22],
          "rows": [
            ["A", "30", "36", "+20%"],
            ["B", "20", "18", "−10%"],
            ["Total", "50", "54", "+8%"]
          ],
          "totalRow": true
        }$json$::jsonb,
        $json$[
          "关键变化：A 产品增加 6 万元，抵消 B 产品减少的 2 万元，带动整体增长。",
          "下一步检查：拆分 B 产品的销量与成交均价，核实下降原因。",
          "当前数据仅能说明金额变化，尚不足以判断原因。"
        ]$json$::jsonb,
        $json$[
          "Key change: A grew by CNY 60,000, offsetting the CNY 20,000 decline in B and driving overall growth.",
          "Next: Break down B's volume and average selling price to investigate the decline.",
          "These figures show changes in sales value; they are not enough to establish causes."
        ]$json$::jsonb
    )
), repaired AS (
    UPDATE gateway.skill_expert_profile AS profile
    SET preview_zh = (profile.preview_zh - 'intro' - 'table') || jsonb_build_object(
            'intro', seed.intro_zh,
            'table', seed.table_zh,
            'outputParagraphs', seed.output_paragraphs_zh
        ),
        preview_en = (profile.preview_en - 'intro' - 'table') || jsonb_build_object(
            'intro', seed.intro_en,
            'table', seed.table_en,
            'outputParagraphs', seed.output_paragraphs_en
        ),
        updated_at = now()
    FROM gateway.skill AS skill, seed
    WHERE profile.skill_id = skill.id
      AND skill.code = seed.code
      AND skill.kind = 'EXPERT'
      AND skill.deleted_at IS NULL
      AND profile.preview_zh IS NOT NULL
      AND profile.preview_en IS NOT NULL
      AND (profile.preview_zh->'table' IS NULL OR profile.preview_zh->'table' = 'null'::jsonb)
      AND (profile.preview_en->'table' IS NULL OR profile.preview_en->'table' = 'null'::jsonb)
      AND profile.preview_zh#>>'{outputParagraphs,1}' = seed.legacy_header_zh
      AND profile.preview_en#>>'{outputParagraphs,1}' = seed.legacy_header_en
    RETURNING profile.skill_id
)
UPDATE gateway.skill AS skill
SET row_version = skill.row_version + 1,
    updated_at = now()
FROM repaired
WHERE skill.id = repaired.skill_id;

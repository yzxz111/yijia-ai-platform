-- Restore the six curated Experts' complete desktop detail presentation from the last
-- pre-WYSIWYG release.  V68 intentionally created only a compatibility shell (task_types copied
-- from tags); this migration fills that shell with the former authoritative bilingual usage copy
-- and examples so the new structured renderer is content-equivalent to the legacy page.
--
-- The guard is deliberately strict.  It only matches an untouched, incomplete V68 shell and will
-- never overwrite a profile an administrator has completed through the WYSIWYG editor.  The
-- parent skill row_version is advanced exactly once for every seeded aggregate.
WITH seed(
    code,
    task_types,
    task_types_en,
    usage_guide,
    usage_guide_en,
    example_prompts,
    example_prompts_en,
    preview_zh,
    preview_en
) AS (
    VALUES
    (
        'ai-office-assistant',
        ARRAY['办公文件处理', '表格汇总核验', '跨文件分析', '格式整理']::text[],
        ARRAY['Office-file processing', 'Spreadsheet reconciliation', 'Cross-file analysis', 'Format cleanup']::text[],
        '添加要处理的办公文件，说明需要读取、创建、修改、比对或汇总的内容以及期望的交付格式。默认保留原文件。',
        'Attach the office files, describe what to read, create, edit, compare, or consolidate, and state the desired output format. Originals are preserved by default.',
        $json$[
          "根据 Word、Excel 和 PDF 材料，沿用这份模板制作可编辑 PPT 汇报，附讲稿和来源",
          "从多个 Excel 汇总工资和考勤",
          "把会议材料整理成可交付的 Word 报告"
        ]$json$::jsonb,
        $json$[
          "Create an editable PPT presentation from Word, Excel, and PDF materials using this template, with speaker notes and sources",
          "Reconcile payroll and attendance across Excel files",
          "Turn meeting materials into a deliverable Word report"
        ]$json$::jsonb,
        $json$
        {
          "heading": "一句话，理清表格",
          "scene": "以跨表考勤汇总为例",
          "request": "帮我汇总这两张考勤表，按姓名列出应出勤、实际出勤和差额，标出需要核实的地方。",
          "material": "演示材料｜应出勤.xlsx：林然 22 天、周宁 22 天；打卡汇总.xlsx：林然 21 天、周宁 22 天。",
          "outputTitle": "考勤汇总与核对",
          "outputParagraphs": [
            "单位：天",
            "姓名｜应出勤｜实际出勤｜差额",
            "林然｜22｜21｜1",
            "周宁｜22｜22｜0",
            "合计｜44｜43｜1",
            "核对结果：两个表各 2 人，姓名均可对应，未发现重名记录。",
            "需要核实：林然有 1 天差额，需结合请假或漏打卡记录确认原因。",
            "差额＝应出勤－实际出勤，不直接认定为旷工。"
          ]
        }$json$::jsonb,
        $json$
        {
          "heading": "One sentence to organize your data",
          "scene": "Attendance across spreadsheets, for example",
          "request": "Combine these two attendance sheets. List expected days, actual days and the difference for each person, and flag anything to check.",
          "material": "Demo files | Expected.xlsx: Lin Ran 22 days, Zhou Ning 22 days. Clock-ins.xlsx: Lin Ran 21 days, Zhou Ning 22 days.",
          "outputTitle": "Attendance summary and checks",
          "outputParagraphs": [
            "Unit: days",
            "Name | Expected | Actual | Difference",
            "Lin Ran | 22 | 21 | 1",
            "Zhou Ning | 22 | 22 | 0",
            "Total | 44 | 43 | 1",
            "Check: Both sheets contain 2 people. Names match and no duplicate names were found.",
            "To verify: Lin Ran has a 1-day difference. Check leave records or missing clock-ins to establish the reason.",
            "Difference = expected - actual days; this alone does not establish an unauthorized absence."
          ]
        }$json$::jsonb
    ),
    (
        'content-summarizer',
        ARRAY['文章摘要', '会议纪要', '网页提炼', '行动项整理']::text[],
        ARRAY['Article summaries', 'Meeting minutes', 'Web-page distillation', 'Action-item extraction']::text[],
        '粘贴或添加文章、网页、会议记录等原始材料，并说明希望得到摘要、结论还是行动项。',
        'Paste or attach the article, web page, meeting notes, or other source material, and specify whether you need a summary, conclusions, or action items.',
        $json$[
          "总结这篇文章并提炼行动项",
          "把会议记录整理成决策和待办事项"
        ]$json$::jsonb,
        $json$[
          "Summarize this article and extract action items",
          "Turn these meeting notes into decisions and TODOs"
        ]$json$::jsonb,
        $json$
        {
          "heading": "一句话，理清资料",
          "scene": "以项目周会纪要整理为例",
          "request": "把这段周会记录整理成决策和待办，列清负责人和截止日期。",
          "material": "会议记录（演示）｜首版 9/18 上线。\n李明 9/15 补齐注册流程；王悦 9/16 完成回归测试。\n短信报价待定，陈晨 9/14 跟进。",
          "outputTitle": "周会决策与待办",
          "outputParagraphs": [
            "已确认决策：首版定于 9 月 18 日上线。",
            "待办事项｜负责人｜截止日期",
            "补齐注册流程｜李明｜9 月 15 日",
            "完成回归测试｜王悦｜9 月 16 日",
            "跟进短信服务报价｜陈晨｜9 月 14 日",
            "待确认：短信服务报价尚未确定。"
          ]
        }$json$::jsonb,
        $json$
        {
          "heading": "One sentence to find clarity",
          "scene": "Weekly project meeting notes, for example",
          "request": "Turn these meeting notes into decisions and action items, with an owner and deadline for each task.",
          "material": "Demo notes: Launch on Sep 18. Li Ming completes sign-up by Sep 15; Wang Yue finishes regression testing by Sep 16. SMS pricing is pending; Chen Chen follows up by Sep 14.",
          "outputTitle": "Meeting decisions and action items",
          "outputParagraphs": [
            "Confirmed: The first release launches on September 18.",
            "Action item | Owner | Due date",
            "Complete sign-up flow | Li Ming | Sep 15",
            "Finish regression testing | Wang Yue | Sep 16",
            "Follow up on SMS pricing | Chen Chen | Sep 14",
            "Pending: SMS service pricing is still unconfirmed."
          ]
        }$json$::jsonb
    ),
    (
        'content-writing-expert',
        ARRAY['文章写作', '营销文案', '社交媒体内容', '选题策划']::text[],
        ARRAY['Article writing', 'Marketing copy', 'Social media content', 'Topic planning']::text[],
        '说明发布平台、目标读者、内容主题和希望达成的效果；有品牌资料或参考内容时请一并提供。',
        'Describe the publishing platform, audience, topic, and desired outcome. Include brand materials or references when available.',
        $json$[
          "写一篇小红书新品种草文案",
          "帮我策划一周公众号选题"
        ]$json$::jsonb,
        $json$[
          "Write a Xiaohongshu product-seeding post",
          "Plan a week of official-account topics"
        ]$json$::jsonb,
        $json$
        {
          "heading": "一句话，开始创作",
          "scene": "以小红书种草文案为例",
          "request": "帮我写一篇便携咖啡机的小红书种草文案，面向年轻上班族，语气轻松有趣，突出小巧便携。",
          "material": "演示产品资料｜便携咖啡机\n主打小巧、便于收纳与携带。",
          "outputTitle": "工位可以固定，咖啡快乐要随身",
          "outputParagraphs": [
            "早八人还没醒，咖啡瘾先打卡了。\n还好，包里有这台便携咖啡机。",
            "小巧好收纳，工位放得下，\n出差带得走。",
            "忙里偷闲来一杯，给自己按下重启键。\n班照上，喜欢的那一口也得安排上。",
            "#打工人的咖啡日常  #便携咖啡机"
          ]
        }$json$::jsonb,
        $json$
        {
          "heading": "One sentence to start creating",
          "scene": "A Xiaohongshu product post, for example",
          "request": "Write a light, fun Xiaohongshu post about a portable coffee maker for young professionals, highlighting its compact size and portability.",
          "material": "Demo product brief | Portable coffee maker\nCompact, easy to store and carry.",
          "outputTitle": "Your desk stays put. Your coffee comes along.",
          "outputParagraphs": [
            "Not quite awake, but already craving coffee?\nGood thing this little coffee maker fits in your bag.",
            "Small enough for your desk,\nready for your next work trip.",
            "Take a coffee break and hit your own reset button.\nA busy day still deserves your favorite sip.",
            "#CoffeeAtWork  #PortableCoffeeMaker"
          ]
        }$json$::jsonb
    ),
    (
        'document-review-expert',
        ARRAY['Word/PDF 版本与条款比对', '数值与表格核查', '保留结构的指定修订']::text[],
        ARRAY['Word/PDF version and clause comparison', 'Number and table checks', 'Specified edits with structure preservation']::text[],
        '添加要审校的 Word/PDF，说明版本对应关系和审校重点；需要修改时注明目标版本及具体改动。原件保持不变。',
        'Attach the Word/PDF files, identify their version mapping and review focus, and specify the target version and exact edits when requesting changes. Originals remain unchanged.',
        $json$[
          "比对这两版 Word/PDF，列出条款、数值和结构差异，附两侧原文定位；只按我指定的改动另存修订副本",
          "只核对这两版付款条款的变化，给出原文定位，不修改文件"
        ]$json$::jsonb,
        $json$[
          "Compare these Word/PDF versions for clause, number, and structure changes with locators on both sides; save a revision copy only for my specified edits",
          "Check only the payment-clause changes between these versions with source locators; do not edit files"
        ]$json$::jsonb,
        $json$
        {
          "heading": "一句话，找出差异",
          "scene": "以项目方案版本审校为例",
          "request": "对照这两版项目方案，找出交付日期和预算的变化，标清位置和修改建议，先不要改文件。",
          "material": "演示材料｜v1.docx：第 2 页交付日 9/20；第 3 页设计 8,000 元＋开发 12,000 元＝20,000 元。\nv2.pdf：第 2 页交付日 9/25；第 3 页设计 8,000 元＋开发 15,000 元，合计仍写 20,000 元。",
          "outputTitle": "两处需要复核",
          "outputParagraphs": [
            "01  交付日期变化｜位置：v1 / v2 第 2 页",
            "9/20 → 9/25，交付日期延后 5 天。",
            "建议：确认日期变更是否已同步给项目相关方。",
            "02  预算合计未同步｜位置：v2 第 3 页预算表",
            "8,000＋15,000＝23,000 元，表中合计少计 3,000 元。",
            "建议：确认开发费用为 15,000 元后，将合计改为 23,000 元。"
          ]
        }$json$::jsonb,
        $json$
        {
          "heading": "One sentence to spot differences",
          "scene": "Reviewing project proposal versions, for example",
          "request": "Compare these two project proposals. Locate changes to the delivery date and budget, and suggest corrections without editing the files yet.",
          "material": "Demo files | v1.docx: p. 2 delivery Sep 20; p. 3 design CNY 8,000 + development CNY 12,000 = CNY 20,000.\nv2.pdf: p. 2 delivery Sep 25; p. 3 design CNY 8,000 + development CNY 15,000, but the total still reads CNY 20,000.",
          "outputTitle": "Two items to review",
          "outputParagraphs": [
            "01  Delivery date changed | Location: v1 / v2, page 2",
            "Sep 20 → Sep 25: delivery moves back by 5 days.",
            "Suggestion: Confirm the new date has been shared with project stakeholders.",
            "02  Budget total not updated | Location: v2, page 3, budget table",
            "8,000 + 15,000 = CNY 23,000. The displayed total is CNY 3,000 too low.",
            "Suggestion: Confirm development costs are CNY 15,000, then update the total to CNY 23,000."
          ]
        }$json$::jsonb
    ),
    (
        'sales-analysis-expert',
        ARRAY['销售月报与周期对比', '销售数据汇总', '销售指标与异常核验']::text[],
        ARRAY['Monthly sales reports and period comparisons', 'Sales summaries', 'Sales metric and anomaly checks']::text[],
        '添加销售数据文件，说明统计期间、指标口径、对比维度和期望交付；缺失或冲突的数据会单独标明。',
        'Attach the sales data and state the reporting period, metric definitions, comparison dimensions, and desired output. Missing or conflicting data will be identified.',
        $json$[
          "按产品汇总销售额",
          "根据这两期销售数据制作完整月报，按产品和地区对比，附分析表格、图表及异常和缺失说明"
        ]$json$::jsonb,
        $json$[
          "Summarize sales by product",
          "Create a full monthly report from these two sales periods, comparing products and regions with analysis tables, charts, and anomaly and missing-data notes"
        ]$json$::jsonb,
        $json$
        {
          "heading": "一句话，开始分析",
          "scene": "以月度销售复盘为例",
          "request": "根据这份销售表，比较 7 月和 8 月各产品的销售额，给我关键变化和下一步检查方向。",
          "material": "演示数据（万元）｜A 产品：7 月 30、8 月 36；B 产品：7 月 20、8 月 18。口径为已确认净销售额。",
          "outputTitle": "8 月销售复盘",
          "outputParagraphs": [
            "净销售额 54 万元，环比增长 8%",
            "产品｜7 月（万元）｜8 月（万元）｜环比",
            "A｜30｜36｜+20%",
            "B｜20｜18｜−10%",
            "合计｜50｜54｜+8%",
            "关键变化：A 产品增加 6 万元，抵消 B 产品减少的 2 万元，带动整体增长。",
            "下一步检查：拆分 B 产品的销量与成交均价，核实下降原因。",
            "当前数据仅能说明金额变化，尚不足以判断原因。"
          ]
        }$json$::jsonb,
        $json$
        {
          "heading": "One sentence to start analyzing",
          "scene": "A monthly sales review, for example",
          "request": "Compare July and August sales for each product in this table. Show the key changes and what to investigate next.",
          "material": "Demo data (CNY 10,000) | Product A: July 30, August 36. Product B: July 20, August 18. Figures represent recognized net sales.",
          "outputTitle": "August sales review",
          "outputParagraphs": [
            "Net sales: CNY 540,000, up 8% month over month",
            "Product | Jul (CNY 10k) | Aug (CNY 10k) | MoM",
            "A | 30 | 36 | +20%",
            "B | 20 | 18 | −10%",
            "Total | 50 | 54 | +8%",
            "Key change: A grew by CNY 60,000, offsetting the CNY 20,000 decline in B and driving overall growth.",
            "Next: Break down B's volume and average selling price to investigate the decline.",
            "These figures show changes in sales value; they are not enough to establish causes."
          ]
        }$json$::jsonb
    ),
    (
        'social-media-operations-expert',
        ARRAY['广告文案与事实检查', '短视频策划、脚本、分镜与包装文案', '内容运营包与日历', '社媒平台文案', '文案优化与发布前检查']::text[],
        ARRAY['Ad copy and fact checks', 'Short-video plans, scripts, storyboards and packaging copy', 'Content operations packages and calendars', 'Social platform copy', 'Copy optimization and pre-publication checks']::text[],
        '提供品牌 brief、产品事实或逐字稿，说明目标平台、受众、内容形式和周期。仅生成可检查的草稿，不会自动发布或写入外部账户。',
        'Provide the brand brief, product facts, or transcript and state the target platform, audience, content format, and period. Outputs are reviewable drafts and are never published or written to external accounts automatically.',
        $json$[
          "根据品牌 brief，为小红书写 3 条广告文案并附逐条事实检查，只交付广告草稿，不做视频或运营日历",
          "根据这些产品材料，写一份 30 秒短视频策划、脚本、分镜和包装文案，逐条标明事实来源，不要成片",
          "根据已有逐字稿，只修改上一版短视频脚本，不重新转写或生成视频",
          "根据产品材料，为下周小红书和抖音制作内容日历、平台文案及逐项发布前检查清单，只生成草稿",
          "根据这份产品 brief，只写一篇小红书文案并附必要事实检查"
        ]$json$::jsonb,
        $json$[
          "Use this brand brief to write three Xiaohongshu ads with claim-by-claim fact checks; ad drafts only, without video or an operations calendar",
          "Use these product materials for a 30-second video plan, script, storyboard and packaging copy; trace every claim to its source, without producing a video",
          "Use the existing transcript to revise only the previous short-video script, without retranscribing or producing a video",
          "Use the product materials to create next week's Xiaohongshu and Douyin calendar, platform copy, and item-by-item pre-publication checklist; drafts only",
          "Use this product brief to write just one Xiaohongshu draft with the necessary fact checks"
        ]$json$::jsonb,
        $json$
        {
          "heading": "一句话，开始创作",
          "scene": "以小红书随行杯文案为例",
          "request": "根据这份产品 brief，给上班族写一条自然、不夸张的小红书文案，附标签，并核对用到的卖点。",
          "material": "演示 brief｜350mL 随行杯，杯盖可拆洗，有奶白、雾蓝两色。",
          "outputTitle": "奶白还是雾蓝？通勤杯也想选喜欢的",
          "outputParagraphs": [
            "上班路上的小仪式，是带上一杯自己喜欢的饮品。",
            "这只随行杯有奶白、雾蓝两色，容量 350mL。杯盖可拆开清洗，日常用完，也别忘了把边角洗干净。",
            "如果是你，会选温柔的奶白，还是清爽的雾蓝？",
            "#通勤日常  #随行杯  #日常好物",
            "卖点核对",
            "容量：350mL；清洗方式：杯盖可拆洗。\n配色：奶白、雾蓝。\n以上三项均来自已提供的 brief。"
          ]
        }$json$::jsonb,
        $json$
        {
          "heading": "One sentence to start creating",
          "scene": "A Xiaohongshu travel cup post, for example",
          "request": "Use this product brief to write a natural Xiaohongshu post for office workers. Include hashtags, avoid exaggeration, and check the product claims.",
          "material": "Demo brief | 350mL travel cup; removable lid for cleaning; available in cream white and mist blue.",
          "outputTitle": "Cream white or mist blue? Pick your commute companion.",
          "outputParagraphs": [
            "A small morning ritual: bring along a drink you love.",
            "This 350mL cup comes in cream white and mist blue. The lid comes apart for cleaning—remember those little corners after each use.",
            "Which would you choose: soft cream white or refreshing mist blue?",
            "#DailyCommute  #TravelCup  #EverydayFinds",
            "Product claim check",
            "Capacity: 350mL. Cleaning: removable lid.\nColors: cream white and mist blue.\nAll three claims come from the supplied brief."
          ]
        }$json$::jsonb
    )
), seeded AS (
    UPDATE gateway.skill_expert_profile profile
    SET task_types = seed.task_types,
        task_types_en = seed.task_types_en,
        usage_guide = seed.usage_guide,
        usage_guide_en = seed.usage_guide_en,
        example_prompts = seed.example_prompts,
        example_prompts_en = seed.example_prompts_en,
        preview_zh = seed.preview_zh,
        preview_en = seed.preview_en,
        updated_at = now()
    FROM gateway.skill skill, seed
    WHERE profile.skill_id = skill.id
      AND skill.code = seed.code
      AND skill.kind = 'EXPERT'
      AND skill.deleted_at IS NULL
      AND profile.usage_guide IS NULL
      AND profile.example_prompts = '[]'::jsonb
      AND profile.preview_zh IS NULL
      AND profile.portable_revision = 0
    RETURNING profile.skill_id
)
UPDATE gateway.skill skill
SET row_version = skill.row_version + 1,
    updated_at = now()
FROM seeded
WHERE skill.id = seeded.skill_id;

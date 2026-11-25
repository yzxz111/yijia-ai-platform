"""回填 gateway.skill 的中英双语字段（name/name_en/summary/summary_en/description/description_en）。

设计：
  - `name`（中文标题）= SKILL.md 的一级标题
  - `name_en`（英文标题）= Package code（既有英文命名习惯）
  - `summary` / `description`（中文）= 中文一句话简介
  - `summary_en` / `description_en`（英文）= 英文一句话简介
  - 已有中文 summary 的包（data-analyst 等）保持中文在 summary，英文写进 summary_en
  - 原本是英文 summary 的包，英文移入 summary_en，中文新写进 summary

仅更新这 6 个语言字段，不触碰 status / version / row_version。
"""
from __future__ import annotations

import json
import subprocess
import sys

CONTAINER = "yuzhihuihe-api-platform-postgres-1"
DB = "api_model_platform"

# code -> (中文标题, 英文标题, 中文简介, 英文简介)
ROWS: dict[str, tuple[str, str, str, str]] = {
    "ai-office-assistant": (
        "AI办公助手",
        "AI Office Assistant",
        "读取、创建、编辑、比较并汇总 Word、PDF、Excel 与 PowerPoint 文件，支持工资与考勤核对，以及基于模板的可编辑演示文稿。",
        "Read, create, edit, compare, and consolidate Word, PDF, Excel, and PowerPoint files, including payroll and attendance reconciliation and template-based editable presentations.",
    ),
    "content-summarizer": (
        "内容总结助手",
        "Content Summarizer",
        "把文章、会议记录、网页与多来源材料提炼为摘要、决策要点与待办事项，不丢失关键细节。",
        "Distill articles, meeting transcripts, web pages, and multi-source material into summaries, decisions, and action items without losing key detail.",
    ),
    "content-writing-expert": (
        "内容创作专家",
        "Content Writing Expert",
        "规划选题并撰写可评审的原创中文内容，覆盖微信公众号、小红书、知乎与短视频渠道，从大纲到成稿。",
        "Plan topics and write review-ready, original Chinese content for WeChat Official Accounts, Xiaohongshu, Zhihu, and short-video channels, from outline to polished draft.",
    ),
    "document-review-expert": (
        "文档审校专家",
        "Document Review Expert",
        "审校 Word 与 PDF 的条款、数字与结构，问题可追溯至原文，并按指定修改保存保留结构的修订副本。",
        "Review Word and PDF clauses, numbers, and structure with findings traceable to the source, and save a structure-preserving revision copy for specified edits.",
    ),
    "sales-analysis-expert": (
        "销售分析专家",
        "Sales Analysis Expert",
        "按产品、区域或周期分析源数据中的销售情况，输出聚焦的摘要或月度报告，含表格、图表与可追溯的数据质量说明。",
        "Analyze sales by product, region, or period from source data, delivering focused summaries or monthly reports with tables, charts, and traceable data-quality notes.",
    ),
    "social-media-operations-expert": (
        "社媒运营专家",
        "Social Media Operations Expert",
        "产出有依据的广告、平台文案、内容日历，以及短视频的方案、脚本、分镜与包装文案，供人工使用。",
        "Produce evidence-backed ads, platform copy, content calendars, and short-video plans, scripts, storyboards and packaging copy for manual use.",
    ),
    "content-writer": (
        "多平台内容创作",
        "Multi-Platform Content Creation",
        "依据已确认的选题简报，为小红书、知乎、微信公众号与短视频渠道撰写可评审的原创中文社交内容草稿。",
        "Create review-ready, original Chinese social-media drafts for Xiaohongshu, Zhihu, WeChat Official Accounts, and short-video channels from an approved brief.",
    ),
    "data-analyst": (
        "数据分析助手",
        "Data Analyst",
        "清洗和分析 CSV、Excel 报表，核对指标口径，识别趋势与异常，并给出可执行的图表建议。",
        "Clean and analyze CSV and Excel reports, reconcile metric definitions, identify trends and anomalies, and recommend actionable charts.",
    ),
    "ecommerce-ops": (
        "电商运营助手",
        "E-commerce Operations",
        "按目标电商平台规则优化商品标题与详情页卖点，规划促销活动，并整理客服及差评应对话术。",
        "Optimize product titles and detail-page selling points to each marketplace's rules, plan promotions, and prepare customer-service and negative-review response scripts.",
    ),
    "example-skill": (
        "示例 Skill",
        "Example Skill",
        "用于验证技能包导入与展示流程的最小占位示例，不提供特定业务领域能力。",
        "A minimal placeholder used to verify the skill-package import and display flow; it provides no business-domain capability.",
    ),
}


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def main() -> int:
    statements = ["BEGIN;"]
    for code, (zh_title, en_title, zh_desc, en_desc) in ROWS.items():
        statements.append(
            "UPDATE gateway.skill SET "
            f"name = {sql_literal(zh_title)}, "
            f"name_en = {sql_literal(en_title)}, "
            f"summary = {sql_literal(zh_desc)}, "
            f"summary_en = {sql_literal(en_desc)}, "
            f"description = {sql_literal(zh_desc)}, "
            f"description_en = {sql_literal(en_desc)} "
            f"WHERE code = {sql_literal(code)} AND deleted_at IS NULL;"
        )
    statements.append("COMMIT;")

    script = "\n".join(statements)
    result = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-U", "postgres", "-d", DB,
         "-v", "ON_ERROR_STOP=1", "-f", "-"],
        input=script.encode("utf-8"),
        capture_output=True,
    )
    sys.stdout.write(result.stdout.decode("utf-8", "replace"))
    sys.stderr.write(result.stderr.decode("utf-8", "replace"))
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())

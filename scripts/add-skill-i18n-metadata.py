"""给 10 个内置包的 SKILL.md 补上 metadata i18n 键，使新包上传即可自动回填中英目录文案。

约定（YCPS §7.1 扩展）：
  - frontmatter `description` 保留为默认语言（中文）的一句话简介
  - `metadata.summary.en` 声明英文简介
  - `metadata.title.zh` / `metadata.title.en` 声明中英标题

只改动 frontmatter 区域，正文原样保留。幂等：已存在的键会被更新为新值。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

SKILLS_ROOT = Path(r"E:\code\yuzhi\system-main\skills")

# code -> (中文标题, 英文标题, 中文简介, 英文简介)
COPY: dict[str, tuple[str, str, str, str]] = {
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

FRONTMATTER = re.compile(r"^---\r?\n(.*?)\r?\n---\r?\n", re.S)


def yaml_quote(value: str) -> str:
    """单引号 YAML 标量：内部单引号需双写。"""
    return "'" + value.replace("'", "''") + "'"


def build_metadata_block(zh_title: str, en_title: str, zh_desc: str, en_desc: str) -> str:
    return (
        "metadata:\n"
        f"  title.zh: {yaml_quote(zh_title)}\n"
        f"  title.en: {yaml_quote(en_title)}\n"
        f"  summary.en: {yaml_quote(en_desc)}\n"
    )


def rewrite(path: Path, zh_title: str, en_title: str, zh_desc: str, en_desc: str) -> str:
    text = path.read_text(encoding="utf-8")
    match = FRONTMATTER.match(text)
    if not match:
        return "no-frontmatter"

    body = match.group(1)
    rest = text[match.end():]

    lines = body.split("\n")
    kept: list[str] = []
    skipping_metadata = False
    for line in lines:
        if line.startswith("metadata:"):
            skipping_metadata = True
            continue
        if skipping_metadata:
            # metadata 下的缩进子项属于旧块，一并丢弃
            if line.startswith((" ", "\t")) or line.strip() == "":
                continue
            skipping_metadata = False
        # description 改为中文默认简介
        if line.startswith("description:"):
            kept.append(f"description: {yaml_quote(zh_desc)}")
            continue
        kept.append(line)

    new_body = "\n".join(kept).rstrip("\n")
    new_text = (
        "---\n"
        + new_body
        + "\n"
        + build_metadata_block(zh_title, en_title, zh_desc, en_desc)
        + "---\n"
        + rest
    )
    path.write_text(new_text, encoding="utf-8")
    return "ok"


def main() -> int:
    changed = 0
    for code, (zh_title, en_title, zh_desc, en_desc) in COPY.items():
        targets = list((SKILLS_ROOT / code).rglob("SKILL.md"))
        if not targets:
            print(f"SKIP {code}: 找不到 SKILL.md")
            continue
        for path in targets:
            status = rewrite(path, zh_title, en_title, zh_desc, en_desc)
            print(f"{status:14s} {path.relative_to(SKILLS_ROOT)}")
            if status == "ok":
                changed += 1
    print(f"\n共改写 {changed} 个 SKILL.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

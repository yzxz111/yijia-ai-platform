# -*- coding: utf-8 -*-
"""生成中文《套餐价格表》。

数据来源：platform schema 副本上实跑 V102→V105 后的 PUBLISHED 版本目录。
只读数据，不改动任何数据库。
"""
from openpyxl import Workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

OUT = "reports/套餐价格表.xlsx"

HEADER_FILL = PatternFill("solid", fgColor="1F4E79")
HEADER_FONT = Font(bold=True, color="FFFFFF", size=11)
TITLE_FONT = Font(bold=True, size=14, color="1F4E79")
GROUP_FILL = PatternFill("solid", fgColor="DDEBF7")
THIN = Side(style="thin", color="B7C9E2")
BORDER = Border(left=THIN, right=THIN, top=THIN, bottom=THIN)
MONEY = "#,##0.00"

# --------------------------------------------------------------------------
# 表一：套餐价格表
# --------------------------------------------------------------------------
PRICE_HEADERS = [
    "套餐名称", "套餐代码", "品类", "版本", "售价（元）", "有效期（天）",
    "核心额度", "音频额度", "API 密钥数", "每分钟请求", "每分钟额度",
    "最大并发", "每日请求", "单次输出上限", "单文件上限", "可用做什么",
]

PRICE_ROWS = [
    ["免费版", "FREE", "文本对话", "v2", 0, 30, "5 万 token", "—", 1,
     "2 次/分", "2 万 token/分", 1, 20, "2,000 token", "5 MiB",
     "文本对话、看图、文件解析（4 个模型共享额度）；每人限领一次"],
    ["入门版", "STARTER", "文本对话", "v3", 29, 30, "200 万 token", "—", 3,
     "20 次/分", "10 万 token/分", 3, "2,000", "4,096 token", "20 MiB",
     "文本对话、看图、文件解析（5 个模型共享额度）"],
    ["高级版", "ADVANCED", "文本对话", "v3", 399, 30, "1,600 万 token", "—", 10,
     "100 次/分", "50 万 token/分", 15, "10,000", "8,192 token", "20 MiB",
     "入门版全部 + 文本专业版、视觉专业版（后两者带专属子额度）"],
    ["企业版", "ENTERPRISE", "文本对话", "v4", 1599, 30, "1 亿 token", "—", 50,
     "300 次/分", "200 万 token/分", 50, "50,000", "16,384 token", "20 MiB",
     "全部 8 个文本 / 视觉 / 文件 / 多模态模型，各带专属子额度"],
    ["视频轻量版", "VIDEO_LITE", "视频生成", "v4", 88, 30, "100 秒视频", "100 秒音频", 2,
     "20 次/分", "60 秒/分", 2, "200", "4,096 token", "20 MiB",
     "480P、720P（MiniMax）视频生成 + 语音识别 / 字幕 / 配音 / 换口型"],
    ["视频轻量版 · 年付", "VIDEO_LITE_ANNUAL", "视频生成", "v4", 880, 365,
     "1,100 秒视频", "100 秒音频", 3, "30 次/分", "60 秒/分", 3, "500",
     "4,096 token", "50 MiB",
     "同视频轻量版，按年付费，折合每秒更便宜"],
    ["视频基础版", "VIDEO_STARTER", "视频生成", "v5", 129, 30, "60 秒视频", "200 秒音频", 2,
     "20 次/分", "60 秒/分", 2, "200", "4,096 token", "20 MiB",
     "480P / 720P / 1080P 三档 + MiniMax 720P + 4 项音频能力"],
    ["视频创作版", "VIDEO_CREATOR", "视频生成", "v5", 399, 30, "190 秒视频", "600 秒音频", 3,
     "60 次/分", "190 秒/分", 5, "500", "8,192 token", "50 MiB",
     "在视频基础版上增加视频专业档 720P，适合稳定产出"],
    ["视频专业版", "VIDEO_PRO", "视频生成", "v6", 1399, 30, "600 秒视频", "1,000 秒音频", 10,
     "120 次/分", "600 秒/分", 10, "1,200", "8,192 token", "100 MiB",
     "全部 6 档视频 + 4 项音频能力，各视频模型带专属子额度"],
    ["图片生成套餐", "IMAGE_BASIC", "图片生成", "v1", 49.9, 30, "48 张图", "—", 2,
     "20 次/分", "480 张/分", 2, "200", "4,096 token", "20 MiB",
     "2K 文生图 / 图生图"],
]

# --------------------------------------------------------------------------
# 表二：可用模型明细
# --------------------------------------------------------------------------
MODEL_HEADERS = ["套餐名称", "套餐代码", "模型代码", "模型名称", "能力", "单模型额度"]

MODEL_NAMES = {
    "text-basic": "文本基础版",
    "text-standard": "文本标准版",
    "text-pro": "文本专业版",
    "text-max": "文本旗舰版",
    "vision-basic": "视觉基础版",
    "vision-pro": "视觉专业版",
    "multimodal-basic": "多模态基础版",
    "file-basic": "文件基础版",
    "image-basic": "图片基础版",
    "video-budget": "视频 · 经济档",
    "video-basic": "视频 · 基础档",
    "video-standard": "视频 · 标准档",
    "video-pro": "视频 · 专业档",
    "video-max": "视频 · 旗舰档",
    "video-minimax": "MiniMax",
    "audio-asr-basic": "语音识别基础版",
    "audio-asr-subtitle": "语音识别 · 字幕版",
    "audio-tts-basic": "配音基础版",
    "audio-retalk": "口型替换",
}

CAPABILITY_CN = {
    ("TEXT",): "文本对话",
    ("TEXT", "IMAGE"): "文本对话 + 看图",
    ("TEXT", "FILE"): "文本对话 + 文件解析",
    ("TEXT", "IMAGE", "FILE"): "文本对话 + 看图 + 文件解析",
    ("TEXT", "IMAGE", "IMAGE_GEN"): "文本对话 + 看图 + 文生图 / 图生图",
    ("TEXT", "IMAGE", "VIDEO"): "文本对话 + 看图 + 视频生成",
    ("AUDIO_TRANSCRIBE",): "语音转文字 / 字幕",
    ("AUDIO_SYNTHESIS",): "文字转语音（配音）",
    ("LIP_SYNC",): "换口型 / 对口型",
}

SHARED = {
    "FREE": "共享套餐额度（5 万 token）",
    "STARTER": "共享套餐额度（200 万 token）",
    "ADVANCED": "共享套餐额度（1,600 万 token）",
    "VIDEO_LITE": "共享套餐额度（100 秒）",
    "VIDEO_LITE_ANNUAL": "共享套餐额度（1,100 秒）",
    "VIDEO_STARTER": "共享套餐额度（60 秒）",
    "VIDEO_CREATOR": "共享套餐额度（190 秒）",
    "IMAGE_BASIC": "共享套餐额度（48 张）",
}
SHARED_AUDIO = "共享音频额度（{n} 秒）"

# (套餐代码, 套餐名称, [(模型代码, 能力元组, 单模型额度)])
ENTITLEMENTS = [
    ("FREE", "免费版", [
        ("text-basic", ("TEXT",), None),
        ("vision-basic", ("TEXT", "IMAGE"), None),
        ("multimodal-basic", ("TEXT", "IMAGE", "FILE"), None),
        ("file-basic", ("TEXT", "FILE"), None),
    ]),
    ("STARTER", "入门版", [
        ("text-basic", ("TEXT",), None),
        ("text-standard", ("TEXT",), None),
        ("vision-basic", ("TEXT", "IMAGE"), None),
        ("multimodal-basic", ("TEXT", "IMAGE", "FILE"), None),
        ("file-basic", ("TEXT", "FILE"), None),
    ]),
    ("ADVANCED", "高级版", [
        ("text-basic", ("TEXT",), None),
        ("text-standard", ("TEXT",), None),
        ("text-pro", ("TEXT",), 6_000_000),
        ("vision-basic", ("TEXT", "IMAGE"), None),
        ("vision-pro", ("TEXT", "IMAGE"), 2_000_000),
        ("multimodal-basic", ("TEXT", "IMAGE", "FILE"), None),
        ("file-basic", ("TEXT", "FILE"), None),
    ]),
    ("ENTERPRISE", "企业版", [
        ("text-basic", ("TEXT",), 60_000_000),
        ("text-standard", ("TEXT",), 10_000_000),
        ("text-pro", ("TEXT",), 10_000_000),
        ("text-max", ("TEXT",), 2_000_000),
        ("vision-basic", ("TEXT", "IMAGE"), 5_000_000),
        ("vision-pro", ("TEXT", "IMAGE"), 5_000_000),
        ("multimodal-basic", ("TEXT", "IMAGE", "FILE"), 3_000_000),
        ("file-basic", ("TEXT", "FILE"), 5_000_000),
    ]),
    ("VIDEO_LITE", "视频轻量版", [
        ("video-budget", ("TEXT", "IMAGE", "VIDEO"), None),
        ("video-minimax", ("TEXT", "IMAGE", "VIDEO"), None),
    ]),
    ("VIDEO_LITE_ANNUAL", "视频轻量版 · 年付", [
        ("video-budget", ("TEXT", "IMAGE", "VIDEO"), None),
        ("video-minimax", ("TEXT", "IMAGE", "VIDEO"), None),
    ]),
    ("VIDEO_STARTER", "视频基础版", [
        ("video-budget", ("TEXT", "IMAGE", "VIDEO"), None),
        ("video-basic", ("TEXT", "IMAGE", "VIDEO"), None),
        ("video-standard", ("TEXT", "IMAGE", "VIDEO"), None),
        ("video-minimax", ("TEXT", "IMAGE", "VIDEO"), None),
    ]),
    ("VIDEO_CREATOR", "视频创作版", [
        ("video-budget", ("TEXT", "IMAGE", "VIDEO"), None),
        ("video-basic", ("TEXT", "IMAGE", "VIDEO"), None),
        ("video-standard", ("TEXT", "IMAGE", "VIDEO"), None),
        ("video-pro", ("TEXT", "IMAGE", "VIDEO"), None),
        ("video-minimax", ("TEXT", "IMAGE", "VIDEO"), None),
    ]),
    ("VIDEO_PRO", "视频专业版", [
        ("video-budget", ("TEXT", "IMAGE", "VIDEO"), 150),
        ("video-standard", ("TEXT", "IMAGE", "VIDEO"), 120),
        ("video-basic", ("TEXT", "IMAGE", "VIDEO"), 100),
        ("video-pro", ("TEXT", "IMAGE", "VIDEO"), 80),
        ("video-max", ("TEXT", "IMAGE", "VIDEO"), 150),
        ("video-minimax", ("TEXT", "IMAGE", "VIDEO"), None),
    ]),
    ("IMAGE_BASIC", "图片生成套餐", [
        ("image-basic", ("TEXT", "IMAGE", "IMAGE_GEN"), None),
    ]),
]

AUDIO_MODELS = [
    ("audio-asr-basic", ("AUDIO_TRANSCRIBE",)),
    ("audio-asr-subtitle", ("AUDIO_TRANSCRIBE",)),
    ("audio-tts-basic", ("AUDIO_SYNTHESIS",)),
    ("audio-retalk", ("LIP_SYNC",)),
]
VIDEO_PLAN_AUDIO_SECONDS = {
    "VIDEO_LITE": 100,
    "VIDEO_LITE_ANNUAL": 100,
    "VIDEO_STARTER": 200,
    "VIDEO_CREATOR": 600,
    "VIDEO_PRO": 1000,
}


def model_quota_text(plan_code, quota, shared_default):
    if quota is None:
        return shared_default
    if plan_code == "ENTERPRISE":
        return f"{quota // 10000} 万 token"
    if plan_code in VIDEO_PLAN_AUDIO_SECONDS:
        return f"{quota} 秒"
    return f"{quota:,} token"


def build_model_rows():
    rows = []
    for plan_code, plan_name, models in ENTITLEMENTS:
        shared_default = SHARED.get(plan_code, "共享套餐额度")
        for code, caps, quota in models:
            rows.append([
                plan_name, plan_code, code, MODEL_NAMES[code],
                CAPABILITY_CN[caps],
                model_quota_text(plan_code, quota, shared_default),
            ])
        if plan_code in VIDEO_PLAN_AUDIO_SECONDS:
            audio_shared = SHARED_AUDIO.format(n=VIDEO_PLAN_AUDIO_SECONDS[plan_code])
            for code, caps in AUDIO_MODELS:
                rows.append([
                    plan_name, plan_code, code, MODEL_NAMES[code],
                    CAPABILITY_CN[caps], audio_shared,
                ])
    return rows


# --------------------------------------------------------------------------
# 表三：模型零售单价
# --------------------------------------------------------------------------
RATE_HEADERS = ["模型代码", "模型名称", "计费维度", "档位", "零售单价"]
RATE_ROWS = [
    ["chat-standard", "普通（客户端通道）", "文本（token）", "—", "输入 ¥4.30 / 输出 ¥4.30（每百万 token）"],
    ["chat-deep", "深度（客户端通道）", "文本（token）", "—", "输入 ¥6.90 / 输出 ¥6.90（每百万 token）"],
    ["text-basic", "文本基础版", "文本（token）", "—", "输入 ¥3.00 / 输出 ¥11.00（每百万 token）"],
    ["text-pro", "文本专业版", "文本（token）", "—", "输入 ¥12.00 / 输出 ¥36.00（每百万 token）"],
    ["text-standard", "文本标准版", "文本（token）", "—", "输入 ¥55.00 / 输出 ¥220.00（每百万 token）"],
    ["text-max", "文本旗舰版", "文本（token）", "—", "输入 ¥340.00 / 输出 ¥1,000.00（每百万 token）"],
    ["vision-basic", "视觉基础版", "文本（token）", "—", "输入 ¥22.00 / 输出 ¥85.00（每百万 token）"],
    ["vision-pro", "视觉专业版", "文本（token）", "—", "输入 ¥30.00 / 输出 ¥220.00（每百万 token）"],
    ["multimodal-basic", "多模态基础版", "文本（token）", "—", "输入 ¥70.00 / 输出 ¥280.00（每百万 token）"],
    ["file-basic", "文件基础版", "文本（token）", "—", "输入 ¥70.00 / 输出 ¥280.00（每百万 token）"],
    ["image-basic", "图片基础版", "图片（张）", "2K", "¥0.89 / 张"],
    ["video-budget", "视频 · 经济档", "视频（秒）", "480P", "¥0.50 / 秒"],
    ["video-minimax", "MiniMax", "视频（秒）", "720P", "¥0.83 / 秒"],
    ["video-basic", "视频 · 基础档", "视频（秒）", "720P", "¥1.00 / 秒"],
    ["video-pro", "视频 · 专业档", "视频（秒）", "720P", "¥1.30 / 秒"],
    ["video-standard", "视频 · 标准档", "视频（秒）", "1080P", "¥1.80 / 秒"],
    ["video-max", "视频 · 旗舰档", "视频（秒）", "1080P", "¥5.00 / 秒"],
    ["audio-asr-basic", "语音识别基础版", "音频（秒）", "—", "¥0.05 / 秒"],
    ["audio-asr-subtitle", "语音识别 · 字幕版", "音频（秒）", "—", "¥0.05 / 秒"],
    ["audio-tts-basic", "配音基础版", "音频（秒）", "—", "¥0.05 / 秒"],
    ["audio-retalk", "口型替换", "音频（秒）", "—", "¥0.14 / 秒"],
]


def style_sheet(sheet, headers, rows, widths, title, money_cols=(), numeric_cols=()):
    sheet.append([title])
    sheet.merge_cells(start_row=1, start_column=1, end_row=1, end_column=len(headers))
    title_cell = sheet.cell(row=1, column=1)
    title_cell.font = TITLE_FONT
    title_cell.alignment = Alignment(vertical="center")
    sheet.row_dimensions[1].height = 24

    sheet.append(headers)
    for cell in sheet[2]:
        cell.fill = HEADER_FILL
        cell.font = HEADER_FONT
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
        cell.border = BORDER
    sheet.row_dimensions[2].height = 30

    for row in rows:
        sheet.append(row)

    for row in sheet.iter_rows(min_row=3, max_row=sheet.max_row, max_col=len(headers)):
        for cell in row:
            cell.border = BORDER
            cell.alignment = Alignment(vertical="top", wrap_text=True)

    for col_idx in money_cols:
        for r in range(3, sheet.max_row + 1):
            sheet.cell(row=r, column=col_idx).number_format = MONEY
    for col_idx in numeric_cols:
        for r in range(3, sheet.max_row + 1):
            sheet.cell(row=r, column=col_idx).alignment = Alignment(
                horizontal="center", vertical="top")

    for i, width in enumerate(widths, start=1):
        sheet.column_dimensions[get_column_letter(i)].width = width

    sheet.freeze_panes = "A3"


def main():
    wb = Workbook()

    ws1 = wb.active
    ws1.title = "套餐价格表"
    style_sheet(ws1, PRICE_HEADERS, PRICE_ROWS,
                [18, 20, 10, 7, 11, 11, 14, 12, 11, 11, 15, 9, 10, 13, 11, 46],
                "一加AI中台 · 套餐价格表", money_cols=(5,), numeric_cols=(4, 6, 9, 12))

    ws2 = wb.create_sheet("可用模型明细")
    style_sheet(ws2, MODEL_HEADERS, build_model_rows(),
                [18, 20, 20, 20, 30, 22],
                "各套餐可调用的模型与单模型额度")

    ws3 = wb.create_sheet("模型零售单价")
    style_sheet(ws3, RATE_HEADERS, RATE_ROWS,
                [20, 20, 14, 8, 40],
                "模型别名零售单价（额度消耗的计价口径）")

    note_row = ws3.max_row + 2
    notes = [
        "说明：",
        "1. 售价单位为人民币元；数据库以「分」存储（如 ¥88.00 = 8800 分）。",
        "2. 套餐额度单位随品类变化：文本套餐为 token，视频套餐为秒，图片套餐为张；音频秒为独立额度。",
        "3. 「每分钟额度」的单位同样随品类变化：文本套餐为 token/分，视频套餐为秒/分，图片套餐为张/分。",
        "4. 文本类套餐在激活时还会自动获得 chat-standard（普通）与 chat-deep（深度）两条客户端文本通道。",
        "5. 视频套餐的音频额度随套餐发放（迁移 V103 起），含语音识别、字幕、配音、换口型四项能力。",
        "6. 全部 10 个套餐均为在售状态，每个套餐恰好一个已发布版本；免费版标记为每人限领一次。",
    ]
    for i, text in enumerate(notes):
        cell = ws3.cell(row=note_row + i, column=1, value=text)
        cell.font = Font(bold=(i == 0), size=10)
        cell.alignment = Alignment(vertical="top")

    wb.save(OUT)
    print("saved", OUT)
    print("sheets", wb.sheetnames)


if __name__ == "__main__":
    main()

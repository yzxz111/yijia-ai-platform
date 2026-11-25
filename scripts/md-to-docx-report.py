#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Render a report Markdown file into a formatted DOCX (CJK-aware).

Deliberately a small, project-local converter: it understands exactly the
constructs used by the progress reports -- headings, paragraphs, bullets,
numbered items, blockquotes, pipe tables, fenced code blocks and inline
`code` / **bold** -- rather than a general-purpose Markdown engine.

Layout notes (each of these is load-bearing against right-edge clipping):
  * A4 page with 2.0 cm side margins, overriding python-docx's US-Letter
    default, because the renderer paginates to A4.
  * Every text block carries a right indent, so the writing width is narrower
    than the printable area.
  * Long ASCII identifiers receive zero-width break opportunities; a 24+
    character run with no break point cannot wrap and overruns the line.
  * Tables are a fixed 15.0 cm with ``w:tblW`` written in the position
    ``CT_TblPrBase`` requires, and per-column widths weighted by content.
"""
import re
import sys
from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor, Twips

BODY_LATIN = "Times New Roman"
BODY_CJK = "宋体"
HEAD_LATIN = "Segoe UI"
HEAD_CJK = "微软雅黑"
MONO_LATIN = "Consolas"
MONO_CJK = "微软雅黑"

MONO_BG = "F5F5F5"
HEAD_BG = "DEEAF6"

BODY_RIGHT_INDENT = Cm(2.0)
TABLE_WIDTH = Cm(15.0)
BODY_SIZE = 10.5
TABLE_SIZE = 9.5


def set_run_font(run, latin=BODY_LATIN, cjk=BODY_CJK, size=None,
                 bold=None, color=None):
    run.font.name = latin
    rpr = run._element.get_or_add_rPr()
    rfonts = rpr.find(qn("w:rFonts"))
    if rfonts is None:
        rfonts = rpr.makeelement(qn("w:rFonts"), {})
        rpr.insert(0, rfonts)
    rfonts.set(qn("w:ascii"), latin)
    rfonts.set(qn("w:hAnsi"), latin)
    rfonts.set(qn("w:eastAsia"), cjk)
    rfonts.set(qn("w:cs"), latin)
    if size is not None:
        run.font.size = Pt(size)
    if bold is not None:
        run.font.bold = bold
    if color is not None:
        run.font.color.rgb = color


def soften(text):
    """Insert zero-width break opportunities into long ASCII identifiers."""
    def extend(match):
        token = match.group(0)
        if len(token) < 24:
            return token
        parts = re.split(r"([_\-/:${}])", token)
        return "".join(part + ("\u200b" if part in "_/-:${}" else "")
                       for part in parts)

    return re.sub(r"[A-Za-z0-9_\-/:${}]{24,}", extend, text)


TOKEN = re.compile(r"(\*\*.+?\*\*|`[^`]+`)")


def add_inline(paragraph, text, base_size=BODY_SIZE, base_bold=None,
               latin=BODY_LATIN, cjk=BODY_CJK):
    """Add text to a paragraph honouring **bold** and `code` spans."""
    for piece in TOKEN.split(text):
        if not piece:
            continue
        if piece.startswith("**") and piece.endswith("**") and len(piece) > 4:
            run = paragraph.add_run(soften(piece[2:-2]))
            set_run_font(run, latin, cjk, base_size, True)
        elif piece.startswith("`") and piece.endswith("`") and len(piece) > 2:
            run = paragraph.add_run(soften(piece[1:-1]))
            set_run_font(run, MONO_LATIN, MONO_CJK, base_size - 0.5, None,
                         RGBColor(0xA3, 0x1D, 0x1D))
        else:
            run = paragraph.add_run(soften(piece))
            set_run_font(run, latin, cjk, base_size, base_bold)


def shade_cell(cell, hex_fill):
    tcpr = cell._tc.get_or_add_tcPr()
    shd = tcpr.makeelement(qn("w:shd"), {
        qn("w:val"): "clear", qn("w:color"): "auto", qn("w:fill"): hex_fill,
    })
    tcpr.append(shd)


def shade_paragraph(paragraph, hex_fill):
    ppr = paragraph._p.get_or_add_pPr()
    shd = ppr.makeelement(qn("w:shd"), {
        qn("w:val"): "clear", qn("w:color"): "auto", qn("w:fill"): hex_fill,
    })
    ppr.append(shd)


def indent(para):
    para.paragraph_format.right_indent = BODY_RIGHT_INDENT


def set_repeat_header(row):
    trpr = row._tr.get_or_add_trPr()
    header = trpr.makeelement(qn("w:tblHeader"), {qn("w:val"): "true"})
    trpr.append(header)


def set_table_width(table, total_twips):
    """Declare the table's total width, in a schema-legal position.

    python-docx appends to ``tblPr`` blindly, which places ``w:tblW`` after
    ``w:tblLook``. CT_TblPrBase orders children as tblStyle, tblpPr,
    tblOverlap, bidiVisual, tblStyleRowBandSize, tblStyleColBandSize, tblW, jc,
    tblCellSpacing, tblInd, tblBorders, shd, tblLayout, tblCellMar, tblLook, so
    the width is written directly after the style and tblLayout is not repeated.
    """
    tblpr = table._tbl.tblPr
    for tag in ("w:tblW", "w:jc", "w:tblInd", "w:tblBorders", "w:shd",
                "w:tblLayout", "w:tblCellMar", "w:tblLook"):
        for el in tblpr.findall(qn(tag)):
            tblpr.remove(el)

    tbl_w = tblpr.makeelement(qn("w:tblW"), {
        qn("w:type"): "dxa", qn("w:w"): str(total_twips)})
    style = tblpr.find(qn("w:tblStyle"))
    if style is not None:
        style.addnext(tbl_w)
    else:
        tblpr.insert(0, tbl_w)

    tblpr.append(tblpr.makeelement(qn("w:tblLook"), {
        qn("w:firstRow"): "1", qn("w:lastRow"): "0",
        qn("w:firstColumn"): "1", qn("w:lastColumn"): "0",
        qn("w:noHBand"): "0", qn("w:noVBand"): "1", qn("w:val"): "04A0"}))


def split_row(line):
    body = line.strip()
    if body.startswith("|"):
        body = body[1:]
    if body.endswith("|"):
        body = body[:-1]
    cells, buf, esc = [], "", False
    for ch in body:
        if esc:
            buf += ch
            esc = False
        elif ch == "\\":
            esc = True
        elif ch == "|":
            cells.append(buf.strip())
            buf = ""
        else:
            buf += ch
    cells.append(buf.strip())
    return cells


def is_separator(line):
    return bool(re.fullmatch(r"\|?[\s:|-]+\|?", line.strip())) and "-" in line


def add_table(document, rows):
    """Render a pipe table at a fixed total width."""
    cols = max(len(r) for r in rows)
    table = document.add_table(rows=1, cols=cols)
    table.style = "Table Grid"
    table.autofit = False
    set_table_width(table, int(TABLE_WIDTH.twips))

    weights = []
    for j in range(cols):
        longest = 11
        for row in rows:
            if j < len(row):
                longest = max(longest, len(row[j]))
        weights.append(min(max(longest, 11), 60))
    total_weight = float(sum(weights))
    widths = [int(TABLE_WIDTH.twips * w / total_weight) for w in weights]

    grid = table._tbl.find(qn("w:tblGrid"))
    if grid is not None:
        for gc, w in zip(grid.findall(qn("w:gridCol")), widths):
            gc.set(qn("w:w"), str(w))

    header, body_rows = rows[0], rows[1:]
    for j, text in enumerate(header[:cols]):
        cell = table.rows[0].cells[j]
        cell.width = Twips(widths[j])
        cell.text = ""
        para = cell.paragraphs[0]
        para.paragraph_format.space_after = Pt(0)
        add_inline(para, text, base_size=TABLE_SIZE, base_bold=True)
        shade_cell(cell, HEAD_BG)
    set_repeat_header(table.rows[0])

    for row in body_rows:
        cells = table.add_row().cells
        for j in range(cols):
            text = row[j] if j < len(row) else ""
            cell = cells[j]
            cell.width = Twips(widths[j])
            cell.text = ""
            para = cell.paragraphs[0]
            para.paragraph_format.space_after = Pt(0)
            add_inline(para, text, base_size=TABLE_SIZE)
    document.add_paragraph()
    return table


def add_code_block(document, lines):
    for text in lines:
        para = document.add_paragraph()
        pf = para.paragraph_format
        pf.space_before = Pt(0)
        pf.space_after = Pt(0)
        pf.left_indent = Cm(0.5)
        pf.right_indent = BODY_RIGHT_INDENT
        pf.line_spacing = 1.0
        run = para.add_run(soften(text) if text else " ")
        set_run_font(run, MONO_LATIN, MONO_CJK, 9)
        shade_paragraph(para, MONO_BG)
    document.add_paragraph()


def configure_document(document, title):
    section = document.sections[0]
    section.page_height = Cm(29.7)
    section.page_width = Cm(21.0)
    section.top_margin = Cm(2.0)
    section.bottom_margin = Cm(2.0)
    section.left_margin = Cm(2.0)
    section.right_margin = Cm(2.0)

    normal = document.styles["Normal"]
    normal.font.size = Pt(BODY_SIZE)
    normal.font.name = BODY_LATIN
    normal.element.rPr.rFonts.set(qn("w:eastAsia"), BODY_CJK)
    normal.paragraph_format.space_after = Pt(4)
    normal.paragraph_format.line_spacing = 1.25

    for name, size in (("Heading 1", 16), ("Heading 2", 13.5),
                       ("Heading 3", 11.5), ("Heading 4", 10.5)):
        style = document.styles[name]
        style.font.size = Pt(size)
        style.font.name = HEAD_LATIN
        style.font.color.rgb = RGBColor(0x1F, 0x3B, 0x63)
        style.font.bold = True
        style.element.rPr.rFonts.set(qn("w:eastAsia"), HEAD_CJK)
        style.paragraph_format.space_before = Pt(12 if name == "Heading 1" else 10)
        style.paragraph_format.space_after = Pt(4)

    footer = section.footer.paragraphs[0]
    footer.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = footer.add_run(title)
    set_run_font(run, HEAD_LATIN, HEAD_CJK, 8, None, RGBColor(0x88, 0x88, 0x88))


def convert(md_path: Path, docx_path: Path, title: str):
    lines = md_path.read_text(encoding="utf-8").splitlines()

    document = Document()
    configure_document(document, title)

    buf_table, buf_code = [], []
    in_code = False
    first_heading = True

    def flush_table():
        if buf_table:
            add_table(document, buf_table[:])
            buf_table.clear()

    def flush_code():
        if buf_code:
            add_code_block(document, buf_code[:])
            buf_code.clear()

    for raw in lines:
        line = raw.rstrip()

        if line.startswith("```"):
            if in_code:
                flush_code()
                in_code = False
            else:
                flush_table()
                in_code = True
            continue
        if in_code:
            buf_code.append(line)
            continue

        if line.strip().startswith("|") and line.strip().endswith("|"):
            if not is_separator(line):
                buf_table.append(split_row(line))
            continue
        flush_table()

        if not line.strip():
            continue
        if line.strip() in ("---", "***", "___"):
            continue

        heading = re.match(r"^(#{1,6})\s+(.*)$", line)
        if heading:
            level = len(heading.group(1))
            text = heading.group(2)
            if level == 1 and first_heading:
                para = document.add_paragraph()
                para.alignment = WD_ALIGN_PARAGRAPH.CENTER
                indent(para)
                add_inline(para, text, base_size=20, base_bold=True,
                           latin=HEAD_LATIN, cjk=HEAD_CJK)
                for run in para.runs:
                    run.font.color.rgb = RGBColor(0x1F, 0x3B, 0x63)
                para.paragraph_format.space_after = Pt(10)
                first_heading = False
            else:
                document.add_heading(text, level=min(level, 4))
            continue

        quote = re.match(r"^>\s?(.*)$", line)
        if quote:
            para = document.add_paragraph()
            para.paragraph_format.left_indent = Cm(0.6)
            indent(para)
            add_inline(para, quote.group(1), base_size=9.5)
            for run in para.runs:
                run.font.color.rgb = RGBColor(0x59, 0x59, 0x59)
            continue

        bullet = re.match(r"^(\s*)[-*]\s+(.*)$", line)
        if bullet:
            para = document.add_paragraph(style="List Bullet")
            indent(para)
            add_inline(para, bullet.group(2))
            continue

        numbered = re.match(r"^(\s*)(\d+)\.\s+(.*)$", line)
        if numbered:
            para = document.add_paragraph()
            para.paragraph_format.left_indent = Cm(0.75)
            indent(para)
            add_inline(para, f"{numbered.group(2)}. {numbered.group(3)}")
            continue

        para = document.add_paragraph()
        indent(para)
        add_inline(para, line)

    flush_table()
    flush_code()

    document.save(docx_path)
    return docx_path


if __name__ == "__main__":
    src = Path(sys.argv[1])
    dst = Path(sys.argv[2])
    heading = sys.argv[3] if len(sys.argv) > 3 else src.stem
    out = convert(src, dst, heading)
    print(f"wrote {out} ({out.stat().st_size} bytes)")

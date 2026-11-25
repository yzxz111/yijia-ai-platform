"""Build one reviewed YCPS 1.1 Expert; never modify the published catalog.

Usage: python scripts/build-expert-expansion.py --code academic-writing-mentor [--check]
Render assets with render-expert-expansion.cjs first. The source specification,
reference files and assets are preserved next to the deterministic archive.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import zipfile
from io import BytesIO
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1] / "fixtures" / "expert-expansion"
VERSION = "1.0.0"


def compact(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def formatted(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def digest(value):
    return hashlib.sha256(value).hexdigest()


def run(text, size=14, color=None, weight=None):
    result = {"text": text, "fontSize": size}
    if color:
        result["color"] = color
    if weight:
        result["fontWeight"] = weight
    return result


def node(field, kind, x, y, width, height, runs=None, **extra):
    result = {"id": field.replace(".", "-"), "field": field, "kind": kind,
              "x": x, "y": y, "width": width, "height": height, **extra}
    if runs is not None:
        result["runs"] = runs
    return result


def canvas(spec, lang):
    p = spec["preview"][lang]
    prefix = "一句话" if lang == "zh" else "One sentence"
    assert p["heading"].startswith(prefix)
    nodes = [
        node("preview.heading", "text", 32, 28, 560, 42,
             [run(prefix, 22, "#5574b5", 700), run(p["heading"][len(prefix):], 22, weight=700)]),
        node("preview.scene", "text", 32, 78, 536, 28, [run(p["scene"], 13, "#64748b")]),
        node("preview.request", "card", 32, 116, 536, 88, [run(p["request"], 14, "#182a47")]),
        node("preview.material", "card", 32, 204, 536, 52, [run(p["material"], 13, "#64748b")]),
        node("preview.outputTitle", "text", 48, 288, 520, 36, [run(p["outputTitle"], 18, "#182a47", 700)]),
        node("preview.intro", "text", 48, 328, 520, 42, [run(p["intro"]["text"], 13, "#3152a0")]),
        node("preview.table", "table", 48, 380, 520, 172,
             [run(" / ".join(p["table"]["headers"]), 13, "#3152a0", 700)], table=p["table"],
             style={"backgroundColor": "#ffffff", "borderColor": "#d8e2f3", "borderRadius": 8, "overflow": "auto"}),
        node("title", "text", 710, 150, 338, 38, [run(spec["title"][lang], 21, "#182a47", 700)]),
        node("description", "text", 638, 272, 410, 70, [run(spec["description"][lang], 14, "#475569")]),
        node("usageGuide", "text", 638, 352, 410, 92, [run(spec["usageGuide"][lang], 13, "#475569")]),
        node("asset.illustration", "image", 638, 0, 410, 136, assetRole="illustration"),
        node("asset.icon", "image", 638, 150, 58, 58, assetRole="icon"),
    ]
    for i, text in enumerate(spec["taskTypes"][lang]):
        nodes.append(node(f"tag.{i}", "tag", 710 + (i % 2) * 170, 198 + (i // 2) * 28, 160, 22,
                          [run(text, 12)]))
    for i, text in enumerate(spec["examplePrompts"][lang]):
        nodes.append(node(f"prompt.{i}", "card", 638, 458 + i * 78, 410, 70, [run(text, 13, "#475569")]))
    for i, text in enumerate(p["outputParagraphs"]):
        nodes.append(node(f"preview.outputParagraphs.{i}", "text", 48, 566 + i * 62, 520, 56,
                          [run(text, 13, "#475569")]))
    expected = {"title": spec["title"][lang], "description": spec["description"][lang],
                "usageGuide": spec["usageGuide"][lang], "preview.intro": p["intro"]["text"],
                **{f"preview.{key}": p[key] for key in ("heading", "scene", "request", "material", "outputTitle")},
                **{f"tag.{i}": v for i, v in enumerate(spec["taskTypes"][lang])},
                **{f"prompt.{i}": v for i, v in enumerate(spec["examplePrompts"][lang])},
                **{f"preview.outputParagraphs.{i}": v for i, v in enumerate(p["outputParagraphs"])}}
    assert len({n["id"] for n in nodes}) == len(nodes)
    for n in nodes:
        assert n["x"] >= 0 and n["y"] >= 0
        assert n["x"] + n["width"] <= 1080 and n["y"] + n["height"] <= 704
        if n["field"] in expected:
            assert "".join(r["text"] for r in n["runs"]) == expected[n["field"]]
    for i, a in enumerate(nodes):
        for b in nodes[i + 1:]:
            assert not (a["x"] < b["x"] + b["width"] and b["x"] < a["x"] + a["width"]
                        and a["y"] < b["y"] + b["height"] and b["y"] < a["y"] + a["height"]), (a["field"], b["field"])
    return {"version": 1, "width": 1080, "height": 704, "nodes": nodes}


def metadata(spec):
    result = {}
    for lang in ("zh", "en"):
        assert 0 < len(spec["title"][lang]) <= 128
        assert 0 < len(spec["summary"][lang]) <= 512
        for field in ("title", "summary", "description"):
            result[f"{field}.{lang}"] = spec[field][lang]
        result[f"expert.usageGuide.{lang}"] = spec["usageGuide"][lang]
        for key, source in (("taskType", "taskTypes"), ("examplePrompt", "examplePrompts")):
            assert len(spec[source][lang]) == 3
            for i, text in enumerate(spec[source][lang], 1):
                assert 0 < len(text) <= (64 if key == "taskType" else 300)
                result[f"expert.{key}.{i:02d}.{lang}"] = text
        p = spec["preview"][lang]
        for field in ("heading", "scene", "request", "material", "outputTitle"):
            result[f"expert.preview.{field}.{lang}"] = p[field]
        assert len(p["outputParagraphs"]) == 2
        for i, text in enumerate(p["outputParagraphs"], 1):
            result[f"expert.preview.outputParagraph.{i:02d}.{lang}"] = text
        table = p["table"]
        assert len(table["headers"]) == 3 and sum(table["columnWidths"]) == 100
        assert len(table["columnWidths"]) == 3 and all(isinstance(x, int) and 1 <= x <= 100 for x in table["columnWidths"])
        assert len(table["rows"]) == 3 and all(len(row) == 3 for row in table["rows"])
        assert all(isinstance(x, str) and x.strip() for row in table["rows"] for x in row)
        result[f"expert.preview.intro.v1.{lang}"] = compact(p["intro"])
        result[f"expert.preview.table.v1.{lang}"] = compact(table)
        result[f"expert.canvas.v1.{lang}"] = compact(canvas(spec, lang))
    result.update({"expert.asset.icon.path": "assets/icon.png",
                   "expert.asset.illustration.path": "assets/illustration.png",
                   "expert.asset.exampleImage.path": "assets/example-image.png",
                   "expert.asset.exampleImage.locale": "zh"})
    expert = [value for key, value in result.items() if key.startswith("expert.")]
    assert len(expert) <= 64 and sum(len(v.encode("utf-8")) for v in expert) <= 32768
    for key, value in result.items():
        limit = 32768 if ".canvas." in key else 16384 if ".table." in key else 2048 if ".intro." in key else 1024
        assert isinstance(value, str) and 0 < len(value) <= limit, key
    return result


def build(code):
    assert re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", code) and len(code) <= 64
    spec = json.loads((ROOT / "specs" / f"{code}.json").read_text("utf-8"))
    assert spec["code"] == code
    output = ROOT / code
    values = metadata(spec)
    plugin = {"$schema": "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json", "name": code,
              "version": VERSION, "description": spec["description"]["en"], "author": {"name": "Yuzhi"},
              "license": "UNLICENSED", "keywords": spec["keywords"], "extensions": {"com.yuzhihuihe.ycps": {
                  "ycpsVersion": "1.1.0", "kind": "EXPERT", "entrySkill": code,
                  "manifest": "./com.yuzhihuihe.ycps/manifest.json", "requiredFeatures": ["expert-presentation-v1"]}}}
    runtime = {"$schema": "https://schemas.yuzhihuihe.com/ycps/1.1.0/expert-manifest.schema.json",
               "entryAgent": "main", "agents": [{"id": "main", "role": "LEAD", "instructions": {"type": "ENTRY_SKILL"}, "bindings": []}],
               "dependencies": [], "permissionRequests": {"tools": [], "network": [], "filesystem": []},
               "hostRequirements": {"minClientVersion": "2026.6.30"}}
    body = spec["instructions"].strip()
    assert len(body) >= 900 and len(body.splitlines()) < 500
    skill = "\n".join(["---", f"name: {json.dumps(code)}", f"description: {json.dumps(spec['description']['en'])}",
                        "metadata:", *[f"  {key}: {json.dumps(value, ensure_ascii=False)}" for key, value in sorted(values.items())],
                        "---", "", body, ""] ).encode("utf-8")
    assert len(skill) <= 256 * 1024
    entries = {"plugin.json": formatted(plugin), "com.yuzhihuihe.ycps/manifest.json": formatted(runtime),
               f"skills/{code}/SKILL.md": skill}
    assets = {}
    for role, filename, size, limit in (("icon", "icon.png", (240, 240), 128 * 1024),
                                      ("illustration", "illustration.png", (1440, 480), 640 * 1024),
                                      ("exampleImage", "example-image.png", (1072, 280), 512 * 1024)):
        raw = (output / "source-assets" / filename).read_bytes()
        with Image.open(BytesIO(raw)) as im:
            assert im.format == "PNG" and im.size == size
            im.verify()
        assert len(raw) <= limit
        package_path = f"skills/{code}/assets/{filename}"
        entries[package_path] = raw
        assets[role] = {"path": package_path, "width": size[0], "height": size[1], "bytes": len(raw), "sha256": digest(raw)}
    assert sum(a["bytes"] for a in assets.values()) <= 1280 * 1024
    reference_root = output / "references"
    if reference_root.exists():
        for path in sorted(reference_root.rglob("*.md")):
            relative = path.relative_to(reference_root).as_posix()
            entries[f"skills/{code}/references/{relative}"] = path.read_bytes()
    expanded = sum(len(raw) for raw in entries.values())
    assert expanded <= 5 * 1024 * 1024
    archive = BytesIO()
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name, raw in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            z.writestr(info, raw, compresslevel=9)
    raw = archive.getvalue()
    assert len(raw) <= 2 * 1024 * 1024
    projection = {"name": spec["title"]["zh"], "nameEn": spec["title"]["en"],
                  **{key + suffix: spec[key][lang] for key in ("summary", "description", "taskTypes", "usageGuide", "examplePrompts", "preview")
                     for lang, suffix in (("zh", ""), ("en", "En"))},
                  "canvas": {lang: canvas(spec, lang) for lang in ("zh", "en")}, "exampleImageLocale": "zh"}
    report = {"code": code, "version": VERSION, "kind": "EXPERT", "ycpsVersion": "1.1.0",
              "artifactSha256": digest(raw), "compressedBytes": len(raw), "expandedBytes": expanded,
              "entryCount": len(entries), "assets": assets,
              "presentation": {"locales": ["zh", "en"], "canvasNodes": {lang: len(canvas(spec, lang)["nodes"]) for lang in ("zh", "en")}},
              "runtime": {"entryAgent": "main", "dependencies": [], "minClientVersion": "2026.6.30"},
              "entries": {name: {"bytes": len(value), "sha256": digest(value)} for name, value in sorted(entries.items())},
              "validationScope": "Deterministic packaging, identity, metadata budgets, canvas geometry/runs, decoded asset sizes. See acceptance results for production parser tests.",
              "cloudPublished": False}
    return output, {f"{code}-{VERSION}.ycps.zip": raw, "expected-admin-presentation.json": formatted(projection),
                    "package-report.json": formatted(report), "SKILL.md": skill}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--code", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    root, outputs = build(args.code)
    for name, content in outputs.items():
        target = root / name
        if args.check:
            if not target.is_file() or target.read_bytes() != content:
                raise SystemExit(f"Reproduction mismatch: {target}")
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
    report = json.loads(outputs["package-report.json"])
    print(f"{'CHECK' if args.check else 'BUILD'} {args.code}: {report['compressedBytes']} B, sha256={report['artifactSha256']}")


if __name__ == "__main__":
    main()

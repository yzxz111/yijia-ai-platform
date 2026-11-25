"""Validate and materialize the six curated Expert catalog fixtures deterministically.

The cloud migrations are the source of truth for the bilingual presentation copy.  V73 supplies
the six complete legacy pages and V89 (originally V81) upgrades the three pages that originally contained tables
to editable semantic table/intro blocks.  The production
Java portable-revision compiler is the authoritative YCPS package serializer; this Python tool
never renders or rewrites package bytes.  It validates the exported package semantics, checks the
three exported assets, and materializes the corresponding catalog metadata and hashes in one
deterministic pass.  It is intentionally limited to the six curated Expert rows; SKILL packages
and all unrelated fixture media are left untouched.

Usage::

    python scripts/build-curated-expert-fixtures.py
    python scripts/build-curated-expert-fixtures.py --check-only

The bundled Pillow runtime is used only to validate/materialize the exported illustration assets.
The script does not use filesystem timestamps, ZIP comments/extras, random names, or the current
clock.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import struct
import sys
import zipfile
from pathlib import Path
from typing import Any

from PIL import Image


SYSTEM_ROOT = Path(__file__).resolve().parent.parent
SNAPSHOT_ROOT = SYSTEM_ROOT / "fixtures" / "catalog-snapshot"
CATALOG_PATH = SNAPSHOT_ROOT / "catalog.json"
MIGRATION_PATH = (
    SYSTEM_ROOT
    / "gateway-service"
    / "src"
    / "main"
    / "resources"
    / "db"
    / "migration"
    / "V73__backfill_curated_expert_presentations.sql"
)
TABLE_MIGRATION_PATH = (
    SYSTEM_ROOT
    / "gateway-service"
    / "src"
    / "main"
    / "resources"
    / "db"
    / "migration"
    / "V89__restore_curated_expert_preview_tables.sql"
)

# Published package revision expected in the exported catalog snapshot.  Keep this map in sync
# with the six production rows; `expert_portable_revision` is profile provenance, not this value.
CURATED_REVISIONS = {
    "ai-office-assistant": 9,
    "content-summarizer": 7,
    "content-writing-expert": 4,
    "document-review-expert": 6,
    "sales-analysis-expert": 6,
    "social-media-operations-expert": 4,
}
CURATED_CODES = tuple(CURATED_REVISIONS)
OLD_ILLUSTRATION_SIZE = (2172, 724)
ILLUSTRATION_SIZE = (1440, 480)
ILLUSTRATION_MAX_BYTES = 640 * 1024
ZIP_DATE = (1980, 1, 2, 0, 0, 0)
PLUGIN_SCHEMA = "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json"
RUNTIME_SCHEMA = "https://schemas.yuzhihuihe.com/ycps/1.1.0/expert-manifest.schema.json"
MIN_CLIENT_VERSION = "2026.6.30"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def md5(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()  # noqa: S324 - fixture digest, not security


def sql_string(token: str) -> str:
    token = token.strip()
    if len(token) < 2 or token[0] != "'" or token[-1] != "'":
        raise ValueError(f"expected SQL string, got {token[:40]!r}")
    result: list[str] = []
    index = 1
    while index < len(token) - 1:
        if token[index] == "'" and index + 1 < len(token) - 1 and token[index + 1] == "'":
            result.append("'")
            index += 2
        else:
            result.append(token[index])
            index += 1
    return "".join(result)


def split_sql_fields(source: str) -> list[str]:
    """Split one PostgreSQL VALUES tuple, respecting SQL and dollar-quoted values."""
    fields: list[str] = []
    start = 0
    parentheses = 0
    brackets = 0
    quote = False
    dollar = False
    index = 0
    while index < len(source):
        if dollar:
            if source.startswith("$json$", index):
                dollar = False
                index += 6
                continue
            index += 1
            continue
        if quote:
            if source[index] == "'":
                if index + 1 < len(source) and source[index + 1] == "'":
                    index += 2
                    continue
                quote = False
            index += 1
            continue
        if source.startswith("$json$", index):
            dollar = True
            index += 6
            continue
        char = source[index]
        if char == "'":
            quote = True
        elif char == "(":
            parentheses += 1
        elif char == ")":
            parentheses -= 1
        elif char == "[":
            brackets += 1
        elif char == "]":
            brackets -= 1
        elif char == "," and parentheses == 0 and brackets == 0:
            fields.append(source[start:index].strip())
            start = index + 1
        index += 1
    fields.append(source[start:].strip())
    return fields


def values_tuples(sql: str) -> list[list[str]]:
    marker = re.search(r"\bVALUES\s*", sql, re.IGNORECASE)
    if not marker:
        raise ValueError("V73 values block missing")
    source = sql[marker.end():]
    tuples: list[list[str]] = []
    depth = 0
    start: int | None = None
    quote = False
    dollar = False
    index = 0
    while index < len(source):
        if dollar:
            if source.startswith("$json$", index):
                dollar = False
                index += 6
                continue
            index += 1
            continue
        if quote:
            if source[index] == "'":
                if index + 1 < len(source) and source[index + 1] == "'":
                    index += 2
                    continue
                quote = False
            index += 1
            continue
        if source.startswith("$json$", index):
            dollar = True
            index += 6
            continue
        char = source[index]
        if char == "'":
            quote = True
        elif char == "(" and depth == 0:
            start = index + 1
            depth = 1
        elif char == "(" and depth > 0:
            depth += 1
        elif char == ")" and depth > 0:
            depth -= 1
            if depth == 0:
                assert start is not None
                tuples.append(split_sql_fields(source[start:index]))
                start = None
                # The VALUES clause is followed by the UPDATE CTE.  Do not parse the CTE's
                # function calls as additional tuples.
                if re.match(
                    r"\s*\)\s*,\s*(?:seeded|repaired)\s+AS\b",
                    source[index + 1:],
                    re.IGNORECASE,
                ):
                    break
        elif char == ";" and depth == 0:
            break
        index += 1
    return tuples


def sql_array(token: str) -> list[str]:
    match = re.fullmatch(r"ARRAY\[(.*)\]::text\[\]", token.strip(), re.DOTALL)
    if not match:
        raise ValueError(f"expected text array, got {token[:40]!r}")
    source = match.group(1)
    values: list[str] = []
    start = 0
    quote = False
    index = 0
    while index < len(source):
        if quote:
            if source[index] == "'":
                if index + 1 < len(source) and source[index + 1] == "'":
                    index += 2
                    continue
                quote = False
            index += 1
            continue
        if source[index] == "'":
            quote = True
        elif source[index] == ",":
            value = source[start:index].strip()
            if value:
                values.append(sql_string(value))
            start = index + 1
        index += 1
    value = source[start:].strip()
    if value:
        values.append(sql_string(value))
    return values


def json_dollar(token: str) -> Any:
    token = token.strip()
    if not token.startswith("$json$") or not token.endswith("$json$::jsonb"):
        raise ValueError(f"expected dollar JSON, got {token[:40]!r}")
    return json.loads(token[6:-13])


def migration_profiles() -> dict[str, dict[str, Any]]:
    """Read V73's values without retyping or translating its authoritative copy."""
    tuples = values_tuples(MIGRATION_PATH.read_text(encoding="utf-8"))
    result: dict[str, dict[str, Any]] = {}
    for fields in tuples:
        if len(fields) != 9:
            raise ValueError(f"V73 tuple has {len(fields)} fields, expected 9")
        code = sql_string(fields[0])
        result[code] = {
            "task_types": sql_array(fields[1]),
            "task_types_en": sql_array(fields[2]),
            "usage_guide": sql_string(fields[3]),
            "usage_guide_en": sql_string(fields[4]),
            "example_prompts": json_dollar(fields[5]),
            "example_prompts_en": json_dollar(fields[6]),
            "preview_zh": json_dollar(fields[7]),
            "preview_en": json_dollar(fields[8]),
        }
    if set(result) != set(CURATED_CODES):
        raise ValueError(f"V73 curated profile set mismatch: {sorted(result)}")
    table_tuples = values_tuples(TABLE_MIGRATION_PATH.read_text(encoding="utf-8"))
    table_codes: set[str] = set()
    for fields in table_tuples:
        if len(fields) != 9:
            raise ValueError(f"V89 tuple has {len(fields)} fields, expected 9")
        code = sql_string(fields[0])
        if code not in result or code in table_codes:
            raise ValueError(f"V89 curated table code is invalid or duplicated: {code}")
        table_codes.add(code)
        for preview_key, intro_field, table_field, paragraphs_field in (
            ("preview_zh", 3, 5, 7),
            ("preview_en", 4, 6, 8),
        ):
            preview = result[code][preview_key]
            preview["intro"] = json_dollar(fields[intro_field])
            preview["table"] = json_dollar(fields[table_field])
            preview["outputParagraphs"] = json_dollar(fields[paragraphs_field])
    expected_table_codes = {
        "ai-office-assistant", "content-summarizer", "sales-analysis-expert",
    }
    if table_codes != expected_table_codes:
        raise ValueError(f"V89 curated table profile set mismatch: {sorted(table_codes)}")
    return result


def profile_from_row(row: dict[str, Any], source: dict[str, Any]) -> dict[str, Any]:
    keys = (
        "task_types", "task_types_en", "usage_guide", "usage_guide_en",
        "example_prompts", "example_prompts_en", "preview_zh", "preview_en",
    )
    if all(key in row and row[key] is not None for key in keys):
        return {key: copy.deepcopy(row[key]) for key in keys}
    return copy.deepcopy(source[row["code"]])


def profile_view(profile: dict[str, Any], row: dict[str, Any] | None = None) -> dict[str, Any]:
    """API-shaped copy retained alongside SQL-shaped columns for fixture consumers."""
    result = {
        "taskTypes": copy.deepcopy(profile["task_types"]),
        "taskTypesEn": copy.deepcopy(profile["task_types_en"]),
        "usageGuide": profile["usage_guide"],
        "usageGuideEn": profile["usage_guide_en"],
        "examplePrompts": copy.deepcopy(profile["example_prompts"]),
        "examplePromptsEn": copy.deepcopy(profile["example_prompts_en"]),
        "preview": copy.deepcopy(profile["preview_zh"]),
        "previewEn": copy.deepcopy(profile["preview_en"]),
    }
    # The WYSIWYG canvas is package metadata, but keeping it in the exported presentation
    # projection makes the fixture round-trip complete for offline desktop rendering.
    canvas = (row or {}).get("expert_presentation", {}).get("canvas")
    if canvas is not None:
        result["canvas"] = copy.deepcopy(canvas)
    return result


def read_package_entries(path: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(path) as archive:
        return {info.filename: archive.read(info) for info in archive.infolist() if not info.is_dir()}


def json_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, indent=2).encode("utf-8") + b"\n"


def metadata_value(value: Any) -> str:
    if not isinstance(value, str):
        raise ValueError(f"presentation metadata must be text, got {type(value).__name__}")
    return json.dumps(value, ensure_ascii=False)


def presentation_metadata(row: dict[str, Any], profile: dict[str, Any]) -> dict[str, str]:
    """Build the presentation metadata expected from the production Java compiler."""
    metadata: dict[str, str] = {
        "title.zh": row["name"],
        "title.en": row.get("name_en"),
        "summary.zh": row["summary"],
        "summary.en": row.get("summary_en"),
        "description.zh": row["description"],
        "description.en": row.get("description_en"),
    }
    for index, value in enumerate(profile["task_types"], 1):
        metadata[f"expert.taskType.{index:02d}.zh"] = value
    for index, value in enumerate(profile["task_types_en"], 1):
        metadata[f"expert.taskType.{index:02d}.en"] = value
    metadata["expert.usageGuide.zh"] = profile["usage_guide"]
    if profile.get("usage_guide_en"):
        metadata["expert.usageGuide.en"] = profile["usage_guide_en"]
    for index, value in enumerate(profile["example_prompts"], 1):
        metadata[f"expert.examplePrompt.{index:02d}.zh"] = value
    for index, value in enumerate(profile["example_prompts_en"], 1):
        metadata[f"expert.examplePrompt.{index:02d}.en"] = value

    canvas = (row.get("expert_presentation") or {}).get("canvas")
    if canvas:
        localized = canvas.get("zh") if isinstance(canvas, dict) and "zh" in canvas else canvas
        if localized is not None:
            metadata["expert.canvas.v1.zh"] = json.dumps(
                localized, ensure_ascii=False, separators=(",", ":"), sort_keys=True
            )
        if isinstance(canvas, dict) and canvas.get("en") is not None:
            metadata["expert.canvas.v1.en"] = json.dumps(
                canvas["en"], ensure_ascii=False, separators=(",", ":"), sort_keys=True
            )

    for language, key in (("zh", "preview_zh"), ("en", "preview_en")):
        preview = profile.get(key)
        if not preview:
            continue
        for field in ("heading", "scene", "request", "material", "outputTitle"):
            value = preview.get(field)
            if value:
                metadata[f"expert.preview.{field}.{language}"] = value
        for index, value in enumerate(preview.get("outputParagraphs", []), 1):
            metadata[f"expert.preview.outputParagraph.{index:02d}.{language}"] = value
        if preview.get("intro") is not None:
            metadata[f"expert.preview.intro.v1.{language}"] = json.dumps(
                preview["intro"], ensure_ascii=False, separators=(",", ":"), sort_keys=True
            )
        if preview.get("table") is not None:
            metadata[f"expert.preview.table.v1.{language}"] = json.dumps(
                preview["table"], ensure_ascii=False, separators=(",", ":"), sort_keys=True
            )

    asset_names = {
        "icon": Path(row["icon_file"]).suffix.lower(),
        "illustration": ".webp",
        "exampleImage": Path(row["example_image_file"]).suffix.lower(),
    }
    for role, extension in asset_names.items():
        metadata[f"expert.asset.{role}.path"] = (
            f"assets/{'example-image' if role == 'exampleImage' else role}{extension}"
        )
    return {key: value for key, value in metadata.items() if value not in (None, "")}


def skill_body(original: bytes, row: dict[str, Any], profile: dict[str, Any]) -> bytes:
    """Legacy reference renderer retained for tests; production Java owns package bytes."""
    text = original.decode("utf-8")
    match = re.match(r"\A---\r?\n", text)
    if not match:
        raise ValueError(f"{row['code']} SKILL.md has no YAML frontmatter")
    close = re.search(r"\r?\n---\r?\n", text[match.end():])
    if not close:
        raise ValueError(f"{row['code']} SKILL.md frontmatter is unterminated")
    body_start = match.end() + close.end()
    body = text[body_start:].encode("utf-8")
    metadata = presentation_metadata(row, profile)

    lines = [
        "---",
        f"name: {metadata_value(row['code'])}",
        f"description: {metadata_value(row['description_en'] or row['description'])}",
        "metadata:",
    ]
    for key in sorted(metadata):
        value = metadata[key]
        if value is not None and value != "":
            lines.append(f"  {key}: {metadata_value(value)}")
    lines.extend(["---", ""])
    # The body starts after the original closing delimiter.  The added blank line is the normal
    # YAML document separator and is not part of the original runtime body.
    return ("\n".join(lines)).encode("utf-8") + body


def package_skill_metadata(content: bytes) -> dict[str, str]:
    """Parse Java-generated scalar metadata without taking a PyYAML dependency."""
    document = content.decode("utf-8")
    if not document.startswith(("---\n", "---\r\n")):
        raise ValueError("SKILL.md has no YAML frontmatter")
    first_break = document.find("\n")
    close = document.find("\n---", first_break + 1)
    if close < 0:
        raise ValueError("SKILL.md frontmatter is unterminated")
    frontmatter = document[first_break + 1:close + 1]
    lines = frontmatter.splitlines()
    try:
        start = next(index for index, line in enumerate(lines) if line == "metadata:") + 1
    except StopIteration as error:
        raise ValueError("SKILL.md metadata is missing") from error
    raw_values: dict[str, list[str]] = {}
    current: str | None = None
    for line in lines[start:]:
        if not line.startswith("  "):
            break
        match = re.match(r"^  ([A-Za-z0-9_.-]+):(?: (.*))?$", line)
        if match:
            current = match.group(1)
            raw_values[current] = [match.group(2) or ""]
        elif current is not None:
            raw_values[current].append(line.lstrip())
        else:
            raise ValueError("SKILL.md metadata continuation has no key")
    values: dict[str, str] = {}
    for key, parts in raw_values.items():
        scalar = "\n".join(parts).strip()
        scalar = re.sub(r"\\\r?\n\s*\\ ", " ", scalar)
        scalar = re.sub(r"\\\r?\n\s*", "", scalar)
        value = json.loads(scalar)
        if not isinstance(value, str):
            raise ValueError(f"SKILL.md metadata is not text: {key}")
        values[key] = value
    return values


def is_presentation_metadata(key: str) -> bool:
    return key.startswith("expert.") or bool(
        re.fullmatch(r"(?:title|summary|description)\.(?:zh|en)", key)
    )


def canonical_digest(plugin: dict[str, Any], runtime: dict[str, Any]) -> str:
    pair = {"plugin": plugin, "runtime": runtime}
    content = json.dumps(pair, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode()
    return sha256(content)


def dependency_digest(runtime: dict[str, Any]) -> str:
    dependencies = sorted(runtime.get("dependencies", []), key=lambda item: item.get("key", ""))
    content = json.dumps(dependencies, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode()
    return sha256(content)


def utf8_sort(entries: dict[str, bytes]) -> list[tuple[str, bytes]]:
    return sorted(entries.items(), key=lambda item: item[0].encode("utf-8"))


def deterministic_zip_bytes(entries: dict[str, bytes]) -> bytes:
    from io import BytesIO

    output = BytesIO()
    with zipfile.ZipFile(
        output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9,
        allowZip64=False, strict_timestamps=False,
    ) as archive:
        for name, data in utf8_sort(entries):
            info = zipfile.ZipInfo(name, ZIP_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.comment = b""
            info.extra = b""
            info.create_system = 0
            info.external_attr = 0
            info.internal_attr = 0
            info.flag_bits |= 0x800  # UTF-8 names, even if a future code adds non-ASCII paths.
            archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    return output.getvalue()


def write_deterministic_zip(path: Path, entries: dict[str, bytes]) -> bytes:
    content = deterministic_zip_bytes(entries)
    path.write_bytes(content)
    return content


def convert_illustration(code: str, check_only: bool) -> tuple[Path, bytes]:
    image_dir = SNAPSHOT_ROOT / "images" / "skills"
    old = image_dir / f"{code}-illustration.png"
    new = image_dir / f"{code}-illustration.webp"
    if old.exists():
        with Image.open(old) as image:
            if image.size != OLD_ILLUSTRATION_SIZE:
                raise ValueError(f"{old} expected {OLD_ILLUSTRATION_SIZE}, got {image.size}")
            if check_only:
                raise ValueError(f"obsolete illustration still present: {old}")
            image = image.resize(ILLUSTRATION_SIZE, Image.Resampling.LANCZOS)
            # Explicit settings and no metadata make Pillow's output reproducible for the
            # bundled runtime.  RGB is sufficient for these existing RGB source images.
            image.save(new, format="WEBP", quality=88, method=6, exact=True)
    if not new.exists():
        raise FileNotFoundError(new)
    with Image.open(new) as image:
        if image.size != ILLUSTRATION_SIZE or image.format != "WEBP":
            raise ValueError(f"{new} is not a 1440x480 WebP")
    data = new.read_bytes()
    if len(data) > ILLUSTRATION_MAX_BYTES:
        raise ValueError(f"{new} exceeds {ILLUSTRATION_MAX_BYTES} bytes: {len(data)}")
    if old.exists():
        # The replacement has passed format, dimensions and budget checks above.  Only now
        # remove this one exact obsolete tracked path; no wildcard deletion is used.
        old.unlink()
    if old.exists():
        raise ValueError(f"failed to remove obsolete illustration: {old}")
    return new, data


def png_dimensions(data: bytes) -> tuple[int, int]:
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise ValueError("not a PNG")
    return struct.unpack(">II", data[16:24])


def build_expert(row: dict[str, Any], profile: dict[str, Any], check_only: bool) -> None:
    code = row["code"]
    package_path = SNAPSHOT_ROOT / row["package_file"]
    if not package_path.exists():
        raise FileNotFoundError(package_path)
    entries = read_package_entries(package_path)
    skill_path = f"skills/{code}/SKILL.md"
    if skill_path not in entries:
        raise ValueError(f"{package_path} missing {skill_path}")

    illustration_path, illustration = convert_illustration(code, check_only)
    icon = (SNAPSHOT_ROOT / row["icon_file"]).read_bytes()
    example = (SNAPSHOT_ROOT / row["example_image_file"]).read_bytes()

    plugin = copy.deepcopy(row.get("plugin_manifest"))
    runtime = copy.deepcopy(row.get("runtime_manifest"))
    if plugin is None:
        plugin = json.loads(entries["plugin.json"])
    if runtime is None:
        runtime = json.loads(entries[f"com.yuzhihuihe.ycps/manifest.json"])
    extension = plugin.setdefault("extensions", {}).setdefault("com.yuzhihuihe.ycps", {})
    plugin["$schema"] = PLUGIN_SCHEMA
    plugin["name"] = code
    plugin["version"] = row["version"]
    extension.update({
        "ycpsVersion": "1.1.0",
        "kind": "EXPERT",
        "entrySkill": code,
        "manifest": "./com.yuzhihuihe.ycps/manifest.json",
        "requiredFeatures": ["expert-presentation-v1"],
    })
    runtime["$schema"] = RUNTIME_SCHEMA
    host = runtime.setdefault("hostRequirements", {})
    host["minClientVersion"] = MIN_CLIENT_VERSION

    # Package serialization belongs to the production Java compiler.  Reimplementing Jackson's
    # JSON/YAML byte format here would create a second, incompatible canonicalizer.  The fixture
    # package must therefore come from the cloud export; this script validates its semantics and
    # exact deterministic container, then refreshes catalog hashes without rewriting immutable
    # bytes.
    if json.loads(entries["plugin.json"]) != plugin:
        raise ValueError(f"{package_path} plugin.json differs from production manifest")
    if json.loads(entries["com.yuzhihuihe.ycps/manifest.json"]) != runtime:
        raise ValueError(f"{package_path} runtime manifest differs from production manifest")
    skill_content = entries[skill_path]
    if skill_content.startswith((b"---\n---\n", b"---\r\n---\r\n")):
        raise ValueError(f"{package_path} contains a duplicate YAML document marker")
    actual_metadata = package_skill_metadata(skill_content)
    expected_metadata = presentation_metadata(row, profile)
    for key, value in expected_metadata.items():
        if actual_metadata.get(key) != value:
            raise ValueError(f"{package_path} metadata differs: {key}")
    unexpected = sorted(
        key for key in actual_metadata
        if is_presentation_metadata(key) and key not in expected_metadata
    )
    if unexpected:
        raise ValueError(f"{package_path} contains stale presentation metadata: {unexpected}")
    expected_assets = {
        f"skills/{code}/assets/icon{Path(row['icon_file']).suffix.lower()}": icon,
        f"skills/{code}/assets/illustration.webp": illustration,
        f"skills/{code}/assets/example-image{Path(row['example_image_file']).suffix.lower()}": example,
    }
    actual_asset_names = {name for name in entries if name.startswith(f"skills/{code}/assets/")}
    if actual_asset_names != set(expected_assets):
        raise ValueError(f"{package_path} presentation asset inventory differs")
    for name, expected_content in expected_assets.items():
        if entries.get(name) != expected_content:
            raise ValueError(f"{package_path} asset bytes differ: {name}")
    content = package_path.read_bytes()

    with zipfile.ZipFile(package_path) as archive:
        infos = [info for info in archive.infolist() if not info.is_dir()]
        names = [info.filename for info in infos]
        if names != [name for name, _ in utf8_sort(entries)]:
            raise ValueError(f"{package_path} paths are not UTF-8 sorted")
        if any(info.date_time != ZIP_DATE or info.comment or info.extra for info in infos):
            raise ValueError(f"{package_path} has non-deterministic ZIP metadata")
        if any(info.compress_type != zipfile.ZIP_DEFLATED for info in infos):
            raise ValueError(f"{package_path} has non-deterministic ZIP compression")
        extracted = sum(info.file_size for info in infos)
        file_count = len(infos)

    row.update({
        "manifest": plugin,
        "published_version": row.get("published_version") or row["version"],
        "latest_version": row.get("latest_version") or row["version"],
        "revision": CURATED_REVISIONS[code],
        "size_bytes": len(content),
        "md5": md5(content),
        "sha256": sha256(content),
        "file_count": file_count,
        "extracted_bytes": extracted,
        "package_format": "YCPS",
        "ycps_version": "1.1.0",
        "package_kind": "EXPERT",
        "version_min_client_version": MIN_CLIENT_VERSION,
        "plugin_manifest": plugin,
        "runtime_manifest": runtime,
        "manifest_sha256": canonical_digest(plugin, runtime),
        "dependency_lock_sha256": dependency_digest(runtime),
        "illustration_file": f"images/skills/{code}-illustration.webp",
        "illustration_content_type": "image/webp",
        "illustration_bytes": len(illustration),
        "illustration_md5": md5(illustration),
        "illustration_sha256": sha256(illustration),
        "illustration_width": ILLUSTRATION_SIZE[0],
        "illustration_height": ILLUSTRATION_SIZE[1],
        "example_image_sha256": sha256(example),
        "example_image_width": png_dimensions(example)[0],
        "example_image_height": png_dimensions(example)[1],
        "expert_presentation": profile_view(profile, row),
        "package_file": f"packages/{package_path.name}",
    })
    # Keep row's public minimum unchanged: the package's 1.1 runtime minimum is represented by
    # version_min_client_version, while the operational skill minimum remains cloud-owned.


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()

    catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
    profiles = migration_profiles()
    rows = {row["code"]: row for row in catalog["skills"]}
    if set(CURATED_CODES) - set(rows):
        raise SystemExit(f"catalog missing curated Experts: {sorted(set(CURATED_CODES) - set(rows))}")
    for code in CURATED_CODES:
        row = rows[code]
        if row.get("kind") != "EXPERT":
            raise SystemExit(f"{code} is not EXPERT")
        # Operational gateway.skill.tags stay frozen at the catalog baseline.  Business-facing
        # task types are an independent Expert profile field and must never be copied into tags.
        row["tags"] = ["ycps"]
        profile = profile_from_row(row, profiles)
        row.update(profile)
        build_expert(row, profile, args.check_only)

    if not args.check_only:
        CATALOG_PATH.write_text(
            json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
    action = "validated" if args.check_only else "materialized"
    print(f"{action} {len(CURATED_CODES)} curated Expert fixtures")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""Build the deterministic manifest used to reproduce the cloud Skill/Expert catalog.

The catalog export is the source of truth. This manifest records every published package,
its archive members and Expert presentation metadata, plus every category and its optional
cover. ``--check`` is safe to run in CI and fails when the checked-in manifest or any
package/media digest has drifted.

Usage::

    python scripts/build-cloud-repro-manifest.py
    python scripts/build-cloud-repro-manifest.py --check
    python scripts/build-cloud-repro-manifest.py --desktop-output ../yuzai/tests/fixtures/cloud-skill-catalog/manifest.json
"""
from __future__ import annotations

import argparse
import hashlib
import json
import zipfile
from pathlib import Path
from typing import Any


SYSTEM_ROOT = Path(__file__).resolve().parent.parent
SNAPSHOT_ROOT = SYSTEM_ROOT / "fixtures" / "catalog-snapshot"
CATALOG_PATH = SNAPSHOT_ROOT / "catalog.json"
MANIFEST_PATH = SNAPSHOT_ROOT / "repro-manifest.json"


def digest(path: Path, algorithm: str) -> str:
    hasher = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def snapshot_file(filename: str, description: str) -> Path:
    if not isinstance(filename, str) or not filename:
        raise ValueError(f"{description} has no fixture file")
    root = SNAPSHOT_ROOT.resolve()
    path = (root / filename).resolve()
    if not path.is_relative_to(root):
        raise ValueError(f"{description} fixture is outside the snapshot: {filename}")
    if not path.is_file():
        raise ValueError(f"missing {description} fixture: {filename}")
    return path


def image_dimensions(path: Path, content_type: str) -> tuple[int, int]:
    """Read cover dimensions from the supported image formats without external packages."""
    data = path.read_bytes()
    media_type = (content_type or "").split(";", 1)[0].strip().lower()
    if media_type == "image/png":
        if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
            raise ValueError(f"invalid PNG cover: {path.name}")
        dimensions = (int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big"))
    elif media_type == "image/gif":
        if len(data) < 10 or data[:6] not in (b"GIF87a", b"GIF89a"):
            raise ValueError(f"invalid GIF cover: {path.name}")
        dimensions = (int.from_bytes(data[6:8], "little"), int.from_bytes(data[8:10], "little"))
    elif media_type == "image/webp":
        if len(data) < 20 or data[:4] != b"RIFF" or data[8:12] != b"WEBP":
            raise ValueError(f"invalid WebP cover: {path.name}")
        dimensions = None
        offset = 12
        while offset + 8 <= len(data):
            kind = data[offset:offset + 4]
            size = int.from_bytes(data[offset + 4:offset + 8], "little")
            payload = offset + 8
            if payload + size > len(data):
                raise ValueError(f"truncated WebP cover: {path.name}")
            frame = data[payload:payload + size]
            if kind == b"VP8X" and len(frame) >= 10:
                dimensions = (
                    1 + int.from_bytes(frame[4:7], "little"),
                    1 + int.from_bytes(frame[7:10], "little"),
                )
                break
            if kind == b"VP8 ":
                marker = frame.find(b"\x9d\x01\x2a")
                if marker >= 0 and marker + 7 <= len(frame):
                    dimensions = (
                        int.from_bytes(frame[marker + 3:marker + 5], "little") & 0x3fff,
                        int.from_bytes(frame[marker + 5:marker + 7], "little") & 0x3fff,
                    )
                    break
            if kind == b"VP8L" and len(frame) >= 5 and frame[0] == 0x2f:
                bits = int.from_bytes(frame[1:5], "little")
                dimensions = ((bits & 0x3fff) + 1, ((bits >> 14) & 0x3fff) + 1)
                break
            offset = payload + size + (size % 2)
        if dimensions is None:
            raise ValueError(f"WebP cover has no readable dimensions: {path.name}")
    elif media_type == "image/jpeg":
        if len(data) < 4 or data[:2] != b"\xff\xd8":
            raise ValueError(f"invalid JPEG cover: {path.name}")
        dimensions = None
        offset = 2
        while offset < len(data):
            if data[offset] != 0xff:
                raise ValueError(f"invalid JPEG segment: {path.name}")
            while offset < len(data) and data[offset] == 0xff:
                offset += 1
            if offset >= len(data):
                break
            marker = data[offset]
            offset += 1
            if marker in (0xd8, 0xd9) or 0xd0 <= marker <= 0xd7:
                continue
            if offset + 2 > len(data):
                break
            size = int.from_bytes(data[offset:offset + 2], "big")
            if size < 2 or offset + size > len(data):
                break
            if marker in (0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7,
                          0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf) and size >= 7:
                dimensions = (
                    int.from_bytes(data[offset + 5:offset + 7], "big"),
                    int.from_bytes(data[offset + 3:offset + 5], "big"),
                )
                break
            offset += size
        if dimensions is None:
            raise ValueError(f"JPEG cover has no readable dimensions: {path.name}")
    else:
        raise ValueError(f"unsupported cover content type for {path.name}: {content_type}")
    if min(dimensions) <= 0:
        raise ValueError(f"invalid cover dimensions for {path.name}: {dimensions}")
    return dimensions


def category_record(row: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {
        "kind": row["kind"],
        "code": row["code"],
        "name": row.get("name"),
        "nameEn": row.get("name_en"),
        "sortOrder": row.get("sort_order"),
        "enabled": row.get("enabled"),
        "cover": None,
    }
    filename = row.get("image_file")
    if not filename:
        if any(row.get(f"image_{field}") is not None for field in
               ("content_type", "bytes", "md5", "sha256", "width", "height")):
            raise ValueError(f"{row['kind']}:{row['code']} has cover metadata but no image file")
        return result
    identity = f"{row['kind']}:{row['code']} cover"
    path = snapshot_file(filename, identity)
    content_type = row.get("image_content_type")
    width, height = image_dimensions(path, content_type)
    cover = {
        "file": filename,
        "contentType": content_type,
        "bytes": path.stat().st_size,
        "md5": digest(path, "md5"),
        "sha256": digest(path, "sha256"),
        "width": width,
        "height": height,
    }
    for field, value in (("bytes", cover["bytes"]), ("md5", cover["md5"])):
        if row.get(f"image_{field}") != value:
            raise ValueError(f"{identity} {field} differs from catalog")
    for field in ("sha256", "width", "height"):
        expected = row.get(f"image_{field}")
        if expected is not None and expected != cover[field]:
            raise ValueError(f"{identity} {field} differs from catalog")
    result["cover"] = cover
    return result


def asset_record(row: dict[str, Any], role: str) -> dict[str, Any] | None:
    prefix = {"icon": "icon", "illustration": "illustration", "exampleImage": "example_image"}[role]
    filename = row.get(f"{prefix}_file")
    if not filename:
        if any(row.get(f"{prefix}_{field}") is not None for field in
               ("content_type", "bytes", "md5", "sha256", "width", "height")):
            raise ValueError(f"{row['code']} {role} has media metadata but no file")
        return None
    path = snapshot_file(filename, f"{role} for {row['code']}")
    result: dict[str, Any] = {
        "role": role,
        "file": filename,
        "contentType": row.get(f"{prefix}_content_type"),
        "bytes": path.stat().st_size,
        "md5": digest(path, "md5"),
        "sha256": digest(path, "sha256"),
    }
    expected_bytes = row.get(f"{prefix}_bytes")
    expected_md5 = row.get(f"{prefix}_md5")
    if expected_bytes is not None and result["bytes"] != expected_bytes:
        raise ValueError(f"{row['code']} {role} byte count differs from catalog")
    if expected_md5 and result["md5"] != expected_md5:
        raise ValueError(f"{row['code']} {role} md5 differs from catalog")
    expected_sha256 = row.get(f"{prefix}_sha256")
    if expected_sha256 and result["sha256"] != expected_sha256:
        raise ValueError(f"{row['code']} {role} sha256 differs from catalog")
    for dimension in ("width", "height"):
        value = row.get(f"{prefix}_{dimension}")
        if value is not None:
            result[dimension] = value
    return result


def package_record(row: dict[str, Any]) -> dict[str, Any]:
    package_file = row.get("package_file")
    if not package_file:
        raise ValueError(f"{row['code']} has no package file")
    package_path = snapshot_file(package_file, f"package for {row['code']}")
    package_bytes = package_path.stat().st_size
    package_sha = digest(package_path, "sha256")
    package_md5 = digest(package_path, "md5")
    if row.get("size_bytes") != package_bytes or row.get("sha256") != package_sha or row.get("md5") != package_md5:
        raise ValueError(f"{row['code']} package digest differs from catalog")

    members: list[dict[str, Any]] = []
    seen_members: set[str] = set()
    with zipfile.ZipFile(package_path) as archive:
        for info in sorted(archive.infolist(), key=lambda item: item.filename):
            if info.filename in seen_members:
                raise ValueError(f"{row['code']} has duplicate archive member: {info.filename}")
            if info.is_dir() or not info.filename or info.filename.startswith("/") or \
                    ".." in Path(info.filename).parts:
                raise ValueError(f"{row['code']} has invalid archive member: {info.filename}")
            seen_members.add(info.filename)
            data = archive.read(info)
            members.append({
                "path": info.filename,
                "bytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
            })
    extracted_bytes = sum(member["bytes"] for member in members)
    if row.get("file_count") != len(members):
        raise ValueError(f"{row['code']} ZIP member count differs from catalog")
    if row.get("extracted_bytes") != extracted_bytes:
        raise ValueError(f"{row['code']} ZIP extracted bytes differ from catalog")

    result: dict[str, Any] = {
        "code": row["code"],
        "kind": row["kind"],
        "name": row.get("name"),
        "nameEn": row.get("name_en"),
        "summary": row.get("summary"),
        "summaryEn": row.get("summary_en"),
        "description": row.get("description"),
        "descriptionEn": row.get("description_en"),
        "version": row.get("version") or row.get("published_version"),
        "revision": row.get("revision"),
        "ycpsVersion": row.get("ycps_version"),
        "packageKind": row.get("package_kind") or row["kind"],
        "minClientVersion": row.get("version_min_client_version") or row.get("min_client_version"),
        "packageFile": package_file,
        "artifact": {
            "bytes": package_bytes,
            "md5": package_md5,
            "sha256": package_sha,
            "fileCount": len(members),
            "extractedBytes": extracted_bytes,
        },
        "manifestSha256": row.get("manifest_sha256"),
        "dependencyLockSha256": row.get("dependency_lock_sha256"),
        "members": members,
    }
    if row["kind"] == "EXPERT":
        result["expertPortableRevision"] = row.get("expert_portable_revision")
        result["taskTypes"] = row.get("task_types")
        result["taskTypesEn"] = row.get("task_types_en")
        result["usageGuide"] = row.get("usage_guide")
        result["usageGuideEn"] = row.get("usage_guide_en")
        result["examplePrompts"] = row.get("example_prompts")
        result["examplePromptsEn"] = row.get("example_prompts_en")
        result["preview"] = {"zh": row.get("preview_zh"), "en": row.get("preview_en")}
        result["expertPresentation"] = row.get("expert_presentation")
        result["assets"] = [
            item for role in ("icon", "illustration", "exampleImage")
            if (item := asset_record(row, role)) is not None
        ]
    return result


def build_manifest() -> dict[str, Any]:
    catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
    rows = catalog.get("skills")
    categories = catalog.get("categories")
    if not isinstance(rows, list) or not rows:
        raise ValueError("catalog has no cloud package rows")
    if not isinstance(categories, list) or not categories:
        raise ValueError("catalog has no category rows")
    seen_codes: set[str] = set()
    seen_package_files: set[str] = set()
    for row in rows:
        code = row.get("code")
        if not isinstance(code, str) or not code or code in seen_codes:
            raise ValueError(f"duplicate or invalid package code: {code}")
        seen_codes.add(code)
        if row.get("kind") not in ("EXPERT", "SKILL") or row.get("status") != "PUBLISHED":
            raise ValueError(f"{code} is not a published EXPERT/SKILL")
        if row.get("version") != row.get("published_version"):
            raise ValueError(f"{code} package version differs from published version")
        package_file = row.get("package_file")
        if not package_file:
            raise ValueError(f"{code} has no package file")
        if package_file in seen_package_files:
            raise ValueError(f"duplicate package file: {package_file}")
        seen_package_files.add(package_file)
    seen_categories: set[tuple[str, str]] = set()
    for row in categories:
        identity = (row.get("kind"), row.get("code"))
        if identity[0] not in ("EXPERT", "SKILL") or not isinstance(identity[1], str) or not identity[1]:
            raise ValueError(f"invalid category identity: {identity}")
        if identity in seen_categories:
            raise ValueError(f"duplicate category identity: {identity}")
        seen_categories.add(identity)
    rows = sorted(rows, key=lambda row: row["code"])
    categories = sorted(categories, key=lambda row: (row["kind"], row["code"]))
    category_records = [category_record(row) for row in categories]
    expert_count = sum(row["kind"] == "EXPERT" for row in rows)
    skill_count = len(rows) - expert_count
    return {
        "schemaVersion": 1,
        "source": "system/fixtures/catalog-snapshot/catalog.json",
        "description": "Deterministic identity manifest for published cloud Skill/Expert packages and categories.",
        "packageCount": len(rows),
        "expertCount": expert_count,
        "skillCount": skill_count,
        "categoryCount": len(category_records),
        "coverCount": sum(item["cover"] is not None for item in category_records),
        "categories": category_records,
        "packages": [package_record(row) for row in rows],
    }


def serialise(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def desktop_projection(manifest: dict[str, Any]) -> dict[str, Any]:
    """Keep a small checked-in desktop fixture while pointing to the canonical bytes."""
    return {
        "schemaVersion": manifest["schemaVersion"],
        "source": "../../../../system/fixtures/catalog-snapshot/repro-manifest.json",
        "description": "Desktop contract fixture; package bytes and full presentation data remain in the system snapshot.",
        "packageCount": manifest["packageCount"],
        "expertCount": manifest["expertCount"],
        "skillCount": manifest["skillCount"],
        "packages": [
            {
                "code": item["code"],
                "kind": item["kind"],
                "name": item["name"],
                "nameEn": item["nameEn"],
                "summary": item["summary"],
                "summaryEn": item["summaryEn"],
                "description": item["description"],
                "descriptionEn": item["descriptionEn"],
                "version": item["version"],
                "revision": item["revision"],
                "ycpsVersion": item["ycpsVersion"],
                "packageFile": f"../../../../system/fixtures/catalog-snapshot/{item['packageFile']}",
                "artifact": item["artifact"],
                "manifestSha256": item["manifestSha256"],
                "dependencyLockSha256": item["dependencyLockSha256"],
                **({
                    "taskTypes": item["taskTypes"],
                    "taskTypesEn": item["taskTypesEn"],
                    "expertPresentation": item["expertPresentation"],
                    "assets": item["assets"],
                } if item["kind"] == "EXPERT" else {}),
            }
            for item in manifest["packages"]
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="verify the checked-in manifest without writing")
    parser.add_argument("--desktop-output", type=Path, help="also write the compact desktop fixture projection")
    args = parser.parse_args()
    manifest = build_manifest()
    expected = serialise(manifest)
    if args.check:
        actual = MANIFEST_PATH.read_text(encoding="utf-8") if MANIFEST_PATH.exists() else ""
        if actual != expected:
            raise SystemExit(f"{MANIFEST_PATH} is stale; run without --check")
    else:
        MANIFEST_PATH.write_text(expected, encoding="utf-8", newline="\n")
    if args.desktop_output:
        args.desktop_output.parent.mkdir(parents=True, exist_ok=True)
        args.desktop_output.write_text(serialise(desktop_projection(manifest)), encoding="utf-8", newline="\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

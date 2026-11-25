"""导出当前环境的完整目录数据到 fixtures/catalog-snapshot。

这份快照是**文档示例与测试夹具的唯一数据源**：接口文档里出现的 code、示例响应、
以及测试里 seed 出来的技能，都应取自它，而不是凭空的 `data-analyst` 之类占位值。

覆盖两部分：

1. **云端目录**（gateway schema + 对象存储，经管理端 HTTP 读取）
   - 当前分类词表与全部已上传分类封面，不固定分类或专家数量
   - 每个 YCPS 技能/专家的当前权威包及头像、介绍插画、示例图片
   - 包体逐字节校验 sha256、md5 和大小；只读取现有上传/发布字节，不本地重打包
   - 外部来源 Skill 的清单单独记录；无上传包的外部来源不冒充可复原 YCPS 包
   - 导出完成后自动刷新 `repro-manifest.json`，覆盖两类包的身份、ZIP 成员和 Expert 展示摘要

2. **本地内置 skills**（`Yuzai/SKILLs/`，12 个）
   它们本身就在仓库里，这里只登记清单与内容哈希，便于对照与复原。

用法：
  python scripts/export-catalog-snapshot.py
  python scripts/export-catalog-snapshot.py --base http://8.138.108.191
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import shutil
import subprocess
import tempfile
import urllib.request
from pathlib import Path

SYSTEM_ROOT = Path(__file__).resolve().parent.parent
WORKSPACE_ROOT = SYSTEM_ROOT.parent
SNAPSHOT = SYSTEM_ROOT / "fixtures" / "catalog-snapshot"
BUILTIN_ROOT = WORKSPACE_ROOT / "yuzai" / "SKILLs"

DEFAULT_BASE = "http://127.0.0.1:18081"
DEFAULT_ENV_FILE = SYSTEM_ROOT / "deploy" / "env" / ".env"
DEVICE_ID = "550e8400e29b41d4a716446655440000"
PG_CONTAINER = "yuzhihuihe-api-platform-postgres-1"
PG_DATABASE = "api_model_platform"

CONTENT_TYPES = {
    "image/png": "png", "image/jpeg": "jpg", "image/webp": "webp",
    "image/svg+xml": "svg", "image/gif": "gif", "application/zip": "ycps.zip",
}


def refresh_repro_manifest(root: Path = SNAPSHOT) -> None:
    """Regenerate the deterministic all-package manifest after catalog export."""
    builder_path = Path(__file__).with_name("build-cloud-repro-manifest.py")
    spec = importlib.util.spec_from_file_location("cloud_repro_manifest_builder", builder_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载复现清单生成器: {builder_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.SNAPSHOT_ROOT = root
    module.CATALOG_PATH = root / "catalog.json"
    module.MANIFEST_PATH = root / "repro-manifest.json"
    manifest = module.build_manifest()
    module.MANIFEST_PATH.write_text(module.serialise(manifest), encoding="utf-8", newline="\n")


def psql(sql: str):
    raw = subprocess.run(
        ["docker", "exec", PG_CONTAINER, "psql", "-U", "postgres", "-d", PG_DATABASE,
         "-t", "-A", "-c", sql],
        capture_output=True, text=True, encoding="utf-8", check=True).stdout.strip()
    return json.loads(raw) if raw else []


def load_admin(env_file: Path) -> dict[str, str]:
    values = {}
    for line in env_file.read_text(encoding="utf-8-sig").splitlines():
        if line.strip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    if not values.get("LOCAL_ADMIN_EMAIL") or not values.get("LOCAL_ADMIN_PASSWORD"):
        raise ValueError("LOCAL_ADMIN_EMAIL / LOCAL_ADMIN_PASSWORD missing from env file")
    return {"identifier": values["LOCAL_ADMIN_EMAIL"], "password": values["LOCAL_ADMIN_PASSWORD"]}


class Cloud:
    def __init__(self, base: str, admin: dict[str, str] | None = None) -> None:
        self.base = base.rstrip("/")
        self.token = ""
        self.admin = admin

    def login(self) -> None:
        request = urllib.request.Request(
            f"{self.base}/console/platform/auth/login",
            data=json.dumps(self.admin or load_admin(DEFAULT_ENV_FILE)).encode(),
            headers={"Content-Type": "application/json", "X-Device-Id": DEVICE_ID},
            method="POST")
        with urllib.request.urlopen(request, timeout=60) as response:
            self.token = json.loads(response.read())["accessToken"]

    def get(self, path: str) -> tuple[bytes, str]:
        request = urllib.request.Request(
            f"{self.base}{path}", headers={"Authorization": f"Bearer {self.token}"})
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read(), response.headers.get("Content-Type", "")


def save(root: Path, rel: str, data: bytes, content_type: str) -> tuple[str, str]:
    ext = CONTENT_TYPES.get((content_type or "").split(";")[0].strip().lower(), "bin")
    stem = Path(rel).stem
    name = f"{stem}.{ext}"
    folder = Path(rel).parent
    (root / folder).mkdir(parents=True, exist_ok=True)
    (root / folder / name).write_bytes(data)
    return (folder / name).as_posix(), hashlib.md5(data).hexdigest()


def png_dimensions(data: bytes) -> tuple[int, int]:
    """Read PNG IHDR dimensions so the snapshot records the rendered fixture shape."""
    if len(data) < 24 or not data.startswith(b"\x89PNG\r\n\x1a\n") \
            or data[12:16] != b"IHDR":
        raise ValueError("catalog_example_image_not_png")
    return int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")


def webp_dimensions(data: bytes) -> tuple[int, int]:
    """Read dimensions from the three WebP container variants without Pillow."""
    if len(data) < 16 or data[:4] != b"RIFF" or data[8:12] != b"WEBP":
        raise ValueError("catalog_illustration_not_webp")
    offset = 12
    while offset + 8 <= len(data):
        kind = data[offset:offset + 4]
        size = int.from_bytes(data[offset + 4:offset + 8], "little")
        payload = offset + 8
        if kind == b"VP8X" and payload + 10 <= len(data):
            width = 1 + int.from_bytes(data[payload + 4:payload + 7], "little")
            height = 1 + int.from_bytes(data[payload + 7:payload + 10], "little")
            return width, height
        if kind == b"VP8 " and payload + size <= len(data):
            frame = data[payload:payload + size]
            marker = frame.find(b"\x9d\x01\x2a")
            if marker >= 0 and marker + 7 <= len(frame):
                width = int.from_bytes(frame[marker + 3:marker + 5], "little") & 0x3fff
                height = int.from_bytes(frame[marker + 5:marker + 7], "little") & 0x3fff
                return width, height
        if kind == b"VP8L" and payload + 5 <= len(data) and data[payload] == 0x2f:
            bits = int.from_bytes(data[payload + 1:payload + 5], "little")
            return (bits & 0x3fff) + 1, ((bits >> 14) & 0x3fff) + 1
        offset = payload + size + (size & 1)
    raise ValueError("catalog_illustration_dimensions_missing")


def image_dimensions(data: bytes, content_type: str) -> tuple[int, int] | None:
    mime = content_type.split(";", 1)[0].strip().lower()
    if mime == "image/png":
        return png_dimensions(data)
    if mime == "image/webp":
        return webp_dimensions(data)
    if mime in ("image/jpeg", "image/gif"):
        from io import BytesIO
        from PIL import Image
        with Image.open(BytesIO(data)) as image:
            size = image.size
            image.verify()
        return size
    if mime == "image/svg+xml":
        return None
    raise ValueError(f"unsupported catalog image MIME: {mime}")


def safe_filename(value: str) -> str:
    if not isinstance(value, str) or not value or Path(value).name != value \
            or "/" in value or "\\" in value or ":" in value or value in (".", ".."):
        raise ValueError("unsafe catalog filename")
    return value


def expert_presentation(row: dict) -> dict | None:
    """Return the complete profile copy while keeping SQL-shaped columns on the row."""
    if row.get("kind") != "EXPERT":
        return None
    result = {
        "taskTypes": row.get("task_types") or [],
        "taskTypesEn": row.get("task_types_en") or [],
        "usageGuide": row.get("usage_guide"),
        "usageGuideEn": row.get("usage_guide_en"),
        "examplePrompts": row.get("example_prompts") or [],
        "examplePromptsEn": row.get("example_prompts_en") or [],
        "preview": row.get("preview_zh"),
        "previewEn": row.get("preview_en"),
    }
    canvas: dict[str, object] = {}
    if row.get("canvas_zh") is not None:
        canvas["zh"] = row["canvas_zh"]
    if row.get("canvas_en") is not None:
        canvas["en"] = row["canvas_en"]
    if canvas:
        result["canvas"] = canvas
    return result


def select_snapshot_versions(rows: list[dict]) -> list[dict]:
    """Keep one authoritative package row per skill even if a query regresses to a fan-out.

    Published immutable content wins over a newer READY draft; ties prefer the skill's published
    semantic version and then the highest revision.  This mirrors the LATERAL query below and
    prevents multiple revisions from overwriting the same fixture package filename.
    """
    selected: dict[str, dict] = {}

    def rank(row: dict) -> tuple[bool, bool, int]:
        revision = row.get("revision")
        return (
            row.get("version_status") == "PUBLISHED",
            row.get("version") == row.get("published_version"),
            revision if isinstance(revision, int) and not isinstance(revision, bool) else -1,
        )

    for row in rows:
        code = row.get("code")
        if not isinstance(code, str) or not code:
            raise ValueError("catalog export row is missing code")
        current = selected.get(code)
        if current is None or rank(row) > rank(current):
            selected[code] = row
    return sorted(selected.values(), key=lambda row: (row.get("kind") or "", row["code"]))


def export_categories(cloud: Cloud, root: Path) -> list[dict]:
    rows = psql(
        "SELECT json_agg(row_to_json(t) ORDER BY t.kind, t.sort_order) FROM ("
        "SELECT kind, code, name, name_en, sort_order, enabled, image_object_key, image_updated_at "
        "FROM gateway.skill_category WHERE deleted_at IS NULL) t;")
    for row in rows:
        safe_filename(row["code"])
        has_image = row.pop("image_object_key", None)
        if not has_image:
            row["image_file"] = None
            continue
        data, content_type = cloud.get(
            f"/console/gateway/admin/skills/categories/{row['code']}/image?kind={row['kind']}")
        same_code = sum(other["code"] == row["code"] for other in rows) > 1
        stem = f"{row['kind'].lower()}-{row['code']}" if same_code else row["code"]
        rel, md5 = save(root, f"images/categories/{stem}-cover", data, content_type)
        row.update(image_file=rel, image_content_type=content_type,
                   image_bytes=len(data), image_md5=md5, image_sha256=hashlib.sha256(data).hexdigest())
        dimensions = image_dimensions(data, content_type)
        if dimensions:
            row.update(image_width=dimensions[0], image_height=dimensions[1])
        print(f"  cover  {row['code']:24s} {len(data):9d} B")
    return rows


def export_skills(cloud: Cloud, root: Path) -> list[dict]:
    rows = psql(
        "SELECT json_agg(row_to_json(t) ORDER BY t.kind, t.code) FROM ("
        "SELECT s.id, s.code, s.kind, s.source_format, s.name, s.name_en, s.summary, s.summary_en, s.description, "
        "s.description_en, s.category, s.tags, s.status, s.published_version, s.latest_version, "
        "s.sort_weight, s.icon, s.icon_object_key, s.illustration_object_key, "
        "s.example_image_object_key, s.min_client_version, "
        "s.manifest, v.id AS version_id, v.version, v.revision, v.file_name, v.size_bytes, v.md5, "
        "v.sha256, v.file_count, v.extracted_bytes, v.package_format, v.ycps_version, "
        "v.package_kind, v.min_client_version AS version_min_client_version, "
        "v.status AS version_status, "
        "v.plugin_manifest, v.runtime_manifest, v.manifest_sha256, v.dependency_lock_sha256, "
        "v.sealed, p.task_types, p.task_types_en, p.usage_guide, p.usage_guide_en, "
        "p.example_prompts, p.example_prompts_en, p.preview_zh, p.preview_en, "
        "p.canvas_zh, p.canvas_en, "
        "p.portable_revision AS expert_portable_revision "
        "FROM gateway.skill s LEFT JOIN LATERAL ("
        "SELECT candidate.* FROM gateway.skill_version candidate "
        "WHERE candidate.skill_id = s.id AND candidate.object_key IS NOT NULL AND candidate.deleted_at IS NULL "
        "ORDER BY (candidate.status = 'PUBLISHED') DESC, "
        "(candidate.version = s.published_version) DESC, candidate.revision DESC LIMIT 1"
        ") v ON TRUE LEFT JOIN gateway.skill_expert_profile p "
        "ON p.skill_id = s.id WHERE s.deleted_at IS NULL AND s.source_format = 'YCPS') t;")
    rows = select_snapshot_versions(rows)

    for row in rows:
        code = row["code"]
        safe_filename(code)
        media_roles = (
            ("icon", "icon", "icon"),
            ("illustration", "illustration", "illustration"),
            ("example_image", "example-image", "example"),
        )
        for field, route, file_suffix in media_roles:
            if not row.get(f"{field}_object_key"):
                row[f"{field}_file"] = None
                continue
            data, content_type = cloud.get(
                f"/console/gateway/admin/skills/{row['id']}/{route}")
            rel, md5 = save(
                root, f"images/skills/{code}-{file_suffix}", data, content_type)
            row.update({f"{field}_file": rel, f"{field}_content_type": content_type,
                        f"{field}_bytes": len(data), f"{field}_md5": md5})
            row[f"{field}_sha256"] = hashlib.sha256(data).hexdigest()
            dimensions = image_dimensions(data, content_type)
            if dimensions:
                row.update({f"{field}_width": dimensions[0], f"{field}_height": dimensions[1]})
            print(f"  {field:13s} {code:32s} {len(data):9d} B")

        row.pop("icon_object_key", None)
        row.pop("illustration_object_key", None)
        row.pop("example_image_object_key", None)
        row["expert_presentation"] = expert_presentation(row)
        row.pop("canvas_zh", None)
        row.pop("canvas_en", None)

        if not row.get("file_name"):
            raise ValueError(f"YCPS row has no uploaded package: {code}")
        safe_filename(row["file_name"])
        # Keep stable fixture paths across cloud upload filenames and revision changes.
        # file_name still records the actual source upload name in catalog.json.
        package_name = f"{code}-{safe_filename(row['version'])}.ycps.zip"
        data, _ = cloud.get(
            f"/console/gateway/admin/skills/{row['id']}/versions/{row['version_id']}/package")
        digest = hashlib.sha256(data).hexdigest()
        if digest != row["sha256"]:
            raise SystemExit(f"sha256 不一致 {code}: {digest} != {row['sha256']}")
        if len(data) != row["size_bytes"] or hashlib.md5(data).hexdigest() != row["md5"]:
            raise ValueError(f"package size/md5 mismatch: {code}")
        (root / "packages").mkdir(parents=True, exist_ok=True)
        (root / "packages" / package_name).write_bytes(data)
        row["package_file"] = f"packages/{package_name}"
        print(f"  pkg    {code:32s} {len(data):9d} B  sha256 ok")
    return rows


def export_builtin(root: Path) -> dict:
    """本地内置 skills 的清单。内容就在仓库里，这里只登记身份与哈希。"""
    index_path = BUILTIN_ROOT / "ycps.index.json"
    config_path = BUILTIN_ROOT / "skills.config.json"
    index = json.loads(index_path.read_text(encoding="utf-8"))
    config = json.loads(config_path.read_text(encoding="utf-8"))
    defaults = config.get("defaults", {})

    packages = []
    for entry in index["packages"]:
        code = entry["code"]
        packages.append({
            "code": code,
            "kind": entry["kind"],
            "version": entry["version"],
            "revision": entry["revision"],
            "path": f"Yuzai/SKILLs/{entry['path']}",
            "artifactSha256": entry["artifactSha256"],
            "contentSha256": entry["contentSha256"],
            "order": defaults.get(code, {}).get("order"),
            "enabled": defaults.get(code, {}).get("enabled", True),
        })
    packages.sort(key=lambda p: (p["order"] is None, p["order"] or 0, p["code"]))
    print(f"  builtin {len(packages)} 个内置 skill")
    return {
        "$comment": "本地内置 skills。源码就在 Yuzai/SKILLs/ 下，随仓库分发，无需复原。",
        "source": "Yuzai/SKILLs",
        "index": "Yuzai/SKILLs/ycps.index.json",
        "config": "Yuzai/SKILLs/skills.config.json",
        "packages": packages,
    }


def managed_files(catalog: dict) -> set[str]:
    files = {row["image_file"] for row in catalog.get("categories", []) if row.get("image_file")}
    for row in catalog.get("skills", []):
        files.update(row[key] for key in ("package_file", "icon_file", "illustration_file", "example_image_file") if row.get(key))
    return files


def promote_snapshot(staging: Path, destination: Path) -> None:
    """Replace fully validated exports and remove only previously managed stale assets."""
    new_catalog = json.loads((staging / "catalog.json").read_text(encoding="utf-8"))
    old_path = destination / "catalog.json"
    old_catalog = json.loads(old_path.read_text(encoding="utf-8")) if old_path.exists() else {}
    stale = managed_files(old_catalog) - managed_files(new_catalog)
    for relative in stale:
        target = (destination / relative).resolve()
        if not target.is_relative_to(destination.resolve()) or Path(relative).parts[0] not in ("images", "packages"):
            raise ValueError("unsafe old snapshot asset path")
    for source in sorted(staging.rglob("*")):
        if source.is_file():
            target = destination / source.relative_to(staging)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    for relative in stale:
        (destination / relative).unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default=DEFAULT_BASE)
    parser.add_argument("--env-file", type=Path, default=DEFAULT_ENV_FILE)
    parser.add_argument("--output", type=Path, default=SNAPSHOT)
    args = parser.parse_args()

    cloud = Cloud(args.base, load_admin(args.env_file))
    cloud.login()

    destination = args.output.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Network or validation failures leave the previous fixture untouched.
    with tempfile.TemporaryDirectory(prefix=".catalog-export-", dir=destination.parent) as temporary:
        root = Path(temporary).resolve()
        if not root.is_relative_to(destination.parent.resolve()):
            raise ValueError("staging directory escaped destination parent")
        export_to(cloud, root, args.base)
        promote_snapshot(root, destination)
    print(f"-> {destination}")
    return 0


def export_to(cloud: Cloud, root: Path, base: str) -> None:

    print(f"[1/3] 分类词表  <- {base}")
    categories = export_categories(cloud, root)
    print(f"[2/3] 技能/专家与包体")
    skills = export_skills(cloud, root)
    members = psql(
        "SELECT json_agg(row_to_json(t) ORDER BY t.code, t.sort_order) FROM ("
        "SELECT s.code, m.member_key, m.name, m.name_en, m.profession, m.profession_en, "
        "m.member_role, m.sort_order FROM gateway.skill s "
        "JOIN gateway.skill_member m ON m.skill_id = s.id WHERE s.deleted_at IS NULL AND s.source_format = 'YCPS') t;")
    external = psql(
        "SELECT json_agg(row_to_json(t) ORDER BY t.code) FROM ("
        "SELECT code, kind, name, name_en, source_format, status, category, tags, published_version, latest_version "
        "FROM gateway.skill WHERE deleted_at IS NULL AND source_format <> 'YCPS') t;")
    print(f"[3/3] 本地内置 skills")
    builtin = export_builtin(root)

    root.mkdir(parents=True, exist_ok=True)
    (root / "catalog.json").write_text(json.dumps({
        "$comment": "当前环境的完整目录快照，供文档示例与测试夹具引用。"
                    "由 scripts/export-catalog-snapshot.py 生成，"
                    "用 scripts/restore-catalog-snapshot.py 复原。请勿手工修改。",
        "cloud": {"apiBase": base, "database": f"{PG_DATABASE}/gateway"},
        "scope": "Current authoritative uploaded YCPS package per entry, category covers and expert presentation/media; historical revisions and external-source skill bodies are not duplicated.",
        "categories": categories,
        "skills": skills,
        "members": members,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (root / "external-skills-inventory.json").write_text(json.dumps({
        "scope": "External-source directory inventory only; no uploaded YCPS package exists for these entries. Not part of YCPS restore.",
        "skills": external,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (root / "builtin-skills.json").write_text(
        json.dumps(builtin, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    refresh_repro_manifest(root)

    print(f"\n分类 {len(categories)} / 技能 {len(skills)} / 成员 {len(members)} / "
          f"内置 {len(builtin['packages'])}")


if __name__ == "__main__":
    raise SystemExit(main())

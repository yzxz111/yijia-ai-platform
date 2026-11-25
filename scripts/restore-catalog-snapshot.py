"""把 fixtures/catalog-snapshot 复原到一台新的云端/测试环境。

用途：换主机、换测试环境后，一条命令重建「当前本地环境」的完整目录数据：
快照中的分类词表与封面、全部技能/专家及其展示图片、包体与成员花名册。

设计要点：
  - 幂等：分类按 (kind, code) 判存在则跳过；技能若已存在则读取同版本 revision，
    低于快照时只通过正式“替换包”接口单调推进，等于或高于快照时不覆盖。
    所以中途失败可以重跑，不会产生重复行或把较新的包降级。
  - 普通已有技能只回填缺失图片；待修复的 curated Expert 若 profile 不完整，则把
    profile 与快照中的已校验图片一次性原子恢复；可选示例图缺失时保持空值。完整的管理员 Expert profile
    默认仍视为人工权威；只有显式传入 --repair-curated-media，才会按实际字节哈希
    修复与 curated 快照不一致的图片，且不会改写已完整的 presentation。
  - 只走公开的管理端 HTTP 接口，不直连数据库、不直写对象存储。
    这样复原出来的数据与手工在控制台操作的结果完全一致。
  - Python 只编排快照恢复；YCPS 包的正式序列化由生产 Java 上传/portable-revision
    流程负责。sealed Expert 通过 portable-revisions 端点生成 successor，不在本地重打包。
  - 图片与包体从快照目录读取，字节与云端当前一致（导出时已校验 sha256）。
  - 技能的 status 是 PUBLISHED，所以复原后 `GET /v1/skills` 立即能看到。

用法：
  python scripts/restore-catalog-snapshot.py --dry-run
  python scripts/restore-catalog-snapshot.py
  python scripts/restore-catalog-snapshot.py --base http://127.0.0.1:18081
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

SNAPSHOT = Path(__file__).resolve().parent.parent / "fixtures" / "catalog-snapshot"
DEFAULT_BASE = "http://127.0.0.1:18081"
# 管理员凭据默认从 deploy/env/.env 读取（LOCAL_ADMIN_EMAIL / LOCAL_ADMIN_PASSWORD），
# 因为 compose.yaml 已改为必填、无默认值，仓库里不再有固定密码。
# 读取不到时才回落到本地示例值。
DEFAULT_ENV_FILE = Path(__file__).resolve().parent.parent / "deploy" / "env" / ".env"
FALLBACK_ADMIN = {"identifier": "admin@local.test", "password": "LocalAdmin!2026"}
DEVICE_ID = "550e8400e29b41d4a716446655440000"

# The explicit media-repair switch is intentionally limited to the six curated Experts in this
# fixture.  A complete administrator profile remains authoritative for every other skill/Expert.
CURATED_EXPERT_CODES = frozenset({
    "ai-office-assistant",
    "content-summarizer",
    "content-writing-expert",
    "document-review-expert",
    "sales-analysis-expert",
    "social-media-operations-expert",
})


def snapshot_expert_tags(row: dict) -> list[str]:
    """Validate and return the Expert's operational tags exactly as exported.

    The original six curated Experts have an established ``["ycps"]`` invariant.  Other
    Experts, including newly imported ones with no tags, keep their own snapshot values;
    presentation taskTypes are never substituted for operational tags.
    """
    tags = row.get("tags")
    code = row.get("code")
    if not isinstance(tags, list) or len(tags) > 16 or any(
            not isinstance(tag, str) or not tag or tag != tag.strip() or len(tag) > 64
            for tag in tags) or len(set(tags)) != len(tags):
        raise RuntimeError(f"Expert {code} 快照 tags 不是合法字符串数组: {tags!r}")
    if code in CURATED_EXPERT_CODES and tags != ["ycps"]:
        raise RuntimeError(f"Expert {code} 快照 tags 必须为 ['ycps']，实际 {tags!r}")
    return list(tags)


def load_admin(env_file: Path) -> dict[str, str]:
    """从 .env 读取本地管理员凭据；缺失则回落示例值。"""
    email = password = None
    if env_file.is_file():
        text = env_file.read_text(encoding="utf-8", errors="replace")
        for line in text.splitlines():
            line = line.strip()
            if line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            key, value = key.strip(), value.strip().strip('"').strip("'")
            if key == "LOCAL_ADMIN_EMAIL":
                email = value
            elif key == "LOCAL_ADMIN_PASSWORD":
                password = value
    if email and password:
        return {"identifier": email, "password": password}
    print(f"  ! 未能从 {env_file} 读到凭据，回落到示例值", file=sys.stderr)
    return dict(FALLBACK_ADMIN)


MEDIA_TYPES = {
    ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
    ".webp": "image/webp", ".svg": "image/svg+xml", ".gif": "image/gif",
    ".zip": "application/zip",
}


def media_type_of(path: Path) -> str:
    """图片路由按 MIME 白名单校验，YAPS 包按 zip 处理。"""
    if path.name.endswith(".ycps.zip"):
        return "application/zip"
    return MEDIA_TYPES.get(path.suffix.lower(), "application/octet-stream")


class Client:
    def __init__(self, base: str, admin: dict[str, str]) -> None:
        self.base = base.rstrip("/")
        self.admin = admin
        self.token = ""

    def login(self) -> None:
        body = json.dumps(self.admin).encode()
        request = urllib.request.Request(
            f"{self.base}/console/platform/auth/login", data=body,
            headers={"Content-Type": "application/json", "X-Device-Id": DEVICE_ID},
            method="POST")
        with urllib.request.urlopen(request) as response:
            self.token = json.loads(response.read())["accessToken"]

    def call(self, method: str, path: str, payload=None, content_type=None):
        headers = {"Authorization": f"Bearer {self.token}"}
        data = None
        if payload is not None:
            if content_type:
                data, headers["Content-Type"] = payload, content_type
            else:
                data = json.dumps(payload).encode()
                headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
            f"{self.base}{path}", data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request) as response:
                raw = response.read()
                return response.status, (json.loads(raw) if raw else {})
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                return error.code, json.loads(raw or b"{}")
            except json.JSONDecodeError:
                return error.code, {"raw": raw.decode("utf-8", "replace")}

    def get_bytes(self, path: str) -> tuple[int, bytes, str | None]:
        """GET an asset without interpreting its body as JSON.

        Media repair must compare the bytes served by the admin API rather than trusting the
        projected URL in A1.  Keep non-2xx bodies opaque; callers only need the status and bytes
        to distinguish a missing asset from a matching/mismatching one.
        """
        request = urllib.request.Request(
            f"{self.base}{path}",
            headers={"Authorization": f"Bearer {self.token}"},
            method="GET",
        )
        try:
            with urllib.request.urlopen(request) as response:
                return response.status, response.read(), response.headers.get_content_type()
        except urllib.error.HTTPError as error:
            content_type = error.headers.get_content_type() if error.headers else None
            return error.code, error.read(), content_type

    def upload(
            self,
            path: str,
            file_path: Path,
            fields: dict[str, str],
            *,
            method: str = "POST"):
        return self.upload_many(path, fields, {"file": file_path}, method=method)

    def upload_many(
            self,
            path: str,
            fields: dict[str, str],
            files: dict[str, Path],
            *,
            method: str = "POST"):
        """Send one deterministic aggregate multipart request with named file parts."""
        boundary = "----ycpsrestore" + uuid.uuid4().hex
        parts: list[bytes] = []
        for name, value in fields.items():
            parts.append(
                f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n"
                f"{value}\r\n".encode())
        # 服务端按 MIME 白名单校验图片，必须发真实类型（不能一律 octet-stream）。
        for field, file_path in files.items():
            parts.append(
                f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; "
                f"filename=\"{file_path.name}\"\r\n"
                f"Content-Type: {media_type_of(file_path)}\r\n\r\n".encode())
            parts.append(file_path.read_bytes())
            parts.append(b"\r\n")
        parts.append(f"--{boundary}--\r\n".encode())
        return self.call(method, path, b"".join(parts),
                         f"multipart/form-data; boundary={boundary}")


def existing_categories(client: Client) -> set[tuple[str, str]]:
    found: set[tuple[str, str]] = set()
    for kind in ("SKILL", "EXPERT"):
        status, payload = client.call("GET", f"/console/gateway/admin/skills/categories?kind={kind}")
        if status != 200:
            raise RuntimeError(f"读取分类失败 {kind}: {status} {payload}")
        if isinstance(payload, dict):
            items = payload.get("categories", [])
        elif isinstance(payload, list):
            items = payload
        else:
            items = None
        if not isinstance(items, list):
            raise RuntimeError(f"读取分类响应异常 {kind}: {payload}")
        for item in items:
            if not isinstance(item, dict) or not item.get("code"):
                raise RuntimeError(f"读取分类响应异常 {kind}: {payload}")
            found.add((kind, item["code"]))
    return found


def existing_skills(client: Client) -> dict[str, dict]:
    status, payload = client.call("GET", "/console/gateway/admin/skills?page=0&size=100")
    if status != 200:
        raise SystemExit(f"读取技能列表失败: {status} {payload}")
    return {item["code"]: item for item in payload["items"]}


def pending_revisions(current_revision: int, snapshot_revision: int) -> tuple[int, ...]:
    """返回为达到快照 revision 所需的 A7 递增序列；绝不生成回退步骤。"""
    if isinstance(current_revision, bool) or not isinstance(current_revision, int) \
            or current_revision < 1:
        raise ValueError(f"当前 revision 非法: {current_revision!r}")
    if isinstance(snapshot_revision, bool) or not isinstance(snapshot_revision, int) \
            or snapshot_revision < 1:
        raise ValueError(f"快照 revision 非法: {snapshot_revision!r}")
    return tuple(range(current_revision + 1, snapshot_revision + 1))


def detail_version(client: Client, skill_id: str, version: str) -> dict | None:
    """只通过 A3 读取已有 code 的版本表，并找到快照对应的版本。"""
    status, payload = client.call("GET", f"/console/gateway/admin/skills/{skill_id}")
    if status != 200:
        raise RuntimeError(f"读取技能详情失败 {skill_id}: {status} {payload}")
    versions = payload.get("versions") if isinstance(payload, dict) else None
    if not isinstance(versions, list):
        raise RuntimeError(f"读取技能详情响应异常 {skill_id}: {payload}")
    matches = [
        item for item in versions
        if isinstance(item, dict) and item.get("version") == version
    ]
    if not matches:
        return None
    for item in matches:
        revision = item.get("revision")
        if isinstance(revision, bool) or not isinstance(revision, int) or revision < 1:
            raise RuntimeError(
                f"技能 {skill_id} 的版本 {version} 含非法 revision: {revision!r}")
    # A3 intentionally returns historical versions too.  Do not rely on its SQL ordering: a
    # READY portable successor must win over an older PUBLISHED row even if a proxy/repository
    # changes the order, and ties should prefer the publishable state.
    return max(
        matches,
        key=lambda item: (
            item["revision"],
            item.get("status") == "PUBLISHED",
            item.get("status") == "READY",
        ),
    )


def read_expert_profile(client: Client, skill_id: str) -> tuple[bool, dict | None]:
    """Read the admin Expert profile without guessing whether it is complete.

    The admin endpoint deliberately returns HTTP 200 with ``presentation: null`` when a curated
    Expert has no profile yet.  A transport/server error is reported as ``False`` so restore can
    fail closed and never replace an administrator's unknown profile with the fixture copy.
    """
    status, payload = client.call(
        "GET", f"/console/gateway/admin/skills/{skill_id}/expert-presentation")
    if status == 200:
        presentation = payload.get("presentation") if isinstance(payload, dict) else None
        return True, presentation if isinstance(presentation, dict) else None
    if status == 404:
        return True, None
    print(f"    ! 读取 Expert presentation 失败 {skill_id}: {status} {payload}")
    return False, None


def profile_complete(presentation: dict | None) -> bool:
    """Return whether the required WYSIWYG fields already exist.

    Media is intentionally not part of this predicate: the admin response projects media from
    the parent skill, while the aggregate endpoint uses KEEP/REPLACE actions.  A complete text
    profile is therefore the immutable-admin boundary; an existing completed profile is never
    rewritten merely because the snapshot contains the same bilingual content.
    """
    if not isinstance(presentation, dict):
        return False
    task_types = presentation.get("taskTypes")
    prompts = presentation.get("examplePrompts")
    preview = presentation.get("preview")

    def nonempty_strings(value) -> bool:
        return (
            isinstance(value, list)
            and bool(value)
            and all(isinstance(item, str) and bool(item.strip()) for item in value)
        )

    def complete_preview(value) -> bool:
        return (
            isinstance(value, dict)
            and all(
                isinstance(value.get(key), str) and bool(value[key].strip())
                for key in ("heading", "request", "outputTitle")
            )
            and nonempty_strings(value.get("outputParagraphs"))
        )

    return (
        nonempty_strings(task_types)
        and isinstance(presentation.get("usageGuide"), str)
        and bool(presentation["usageGuide"].strip())
        and nonempty_strings(prompts)
        and complete_preview(preview)
    )


def snapshot_canvas(row: dict) -> dict | None:
    """Return the optional canonical canvas carried by this snapshot row."""
    profile = row.get("expert_presentation")
    if not isinstance(profile, dict):
        return None
    canvas = profile.get("canvas")
    return canvas if isinstance(canvas, dict) and canvas else None


def missing_snapshot_canvas(row: dict, presentation: dict | None) -> bool:
    """A missing optional canvas is drift only when the approved snapshot contains one.

    Existing non-null administrator canvases are intentionally not classified as missing: the
    restore command must not overwrite a newer authored layout merely because an older snapshot
    differs.  The online verifier remains responsible for reporting that kind of divergence.
    """
    expected = snapshot_canvas(row)
    return expected is not None and (
        not isinstance(presentation, dict)
        or presentation.get("canvas") is None
    )


def _profile_value(row: dict, profile: dict, snake: str, camel: str, default=None):
    value = row.get(snake)
    if value is not None:
        return value
    return profile.get(camel, default)


def _preview_value(row: dict, profile: dict, row_key: str, profile_key: str) -> dict | None:
    value = row.get(row_key)
    if value is not None:
        return value
    value = profile.get(profile_key)
    return value if isinstance(value, dict) else None


def expert_aggregate_payload(
        row: dict,
        *,
        current: dict | None = None,
        presentation: dict | None = None,
        expected_row_version: int | None = None,
        image_actions: dict[str, str] | None = None,
        status: str | None = None,
        preserve_presentation: bool = False,
        preserve_management: bool = False) -> dict:
    """Build the complete multipart JSON contract for an Expert create/update."""
    expected_tags = snapshot_expert_tags(row)
    profile = row.get("expert_presentation")
    if not isinstance(profile, dict):
        profile = {}
    source = presentation if isinstance(presentation, dict) else {}

    def value(key: str, fallback=None):
        # The explicit curated-media repair path is allowed to replace images only.  Preserve
        # every field returned by the complete admin presentation verbatim, including optional
        # null/empty English fields; falling back to the snapshot here would silently overwrite
        # administrator-authored state while repairing a stale PNG/WebP.
        if preserve_presentation:
            return source.get(key)
        # Existing complete profiles are never passed here.  For an incomplete profile, preserve
        # useful already-entered fields and fill empty/invalid required values from the
        # authoritative snapshot.  This also repairs a row whose profile exists but contains an
        # empty required list or a partially-filled preview object.
        existing_value = source.get(key)
        usable = existing_value is not None
        if key in {"taskTypes", "taskTypesEn", "examplePrompts", "examplePromptsEn"}:
            usable = isinstance(existing_value, list) and bool(existing_value)
        elif key in {"usageGuide", "usageGuideEn"}:
            usable = isinstance(existing_value, str) and bool(existing_value.strip())
        elif key == "preview":
            usable = (
                isinstance(existing_value, dict)
                and isinstance(existing_value.get("outputParagraphs"), list)
                and bool(existing_value["outputParagraphs"])
                and bool(existing_value.get("heading"))
                and bool(existing_value.get("request"))
                and bool(existing_value.get("outputTitle"))
            )
        if usable:
            return existing_value
        return fallback

    task_types = value("taskTypes", _profile_value(row, profile, "task_types", "taskTypes", []))
    task_types_en = value(
        "taskTypesEn", _profile_value(row, profile, "task_types_en", "taskTypesEn", []))
    usage_guide = value(
        "usageGuide", _profile_value(row, profile, "usage_guide", "usageGuide", ""))
    usage_guide_en = value(
        "usageGuideEn", _profile_value(row, profile, "usage_guide_en", "usageGuideEn", None))
    prompts = value(
        "examplePrompts", _profile_value(row, profile, "example_prompts", "examplePrompts", []))
    prompts_en = value(
        "examplePromptsEn",
        _profile_value(row, profile, "example_prompts_en", "examplePromptsEn", []),
    )
    preview = value("preview", _preview_value(row, profile, "preview_zh", "preview"))
    preview_en = value("previewEn", _preview_value(row, profile, "preview_en", "previewEn"))
    actions = image_actions or {"icon": "REPLACE", "illustration": "REPLACE", "exampleImage": "REPLACE"}
    current_status = current.get("status") if isinstance(current, dict) else None

    def management(camel: str, snake: str, default=None):
        if preserve_management and isinstance(current, dict) and camel in current:
            return current[camel]
        return row.get(snake, default)

    return {
        "expectedRowVersion": expected_row_version,
        "code": row["code"],
        "name": management("name", "name"),
        "nameEn": management("nameEn", "name_en"),
        "category": management("category", "category"),
        "tags": management("tags", "tags", expected_tags) or [],
        "summary": management("summary", "summary"),
        "summaryEn": management("summaryEn", "summary_en"),
        "description": management("description", "description"),
        "descriptionEn": management("descriptionEn", "description_en"),
        "icon": management("icon", "icon"),
        "minClientVersion": management("minClientVersion", "min_client_version"),
        "sortWeight": management("sortWeight", "sort_weight", 0) or 0,
        "status": status or current_status or "DRAFT",
        "taskTypes": task_types,
        "taskTypesEn": task_types_en,
        "usageGuide": usage_guide,
        "usageGuideEn": usage_guide_en,
        "examplePrompts": prompts,
        "examplePromptsEn": prompts_en,
        "preview": preview,
        "previewEn": preview_en,
        "canvas": profile.get("canvas"),
        "iconAction": actions.get("icon", "KEEP"),
        "illustrationAction": actions.get("illustration", "KEEP"),
        "exampleImageAction": actions.get("exampleImage", "KEEP"),
    }


def expert_image_actions(row: dict) -> tuple[dict[str, str], dict[str, Path]]:
    """Restore declared Expert assets, clearing an absent optional example image.

    The old cloud rows can still point at pre-portable PNG/SVG artwork.  Keeping those pointers
    would both leave the aggregate different from the snapshot and make the subsequent YCPS 1.1
    compiler reject the legacy bytes.  Icon and illustration are required by this snapshot
    restore contract; exampleImage is optional, as in the Expert aggregate API.
    """
    roles = (
        ("icon", "icon_file"),
        ("illustration", "illustration_file"),
        ("exampleImage", "example_image_file"),
    )
    actions: dict[str, str] = {}
    files: dict[str, Path] = {}
    for role, row_key in roles:
        rel = row.get(row_key)
        if not rel:
            if role == "exampleImage" and rel is None:
                actions[role] = "CLEAR"
                continue
            raise RuntimeError(f"Expert {row['code']} 缺少快照图片: {row_key}")
        if not isinstance(rel, str):
            raise RuntimeError(f"Expert {row['code']} 快照图片路径非法: {row_key}")
        actions[role] = "REPLACE"
        files[role] = SNAPSHOT / rel
    return actions, files


def curated_expert_media_actions(
        client: Client,
        row: dict) -> tuple[dict[str, str], dict[str, Path]]:
    """Compare bytes and atomically replace all roles when any curated asset mismatches.

    Replacing the complete trio avoids retaining a legacy pointer whose object can disappear
    between the public byte check and the aggregate KEEP validation. A fully matching trio is
    still a no-op, so reruns remain idempotent.
    """
    code = row.get("code")
    if code not in CURATED_EXPERT_CODES:
        raise RuntimeError(f"Expert {code!r} 不在 curated media 修复白名单")
    roles = (
        ("icon", "icon", "icon_file", "icon_md5"),
        ("illustration", "illustration", "illustration_file", "illustration_md5"),
        ("exampleImage", "example-image", "example_image_file", "example_image_md5"),
    )
    files_by_role: dict[str, Path] = {}
    mismatch = False
    for role, route, file_key, digest_key in roles:
        rel = row.get(file_key)
        expected = row.get(digest_key)
        if not isinstance(rel, str) or not rel or not isinstance(expected, str) or not expected:
            raise RuntimeError(f"Expert {code} 缺少快照媒体校验字段: {file_key}/{digest_key}")
        status, content, actual_mime = client.get_bytes(f"/v1/skills/{code}/{route}")
        # MD5 is snapshot-integrity metadata here, not a security primitive.
        actual = hashlib.md5(content).hexdigest() if status == 200 else None
        source = SNAPSHOT / rel
        files_by_role[role] = source
        mismatch = (
            mismatch
            or actual != expected.lower()
            or actual_mime != media_type_of(source)
        )
    if not mismatch:
        return ({role: "KEEP" for role, *_ in roles}, {})
    return ({role: "REPLACE" for role, *_ in roles}, files_by_role)


def _aggregate_upload(
        client: Client,
        path: str,
        payload: dict,
        files: dict[str, Path],
        *,
        method: str = "PUT"):
    data = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    if hasattr(client, "upload_many"):
        return client.upload_many(path, {"data": data}, files, method=method)
    # Kept only for tiny third-party test doubles that predate the aggregate endpoint.  Production
    # always uses Client.upload_many, which sends named icon/illustration/exampleImage parts.
    raise TypeError("aggregate Expert restore requires Client.upload_many")


def repair_expert_profile(
        client: Client,
        skill_id: str,
        row: dict,
        current: dict,
        presentation: dict | None,
        dry: bool) -> bool:
    """Fill an absent/incomplete Expert with one complete aggregate update."""
    if profile_complete(presentation):
        print("    = Expert presentation 已完整，不覆盖管理员内容")
        return True
    actions, files = expert_image_actions(row)
    expected = current.get("rowVersion") or current.get("row_version")
    if not expected:
        status, detail = client.call("GET", f"/console/gateway/admin/skills/{skill_id}")
        if status == 200 and isinstance(detail, dict):
            expected = detail.get("rowVersion") or detail.get("row_version")
    if not isinstance(expected, int) or expected < 1:
        print("    ! Expert profile 缺少有效 rowVersion，跳过修复")
        return False
    payload = expert_aggregate_payload(
        row,
        current=current,
        presentation=presentation,
        expected_row_version=expected,
        image_actions=actions,
        status=current.get("status") or row.get("status") or "PUBLISHED",
    )
    if dry:
        print("    + Expert presentation/media 原子修复 (dry-run)")
        return True
    status, response = _aggregate_upload(
        client, f"/console/gateway/admin/skills/{skill_id}/expert-presentation",
        payload, files)
    if status not in (200, 201):
        print(f"    ! Expert presentation/media 修复失败: {status} {response}")
        return False
    print("    + Expert presentation/media 原子修复")
    return True


def repair_missing_expert_canvas(
        client: Client,
        skill_id: str,
        row: dict,
        current: dict,
        presentation: dict,
        dry: bool) -> tuple[dict, dict]:
    """Restore a snapshot canvas that disappeared while preserving every other field.

    Canvas is optional for the protocol as a whole, but it is part of the approved identity for
    a row whose snapshot explicitly carries one.  This narrow repair only fills a missing value;
    it never replaces a non-null administrator-authored canvas and never rewrites media bytes.
    """
    if not missing_snapshot_canvas(row, presentation):
        return current, presentation
    expected = current.get("rowVersion") or current.get("row_version")
    if isinstance(expected, bool) or not isinstance(expected, int) or expected < 1:
        raise RuntimeError(f"Expert {row['code']} 缺少有效 rowVersion，无法恢复 canvas")
    payload = expert_aggregate_payload(
        row,
        current=current,
        presentation=presentation,
        expected_row_version=expected,
        image_actions={"icon": "KEEP", "illustration": "KEEP", "exampleImage": "KEEP"},
        status=current.get("status") or row.get("status") or "PUBLISHED",
        preserve_presentation=True,
        preserve_management=True,
    )
    if dry:
        print("    + Expert canvas <- 快照 (dry-run)")
        return current, presentation
    status, response = _aggregate_upload(
        client,
        f"/console/gateway/admin/skills/{skill_id}/expert-presentation",
        payload,
        {},
    )
    if status not in (200, 201) or not isinstance(response, dict):
        raise RuntimeError(f"Expert canvas 恢复失败 {row['code']}: {status} {response}")
    saved_presentation = response.get("presentation")
    if not isinstance(saved_presentation, dict) \
            or saved_presentation.get("canvas") != snapshot_canvas(row):
        raise RuntimeError(f"Expert canvas 恢复响应不一致 {row['code']}: {response}")
    saved_skill = response.get("skill")
    updated = {**current, **(saved_skill if isinstance(saved_skill, dict) else {})}
    row_version = saved_presentation.get("rowVersion")
    if isinstance(row_version, int) and not isinstance(row_version, bool):
        updated["rowVersion"] = row_version
    print("    + Expert canvas <- 快照")
    return updated, saved_presentation


def repair_curated_expert_media(
        client: Client,
        skill_id: str,
        row: dict,
        current: dict,
        presentation: dict,
        dry: bool) -> bool:
    """Explicitly repair only mismatching curated media while preserving complete admin copy."""
    if not profile_complete(presentation):
        raise RuntimeError(f"Expert {row['code']} presentation 不完整，不能走 media-only 修复")
    actions, files = curated_expert_media_actions(client, row)
    if not files:
        print("    = Expert curated media 字节已一致")
        return True
    expected = current.get("rowVersion") or current.get("row_version")
    if not isinstance(expected, int) or expected < 1:
        status, detail = client.call("GET", f"/console/gateway/admin/skills/{skill_id}")
        if status == 200 and isinstance(detail, dict):
            expected = detail.get("rowVersion") or detail.get("row_version")
    if not isinstance(expected, int) or expected < 1:
        print("    ! Expert media-only 修复缺少有效 rowVersion")
        return False
    payload = expert_aggregate_payload(
        row,
        current=current,
        presentation=presentation,
        expected_row_version=expected,
        image_actions=actions,
        status=current.get("status") or row.get("status") or "PUBLISHED",
        preserve_presentation=True,
        preserve_management=True,
    )
    roles = ", ".join(files)
    if dry:
        print(f"    + Expert curated media 修复: {roles} (dry-run)")
        return True
    status, response = _aggregate_upload(
        client,
        f"/console/gateway/admin/skills/{skill_id}/expert-presentation",
        payload,
        files,
    )
    if status not in (200, 201):
        print(f"    ! Expert curated media 修复失败: {status} {response}")
        return False
    print(f"    + Expert curated media 修复: {roles}")
    return True


def create_expert_aggregate(
        client: Client,
        row: dict,
        dry: bool) -> str | None:
    """Create a new Expert and its declared presentation assets in one aggregate request."""
    actions, files = expert_image_actions(row)
    payload = expert_aggregate_payload(row, image_actions=actions, status="DRAFT")
    if dry:
        print(f"  + Expert {row['code']} 原子创建（presentation + {len(files)} images，dry-run）")
        return "<new-expert-id>"
    status, response = _aggregate_upload(
        client, "/console/gateway/admin/skills/experts", payload, files, method="POST")
    if status not in (200, 201):
        print(f"  ! Expert 原子创建失败 {row['code']}: {status} {response}")
        return None
    skill = response.get("skill") if isinstance(response, dict) else None
    skill_id = response.get("skillId") or response.get("id")
    if not skill_id and isinstance(skill, dict):
        skill_id = skill.get("id")
    if not skill_id:
        print(f"  ! Expert 原子创建缺少 skill id: {response}")
        return None
    print(f"  + Expert {row['code']} 原子创建（presentation + {len(files)} images）")
    return str(skill_id)


def advance_revision(
        client: Client,
        skill_id: str,
        version: dict,
        package: Path,
        fields: dict[str, str],
        snapshot_revision: int) -> dict | None:
    """用正式 A7 替换包接口逐次递增，直到达到快照 revision。"""
    current_revision = version.get("revision")
    try:
        revisions = pending_revisions(current_revision, snapshot_revision)
    except ValueError as error:
        print(f"    ! 无法恢复 revision: {error}")
        return None

    if not revisions:
        relation = "高于" if current_revision > snapshot_revision else "等于"
        print(
            f"    = 当前 rev{current_revision} {relation}快照 rev{snapshot_revision}，"
            "不覆盖")
        return version

    version_id = version.get("id")
    if not version_id:
        print("    ! 版本缺少 id，无法通过替换包接口递增 revision")
        return None

    latest = version
    for expected_revision in revisions:
        status, replaced = client.upload(
            f"/console/gateway/admin/skills/{skill_id}/versions/{version_id}/package",
            package,
            fields,
            method="PUT")
        if status not in (200, 201):
            print(f"    ! 包替换失败，目标 rev{expected_revision}: {status} {replaced}")
            return None
        actual_revision = replaced.get("revision")
        if actual_revision != expected_revision:
            print(
                f"    ! 包替换后的 revision 异常: 期望 rev{expected_revision}，"
                f"实际 {actual_revision!r}")
            return None
        latest = replaced
        print(f"    + 包替换 -> rev{actual_revision}")
    return latest


def advance_expert_revision(
        client: Client,
        skill_id: str,
        version: dict,
        package: Path,
        fields: dict[str, str],
        snapshot_revision: int) -> dict | None:
    """Advance an Expert revision, compiling sealed bases through portable-revisions.

    YCPS 1.1 sealed artifacts are immutable.  The legacy package PUT remains valid for an
    unsealed version, but must never be used once the selected base has been sealed.  Portable
    generation returns a new version id; that successor becomes the base for the next monotonic
    step (which also makes a large revision gap converge deterministically).
    """
    current_revision = version.get("revision")
    try:
        revisions = pending_revisions(current_revision, snapshot_revision)
    except ValueError as error:
        print(f"    ! 无法恢复 Expert revision: {error}")
        return None
    if not revisions:
        relation = "高于" if current_revision > snapshot_revision else "等于"
        print(
            f"    = 当前 Expert rev{current_revision} {relation}快照 rev{snapshot_revision}，不覆盖")
        return version

    latest = version
    for expected_revision in revisions:
        version_id = latest.get("id")
        if not version_id:
            print("    ! Expert 版本缺少 id，无法递增 revision")
            return None
        if latest.get("sealed"):
            status, generated = client.call(
                "POST",
                f"/console/gateway/admin/skills/{skill_id}/versions/{version_id}/portable-revisions",
            )
            action = "portable revision"
        else:
            status, generated = client.upload(
                f"/console/gateway/admin/skills/{skill_id}/versions/{version_id}/package",
                package,
                fields,
                method="PUT")
            action = "包替换"
        if status not in (200, 201):
            print(f"    ! Expert {action} 失败，目标 rev{expected_revision}: {status} {generated}")
            return None
        actual_revision = generated.get("revision")
        if actual_revision != expected_revision:
            print(
                f"    ! Expert {action} 后 revision 异常: 期望 rev{expected_revision}，"
                f"实际 {actual_revision!r}")
            return None
        latest = generated
        print(f"    + Expert {action} -> rev{actual_revision}")
    return latest


def publish_expert_snapshot_revision(
        client: Client,
        skill_id: str,
        version: dict,
        row: dict,
        snapshot_revision: int | None = None) -> dict | None:
    """Publish the exact restored Expert revision when the snapshot says it is public."""
    target_revision = row.get("revision", 1) if snapshot_revision is None else snapshot_revision
    actual_revision = version.get("revision")
    if isinstance(target_revision, bool) or not isinstance(target_revision, int) \
            or target_revision < 1:
        print(f"    ! Expert 快照 revision 非法: {target_revision!r}")
        return None
    if isinstance(actual_revision, bool) or not isinstance(actual_revision, int) \
            or actual_revision < 1:
        print(f"    ! Expert 目标 revision 非法: {actual_revision!r}")
        return None
    if actual_revision > target_revision:
        # A newer package is never downgraded or promoted merely because this snapshot is old.
        # In particular, an interrupted newer READY successor must not become the live package.
        print(
            f"    = Expert 当前 rev{actual_revision} 高于快照 rev{target_revision}，"
            "不覆盖或发布")
        return version
    if actual_revision < target_revision:
        print(
            f"    ! Expert 尚未达到快照 revision: 当前 rev{actual_revision}，"
            f"目标 rev{target_revision}")
        return None
    if row.get("status") != "PUBLISHED" or version.get("status") == "PUBLISHED":
        return version
    version_id = version.get("id")
    if not version_id:
        print("    ! Expert 目标 revision 缺少 id，无法发布")
        return None
    status, published = client.call(
        "POST", f"/console/gateway/admin/skills/{skill_id}/versions/{version_id}/publish")
    if status != 200:
        print(f"    ! Expert 目标 revision 发布失败: {status} {published}")
        return None
    if not isinstance(published, dict) \
            or published.get("revision") != target_revision \
            or published.get("status") not in (None, "PUBLISHED"):
        print(f"    ! Expert 目标 revision 发布响应异常: {published}")
        return None
    print(f"    + Expert 目标 revision 发布: rev{published.get('revision')}")
    return published


MEDIA_ROLES = (
    ("icon", "icon", "iconUrl"),
    ("illustration", "illustration", "illustrationUrl"),
    ("example_image", "example-image", "exampleImageUrl"),
)


def upload_snapshot_version(
        client: Client,
        skill_id: str,
        row: dict,
        package: Path,
        fields: dict[str, str]) -> dict:
    """Upload the snapshot's semantic version and fail closed on every non-success response."""
    version_fields = {"version": row["version"]}
    version_fields.update(fields)
    status, version = client.upload(
        f"/console/gateway/admin/skills/{skill_id}/versions", package, version_fields)
    if status not in (200, 201) or not isinstance(version, dict):
        raise RuntimeError(f"包上传失败 {row['code']}: {status} {version}")
    revision = version.get("revision")
    version_id = version.get("id")
    if isinstance(revision, bool) or not isinstance(revision, int) or revision < 1 \
            or not version_id:
        raise RuntimeError(f"包上传响应异常 {row['code']}: {version}")
    print(f"    + 包 v{row['version']} rev{revision}")
    return version


def restore_skill_media(
        client: Client,
        skill_id: str,
        row: dict,
        current: dict | None,
        dry: bool) -> None:
    """Backfill missing snapshot media without replacing an existing remote image."""
    for field, route, url_field in MEDIA_ROLES:
        rel = row.get(f"{field}_file")
        if not rel:
            continue
        if current and current.get(url_field):
            print(f"    = {field} 已存在，不覆盖")
            continue
        if dry:
            print(f"    + {field} <- {rel} (dry-run)")
            continue
        status, payload = client.upload(
            f"/console/gateway/admin/skills/{skill_id}/{route}", SNAPSHOT / rel, {})
        print(f"    {'+' if status == 200 else '!'} {field}: {status}")
        if status != 200:
            raise RuntimeError(f"{field} 上传失败 {skill_id}: {status} {payload}")


def restore_categories(client: Client, snapshot: dict, dry: bool) -> None:
    have = existing_categories(client)
    for row in snapshot["categories"]:
        kind, code = row["kind"], row["code"]
        if (kind, code) in have:
            print(f"  = 分类已存在 {kind}/{code}")
            continue
        if dry:
            print(f"  + 分类 {kind}/{code} {row['name']} (dry-run)")
            continue
        status, payload = client.call("POST", "/console/gateway/admin/skills/categories", {
            "kind": kind, "code": code, "name": row["name"],
            "nameEn": row.get("name_en"), "sortOrder": row["sort_order"],
        })
        if status != 201:
            raise RuntimeError(f"分类创建失败 {kind}/{code}: {status} {payload}")
        print(f"  + 分类 {kind}/{code} {row['name']}")

    # 封面必须在分类存在之后上传
    for row in snapshot["categories"]:
        if not row.get("image_file"):
            continue
        image = SNAPSHOT / row["image_file"]
        status, content, content_type = client.get_bytes(
            f"/console/gateway/admin/skills/categories/{row['code']}/image?kind={row['kind']}"
        )
        if (
            status == 200
            and hashlib.md5(content).hexdigest() == hashlib.md5(image.read_bytes()).hexdigest()
            and content_type == media_type_of(image)
        ):
            print(f"  = 封面 {row['code']} 字节与 MIME 已一致")
            continue
        if dry:
            print(f"  + 封面 {row['code']} <- {row['image_file']} (dry-run)")
            continue
        status, payload = client.upload(
            f"/console/gateway/admin/skills/categories/{row['code']}/image?kind={row['kind']}",
            image, {})
        print(f"  {'+' if status == 200 else '!'} 封面 {row['code']}: {status}")
        if status != 200:
            raise RuntimeError(f"分类封面上传失败 {row['kind']}/{row['code']}: {status} {payload}")


def restore_skill_tags(
        client: Client,
        skill_id: str,
        row: dict,
        current: dict,
        dry: bool) -> dict:
    """Repair only operational Expert tags without touching a complete admin presentation."""
    expected_tags = snapshot_expert_tags(row)
    # A1 always contains tags in production.  Keep tiny offline doubles compatible while still
    # failing closed when the real endpoint explicitly reports a different operational tag set.
    if "tags" not in current or current.get("tags") == expected_tags:
        return current
    if dry:
        print(f"    + Expert tags -> {expected_tags} (dry-run)")
        return current
    row_version = current.get("rowVersion") or current.get("row_version")
    if isinstance(row_version, bool) or not isinstance(row_version, int) or row_version < 1:
        raise RuntimeError(f"Expert {row['code']} 缺少有效 rowVersion，无法恢复 tags")
    payload = {
        "name": current.get("name") or row.get("name"),
        "nameEn": current.get("nameEn", row.get("name_en")),
        "category": current.get("category", row.get("category")),
        "tags": expected_tags,
        "summary": current.get("summary") or row.get("summary"),
        "summaryEn": current.get("summaryEn", row.get("summary_en")),
        "description": current.get("description") or row.get("description"),
        "descriptionEn": current.get("descriptionEn", row.get("description_en")),
        "icon": current.get("icon", row.get("icon")),
        "minClientVersion": current.get("minClientVersion", row.get("min_client_version")),
        "kind": current.get("kind") or row.get("kind"),
        "sortWeight": current.get("sortWeight", row.get("sort_weight") or 0),
        "rowVersion": row_version,
    }
    status, response = client.call("PUT", f"/console/gateway/admin/skills/{skill_id}", payload)
    if status != 200 or not isinstance(response, dict):
        raise RuntimeError(f"Expert tags 恢复失败 {row['code']}: {status} {response}")
    print(f"    + Expert tags -> {expected_tags}")
    return {**current, **response}


def _publish_snapshot_version(
        client: Client,
        skill_id: str,
        version: dict,
        row: dict,
        *,
        label: str = "技能") -> dict:
    """Publish exactly the snapshot revision, never a newer READY revision."""
    target_revision = row.get("revision", 1)
    actual_revision = version.get("revision")
    if isinstance(target_revision, bool) or not isinstance(target_revision, int) \
            or target_revision < 1 or isinstance(actual_revision, bool) \
            or not isinstance(actual_revision, int) or actual_revision < 1:
        raise RuntimeError(f"{label} {row['code']} 发布 revision 非法: {version}")
    if actual_revision > target_revision:
        print(
            f"    = {label} 当前 rev{actual_revision} 高于快照 rev{target_revision}，"
            "不覆盖或发布")
        return version
    if actual_revision < target_revision:
        raise RuntimeError(
            f"{label} {row['code']} 尚未达到快照 revision: "
            f"当前 rev{actual_revision}，目标 rev{target_revision}")
    if row.get("status") != "PUBLISHED" or version.get("status") == "PUBLISHED":
        return version
    version_id = version.get("id")
    if not version_id:
        raise RuntimeError(f"{label} {row['code']} 目标 revision 缺少 id，无法发布")
    status, published = client.call(
        "POST", f"/console/gateway/admin/skills/{skill_id}/versions/{version_id}/publish")
    print(f"    {'+' if status == 200 else '!'} {label} 目标 revision 发布: {status}")
    if status != 200 or not isinstance(published, dict) \
            or published.get("revision") != target_revision \
            or published.get("status") not in (None, "PUBLISHED"):
        raise RuntimeError(f"{label} {row['code']} 发布失败或响应异常: {status} {published}")
    return published


def restore_skills(
        client: Client,
        snapshot: dict,
        dry: bool,
        *,
        repair_curated_media: bool = False) -> None:
    have = existing_skills(client)
    for row in snapshot["skills"]:
        code = row["code"]
        snapshot_revision = row.get("revision", 1)
        try:
            # 在产生任何远端写入前校验快照值。
            pending_revisions(1, snapshot_revision)
        except ValueError as error:
            raise RuntimeError(f"快照 {code} 无法恢复: {error}") from error

        package = SNAPSHOT / row["package_file"]
        replacement_fields: dict[str, str] = {}
        if row.get("version_min_client_version"):
            replacement_fields["minClientVersion"] = row["version_min_client_version"]

        existing = have.get(code)
        if existing:
            skill_id = existing.get("id")
            if not skill_id:
                raise RuntimeError(f"技能 {code} 已存在但缺少 id")
            if row.get("kind") == "EXPERT":
                # Validate before any aggregate/profile or version writes.  Business taskTypes
                # remain presentation fields and are never copied into operational tags.
                snapshot_expert_tags(row)
                known_profile, presentation = read_expert_profile(client, skill_id)
                if not known_profile:
                    raise RuntimeError(f"Expert presentation 状态未知: {code}")
                complete = profile_complete(presentation)
                if not complete:
                    if not repair_expert_profile(
                            client, skill_id, row, existing, presentation, dry):
                        # A failed aggregate repair must not be followed by a portable compile:
                        # the compiler intentionally rejects an absent/incomplete profile.
                        raise RuntimeError(f"Expert presentation/media restore failed: {code}")
                else:
                    existing = restore_skill_tags(client, skill_id, row, existing, dry)
                    existing, presentation = repair_missing_expert_canvas(
                        client, skill_id, row, existing, presentation, dry)
                    if repair_curated_media and not repair_curated_expert_media(
                            client, skill_id, row, existing, presentation, dry):
                        raise RuntimeError(f"Expert curated media restore failed: {code}")

                version = detail_version(client, skill_id, row["version"])
                if version is None:
                    if dry:
                        print(
                            f"  ~ Expert 已存在 {code}，缺少 v{row['version']}，"
                            f"将上传并推进到 rev{snapshot_revision} (dry-run)")
                        continue
                    # A previous aggregate may have succeeded while its package upload failed.
                    # Creating the missing semantic version is safe and makes a rerun converge.
                    version = upload_snapshot_version(
                        client, skill_id, row, package, replacement_fields)
                if dry:
                    revisions = pending_revisions(version.get("revision"), snapshot_revision)
                    if revisions:
                        route = "portable-revisions（sealed）" if version.get("sealed") \
                                else "替换包接口"
                        print(
                            f"  ~ Expert 已存在 {code}，将通过{route}从 "
                            f"rev{version.get('revision')} 推进到 rev{snapshot_revision} (dry-run)")
                    else:
                        print(
                            f"  = Expert 已存在 {code} rev{version.get('revision')}，"
                            f"已达到快照 rev{snapshot_revision} (dry-run)")
                    continue
                print(f"  = Expert 已存在 {code}，检查 v{row['version']} revision")
                restored_version = advance_expert_revision(
                    client, skill_id, version, package, replacement_fields, snapshot_revision)
                if restored_version is None:
                    raise RuntimeError(f"Expert package restore failed: {code}")
                if publish_expert_snapshot_revision(
                        client, skill_id, restored_version, row, snapshot_revision) is None:
                    raise RuntimeError(f"Expert package publish failed: {code}")
                continue

            version = detail_version(client, skill_id, row["version"])
            if version is None:
                if dry:
                    print(
                        f"  ~ 技能已存在 {code}，缺少 v{row['version']}，"
                        f"将上传并推进到 rev{snapshot_revision} (dry-run)")
                    restore_skill_media(client, skill_id, row, existing, dry=True)
                    continue
                version = upload_snapshot_version(
                    client, skill_id, row, package, replacement_fields)
            if dry:
                revisions = pending_revisions(version.get("revision"), snapshot_revision)
                if revisions:
                    print(
                        f"  ~ 技能已存在 {code}，将通过替换包接口从 "
                        f"rev{version.get('revision')} 推进到 rev{snapshot_revision} (dry-run)")
                else:
                    print(
                        f"  = 技能已存在 {code} rev{version.get('revision')}，"
                        f"已达到快照 rev{snapshot_revision} (dry-run)")
                restore_skill_media(client, skill_id, row, existing, dry=True)
                continue
            print(f"  = 技能已存在 {code}，检查 v{row['version']} revision")
            restored_version = advance_revision(
                client, skill_id, version, package, replacement_fields, snapshot_revision)
            if restored_version is None:
                raise RuntimeError(f"技能 package restore failed: {code}")
            _publish_snapshot_version(client, skill_id, restored_version, row)
            restore_skill_media(client, skill_id, row, existing, dry=False)
            continue

        if row.get("kind") == "EXPERT":
            snapshot_expert_tags(row)
            skill_id = create_expert_aggregate(client, row, dry)
            if dry:
                continue
            if skill_id is None:
                raise RuntimeError(f"Expert create failed: {code}")
            version = upload_snapshot_version(
                client, skill_id, row, package, replacement_fields)
            version = advance_expert_revision(
                client, skill_id, version, package, replacement_fields, snapshot_revision)
            if version is None:
                raise RuntimeError(f"Expert package restore failed: {code}")
            if publish_expert_snapshot_revision(
                    client, skill_id, version, row, snapshot_revision) is None:
                raise RuntimeError(f"Expert package publish failed: {code}")
            continue

        if dry:
            print(
                f"  + 技能 {code} {row['name']}，包将推进到 "
                f"rev{snapshot_revision} (dry-run)")
            restore_skill_media(client, "<new-skill-id>", row, None, dry=True)
            continue
        status, payload = client.call("POST", "/console/gateway/admin/skills", {
            "code": code, "name": row["name"], "nameEn": row.get("name_en"),
            "category": row.get("category"), "tags": row.get("tags") or [],
            "summary": row.get("summary"), "summaryEn": row.get("summary_en"),
            "description": row.get("description"), "descriptionEn": row.get("description_en"),
            "icon": row.get("icon"), "kind": row["kind"],
            "minClientVersion": row.get("min_client_version"),
            "sortWeight": row.get("sort_weight") or 0,
        })
        if status not in (200, 201) or not isinstance(payload, dict):
            raise RuntimeError(f"技能创建失败 {code}: {status} {payload}")
        skill_id = payload.get("id")
        if not skill_id:
            raise RuntimeError(f"技能创建响应缺少 id {code}: {payload}")
        print(f"  + 技能 {code} {row['name']} ({row['kind']})")

        version = upload_snapshot_version(
            client, skill_id, row, package, replacement_fields)
        version = advance_revision(
            client, skill_id, version, package, replacement_fields, snapshot_revision)
        if version is None:
            raise RuntimeError(f"技能 package restore failed: {code}")

        version_id = version.get("id")
        if not version_id:
            raise RuntimeError(f"技能目标 revision 缺少 id: {code}")
        status, published = client.call(
            "POST", f"/console/gateway/admin/skills/{skill_id}/versions/{version_id}/publish")
        print(f"    {'+' if status == 200 else '!'} 发布: {status}")
        if status != 200 or not isinstance(published, dict) \
                or published.get("revision") != row.get("revision", 1) \
                or published.get("status") not in (None, "PUBLISHED"):
            raise RuntimeError(f"技能发布失败或响应异常 {code}: {status} {published}")

        restore_skill_media(client, skill_id, row, None, dry=False)


def restore_members(client: Client, snapshot: dict, dry: bool) -> None:
    have = existing_skills(client)
    by_code: dict[str, list[dict]] = {}
    for row in snapshot.get("members", []):
        by_code.setdefault(row["code"], []).append(row)
    planned_codes = {row["code"] for row in snapshot.get("skills", [])} if dry else set()
    missing = [code for code in by_code if code not in have and code not in planned_codes]
    if missing:
        raise RuntimeError(f"花名册目标技能缺失: {', '.join(missing)}")
    for code, roster in by_code.items():
        item = have.get(code)
        if item is None:
            # A clean-environment dry run planned this Expert in restore_skills but has no id yet.
            print(f"  + 花名册 {code} ({len(roster)} 人) (dry-run，新技能创建后)")
            continue
        members = [{
            "memberKey": m["member_key"], "name": m.get("name"), "nameEn": m.get("name_en"),
            "profession": m.get("profession"), "professionEn": m.get("profession_en"),
            "role": m["member_role"], "sortOrder": m["sort_order"],
        } for m in sorted(roster, key=lambda m: m["sort_order"])]
        members.sort(key=lambda member: (member.get("sortOrder") or 0, member["memberKey"]))
        status, detail = client.call(
            "GET", f"/console/gateway/admin/skills/{item['id']}")
        if status != 200 or not isinstance(detail, dict):
            raise RuntimeError(f"读取花名册失败 {code}: {status} {detail}")
        current = detail.get("members")
        if isinstance(current, list):
            normalized = [{key: member.get(key) for key in (
                "memberKey", "name", "nameEn", "profession", "professionEn", "role", "sortOrder"
            )} for member in current if isinstance(member, dict)]
            normalized.sort(key=lambda member: (member.get("sortOrder") or 0, member.get("memberKey") or ""))
            if normalized == members:
                print(f"  = 花名册 {code} 已一致")
                continue
        if dry:
            print(f"  + 花名册 {code} ({len(roster)} 人) (dry-run)")
            continue
        status, payload = client.call(
            "PUT", f"/console/gateway/admin/skills/{item['id']}/members", {"members": members})
        print(f"  {'+' if status == 200 else '!'} 花名册 {code}: {status}")
        if status != 200:
            raise RuntimeError(f"花名册恢复失败 {code}: {status} {payload}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default=DEFAULT_BASE)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--env-file", default=str(DEFAULT_ENV_FILE),
                        help="从中读取 LOCAL_ADMIN_EMAIL / LOCAL_ADMIN_PASSWORD")
    parser.add_argument("--password", default=None,
                        help="直接指定管理员密码，优先于 --env-file")
    parser.add_argument(
        "--repair-curated-media",
        action="store_true",
        help="显式按快照哈希修复六个 curated Expert 的不一致图片；一致图片保持不写入",
    )
    args = parser.parse_args()

    snapshot = json.loads((SNAPSHOT / "catalog.json").read_text(encoding="utf-8"))
    admin = load_admin(Path(args.env_file))
    if args.password:
        admin["password"] = args.password
    client = Client(args.base, admin)
    client.login()

    print(f"快照: {SNAPSHOT}")
    print(f"目标: {args.base}")
    print(f"管理员: {admin['identifier']}\n")

    try:
        print("[1/4] 分类词表与封面")
        restore_categories(client, snapshot, args.dry_run)
        print("\n[2/4] 技能 / 专家 + 包体 + 图片")
        restore_skills(
            client,
            snapshot,
            args.dry_run,
            repair_curated_media=args.repair_curated_media,
        )
        print("\n[3/4] 专家团花名册")
        restore_members(client, snapshot, args.dry_run)
    except RuntimeError as error:
        print(f"\n! 恢复中止: {error}", file=sys.stderr)
        return 1
    print("\n[4/4] 完成")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

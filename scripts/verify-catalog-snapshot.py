"""Read-only acceptance check for the six curated YCPS 1.1 Expert rows.

This command deliberately performs only GET requests after the normal platform login.  It never
creates a download session, publishes a version, repairs a profile, or uploads an object.  The
admin package endpoint is used for byte verification because the public download-session endpoint
is a POST and would mutate download state.

Usage::

    python scripts/verify-catalog-snapshot.py --base http://127.0.0.1:18081
    python scripts/verify-catalog-snapshot.py --skip-package-bytes

Exit status is 0 only when every curated Expert matches the fixture snapshot, including bilingual
semantic preview blocks and the corresponding published YCPS metadata.  A failing check prints a
compact, credential-free report and exits 1; transport/configuration failures exit 2.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import sys
import urllib.error
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping


SYSTEM_ROOT = Path(__file__).resolve().parent.parent
SNAPSHOT_ROOT = SYSTEM_ROOT / "fixtures" / "catalog-snapshot"
CATALOG_PATH = SNAPSHOT_ROOT / "catalog.json"
DEFAULT_BASE = "http://127.0.0.1:18081"
DEFAULT_ENV_FILE = SYSTEM_ROOT / "deploy" / "env" / ".env"
FALLBACK_ADMIN = {"identifier": "admin@local.test", "password": "LocalAdmin!2026"}
DEVICE_ID = "550e8400e29b41d4a716446655440000"
DEFAULT_CLIENT_VERSION = "2026.6.30"
EXPECTED_YCPS_VERSION = "1.1.0"
EXPECTED_PACKAGE_KIND = "EXPERT"
EXPECTED_VERSION = "1.0.0"
TABLE_EXPERT_CODES = frozenset({
    "ai-office-assistant",
    "content-summarizer",
    "sales-analysis-expert",
})
NO_TABLE_EXPERT_CODES = frozenset({
    "content-writing-expert",
    "document-review-expert",
    "social-media-operations-expert",
})
ASSET_ROLES = {
    "icon": ("icon_file", "icon_content_type"),
    "illustration": ("illustration_file", "illustration_content_type"),
    "exampleImage": ("example_image_file", "example_image_content_type"),
}


class ConfigurationError(RuntimeError):
    """The verifier cannot start without a valid local fixture/configuration."""


class TransportError(RuntimeError):
    """The read-only cloud endpoint could not be reached or decoded."""


@dataclass(frozen=True)
class HttpResult:
    status: int
    headers: Mapping[str, str]
    body: bytes


class ReadOnlyClient:
    """Small HTTP client whose public methods are limited to login and GET."""

    def __init__(self, base: str, admin: Mapping[str, str]) -> None:
        self.base = base.rstrip("/")
        self.admin = dict(admin)
        self.token = ""

    def login(self) -> None:
        body = json.dumps(self.admin, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            f"{self.base}/console/platform/auth/login",
            data=body,
            headers={"Content-Type": "application/json", "X-Device-Id": DEVICE_ID},
            method="POST",
        )
        result = self._request(request)
        try:
            payload = json.loads(result.body.decode("utf-8"))
            token = payload["accessToken"]
        except (UnicodeDecodeError, json.JSONDecodeError, KeyError, TypeError) as error:
            raise TransportError(f"登录响应异常: HTTP {result.status}") from error
        if not isinstance(token, str) or not token:
            raise TransportError(f"登录响应缺少 accessToken: HTTP {result.status}")
        self.token = token

    def get_json(self, path: str, *, client_version: str | None = None) -> Any:
        result = self.get(path, client_version=client_version)
        try:
            return json.loads(result.body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise TransportError(f"GET {path} 返回非 JSON: HTTP {result.status}") from error

    def get(self, path: str, *, client_version: str | None = None) -> HttpResult:
        headers = {"Authorization": f"Bearer {self.token}"}
        if client_version:
            headers["X-Client-Version"] = client_version
        request = urllib.request.Request(f"{self.base}{path}", headers=headers, method="GET")
        return self._request(request, path=path)

    def _request(self, request: urllib.request.Request, *, path: str = "login") -> HttpResult:
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return HttpResult(
                    response.status,
                    {key.lower(): value for key, value in response.headers.items()},
                    response.read(),
                )
        except urllib.error.HTTPError as error:
            # Do not include the response body in the exception: a proxy/error page must never
            # accidentally expose a token, cookie, or local configuration value in the report.
            raise TransportError(f"GET {path} 返回 HTTP {error.code}") from error
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise TransportError(f"请求 {path} 失败: {error.__class__.__name__}") from error


def load_admin(env_file: Path) -> dict[str, str]:
    """Read local credentials without ever printing their values."""
    values: dict[str, str] = {}
    if env_file.is_file():
        for raw_line in env_file.read_text(encoding="utf-8", errors="replace").splitlines():
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip().strip('"').strip("'")
    identifier = values.get("LOCAL_ADMIN_EMAIL") or FALLBACK_ADMIN["identifier"]
    password = values.get("LOCAL_ADMIN_PASSWORD") or FALLBACK_ADMIN["password"]
    return {"identifier": identifier, "password": password}


def load_catalog(path: Path = CATALOG_PATH) -> dict[str, Any]:
    try:
        catalog = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ConfigurationError(f"无法读取 catalog 快照: {path}") from error
    if not isinstance(catalog, dict) or not isinstance(catalog.get("skills"), list):
        raise ConfigurationError("catalog 快照缺少 skills 数组")
    return catalog


def curated_rows(catalog: Mapping[str, Any]) -> dict[str, dict[str, Any]]:
    rows: dict[str, dict[str, Any]] = {}
    for row in catalog["skills"]:
        if not isinstance(row, dict) or row.get("kind") != EXPECTED_PACKAGE_KIND:
            continue
        code = row.get("code")
        if not isinstance(code, str) or not code:
            raise ConfigurationError("Expert 快照行缺少 code")
        if code not in TABLE_EXPERT_CODES | NO_TABLE_EXPERT_CODES:
            continue
        if code in rows:
            raise ConfigurationError(f"Expert 快照 code 重复: {code}")
        rows[code] = row
    if len(rows) != 6:
        raise ConfigurationError(f"快照应包含 6 个 curated Expert，实际 {len(rows)} 个")
    return rows


def expected_presentation(row: Mapping[str, Any]) -> dict[str, Any]:
    value = row.get("expert_presentation")
    if not isinstance(value, dict):
        raise ConfigurationError(f"快照 {row.get('code')} 缺少 expert_presentation")
    return value


def expected_asset_digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def expected_zip_names(code: str) -> list[str]:
    return [
        "com.yuzhihuihe.ycps/manifest.json",
        "plugin.json",
        f"skills/{code}/SKILL.md",
        f"skills/{code}/assets/example-image.png",
        f"skills/{code}/assets/icon.png",
        f"skills/{code}/assets/illustration.webp",
    ]


def compare_expected(actual: Any, expected: Any, path: str, failures: list[str]) -> None:
    if actual != expected:
        failures.append(f"{path}: 与快照不一致")


def _catalog_preview(row: Mapping[str, Any], language: str) -> Mapping[str, Any]:
    """Return one bilingual preview directly from the catalog fixture row."""
    key = "preview_zh" if language == "zh" else "preview_en"
    value = row.get(key) if key in row else None
    if key not in row:
        # Keep this fallback for callers constructing a compact row from the API-shaped
        # presentation object.  The exported catalog itself always carries preview_zh/en.
        presentation = row.get("expert_presentation")
        presentation_key = "preview" if language == "zh" else "previewEn"
        value = presentation.get(presentation_key) if isinstance(presentation, Mapping) else None
    if not isinstance(value, Mapping):
        raise ConfigurationError(f"快照 {row.get('code')} 缺少 preview_{language}")
    return value


def _compact_json(value: Any) -> str:
    """Serialize semantic metadata exactly as the YCPS compiler does."""
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def validate_semantic_preview_fields(
    profile: Any,
    expected: Mapping[str, Any],
    prefix: str,
    failures: list[str],
    *,
    catalog_row: Mapping[str, Any] | None = None,
) -> None:
    """Check bilingual intro/table values and reject tables on non-table experts.

    The regular profile comparison remains responsible for the complete presentation object.  This
    focused check gives the online acceptance report a field-level failure and, importantly,
    treats an omitted or explicit-null optional field as equivalent to the catalog's null value
    while still rejecting a newly introduced semantic table.  ``catalog_row`` makes the source
    explicit when the API-shaped presentation copy and the exported row are both available.
    """
    if not isinstance(profile, Mapping):
        failures.append(f"{prefix}: presentation 缺失")
        return
    for actual_key, expected_key in (("preview", "preview"), ("previewEn", "previewEn")):
        actual_preview = profile.get(actual_key)
        language = "zh" if expected_key == "preview" else "en"
        expected_preview = (
            _catalog_preview(catalog_row, language)
            if catalog_row is not None
            else expected.get(expected_key)
        )
        if not isinstance(expected_preview, Mapping):
            failures.append(f"{prefix}.{actual_key}: 快照 preview 缺失")
            continue
        if not isinstance(actual_preview, Mapping):
            failures.append(f"{prefix}.{actual_key}: preview 缺失")
            continue
        for field in ("intro", "table"):
            expected_value = expected_preview.get(field)
            actual_value = actual_preview.get(field)
            if expected_value is None:
                if actual_value is not None:
                    failures.append(f"{prefix}.{actual_key}.{field}: 快照中不应存在语义内容")
            else:
                compare_expected(
                    without_null_object_fields(actual_value),
                    without_null_object_fields(expected_value),
                    f"{prefix}.{actual_key}.{field}",
                    failures,
                )


def expected_semantic_preview_metadata(row: Mapping[str, Any]) -> dict[str, str]:
    """Build the exact bilingual intro/table metadata expected from a catalog row."""
    metadata: dict[str, str] = {}
    for language in ("zh", "en"):
        preview = _catalog_preview(row, language)
        for field in ("intro", "table"):
            value = preview.get(field)
            if value is not None:
                metadata[f"expert.preview.{field}.v1.{language}"] = _compact_json(value)
    return metadata


def validate_catalog_semantic_preview_set(
    rows: Mapping[str, Mapping[str, Any]],
) -> None:
    """Guard the fixture contract: exactly three bilingual semantic-table experts."""
    table_codes: set[str] = set()
    for code, row in rows.items():
        zh_table = _catalog_preview(row, "zh").get("table")
        en_table = _catalog_preview(row, "en").get("table")
        if (zh_table is None) != (en_table is None):
            raise ConfigurationError(f"快照 {code} 的中英文 table 不对称")
        if zh_table is not None:
            table_codes.add(code)
    if table_codes != TABLE_EXPERT_CODES:
        raise ConfigurationError(
            "快照 table Expert 集合不正确: " + ", ".join(sorted(table_codes))
        )
    no_table_codes = set(rows) - table_codes
    if no_table_codes != NO_TABLE_EXPERT_CODES:
        raise ConfigurationError(
            "快照无 table Expert 集合不正确: " + ", ".join(sorted(no_table_codes))
        )


def validate_package_preview_metadata(
    code: str,
    metadata: Mapping[str, str],
    row: Mapping[str, Any],
    failures: list[str],
) -> None:
    """Compare YCPS semantic preview metadata with the catalog, including absence checks."""
    expected = expected_semantic_preview_metadata(row)
    semantic_prefixes = (
        "expert.preview.intro.v1.",
        "expert.preview.table.v1.",
    )
    for key, expected_value in expected.items():
        actual_value = metadata.get(key)
        path = f"{code}.package.SKILL.md.metadata.{key}"
        if actual_value is None:
            failures.append(f"{path}: 缺失")
            continue
        try:
            parsed = json.loads(actual_value)
        except (TypeError, json.JSONDecodeError):
            failures.append(f"{path}: 不是合法 JSON")
            continue
        if parsed != json.loads(expected_value):
            failures.append(f"{path}: 与 catalog 不一致")
        elif actual_value != expected_value:
            # The package compiler emits compact, sorted JSON.  Keep this strict so a published
            # package cannot silently drift from the deterministic catalog serialization.
            failures.append(f"{path}: 与 catalog 不一致")

    for key in metadata:
        if key.startswith(semantic_prefixes) and key not in expected:
            failures.append(
                f"{code}.package.SKILL.md.metadata.{key}: catalog 中不应存在"
            )


def without_null_object_fields(value: Any) -> Any:
    """Treat absent and explicit-null optional preview members as the same wire value."""
    if isinstance(value, dict):
        return {
            key: without_null_object_fields(item)
            for key, item in value.items()
            if item is not None
        }
    if isinstance(value, list):
        return [without_null_object_fields(item) for item in value]
    return value


def validate_top_level_fields(
    actual: Mapping[str, Any] | None,
    row: Mapping[str, Any],
    fields: Mapping[str, str],
    prefix: str,
    failures: list[str],
) -> None:
    """Compare the user-visible skill shell as well as the presentation payload."""
    if not isinstance(actual, Mapping):
        failures.append(f"{prefix}: 响应不是对象")
        return
    for actual_key, snapshot_key in fields.items():
        compare_expected(actual.get(actual_key), row.get(snapshot_key), f"{prefix}.{actual_key}", failures)


def validate_profile(
    profile: Any,
    expected: Mapping[str, Any],
    prefix: str,
    failures: list[str],
) -> None:
    if not isinstance(profile, dict):
        failures.append(f"{prefix}: presentation 缺失")
        return
    for key, value in expected.items():
        actual = profile.get(key)
        if key in {"preview", "previewEn"}:
            actual = without_null_object_fields(actual)
            value = without_null_object_fields(value)
        compare_expected(actual, value, f"{prefix}.{key}", failures)
    # A presentation response may add URLs/metadata, but it must not silently omit any of the
    # three portable asset roles or turn structured arrays into a scalar value.
    for key in ("taskTypes", "taskTypesEn", "examplePrompts", "examplePromptsEn"):
        if not isinstance(profile.get(key), list):
            failures.append(f"{prefix}.{key}: 不是数组")
    preview = profile.get("preview")
    if not isinstance(preview, dict):
        failures.append(f"{prefix}.preview: 缺失")
    else:
        for key in ("heading", "request", "outputTitle", "outputParagraphs"):
            if key not in preview:
                failures.append(f"{prefix}.preview.{key}: 缺失")
        if not isinstance(preview.get("outputParagraphs"), list):
            failures.append(f"{prefix}.preview.outputParagraphs: 不是数组")
    assets = profile.get("assets")
    if not isinstance(assets, dict):
        failures.append(f"{prefix}.assets: 缺失")
    else:
        for role in ASSET_ROLES:
            if not isinstance(assets.get(role), str) or not assets[role]:
                failures.append(f"{prefix}.assets.{role}: 缺失")


def _skill_sections(content: bytes) -> tuple[str, bytes]:
    """Return frontmatter text and the byte-exact runtime body.

    The gateway deliberately accepts an optional YAML document marker inside the Markdown
    delimiters.  Use the same delimiter search as the production Inspector so old generated
    packages with that harmless marker can still be compared semantically.
    """
    document = content.decode("utf-8")
    if not document.startswith(("---\n", "---\r\n")):
        raise ValueError("frontmatter missing")
    first_break = document.find("\n")
    close = document.find("\n---", first_break + 1)
    if close < 0:
        raise ValueError("frontmatter unterminated")
    body_start = close + 4
    if body_start < len(document) and document[body_start] == "\r":
        body_start += 1
    if body_start < len(document) and document[body_start] == "\n":
        body_start += 1
    return document[first_break + 1:close + 1], document[body_start:].encode("utf-8")


def _metadata_values(frontmatter: str) -> dict[str, str]:
    """Parse the generated metadata map without adding a PyYAML runtime dependency.

    Both fixture and Java generator emit scalar string values. Jackson may wrap a quoted scalar
    with YAML's escaped-newline form; normalize only that form, then let the JSON string decoder
    handle Unicode and escapes exactly.
    """
    lines = frontmatter.splitlines()
    try:
        start = next(index for index, line in enumerate(lines) if line == "metadata:") + 1
    except StopIteration as error:
        raise ValueError("metadata missing") from error
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
            raise ValueError("metadata continuation without key")
    values: dict[str, str] = {}
    for key, parts in raw_values.items():
        scalar = "\n".join(parts).strip()
        scalar = re.sub(r"\\\r?\n\s*\\ ", " ", scalar)
        scalar = re.sub(r"\\\r?\n\s*", "", scalar)
        try:
            decoded = json.loads(scalar)
        except json.JSONDecodeError as error:
            raise ValueError(f"metadata scalar invalid: {key}") from error
        if not isinstance(decoded, str):
            raise ValueError(f"metadata scalar is not text: {key}")
        values[key] = decoded
    return values


def validate_zip(
    code: str,
    body: bytes,
    failures: list[str],
    fixture_body: bytes | None = None,
    *,
    catalog_row: Mapping[str, Any] | None = None,
) -> None:
    try:
        with zipfile.ZipFile(io.BytesIO(body)) as archive:
            if archive.testzip() is not None:
                failures.append(f"{code}.package: ZIP CRC 校验失败")
            infos = archive.infolist()
            names = [info.filename for info in infos]
            expected = expected_zip_names(code)
            if names != expected:
                failures.append(f"{code}.package.entries: 不是确定性的 6 文件 Expert 包")
                return
            if any(info.comment or info.extra for info in infos):
                failures.append(f"{code}.package: 含 ZIP comment/extra 字段")
            if any(info.date_time != (1980, 1, 2, 0, 0, 0) for info in infos):
                failures.append(f"{code}.package: ZIP 时间戳不确定")
            if any(info.compress_type != zipfile.ZIP_DEFLATED for info in infos):
                failures.append(f"{code}.package: ZIP 压缩方式不一致")
            skill_path = f"skills/{code}/SKILL.md"
            actual_frontmatter, actual_runtime = _skill_sections(archive.read(skill_path))
            actual_metadata = _metadata_values(actual_frontmatter)
            if catalog_row is not None:
                validate_package_preview_metadata(
                    code,
                    actual_metadata,
                    catalog_row,
                    failures,
                )
            if fixture_body is None:
                return
            with zipfile.ZipFile(io.BytesIO(fixture_body)) as fixture:
                for manifest in ("plugin.json", "com.yuzhihuihe.ycps/manifest.json"):
                    if json.loads(archive.read(manifest)) != json.loads(fixture.read(manifest)):
                        failures.append(f"{code}.package.{manifest}: 语义与快照不一致")
                fixture_frontmatter, fixture_runtime = _skill_sections(fixture.read(skill_path))
                if actual_metadata != _metadata_values(fixture_frontmatter):
                    failures.append(f"{code}.package.SKILL.md.metadata: 与快照不一致")
                if actual_runtime != fixture_runtime:
                    failures.append(f"{code}.package.SKILL.md.body: 与快照不一致")
                for name in expected:
                    if "/assets/" in name and archive.read(name) != fixture.read(name):
                        failures.append(f"{code}.package.{name}: 资源字节与快照不一致")
    except (OSError, UnicodeDecodeError, ValueError, KeyError, zipfile.BadZipFile) as error:
        failures.append(f"{code}.package: 不是合法 ZIP ({error.__class__.__name__})")


def verify(catalog: Mapping[str, Any], client: ReadOnlyClient, *, package_bytes: bool = True) -> list[str]:
    """Run all read-only checks and return credential-free failure descriptions."""
    rows = curated_rows(catalog)
    validate_catalog_semantic_preview_set(rows)
    failures: list[str] = []
    client.login()

    admin_list = client.get_json("/console/gateway/admin/skills?page=0&size=100")
    admin_items = admin_list.get("items") if isinstance(admin_list, dict) else None
    if not isinstance(admin_items, list):
        raise TransportError("管理端技能列表响应缺少 items")
    admin_by_code = {item.get("code"): item for item in admin_items if isinstance(item, dict)}

    public_list = client.get_json("/v1/skills?limit=100", client_version=DEFAULT_CLIENT_VERSION)
    public_items = public_list.get("data") if isinstance(public_list, dict) else None
    if not isinstance(public_items, list):
        raise TransportError("公共技能列表响应缺少 data")
    public_by_code = {item.get("name"): item for item in public_items if isinstance(item, dict)}
    public_forbidden = {"expertPresentation", "preview", "usageGuide", "examplePrompts"}
    for item in public_items:
        if not isinstance(item, dict):
            failures.append("public.list: 存在非对象项")
            continue
        leaked = sorted(public_forbidden.intersection(item))
        if leaked:
            failures.append(f"public.list.{item.get('name')}: 列表泄露大段展示字段 {leaked}")

    for code, row in rows.items():
        expected = expected_presentation(row)
        admin = admin_by_code.get(code)
        if not isinstance(admin, dict):
            failures.append(f"{code}: 管理端列表缺少技能")
            continue
        if admin.get("kind") != EXPECTED_PACKAGE_KIND:
            failures.append(f"{code}.kind: 不是 EXPERT")
        compare_expected(admin.get("tags"), ["ycps"], f"{code}.tags", failures)
        skill_id = admin.get("id")
        if not isinstance(skill_id, str) or not skill_id:
            failures.append(f"{code}.id: 缺失")
            continue

        detail = client.get_json(f"/console/gateway/admin/skills/{skill_id}")
        validate_top_level_fields(
            detail,
            row,
            {
                "name": "name",
                "nameEn": "name_en",
                "summary": "summary",
                "summaryEn": "summary_en",
                "description": "description",
                "descriptionEn": "description_en",
                "category": "category",
                "status": "status",
                "publishedVersion": "published_version",
                "latestVersion": "latest_version",
                "sortWeight": "sort_weight",
                "minClientVersion": "version_min_client_version",
            },
            f"{code}.admin",
            failures,
        )
        profile_response = client.get_json(
            f"/console/gateway/admin/skills/{skill_id}/expert-presentation"
        )
        profile = profile_response.get("presentation") if isinstance(profile_response, dict) else None
        validate_profile(profile, expected, f"{code}.admin.presentation", failures)
        validate_semantic_preview_fields(
            profile,
            expected,
            f"{code}.admin.presentation",
            failures,
            catalog_row=row,
        )

        # Read the three actual object-store-backed responses through the admin GET routes.  The
        # fixture digest is the source of truth; this catches stale pointers even if profile text
        # has already been repaired.
        for role, (file_key, content_type_key) in ASSET_ROLES.items():
            file_name = row.get(file_key)
            if not isinstance(file_name, str):
                failures.append(f"{code}.asset.{role}: 快照缺少文件")
                continue
            asset_result = client.get(
                f"/console/gateway/admin/skills/{skill_id}/{role.replace('exampleImage', 'example-image')}"
            )
            digest = hashlib.sha256(asset_result.body).hexdigest()
            expected_digest = expected_asset_digest(SNAPSHOT_ROOT / file_name)
            if digest != expected_digest:
                failures.append(f"{code}.asset.{role}: 字节哈希不匹配")
            expected_type = row.get(content_type_key)
            actual_type = asset_result.headers.get("content-type", "").split(";", 1)[0]
            if isinstance(expected_type, str) and actual_type != expected_type:
                failures.append(f"{code}.asset.{role}: MIME {actual_type!r} != {expected_type!r}")

        versions = detail.get("versions") if isinstance(detail, dict) else None
        if not isinstance(versions, list):
            failures.append(f"{code}.versions: 缺失")
        else:
            target_revision = row.get("revision")
            matches = [
                value
                for value in versions
                if isinstance(value, dict)
                and value.get("version") == EXPECTED_VERSION
                and value.get("revision") == target_revision
            ]
            if len(matches) != 1:
                failures.append(f"{code}.revision: 未找到唯一目标 revision {target_revision}")
            else:
                version = matches[0]
                for key, value in {
                    "status": "PUBLISHED",
                    "sealed": True,
                    "packageFormat": "YCPS",
                    "ycpsVersion": EXPECTED_YCPS_VERSION,
                    "packageKind": EXPECTED_PACKAGE_KIND,
                    "scanStatus": "PASSED",
                    "fileCount": 6,
                }.items():
                    compare_expected(version.get(key), value, f"{code}.revision.{key}", failures)
                package_file = row.get("package_file")
                fixture_package = SNAPSHOT_ROOT / package_file if isinstance(package_file, str) else None
                if fixture_package is None or not fixture_package.is_file():
                    failures.append(f"{code}.package: 快照包缺失")
                elif package_bytes:
                    package_result = client.get(
                        f"/console/gateway/admin/skills/{skill_id}/versions/{version['id']}/package"
                    )
                    digest = hashlib.sha256(package_result.body).hexdigest()
                    expected_digest = hashlib.sha256(fixture_package.read_bytes()).hexdigest()
                    if digest != expected_digest:
                        failures.append(f"{code}.package: 下载包 SHA-256 不匹配")
                    if package_result.headers.get("x-skill-sha256") not in (None, digest):
                        failures.append(f"{code}.package: X-Skill-Sha256 与内容不一致")
                    if version.get("sha256") != digest:
                        failures.append(f"{code}.package: 版本 SHA-256 与内容不一致")
                    if version.get("sizeBytes") != len(package_result.body):
                        failures.append(f"{code}.package: 版本大小与内容不一致")
                    validate_zip(
                        code,
                        package_result.body,
                        failures,
                        fixture_package.read_bytes(),
                        catalog_row=row,
                    )

        public = public_by_code.get(code)
        if not isinstance(public, dict):
            failures.append(f"{code}: 公共列表缺少技能")
        else:
            validate_top_level_fields(
                public,
                row,
                {
                    "title": "name",
                    "titleEn": "name_en",
                    "summary": "summary",
                    "summaryEn": "summary_en",
                    "description": "description",
                    "descriptionEn": "description_en",
                    "category": "category",
                    "status": "status",
                    "publishedVersion": "published_version",
                    "latestVersion": "latest_version",
                    "minClientVersion": "version_min_client_version",
                    "revision": "revision",
                    "sha256": "sha256",
                },
                f"{code}.public.list",
                failures,
            )
            compare_expected(public.get("taskTypes"), expected.get("taskTypes"), f"{code}.public.taskTypes", failures)
            compare_expected(public.get("taskTypesEn"), expected.get("taskTypesEn"), f"{code}.public.taskTypesEn", failures)

        public_detail = client.get_json(f"/v1/skills/{code}", client_version=DEFAULT_CLIENT_VERSION)
        if not isinstance(public_detail, dict):
            failures.append(f"{code}.public.detail: 响应不是对象")
        else:
            validate_top_level_fields(
                public_detail,
                row,
                {
                    "title": "name",
                    "titleEn": "name_en",
                    "summary": "summary",
                    "summaryEn": "summary_en",
                    "description": "description",
                    "descriptionEn": "description_en",
                    "category": "category",
                    "status": "status",
                    "publishedVersion": "published_version",
                    "latestVersion": "latest_version",
                    "minClientVersion": "version_min_client_version",
                    "revision": "revision",
                    "sha256": "sha256",
                },
                f"{code}.public.detail",
                failures,
            )
            validate_profile(
                public_detail.get("expertPresentation"),
                expected,
                f"{code}.public.detail.expertPresentation",
                failures,
            )
            validate_semantic_preview_fields(
                public_detail.get("expertPresentation"),
                expected,
                f"{code}.public.detail.expertPresentation",
                failures,
                catalog_row=row,
            )

    return failures


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=DEFAULT_BASE, help="gateway base URL")
    parser.add_argument("--env-file", type=Path, default=DEFAULT_ENV_FILE)
    parser.add_argument(
        "--skip-package-bytes",
        action="store_true",
        help="只验版本对象元数据，不下载包字节（仍保持 GET-only）",
    )
    parser.add_argument("--json", action="store_true", dest="json_output")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        catalog = load_catalog()
        client = ReadOnlyClient(args.base, load_admin(args.env_file))
        failures = verify(catalog, client, package_bytes=not args.skip_package_bytes)
    except (ConfigurationError, TransportError) as error:
        if args.json_output:
            print(json.dumps({"ok": False, "error": str(error)}, ensure_ascii=False))
        else:
            print(f"验收无法完成: {error}", file=sys.stderr)
        return 2
    if args.json_output:
        print(json.dumps({"ok": not failures, "failures": failures}, ensure_ascii=False))
    elif failures:
        print(f"六个 curated Expert 只读验收失败（{len(failures)} 项）：")
        for failure in failures:
            print(f"- {failure}")
    else:
        print("六个 curated Expert 只读验收通过：profile、三图、公共列表/Detail、目标 revision 与包哈希均一致。")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())

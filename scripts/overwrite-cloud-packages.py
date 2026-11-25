"""把 system-main/skills 的包原地覆盖到云端（不升版本号）。

与 promote-and-upload.py 的区别：
  - 不修改 plugin.json 的 version，保持原版本号
  - 走 A7「替换包」接口 PUT /{id}/versions/{versionId}/package
  - 服务端会增加 revision、重新校验，并在该版本原本在线时自动重新发布

用法：
  python overwrite-cloud-packages.py --dry-run
  python overwrite-cloud-packages.py --only example-skill
  python overwrite-cloud-packages.py
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

SKILLS_ROOT = Path(r"E:\code\yuzhi\system-main\skills")
DIST = Path(r"E:\code\yuzhi\dist-ycps-experts")
BASE = "http://127.0.0.1:18081"
ADMIN = {"identifier": "admin@local.test", "password": "LocalAdmin!2026"}
DEVICE_ID = "550e8400e29b41d4a716446655440000"


def login() -> str:
    request = urllib.request.Request(
        f"{BASE}/console/platform/auth/login",
        data=json.dumps(ADMIN).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-Device-Id": DEVICE_ID},
        method="POST",
    )
    with urllib.request.urlopen(request) as response:
        return json.loads(response.read().decode("utf-8"))["accessToken"]


def api(method: str, path: str, token: str, data=None, content_type=None):
    headers = {"Authorization": f"Bearer {token}"}
    if content_type:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(f"{BASE}{path}", data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request) as response:
            return response.status, json.loads(response.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode("utf-8") or "{}")


def list_skills(token: str) -> dict[str, dict]:
    status, payload = api("GET", "/console/gateway/admin/skills?page=0&size=100", token)
    if status != 200:
        raise SystemExit(f"读取技能列表失败: {status} {payload}")
    return {item["code"]: item for item in payload["items"]}


def detail(token: str, skill_id: str) -> dict:
    status, payload = api("GET", f"/console/gateway/admin/skills/{skill_id}", token)
    if status != 200:
        raise SystemExit(f"读取技能详情失败: {status} {payload}")
    return payload


def build(code: str) -> Path:
    package_root = SKILLS_ROOT / code
    plugin = json.loads((package_root / "plugin.json").read_text(encoding="utf-8"))
    target = DIST / f"{code}-{plugin['version']}.ycps.zip"
    files = sorted(p for p in package_root.rglob("*") if p.is_file())
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            archive.write(path, path.relative_to(package_root).as_posix())
    return target


def multipart(fields: dict[str, str], file_field: str, file_path: Path) -> tuple[bytes, str]:
    boundary = "----ycpsoverwriteboundary"
    parts: list[bytes] = []
    for name, value in fields.items():
        parts.append(
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode("utf-8")
        )
    parts.append(
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"{file_field}\"; "
        f"filename=\"{file_path.name}\"\r\nContent-Type: application/zip\r\n\r\n".encode("utf-8")
    )
    parts.append(file_path.read_bytes())
    parts.append(f"\r\n--{boundary}--\r\n".encode("utf-8"))
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--only", default=None)
    args = parser.parse_args()

    token = login()
    skills = list_skills(token)
    codes = [args.only] if args.only else sorted(skills)

    for code in codes:
        item = skills.get(code)
        if not item:
            print(f"SKIP {code}: 云端不存在")
            continue
        versions = detail(token, item["id"])["versions"]
        if not versions:
            print(f"SKIP {code}: 没有版本")
            continue
        latest = versions[0]
        print(f"\n=== {code} v{latest['version']} rev{latest['revision']} ({latest['status']}) ===")

        if args.dry_run:
            print("  [dry-run] 跳过")
            continue

        archive = build(code)
        body, content_type = multipart({}, "file", archive)
        status, payload = api(
            "PUT",
            f"/console/gateway/admin/skills/{item['id']}/versions/{latest['id']}/package",
            token, body, content_type,
        )
        print(f"  覆盖: HTTP {status}")
        if status != 200:
            print(f"    失败: {payload}")
            continue
        print(f"  新 revision={payload.get('revision')} status={payload.get('status')} online={payload.get('online')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

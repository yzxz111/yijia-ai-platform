"""把 system-main/skills 的包升版本、重打、上传到云端并发布为线上版本。

背景：已发布版本在服务端被「封存」（sealed），不能原地替换，必须发新版本号。
因此本脚本对每个包执行：
  1. 把 plugin.json 的 version 升一个 patch（1.0.0 -> 1.0.1）
  2. 重新打包到 dist-ycps-experts
  3. 通过 A6 上传为新版本
  4. 通过 A8 发布为线上版本

用法：
  python promote-and-upload.py --dry-run          # 只看计划，不动任何东西
  python promote-and-upload.py                    # 实际执行
  python promote-and-upload.py --only ai-office-assistant
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

SKILLS_ROOT = Path(r"E:\code\yuzhi\system-main\skills")
DIST = Path(r"E:\code\yuzhi\dist-ycps-experts")
BASE = "http://127.0.0.1:18081"
ADMIN = {"identifier": "admin@local.test", "password": "LocalAdmin!2026"}
DEVICE_ID = "550e8400e29b41d4a716446655440000"

SKILL_IDS = {
    "ai-office-assistant": "735f4469-5815-4e12-9ff8-96e97bd553ad",
    "content-summarizer": "6b0cf6aa-682b-43f0-adae-c4b4415cd72d",
    "content-writer": "5a40dfc9-3175-414c-ab1e-10db5dba99e0",
    "content-writing-expert": "5f0f105e-44f0-4081-b867-aac0b54284b3",
    "data-analyst": "e3849df1-6594-4f31-91b2-4bcb5d5d6715",
    "document-review-expert": "8276e8ba-263f-4b6c-89fa-98a787c238a1",
    "ecommerce-ops": "605114d6-6f68-4052-af42-c1a922d04800",
    "example-skill": "c9227cd9-c90e-4694-b1ee-6f0558c1c411",
    "sales-analysis-expert": "abeb3260-8442-4c20-bc7e-bbf5a9f8000f",
    "social-media-operations-expert": "41de020c-cd40-4983-907b-375f3841340e",
}


def bump_patch(version: str) -> str:
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", version)
    if not match:
        raise ValueError(f"无法升版本的版本号: {version}")
    major, minor, patch = (int(part) for part in match.groups())
    return f"{major}.{minor}.{patch + 1}"


def login() -> str:
    payload = json.dumps(ADMIN).encode("utf-8")
    request = urllib.request.Request(
        f"{BASE}/console/platform/auth/login",
        data=payload,
        headers={"Content-Type": "application/json", "X-Device-Id": DEVICE_ID},
        method="POST",
    )
    with urllib.request.urlopen(request) as response:
        body = json.loads(response.read().decode("utf-8"))
    return body["accessToken"]


def multipart(fields: dict[str, str], file_field: str, file_path: Path) -> tuple[bytes, str]:
    boundary = "----ycpsuploadboundary"
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


def request_json(method: str, path: str, token: str, data=None, content_type=None):
    headers = {"Authorization": f"Bearer {token}"}
    if content_type:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(f"{BASE}{path}", data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request) as response:
            return response.status, json.loads(response.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode("utf-8") or "{}")


def repackage(code: str) -> tuple[Path, str]:
    package_root = SKILLS_ROOT / code
    plugin_path = package_root / "plugin.json"
    plugin = json.loads(plugin_path.read_text(encoding="utf-8"))
    old_version = plugin["version"]
    new_version = bump_patch(old_version)

    plugin["version"] = new_version
    plugin_path.write_text(
        json.dumps(plugin, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    target = DIST / f"{code}-{new_version}.ycps.zip"
    files = sorted(p for p in package_root.rglob("*") if p.is_file())
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            archive.write(path, path.relative_to(package_root).as_posix())
    return target, new_version


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--only", default=None)
    args = parser.parse_args()

    codes = [args.only] if args.only else sorted(SKILL_IDS)
    token = None if args.dry_run else login()

    for code in codes:
        skill_id = SKILL_IDS[code]
        plugin = json.loads((SKILLS_ROOT / code / "plugin.json").read_text(encoding="utf-8"))
        target_version = bump_patch(plugin["version"])
        print(f"\n=== {code}  {plugin['version']} -> {target_version} ===")

        if args.dry_run:
            print("  [dry-run] 跳过")
            continue

        archive, new_version = repackage(code)
        print(f"  已重打: {archive.name}")

        body, content_type = multipart(
            {"version": new_version}, "file", archive
        )
        status, payload = request_json(
            "POST", f"/console/gateway/admin/skills/{skill_id}/versions",
            token, body, content_type,
        )
        print(f"  上传: HTTP {status}")
        if status != 200:
            print(f"    失败: {payload}")
            continue

        version_id = payload.get("id")
        status, payload = request_json(
            "POST",
            f"/console/gateway/admin/skills/{skill_id}/versions/{version_id}/publish",
            token,
        )
        print(f"  发布: HTTP {status}")
        if status != 200:
            print(f"    失败: {payload}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

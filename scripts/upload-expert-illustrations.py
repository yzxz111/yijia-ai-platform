"""把已批准的云端目录快照插画补到测试环境（走 A19，不写数据库）。

素材来自本仓库 `fixtures/catalog-snapshot/images/skills/*-illustration.png`。桌面端不提供
专家专属插画或回退映射，只鉴权拉取云端目录图片；本脚本仅用于复原既有云端快照。

用法：
  python upload-expert-illustrations.py --dry-run
  python upload-expert-illustrations.py --only ai-office-assistant
  python upload-expert-illustrations.py
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

BASE = "http://127.0.0.1:18081"
ADMIN = {"identifier": "admin@local.test", "password": "LocalAdmin!2026"}
DEVICE_ID = "550e8400e29b41d4a716446655440000"

SYSTEM_ROOT = Path(__file__).resolve().parent.parent
SCENES = SYSTEM_ROOT / "fixtures" / "catalog-snapshot" / "images" / "skills"

# 后端 code -> 素材文件名。
#
# 2026-09-19 首轮：这 4 个专家的 `illustration_object_key` 为 NULL，需要补。
# 同日追加 content-writing-expert —— 它原本有一张插画，但在 16:05:01 被
# 「清空已上传插画」清掉了（审计日志 `cleared: true`），于是也回到缺图状态。
# 用与其余 5 个一致的 PNG 原图规格，避免同一批专家里混着不同尺寸的插画。
MISSING = {
    "ai-office-assistant": "ai-office-assistant-illustration.png",
    "content-summarizer": "content-summarizer-illustration.png",
    "content-writing-expert": "content-writing-expert-illustration.png",
    "sales-analysis-expert": "sales-analysis-expert-illustration.png",
    "social-media-operations-expert": "social-media-operations-expert-illustration.png",
}


def login() -> str:
    request = urllib.request.Request(
        f"{BASE}/console/platform/auth/login",
        data=json.dumps(ADMIN).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-Device-Id": DEVICE_ID},
        method="POST",
    )
    with urllib.request.urlopen(request) as response:
        return json.loads(response.read().decode("utf-8"))["accessToken"]


def list_experts(token: str) -> dict[str, dict]:
    url = f"{BASE}/console/gateway/admin/skills?page=0&size=100&kind=EXPERT"
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(request) as response:
        payload = json.loads(response.read().decode("utf-8"))
    return {item["code"]: item for item in payload["items"] if item.get("kind") == "EXPERT"}


def upload_illustration(token: str, skill_id: str, path: Path) -> tuple[int, dict]:
    """multipart/form-data，字段名固定为 file（与 web-console 的 uploadAdminSkillIllustration 一致）"""
    boundary = f"----yuzhi{uuid.uuid4().hex}"
    body = b"".join([
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="file"; filename="{path.name}"\r\n'.encode(),
        b"Content-Type: image/png\r\n\r\n",
        path.read_bytes(),
        f"\r\n--{boundary}--\r\n".encode(),
    ])
    request = urllib.request.Request(
        f"{BASE}/console/gateway/admin/skills/{skill_id}/illustration",
        data=body,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request) as response:
            return response.status, json.loads(response.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode("utf-8") or "{}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--only", action="append", default=None)
    args = parser.parse_args()

    targets = {
        code: name for code, name in MISSING.items()
        if not args.only or code in args.only
    }
    if not targets:
        print("没有匹配的目标。", file=sys.stderr)
        return 2

    token = login()
    experts = list_experts(token)
    print(f"已登录，读到 {len(experts)} 个专家。")

    failures = 0
    for code, file_name in targets.items():
        source = SCENES / file_name
        if not source.is_file():
            print(f"[SKIP] {code}: 素材不存在 {source}")
            failures += 1
            continue
        expert = experts.get(code)
        if not expert:
            print(f"[SKIP] {code}: 云端没有这个专家")
            failures += 1
            continue
        size_kb = source.stat().st_size / 1024
        if args.dry_run:
            print(f"[DRY] {code}: {file_name} ({size_kb:.0f} KB) -> {expert['id']}")
            continue
        status, payload = upload_illustration(token, expert["id"], source)
        if status == 200:
            print(f"[OK] {code}: {file_name} ({size_kb:.0f} KB) -> {payload.get('illustrationUrl')}")
        else:
            print(f"[FAIL] {code}: HTTP {status} {payload}")
            failures += 1

    print(f"完成，失败 {failures} 个。")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())

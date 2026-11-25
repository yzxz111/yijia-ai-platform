"""Restore the six approved expert example-image fixtures to the cloud catalog.

The PNGs are cloud-catalog snapshot assets, not content generated from desktop
expert definitions. They are uploaded through the authenticated admin API. The API owns object-store writes, the
``gateway.skill`` pointer swap, audit records, and catalog invalidation events;
this script never writes PostgreSQL or object storage directly.

Usage:
  python scripts/upload-expert-example-images.py --dry-run
  python scripts/upload-expert-example-images.py
  python scripts/upload-expert-example-images.py --base https://example.invalid --env-file path/to/admin.env
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import sys
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path

SYSTEM_ROOT = Path(__file__).resolve().parent.parent
IMAGE_ROOT = SYSTEM_ROOT / "fixtures" / "catalog-snapshot" / "images" / "skills"
DEFAULT_BASE = "http://127.0.0.1:18081"
DEFAULT_ENV_FILE = SYSTEM_ROOT / "deploy" / "env" / ".env"
FALLBACK_ADMIN = {"identifier": "admin@local.test", "password": "LocalAdmin!2026"}
DEVICE_ID = "550e8400e29b41d4a716446655440000"
MAX_IMAGE_BYTES = 4 * 1024 * 1024
TARGET_CODES = (
    "ai-office-assistant",
    "content-summarizer",
    "content-writing-expert",
    "document-review-expert",
    "sales-analysis-expert",
    "social-media-operations-expert",
)


def load_admin(env_file: Path) -> dict[str, str]:
    """Read the local admin account without printing its password."""
    values: dict[str, str] = {}
    if env_file.is_file():
        for raw_line in env_file.read_text(encoding="utf-8", errors="replace").splitlines():
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip().strip('"').strip("'")
    identifier = values.get("LOCAL_ADMIN_EMAIL")
    password = values.get("LOCAL_ADMIN_PASSWORD")
    if identifier and password:
        return {"identifier": identifier, "password": password}
    print(f"warning: admin credentials missing in {env_file}; using local fallback", file=sys.stderr)
    return dict(FALLBACK_ADMIN)


class Client:
    def __init__(self, base: str, admin: dict[str, str]) -> None:
        self.base = base.rstrip("/")
        self.admin = admin
        self.token = ""

    def login(self) -> None:
        request = urllib.request.Request(
            f"{self.base}/console/platform/auth/login",
            data=json.dumps(self.admin).encode("utf-8"),
            headers={"Content-Type": "application/json", "X-Device-Id": DEVICE_ID},
            method="POST",
        )
        with urllib.request.urlopen(request) as response:
            self.token = json.loads(response.read().decode("utf-8"))["accessToken"]

    def json(self, method: str, path: str) -> tuple[int, dict]:
        request = urllib.request.Request(
            f"{self.base}{path}",
            headers={"Authorization": f"Bearer {self.token}"},
            method=method,
        )
        try:
            with urllib.request.urlopen(request) as response:
                body = response.read()
                return response.status, json.loads(body or b"{}")
        except urllib.error.HTTPError as error:
            body = error.read()
            try:
                return error.code, json.loads(body or b"{}")
            except json.JSONDecodeError:
                return error.code, {"raw": body.decode("utf-8", "replace")}

    def upload(self, skill_id: str, image: Path) -> tuple[int, dict]:
        boundary = f"----yuzhi-example-{uuid.uuid4().hex}"
        body = b"".join((
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="file"; filename="{image.name}"\r\n'.encode(),
            b"Content-Type: image/png\r\n\r\n",
            image.read_bytes(),
            f"\r\n--{boundary}--\r\n".encode(),
        ))
        request = urllib.request.Request(
            f"{self.base}/console/gateway/admin/skills/{skill_id}/example-image",
            data=body,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Content-Type": f"multipart/form-data; boundary={boundary}",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request) as response:
                return response.status, json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as error:
            body = error.read()
            try:
                return error.code, json.loads(body or b"{}")
            except json.JSONDecodeError:
                return error.code, {"raw": body.decode("utf-8", "replace")}

    def download(self, skill_id: str) -> tuple[int, bytes, str]:
        request = urllib.request.Request(
            f"{self.base}/console/gateway/admin/skills/{skill_id}/example-image",
            headers={"Authorization": f"Bearer {self.token}"},
        )
        try:
            with urllib.request.urlopen(request) as response:
                return response.status, response.read(), response.headers.get("Content-Type", "")
        except urllib.error.HTTPError as error:
            return error.code, error.read(), error.headers.get("Content-Type", "")

    def download_bundle(self, skill_id: str) -> tuple[int, bytes, int]:
        request = urllib.request.Request(
            f"{self.base}/console/gateway/admin/skills/{skill_id}/package/bundle",
            headers={"Authorization": f"Bearer {self.token}"},
        )
        try:
            with urllib.request.urlopen(request) as response:
                assets = int(response.headers.get("X-Skill-Bundle-Assets", "0") or 0)
                return response.status, response.read(), assets
        except urllib.error.HTTPError as error:
            return error.code, error.read(), 0


def list_experts(client: Client) -> dict[str, dict]:
    status, payload = client.json(
        "GET", "/console/gateway/admin/skills?page=0&size=100&kind=EXPERT"
    )
    if status != 200:
        raise RuntimeError(f"cannot list experts: HTTP {status} {payload}")
    return {
        item["code"]: item
        for item in payload.get("items", [])
        if item.get("kind") == "EXPERT"
    }


def validate_image(code: str) -> tuple[Path, bytes, str]:
    image = IMAGE_ROOT / f"{code}-example.png"
    if not image.is_file():
        raise RuntimeError(f"missing image: {image}")
    data = image.read_bytes()
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError(f"not a PNG: {image}")
    if len(data) > MAX_IMAGE_BYTES:
        raise RuntimeError(f"image exceeds 4 MiB: {image} ({len(data)} bytes)")
    return image, data, hashlib.sha256(data).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default=DEFAULT_BASE)
    parser.add_argument("--env-file", default=str(DEFAULT_ENV_FILE))
    parser.add_argument("--password", help="override LOCAL_ADMIN_PASSWORD")
    parser.add_argument("--only", action="append", choices=TARGET_CODES)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument(
        "--replace",
        action="store_true",
        help="replace an existing non-matching example image",
    )
    args = parser.parse_args()

    targets = tuple(args.only or TARGET_CODES)
    prepared = {code: validate_image(code) for code in targets}
    admin = load_admin(Path(args.env_file))
    if args.password:
        admin["password"] = args.password
    client = Client(args.base, admin)
    client.login()
    experts = list_experts(client)
    print(f"target={args.base} admin={admin['identifier']} experts={len(experts)}")

    failures = 0
    for code in targets:
        expert = experts.get(code)
        if not expert:
            print(f"[FAIL] {code}: expert not found")
            failures += 1
            continue
        if expert.get("status") != "PUBLISHED":
            print(f"[FAIL] {code}: expected PUBLISHED, got {expert.get('status')}")
            failures += 1
            continue

        image, expected_bytes, expected_sha = prepared[code]
        existing_url = expert.get("exampleImageUrl")
        if existing_url:
            status, current_bytes, current_type = client.download(expert["id"])
            current_sha = hashlib.sha256(current_bytes).hexdigest() if status == 200 else ""
            if status == 200 and current_sha == expected_sha:
                print(f"[OK] {code}: already current ({len(current_bytes)} bytes, {current_type})")
                continue
            if not args.replace:
                print(
                    f"[FAIL] {code}: existing image differs; rerun with --replace "
                    f"(HTTP {status}, sha256={current_sha or 'unavailable'})"
                )
                failures += 1
                continue

        if args.dry_run:
            print(
                f"[DRY] {code}: {image.name} ({len(expected_bytes)} bytes, "
                f"sha256={expected_sha}) -> {expert['id']}"
            )
            continue

        status, payload = client.upload(expert["id"], image)
        if status != 200 or not payload.get("exampleImageUrl"):
            print(f"[FAIL] {code}: upload HTTP {status} {payload}")
            failures += 1
            continue

        read_status, actual_bytes, content_type = client.download(expert["id"])
        actual_sha = hashlib.sha256(actual_bytes).hexdigest() if read_status == 200 else ""
        if read_status != 200 or actual_sha != expected_sha or not content_type.startswith("image/png"):
            print(
                f"[FAIL] {code}: verification HTTP {read_status}, type={content_type}, "
                f"sha256={actual_sha or 'unavailable'}"
            )
            failures += 1
            continue
        print(
            f"[OK] {code}: {len(actual_bytes)} bytes, sha256={actual_sha}, "
            f"url={payload['exampleImageUrl']}"
        )

    if not args.dry_run and failures == 0:
        catalog_status, catalog = client.json(
            "GET", "/console/gateway/skills?limit=100&kind=EXPERT"
        )
        catalog_by_code = {
            item.get("name"): item for item in catalog.get("data", [])
        } if catalog_status == 200 else {}
        for code in targets:
            expected_path, expected_bytes, expected_sha = prepared[code]
            catalog_item = catalog_by_code.get(code)
            expected_url = f"/v1/skills/{code}/example-image"
            if not catalog_item or catalog_item.get("exampleImageUrl") != expected_url:
                print(
                    f"[FAIL] {code}: client catalog exampleImageUrl is "
                    f"{catalog_item.get('exampleImageUrl') if catalog_item else 'missing'}"
                )
                failures += 1
                continue

            expert = experts[code]
            bundle_status, bundle_bytes, asset_count = client.download_bundle(expert["id"])
            try:
                archive = zipfile.ZipFile(io.BytesIO(bundle_bytes))
                matching = [
                    name for name in archive.namelist()
                    if name.endswith(f"/{expected_path.name}")
                ]
                bundled = archive.read(matching[0]) if len(matching) == 1 else b""
            except (zipfile.BadZipFile, KeyError):
                matching, bundled = [], b""
            bundled_sha = hashlib.sha256(bundled).hexdigest() if bundled else ""
            if (
                bundle_status != 200
                or asset_count != 3
                or len(matching) != 1
                or bundled_sha != expected_sha
                or bundled != expected_bytes
            ):
                print(
                    f"[FAIL] {code}: bundle HTTP {bundle_status}, assets={asset_count}, "
                    f"matches={len(matching)}, sha256={bundled_sha or 'unavailable'}"
                )
                failures += 1
                continue
            print(
                f"[OK] {code}: catalog URL and complete bundle verified "
                f"(3 assets, {matching[0]})"
            )

    print(f"done: {len(targets) - failures} ok, {failures} failed")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())

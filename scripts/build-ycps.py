"""把 skills/<code> 目录打成 YCPS 包，用于验证 i18n 自动回填。

YCPS §5 要求 ZIP 根目录直接包含 plugin.json，不得再套一层目录，因此逐文件写入
相对路径。保持与既有 dist-ycps-experts/*.zip 一致的命名：<code>-<version>.ycps.zip。
"""
from __future__ import annotations

import json
import sys
import zipfile
from pathlib import Path

SKILLS_ROOT = Path(r"E:\code\yuzhi\system-main\skills")


def build(code: str, output: Path) -> Path:
    package_root = SKILLS_ROOT / code
    plugin = json.loads((package_root / "plugin.json").read_text(encoding="utf-8"))
    version = plugin["version"]
    output = output / f"{code}-{version}.ycps.zip"

    files = sorted(p for p in package_root.rglob("*") if p.is_file())
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            relative = path.relative_to(package_root).as_posix()
            archive.write(path, relative)
    return output


def main() -> int:
    if len(sys.argv) < 3:
        print("用法: build-ycps.py <code> <输出目录>")
        return 2
    result = build(sys.argv[1], Path(sys.argv[2]))
    print(result)
    with zipfile.ZipFile(result) as archive:
        for name in archive.namelist():
            print("  ", name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

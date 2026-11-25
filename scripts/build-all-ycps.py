"""批量打包 system-main/skills 下的全部 YCPS 包到 dist-ycps-experts。

YCPS §5：ZIP 根目录必须直接包含 plugin.json，不得再套一层目录。
产物命名：<code>-<version>.ycps.zip（与既有产物一致）。

可选 --clean：先清空输出目录，避免旧版本的残留产物混在一起。
"""
from __future__ import annotations

import json
import shutil
import sys
import zipfile
from pathlib import Path

SKILLS_ROOT = Path(r"E:\code\yuzhi\system-main\skills")
DIST = Path(r"E:\code\yuzhi\dist-ycps-experts")


def build(code: str, output_dir: Path) -> Path:
    package_root = SKILLS_ROOT / code
    plugin = json.loads((package_root / "plugin.json").read_text(encoding="utf-8"))
    target = output_dir / f"{code}-{plugin['version']}.ycps.zip"

    files = sorted(p for p in package_root.rglob("*") if p.is_file())
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            archive.write(path, path.relative_to(package_root).as_posix())
    return target


def main() -> int:
    clean = "--clean" in sys.argv
    if clean and DIST.exists():
        shutil.rmtree(DIST)
    DIST.mkdir(parents=True, exist_ok=True)

    codes = sorted(
        p.name for p in SKILLS_ROOT.iterdir()
        if p.is_dir() and (p / "plugin.json").is_file()
    )
    for code in codes:
        result = build(code, DIST)
        print(f"{code:32s} -> {result.name}")

    zips = sorted(DIST.glob("*.ycps.zip"))
    print(f"\n共生成 {len(zips)} 个产物")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

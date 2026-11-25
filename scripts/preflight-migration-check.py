"""重建前预检：比对本地迁移文件的 Flyway checksum 与库中记录。

Flyway ChecksumCalculator 算法：
  - 按 \r\n|\r|\n 切行（行分隔符不计入内容）
  - 首行剥掉 UTF-8 BOM
  - 每行按 UTF-8 编码依次喂 CRC32（不喂换行符）
  - 取有符号 32 位 int

用法：
  python scripts/preflight-migration-check.py
"""
from __future__ import annotations

import re
import subprocess
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PG_CONTAINER = "yuzhihuihe-api-platform-postgres-1"
DB = "api_model_platform"
PG_PASSWORD = "local-postgres-password"


def flyway_checksum(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    lines = re.split(r"\r\n|\r|\n", text)
    if lines and lines[-1] == "":
        lines.pop()
    crc = 0
    first = True
    for line in lines:
        if first:
            line = line.lstrip("\ufeff")
            first = False
        crc = zlib.crc32(line.encode("utf-8"), crc)
    value = crc & 0xFFFFFFFF
    return value - 0x100000000 if value >= 0x80000000 else value


def query(sql: str) -> list[list[str]]:
    result = subprocess.run(
        ["docker", "exec", "-e", f"PGPASSWORD={PG_PASSWORD}", PG_CONTAINER,
         "psql", "-U", "postgres", "-d", DB, "-t", "-A", "-F", "\t", "-c", sql],
        capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"查询失败: {result.stderr.strip()}")
    return [line.split("\t") for line in result.stdout.strip().splitlines() if line]


def check(schema: str, service: str) -> bool:
    print(f"\n=== {schema} ({service}) ===")
    rows = query(
        f"SELECT version, description, coalesce(checksum::text,'NULL'), success::text "
        f"FROM {schema}.flyway_schema_history ORDER BY installed_rank;")
    recorded = {r[0]: (r[1], r[2], r[3]) for r in rows if len(r) >= 4}

    migration_dir = ROOT / service / "src/main/resources/db/migration"
    local = {}
    for path in sorted(migration_dir.glob("V*.sql")):
        ver = path.name[1:].split("__", 1)[0]
        if ver.isdigit():
            local[ver] = path

    failed = recorded.get("0")  # 占位，忽略
    problems: list[str] = []

    # 1) 已应用但本地文件缺失
    for ver in sorted(recorded, key=lambda v: int(v) if v.isdigit() else 0):
        if ver not in local:
            problems.append(f"  文件缺失: V{ver} ({recorded[ver][0]})")
        elif recorded[ver][2] == "f":
            problems.append(f"  历史失败记录: V{ver}")

    # 2) checksum 不符 / 描述不符
    mismatch = 0
    for ver in sorted(local, key=int):
        if ver not in recorded:
            continue
        desc, db_sum, _ = recorded[ver]
        local_sum = str(flyway_checksum(local[ver]))
        if db_sum != local_sum:
            mismatch += 1
            problems.append(
                f"  checksum 不符 V{ver}: 库={db_sum} 本地={local_sum}\n"
                f"      库描述: {desc}\n"
                f"      本地文件: {local[ver].name}")

    # 3) 待应用的新迁移
    max_applied = max((int(v) for v in recorded if v.isdigit()), default=0)
    pending = [v for v in sorted(local, key=int) if int(v) > max_applied]

    print(f"  已应用最大版本: V{max_applied}")
    print(f"  待应用新迁移: {', '.join('V' + v for v in pending) or '无'}")

    if problems:
        print(f"  ⚠️ 发现 {len(problems)} 个问题:")
        for p in problems:
            print(p)
        return False

    print(f"  ✅ {len(recorded)} 条已应用记录全部匹配，无 checksum 冲突")
    return True


def main() -> int:
    ok = check("platform", "platform-service")
    ok = check("gateway", "gateway-service") and ok
    print()
    if ok:
        print("预检通过：可以安全重建（保留数据卷）")
        return 0
    print("预检失败：直接重建会因 checksum 冲突启动失败，需先处理上述问题")
    return 1


if __name__ == "__main__":
    sys.exit(main())

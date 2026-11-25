"""复现 Flyway 的 SQL 迁移校验和算法，用于校验/修复 *_schema_history。

Flyway 的 ChecksumCalculator 对 SQL 文件的做法：
  - 用 BufferedReader 逐行读（行分隔符 \n / \r\n / \r，且不计入内容）
  - 首行剥掉 UTF-8 BOM
  - 把每行按 UTF-8 编码后依次喂给 CRC32（不喂换行符）
  - 取 (int) crc32.getValue()

先用未改动的迁移验证算法能复现库中校验和，再对被改动的文件输出新值。

用法（路径自动定位到本脚本所在仓库，可用 argv 覆盖）：
    python scripts/flyway-checksum.py [gateway|platform] [迁移目录]

**改过已经应用过的迁移之后**（例如 V102 修掉了"建库时要求渠道已健康"的断言），
已经登记过它的环境必须同步那一行，否则启动时校验和不匹配：

    UPDATE gateway.flyway_schema_history
       SET checksum = <新值> WHERE version = '<版本>';
"""
import re
import sys
import zlib
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MODULES = {
    "gateway": REPO_ROOT / "gateway-service/src/main/resources/db/migration",
    "platform": REPO_ROOT / "platform-service/src/main/resources/db/migration",
}
MIGRATION_DIR = MODULES["gateway"]


def flyway_checksum(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    # 只按 \r\n / \r / \n 切行，与 Java BufferedReader 一致
    lines = re.split(r"\r\n|\r|\n", text)
    if lines and lines[-1] == "":
        lines.pop()  # 末尾换行不产生额外空行
    crc = 0
    first = True
    for line in lines:
        if first:
            line = line.lstrip("\ufeff")
            first = False
        crc = zlib.crc32(line.encode("utf-8"), crc)
    # Java 的 int 是有符号 32 位
    value = crc & 0xFFFFFFFF
    return value - 0x100000000 if value >= 0x80000000 else value


def main() -> None:
    directory = MIGRATION_DIR
    if len(sys.argv) > 1:
        directory = MODULES.get(sys.argv[1], Path(sys.argv[1]))
    if len(sys.argv) > 2:
        directory = Path(sys.argv[2])
    if not directory.is_dir():
        print(f"not a migration directory: {directory}", file=sys.stderr)
        return 2
    for path in sorted(directory.glob("V*.sql")):
        version = path.name[1:].split("__", 1)[0]
        print(f"{version}\t{flyway_checksum(path)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

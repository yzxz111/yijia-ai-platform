"""同步「改过已应用的迁移」之后的 Flyway 校验和 —— 把必然的启动失败变成一条明确指令。

## 为什么需要它

Flyway 默认 `validateOnMigrate=true`：启动时拿本地迁移文件重算校验和，与
`*_flyway_schema_history` 里记录的值比对，不一致就**中止迁移并让应用启动失败**。

而"修一个已应用迁移里的 bug"必然改变它的校验和 —— 这正是
`scripts/flyway-checksum.py` 文件头写明的约定场景：

> 改过已经应用过的迁移之后，已经登记过它的环境必须同步那一行，否则启动时校验和不匹配

问题在于**漏做这一步的代价很大且症状会骗人**：报的是启动期校验和不匹配，
容易被误判成"数据库连不上""镜像坏了""环境变量没配"，
而真正的动作只是几条 UPDATE。本脚本就是把这件事自动化：
**在重启之前**把差异列出来，并给出可执行的修复语句。

## 它做什么

只读比对（默认）：把本地每个迁移文件的校验和与目标库里记录的逐一对照，
列出**内容不一致**与**库里从未记录（新迁移）**两类。

`--apply` 才真正写库，且**只写"两边版本都存在但校验和不同"**的那几行 ——
新迁移交给 Flyway 自己执行并登记，本脚本不越权代劳。

## 用法

    # 只看差异（默认，不改库）
    python scripts/flyway-checksum-sync.py --schema platform \\
        --url jdbc:postgresql://localhost:5432/api_model_platform \\
        --user postgres --password ...

    # 确认后写库
    python scripts/flyway-checksum-sync.py --schema platform --apply --url ...

`psql` 取本机 PATH 上的；没有则退回 `docker run --rm postgres:17-alpine`。
"""
from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import zlib
from pathlib import Path
from urllib.parse import urlparse

# Windows 控制台默认可能是 GBK（本机实测），任何非 GBK 字符（例如 ⚠）都会让
# 打印抛 UnicodeEncodeError —— 一个纯提示性的符号不该让整个脚本崩掉。
# 这里把两个流都改成"不可编码就替换"，而不是让 encode 失败。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(errors="replace")
    except (AttributeError, ValueError):  # pragma: no cover - 老解释器/被重定向
        pass

ROOT = Path(__file__).resolve().parent.parent
PSQL_FALLBACK_IMAGE = "postgres:17-alpine"

MIGRATION_DIRS = {
    "gateway": ROOT / "gateway-service/src/main/resources/db/migration",
    "platform": ROOT / "platform-service/src/main/resources/db/migration",
}


def flyway_checksum(path: Path) -> int:
    """与 `scripts/flyway-checksum.py` 完全相同的算法。

    契约性重复：两处必须一致，否则本脚本会算出一个 Flyway 不认的值。
    改动其一必须同步另一处。（已用真 Flyway 迁移出的库交叉验证过：
    V89 → 23882203、V103 → -150472371，与 Flyway 写进 schema history 的值逐位相同。）
    """
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


class Connection:
    """一个 psql 调用目标。

    三种形态，按优先级：
    1. `--container <名字>`：`docker exec` 进那个容器跑 psql。
       **生产上推荐这个** —— 不依赖宿主机有没有 psql、也不依赖网络可达性，
       而 compose 里的库容器名是确定的（`...-postgres-1`）。
    2. 本机 PATH 上有 `psql`：直接连。
    3. 都没有：退回 `docker run --rm postgres:17-alpine psql`。
    """

    def __init__(self, url: str, user: str, password: str, container: str = "") -> None:
        if url.startswith("jdbc:"):
            url = url[len("jdbc:"):]
        parsed = urlparse(url)
        if parsed.scheme not in ("postgres", "postgresql"):
            raise SystemExit(f"只支持 postgres:// 或 jdbc:postgresql:// 连接串，收到: {url}")
        self.host = parsed.hostname or "localhost"
        self.port = str(parsed.port or 5432)
        self.user = user or parsed.username or "postgres"
        self.password = password or parsed.password or ""
        self.database = (parsed.path or "/").lstrip("/") or "postgres"
        self.container = container
        self.via_docker = not container and shutil.which("psql") is None
        if self.via_docker and shutil.which("docker") is None:
            raise SystemExit("既没有本机 psql，也没有 docker —— 无法查询")

    def _env(self) -> list[str]:
        host = self.host
        if self.via_docker and host in ("localhost", "127.0.0.1"):
            host = "host.docker.internal"
        return [
            "-e", f"PGPASSWORD={self.password}",
            "-e", "PGCLIENTENCODING=UTF8",
            "-e", f"PGHOST={host}",
            "-e", f"PGPORT={self.port}",
            "-e", f"PGUSER={self.user}",
            "-e", f"PGDATABASE={self.database}",
        ]

    def _command(self, extra: list[str]) -> list[str]:
        """构造 psql 调用。

        **必须把连接参数显式传给 psql。** 原先"本机有 psql"那个分支只返回裸 `psql`，
        于是 `--url/--user/--password` 被完全忽略，psql 去连它自己的默认库。
        本脚本会 `UPDATE ... SET checksum`（`--apply`），**连错库就是写错库** ——
        这是最不能容忍的一类错误。现在三个分支统一用显式 `-h/-p/-U/-d`。

        （另一个更早的错：`via_docker` 曾写成"本机有 psql"的语义，实际它是
        "本机没有 psql、要退回 docker"的开关。）
        """
        connect = ["-h", self.host, "-p", self.port, "-U", self.user, "-d", self.database]
        common_env = [
            "-e", f"PGPASSWORD={self.password}",
            "-e", "PGCLIENTENCODING=UTF8",
        ]
        if self.container:
            # 已经在目标库容器里，连它自己即可（localhost 一定可达，
            # 且不依赖 --url 里的 host 能在容器内解析）。
            # 端口固定用容器内的 5432：--url 里若是宿主机映射端口（例如 15433），
            # 那个端口在容器内部并不存在，照抄会连不上。
            inner = ["-h", "localhost", "-p", "5432",
                     "-U", self.user, "-d", self.database]
            return ["docker", "exec", "-i", *common_env, self.container,
                    "psql", "-v", "ON_ERROR_STOP=1", *inner, *extra]
        if self.via_docker:
            host = "host.docker.internal" if self.host in ("localhost", "127.0.0.1") else self.host
            connect = ["-h", host, "-p", self.port, "-U", self.user, "-d", self.database]
            return ["docker", "run", "--rm", "-i", *common_env, PSQL_FALLBACK_IMAGE,
                    "psql", "-v", "ON_ERROR_STOP=1", *connect, *extra]
        return ["psql", "-v", "ON_ERROR_STOP=1", *connect, *extra]

    def rows(self, sql: str) -> list[list[str]]:
        result = subprocess.run(
            self._command(["-t", "-A", "-F", "\t", "-c", sql]),
            capture_output=True, text=True, encoding="utf-8",
        )
        if result.returncode != 0:
            raise SystemExit(f"查询失败:\n{sql}\n{(result.stderr or '').strip()[:1500]}")
        return [line.split("\t") for line in result.stdout.strip().splitlines() if line]

    def execute(self, sql: str) -> None:
        result = subprocess.run(
            self._command(["-v", "ON_ERROR_STOP=1", "-q", "-c", sql]),
            capture_output=True, text=True, encoding="utf-8",
        )
        if result.returncode != 0:
            raise SystemExit(f"执行失败:\n{sql}\n{(result.stderr or '').strip()[:1500]}")


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--schema", required=True, choices=sorted(MIGRATION_DIRS))
    parser.add_argument("--url", required=True)
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--password", default="")
    parser.add_argument("--container", default="",
                        help="docker exec 进这个容器跑 psql（生产推荐，例如 "
                             "<compose 项目名>-postgres-1）")
    parser.add_argument("--apply", action="store_true",
                        help="真正写库；不加就是只读比对")
    args = parser.parse_args()

    directory = MIGRATION_DIRS[args.schema]
    local = {}
    for path in sorted(directory.glob("V*.sql")):
        version = path.name[1:].split("__", 1)[0]
        local[version] = flyway_checksum(path)

    connection = Connection(args.url, args.user, args.password, args.container)
    history = f"{args.schema}.flyway_schema_history"
    try:
        recorded = {
            row[0]: int(row[1])
            for row in connection.rows(
                f"SELECT version, checksum FROM {history} WHERE checksum IS NOT NULL;")
            if len(row) >= 2 and row[1] not in ("", None)
        }
    except SystemExit:
        raise SystemExit(
            f"读不到 {history} —— 目标库可能还没跑过迁移，或 schema 名不对。")

    changed = {v: c for v, c in local.items() if v in recorded and recorded[v] != c}
    added = sorted(set(local) - set(recorded), key=lambda v: int(v))

    print(f"== {args.schema}：本地 {len(local)} 个迁移，库中已记录 {len(recorded)} 个 ==")
    if added:
        print(f"\n新迁移（{len(added)} 个，Flyway 启动时会自己执行并登记，本脚本不处理）：")
        print("  " + ", ".join(f"V{v}" for v in added))

    if not changed:
        print("\n通过：已应用迁移的校验和与本地文件完全一致，可以直接启动。")
        return 0

    print(f"\n[WARN] 校验和不一致（{len(changed)} 个）—— **不同步就会导致应用启动失败**：")
    for version in sorted(changed, key=int):
        print(f"  V{version}: 库中 {recorded[version]}  ->  本地 {changed[version]}")

    print("\n修复语句：")
    print(f"  -- 在 {args.schema} 库执行")
    for version in sorted(changed, key=int):
        print(f"  UPDATE {history} SET checksum = {changed[version]} "
              f"WHERE version = '{version}';")

    if not args.apply:
        print("\n（只读模式。确认无误后加 --apply 写库，或手工执行上面的语句。）")
        return 1

    for version in sorted(changed, key=int):
        connection.execute(
            f"UPDATE {history} SET checksum = {changed[version]} WHERE version = '{version}';")
    print(f"\n已同步 {len(changed)} 行。请再跑一次本脚本（不带 --apply）确认通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())

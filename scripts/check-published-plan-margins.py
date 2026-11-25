"""套餐毛利前置审计：检查已发布套餐是否击穿毛利下限（迁移直写管不到的那一层）。

## 为什么需要这个脚本

平台有**两层**毛利保护，但**都拦不住迁移直写**：

1. **发布时硬拦**（API 路径）：`PackageMarginPolicy` + `V58`，
   低于该维度下限直接 `RAISE EXCEPTION 'published_plan_below_thirty_percent'`；
2. **运行时复核**（定时任务）：`PublishedPlanMarginMonitor` 只**告警**，
   自述"只报告，绝不修改"。

**缺口**：迁移直接 INSERT/UPDATE `platform.package_plan_version`，不经过第 1 层。
`V102__add_video_minimax_to_published_video_plans.sql:59` 就是一个实例：
它把新增模型的新版本发布出去，而 `estimated_cost_amount` /
`estimated_margin_ratio` / `margin_warning` **整列从旧版本复制**
（同一模式见 `V103:156-157`）。结果是新版本带着**陈旧的**毛利快照在售 ——
V103 自己后来不得不重算（`:252-255`）并断言（`:510-523`），
这本身就证明"复制"是错的默认。

## 本脚本做什么

对一个**已由 Flyway 迁移完成**的数据库只读查询，发现问题即非零退出，
可直接当 CI 关卡：

- **A 类（硬失败）**：PUBLISHED 版本存在但毛利快照为空
  （`estimated_margin_ratio` 或 `estimated_cost_amount` 为 NULL）
  → 该版本从未被任何守卫评估过；
- **B 类（硬失败）**：PUBLISHED 版本毛利快照**低于该维度下限**
  （下限取自 `platform.package_dimension_price_policy`）；
- **B2 类（提示）**：该维度没有策略行，按 `PackageMarginPolicy` 的回退常量提示；
- **C 类（硬失败）**：PUBLISHED 版本的 `margin_warning` 与
  "毛利快照低于该维度预警下限"这一事实**不一致**（该字段是复制来的陈旧值）。

**已知局限（必须知道）**：本脚本读的是**库里存的**毛利快照，不是用当前成本基准
重算的结果。因此它**不能**发现"成本基准事后变了导致老版本转亏"——那是
`PublishedPlanMarginMonitor` 的职责（需要网关在线）。本脚本管的是**迁移把没有
快照 / 快照陈旧 / 已低于下限的版本发布出去**这一类，而这一类恰好是发布守卫
管不到的（迁移直写不经过 API）。

## 为什么本脚本**不**自己灌迁移

第一版想用 `psql -f` 按文件名顺序重放迁移，省掉 JVM 依赖。**这条路是错的**，
实测在三个地方翻车，记在这里免得后人重走：

1. `gateway` / `platform` schema 与 `pgcrypto`、`btree_gist` 由
   `deploy/postgres/init.sql` 建，**不在任何迁移文件里**；
2. `PGCLIENTENCODING` 必须显式为 UTF8，否则 psql 按镜像 locale（实测 SQL_ASCII）
   解读含中文注释的迁移文件，报 `invalid byte sequence for encoding "UTF8"`；
3. **最硬的一条**：`V106__backfill_audio_capability_for_existing_video_packages.sql`
   依赖 psql 会话状态（临时表/`\\set`），逐文件 `-f -` 重放会在
   `relation "v106_audio_seconds" does not exist` 处失败。

**迁移链的权威执行者是 Flyway**（生产也是它）。因此本脚本审计一个已经由
Flyway 迁移完成的库，不自己重放。CI 里的推荐接法：

    mvn -pl platform-service test -Dtest=PlatformMigrationTest   # 顺带验证迁移链本身
    python scripts/check-published-plan-margins.py --url <同一个库>

## 用法

    python scripts/check-published-plan-margins.py --url jdbc:postgresql://localhost:5432/api_model_platform
    python scripts/check-published-plan-margins.py --url ... --report-only     # 只盘点，不失败
    python scripts/check-published-plan-margins.py --url ... --user postgres --password ...

`psql` 取本机 PATH 上的；没有则退回到 `docker run --rm postgres:17-alpine psql`。
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from urllib.parse import urlparse

# 与 PackageMarginPolicy 的常量保持一致：该维度在
# platform.package_dimension_price_policy 没有行时的回退下限。
# 见 packagecatalog/PackageMarginPolicy.java:50-63。
FALLBACK_FLOORS = {
    "TOKEN": ("0.30000", "0.35000"),
    "VIDEO_SECOND": ("0.25000", "0.30000"),
    "IMAGE": ("0.25000", "0.30000"),
    "AUDIO_SECOND": ("0.25000", "0.30000"),
}

PSQL_FALLBACK_IMAGE = "postgres:17-alpine"


class Connection:
    """一个 psql 调用目标：本机 psql，或 docker 里的 psql。"""

    def __init__(self, url: str, user: str, password: str) -> None:
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
        self.via_docker = shutil.which("psql") is None
        if self.via_docker and shutil.which("docker") is None:
            raise SystemExit("既没有本机 psql，也没有 docker —— 无法查询")

    def _command(self, extra: list[str]) -> list[str]:
        """构造 psql 调用。

        **必须把连接参数显式传给 psql。** 原先有两个分支：
        `docker run` 分支传了 PGHOST/PGPORT/PGUSER/PGDATABASE 环境变量，
        而"本机有 psql"那个分支只返回裸 `psql`、一个参数都不带 ——
        于是 `--url/--user/--password` 被**完全忽略**，psql 会去连它自己的默认库
        （本机或 libpq 环境变量指向的那个）。在只读审计里这会读错库、
        在 `--apply` 场景里会**写错库**。

        这里改成统一用显式 `-h/-p/-U/-d`，两个分支共用同一套连接参数；
        docker 分支只额外负责"容器怎么连回宿主机"。
        """
        host = self.host
        if self.via_docker and host in ("localhost", "127.0.0.1"):
            # 容器内的 localhost 是容器自己；宿主机要经 host.docker.internal。
            host = "host.docker.internal"
        connect = [
            "-h", host,
            "-p", self.port,
            "-U", self.user,
            "-d", self.database,
        ]
        env = [
            "-e", f"PGPASSWORD={self.password}",
            "-e", "PGCLIENTENCODING=UTF8",
        ]
        if self.via_docker:
            return ["docker", "run", "--rm", "-i", *env, PSQL_FALLBACK_IMAGE,
                    "psql", "-v", "ON_ERROR_STOP=1", *connect, *extra]
        # 本机 psql：PGPASSWORD 走进程环境，其余连接参数用命令行显式指定。
        return ["psql", "-v", "ON_ERROR_STOP=1", *connect, *extra]

    def rows(self, sql: str) -> list[list[str]]:
        result = subprocess.run(
            self._command(["-t", "-A", "-F", "\t", "-c", sql]),
            capture_output=True, text=True, encoding="utf-8",
        )
        if result.returncode != 0:
            raise SystemExit(f"查询失败:\n{sql}\n{(result.stderr or '').strip()[:1500]}")
        return [line.split("\t") for line in result.stdout.strip().splitlines() if line]


def audit(connection: Connection) -> list[tuple[str, str]]:
    findings: list[tuple[str, str]] = []

    # ---- A 类：已发布但毛利快照为空 ----
    for (label,) in connection.rows("""
        SELECT plan.code || ' v' || version.version || ' (' || version.id || ')'
        FROM platform.package_plan_version version
        JOIN platform.package_plan plan ON plan.id = version.package_plan_id
        WHERE version.status = 'PUBLISHED'
          AND (version.estimated_margin_ratio IS NULL
               OR version.estimated_cost_amount IS NULL)
        ORDER BY plan.code;
    """):
        findings.append((
            "A",
            f"{label}: 已发布但毛利快照为空 —— 该版本从未被发布守卫评估过"
            f"（迁移直写的典型后果）",
        ))

    # ---- B 类：已发布且毛利快照低于该维度下限 ----
    for (label,) in connection.rows("""
        SELECT plan.code || ' v' || version.version
               || ' 维度=' || version.usage_dimension
               || ' 毛利=' || version.estimated_margin_ratio
               || ' 下限=' || floor.minimum_margin
        FROM platform.package_plan_version version
        JOIN platform.package_plan plan ON plan.id = version.package_plan_id
        JOIN platform.package_dimension_price_policy floor
          ON floor.usage_dimension = version.usage_dimension
        WHERE version.status = 'PUBLISHED'
          AND version.estimated_margin_ratio IS NOT NULL
          AND version.estimated_margin_ratio < floor.minimum_margin
        ORDER BY version.estimated_margin_ratio;
    """):
        findings.append(("B", f"{label}: 已发布且击穿毛利下限，仍在售"))

    # ---- B2 类：该维度没有策略行，无法判定 ----
    for (dimension,) in connection.rows("""
        SELECT DISTINCT version.usage_dimension
        FROM platform.package_plan_version version
        LEFT JOIN platform.package_dimension_price_policy floor
               ON floor.usage_dimension = version.usage_dimension
        WHERE version.status = 'PUBLISHED'
          AND version.usage_dimension IS NOT NULL
          AND floor.usage_dimension IS NULL
        ORDER BY version.usage_dimension;
    """):
        fallback = FALLBACK_FLOORS.get(dimension)
        hint = (
            f"，代码回退下限为 {fallback[0]}" if fallback
            else "，且代码里也没有该维度的回退下限 —— 这是一个需要登记的缺口"
        )
        findings.append((
            "B2",
            f"维度 {dimension} 没有 platform.package_dimension_price_policy 行{hint}"
            f"；该维度已发布版本无法按策略判定",
        ))

    # ---- C 类：margin_warning 与该维度预警下限不一致 ----
    for (label,) in connection.rows("""
        SELECT plan.code || ' v' || version.version
               || ' 快照=' || version.estimated_margin_ratio
               || ' 预警下限=' || floor.warning_margin
               || ' 库里 margin_warning=' || version.margin_warning
        FROM platform.package_plan_version version
        JOIN platform.package_plan plan ON plan.id = version.package_plan_id
        JOIN platform.package_dimension_price_policy floor
          ON floor.usage_dimension = version.usage_dimension
        WHERE version.status = 'PUBLISHED'
          AND version.estimated_margin_ratio IS NOT NULL
          AND version.margin_warning
              <> (version.estimated_margin_ratio < floor.warning_margin)
        ORDER BY plan.code;
    """):
        findings.append((
            "C",
            f"{label}: margin_warning 与快照不符 —— 预警字段是复制来的陈旧值",
        ))

    return findings


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", required=True,
                        help="已由 Flyway 迁移完成的库，postgres:// 或 jdbc:postgresql://")
    parser.add_argument("--user", default="postgres")
    parser.add_argument("--password", default="")
    parser.add_argument("--report-only", action="store_true",
                        help="只打印清单，始终退出 0（用于盘点存量）")
    args = parser.parse_args()

    connection = Connection(args.url, args.user, args.password)
    print(f"== 审计 {connection.host}:{connection.port}/{connection.database} ==")
    findings = audit(connection)

    if not findings:
        print("通过：没有发现未被守卫评估、或已击穿下限的在售套餐。")
        return 0

    order = {"A": 0, "B": 1, "B2": 2, "C": 3}
    findings.sort(key=lambda item: order.get(item[0], 9))
    print(f"\n发现 {len(findings)} 项：\n")
    for kind, message in findings:
        print(f"  [{kind}] {message}")
    print(
        "\n处置口径：\n"
        "  A/B  → 该版本在被守卫评估前就发布了。迁移直写是唯一可能的原因，"
        "须补一次重算（参考 V103:252-255 的写法）或改走发布 API。\n"
        "  B2   → 补 platform.package_dimension_price_policy 行，或确认回退常量仍适用。\n"
        "  C    → margin_warning 不该从旧版本复制，必须按该维度预警下限重算。\n"
    )
    return 0 if args.report_only else 1


if __name__ == "__main__":
    sys.exit(main())

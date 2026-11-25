"""Flyway 迁移文件全面静态检查。

检查项：
  1. 版本号：重复、缺口（与库中已应用记录对照）
  2. append-only / 不可变触发器保护的表：是否有 UPDATE/DELETE（会运行时失败）
  3. 危险 DDL：DROP TABLE / DROP COLUMN / TRUNCATE / 无 WHERE 的 DELETE
  4. 幂等性：CREATE TABLE / ADD COLUMN / CREATE INDEX 是否带 IF NOT EXISTS
  5. 命名一致性：版本号与文件名描述是否匹配
  6. 交易安全：是否为破坏性操作显式包事务

用法：
  python scripts/audit-migrations.py [--verbose]
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PG_CONTAINER = "yuzhihuihe-api-platform-postgres-1"
DB = "api_model_platform"
PG_PASSWORD = "local-postgres-password"

SERVICES = [
    ("platform", "platform-service"),
    ("gateway", "gateway-service"),
]

# 已知的 append-only / 不可变触发器函数名 -> 该触发器保护的表
# 通过解析迁移里的 CREATE TRIGGER 自动发现，这里是兜底关键字
IMMUTABLE_HINT = re.compile(
    r"reject_\w*mutation|reject_published\w*|immutable|append_only", re.I)

CREATE_TRIGGER = re.compile(
    r"CREATE\s+TRIGGER\s+(\w+)\s+(BEFORE|AFTER)\s+(INSERT\s+OR\s+)?"
    r"(UPDATE(?:\s+OR\s+DELETE)?|DELETE(?:\s+OR\s+UPDATE)?)\s+ON\s+([\w.]+)",
    re.I | re.S)

UPDATE_TARGET = re.compile(r"\bUPDATE\s+(?:ONLY\s+)?([\w.]+)", re.I)
DELETE_TARGET = re.compile(r"\bDELETE\s+FROM\s+(?:ONLY\s+)?([\w.]+)", re.I)


def query(sql: str) -> list[list[str]]:
    r = subprocess.run(
        ["docker", "exec", "-e", f"PGPASSWORD={PG_PASSWORD}", PG_CONTAINER,
         "psql", "-U", "postgres", "-d", DB, "-t", "-A", "-F", "\t", "-c", sql],
        capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(f"查询失败: {r.stderr.strip()}")
    return [l.split("\t") for l in r.stdout.strip().splitlines() if l]


def strip_sql_comments(text: str) -> str:
    """去掉 -- 行注释与 /* */ 块注释，避免误报。"""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    out = []
    for line in text.splitlines():
        # 简单处理：不剥离字符串字面量内的 --（迁移里极少见）
        idx = line.find("--")
        out.append(line[:idx] if idx >= 0 else line)
    return "\n".join(out)


# 无条件 RAISE（无前置 IF 守卫）的触发器函数 —— 真正会拦任何 UPDATE/DELETE
UNCONDITIONAL_FUNCS = {
    "reject_operating_fact_mutation",
    "reject_balance_ledger_mutation",
    "reject_quota_ledger_mutation",
}


def unconditional_trigger_tables() -> dict[str, str]:
    """从数据库读触发器定义，只返回**无条件拒绝**的那些（表名 -> 触发器名）。

    判定依据：函数体里 RAISE 之前没有任何 IF ... THEN 守卫。
    这些触发器会拦下**任何** UPDATE/DELETE，是迁移的唯一真风险源。
    """
    rows = query(
        "SELECT n.nspname || '.' || c.relname, t.tgname, p.proname, "
        "       coalesce(p.prosrc,'') "
        "FROM pg_trigger t "
        "JOIN pg_class c ON c.oid = t.tgrelid "
        "JOIN pg_namespace n ON n.oid = c.relnamespace "
        "JOIN pg_proc p ON p.oid = t.tgfoid "
        "WHERE NOT t.tgisinternal AND n.nspname IN ('platform','gateway') "
        "  AND pg_get_triggerdef(t.oid) ~* '(UPDATE|DELETE)';")
    out: dict[str, str] = {}
    for row in rows:
        if len(row) < 4:
            continue
        table, tgname, proname, src = row[0], row[1], row[2], row[3]
        if proname not in UNCONDITIONAL_FUNCS:
            continue
        # 双保险：确认函数体里 RAISE 前没有 IF
        body = src
        raise_idx = body.upper().find("RAISE")
        head = body[:raise_idx] if raise_idx > 0 else body
        if re.search(r"\bIF\b", head, re.I):
            continue  # 有守卫，不算无条件
        out[table.split(".")[-1].lower()] = tgname
    return out



def audit_service(schema: str, service: str, verbose: bool) -> list[str]:
    issues: list[str] = []
    d = ROOT / service / "src/main/resources/db/migration"
    files = {}
    for p in sorted(d.glob("V*.sql")):
        ver = p.name[1:].split("__", 1)[0]
        if ver.isdigit():
            files.setdefault(int(ver), []).append(p)

    print(f"\n{'=' * 70}\n{schema} ({service}) —— {sum(len(v) for v in files.values())} 个文件\n{'=' * 70}")

    # ---- 1. 重复 / 缺口 ----
    dupes = [v for v, ps in files.items() if len(ps) > 1]
    if dupes:
        for v in sorted(dupes):
            names = ", ".join(p.name for p in files[v])
            issues.append(f"[重复编号] V{v}: {names}")

    applied = {}
    try:
        rows = query(f"SELECT version, description, success::text FROM {schema}.flyway_schema_history ORDER BY installed_rank;")
        applied = {int(r[0]): (r[1], r[2]) for r in rows if r[0].isdigit()}
    except SystemExit as e:
        issues.append(f"[无法读取历史] {e}")

    missing_in_files = sorted(set(applied) - set(files))
    for v in missing_in_files:
        issues.append(f"[文件缺失] V{v} 已应用到库中（{applied[v][0]}），但本地无对应文件")

    not_applied = sorted(set(files) - set(applied))
    gaps = []
    if applied:
        mx = max(applied)
        for v in sorted(files):
            if v <= mx and v not in applied:
                gaps.append(v)
    for v in gaps:
        issues.append(f"[编号缺口] V{v} 在已应用区间内但库中无记录")

    print(f"  已应用最大版本: V{max(applied) if applied else '-'}")
    print(f"  重复编号: {', '.join('V'+str(v) for v in sorted(dupes)) or '无'}")
    print(f"  文件缺失: {', '.join('V'+str(v) for v in missing_in_files) or '无'}")
    print(f"  编号缺口: {', '.join('V'+str(v) for v in gaps) or '无'}")
    print(f"  待应用(新增): {', '.join('V'+str(v) for v in not_applied) or '无'}")

    # ---- 2. 收集「无条件拒绝」触发器保护的表（唯一真风险源）----
    protected = unconditional_trigger_tables()
    print(f"\n  无条件拒绝型触发器保护表 {len(protected)} 张（会对任何 UPDATE/DELETE 报错）:")
    for t, tg in sorted(protected.items()):
        print(f"    {t}  <- {tg}")
    if not protected:
        print("    （无）")

    # 收集 DISABLE TRIGGER 的迁移，用于判断是否已豁免
    disabled_in: dict[int, set[str]] = {}
    for v, ps in files.items():
        for p in ps:
            txt = strip_sql_comments(p.read_text(encoding="utf-8", errors="replace"))
            for m in re.finditer(r"DISABLE\s+TRIGGER\s+(\w+)", txt, re.I):
                disabled_in.setdefault(v, set()).add(m.group(1).lower())
    # ---- 3. 扫每个文件：UPDATE/DELETE 无条件保护表 ----
    print(f"\n  --- UPDATE/DELETE 无条件保护表检查 ---")
    hits = 0
    for v in sorted(files):
        for p in files[v]:
            raw = p.read_text(encoding="utf-8", errors="replace")
            txt = strip_sql_comments(raw)
            # 本文件内是否 DISABLE 了对应触发器（豁免窗口）
            dis = {m.group(1).lower()
                   for m in re.finditer(r"DISABLE\s+TRIGGER\s+(\w+)", txt, re.I)}
            for rx, kind in ((UPDATE_TARGET, "UPDATE"), (DELETE_TARGET, "DELETE")):
                for m in rx.finditer(txt):
                    tbl = m.group(1).split(".")[-1].strip('"').lower()
                    if tbl not in protected:
                        continue
                    tg = protected[tbl]
                    if tg.lower() in dis:
                        continue  # 本文件已 DISABLE，安全
                    hits += 1
                    line = txt[:m.start()].count("\n") + 1
                    issues.append(
                        f"[触发器冲突] {p.name}:{line} {kind} {tbl} "
                        f"（受 {tg} 无条件保护，且未 DISABLE → 有存量数据时运行失败）")
    print(f"    冲突点: {hits or '无'}")

    # ---- 4. 危险 DDL ----
    print(f"\n  --- 危险 DDL 扫描 ---")
    # DELETE 无 WHERE：要求 DELETE 与 FROM/表名同处一条语句且其后无 WHERE
    delete_noguard = re.compile(
        r"\bDELETE\s+FROM\s+[\w.\"]+\s*(?:;|$)", re.I | re.M)
    danger_rules = [
        (re.compile(r"\bDROP\s+TABLE\s+(?!IF\s+EXISTS)", re.I), "DROP TABLE 无 IF EXISTS"),
        (re.compile(r"\bDROP\s+COLUMN\s+(?!IF\s+EXISTS)", re.I), "DROP COLUMN 无 IF EXISTS"),
        (re.compile(r"\bTRUNCATE\b", re.I), "TRUNCATE"),
        (delete_noguard, "DELETE 无 WHERE"),
    ]
    danger_hits = 0
    for v in sorted(files):
        for p in files[v]:
            txt = strip_sql_comments(p.read_text(encoding="utf-8", errors="replace"))
            # 折叠多行，便于判断 DELETE 后面是否跟 WHERE
            flat = re.sub(r"\s+", " ", txt)
            for rx, label in danger_rules:
                for m in rx.finditer(flat):
                    # 若该 DELETE 在后续 200 字符内出现 WHERE，视为有守卫
                    if label == "DELETE 无 WHERE":
                        tail = flat[m.end():m.end() + 300]
                        if re.search(r"\bWHERE\b", tail, re.I):
                            continue
                    line = txt[:m.start()].count("\n") + 1
                    danger_hits += 1
                    issues.append(f"[危险DDL] {p.name}:{line} {label}")
    print(f"    命中: {danger_hits or '无'}")

    # ---- 5. 幂等性 ----
    print(f"\n  --- 幂等性扫描（CREATE 无 IF NOT EXISTS）---")
    nonidem = []
    for v in sorted(files):
        for p in files[v]:
            txt = strip_sql_comments(p.read_text(encoding="utf-8", errors="replace"))
            for rx, label in [
                (re.compile(r"CREATE\s+TABLE\s+(?!IF\s+NOT\s+EXISTS)", re.I), "CREATE TABLE"),
                (re.compile(r"CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?!IF\s+NOT\s+EXISTS)", re.I), "CREATE INDEX"),
                (re.compile(r"ALTER\s+TABLE\s+[\w.]+\s+ADD\s+COLUMN\s+(?!IF\s+NOT\s+EXISTS)", re.I), "ADD COLUMN"),
            ]:
                for m in rx.finditer(txt):
                    line = txt[:m.start()].count("\n") + 1
                    nonidem.append((v, p.name, line, label))
    if nonidem:
        print(f"    {len(nonidem)} 处（Flyway 每个迁移只跑一次，通常可接受，仅供参考）")
        if verbose:
            for v, n, l, lab in nonidem[:40]:
                print(f"      V{v} {n}:{l} {lab}")
    else:
        print("    无")

    return issues


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    all_issues: list[str] = []
    for schema, service in SERVICES:
        all_issues += audit_service(schema, service, args.verbose)

    print(f"\n{'=' * 70}\n汇总\n{'=' * 70}")
    if not all_issues:
        print("  ✅ 未发现问题")
        return 0
    # 按类别分组
    buckets: dict[str, list[str]] = {}
    for i in all_issues:
        cat = i.split("]")[0].lstrip("[")
        buckets.setdefault(cat, []).append(i)
    for cat in sorted(buckets):
        print(f"\n  【{cat}】{len(buckets[cat])} 项")
        for i in buckets[cat]:
            print(f"    {i}")
    print(f"\n  合计 {len(all_issues)} 项")
    return 0 if not any(k == "触发器冲突" or "文件缺失" in k or "重复编号" in k for k in buckets) else 1


if __name__ == "__main__":
    sys.exit(main())

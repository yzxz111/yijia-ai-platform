#!/usr/bin/env bash
set -euo pipefail

deploy_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(dirname -- "$deploy_dir")"
env_files=(--env-file "$project_dir/.secrets/oss.env")
if [[ -f "$project_dir/.secrets/local-runtime.env" ]]; then
  env_files+=(--env-file "$project_dir/.secrets/local-runtime.env")
fi
compose_files=(-f "$deploy_dir/compose.yaml")
if [[ -f "$project_dir/.secrets/alipay.env" ]]; then
  env_files+=(--env-file "$project_dir/.secrets/alipay.env")
  compose_files+=(-f "$deploy_dir/compose.alipay.yaml")
fi
compose_files+=(-f "$deploy_dir/compose.https.yaml")

# ---------------------------------------------------------------------------
# 迁移校验和预检（只在 `up` 系列命令前跑）
#
# 为什么需要：修一个**已应用**迁移里的 bug 必然改变它的 Flyway 校验和，而
# Flyway 启动时校验不通过就会**中止迁移、让服务起不来**。那个失败的症状是
# "启动期校验和不匹配"，极容易被误判成数据库/镜像/环境变量的问题，
# 而真正的动作只是几条 UPDATE。
#
# 因此把这一步提到 `docker compose up` 之前：**在服务尝试启动之前**就报出
# 差异和修复语句，而不是等容器起来又挂掉再去翻日志。
#
# 只读检查，默认不写库；确认后用
#   python scripts/flyway-checksum-sync.py --schema platform --apply ...
# 或直接执行它打印出来的 UPDATE。
# ---------------------------------------------------------------------------
preflight_checksums() {
  local python_bin="${YZH_PYTHON:-python3}"
  local sync_script="$project_dir/scripts/flyway-checksum-sync.py"
  [[ -f "$sync_script" ]] || return 0
  command -v "$python_bin" >/dev/null 2>&1 || return 0

  local pg_container="yuzhihuihe-api-platform-postgres-1"
  docker inspect -f '{{.State.Running}}' "$pg_container" >/dev/null 2>&1 || return 0

  local db_password
  db_password="$(grep -E '^POSTGRES_PASSWORD=' "$project_dir/.secrets/oss.env" 2>/dev/null | head -n1 | cut -d= -f2- || true)"
  [[ -n "$db_password" ]] || return 0

  echo "== Flyway 校验和预检 =="
  "$python_bin" "$sync_script" --schema platform \
    --container "$pg_container" \
    --url "jdbc:postgresql://localhost/api_model_platform" \
    --user postgres --password "$db_password" || {
      echo
      echo "!! 上面的校验和不一致会让 platform-service 启动失败。"
      echo "!! 按它打印的 UPDATE 语句同步（或加 --apply），然后重新部署。"
      return 1
    }
}

if [[ "${1:-}" =~ ^(up|restart|start)$ ]]; then
  preflight_checksums
fi

exec docker compose --project-name yuzhihuihe-api-platform \
  "${env_files[@]}" \
  "${compose_files[@]}" "$@"

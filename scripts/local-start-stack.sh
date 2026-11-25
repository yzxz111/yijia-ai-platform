#!/usr/bin/env bash
# 启动本地 API 模型服务平台（system 中台）完整栈
#
# 用法：
#   bash scripts/local-start-stack.sh          # 启动并等待健康
#   bash scripts/local-start-stack.sh --logs   # 启动后跟踪网关日志
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE="deploy/env/.env"
COMPOSE_FILE="deploy/compose.yaml"
BASE_URL="http://127.0.0.1:18081"

fail() { echo "[错误] $*" >&2; exit 1; }

echo "==> 1/4 前置检查"

[ -f "$ENV_FILE" ] || fail "缺少 $ENV_FILE。请先运行：bash scripts/local-setup-env.sh"

for f in deploy/geoip/ip2region_v4.xdb deploy/geoip/ip2region_v6.xdb; do
  [ -s "$f" ] || fail "缺少 GeoIP 数据文件 $f。请运行 deploy/geoip/download-ip2region.ps1"
done
echo "    GeoIP 数据文件就绪"

docker info >/dev/null 2>&1 || fail "Docker 引擎未运行。请先启动 Docker Desktop（WSL2 后端），再重试。"
echo "    Docker 引擎就绪：$(docker info --format '{{.ServerVersion}}' 2>/dev/null)"

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" config --quiet \
  || fail "compose 配置校验失败"
echo "    compose 配置校验通过"

echo "==> 2/4 构建并启动容器（首次构建耗时较长）"
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up --build -d

echo "==> 3/4 等待服务健康（最多 15 分钟）"
deadline=$(( $(date +%s) + 900 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  p=$(curl -fsS --max-time 5 "$BASE_URL/health/platform" 2>/dev/null || true)
  g=$(curl -fsS --max-time 5 "$BASE_URL/health/gateway"  2>/dev/null || true)
  if echo "$p" | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && \
     echo "$g" | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
    echo "    两个服务均 UP"
    break
  fi
  printf '.'
  sleep 5
done
echo ""

echo "==> 4/4 状态汇总"
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps --all

echo ""
echo "健康检查："
curl -fsS "$BASE_URL/health/platform" 2>/dev/null && echo "" || echo "  platform: 未就绪"
curl -fsS "$BASE_URL/health/gateway"  2>/dev/null && echo "" || echo "  gateway:  未就绪"

echo ""
echo "控制台入口： $BASE_URL"
echo "管理员邮箱： $(grep '^LOCAL_ADMIN_EMAIL=' "$ENV_FILE" | cut -d= -f2-)"
echo "管理员密码： $(grep '^LOCAL_ADMIN_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)"

if [ "${1:-}" = "--logs" ]; then
  echo ""
  echo "==> 跟踪 gateway-service 日志（Ctrl+C 退出）"
  exec docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" logs -f gateway-service
fi

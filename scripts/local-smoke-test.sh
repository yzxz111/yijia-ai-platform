#!/usr/bin/env bash
# 本地栈冒烟验证：健康检查 + 管理员登录 + 受保护接口
#
# 用法：bash scripts/local-smoke-test.sh
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE="deploy/env/.env"
BASE_URL="${BASE_URL:-http://127.0.0.1:18081}"

[ -f "$ENV_FILE" ] || { echo "缺少 $ENV_FILE" >&2; exit 1; }

ADMIN_EMAIL=$(grep '^LOCAL_ADMIN_EMAIL=' "$ENV_FILE" | cut -d= -f2-)
ADMIN_PASSWORD=$(grep '^LOCAL_ADMIN_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)

echo "==> 健康检查"
for svc in platform gateway; do
  body=$(curl -fsS --max-time 10 "$BASE_URL/health/$svc" 2>/dev/null || echo "")
  if echo "$body" | grep -q '"status":"UP"'; then
    echo "    $svc: UP"
  else
    echo "    $svc: 未就绪 ($body)" >&2
    exit 1
  fi
done

# X-Device-Id 必须是 32 位小写十六进制字符串，否则返回 invalid_device_id。
# 必须使用**固定**值：每次随机会导致管理员累积超过 max_login_devices（默认 5）
# 的会话数，之后登录会被拒（login_device_limit_reached）。
DEVICE_ID="${SMOKE_DEVICE_ID:-550e8400e29b41d4a716446655440000}"

echo "==> 管理员登录（$ADMIN_EMAIL）"
resp=$(curl -sS --max-time 20 -X POST "$BASE_URL/console/platform/auth/login" \
  -H 'Content-Type: application/json' \
  -H "X-Device-Id: $DEVICE_ID" \
  -H 'User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0 Safari/537.36' \
  -d "{\"identifier\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" 2>&1)

token=$(echo "$resp" | grep -oE '"accessToken":"[^"]+"' | head -1 | cut -d'"' -f4 || true)
if [ -z "$token" ]; then
  echo "    登录失败：$resp" >&2
  exit 1
fi
echo "    已取得 accessToken"

echo "==> 受保护接口 /console/platform/auth/me"
curl -fsS --max-time 15 "$BASE_URL/console/platform/auth/me" \
  -H "Authorization: Bearer $token" 2>/dev/null | head -c 400
echo ""
echo ""
echo "==> 全部通过。控制台入口：$BASE_URL"

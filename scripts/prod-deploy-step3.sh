#!/usr/bin/env bash
# STEP 3: rollback anchor -> recreate gateway-service -> verify.
set -uo pipefail
P=/opt/yuzhihuihe-api-platform
GW=yuzhihuihe-api-platform-gateway-service-1
STAMP=$(date -u +%Y%m%d-%H%M%S)

echo "===== 1) rollback anchor ====="
OLD=576eee93f987
docker tag "$OLD" "yuzhihuihe/gateway-service:rollback-pre-cutfix-$STAMP" && \
  echo "  tagged yuzhihuihe/gateway-service:rollback-pre-cutfix-$STAMP -> $OLD"
NEW=$(docker images --format '{{.ID}}' yuzhihuihe/gateway-service:local | head -1)
echo "  new local image: $NEW"

echo
echo "===== 2) before ====="
docker inspect "$GW" --format '  started={{.State.StartedAt}} health={{.State.Health.Status}} image={{.Image}}'
echo -n "  log lines: "; docker logs "$GW" 2>&1 | wc -l

echo
echo "===== 3) recreate ONLY gateway-service ====="
cd "$P/deploy" || exit 1
bash "$P/deploy/production.sh" up -d --no-deps --force-recreate --no-build --pull never gateway-service 2>&1 | tail -12

echo
echo "===== 4) wait for healthy (max 240s) ====="
for i in $(seq 1 80); do
  st=$(docker inspect "$GW" --format '{{.State.Health.Status}}' 2>/dev/null || echo missing)
  case "$st" in
    healthy)   echo "  healthy after ~$((i*3))s"; break ;;
    unhealthy) echo "  UNHEALTHY after ~$((i*3))s"; break ;;
  esac
  sleep 3
done
docker inspect "$GW" --format '  started={{.State.StartedAt}} health={{.State.Health.Status}} image={{.Image}}'

echo
echo "===== 5) public API ====="
for u in https://api.yuzhh.com/actuator/health https://api.yuzhh.com/v1/models https://api.yuzhh.com/admin/models; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$u" 2>/dev/null)
  echo "  $u -> HTTP $code"
done

echo
echo "===== 6) wait 45s, then check startup + noise ====="
sleep 45
echo -n "  OTel export failures: "; docker logs "$GW" 2>&1 | grep -c 'Failed to export spans'
echo -n "  total log lines:      "; docker logs "$GW" 2>&1 | wc -l
echo "  --- last 6 lines ---"
docker logs --tail 6 "$GW" 2>&1 | cut -c1-170

echo
echo "===== 7) all containers ====="
docker ps --format '{{.Names}}|{{.Status}}' | grep -E 'gateway|platform|nginx|web-console|postgres|redis' | sort

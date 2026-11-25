#!/usr/bin/env bash
# STEP 3b: confirm the public API still works, then recreate platform-service.
set -uo pipefail
P=/opt/yuzhihuihe-api-platform
PL=yuzhihuihe-api-platform-platform-service-1

echo "===== A. is the public API still serving? (expect 401 = gateway alive, auth required) ====="
for u in https://api.yuzhh.com/v1/models https://api.yuzhh.com/actuator/health https://api.yuzhh.com/admin/models; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$u" 2>/dev/null)
  echo "  $u -> HTTP $code"
done

echo
echo "===== B. nginx still proxying to the new gateway container? ====="
docker logs --since 5m yuzhihuihe-api-platform-nginx-1 2>&1 | grep -oE '" (200|401|502|504) ' | sort | uniq -c | head

echo
echo "===== C. before: platform-service state ====="
docker inspect "$PL" --format '  started={{.State.StartedAt}} health={{.State.Health.Status}}'
echo -n "  OTEL_TRACING_ENABLED = "; docker exec "$PL" printenv OTEL_TRACING_ENABLED
echo -n "  OTel failures in current log: "; docker logs "$PL" 2>&1 | grep -c 'Failed to export spans'
echo -n "  total log lines:              "; docker logs "$PL" 2>&1 | wc -l

echo
echo "===== D. recreating ONLY platform-service ====="
cd "$P/deploy"
bash "$P/deploy/production.sh" up -d --no-deps platform-service 2>&1 | tail -20
rc=$?
echo "  exit=$rc"
[ $rc -ne 0 ] && { echo "  ABORT"; exit 1; }

echo
echo "===== E. wait for healthy (max 240s) ====="
for i in $(seq 1 80); do
  st=$(docker inspect "$PL" --format '{{.State.Health.Status}}' 2>/dev/null || echo missing)
  case "$st" in
    healthy)   echo "  healthy after ~$((i*3))s"; break ;;
    unhealthy) echo "  UNHEALTHY after ~$((i*3))s"; break ;;
  esac
  sleep 3
done
docker inspect "$PL" --format '  started={{.State.StartedAt}} health={{.State.Health.Status}}'
echo -n "  OTEL_TRACING_ENABLED = "; docker exec "$PL" printenv OTEL_TRACING_ENABLED

echo
echo "===== F. wait 60s then measure ====="
sleep 60
echo -n "  OTel failures since restart: "; docker logs "$PL" 2>&1 | grep -c 'Failed to export spans'
echo -n "  total log lines since restart: "; docker logs "$PL" 2>&1 | wc -l

echo
echo "===== G. public API after both recreates ====="
for u in https://api.yuzhh.com/v1/models https://api.yuzhh.com/actuator/health; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$u" 2>/dev/null)
  echo "  $u -> HTTP $code"
done
echo
docker ps --format '{{.Names}}\t{{.Status}}' | grep -E 'gateway|platform|nginx' | sort

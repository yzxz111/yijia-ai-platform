#!/usr/bin/env bash
# STEP 4: confirm the 502s were only the recreate window, and that nothing was lost.
set -uo pipefail
NG=yuzhihuihe-api-platform-nginx-1
GW=yuzhihuihe-api-platform-gateway-service-1
PL=yuzhihuihe-api-platform-platform-service-1

echo "===== A. 502s: how recent is the newest one? ====="
echo "  newest 502 timestamp:"
docker logs "$NG" 2>&1 | grep '" 502 ' | tail -1 | grep -oE '\[[^]]+\]' | sed 's/^/    /'
echo "  newest request of ANY status:"
docker logs "$NG" 2>&1 | tail -1 | grep -oE '\[[^]]+\]' | sed 's/^/    /'

echo
echo "===== B. 502 count in the last 60 seconds (should be 0) ====="
docker logs --since 60s "$NG" 2>&1 | grep -c '" 502 ' | sed 's/^/  last 60s: /'
echo "===== 502 count in the last 3 minutes ====="
docker logs --since 3m "$NG" 2>&1 | grep -c '" 502 ' | sed 's/^/  last 3m:  /'

echo
echo "===== C. status distribution since both restarts ====="
docker logs --since 3m "$NG" 2>&1 | grep -oE '" [0-9]{3} ' | sort | uniq -c | sort -rn | head

echo
echo "===== D. noise level now (per service, since restart) ====="
for c in "$GW" "$PL"; do
  t=$(docker logs "$c" 2>&1 | wc -l)
  o=$(docker logs "$c" 2>&1 | grep -c 'Failed to export spans')
  echo "  $c: total=$t  otel_failures=$o"
done

echo
echo "===== E. did anything else get lost in the recreate? (the keys that nearly bit us) ====="
for c in "$GW" "$PL"; do
  echo "--- $c"
  for k in OTEL_TRACING_ENABLED CONTENT_SAFETY_ENABLED CONTENT_SAFETY_SEMANTIC_ALLOW_MOCK PAYMENT_MOCK_ENABLED SUBSCRIPTION_JOBS_ENABLED SPRING_PROFILES_ACTIVE; do
    v=$(docker exec "$c" printenv "$k" 2>/dev/null)
    echo "    $k=${v:-<unset>}"
  done
done

echo
echo "===== F. end-to-end: console + model list reachable ====="
for u in https://api.yuzhh.com/actuator/health https://api.yuzhh.com/v1/models https://api.yuzhh.com/admin/models; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$u" 2>/dev/null)
  echo "  $u -> HTTP $code"
done

#!/usr/bin/env bash
# STEP 2a (read-only): figure out the correct restart invocation, without changing anything.
set -uo pipefail
P=/opt/yuzhihuihe-api-platform

echo "===== production.sh: the exec line and its args ====="
sed -n '55,75p' "$P/deploy/production.sh"

echo
echo "===== does it accept '--help' style passthrough? (dry: just show usage) ====="
grep -n 'usage\|Usage\|case \|"\$1"' "$P/deploy/production.sh" | head -20

echo
echo "===== what compose version and does 'config' resolve our new value? ====="
cd "$P/deploy"
docker compose --project-name yuzhihuihe-api-platform \
  --env-file "$P/.secrets/oss.env" \
  --env-file "$P/.secrets/local-runtime.env" \
  ${ALIPAY_ENV:+} \
  -f "$P/deploy/compose.yaml" \
  -f "$P/deploy/compose.https.yaml" \
  config 2>/dev/null | grep -A2 -B2 'OTEL_TRACING_ENABLED' | head -20

echo
echo "===== confirm the RESOLVED value for both services ====="
cd "$P/deploy"
for svc in gateway-service platform-service; do
  v=$(docker compose --project-name yuzhihuihe-api-platform \
        --env-file "$P/.secrets/oss.env" \
        --env-file "$P/.secrets/local-runtime.env" \
        -f "$P/deploy/compose.yaml" \
        -f "$P/deploy/compose.https.yaml" \
        config 2>/dev/null | awk -v s="$svc" '
          $0 ~ "^  "s":" {insvc=1; next}
          insvc && /^  [a-z]/ {insvc=0}
          insvc && /OTEL_TRACING_ENABLED/ {print $2; exit}')
  echo "  $svc -> OTEL_TRACING_ENABLED=$v"
done

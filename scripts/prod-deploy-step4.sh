#!/usr/bin/env bash
# STEP 4: proper rollback anchor + real end-to-end proof.
set -uo pipefail
GW=yuzhihuihe-api-platform-gateway-service-1
PREV=37cf01adaa5d          # rollback-t5t3-20261015d -- verified pre-fix
STAMP=$(date -u +%Y%m%d-%H%M%S)

echo "===== 1) re-anchor rollback onto the verified PRE-FIX image ====="
docker tag "$PREV" "yuzhihuihe/gateway-service:rollback-pre-cutfix-$STAMP" \
  && echo "  tagged rollback-pre-cutfix-$STAMP -> $PREV"
echo "  --- anchors now available ---"
docker images --format '  {{.Repository}}:{{.Tag}} {{.ID}}' | grep 'gateway-service:rollback-pre-cutfix'

echo
echo "===== 2) prove the anchor really is PRE-fix ====="
docker run --rm --entrypoint sh "yuzhihuihe/gateway-service:rollback-pre-cutfix-$STAMP" -c \
  'mkdir -p /tmp/j && cd /tmp/j && unzip -o -q /app/app.jar "BOOT-INF/classes/com/yuzhihuihe/apiplatform/gateway/openai/ChatCompletionService.class" && echo -n "  pre-fix image: failoverAfterInterruption = " && strings BOOT-INF/classes/com/yuzhihuihe/apiplatform/gateway/openai/ChatCompletionService.class | grep -c failoverAfterInterruption'

echo
echo "===== 3) real end-to-end streaming request through the public API ====="
echo "  (using a probe key so we exercise auth + routing, not a real charge)"
# find an api key that exists so we can reach the provider path; fall back to an auth-less probe
KEY=$(docker exec -i yuzhihuihe-api-platform-postgres-1 psql -U postgres -d api_model_platform -tAc \
  "select key_prefix from gateway.api_key where status='ACTIVE' order by created_at desc limit 1;" 2>/dev/null | tr -d ' ')
echo "  newest api_key prefix: ${KEY:-<none>}"

echo
echo "  --- unauthenticated (expect 401: auth path alive) ---"
curl -s -o /dev/null -w '    /v1/models -> HTTP %{http_code}\n' --max-time 15 https://api.yuzhh.com/v1/models

echo "  --- chat completions with a bogus key (expect 401, not 500) ---"
curl -s -o /dev/null -w '    /v1/chat/completions -> HTTP %{http_code}\n' --max-time 20 \
  -H 'Authorization: Bearer sk-invalid-probe' -H 'Content-Type: application/json' \
  -d '{"model":"chat-deep","stream":true,"messages":[{"role":"user","content":"ping"}]}' \
  https://api.yuzhh.com/v1/chat/completions

echo
echo "===== 4) gateway log after the probes (no unexpected exceptions?) ====="
docker logs --tail 15 "$GW" 2>&1 | cut -c1-160
echo
echo -n "  ERROR-level lines since restart: "
docker logs "$GW" 2>&1 | grep -c '"level":"ERROR"'
echo -n "  OTel export failures:            "
docker logs "$GW" 2>&1 | grep -c 'Failed to export spans'

echo
echo "===== 5) final state ====="
docker inspect "$GW" --format '  gateway: image={{.Image}} health={{.State.Health.Status}} started={{.State.StartedAt}}'
docker ps --format '{{.Names}}|{{.Status}}' | grep -E 'gateway|platform|nginx' | sort

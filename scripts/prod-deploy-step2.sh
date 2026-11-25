#!/usr/bin/env bash
# STEP 2: build the gateway image ON the server from the freshly installed sources.
set -uo pipefail
P=/opt/yuzhihuihe-api-platform
cd "$P" || exit 1

before=$(docker images --format '{{.ID}}' yuzhihuihe/gateway-service:local | head -1)
echo "image BEFORE: $before"
echo "build started: $(date -u +%H:%M:%S)"
echo

bash deploy/production.sh build gateway-service 2>&1 | tail -40
rc=${PIPESTATUS[0]}
echo
echo "build exit=$rc   finished: $(date -u +%H:%M:%S)"

if [ "$rc" -ne 0 ]; then
  echo "=== BUILD FAILED -- last 60 lines of output above; nothing was deployed ==="
  exit 1
fi

after=$(docker images --format '{{.ID}}' yuzhihuihe/gateway-service:local | head -1)
echo "image AFTER : $after"
if [ "$before" = "$after" ]; then
  echo "WARN: image id unchanged -- the build did not produce a new image"
  exit 2
fi

echo
echo "===== is the fix inside the NEW image? ====="
CLASS=BOOT-INF/classes/com/yuzhihuihe/apiplatform/gateway/openai/ChatCompletionResponseProtocol.class
docker run --rm --entrypoint sh "yuzhihuihe/gateway-service:local" -c "mkdir -p /tmp/jc && cd /tmp/jc && unzip -o -q /app/app.jar '$CLASS' && echo -n 'hasDeliverableContent = ' && strings '$CLASS' | grep -c hasDeliverableContent"
CLASS2=BOOT-INF/classes/com/yuzhihuihe/apiplatform/gateway/openai/ChatCompletionService.class
docker run --rm --entrypoint sh "yuzhihuihe/gateway-service:local" -c "mkdir -p /tmp/jc && cd /tmp/jc && unzip -o -q /app/app.jar '$CLASS2' && echo -n 'failoverAfterInterruption = ' && strings '$CLASS2' | grep -c failoverAfterInterruption"

echo
echo "########## image built, NOT yet deployed ##########"

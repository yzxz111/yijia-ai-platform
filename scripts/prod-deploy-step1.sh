#!/usr/bin/env bash
# STEP 1: stage -> backup -> install -> verify (all three in one go).
set -euo pipefail
P=/opt/yuzhihuihe-api-platform
STAGE=/tmp/dsh-stage
cd "$P"

echo "===== 0) staged files present? ====="
ls -la "$STAGE" | tail -n +2 | awk '{print "  "$5" bytes  "$9}'

echo
echo "===== 1) backup the versions we are about to overwrite ====="
STAMP=$(date -u +%Y%m%d-%H%M%S)
BK="$P/.src-backup-$STAMP"
mkdir -p "$BK"
while read -r want path; do
  [ -z "${want:-}" ] && continue
  mkdir -p "$BK/$(dirname "$path")"
  cp -p "$P/$path" "$BK/$path"
done < /tmp/dsh-manifest.txt
echo "  backup dir: $BK"
echo "$BK" > /tmp/dsh-backup-dir.txt

echo
echo "===== 2) install the staged files ====="
while read -r want path; do
  [ -z "${want:-}" ] && continue
  cp "$STAGE/$(basename "$path")" "$P/$path"
  echo "  installed $(basename "$path")"
done < /tmp/dsh-manifest.txt

echo
echo "===== 3) verify by sha256 ====="
fail=0
while read -r want path; do
  [ -z "${want:-}" ] && continue
  got=$(sha256sum "$P/$path" | cut -d' ' -f1)
  if [ "$want" = "$got" ]; then
    echo "  OK       $(basename "$path")"
  else
    echo "  MISMATCH $(basename "$path")"; fail=1
  fi
done < /tmp/dsh-manifest.txt
[ "$fail" -eq 1 ] && { echo "ABORT: hash mismatch"; exit 1; }

echo
echo "===== 4) fix markers now in the server tree ====="
printf '  protocol.hasDeliverableContent      = %s\n' "$(grep -c hasDeliverableContent gateway-service/src/main/java/com/yuzhihuihe/apiplatform/gateway/openai/ChatCompletionResponseProtocol.java)"
printf '  service.failoverAfterInterruption   = %s\n' "$(grep -c failoverAfterInterruption gateway-service/src/main/java/com/yuzhihuihe/apiplatform/gateway/openai/ChatCompletionService.java)"
printf '  test: pre-content failover          = %s\n' "$(grep -c failsOverWhenTheUpstreamCutsTheStreamBeforeAnyContentReachesTheClient gateway-service/src/test/java/com/yuzhihuihe/apiplatform/gateway/openai/ChatCompletionContractTest.java)"
printf '  test: post-content no-failover      = %s\n' "$(grep -c doesNotFailOverOnceContentHasReachedTheClient gateway-service/src/test/java/com/yuzhihuihe/apiplatform/gateway/openai/ChatCompletionContractTest.java)"
printf '  test: role-only envelope            = %s\n' "$(grep -c treatsARoleOnlyEnvelopeAsNoContentEvenAfterSerialization gateway-service/src/test/java/com/yuzhihuihe/apiplatform/gateway/openai/ChatCompletionResponseProtocolTest.java)"
printf '  test: already-classified reason     = %s\n' "$(grep -c anAlreadyClassifiedFailureKeepsItsExactReasonCode gateway-service/src/test/java/com/yuzhihuihe/apiplatform/gateway/openai/ChatStreamFailureClassifierTest.java)"
printf '  test: empty-choices tolerated       = %s\n' "$(grep -c acceptsEmptyChoicesWithoutUsageAsAValidStreamChunk gateway-service/src/test/java/com/yuzhihuihe/apiplatform/gateway/openai/ChatCompletionResponseProtocolTest.java)"

echo
echo "===== 5) rollback anchor (image currently running) ====="
docker images --format '{{.Repository}}:{{.Tag}} {{.ID}}' | grep -E 'yuzhihuihe/gateway-service:local'

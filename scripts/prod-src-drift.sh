#!/usr/bin/env bash
# READ-ONLY: how far apart are the server's sources and my working tree?
set -uo pipefail
P=/opt/yuzhihuihe-api-platform

echo "===== A. is the server tree a git repo / what revision? ====="
cd "$P" 2>/dev/null || { echo "  no $P"; exit 1; }
git rev-parse --short HEAD 2>/dev/null || echo "  (not a git repo)"
git status --porcelain 2>/dev/null | head -5 || true
echo "  dir listing (top):"; ls -1 | head -20

echo
echo "===== B. does the server have the files I changed? (hash of each) ====="
for f in \
  gateway-service/src/main/java/com/yuzhihuihe/apiplatform/gateway/openai/ChatCompletionService.java \
  gateway-service/src/main/java/com/yuzhihuihe/apiplatform/gateway/openai/ChatCompletionResponseProtocol.java \
  gateway-service/src/main/java/com/yuzhihuihe/apiplatform/gateway/openai/ChatStreamFailureClassifier.java \
  gateway-service/src/main/java/com/yuzhihuihe/apiplatform/gateway/provider/ProviderAdapter.java ; do
  if [ -f "$P/$f" ]; then
    printf '  %s  %s  %s\n' "$(sha256sum "$P/$f" | cut -c1-16)" "$(stat -c '%y' "$P/$f" | cut -c1-19)" "$(basename "$f")"
  else
    printf '  %-16s %s\n' "(missing)" "$(basename "$f")"
  fi
done

echo
echo "===== C. the two markers of my fix present on the server? ====="
grep -c 'hasDeliverableContent' "$P/gateway-service/src/main/java/com/yuzhihuihe/apiplatform/gateway/openai/ChatCompletionResponseProtocol.java" 2>/dev/null | sed 's/^/  ChatCompletionResponseProtocol.hasDeliverableContent = /'
grep -c 'failoverAfterInterruption' "$P/gateway-service/src/main/java/com/yuzhihuihe/apiplatform/gateway/openai/ChatCompletionService.java" 2>/dev/null | sed 's/^/  ChatCompletionService.failoverAfterInterruption = /'
grep -c 'anAlreadyClassifiedFailureKeepsItsExactReasonCode' "$P/gateway-service/src/test/java/com/yuzhihuihe/apiplatform/gateway/openai/ChatStreamFailureClassifierTest.java" 2>/dev/null | sed 's/^/  classifier test (already-classified case)  = /'
grep -c 'treatsARoleOnlyEnvelopeAsNoContentEvenAfterSerialization' "$P/gateway-service/src/test/java/com/yuzhihuihe/apiplatform/gateway/openai/ChatCompletionResponseProtocolTest.java" 2>/dev/null | sed 's/^/  protocol test (role-only envelope)      = /'

echo
echo "===== D. can the server build offline? (maven repo + mirror) ====="
ls -d /root/.m2/repository 2>/dev/null && du -sh /root/.m2/repository 2>/dev/null | sed 's/^/  m2: /'
grep -oE 'mirrors\.aliyun\.com|maven-settings\.xml' "$P/gateway-service/Dockerfile" | sort -u | sed 's/^/  Dockerfile mirror: /'
ls -la "$P/maven-settings.xml" 2>/dev/null | sed 's/^/  /' || echo "  (no maven-settings.xml at root)"
find "$P" -maxdepth 2 -name 'maven-settings.xml' 2>/dev/null | sed 's/^/  found: /'

echo
echo "===== E. buildkit cache state (image layer reuse) ====="
docker buildx du 2>/dev/null | tail -3 || echo "  (buildx du unavailable)"

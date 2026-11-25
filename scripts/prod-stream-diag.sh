#!/usr/bin/env bash
# READ-ONLY diagnostic: capture a real SSE stream from the production upstream to see
# which field violates validateStreamChunk().
set -uo pipefail
PG=yuzhihuihe-api-platform-postgres-1

echo "===== upstream config for chat-deep / chat-standard ====="
docker exec -i "$PG" psql -U postgres -d api_model_platform -tAc "select m.code||' | upstream='||cm.upstream_model||' | channel='||c.code||' | url='||c.base_url||' | timeoutMs='||coalesce(c.timeout_ms::text,'-') from gateway.channel_model cm join gateway.model m on m.id=cm.model_id join gateway.channel c on c.id=cm.channel_id where m.code in ('chat-deep','chat-standard') and cm.enabled and c.enabled;"

echo
echo "===== is usage/stream_options in channel settings? ====="
docker exec -i "$PG" psql -U postgres -d api_model_platform -tAc "select 'settings='||coalesce(settings::text,'null') from gateway.channel where code='tianyi-chat-primary';"

echo
echo "===== decrypted api key (masked length) ====="
KEY_LEN=$(docker exec -i "$PG" psql -U postgres -d api_model_platform -tAc "select length(encrypted_api_key) from gateway.channel where code='tianyi-chat-primary';")
echo "encrypted_api_key length = $KEY_LEN (needs gateway's AES key to decrypt)"

echo
echo "===== raw upstream SSE capture: minimal chat stream ====="
# Use the same base_url the channel uses; key must be supplied by caller.
if [ -z "${UPSTREAM_KEY:-}" ]; then
  echo "UPSTREAM_KEY not set - skipping live capture"
else
  echo "--- streaming request, capturing first 40 SSE lines ---"
  curl -sN --max-time 45 \
    -H "Authorization: Bearer $UPSTREAM_KEY" \
    -H 'Content-Type: application/json' \
    -d '{"model":"deepseek-v4.1-flash","stream":true,"stream_options":{"include_usage":true},"max_tokens":64,"messages":[{"role":"user","content":"用一句话说明什么是TCP"}]}' \
    "https://ai.ctaigw.cn/v1/chat/completions" | head -40
fi

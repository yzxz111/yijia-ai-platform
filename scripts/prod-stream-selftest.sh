#!/usr/bin/env bash
# READ-ONLY: what has REAL traffic done since the cut-fix deploy (05:22:34Z)?
set -uo pipefail
GW=yuzhihuihe-api-platform-gateway-service-1
Q() { docker exec -i yuzhihuihe-api-platform-postgres-1 psql -U postgres -d api_model_platform -tAc "$1"; }

echo "===== A. deploy time vs traffic ====="
echo "  gateway started: $(docker inspect "$GW" --format '{{.State.StartedAt}}')"
Q "select '  calls since deploy = '||count(*) from gateway.usage_log where created_at > '2026-10-16 05:22:34+00';"
Q "select '  calls today        = '||count(*) from gateway.usage_log where created_at::date = current_date;"

echo
echo "===== B. every streaming call since the deploy ====="
Q "select to_char(created_at,'HH24:MI:SS')||' | '||rpad(model_code,16)||' | http='||coalesce(http_status::text,'-')||' | outcome='||rpad(coalesce(outcome,'-'),10)||' | first_token_ms='||coalesce(first_token_ms::text,'NULL')||' | attempt='||coalesce(attempt_count::text,'-')||' | dur='||coalesce(duration_ms::text,'-')||' | '||coalesce(left(error_summary,40),'-') from gateway.usage_log where created_at > '2026-10-16 05:22:34+00' order by created_at;"

echo
echo "===== C. the real calls that DID happen around 04:58 (pre-deploy, for baseline) ====="
Q "select to_char(created_at,'HH24:MI:SS')||' | '||rpad(model_code,16)||' | http='||coalesce(http_status::text,'-')||' | first_token_ms='||coalesce(first_token_ms::text,'NULL')||' | '||coalesce(left(error_summary,40),'-') from gateway.usage_log where created_at between '2026-10-16 04:50:00+00' and '2026-10-16 05:10:00+00' order by created_at;"

echo
echo "===== D. post-first-byte failures in the new gateway's lifetime ====="
echo -n "  'chat stream failed after the first byte' lines: "
docker logs "$GW" 2>&1 | grep -c 'chat stream failed after the first byte'
echo -n "  reason codes seen: "
docker logs "$GW" 2>&1 | grep -o 'reason=[a-z_]*' | sort -u | tr '\n' ' '
echo
echo -n "  OTel noise: "; docker logs "$GW" 2>&1 | grep -c 'Failed to export spans'
echo -n "  ERROR lines: "; docker logs "$GW" 2>&1 | grep -c '"level":"ERROR"'

echo
echo "===== E. can I recover the one ACTIVE key to test with? ====="
Q "select 'prefix='||key_prefix||' | secret_ciphertext_len='||coalesce(length(secret_ciphertext),0)||' | key_version='||coalesce(secret_key_version,'-')||' | hash_len='||coalesce(length(key_hash),0) from gateway.api_key where status='ACTIVE';"
echo "  SECURITY_API_KEY_PEPPER set: $(docker exec "$GW" printenv SECURITY_API_KEY_PEPPER >/dev/null 2>&1 && echo yes || echo no)"

echo
echo "===== F. does that key's user still have quota on chat-deep? ====="
Q "select 'user='||left(ak.user_id::text,8)||' key='||ak.key_prefix||' allowed_models='||coalesce(ak.allowed_models::text,'-')||' rpm='||coalesce(ak.requests_per_minute::text,'-') from gateway.api_key ak where ak.status='ACTIVE';"
Q "select 'package_instance='||left(pi.id::text,8)||' status='||coalesce(pi.status,'-') from gateway.package_instance pi where pi.user_id=(select user_id from gateway.api_key where status='ACTIVE') limit 3;" 2>/dev/null || echo "  (package_instance query skipped)"

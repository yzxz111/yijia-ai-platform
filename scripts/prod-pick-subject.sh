#!/usr/bin/env bash
# READ-ONLY: find a LIVE package whose allowed_models includes chat-deep.
set -uo pipefail
Q() { docker exec -i yuzhihuihe-api-platform-postgres-1 psql -U postgres -d api_model_platform -tAc "$1"; }

echo "===== A. live packages that allow chat-deep ====="
Q "select left(user_id::text,8)||' | pi='||left(id::text,8)||' | status='||coalesce(status,'-')||' | expires='||coalesce(expires_at::text,'-')||' | pkg='||coalesce(package_code,'-')||' | dim='||coalesce(usage_dimension,'-')||' | total_quota='||coalesce(total_token_quota::text,'-')||' | used='||coalesce(used_tokens::text,'-') from gateway.package_instance where status='ACTIVE' and allowed_models::text like '%chat-deep%' order by expires_at desc nulls last limit 10;"

echo
echo "===== B. the chat-deep entry inside such a package (quota shape) ====="
Q "select jsonb_pretty(allowed_models) from gateway.package_instance where status='ACTIVE' and allowed_models::text like '%chat-deep%' limit 1;" 2>&1 | grep -A6 -i 'chat-deep' | head -20

echo
echo "===== C. does the SAME user also have an ACTIVE key (so I can test without minting)? ====="
Q "select ak.key_prefix||' | user='||left(ak.user_id::text,8)||' | status='||ak.status||' | models='||left(coalesce(ak.allowed_models::text,'-'),60) from gateway.api_key ak where ak.status='ACTIVE' and ak.user_id in (select user_id from gateway.package_instance where status='ACTIVE' and allowed_models::text like '%chat-deep%');"

echo
echo "===== D. any key at all (even revoked) for those users, with its allowed models ====="
Q "select ak.key_prefix||' | user='||left(ak.user_id::text,8)||' | status='||ak.status||' | models='||left(coalesce(ak.allowed_models::text,'-'),70) from gateway.api_key ak where ak.user_id in (select user_id from gateway.package_instance where status='ACTIVE' and allowed_models::text like '%chat-deep%') order by ak.created_at desc limit 8;"

echo
echo "===== E. summary: active packages allowing chat-deep, grouped by user ====="
Q "select left(user_id::text,8)||' -> '||count(*)||' live package(s): '||string_agg(coalesce(package_code,'-'), ', ') from gateway.package_instance where status='ACTIVE' and allowed_models::text like '%chat-deep%' group by user_id;"

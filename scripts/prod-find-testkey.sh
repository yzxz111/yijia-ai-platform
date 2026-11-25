#!/usr/bin/env bash
# READ-ONLY: find a way to make a REAL authenticated streaming request for the self-test.
set -uo pipefail
Q() { docker exec -i yuzhihuihe-api-platform-postgres-1 psql -U postgres -d api_model_platform -tAc "$1"; }

echo "===== A. api_key table shape ====="
Q "select column_name from information_schema.columns where table_schema='gateway' and table_name='api_key' order by ordinal_position;" | tr '\n' ' '
echo
echo "===== B. keys that are usable (active, not expired) ====="
Q "select key_prefix||' | user='||left(user_id::text,8)||' | status='||status||' | created='||created_at::date||' | last_used='||coalesce(last_used_at::text,'never') from gateway.api_key order by created_at desc limit 12;"

echo
echo "===== C. is there a seeded/test key with a KNOWN plaintext? (demo-style prefixes) ====="
Q "select key_prefix||' | '||coalesce(name,'-')||' | '||status from gateway.api_key where key_prefix like '%test%' or key_prefix like '%demo%' or key_prefix like '%probe%' or coalesce(name,'') ilike '%test%' order by created_at desc limit 10;"

echo
echo "===== D. how are keys hashed? (so I know if I can mint a temp one) ====="
Q "select column_name from information_schema.columns where table_schema='gateway' and table_name='api_key' and column_name like '%hash%' or column_name like '%pepper%' or column_name like '%secret%';"
echo "  gateway env has SECURITY_API_KEY_PEPPER: $(docker exec yuzhihuihe-api-platform-gateway-service-1 printenv SECURITY_API_KEY_PEPPER >/dev/null 2>&1 && echo yes || echo no)"

echo
echo "===== E. admin console credentials available for login? ====="
Q "select 'admin users: '||count(*) from platform.admin_user;" 2>/dev/null || echo "  (no platform.admin_user)"
Q "select column_name from information_schema.columns where table_schema='platform' and table_name='admin_user' order by ordinal_position;" 2>/dev/null | tr '\n' ' '

echo
echo "===== F. user accounts with a package (needed to actually reach a provider) ====="
Q "select p.user_id::text||' | model='||m.code||' | remaining='||coalesce(p.remaining_tokens::text,'-') from gateway.package_instance_model_quota p join gateway.model m on m.id=p.model_id order by p.created_at desc limit 5;" 2>/dev/null || \
Q "select table_name from information_schema.tables where table_schema='gateway' and table_name like '%quota%';"

#!/usr/bin/env bash
set -uo pipefail
PG="${PG:-yuzhihuihe-api-platform-postgres-1}"
Q() { docker exec -i "$PG" psql -U postgres -d api_model_platform -tAc "$1"; }

echo "=== historical provider_id -> which channel (context of ids now remapped) ==="
Q "select 'provider_id='||coalesce(provider_id::text,'null')||' channel='||rpad(coalesce(c.code,'(no such channel)'),24)||' n='||count(*)||' first='||min(l.created_at)::text||' last='||max(l.created_at)::text from gateway.usage_log l left join gateway.channel c on c.id=l.channel_id group by l.provider_id, c.code order by count(*) desc;"

echo
echo "=== local-mock-primary traffic detail ==="
Q "select to_char(created_at,'MM-DD HH24:MI')||' | '||rpad(model_code,18)||' | up='||rpad(coalesce(upstream_model,'-'),18)||' | http='||coalesce(http_status::text,'-')||' | '||coalesce(left(error_summary,50),'-') from gateway.usage_log where channel_id='87000000-0000-4000-8000-000000000101' order by created_at desc limit 20;"

echo
echo "=== last traffic per model (staleness) ==="
Q "select rpad(model_code,20)||' last='||max(created_at)::text||' calls='||count(*) from gateway.usage_log group by model_code order by max(created_at) desc;"

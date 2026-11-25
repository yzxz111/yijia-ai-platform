#!/usr/bin/env bash
# READ-ONLY: full history of upstream-side cuts, to find the real pattern.
set -uo pipefail
GW=yuzhihuihe-api-platform-gateway-service-1
Q() { docker exec -i yuzhihuihe-api-platform-postgres-1 psql -U postgres -d api_model_platform -tAc "$1"; }

echo "===== A. every transport-ish failure ever recorded (usage_log, whole history) ====="
Q "select to_char(created_at,'MM-DD HH24:MI:SS')||' | '||rpad(model_code,16)||' | http='||coalesce(http_status::text,'-')||' | '||rpad(coalesce(error_category,'-'),24)||' | '||coalesce(left(error_summary,55),'-') from gateway.usage_log where error_summary ilike '%unreachable%' or error_summary ilike '%interrupted%' or error_summary ilike '%reset%' or error_summary ilike '%timeout%' or error_category ilike '%timeout%' or error_summary ilike '%504%' order by created_at desc limit 40;"

echo
echo "===== B. counts by summary shape, whole history ====="
Q "select rpad(coalesce(error_summary,'(none)'),62)||' n='||count(*) from gateway.usage_log where http_status is null or http_status not between 200 and 299 group by error_summary order by count(*) desc limit 20;"

echo
echo "===== C. how long did the cut requests take? (timeout=60000ms is the ceiling) ====="
Q "select rpad(model_code,16)||' | http='||coalesce(http_status::text,'-')||' | n='||lpad(count(*)::text,4)||' | minMs='||lpad(min(duration_ms)::text,7)||' p50Ms='||lpad(percentile_disc(0.5) within group (order by duration_ms)::text,7)||' maxMs='||lpad(max(duration_ms)::text,7) from gateway.usage_log where http_status is null or http_status not between 200 and 299 group by model_code, http_status order by count(*) desc limit 15;"

echo
echo "===== D. upstream cut vs stream: was the first byte already delivered? (first_token_ms) ====="
Q "select rpad(model_code,16)||' | first_token_ms='||coalesce(first_token_ms::text,'NULL')||' | duration_ms='||coalesce(duration_ms::text,'-')||' | stream='||coalesce(stream::text,'-')||' | '||coalesce(left(error_summary,45),'-') from gateway.usage_log where (error_summary ilike '%unreachable%' or error_summary ilike '%interrupted%') order by created_at desc limit 20;"

echo
echo "===== E. gateway-side raw transport events, full container history ====="
echo "--- distinct netty/reactor transport messages ever logged ---"
docker logs "$GW" 2>&1 | grep -oE '(PrematureCloseException[^"\\]{0,60}|Connection reset by peer|recvAddress\(\.\.\) failed[^"\\]{0,30}|writevAddresses\(\.\.\) failed[^"\\]{0,30}|prematurely closed[^"\\]{0,40})' | sort | uniq -c | sort -rn | head -20
echo "  (empty = this container generation saw none)"

echo
echo "===== F. upstream config: timeouts and limits per live channel ====="
Q "select rpad(code,26)||' timeout_ms='||rpad(coalesce(timeout_ms::text,'NULL'),8)||' rpm='||rpad(coalesce(requests_per_minute::text,'-'),6)||' tpm='||rpad(coalesce(tokens_per_minute::text,'-'),10)||' conc='||coalesce(max_concurrency::text,'-') from gateway.channel where enabled order by code;"

echo
echo "===== G. model-side max_output_tokens (does the gateway ask for too much?) ====="
Q "select rpad(code,18)||' max_output_tokens='||coalesce(max_output_tokens::text,'NULL')||' context_window='||coalesce(context_window::text,'NULL')||' strategy='||coalesce(routing_strategy,'-') from gateway.model where enabled order by code;"

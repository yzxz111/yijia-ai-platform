#!/usr/bin/env bash
# READ-ONLY: for the models that actually failed, is there a second channel to fail over TO?
set -uo pipefail
Q() { docker exec -i yuzhihuihe-api-platform-postgres-1 psql -U postgres -d api_model_platform -tAc "$1"; }

echo "===== A. every enabled model -> its usable channels (enabled mapping AND enabled channel) ====="
Q "select rpad(m.code,18)||' usable_channels='||count(*)||'  '||string_agg(c.code, ', ' order by c.code) from gateway.model m join gateway.channel_model cm on cm.model_id=m.id and cm.enabled join gateway.channel c on c.id=cm.channel_id and c.enabled join gateway.provider p on p.id=c.provider_id where m.enabled and m.client_visible group by m.code order by count(*), m.code;"

echo
echo "===== B. the models that actually failed in production: how many channels each? ====="
Q "select rpad(m.code,18)||' channels='||count(*) from gateway.model m join gateway.channel_model cm on cm.model_id=m.id and cm.enabled join gateway.channel c on c.id=cm.channel_id and c.enabled where m.code in ('chat-deep','chat-standard','vision-pro','multimodal-basic','text-basic','text-max','image-basic','video-minimax') group by m.code order by 1;"

echo
echo "===== C. are those channels the SAME upstream host? (failover to a mirror is useless) ====="
Q "select rpad(c.code,26)||' -> '||coalesce(c.base_url,'-') from gateway.channel c where c.enabled order by c.base_url, c.code;"

echo
echo "===== D. did the 6 chunk-shape failures deliver anything to the client? ====="
Q "select to_char(created_at,'MM-DD HH24:MI')||' | '||rpad(model_code,14)||' | first_token_ms='||coalesce(first_token_ms::text,'NULL')||' | attempt_count='||coalesce(attempt_count::text,'-')||' | duration_ms='||coalesce(duration_ms::text,'-') from gateway.usage_log where error_summary ilike '%unknown_stream_failure%' order by created_at desc;"

echo
echo "===== E. retry evidence: did any failed request ever span more than one attempt? ====="
Q "select 'attempt_count distribution: '||coalesce(attempt_count::text,'NULL')||' -> n='||count(*) from gateway.usage_log where http_status not between 200 and 299 group by attempt_count order by 1;"

echo
echo "===== F. channels/providers that could serve as a real second opinion ====="
Q "select rpad(p.code,12)||' type='||rpad(coalesce(p.provider_type,'-'),10)||' enabled='||p.enabled||' url='||coalesce(p.base_url,'-') from gateway.provider p order by p.enabled desc, p.code;"

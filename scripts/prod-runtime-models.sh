#!/usr/bin/env bash
set -uo pipefail
GW=yuzhihuihe-api-platform-gateway-service-1
PL=yuzhihuihe-api-platform-platform-service-1
Q() { docker exec -i yuzhihuihe-api-platform-postgres-1 psql -U postgres -d api_model_platform -tAc "$1"; }

echo "===== container start times (current deploy) ====="
for c in "$GW" "$PL" yuzhihuihe-api-platform-web-console-1 yuzhihuihe-api-platform-document-converter-1; do
  docker inspect "$c" --format '{{.Name}} started={{.State.StartedAt}} image={{.Config.Image}}'
done
docker inspect "$GW" --format 'gateway image created={{.Created}}'

echo
echo "===== models actually called SINCE gateway start (the currently-running deploy) ====="
Q "select rpad(model_code,20)||' calls='||lpad(count(*)::text,5)||' ok='||lpad(count(*) filter (where http_status between 200 and 299)::text,5)||' err='||lpad(count(*) filter (where http_status not between 200 and 299)::text,5)||' last='||max(created_at)::text from gateway.usage_log where created_at > (select start_time from (select now() - interval '18 hours' as start_time) s) group by model_code order by count(*) desc;"

echo
echo "===== TODAY (server date) ====="
Q "select rpad(model_code,20)||' calls='||lpad(count(*)::text,5)||' ok='||lpad(count(*) filter (where http_status between 200 and 299)::text,5)||' upstream='||coalesce(max(upstream_model),'-') from gateway.usage_log where created_at::date = current_date group by model_code order by count(*) desc;"

echo
echo "===== can the server actually reach each upstream right now? ====="
for h in ai.ctaigw.cn dashscope.aliyuncs.com api.deepseek.com; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "https://$h/" 2>/dev/null)
  echo "  $h -> HTTP $code"
done

echo
echo "===== wiremock: is ANY model/provider pointing at it in the live DB? ====="
Q "select 'channel='||c.code||' url='||coalesce(c.base_url,'-')||' enabled='||c.enabled||' provider='||p.code from gateway.channel c join gateway.provider p on p.id=c.provider_id where c.base_url like '%wiremock%' or p.base_url like '%wiremock%';"

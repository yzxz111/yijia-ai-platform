#!/usr/bin/env bash
set -euo pipefail

proxy_container=yuzhihuihe-api-platform-nginx-1
docker exec "$proxy_container" nginx -t
docker exec "$proxy_container" nginx -s reload

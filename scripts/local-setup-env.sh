#!/usr/bin/env bash
# 本地栈 .env 生成脚本：基于 .env.example 生成 deploy/env/.env，并注入随机密钥
set -euo pipefail

# 切到仓库根目录（本脚本位于 <root>/scripts/）
cd "$(dirname "$0")/.."

SRC="deploy/env/.env.example"
DST="deploy/env/.env"

if [ ! -f "$SRC" ]; then
  echo "缺少模板: $SRC" >&2
  exit 1
fi

cp -f "$SRC" "$DST"

# 生成随机值
HMAC_SECRET=$(openssl rand -hex 32)
API_KEY_PEPPER=$(openssl rand -hex 32)
ENC_KEY=$(openssl rand -base64 32 | tr -d '\n')
CONVERTER_TOKEN=$(openssl rand -hex 32)
MOCK_CALLBACK_SECRET=$(openssl rand -hex 32)
LOGIN_RISK_PEPPER=$(openssl rand -hex 32)
PWD_LOGIN_HMAC=$(openssl rand -hex 32)
ADMIN_PASSWORD=$(openssl rand -base64 18 | tr -d '\n' | tr -d '/+=' | head -c 16)

# 用 sed 替换占位符（值中不含 / & 等 sed 特殊字符，已过滤）
sed -i \
  -e "s|^SECURITY_INTERNAL_HMAC_SECRET=.*|SECURITY_INTERNAL_HMAC_SECRET=${HMAC_SECRET}|" \
  -e "s|^SECURITY_API_KEY_PEPPER=.*|SECURITY_API_KEY_PEPPER=${API_KEY_PEPPER}|" \
  -e "s|^GATEWAY_SECRETS_ENCRYPTION_KEY=.*|GATEWAY_SECRETS_ENCRYPTION_KEY=${ENC_KEY}|" \
  -e "s|^DOCUMENT_CONVERTER_AUTH_TOKEN=.*|DOCUMENT_CONVERTER_AUTH_TOKEN=${CONVERTER_TOKEN}|" \
  -e "s|^PAYMENT_MOCK_CALLBACK_SECRET=.*|PAYMENT_MOCK_CALLBACK_SECRET=${MOCK_CALLBACK_SECRET}|" \
  -e "s|^LOGIN_RISK_PEPPER=.*|LOGIN_RISK_PEPPER=${LOGIN_RISK_PEPPER}|" \
  -e "s|^PASSWORD_LOGIN_RATE_LIMIT_HMAC_KEY=.*|PASSWORD_LOGIN_RATE_LIMIT_HMAC_KEY=${PWD_LOGIN_HMAC}|" \
  -e "s|^LOCAL_ADMIN_PASSWORD=.*|LOCAL_ADMIN_PASSWORD=${ADMIN_PASSWORD}|" \
  "$DST"

# 校验：GATEWAY_SECRETS_ENCRYPTION_KEY 必须解码为 32 字节
BYTES=$(grep '^GATEWAY_SECRETS_ENCRYPTION_KEY=' "$DST" | cut -d= -f2- | base64 -d 2>/dev/null | wc -c | tr -d ' ')
if [ "$BYTES" != "32" ]; then
  echo "GATEWAY_SECRETS_ENCRYPTION_KEY 解码后为 ${BYTES} 字节，应为 32" >&2
  exit 1
fi

# 校验：三个必填密钥均已替换，且互不相同
for k in SECURITY_INTERNAL_HMAC_SECRET SECURITY_API_KEY_PEPPER GATEWAY_SECRETS_ENCRYPTION_KEY \
         DOCUMENT_CONVERTER_AUTH_TOKEN PAYMENT_MOCK_CALLBACK_SECRET LOGIN_RISK_PEPPER \
         PASSWORD_LOGIN_RATE_LIMIT_HMAC_KEY; do
  v=$(grep "^${k}=" "$DST" | cut -d= -f2-)
  if [ -z "$v" ] || echo "$v" | grep -q 'replace-me'; then
    echo "${k} 仍为占位符或为空" >&2
    exit 1
  fi
done

if [ "$(grep '^PAYMENT_MOCK_CALLBACK_SECRET=' "$DST" | cut -d= -f2-)" = "$(grep '^SECURITY_INTERNAL_HMAC_SECRET=' "$DST" | cut -d= -f2-)" ]; then
  echo "PAYMENT_MOCK_CALLBACK_SECRET 不得与 SECURITY_INTERNAL_HMAC_SECRET 相同" >&2
  exit 1
fi

echo "OK: deploy/env/.env 已生成"
echo "LOCAL_ADMIN_EMAIL=$(grep '^LOCAL_ADMIN_EMAIL=' "$DST" | cut -d= -f2-)"
echo "LOCAL_ADMIN_PASSWORD=${ADMIN_PASSWORD}"

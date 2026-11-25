#!/usr/bin/env python3
"""READ-ONLY: would a real second upstream actually work as a failover target?
Decrypts the DORMANT deepseek credentials and probes them. Sends one tiny request."""
import base64, json, os, ssl, subprocess, urllib.request

KEY = base64.b64decode(os.environ["GATEWAY_SECRETS_ENCRYPTION_KEY"])

def pg(sql):
    return subprocess.run(
        ["docker", "exec", "-i", "yuzhihuihe-api-platform-postgres-1",
         "psql", "-U", "postgres", "-d", "api_model_platform", "-tAc", sql],
        capture_output=True, text=True, check=True).stdout.strip()

def decrypt(blob):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    return AESGCM(KEY).decrypt(blob[:12], blob[12:], None).decode()

print("=== dormant channels that could serve as a REAL second upstream ===")
rows = pg("select c.code||'|'||c.base_url||'|'||encode(c.encrypted_api_key,'hex')||'|'||c.enabled::text "
          "from gateway.channel c where c.code in ('deepseek-primary','bailian-primary') order by c.code;")
legacy = pg("select c.code||'|'||c.base_url||'|'||coalesce(encode(c.encrypted_api_key,'hex'),'')||'|'||c.enabled::text "
            "from gateway.channel c where c.code in ('bailian-primary','deepseek-primary','tianyi-chat-primary') order by c.code;")
print(legacy)

for line in rows.splitlines():
    if not line.strip():
        continue
    code, base_url, hexkey, enabled = line.split("|")
    print(f"\n--- {code}  ({base_url})  enabled={enabled}")
    if not hexkey:
        print("    no credential stored -> cannot probe")
        continue
    try:
        api_key = decrypt(bytes.fromhex(hexkey))
    except Exception as exc:
        print(f"    decrypt failed: {exc}")
        continue
    print(f"    credential: len={len(api_key)} prefix={api_key[:5]}...")

    url = base_url.rstrip("/") + "/chat/completions"
    model = "deepseek-chat"
    body = json.dumps({"model": model, "stream": False, "max_tokens": 8,
                       "messages": [{"role": "user", "content": "hi"}]}).encode()
    req = urllib.request.Request(url, data=body, method="POST", headers={
        "Authorization": f"Bearer {api_key}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, context=ssl.create_default_context(), timeout=30) as resp:
            payload = resp.read(300).decode("utf-8", "replace")
            print(f"    PROBE HTTP {resp.status}: {payload[:200]}")
    except urllib.error.HTTPError as exc:
        detail = exc.read(300).decode("utf-8", "replace")
        print(f"    PROBE HTTP {exc.code}: {detail[:220]}")
    except Exception as exc:
        print(f"    PROBE FAILED: {exc.__class__.__name__}: {exc}")

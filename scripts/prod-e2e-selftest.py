#!/usr/bin/env python3
"""TRUE end-to-end self-test through the production gateway.

Mints a temporary API key for a TEST-artifact account (revoked again at the end),
sends a real streaming request that forces upstream truncation, and reports what the
client actually received plus what the gateway recorded in its own usage ledger.
"""
import base64, hashlib, hmac, json, os, secrets, ssl, subprocess, sys, time

PEPPER = os.environ["SECURITY_API_KEY_PEPPER"].encode()
PREFIX = "sk-yzh-"

def pg(sql):
    return subprocess.run(
        ["docker", "exec", "-i", "yuzhihuihe-api-platform-postgres-1",
         "psql", "-U", "postgres", "-d", "api_model_platform", "-tAc", sql],
        capture_output=True, text=True, check=True).stdout.strip()

def psql(sql):
    return subprocess.run(
        ["docker", "exec", "-i", "yuzhihuihe-api-platform-postgres-1",
         "psql", "-U", "postgres", "-d", "api_model_platform", "-c", sql],
        capture_output=True, text=True)

# --- pick a TEST-artifact user with an ACTIVE package that allows chat-deep ---
row = pg("""
  select pi.user_id::text || '|' || pi.tenant_id::text || '|' || pi.id::text
  from gateway.package_instance pi
  where pi.status='ACTIVE'
    and pi.allowed_models::text like '%chat-deep%'
    and (pi.user_id::text like '8ea03799%' or pi.user_id::text like '84cfcebb%'
         or pi.user_id::text like '70000000%' or pi.user_id::text like '23837b95%')
  order by pi.expires_at desc nulls last limit 1;
""")
if not row:
    print("no TEST-artifact account with chat-deep; aborting"); sys.exit(1)
user_id, tenant_id, pi_id = row.split("|")
print(f"test subject: user={user_id[:8]} tenant={tenant_id[:8]} package_instance={pi_id[:8]}")

# --- mint a temporary key (same scheme as ApiKeyService: HMAC-SHA256(pepper, secret)) ---
raw = secrets.token_bytes(32)
secret = PREFIX + base64.urlsafe_b64encode(raw).decode().rstrip("=")
prefix = secret[: len(PREFIX) + 8]
digest = hmac.new(PEPPER, secret.encode(), hashlib.sha256).hexdigest()
print(f"minted temp key: {prefix}… (len={len(secret)})")

out = psql(f"""
  insert into gateway.api_key
    (id, tenant_id, user_id, package_instance_id, name, key_prefix, key_hash,
     allowed_models, status, usage_dimension, created_at, updated_at)
  values
    (gen_random_uuid(), '{tenant_id}', '{user_id}', '{pi_id}',
     'DSH-SELFTEST-TEMP-ARTIFACT-20261016',
     '{prefix}', '{digest}', '["chat-deep"]'::jsonb, 'ACTIVE', 'TOKEN', now(), now())
  returning id;
""")
if out.returncode != 0:
    print("INSERT FAILED:\n" + out.stderr[:800]); sys.exit(1)
key_id = out.stdout.strip().splitlines()[2].strip() if out.stdout.count("\n") > 2 else out.stdout.strip()
print(f"inserted api_key id={key_id}")

try:
    import urllib.request, urllib.error
    url = "https://api.yuzhh.com/v1/chat/completions"
    payload = {
        "model": "chat-deep",
        "stream": True,
        "max_tokens": 100,        # forces upstream length truncation = the repro condition
        "messages": [{"role": "user",
                      "content": "请输出 500 个汉字，只输出汉字，不要推理过程"}],
    }
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), method="POST",
                                 headers={"Authorization": f"Bearer {secret}",
                                          "Content-Type": "application/json",
                                          "Accept": "text/event-stream"})
    print(f"\nPOST {url}   model=chat-deep stream=true max_tokens=100")
    frames, tail, t0 = [], None, time.time()
    try:
        with urllib.request.urlopen(req, context=ssl.create_default_context(), timeout=120) as resp:
            print(f"  HTTP {resp.status}  content-type={resp.headers.get('content-type')}")
            for raw_line in resp:
                line = raw_line.decode("utf-8", "replace").rstrip("\r\n")
                if not line:
                    continue
                frames.append(line)
    except urllib.error.HTTPError as exc:
        tail = f"HTTP {exc.code}: {exc.read(300).decode('utf-8','replace')}"
    except Exception as exc:
        tail = f"{exc.__class__.__name__}: {exc}"
    el = time.time() - t0

    data_frames = [f[5:].strip() for f in frames if f.startswith("data:")]
    done = "[DONE]" in data_frames
    errors = [f for f in data_frames if f != "[DONE]" and '"error"' in f]
    content = ""
    finish = None
    for d in data_frames:
        if d == "[DONE]":
            continue
        try:
            o = json.loads(d)
        except Exception:
            continue
        for c in (o.get("choices") or []):
            content += (c.get("delta") or {}).get("content") or ""
            if c.get("finish_reason"):
                finish = c["finish_reason"]

    print(f"  sse_lines={len(frames)}  data_frames={len(data_frames)}  elapsed={el:.1f}s")
    print(f"  [DONE] present      : {done}")
    print(f"  finish_reason       : {finish}")
    print(f"  content length      : {len(content)} chars")
    print(f"  error frames        : {len(errors)}")
    for e in errors[:2]:
        print(f"     {e[:220]}")
    if tail:
        print(f"  TRANSPORT PROBLEM   : {tail}")

    print("\n--- gateway's own accounting for this request (last usage row) ---")
    print(pg("""
      select 'http='||coalesce(http_status::text,'-')
           ||' outcome='||coalesce(outcome,'-')
           ||' first_token_ms='||coalesce(first_token_ms::text,'NULL')
           ||' attempt='||coalesce(attempt_count::text,'-')
           ||' finish_reason='||coalesce(finish_reason,'-')
           ||' err='||coalesce(left(error_summary,40),'none')
      from gateway.usage_log order by created_at desc limit 1;
    """))

    print("\n--- gateway log lines for this window ---")
    log = subprocess.run(["docker", "logs", "--since", "3m",
                          "yuzhihuihe-api-platform-gateway-service-1"],
                         capture_output=True, text=True).stderr
    hits = [l for l in (log or "").splitlines()
            if "chat stream failed" in l or '"level":"ERROR"' in l]
    if hits:
        for l in hits[-5:]:
            print("   " + l[:200])
    else:
        print("   (no stream-failure and no ERROR lines)")

    ok = done and not errors and not tail
    print("\n" + "=" * 74)
    print("VERDICT:", "stream completed normally (content + finish_reason + [DONE], no error frame)"
          if ok else "stream did NOT complete normally -- see above")
    print("=" * 74)
finally:
    cleanup = psql(f"update gateway.api_key set status='REVOKED', revoked_at=now(), "
                   f"updated_at=now() where key_hash='{digest}' returning key_prefix;")
    print("\ncleanup: temp key revoked ->", (cleanup.stdout or cleanup.stderr).strip()[:120])

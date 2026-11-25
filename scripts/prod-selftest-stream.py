#!/usr/bin/env python3
"""Try hard to reproduce the exact production failure shape and record the verdict."""
import base64, json, os, ssl, subprocess, sys, time, urllib.request, urllib.error

KEY = base64.b64decode(os.environ["GATEWAY_SECRETS_ENCRYPTION_KEY"])

def pg(sql):
    return subprocess.run(
        ["docker", "exec", "-i", "yuzhihuihe-api-platform-postgres-1",
         "psql", "-U", "postgres", "-d", "api_model_platform", "-tAc", sql],
        capture_output=True, text=True, check=True).stdout.strip()

def decrypt(blob):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    return AESGCM(KEY).decrypt(blob[:12], blob[12:], None).decode()

row = pg("select encode(encrypted_api_key,'hex')||'|'||base_url from gateway.channel "
         "where code='tianyi-chat-primary';")
hexkey, base_url = row.split("|", 1)
api_key = decrypt(bytes.fromhex(hexkey))
url = base_url.rstrip("/") + "/chat/completions"


def old_rule(o):
    ch = o.get("choices")
    if not isinstance(ch, list):
        return False
    if len(ch) == 0:
        return isinstance(o.get("usage"), dict)     # OLD: required usage
    return True


def new_rule(o):
    ch = o.get("choices")
    if not isinstance(ch, list):
        return False
    if len(ch) == 0:
        return True                                  # NEW: tolerated
    return True


def run(label, max_tokens, prompt, stream_options=True):
    payload = {"model": "deepseek-v4.1-flash", "stream": True,
               "max_tokens": max_tokens,
               "messages": [{"role": "user", "content": prompt}]}
    if stream_options:
        payload["stream_options"] = {"include_usage": True}
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), method="POST",
                                 headers={"Authorization": f"Bearer {api_key}",
                                          "Content-Type": "application/json",
                                          "Accept": "text/event-stream"})
    frames, tail, t0 = [], None, time.time()
    try:
        with urllib.request.urlopen(req, context=ssl.create_default_context(), timeout=180) as resp:
            for raw in resp:
                line = raw.decode("utf-8", "replace").rstrip("\r\n")
                if line.startswith("data:"):
                    d = line[5:].strip()
                    if d == "[DONE]":
                        frames.append("[DONE]")
                        break
                    if d:
                        frames.append(json.loads(d))
    except Exception as exc:
        tail = f"{exc.__class__.__name__}: {exc}"
    el = time.time() - t0

    real = [f for f in frames if isinstance(f, dict)]
    bad_old = [f for f in real if not old_rule(f)]
    bad_new = [f for f in real if not new_rule(f)]
    finishers = [c.get("finish_reason") for f in real for c in (f.get("choices") or [])
                 if c.get("finish_reason")]
    print(f"{label:52s} frames={len(real):4d} DONE={str(frames and frames[-1] == '[DONE]'):5s} "
          f"finish={finishers} bad_OLD={len(bad_old)} bad_NEW={len(bad_new)} {el:.1f}s")
    if tail:
        print(f"      TRANSPORT: {tail}")
    return real, bad_old, bad_new


print("=" * 100)
print("Reproducing the length-truncation shape (the condition that broke production)")
print("=" * 100)
results = []
for i, (label, cap, prompt, so) in enumerate([
    ("known repro: max_tokens=200, long-answer prompt", 200, "详细解释 TCP 三次握手，至少 300 字", True),
    ("known repro: max_tokens=800, long-answer prompt", 800, "详细解释 TCP 三次握手，至少 300 字", True),
    ("no reasoning: plain long answer, max_tokens=100", 100, "请输出 500 个汉字，只输出汉字，不要推理过程", True),
    ("minimal: max_tokens=8", 8, "详细解释 TCP 三次握手", True),
    ("no stream_options, max_tokens=200", 200, "详细解释 TCP 三次握手，至少 300 字", False),
]):
    results.append(run(label, cap, prompt, so))

print()
print("=" * 100)
total_old = sum(len(r[1]) for r in results)
total_new = sum(len(r[2]) for r in results)
print(f"frames rejected by OLD rule across all runs : {total_old}")
print(f"frames rejected by NEW rule across all runs : {total_new}")
print("=" * 100)
if total_old and not total_new:
    print("=> REPRODUCED: the upstream still emits frames the OLD rule rejected;")
    print("   the deployed NEW rule accepts them.")
elif not total_old:
    print("=> NOT reproduced in these runs: the upstream returned only legal frames.")
    print("   So live evidence alone cannot confirm the fix; it is confirmed by the")
    print("   unit tests (which feed the exact frame) plus the deployed bytecode.")
sys.exit(0 if total_new == 0 else 1)

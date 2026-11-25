#!/usr/bin/env python3
"""READ-ONLY: determine whether the empty-choices chunk survives the gateway's own
output-cap cut, i.e. whether takeUntil(outputCapReached) intercepts it first."""
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

row = pg("select encode(encrypted_api_key,'hex')||'|'||base_url from gateway.channel where code='tianyi-chat-primary';")
hexkey, base_url = row.split("|", 1)
api_key = decrypt(bytes.fromhex(hexkey))
url = base_url.rstrip("/") + "/chat/completions"


def stream_and_report(label, max_tokens):
    print("=" * 76)
    print(f"{label}   max_tokens={max_tokens}")
    print("=" * 76)
    payload = {
        "model": "deepseek-v4.1-flash", "stream": True,
        "stream_options": {"include_usage": True},
        "max_tokens": max_tokens,
        "messages": [{"role": "user", "content": "详细解释 TCP 三次握手，至少 300 字"}],
    }
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), method="POST",
                                 headers={"Authorization": f"Bearer {api_key}",
                                          "Content-Type": "application/json",
                                          "Accept": "text/event-stream"})
    seq = []
    with urllib.request.urlopen(req, context=ssl.create_default_context(), timeout=180) as resp:
        for raw in resp:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            if not line.startswith("data:"):
                continue
            d = line[5:].strip()
            if d == "[DONE]":
                seq.append(("DONE", None))
                break
            if not d:
                continue
            seq.append(("data", json.loads(d)))

    total = len(seq)
    for i, (kind, obj) in enumerate(seq):
        if kind == "DONE":
            continue
        ch = obj.get("choices")
        if isinstance(ch, list) and len(ch) == 0:
            has_usage = isinstance(obj.get("usage"), dict)
            print(f"  chunk #{i+1}/{total}  EMPTY choices  usage={'YES' if has_usage else 'NO'}"
                  f"  <-- {'LEGAL (usage tail)' if has_usage else 'ILLEGAL per gateway'}")

    # what a takeUntil on the finish chunk would keep
    finish_idx = None
    for i, (kind, obj) in enumerate(seq):
        if kind != "data":
            continue
        for c in (obj.get("choices") or []):
            if c.get("finish_reason"):
                finish_idx = i
                break
        if finish_idx is not None:
            break

    print(f"  total events={total}  [DONE]={seq[-1][0] == 'DONE'}")
    if finish_idx is not None:
        kind, obj = seq[finish_idx]
        fr = [c.get("finish_reason") for c in (obj.get("choices") or [])]
        print(f"  first finish_reason chunk = #{finish_idx+1} {fr}")
        after = seq[finish_idx + 1:]
        print(f"  events AFTER the finish chunk = {len(after)}")
        for j, (k, o) in enumerate(after):
            if k == "DONE":
                print(f"    +{j+1} DONE")
            else:
                ch = o.get("choices")
                print(f"    +{j+1} choices={json.dumps(ch, ensure_ascii=False)[:60]}"
                      f" usage={'yes' if isinstance(o.get('usage'), dict) else 'no'}")
    print()


stream_and_report("A. truncate at max_tokens=64 (empty answer region)", 64)
stream_and_report("B. truncate at max_tokens=200", 200)

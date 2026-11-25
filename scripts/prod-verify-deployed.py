#!/usr/bin/env python3
"""Decisive check: are the three fixes present in the RUNNING gateway image?

Parses each class file's constant pool directly (no javap needed) and looks for
method-name entries that only exist in the fixed code.
"""
import re, subprocess, sys

GW = "yuzhihuihe-api-platform-gateway-service-1"
BASE = "BOOT-INF/classes/com/yuzhihuihe/apiplatform/gateway/"
TARGETS = {
    "openai/ChatCompletionResponseProtocol.class": ["hasDeliverableContent"],
    "openai/ChatCompletionService.class": ["failoverAfterInterruption", "finishStream"],
    "openai/ChatStreamFailureClassifier.class": ["reason", "isClientAbort", "cameFromTheDownstreamWrite"],
}

def entries(cls_bytes):
    """Crude but reliable: method names appear as UTF8 constant-pool entries."""
    return set(re.findall(rb"[A-Za-z_$][A-Za-z0-9_$]{3,60}", cls_bytes))

ok = True
for rel, want in TARGETS.items():
    path = BASE + rel
    dump = subprocess.run(
        ["docker", "exec", GW, "sh", "-c",
         f"cd /tmp && rm -rf cp && mkdir cp && cd cp && unzip -o -q /app/app.jar '{path}' && cat '{path}'"],
        capture_output=True)
    if dump.returncode != 0 or not dump.stdout:
        print(f"  FAIL  {rel}  (could not extract)")
        ok = False
        continue
    names = entries(dump.stdout)
    found = {w: (w.encode() in names) for w in want}
    mark = "OK   " if all(found.values()) else "FAIL "
    if not all(found.values()):
        ok = False
    detail = "  ".join(f"{w}={'yes' if v else 'NO'}" for w, v in found.items())
    print(f"  {mark} {rel:52s} {detail}")

print()
print("VERDICT:", "all three fixes are present in the running image"
      if ok else "SOMETHING IS MISSING -- investigate before trusting the deploy")
sys.exit(0 if ok else 1)

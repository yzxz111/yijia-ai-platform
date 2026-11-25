#!/usr/bin/env bash
# STEP 1 (safe, no restart): back up the runtime env file and turn OTEL tracing off.
# The only edit is the OTEL_TRACING_ENABLED value; everything else in the file is untouched.
set -euo pipefail
P=/opt/yuzhihuihe-api-platform
F="$P/.secrets/local-runtime.env"
STAMP=$(date -u +%Y%m%d-%H%M%S)
BAK="$F.bak-$STAMP-otel-off"

echo "===== backup ====="
cp -p "$F" "$BAK"
echo "  $BAK"
echo -n "  sha256 before: "; sha256sum "$F" | cut -d' ' -f1

echo
echo "===== the exact line we are about to replace ====="
grep -n '^OTEL_TRACING_ENABLED=' "$F"

echo
echo "===== apply the edit (value only; one line in, several comment lines + value out) ====="
python3 - "$F" <<'PY'
import sys
path = sys.argv[1]
with open(path, encoding='utf-8') as handle:
    lines = handle.readlines()

replacement = [
    "# ---------------------------------------------------------------------------\n",
    "# OTel tracing: OFF. This host runs no collector at all -- nothing listens on 4318\n",
    "# and the container cannot even resolve 'otel-collector', while\n",
    "# management.otlp.tracing.endpoint still points there. The exporter therefore\n",
    "# retried forever and wrote one ERROR every ~13s: 1461 of the gateway's last 1467\n",
    "# log lines were that single message, which buried every real diagnostic\n",
    "# (it is what made the 2026-10-16 stream-failure investigation so slow).\n",
    "# Traces with no receiver are worth nothing, so the honest value is 'false' --\n",
    "# which is also the compose.yaml default. Set it back to true only together with\n",
    "# actually deploying a collector.\n",
    "OTEL_TRACING_ENABLED=false\n",
]

out, replaced = [], 0
for line in lines:
    if line.startswith('OTEL_TRACING_ENABLED='):
        out.extend(replacement)
        replaced += 1
    else:
        out.append(line)

if replaced != 1:
    raise SystemExit(f"expected exactly 1 OTEL_TRACING_ENABLED line, found {replaced}")

with open(path, 'w', encoding='utf-8') as handle:
    handle.writelines(out)
print(f"  replaced {replaced} line")
PY

echo
echo "===== verify ====="
grep -n 'OTEL_TRACING_ENABLED' "$F"
echo -n "  sha256 after:  "; sha256sum "$F" | cut -d' ' -f1
echo "  permissions:   $(stat -c '%a %U:%G' "$F")   (was 600 root:root)"

echo
echo "===== diff against backup (should be OTEL-only) ====="
diff "$BAK" "$F" | sed 's/^/  /'

echo
echo "===== sanity: every other key still present ====="
echo -n "  key count before: "; grep -cE '^[A-Za-z0-9_]+=' "$BAK"
echo -n "  key count after:  "; grep -cE '^[A-Za-z0-9_]+=' "$F"

echo
echo "===== which restart path is available ====="
chmod +x "$P/deploy/production.sh"
"$P/deploy/production.sh" otel-off-check --help 2>&1 | head -20 || echo "  (production.sh needs a different arg form)"

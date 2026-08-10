#!/usr/bin/env bash
# Sample Semper RAM (PSS) + pull on-device ROM/RAM CSV written by AlphaDeviceMeter.
#
# Usage (Pixel on USB / WSL with adb):
#   ./scripts/meter_alpha_device_memory.sh once
#   ./scripts/meter_alpha_device_memory.sh watch          # during a matrix scenario
#   ./scripts/meter_alpha_device_memory.sh pull           # after done:N
#   ./scripts/meter_alpha_device_memory.sh summarize OUT.csv
#
# Env:
#   PACKAGE   default com.indicvision.semper
#   INTERVAL  watch poll seconds (default 5)
#   OUT_DIR   where pull/watch CSVs land (default /tmp/semper_alpha_meter)
set -euo pipefail

PACKAGE="${PACKAGE:-com.indicvision.semper}"
INTERVAL="${INTERVAL:-5}"
OUT_DIR="${OUT_DIR:-/tmp/semper_alpha_meter}"
CMD="${1:-once}"

need_adb() {
  command -v adb >/dev/null || { echo "adb not found" >&2; exit 1; }
  adb get-state >/dev/null 2>&1 || { echo "no device (adb get-state failed)" >&2; exit 1; }
}

mkdir -p "$OUT_DIR"

# TOTAL PSS from dumpsys meminfo (kB). Empty if process not running.
pss_kb() {
  adb shell dumpsys meminfo "$PACKAGE" 2>/dev/null \
    | tr -d '\r' \
    | awk '
      /TOTAL PSS:/ { gsub(/[^0-9]/, "", $3); if ($3!="") { print $3; exit } }
      /^ *TOTAL +[0-9]/ && !seen { print $2; seen=1; exit }
    '
}

# App code+data+cache from dumpsys package when present (bytes). Best-effort.
package_sizes() {
  adb shell dumpsys package "$PACKAGE" 2>/dev/null | tr -d '\r' | awk '
    /codeSize=/ { for (i=1;i<=NF;i++) if ($i ~ /codeSize=/) { split($i,a,"="); code=a[2] } }
    /dataSize=/ { for (i=1;i<=NF;i++) if ($i ~ /dataSize=/) { split($i,a,"="); data=a[2] } }
    /cacheSize=/ { for (i=1;i<=NF;i++) if ($i ~ /cacheSize=/) { split($i,a,"="); cache=a[2] } }
    END {
      if (code=="" && data=="" && cache=="") exit 1
      printf "code=%s data=%s cache=%s\n", code+0, data+0, cache+0
    }
  ' || true
}

remote_csv() {
  # App writes AlphaDeviceMeter CSV here (no root / run-as needed).
  echo "/sdcard/Android/data/${PACKAGE}/files/alpha_meter/samples.csv"
}

cmd_once() {
  need_adb
  local ts pss sizes
  ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  pss="$(pss_kb || true)"
  sizes="$(package_sizes || true)"
  echo "utc=${ts} package=${PACKAGE} pss_kb=${pss:-na} ${sizes}"
  echo "${ts},${pss:-},${sizes}" >> "${OUT_DIR}/adb_meminfo.csv"
}

cmd_watch() {
  need_adb
  local header="${OUT_DIR}/adb_watch.csv"
  if [[ ! -f "$header" ]]; then
    echo "utc_iso,pss_kb" > "$header"
  fi
  echo "Watching ${PACKAGE} every ${INTERVAL}s → ${header} (Ctrl-C to stop)"
  while true; do
    local ts pss
    ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    pss="$(pss_kb || true)"
    echo "${ts},${pss:-}" | tee -a "$header"
    sleep "$INTERVAL"
  done
}

cmd_pull() {
  need_adb
  local remote dest
  remote="$(remote_csv)"
  dest="${OUT_DIR}/samples.csv"
  if adb pull "$remote" "$dest" 2>/dev/null; then
    echo "Pulled ${remote} → ${dest}"
  else
    echo "No app CSV at ${remote} yet (open the app / run a scenario first)." >&2
    echo "Falling back to logcat AlphaMeter lines…"
    adb logcat -d -s AlphaMeter:I | tr -d '\r' | tee "${OUT_DIR}/alphameter_logcat.txt"
  fi
}

cmd_summarize() {
  local csv="${1:-${OUT_DIR}/samples.csv}"
  if [[ ! -f "$csv" ]]; then
    echo "missing ${csv} — run: $0 pull" >&2
    exit 1
  fi
  python3 - "$csv" <<'PY'
import csv, sys
from collections import defaultdict
path = sys.argv[1]
rows = list(csv.DictReader(open(path, newline="")))
if not rows:
    print("empty csv"); sys.exit(0)
def num(r, k):
    try: return int(float(r.get(k) or 0))
    except: return 0
peak_pss = max(num(r, "pss_kb") for r in rows)
peak_java = max(num(r, "java_used_kb") for r in rows)
peak_rom = max(num(r, "rom_bytes") for r in rows)
min_rom = min(num(r, "rom_bytes") for r in rows)
print(f"samples={len(rows)}")
print(f"peak_pss_kb={peak_pss} ({peak_pss/1024:.1f} MiB)")
print(f"peak_java_used_kb={peak_java} ({peak_java/1024:.1f} MiB)")
print(f"rom_bytes_min={min_rom} rom_bytes_peak={peak_rom} delta={peak_rom-min_rom}")
print(f"rom_peak_mib={peak_rom/1024/1024:.1f} delta_mib={(peak_rom-min_rom)/1024/1024:.1f}")
print("--- by label (last sample) ---")
last = {}
for r in rows:
    last[r.get("label") or "?"] = r
for label, r in last.items():
    print(
        f"{label}: pss_kb={num(r,'pss_kb')} java_kb={num(r,'java_used_kb')} "
        f"rom_bytes={num(r,'rom_bytes')} sessions_bytes={num(r,'sessions_bytes')} "
        f"cache_dir_bytes={num(r,'cache_dir_bytes')}"
    )
PY
}

case "$CMD" in
  once) cmd_once ;;
  watch) cmd_watch ;;
  pull) cmd_pull ;;
  summarize) cmd_summarize "${2:-}" ;;
  *)
    echo "Usage: $0 once|watch|pull|summarize [csv]" >&2
    exit 2
    ;;
esac

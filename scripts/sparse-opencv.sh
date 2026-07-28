#!/usr/bin/env bash
# Drop unused OpenCV trees from the submodule worktree (doc/samples/data/apps).
# Run from repo root after: git submodule update --init --recursive
# Cone mode keeps root files (CMakeLists.txt, LICENSE, …) automatically —
# only list directories to include.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OC="$ROOT/native/third_party/opencv"
if [[ ! -d "$OC" ]]; then
  echo "OpenCV submodule not checked out at $OC" >&2
  exit 1
fi
cd "$OC"
git sparse-checkout init --cone
# Include `hal` — Carotene (NEON) lives there; OpenCV CMake also probes
# KleidiCV under hal/kleidicv when WITH_KLEIDICV is on.
git sparse-checkout set modules include 3rdparty cmake platforms hal
git checkout -- .
# OpenCV CMake always add_subdirectory(doc/data); those trees are huge and
# unused — plant no-op stubs so configure succeeds without checking them out.
for stub in doc data; do
  mkdir -p "$stub"
  printf '%s\n' '# Auto-generated stub for sparse OpenCV checkout' > "$stub/CMakeLists.txt"
done
echo "OpenCV sparse checkout applied under $OC"
du -sh "$OC" 2>/dev/null || true

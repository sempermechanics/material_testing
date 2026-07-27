#!/usr/bin/env bash
# Drop unused OpenCV trees from the submodule worktree (doc/samples/data/apps).
# Run from repo root after: git submodule update --init --recursive
# Cone mode keeps root files (CMakeLists.txt, LICENSE, …) automatically —
# only list directories to include.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OC="$ROOT/app/src/main/cpp/third_party/opencv"
if [[ ! -d "$OC" ]]; then
  echo "OpenCV submodule not checked out at $OC" >&2
  exit 1
fi
cd "$OC"
git sparse-checkout init --cone
git sparse-checkout set modules include 3rdparty cmake platforms
git checkout -- .
echo "OpenCV sparse checkout applied under $OC"
du -sh "$OC" 2>/dev/null || true

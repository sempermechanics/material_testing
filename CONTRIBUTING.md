# Contributing to inDIC

Thanks for helping. This repo is a single Android app (`:app`) with an optional
FastAPI backend. The correlation engine is C++ (on-device); most UI work is
Kotlin and needs no native toolchain.

## Clone and native deps

```bash
git clone <repo-url>
cd IndicVisionDIC
git submodule update --init --recursive
```

OpenCV's full tree is large. After the first submodule init, apply a sparse
checkout so unused `doc/`, `samples/`, `data/`, and `apps/` trees are dropped
(~100+ MB):

```bash
# From repo root — safe to re-run
./scripts/sparse-opencv.sh          # Git Bash / macOS / Linux
# or on Windows PowerShell:
.\scripts\sparse-opencv.ps1
```

Eigen stays a normal submodule (headers only, small).

### History rewrite (maintainers)

The old prebuilt OpenCV Android SDK under `app/src/main/cpp/opencv/` was
removed from HEAD but lingered in git history. That path (and orphaned
`README.pdf`) was purged with `git filter-repo`. **If you cloned before the
purge, re-clone** (or fetch and hard-reset to the rewritten tip). Do not merge
pre-rewrite local branches into the rewritten remote without rebasing onto the
new history. See [docs/ops/RELEASING.md](docs/ops/RELEASING.md).

### Local disk hygiene

Native OpenCV builds cache under `app/.cxx/` (~GB). Safe to delete when you
need space; the next native build recreates it:

```bash
rm -rf app/.cxx app/build
# Windows: Remove-Item -Recurse -Force app\.cxx, app\build
```

## First reads

1. [README](README.md) — what the product is and how to build
2. [docs/README.md](docs/README.md) — DIC primer, glossary, doc index
3. [docs/app/ARCHITECTURE.md](docs/app/ARCHITECTURE.md) — Activity flow and
   Kotlin package map (start here for Android UI changes)

## Build and test

```bash
# App (debug)
./gradlew :app:assembleDebug

# Kotlin unit tests + quality gates
./gradlew :app:testDebugUnitTest spotlessCheck :app:detekt :app:lintDebug

# Native engine tests (PC, no device)
cmake -S app/src/test/cpp -B build/native-tests -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-tests -j
./build/native-tests/dic_tests
```

On Windows use `gradlew.bat` instead of `./gradlew`.

Cloud features need `INDIC_API_BASE_URL` in `local.properties` and Firebase
setup — see [docs/backend/AUTH_SETUP.md](docs/backend/AUTH_SETUP.md). The engine
and local analysis work without it.

## Where to change what

| Area | Entry point |
|---|---|
| Android UI / sessions / viewer | [docs/app/ARCHITECTURE.md](docs/app/ARCHITECTURE.md) |
| C++ correlation engine | [docs/engine/ARCHITECTURE.md](docs/engine/ARCHITECTURE.md) |
| Sign-in / allow-list | [docs/backend/AUTH_SETUP.md](docs/backend/AUTH_SETUP.md) |
| GCP backend deploy | [docs/backend/BACKEND_SETUP_GCP.md](docs/backend/BACKEND_SETUP_GCP.md) |
| CI / release | [docs/ops/CI.md](docs/ops/CI.md), [docs/ops/RELEASING.md](docs/ops/RELEASING.md) |

Prefer extracting a `*Helper` / `*Runner` next to existing ones over growing a
god Activity. Do not introduce a DI framework for small helpers.

## Pull requests

- Keep PRs focused (one concern: dead-code cleanup, one helper extract, one feature)
- Match existing naming and package layout
- Run the relevant tests above before asking for review
- Link issues when applicable; `good first issue` tags are scoped for newcomers

## License

License to be finalized before public release. Until then, all rights reserved.

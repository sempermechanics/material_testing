# CI — path-filtered tiers (PR and push)

Defined in [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml).
Each tier runs only when the diff can break what that tier proves. The same
rules apply on pull requests and on pushes to `main` / `damodar`. There is
**no weekly/scheduled full matrix** — use the `full-ci` label or
**Actions → CI → Run workflow** (`workflow_dispatch`) for an on-demand full run.

```
changes ──┬──> tier1-app-fast ─────────────┐
          ├──> tier2-engine-host ──────────┤
          ├──> tier2-sanitizers ───────────┤
          ├──> tier3-emulator-e2e (*) ─────┤
          ├──> tier4-backend ──────────────┤
          └──> tier5-signed-release (*) ───┤
                                           └──> ci-ok

(*) Tier 3/5 need `changes` (+ optional Tier 1). They do **not** require Tier 1
    to have run — skipped Tier 1 no longer cascade-skips JNI/release jobs.
```

| Tier | Job | Proves | Typical (warm / cold) |
|------|-----|--------|-----------------------|
| **1** | `tier1-app-fast` | spotless, detekt, lint, JVM unit tests, `compileReleaseKotlin` | ~5–8 / ~10 min |
| **2a** | `tier2-engine-host` | Host C++ + OpenCV fixtures + perf floor | ~2 / ~3 min |
| **2b** | `tier2-sanitizers` | ASan+UBSan and TSan | ~5 / ~8 min each |
| **3** | `tier3-emulator-e2e` | x86_64 emulator: JNI smoke + Espresso UI | ~20–40 / ~60–90 min |
| **4** | `tier4-backend` | Backend pytest + ruff lint | ~2–5 min |
| **5** | `tier5-signed-release` | R8 + signed `assembleRelease` arm64 + `.so` check | ~15–40 / up to ~90 min |
| **6** | `ci-ok` | Single required status check — all tiers passed/skipped | seconds |

## Path filters

| Output | Paths (summary) | Tiers |
|--------|-----------------|-------|
| `app` | `app/**` except `app/src/main/cpp/**`, Gradle wrapper/catalog | 1 |
| `native_core` | `native/src`, `include`, `tests`, `cmake`, top-level CMake | 2a + 2b |
| `native_jni` | `native/adapters`, `app/src/main/cpp`, `SemperNativeLib.kt`, JNI headers (`io.hpp`, `pipeline.hpp`) | 2a + 2b + 3 + 5 |
| `backend` | `backend/**` | 4 |
| `full_ci` | `full-ci` label, `workflow_dispatch`, or `.github/workflows/ci.yml` edit | all |

| Diff class | T1 | T2a | Sanitizers | T3 | T4 | T5 |
|------------|----|-----|------------|----|----|-----|
| native-core only | — | run | run | — | — | — |
| native-jni (or core+jni) | — | run | run | run | — | run |
| app only | run | — | — | —† | — | —† |
| backend only | — | — | — | — | run | — |
| app + native | run | run | run | run | — | run |
| `full-ci` / CI workflow edit | run | run | run | run | run | run |

† Also with `e2e` / `release` / `engine` labels, or when instrumented tests / packaging force a wider run via `full-ci`.

Skipped jobs count as success for `ci-ok`. Empty base SHA (force-push / first commit) fails open and runs the full matrix.

## Required check

Set **`ci-ok`** as the single required status check in branch protection. It
gates on all tiers and treats skipped jobs as passing.

## On-demand full matrix

- PR label: `full-ci`
- Or: Actions tab → **CI** → **Run workflow** (defaults to full)

## Reproducing a failure locally

```bash
# Full local gate (mirrors tiers 1 + 5, no emulator)
./gradlew ciReleaseGate

# Individual tiers
./gradlew :app:testDebugUnitTest spotlessCheck :app:detekt :app:lintDebug   # tier 1
cmake -S native/tests -B build/native-tests -DDIC_REQUIRE_OPENCV=ON && cmake --build build/native-tests -j && ./build/native-tests/dic_tests   # tier 2a
cd backend && pip install -r requirements-test.txt && pytest tests/ -v   # tier 4
./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64              # tier 3 (emulator)
```

## Manual release

A separate `workflow_dispatch` workflow ([release.yml](../../.github/workflows/release.yml))
builds a signed release APK and publishes it as a GitHub Release. Requires the
`release` environment approval. See [RELEASING.md](RELEASING.md).

Release builds **require** `INDIC_API_BASE_URL` (GitHub Environment variable) and
pass `-PrequireCloudApi=true` so an empty URL cannot silently ship with cloud
sync disabled. Local `assembleRelease` without that flag still allows offline
inspection builds.

## Backend deploy

[`deploy-backend.yml`](../../.github/workflows/deploy-backend.yml) is
`workflow_dispatch` with separate **staging** and **production** GitHub
Environments. It:

1. Runs backend ruff + pytest.
2. Deploys from `backend/` (Dockerfile → immutable revision suffix = git SHA).
3. Records the revision image digest.
4. Smokes `GET /readyz` (Firestore + Drive).
5. On smoke failure, routes 100% traffic back to the previous revision.

Required per environment: secrets `GCP_WIF_PROVIDER`, `GCP_DEPLOY_SA`; vars
`FIREBASE_PROJECT_ID`, `SHARED_DRIVE_ID`, `SERVICE_ACCOUNT_EMAIL`,
`AUTO_APPROVE_HD`, `ADMIN_EMAILS`, `SUPPORT_EMAIL`, `NOTIFY_FROM`. Until those
exist in GitHub, treat deploy wiring as **UNKNOWN** (repo template only).

API Gateway OpenAPI uses placeholder `__CLOUD_RUN_URL__` — substitute at config
create time (see `backend/gateway/openapi.yaml`); never commit a live hostname.

## Caching

| Cache | Key pattern | Purpose |
|-------|-------------|---------|
| `app/.cxx` | `cxx-{arm64,x86_64}-<hash>` | ABI-specific CMake/ninja tree |
| `ccache` | `ccache-{arm64,x86_64,host-tests,san-*}-<sha>` | Compiled object cache (per ABI/sanitizer) |
| `~/.gradle` | managed by `setup-gradle` | Dependency/build cache |
| `~/apt-cache` | `apt-libopencv-dev-*` | libopencv-dev `.deb` archives |

### Keeping under the 10 GB limit

A repository gets 10 GB of Actions cache. The ccache and `.cxx` entries are
0.5–1 GB each and the ccache keys end in the commit SHA, so left alone every
push adds a fresh copy and GitHub starts evicting — blindly, by least recent
use, which is how the Gradle home cache disappears and every tier goes cold.

Two things keep that from happening:

* **Each caching job prunes its own prefix.** The last step of tiers 2, 3 and 5
  (and of the release build) runs
  [`.github/actions/prune-cache`](../../.github/actions/prune-cache/action.yml),
  which deletes the older entries under its key prefix on that ref just before
  `actions/cache` writes the new one. The key the job is about to save is passed
  as `except-key` so a cache *hit* — which is not rewritten — is never deleted.
  Net effect: one entry per prefix per branch, not one per commit. It is
  best-effort: a fork PR's read-only token makes it warn instead of fail.
* **[`cache-cleanup.yml`](../../.github/workflows/cache-cleanup.yml) sweeps the
  rest.** It drops a PR's caches when the PR closes, and nightly at 03:00 UTC it
  deletes caches of deleted branches and closed PRs, collapses superseded
  ccache/`.cxx` entries, then trims least-recently-used entries until total
  usage is under 8 GB.

Run the sweep by hand from the Actions tab — `Cache cleanup` →
**Run workflow** — with `dry-run` ticked to see what it would delete, or a
smaller `budget-gb` to claw back more space. To inspect usage yourself:

```bash
gh cache list --limit 100 --sort size_in_bytes --order desc
```

## Things that surprise people

**Only arm64-v8a is shipped.** Every phone from 2022 on is 64-bit ARM. CI builds
exactly what users get. Build for an emulator locally with `-PabiFilters=x86_64`.

**Sanitizer jobs skip OpenCV.** Linking an uninstrumented third-party library
produces leak/thread-pool noise. The synthetic suites cover the same engine paths.

**Wrapper validation is off in NDK jobs only.** OpenCV's repo bundles ancient
`gradle-wrapper.jar` files that fail checksum validation. Our wrapper is
validated by tier 1 when Tier 1 runs.

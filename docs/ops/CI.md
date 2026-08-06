# CI — path-filtered tiers (PR and push)

Defined in [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml).
Each tier runs only when the diff can break what that tier proves. The same
rules apply on pull requests and on pushes to `main` / `damodar`. There is
**no weekly/scheduled full matrix** — use the `full-ci` label or
**Actions → CI → Run workflow** (`workflow_dispatch`) for an on-demand full run.

```
secret-scan ─────────────────────────────┐
legal-pages ─────────────────────────────┤
changes ──┬──> tier1-app-fast ───────────┤
          ├──> tier3-emulator-e2e (*) ───┤
          ├──> tier4-backend ────────────┤
          └──> tier5-signed-release (*) ─┤
                                         └──> ci-ok

(*) Tier 3/5 need `changes` (+ optional Tier 1). They do **not** require Tier 1
    to have run — skipped Tier 1 no longer cascade-skips JNI/release jobs.
```

| Job | Proves | Path-filtered? | Typical (warm / cold) |
|-----|--------|---|-----------------------|
| `secret-scan` | gitleaks over the **full history**, config `.gitleaks.toml` | No — always runs | ~1–2 min |
| `legal-pages` | `scripts/render_legal_pages.py --check`: the published pages still match `docs/legal/` | No — always runs | seconds |
| `changes` | Resolves the path filters below into tier flags | — | seconds |
| `tier1-app-fast` | spotless, detekt, lint, JVM unit tests, `compileReleaseKotlin`, Kover coverage log | `app` | ~5–8 / ~10 min |
| `tier3-emulator-e2e` | x86_64 emulator: JNI smoke + `AnalysisWizardSmokeTest` | `native_jni` | ~20–40 / ~60–90 min |
| `tier4-backend` | ruff, shell-script parse, pip-audit, hashed-lock verification, pytest at `--cov-fail-under=75`, Firestore emulator suite | `backend` | ~5–10 min |
| `tier5-signed-release` | R8 + signed `assembleRelease` arm64, `.so` presence, signature verify, R8 mapping artifact | `native_jni` | ~15–40 / up to ~90 min |
| `ci-ok` | Single required status check — every job above passed or was skipped | — | seconds |

**There is no tier 2 here any more.** Host C++ builds, the DICe comparisons and
the ASan/UBSan and TSan suites moved to `semperdic/semper-dic-engine` along with
the engine sources. This repo only proves the pinned submodule still *links*:
arm64 in tier 5, x86_64 on the emulator in tier 3. `ci-ok` prints a reminder of
where the engine suites live.

`secret-scan` needs a `GITLEAKS_LICENSE` secret (gitleaks-action requires one for
organisation repositories). Without it the job fails, and because it gates
`ci-ok`, so does every PR.

## Path filters

| Output | Paths | Jobs |
|--------|-------|------|
| `app` | `app/**` except `app/src/main/cpp/**`; `gradle/**`, `*.gradle.kts`, `gradlew`, `gradlew.bat`, `settings.gradle.kts` | tier 1 |
| `native_core` | `native` (the gitlink itself) and `.gitmodules` | — see note |
| `native_jni` | `native`, `.gitmodules`, `app/src/main/cpp/**`, `SemperNativeLib.kt` | tiers 3 + 5 |
| `backend` | `backend/**` | tier 4 |
| `full_ci` | `full-ci` label, `workflow_dispatch`, or an edit to `.github/workflows/ci.yml` | all |

Because the engine is a submodule, `native_core` and `native_jni` now match the
**gitlink** `native` rather than a source tree — bumping the pinned engine
commit is what triggers them. `native_core` no longer has a job of its own; it
survives as an output because the engine repo's CI is what consumes core
changes.

Two further filters widen `app` rather than gating a job directly:
`app/src/androidTest/**` and the packaging files (`app/build.gradle.kts`,
`app/proguard-rules.pro`, `gradle/libs.versions.toml`).

| Diff class | T1 | T3 | T4 | T5 |
|------------|----|----|----|-----|
| engine pin bump | — | run | — | run |
| app only | run | —† | — | —† |
| backend only | — | — | run | — |
| app + engine pin | run | run | — | run |
| `full-ci` / CI workflow edit | run | run | run | run |

† Also with the `e2e`, `release` or `engine` label.

`secret-scan` and `legal-pages` are absent from that table on purpose: they carry
no path filter and run on every event.

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
./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64                # tier 3 (emulator)
cd backend && pip install -r requirements-test.txt && pytest tests/ -v      # tier 4

# The two always-on gates
python scripts/render_legal_pages.py --check                                # legal-pages
gitleaks detect --config .gitleaks.toml                                     # secret-scan
```

The engine's own suites are not reproducible from this repo — run them in
`semperdic/semper-dic-engine`, or from the submodule as described in
[docs/engine/TESTING.md](../engine/TESTING.md).

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
2. Deploys from `backend/` with **`no_traffic: true`**, tagging the new revision
   `cand-<run_id>-<run_attempt>`. The previous revision keeps serving.
3. Records the revision image digest.
4. Smokes `GET /readyz` on the **tagged candidate URL**, authenticated with an ID
   token whose audience is the service URL — production runs
   `--no-allow-unauthenticated`, so an unauthenticated probe would only prove the
   gateway rejects it.
5. Promotes the candidate to 100% traffic once the smoke passes.

Note the ordering: traffic never reaches an unproven revision, so a failed smoke
needs no rollback. The revision suffix includes the **run attempt** as well as
the run id, so re-running a failed job cannot collide with the revision name the
first attempt created.

Required per environment: secrets `GCP_WIF_PROVIDER`, `GCP_DEPLOY_SA`; vars
`FIREBASE_PROJECT_ID`, `SHARED_DRIVE_ID`, `SERVICE_ACCOUNT_EMAIL`,
`AUTO_APPROVE_HD`, `ADMIN_EMAILS`, `SUPPORT_EMAIL`, `NOTIFY_FROM`, and — for
async provisioning — `TASKS_QUEUE`, `TASKS_LOCATION`, `TASKS_TARGET_BASE_URL`,
`TASKS_INVOKER_SA`. Leave the `TASKS_*` set empty to provision inline; see
[BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) A6.

API Gateway OpenAPI uses the placeholders `__CLOUD_RUN_URL__` and
`__FIREBASE_PROJECT_ID__` — substitute both at config-create time (see
`backend/gateway/openapi.yaml`). The substituted output goes to
`backend/gateway/openapi.generated.yaml`, which is **gitignored**: never commit a
live hostname or project id.

## Data-protection workflows

Two more scheduled workflows guard Firestore, both documented in
[FIRESTORE_DATA_PROTECTION.md](../backend/FIRESTORE_DATA_PROTECTION.md):

| Workflow | When | What it does |
|---|---|---|
| [`firestore-backup.yml`](../../.github/workflows/firestore-backup.yml) | Daily, 02:17 UTC | Exports Firestore to a GCS bucket, writing a `manifest.json` beside it. Refuses to run unless PITR is on |
| [`firestore-restore-drill.yml`](../../.github/workflows/firestore-restore-drill.yml) | Monthly, 03:40 UTC on the 1st, plus on demand | Imports the latest export into a throwaway project, verifies it against the manifest, then purges it. Runs in its own `restore-drill` GitHub environment |

A backup nobody has restored is a hope, not a backup — that is why the drill is a
scheduled workflow rather than a runbook step.

## Caching

| Cache | Key pattern | Purpose |
|-------|-------------|---------|
| `app/.cxx` | `cxx-arm64-<hash>` (tier 5), `cxx-x86_64-<hash>` (tier 3) | ABI-specific CMake/ninja tree |
| `ccache` | `ccache-arm64-<sha>`, `ccache-x86_64-<sha>` | Compiled object cache, per ABI |
| `~/.gradle` | managed by `setup-gradle` | Dependency/build cache |
| pip | managed by `setup-python`, keyed on `backend/requirements-test.txt` | Backend test dependencies |

The `host-tests` and `san-*` ccache prefixes are gone with the engine tiers, as
is the `apt-libopencv-dev-*` cache — nothing in this repo installs
`libopencv-dev` any more.

### Keeping under the 10 GB limit

A repository gets 10 GB of Actions cache. The ccache and `.cxx` entries are
0.5–1 GB each and the ccache keys end in the commit SHA, so left alone every
push adds a fresh copy and GitHub starts evicting — blindly, by least recent
use, which is how the Gradle home cache disappears and every tier goes cold.

Two things keep that from happening:

* **Each caching job prunes its own prefix.** The last step of tiers 3 and 5
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

**Wrapper validation is off in NDK jobs only.** OpenCV's repo bundles ancient
`gradle-wrapper.jar` files that fail checksum validation, and those jobs check
out submodules recursively. Our own wrapper is validated by tier 1 when tier 1
runs.

**Two jobs run on every single event.** `secret-scan` and `legal-pages` carry no
path filter, so a documentation-only PR still runs them — and can still be
blocked by them, which is the point.

**A green backend tier means more than pytest.** Tier 4 also audits dependencies
with pip-audit, proves `requirements.lock` resolves under `--require-hashes` on
Python 3.12 and still covers every direct dependency, and runs the concurrency
tests against a real Firestore emulator. Those paths are structurally untestable
against the in-memory fake.

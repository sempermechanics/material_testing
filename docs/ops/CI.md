# CI — what runs on every push

Defined in [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml). Six
tiers run on every push to `main` and `damodar`, and on pull requests.
Long-running tiers (emulator, signed release) start in parallel after tier 1 so
the wall clock is the **max** of the two, not the sum.

```
tier1-app-fast ──┬──> tier3-emulator-e2e ──┐
                 └──> tier5-signed-release ─┤
tier2-engine-host ──────────────────────────┤
tier2-sanitizers ───────────────────────────┤
tier4-backend ──────────────────────────────┤
                                            └──> ci-ok
```

| Tier | Job | Proves | Typical (warm / cold) |
|------|-----|--------|-----------------------|
| **1** | `tier1-app-fast` | spotless, detekt, lint, JVM unit tests, `compileReleaseKotlin` | ~5–8 / ~10 min |
| **2a** | `tier2-engine-host` | Host C++ + OpenCV fixtures | ~2 / ~3 min |
| **2b** | `tier2-sanitizers` | ASan+UBSan and TSan matrix | ~5 / ~8 min each |
| **3** | `tier3-emulator-e2e` | x86_64 emulator: JNI smoke + Espresso UI | ~20–40 / ~60–90 min |
| **4** | `tier4-backend` | Backend pytest + ruff lint | ~2–5 min |
| **5** | `tier5-signed-release` | R8 + signed `assembleRelease` arm64 + `.so` check | ~15–40 / up to ~90 min |
| **6** | `ci-ok` | Single required status check — all tiers passed/skipped | seconds |

## Path filters

On push to `main`/`damodar`, all tiers always run. On PRs, expensive tiers
(emulator, signed release, engine) can be skipped when the change set does not
affect them. Skipped jobs count as success for `ci-ok`.

## Required check

Set **`ci-ok`** as the single required status check in branch protection. It
gates on all tiers and treats skipped jobs as passing.

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
validated by tier 1.

# Tech debt

What is **owed** or **deliberately deferred**, and nothing else. A row is removed
when it is fixed or closed. Its history stays in git
(`git log -S'TD-NN' -- docs/ops/TECH_DEBT.md`), and what shipped is in
[CHANGELOG.md](CHANGELOG.md). Forward-looking ideas are in
[FUTURE_IMPROVEMENTS.md](FUTURE_IMPROVEMENTS.md).

**Re-verified against the code on 2026-10-08** at `main` @ `8ae203ce`. Evidence is
`path:line`, from `app/src/main/java/com/sempermechanics/semper/` unless it starts
at the repo root. On that date, the 87 rows that were fixed or closed were removed:
TD-3, 24–28, 33–35, 37–44, 46–68, 70–99, 133, 134, 136–138, 140–144 and 146–152.

TD numbers are shared with semperdic-app; check both registers before taking one
(the next free in both is TD-201). TD-199 means two things: here it was the
files over 500 lines, closed by #123. In the parent it is the test-heap fix in
its #350.

## Lint and detekt

Baselines stay empty: `app/lint-baseline.xml` and `app/detekt-baseline.xml`.
`./gradlew :app:lintDebug` fails on errors.

**Open warnings** (do not baseline, do not `warningsAsErrors` until decided):

- `OldTargetApi`: disabled in `app/build.gradle.kts:292` until a deliberate
  `targetSdk` 36→37 bump PR. Do not re-enable casually.
- Three lint warnings in lab code: `InflateParams` at `ui/analysis/load/TypedLoadsSheet.kt:100`,
  and `PluralsCandidate` on `results_yield_fmt` and `video_keyframes_estimate_fmt`
  (`app/src/main/res/values/strings.xml:800`, `:1028`).

Size and complexity findings are silenced with targeted `@file:Suppress`, not a
baseline. Prefer extracting over widening those lists. On 2026-10-08, 65 main
files carry `@file:Suppress`: most often `MagicNumber` 30, `ReturnCount` 21,
`TooManyFunctions` 21, `LongParameterList` 12 and `TooGenericExceptionCaught` 10.
Another 10 carry `@file:SuppressLint`. Catalog version-availability lint IDs are
disabled; bump dependencies in deliberate PRs.

Orphaned strings from the 2026-08-18 workflow audit are listed in
[../app/WORKFLOWS.md](../app/WORKFLOWS.md) §11. None of them fails a gate, so they
are removed as they are found, not in a sweep.

## Open register

Priority = (Impact + Risk) × (6 − Effort).

| ID | Category | Item | I | R | E | P | Status |
|----|----------|------|---|---|---|---|--------|
| TD-29 | App | Two auth continue hosts: `AUTH_HOST` / `LEGACY_AUTH_HOST` (`data/account/AuthLinks.kt:25`, `:32`; the set at `:40`). The manifest has four App Link filters over the two hosts (`app/src/main/AndroidManifest.xml:67-109`; legacy at `:98`, `:107`; its comments at `:64`, `:76` still say the URLs live in `AuthRepository`). `AuthHostsTest.kt:17` pins the legacy host | 1 | 2 | 2 | **8** | Deferred until Play vitals show no legacy-only build installed |
| TD-30 | Ops | The API Gateway runs in asia-northeast1 (`.github/workflows/deploy-backend.yml:439`), Cloud Run in asia-south1 (`:34`). API Gateway has no asia-south1 location, so every call crosses Tokyo↔Mumbai. Over 14 days the gateway added p50 135 ms / p95 1.0 s | 2 | 1 | 3 | **9** | **Accepted, deferred.** The legacy `indic-gw` / `indic-api` pair is gone (checked 2026-09-24). Two options wait for a latency complaint or a public launch, and both need an app release: new builds call Cloud Run directly, or a global HTTPS load balancer with a serverless NEG (about $20–25 a month). See [../perf/backend-cost.md](../perf/backend-cost.md) |
| TD-36 | Legal | 10 bracketed operator fields in `docs/legal/PRIVACY_POLICY.md` (:5 ×2, :7 ×2, :18, :153) and `docs/legal/TERMS_OF_SERVICE.md` (:6 ×2, :438, :439) are published as literal text on the hosted pages (`firebase-hosting/public/privacy/index.html:25`, `:31`, `:102`; `firebase-hosting/public/terms/index.html:25`, `:117`; live on 2026-10-08). `render_legal_pages.py --check` does not catch them | 2 | 5 | 1 | **35** | Operator task (the values). Listed in [PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md) |
| TD-45 | Backend | Four compat shims from the `campus`→`institution` / `plan`→`mode` rename remain (CLOUD_ARCHITECTURE_GCP §20.5), all waiting on the installed-app fleet. (6) The app reads `config.plan` when `mode` is neither licensed nor demo (`data/net/AppRemoteConfig.kt:198-203`, `data/net/ApiDtos.kt:75`). (7) The app falls back to the old `plan` pref key (`AppRemoteConfig.kt:215-224`; key at `data/prefs/PrefFiles.kt:121`). (8) The backend writes a `plan` mirror: `_mode_patch` (`backend/app/repo/_base.py:126-134`), called from `repo/claims.py`, `repo/devlock.py`, `repo/license_admin.py`, `repo/mint.py` and `repo/seats.py`; `/v1/config` (`repo/user_config.py:219`); licence summaries and mint docs (`repo/mint.py:61`, `:127`); and `backend/app/licenses.py:81`. (9) Stored `plan` / `campus` values are still read: `normalize_mode` / `normalize_kind` (`licenses.py:86`, `:98`), `_license_mode` and `_is_institution` (`repo/_base.py:137-142`, `:156-158`) | 2 | 2 | 2 | **16** | Open; shims 1–5 were retired on 2026-09-26. Remove 6 and 7 together when Play Console shows a `mode`-reading build is the whole fleet, then 8. Remove 9 when migration 002 has reached every document and 8 has stopped writing the mirror (no users without `mode`, no licences with `kind == "campus"`) |
| TD-69 | Build | Every Gradle run warns "Deprecated Gradle features … incompatible with Gradle 10". It comes from one call, `ReportingExtension.file(String)` in detekt `1.23.8`'s `DetektPlugin.apply` (`gradle/libs.versions.toml:31`); nothing in our build scripts. The wrapper is 9.8.0 and cannot move to 10 until detekt fixes it | 1 | 2 | 1 | **15** | Open. Check by hand: Dependabot's Gradle group ignores minor and patch updates (`.github/dependabot.yml:31-48`), and detekt 2 is a new group id (`dev.detekt`). On 2026-10-08, 1.23.8 is still the newest 1.x and `dev.detekt` is at `2.0.0-alpha.6`. Do not bump the wrapper to 10 before then |
| TD-135 | Performance | The Pixel 6 (`oriole`) startup references in `benchmark/gates.json` (wizard cold start 369 ms at `:14`; cold 378, warm 81) were taken on 2026-09-25 with nothing recorded about the phone's state. On 2026-09-26 the same build read +23 % and a reference build failed its own gate in 2 of 4 rounds as the phone warmed. So a trip cannot be told from the phone's state | 2 | 2 | 2 | **16** | **Partly fixed** ([ADR-008](../adr/ADR-008-startup-gates-phone-state.md)): `scripts/startup_ab.py` A/B runs (10 % `abMargin`); `DeviceStateRule` and the gates' `state` check (thermal 0, plugged in); 15 cold-start iterations (`StartupBenchmark.kt:60`). Open: re-take the `oriole` references under that protocol and record the phone's state in the label. The parent's 2026-10-05 re-take was of its own app and is not copied here |
| TD-139 | UX (lab) | The viewer draws each frame on its own photo at the displaced positions ([ADR-011](../adr/ADR-011-viewer-deformed-frame.md); `ui/viewer/ViewerScaleController.kt:144-145` → `report/DeformedHeatmap.kt:40`). The exports still draw every frame at the reference positions: the summary GIF (`ui/viewer/summary/SummaryAnimation.kt:249`, over its palette background), the PNG photo and package exports (`ui/viewer/share/FieldImageExport.kt:52`, over the reference at `:114`), and the report heatmap (`report/ReportBuilder.kt:209`, over `baseImg` at `:230-232`). A student sees the beam bend on screen and not in what they hand in | 2 | 1 | 3 | **9** | Open. Decide whether exports follow the screen. The GIF's bytes are pinned, so moving it needs a new oracle; the report heatmap sits inside ReportBuilder's fusion pass, which is not to be split |
| TD-145 | App | Settings → Account reads "Licensed as SEMP-…" on an account that runs as Demo. `/v1/config` sends `licensePrefix` whatever the mode (`backend/app/repo/user_config.py:248`, from `license_summary` at `:266`). So a Demo key, a revoked or lapsed licence, a floating seat with no lease, or an account demoted by a device lock all send one. The app shows the row whenever the prefix is non-empty (`ui/settings/SettingsAccountSection.kt:31`); its comment at `:25-28` says the prefix is empty on Demo. Seen on the `v1.2-beta.3` Pixel 6 smoke (2026-09-26). It is semperdic-app's row, open there too with the same code | 2 | 1 | 1 | **15** | Open. Show the row only when `LicenseEntitlements.isLicensed` (`data/account/LicenseEntitlements.kt:39`), or word a held-but-inactive licence differently; fix the comment |

## Deferred by design

| Item | Why |
|---|---|
| Auth-gated UI E2E | Needs Firebase test credentials in CI. `FirebaseAuthIntegrationTest` skips itself without `FIREBASE_TEST_EMAIL` / `FIREBASE_TEST_PASSWORD` (`app/src/androidTest/java/com/sempermechanics/semper/auth/FirebaseAuthIntegrationTest.kt:27-33`). CI's Tier 3 passes neither, and no workflow has such a secret |
| `StartupTimingMetric` benchmarks on an API 37 emulator | `startActivityAndWait` confirms launches through `dumpsys gfxinfo framestats`, which is empty there for every activity. `StartupBenchmark` / `ScreenBenchmark` need a phone or an older image; `ViewerScrubBenchmark` avoids the API and runs |
| Float16 / ZNSSD quantisation for field data | Would change reported numbers; rejected under the bit-exactness requirement |
| In-memory X/Y compaction (derive coordinates from the grid) | Lossless and worth about 25 %, but a larger change that also touches the native writer |

## Checked by hand, not from the repository

Branch protection requiring `CI OK`; API-key restrictions; GitHub Environment branch
rules on `production`. The open checkboxes are in
[PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md).

## Perf and quality gates (do not loosen)

See [../../engine/docs/PERF_BASELINE_bd44af0.md](../../engine/docs/PERF_BASELINE_bd44af0.md):
≥ 4557 solves/s on the host, the smoke and DICe floors, and `-O3 -ffast-math` / OpenMP /
LTO kept on the release pipeline. **No CI job in this repository enforces the
solve-rate floor:** it is checked by hand in the engine repo when the `engine` pin
moves. `koverVerify` (floor 49) runs in CI tier 1 and `ciReleaseGate`.

The CI benchmark job ("Benchmarks (macro + micro)") is emulator **smoke**, with no
numeric thresholds. Macrobenchmark suppresses `EMULATOR,LOW-BATTERY,UNLOCKED`.
`HotPathMicroBenchmark` suppresses
`EMULATOR,DEBUGGABLE,LOW-BATTERY,UNLOCKED,ACTIVITY-MISSING,NOT-AOT-COMPILED`. Keep API 34:
API 37's `dumpsys gfxinfo framestats` is empty. Run it with the `run_benchmark`
dispatch input or the `benchmark` label.

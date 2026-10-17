# Tech debt

What is **owed** or **deliberately deferred**, and nothing else. A row is removed
when it is fixed or closed. Its history stays in git
(`git log -S'TD-NN' -- docs/ops/TECH_DEBT.md`), and what shipped is in
[CHANGELOG.md](CHANGELOG.md). Forward-looking ideas are in
[FUTURE_IMPROVEMENTS.md](FUTURE_IMPROVEMENTS.md).

**Re-verified against the code on 2026-10-08** at `main` @ `070eb330`, and live
with `gcloud` and `gh` where a row is about deployed state. Evidence is
`path:line`, from `app/src/main/java/com/sempermechanics/semper/` unless it starts
at the repo root (or `backend/app/` for a `repo/`, `routers/` or other backend
module). On that date, the 81 rows that were fixed or closed were removed:
TD-3, 24–28, 33–35, 37–44, 46–64, 66–68, 70–77, 79–88, 90, 97, 133, 134, 136–138,
178–184, 186–193, 195 and 196. TD-65 followed the same day: the `steel_24` fixture
(→ `steel_26`, `steel_27`; subset 21, step 5) gave the same `.dat` SHA-256 four
times on one emulator. The line cites in TD-29, TD-45, TD-176, TD-198, TD-209 and
the gates below were re-checked on 2026-10-14 at `main` @ `8ba7081c`, after #428–#435
closed TD-139, 153, 154, 163, 164, 169, 170, 173, 197 and 208.

TD numbers are shared with material_testing; check both registers before taking
one ([FORK_SYNC.md](FORK_SYNC.md)). The next free in both is TD-213 (TD-212 taken 2026-10-17; TD-211 taken and closed
2026-10-15; checked 2026-10-14; material_testing's highest is TD-201). Its own open rows not listed
here are TD-135, TD-139 and TD-145 (fixed here); TD-29,
TD-30, TD-36, TD-45 and TD-69 are open in both (checked against its `main` @
`12f2e7d` on 2026-10-08). TD-199 means two things: here it was the unit-test
heap, fixed by #350 except two holders, which were TD-200 and are fixed too;
there it was the files over 500 lines, closed by its #123.

## Lint and detekt

Baselines stay empty: `app/lint-baseline.xml` and `app/detekt-baseline.xml`.
`./gradlew :app:lintDebug` fails on errors.

**Open warnings** (do not baseline, do not `warningsAsErrors` until decided):

- `OldTargetApi`: disabled in `app/build.gradle.kts:320` until a deliberate
  `targetSdk` 36→37 bump PR. Do not re-enable casually.

Size and complexity findings are silenced with targeted `@file:Suppress`, not a
baseline. Prefer extracting over widening those lists. On 2026-10-08, 58 main
files carry `@file:Suppress`: most often `MagicNumber` 25, `ReturnCount` 20,
`TooManyFunctions` 19, `LongParameterList` 12, `TooGenericExceptionCaught` 9,
`NestedBlockDepth` 6, `CyclomaticComplexMethod` 6 and `LongMethod` 5. Another 8
carry `@file:SuppressLint`. Since the baselines are empty, suppression is the only
thing keeping those findings quiet. Catalog version-availability lint IDs are
disabled; bump dependencies in deliberate PRs.

## Open register

Priority = (Impact + Risk) × (6 − Effort).

| ID | Category | Item | I | R | E | P | Status |
|----|----------|------|---|---|---|---|--------|
| TD-29 | App | Two auth continue hosts (`AUTH_HOST` / `LEGACY_AUTH_HOST`, `data/account/AuthLinks.kt:25,32`, both in `AUTH_HOSTS` at `:40`; four `autoVerify` App Link filters over two hosts in the manifest, `app/src/main/AndroidManifest.xml:92`, `:103` for `app.sempermechanics.com` and `:117`, `:126` for the `firebaseapp.com` host; `AuthHostsTest.kt:17` pins the legacy host) | 1 | 2 | 2 | **8** | Deferred until Play vitals show no legacy-only build installed |
| TD-30 | Ops | API Gateway in asia-northeast1 (`semper-gw`, `semper-gw-staging`) vs Cloud Run in asia-south1 (`semper-api`, `semper-api-staging`), checked live 2026-10-08. The legacy gateway and API resources are gone; the `indic-gw@` and `indic-api@` service accounts are not, and still run both (`deploy-backend.yml:523`) | 2 | 1 | 3 | **9** | **Accepted** (checked 2026-09-24). The legacy pair is gone: `gcloud api-gateway apis list` shows only `semper-api` and `semper-api-staging`, `gcloud run services list` only `semper-api` and `semper-api-staging`, and `gcloud endpoints services list` has no `indic-api` service. API Gateway offers no asia-south1 location (its locations API lists asia-northeast1 as the only Asian region), so the gateway cannot move next to Cloud Run. Measured over 14 days (`serviceruntime.googleapis.com/api/request_latencies_*`, service `semper-api`): gateway overhead p50 135 ms / p95 1.0 s, backend p50 0.71 s / p95 1.7 s, where backend includes the Tokyo→Mumbai leg. Removing the hop means replacing API Gateway, which needs an ADR, not a fix. **2026-09-26 (cost review):** kept, because the gateway costs nothing at 1,492 calls in 30 days. Production now scales to zero, so a cold start (p50 3.9 s, p95 6.0 s) matters more than this hop. Two options are deferred until a latency complaint or a public launch: (a) new builds call Cloud Run directly, and the gateway is retired once its traffic is about zero; (b) a global HTTPS load balancer with a serverless NEG, about $20–25 a month. Both need an app release ([perf/backend-cost.md](../perf/backend-cost.md)) |
| TD-36 | Legal | 10 bracketed operator fields in `docs/legal/PRIVACY_POLICY.md` (:5 ×2, :7 ×2, :18, :153) and `TERMS_OF_SERVICE.md` (:6 ×2, :438, :439) are published as literal text on the hosted pages | 2 | 5 | 1 | **35** | Operator task (values); listed in [PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md) |
| TD-45 | Backend | Compat shims from the `campus`→`institution` / `plan`→`mode` rename (CLOUD_ARCHITECTURE_GCP §20.5). Four remain, all waiting on the installed-app fleet, which cannot be measured yet: (6) the app reads `config.plan` when `mode` is empty (`AppRemoteConfig.kt:198-203`, `ApiDtos.kt:79`); (7) the app falls back to the old `plan` pref key (`AppRemoteConfig.kt:215-224`); (8) the backend writes a `plan` mirror into `/v1/config`, licence summaries and user documents (`repo/_base.py:222-230` `_mode_patch`, read back by `_license_mode` at `:233-237`; `repo/user_config.py:221` in `/v1/config`, written to the user at `:318` and deleted at `:322`; `repo/mint.py:64,130`; `licenses.py:81` `legacy_plan`); (9) `normalize_mode` / `normalize_kind` read `plan` / `campus` values in stored documents (`licenses.py:86,98`) | 2 | 2 | 2 | **16** | **Partly fixed.** Backend quick-wins PR: the never-called `/v1/campus/*` invite-revoke alias deleted; `LICENSE_ACTIVATE` audits `mode`. 2026-09-26: shims 1–5 retired on signals checked live that day — `PRO_MAX_SESSIONS_PER_USER` read (on neither Cloud Run service), the ID-token-only `/uploads` read and `REQUIRE_ATTESTED_UPLOADS` (0 `legacy_unattested_uploads` in 30 days of logs), `kind="campus"` at mint and the `plan` config patch (now 422s; no tooling sends them, 0 calls to the route), and the four `/v1/campus/*` seat routes in the router and gateway spec (0 requests in 30 days). Open: 6–9. Signals — 6/7/8: Play Console version distribution shows a `mode`-reading build is the fleet (6 ships with 7); 9: migration 002 has reached every document and #8 stopped writing the mirror (a Firestore count of users with no `mode` and licences with `kind == "campus"` is zero) |
| TD-69 | Build | Every Gradle run warns "Deprecated Gradle features were used … incompatible with Gradle 10". `--warning-mode all --stacktrace` traces it to one call: `ReportingExtension.file(String)` from `io.gitlab.arturbosch.detekt.DetektPlugin.apply` (`DetektPlugin.kt:28`), detekt `1.23.8` (`gradle/libs.versions.toml:31`); nothing in our build scripts. Gradle 10 removes the method, so the wrapper cannot move to 10 until detekt ships a fix | 1 | 2 | 1 | **15** | Open (found 2026-09-24): wait for a detekt release that drops the call. Do not bump the wrapper to 10 before then. Dependabot will not propose it: the gradle ecosystem ignores minor and patch updates (`.github/dependabot.yml`), and detekt 2 is a different id, so check by hand. Re-checked 2026-10-08 on Maven Central: the newest 1.x is still `1.23.8`; the only newer line is detekt 2.0 under `dev.detekt`, at `2.0.0-alpha.6` |
| TD-155 | Performance | Three of Semper's real-device benchmark tests have no Pixel 6 reference, so `scripts/ci_test_report.py --gates` never gates them: `StartupBenchmark.coldStartup`, `StartupBenchmark.warmStartup` and `ScreenBenchmark.analysisWizardColdStartup` (`benchmark/gates.json`, `oriole`). On 2026-10-05 (benchmark build of `d4b86d91`, signed in, on AC) the phone held thermal status 0 for 3 minutes before each class, and these three ended at status 1 (cold start 609 ms, warm start 62 ms, wizard 820 ms); so did 5 of 8 such class runs earlier that day. The other four tests stayed at 0 and are the references now. `gates.json` gates a result only at `"maxThermalStatus": 0` ([ADR-008](../adr/ADR-008-startup-gates-phone-state.md)) | 1 | 2 | 2 | **12** | Open. Retry the three on a cooler phone: one class per `am instrument` call, pulling its `*benchmarkData.json` and `*deviceState.json` before the next (each call overwrites both), after the phone holds status 0, and add each test that stays at 0 on the charger to `oriole`. If the cold start never does, decide whether this phone gates startup at status 1 |
| TD-171 | Code | detekt never checks cancellation handling. `SuspendFunSwallowedCancellation` (detekt's coroutines rule set, off by default) needs type resolution, but CI runs plain `:app:detekt` (`.github/workflows/ci.yml:393`) on the default config (`buildUponDefaultConfig`, no config file; `app/build.gradle.kts:394-399`), so a `runCatching` around a suspend call (TD-41) is caught only in review ([ADR-018](../adr/ADR-018-error-convention.md)). Type resolution does not work on detekt 1.23.8 here (tried 2026-10-09): AGP 9's built-in Kotlin leaves no `detektMain`/`detektDebug` task, and a hand-registered `Detekt` task given the debug `compileClasspath` plus the SDK boot classpath reads none of it, because detekt's embedded Kotlin 2.0 compiler rejects the dependencies' metadata ("binary version of its metadata is 2.4.0, expected version is 2.0.0", for `kotlin-stdlib-2.4.20` and every other Kotlin jar). Every stdlib call then goes unresolved, a probe `suspend fun p() = runCatching { delay(1) }` included ("unresolved reference: runCatching"), and the rule reports nothing. | 1 | 2 | 2 | **12** | Open. Needs detekt 2.x: `dev.detekt` 2.0.0-alpha.6 (Maven Central, 2026-08) is built on Kotlin 2.4.10, so it should read this metadata; it renames the plugin id, config and some rules, so it is a migration of the main gate too, or a separate CLI run for this one rule |
| TD-172 | Code | `NoiseFloorProbe`, `NoiseFloorStats` and `NoiseFloorPixels` (871 lines, `ui/analysis/recommend/`) have no production caller: outside those three files they are named only in KDoc. They estimate the measurement floor from a burst of static test frames (`NoiseFloorProbe.kt:17-28`); they stay because `NoiseFloorProbeTest`, `NoiseFloorStatsTest`, `NoiseCorrelationTest` and `NoiseFloorProbeParamsTest` back them and [NOISE_FLOOR_STRAIN_ACCURACY.md](../app/NOISE_FLOOR_STRAIN_ACCURACY.md) describes them (kept on purpose in #320) | 1 | 1 | 1 | **10** | Open, by choice (owner kept it 2026-10-13): the three files and their tests stay. Revisit if an import should supply a measured floor (the CONTEXT trap says none does) |
| TD-176 | App / backend | The move to `com.sempermechanics.semper` ([ADR-019](../adr/ADR-019-sempermechanics-app-id.md)) keeps the old ids working: the engine forwarders `Java_com_indicvision_semper_*` (six of them, engine `adapters/android/jni/SemperJNI.cpp:256-289`, at `1284edf`), the old ids in `backend/app/apps.py:40-41` and their registration refusal (`apps.RETIRED`, `:51`; `410 app_retired`), the old packages in `firebase-hosting/public/.well-known/assetlinks.json:24-44`, the `INDIC_*` fallbacks (`app/build.gradle.kts:35-41`) and `vars.INDIC_API_BASE_URL` (`.github/workflows/release.yml:130-132`; the repo has `INDIC_API_BASE_URL` and no `SEMPER_API_BASE_URL`, so that fallback is in use). Each is dead once no installed build sends an old id. The access log now names the id each call sends (`appId`: `backend/app/apps.py:73`, `observability.py:20`, `main.py:222`; a listed id, `none` or `unknown`) | 1 | 2 | 1 | **15** | Open. Logging the id is done; remove them once the log shows no old-id call. Query: `gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="semper-api" AND jsonPayload.appId=~"^com\.indicvision\."' --freshness=30d --limit=20`. It sees only lines written after the deploy that adds `appId`; older lines are `textPayload`, with no `appId`. Rename the repo variable before dropping the `release.yml` fallback |
| TD-177 | Performance | Three small slowdowns after the quality program held on the Pixel 6 in both rounds of the 2026-10-05 A/B, whichever build ran first ([QUALITY_PROGRAM_RESULTS.md](QUALITY_PROGRAM_RESULTS.md) §7.1, `ff0cfc3d` against `691fbdd5`): warm start's first frame +13 ms, 62 → 75 and 59 → 72 ms (`benchmark/src/main/java/com/sempermechanics/semper/benchmark/StartupBenchmark.kt:43`, Home's restart path in `ui/home/HomeActivity.kt`); `generateHeatmap` +5 %, 19.0 → 19.7 / 20.2 ms (`report/VisualizationEngine.kt:107`, whose pixel loop was moved whole into `HeatmapRenderer`); `buildReport` +2.3 %, 193 → 198 ms (`report/ReportBuilder.kt:133`). Allocation counts are unchanged. The micro times are from the debuggable build | 1 | 1 | 2 | **8** | Open. Run the three alone and interleaved (`scripts/startup_ab.py` for the warm start) on a release-like build; if they persist, compare a Perfetto trace of Home's warm start and the moved heatmap call path against `ff0cfc3d` |
| TD-198 | Backend / perf | Small items from the 2026-10-05 performance pass, not taken: ECDSA verification and the body hash run on the event loop in `verified_device` (`backend/app/deps.py:346` body read, `:355` hash, `:357-359` ECDSA verify), about 0.1 ms for the signature and up to a few ms for a 4 MiB body; listings read whole documents where `select()` would do (`list_user_sessions`, `repo/sessions.py:288`, reads at `:311` and `:318`; `repo/reconcile.py:103`, `:112`); `observability.classify_route` re-matches a path Starlette already routed (`backend/app/observability.py:78`, `:86`); `_search_licenses` runs its three queries one after another (`backend/app/repo/license_admin.py:82`) | 1 | 1 | 1 | **5** | Open, by choice. Each is under the 5 % floor of `docs/perf/request-volume.md`; `select()` also needs projection support in `tests/fake_firestore.py` |
| TD-207 | Video | On the retriever rung, a video frame's clip time (Home's `Video · 40 frames, 0:00–0:12`, the viewer's `0:01.25`) is the time the frame was asked for, not the decoded frame's own: it records its sample (`imaging/video/VideoFrameExtractor.kt:286`) while `getFrameAtTime` returns the closest frame (`:314-315`) and cannot report that frame's time. Only a clip the hardware decoder refuses reaches this rung; the hardware and AVI rungs record each frame's own presentation time (`DecodedFrame`, `imaging/video/FrameSink.kt:91`) | 1 | 1 | 2 | **8** | Open. Accept, or read the time of the frame nearest each sample from a `MediaExtractor` sample walk, as the key-frame lookup does |
| TD-209 | Backend / App | One public key per device id, whatever the app: `devices/{deviceId}` is written by `register_device` (`backend/app/repo/devices.py:80`, write at `:106`) and read by `verified_device` (`backend/app/deps.py:327`, key at `:357`), and the id is `and-{ANDROID_ID}` (`data/account/DeviceKeys.kt:62`), which Android shares between apps signed with one key. Two such apps on one phone (debug builds of Semper and Material Testing) take the key from each other, and the other's signed calls fail `401 bad_signature` (`deps.py:363`). Bounded since 2026-10-10: retired `com.indicvision.*` ids may not register (`backend/app/apps.py:51`, `routers/devices.py:18`; `410 app_retired`, live in production since the 2026-10-10 deploy, run 38032569176), and a refused call registers again and is re-sent once, at most once a minute per process (`data/net/SemperApiCalls.kt:68`, `data/net/DeviceKeyRecovery.kt:84`) | 1 | 2 | 3 | **9** | Open. Release builds of the two apps have different signing keys, so only testers meet it. A fix keys the device by app (`devices/{app}:{deviceId}`, or an app field checked at verification) and needs a migration of `devices/` on the production database staging shares, plus every reader of `get_device` (`device_in_use`, erasure, the consoles' device history) |
| TD-210 | Backend / perf | A Firestore transaction's begin and commit take no timeout in google-cloud-firestore's public API (2.32.0, `backend/requirements.txt:4`), so they keep the client's 60 s default, the gateway's whole deadline, and a contended transaction may begin and commit up to `_TX_ATTEMPTS` (10, `backend/app/repo/_base.py:47`) times. The reads inside a transaction do carry the 10 s read deadline (#432: `_get(ref, transaction)`, `repo/_base.py:90-96`; constants `:66-70`). Every transaction is created in `_run_tx` (`repo/_base.py:169`) for one of 13 `@firestore.transactional` bodies (`repo/claims.py:194`, `:288`; `repo/devices.py:144`; `repo/devlock.py:142`; `repo/leases.py:86`, `:144`, `:204`; `repo/mint.py:219`; `repo/seats.py:230`, `:280`, `:334`; `repo/sessions.py:208`, `:537`). Recorded as not covered in [CLOUD_ARCHITECTURE_GCP.md](../backend/CLOUD_ARCHITECTURE_GCP.md) §10.1 | 1 | 1 | 2 | **8** | Open. Revisit when the client exposes a timeout on begin and commit, or wrap the transactional call in an overall deadline |
| TD-212 | Perf / docs | The on-device characterization was measured with the AKAZE-seeded engine (pin `1284edf`): its seeding row (`docs/perf/on-device-characterization.md:65`) and the step=20 note (`:45`) describe a seeder engine 0.3.0 replaced with the anchor lattice. The note at `:10` says so, but no number for the current pin exists | 1 | 1 | 2 | **8** | Open. Re-run `SyntheticWorkloadDriver` LIGHT and LARGE on the Pixel 6 at the 0.4.2 pin (0.4.2 adds the log-polar estimate to the seeding row) and replace the tables |

## Deferred by design

| Item | Why |
|---|---|
| Auth-gated UI E2E | `FirebaseAuthIntegrationTest` signs the CI test account in on Tier 3 (TD-200), but through the SDK: no test drives the sign-in screens against real Firebase, and one that did would need the emulator's keyboard and the email-link flow's mailbox |
| `StartupTimingMetric` benchmarks on an API 37 emulator | `startActivityAndWait` confirms launches through `dumpsys gfxinfo framestats`, which is empty there for every activity. `StartupBenchmark` / `ScreenBenchmark` need a phone or an older image (a Pixel 6 on API 37 works: [perf/startup.md](../perf/startup.md)); `ViewerScrubBenchmark` avoids the API and runs |
| Float16 / ZNSSD quantisation for field data | Would change reported numbers; rejected under the bit-exactness requirement |
| In-memory X/Y compaction (derive coordinates from the grid) | Lossless and worth about 25 %, but a larger change that also touches the native writer: the `.dat` stores X and Y for every point (`field/DicResult.kt:20-24`) |

## Checked by hand, not from the repository

These are read with `gh` or `gcloud`, not from the code. On 2026-10-08 none of
them held:

- **API-key restrictions:** the Firebase keys on both projects are limited to a
  list of APIs, but have no Android package / SHA or referrer limits.
- **GitHub Environment branch rules:** no environment, `production` included, has
  a deployment branch policy. Production deploys only from `main` because the
  workflow checks it.

The open checkboxes are in [PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md).

## Perf and quality gates (do not loosen)

See [../../engine/docs/PERF_BASELINE_bd44af0.md](../../engine/docs/PERF_BASELINE_bd44af0.md):
≥ 4557 solves/s on the host, the smoke and DICe floors, and `-O3 -ffast-math` / OpenMP /
LTO kept on the release pipeline. **No CI job in this repository enforces the
solve-rate floor:** it is checked by hand in the engine repo when the `engine` pin
moves. `koverVerify` (lines 75, branches 57, `app/build.gradle.kts:431-436`) runs in CI
tier 1, `ciReleaseGate` and the release workflow.

The CI benchmark job ("Benchmarks (macro + micro)") is emulator **smoke**, with no
numeric thresholds. Macrobenchmark suppresses `EMULATOR,LOW-BATTERY,UNLOCKED`.
`HotPathMicroBenchmark` suppresses
`EMULATOR,DEBUGGABLE,LOW-BATTERY,UNLOCKED,ACTIVITY-MISSING,NOT-AOT-COMPILED`
(`.github/workflows/ci.yml:817`, `:819`). Keep API 34: API 37's
`dumpsys gfxinfo framestats` is empty. Run it with the `run_benchmark` dispatch
input or the `benchmark` label.

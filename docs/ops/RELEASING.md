# Releasing Semper

How a build goes from **`main`** to testers. Written for maintainers; nothing
here is needed for day-to-day contributions. Secrets/vars layout:
[ENVIRONMENTS.md](ENVIRONMENTS.md).

This repo releases **Material Testing** (`com.sempermechanics.materialtesting`, [ADR-019](../adr/ADR-019-sempermechanics-app-id.md)),
not Semper: its signing secrets hold Material Testing's own key, and
`SEMPER_API_BASE_URL` (or the older `INDIC_API_BASE_URL` var) is Semper's gateway, since both apps share one backend
([ADR-009](../adr/ADR-009-material-testing-app-identity.md)). The Asset Links
check greps the whole file for the key's SHA-256, so add it to
`assetlinks.json` in this repo and in `semperdic-app`, which deploys Hosting.

## Alpha (private testers)

Private alpha uses the same **`beta`** Release channel — there is no separate
`alpha` input on [`release.yml`](../../.github/workflows/release.yml).

1. Tag and run Release on **`main`** with channel **`beta`**
   (e.g. `v1.0-beta.1`) → GitHub pre-release + signed APK.
2. Sideload onto tester devices (Pixel OK). The repository reports **public**
   (GitHub REST API, checked 2026-10-18), so anyone with the link can download
   from its Releases; treat a release as published, not private.
3. Smoke sign-in, one analysis, backup/sync, and open results. Do not publish to
   Play or the public website for this ring.

Check the R8 mapping reached Crashlytics (the release build uploads it); the workflow artifact is a 90-day fallback.

## Versioning

- A CI release sets the version from the workflow inputs: `versionName` comes
  from the `version` input (leading `v` stripped) and `versionCode` is 1000 plus
  the workflow run number (`RELEASE_CODE_BASE` in `release.yml`), passed to
  Gradle as `-PversionName` / `-PversionCode`.
  You do **not** hand-edit `app/build.gradle.kts` for a CI release.
- The values in [`app/build.gradle.kts`](../../app/build.gradle.kts) (`1` / `1.0`)
  are only the fallback for local builds that pass no `-P` overrides.
- Beta builds are tagged `v<versionName>-beta.<n>` (e.g. `v1.0-beta.1`);
  stable releases are tagged `v<versionName>`.
- `versionCode` increases for every release (the run number is monotonic).
- `main`'s CI also builds a `release-apk` signed with the release key, at
  **versionCode 1**, so it can never install over a release. Until 2026-10-01 it
  used CI's run number (671 by then in semperdic-app, against its release workflow's 35; here in the 250s against 4 for `v1.2-beta.3`, TD-148), so a
  tester who installed it was refused every later release until they
  uninstalled, which deletes analyses that are not in the cloud. The 1000 base
  puts releases above every number CI ever used, so those phones update again.
  Do not hand CI artifacts to anyone; cut a beta instead.

## Release checklist

1. **Green working tree** — all changes committed, branch merged to **`main`**
   through a reviewed PR.
2. **Green CI** — `CI OK` must be green on the `main` commit you intend to
   release from a **full-matrix** run. A push to `main` runs the emulator and
   release tiers only when its diff touches them, so dispatch **Actions → CI →
   Run workflow** on `main` with `full_ci` ticked (or use that commit's weekly
   run, if it is the head).
3. **Tag and push** the commit you intend to release:

   ```bash
   git tag v1.0-beta.1
   git push origin main --tags
   ```

4. **Run the Release workflow** — go to Actions → Release → Run workflow.
   Select branch **`main`**. Provide the version tag, changelog, and channel
   (beta/stable); the version drives `versionName`/`versionCode` automatically
   (see Versioning). Jobs refuse to run on other refs. The workflow uses the
   `release` environment (and repo-level secrets/vars on Free orgs).
   When the release ships with a backend deploy, dispatch `deploy-backend.yml`
   with `release_train` set to the release tag (`v1.2-beta.5`), so the service and
   the revision carry the label `release-train=v1-2-beta-5` (lowercased, dots to
   dashes). Left empty, the deploy takes the newest `v*` tag reachable from the
   deployed commit. Name the backend commit and revision in the release notes
   ([CI.md](CI.md), Backend deploy).
5. **Smoke the release build on a device** — clean install, sign in,
   run one analysis, confirm the session syncs and each share target works.
6. **Distribute publicly (manual)** — this workflow builds a signed APK and a
   GitHub Release in this repository, a pre-release on the `beta` channel. The
   repository is public, so that Release is downloadable by anyone, and it is
   where a sideloaded build's **Update** button goes: below the minimum version,
   a build not installed by Play opens
   `https://github.com/sempermechanics/material_testing/releases` (the list, since
   `/latest` skips pre-releases; `ui/limit/UpdateSource.kt:19`), and a Play
   install opens its Play listing. Attach the APK to every release. Customer
   distribution is separate:
   - Upload the APK (GitHub Release on
     [sempermechanics/website](https://github.com/sempermechanics/website) preferred) + update
     `downloads/manifest.json`, push so GitHub Pages redeploys — see that repo’s
     `RELEASING.md` — and/or publish on Play Store.
   - Announce on https://sempermechanics.com/ / Discussions on
     `sempermechanics/website`, not this repository.
   - For Play, upload the **`release-aab`** artifact (or the `.aab` attached to the
     GitHub Release), not the APK; see "Staged rollout on Google Play" below.

**Release notes rule.** Every release's notes name what changed for the user and
every customer-visible backend change it depends on. The first release built
after 2026-10-18 must say that **Android 11 (API 30) is now the minimum**: phones
on Android 7–10 cannot install or update it, and keep the version they have,
which the backend may later gate (for this app, `MIN_SUPPORTED_VERSION_CODE_MATERIAL_TESTING`,
which the backend now reads as the fallback for its `_SEMPER_CLASSROOM` pair, [ADR-030](../adr/ADR-030-semper-classroom-app-identity.md);
`MIN_SUPPORTED_VERSION_CODE` is Semper's).

## Staged rollout on Google Play

Readiness items 1.1, 6.3 and Part D5
([READINESS_FRAMEWORK_2026-10.md](READINESS_FRAMEWORK_2026-10.md)). Nothing here
has been rehearsed: there is no Play listing or closed track yet (TD-229, TD-231).
Play does not allow a staged rollout for an app's first production release, and a
staged percentage never rises by itself.

| Stage | Users | Minimum soak before the next stage |
|---|---|---|
| Internal / closed track | testers | until every halt signal below has data |
| 1 | 5 % | 24 h, and at least 7 days of the closed track behind it for a release that changes licensing |
| 2 | 20 % | 24 h |
| 3 | 50 % | 48 h |
| 4 | 100 % | — |

**Halt criteria.** Halt the rollout (Play Console → Release → Halt) when any of
these holds for the new version. The numbers are starting values, to be tuned
after the first rollouts:

- **Crash-free users** below **99.5 %** (Crashlytics, the release's version) once at
  least 200 users run it, or a new crash cluster in licensing, upload or restore.
- **User-perceived ANR rate** above **0.47 %**, Play's bad-behaviour threshold
  (Android vitals).
- **Activation errors:** the share of `semper_activation` lines with
  `outcome="refused"` on the new `appVersion` more than 5 points above the previous
  version's 7-day share, with at least 20 attempts (`ops/monitoring/metrics/semper_activation.yaml`).
  The same for `semper_checkout`, leaving out `no_floating_seat`, which is a full pool.
- **Device-lock drops:** `semper_devlock_drop` with `stage="mismatch"` rising on the
  new version.
- **Refusals:** the `auth-denied-spike` alert, or any `AUTH_DENIED` with
  `stage="app_check"` from the new version.
- **Backend:** the `http-5xx-ratio` or `slo-burn-fast` alert while the rollout is
  under way.

Halting stops new installs only; users who already updated stay on the new build.
That is why the levers below, not Play, are the rollback.

## Rollback levers

Play cannot recall a build. In order of speed:

| Lever | Effect | How |
|---|---|---|
| `KILL_SWITCHES` | Switches off `backup`, `share`, `restore` or `upload` without a release. The backend refuses the guarded routes itself (503 `feature_paused`, `Retry-After: 300`; `backend/app/deps.py:295-330`), so it holds for every build, old ones included, which retry the 503; current builds also show the feature as paused. `share` has no route, so only builds that read `/v1/config` obey it | Repo variable, space-separated, then `deploy-backend.yml` ([ENVIRONMENTS.md](ENVIRONMENTS.md)); watch `event="feature_paused"` in the logs to see what it holds back |
| `MIN_SUPPORTED_VERSION_CODE_MATERIAL_TESTING` | Material Testing builds below it keep their analyses, viewer and exports, and cannot start an analysis, upload or restore until updated. `RECOMMENDED_VERSION_CODE_MATERIAL_TESTING` only nudges. Since ADR-030 the backend reads the pair as `MIN_SUPPORTED_VERSION_CODE_SEMPER_CLASSROOM` / `RECOMMENDED_VERSION_CODE_SEMPER_CLASSROOM` and falls back to these `_MATERIAL_TESTING` names when those are unset ([ADR-030](../adr/ADR-030-semper-classroom-app-identity.md)); either pair gates this app's builds. Semper has its own pair, `MIN_SUPPORTED_VERSION_CODE` / `RECOMMENDED_VERSION_CODE`, because each app's versionCodes come from its own release counter (`settings.release_gate(app)`, `backend/app/config.py:308-335`) | Repo variable, redeploy. Raise the recommended one a release ahead. A local debug build at versionCode 1 is never gated |
| `ENTITLEMENT_OFFLINE_GRACE_DAYS` | How long newly issued entitlement tokens hold offline; the fail-open / fail-closed lever for licensing. Tokens already issued keep theirs | Repo variable, redeploy ([ADR-023](../adr/ADR-023-signed-offline-entitlement.md)) |
| `APP_CHECK_MODE` | `enforce` back to `monitor` or `off` | [INCIDENT_RESPONSE.md](INCIDENT_RESPONSE.md) |
| Backend revision | Traffic back to the previous revision | `gcloud run services update-traffic semper-api --to-revisions=<previous>=100 --region asia-south1`; the image is kept as `rollback-prev` ([BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) A7). Firestore is never rolled back by a deploy |
| Hotfix | A new build with a higher `versionCode` | The release workflow's run number makes every release higher than the last (Versioning above); roll it out with the same stages, faster soak if the fix is narrow |

Set a lever on the GitHub environment (`gh variable set NAME --env staging`), not
the repository, so a drill on staging never reaches the next production deploy:
`deploy-backend.yml` runs under `environment:`, whose variables win over the
repository's.

**Drill on staging, 2026-10-22** (TD-220), with a debug build at versionCode 100
pointed at `semper-api-staging`, signed in as a QC test account on an Android 14
and an Android 12 emulator; each step is a `deploy-backend.yml` run from `main`
with `gateway_mode=dry-run`:

1. `KILL_SWITCHES="backup share restore"` (run 39752109438). The viewer's Share
   is greyed and a tap says "Sharing is temporarily unavailable"; Settings hides
   Cloud backup and Analyses data management (`FeatureLocks.settings`), so
   neither backup nor restore can be asked for; the app sends no
   `POST /v1/sessions`. A client that ignores the config (the CI round trip,
   `StagingRoundTripDeviceTest`) is refused by the backend itself: 503
   `{"detail":"feature_paused"}` on the signed create.
2. `KILL_SWITCHES` unset, `MIN_SUPPORTED_VERSION_CODE=200` (run 39755073181).
   **New analysis** opens "Update to keep analysing" (`UpdateRequiredActivity`)
   on both phones; **Open my analyses** returns to Home, where analyses still
   open.
3. Both unset (run 39755853906): the revision's env is back to `0` and empty,
   and after **Refresh licence** New analysis opens the import sheet again.
4. `RECOMMENDED_VERSION_CODE=200` (run 39762144670). After **Refresh licence**,
   the next Home opens with the Snackbar "A newer version of the app is
   available." on both phones; analysing, backup and restore are unchanged.
   It shows once: the key `100<200` is kept (`update_nudge`,
   `data/policy/AppPolicy.kt:44-51`), so further launches stay quiet until the
   recommended version or the build changes. Set back to `0` in run
   39762952329.

A running install sees a change on its next config read: launches within five
minutes of the last reconcile reuse the cached `/v1/config`
(`data/cloud/CloudSync.kt:412`, `RECONCILE_MIN_INTERVAL_MS`), and **Refresh
licence** fetches it at once. Lifting a lever reaches phones the same way, so
allow that long before calling a rollback done. The Semper Classroom pair and
`ENTITLEMENT_OFFLINE_GRACE_DAYS` were not moved (TD-220).

## CI-based release (workflow_dispatch)

The [`release.yml`](../../.github/workflows/release.yml) workflow runs
`verify-legal` / `verify-android` / `verify-backend` in parallel, then
`build-release` → `publish`. Dispatch from **`main` only**.

### Verify jobs — the gate

Green CI used to be a checklist item in this file, which meant a red suite could
still be signed and published. The same checks now run as three jobs so legal,
JVM unit tests, and backend pytest overlap instead of stacking:

- `verify-legal` — `python scripts/render_legal_pages.py --check`: the published
  Privacy Policy and Terms still match `docs/legal/`.
- `verify-android` — CI tier 1's checks: `:app:testDebugUnitTest spotlessCheck
  :app:detekt :app:lintDebug`, the androidTest and `:benchmark` compiles, and
  the Kover floor (`koverVerify`). `compileReleaseKotlin` is left to
  `build-release`, whose R8 build compiles it. No engine/OpenCV submodules: the
  JVM suite does not `loadLibrary`.
- `verify-backend` — the shared `backend-gate` action (hashed lock, `pip-audit`,
  ruff over `app/ tests/ scripts/ ../scripts/`, pytest at the 93 % floor), the
  same one CI and Deploy run.

`build-release` declares `needs: [verify-legal, verify-android, verify-backend]`,
so none of the signing steps run if any of the above fails.

### `build-release` — sign and check

1. Builds a **signed release APK** using repository secrets (keystore, alias,
   passwords) — Environment-scoped when available, otherwise **repo-level**
   secrets on Free private orgs. The `signingConfigs.release` block in
   `app/build.gradle.kts` reads the `SIGNING_*` env vars the workflow sets; if
   the keystore is absent the variant stays **unsigned** rather than silently
   debug-signed. `assembleRelease` still runs R8 minify; the mapping upload
   fails the job if that file is missing.
2. Requires variable **`SEMPER_API_BASE_URL`** (HTTPS API Gateway or Cloud Run
   URL) and builds with `-PrequireCloudApi=true`. A missing, empty or non-HTTPS
   URL fails the job — cloud sync must not ship silently disabled, and ID tokens
   must not go out in cleartext.
3. Verifies the arm64-v8a `.so` is packaged.
4. **Verifies the signature** with `apksigner verify` — the release fails here
   if the APK is not validly signed with the release key. Newer Android
   build-tools print `V2 Signer: certificate SHA-256 digest:` (and similar);
   older ones used `Signer #1 certificate SHA-256 digest:`. The workflow accepts
   both.
5. **Inspects the APK's hardening** with
   [`scripts/check_release_apk.py`](../../scripts/check_release_apk.py) (aapt2
   from the newest build-tools, plus a scan of every `classes*.dex`). It fails
   the release when the APK is debuggable or `testOnly`, ships a component or
   class from the debug source set (`app/src/debug`, e.g.
   `DebugViewerSeedActivity`), contains `DebugAppCheckProviderFactory` or a
   firebase-appcheck-debug version file, allows cleartext (manifest or network
   security config), has a `minSdkVersion` other than `minSdk` in
   `app/build.gradle.kts`, or R8's `mapping.txt` is missing or empty. It
   **warns** when no `sha256/…` pin is compiled in, i.e. the
   `SEMPER_API_CERT_PINS` secret was empty. CI tier 5 runs the same check on
   its own release build. Locally:
   `python3 scripts/check_release_apk.py app-release.apk --mapping mapping.txt`.
6. **Verifies the bundle's signature** (`jarsigner -verify` on the `.aab`).
7. **Verifies `assetlinks.json` lists the release certificate.** It extracts the
   SHA-256 digest from the signed APK, reformats it to the colon-separated
   uppercase form Digital Asset Links uses, and greps
   `firebase-hosting/public/.well-known/assetlinks.json` for it. If the
   fingerprint is absent the release fails with instructions.

   This check exists because the failure it prevents is invisible. App Links only
   verify when the *release* signing certificate is in the hosted file; when it is
   not, the email sign-in and password-reset links stop opening the app and fall
   back to a browser disambiguation dialog — phishable, and indistinguishable from
   an app bug. Rotating the signing key or moving to Play App Signing means adding
   the new fingerprint here and redeploying Hosting.
8. Uploads three artifacts: the APK, the **`release-aab`** Android App Bundle
   (built in the same Gradle run as the APK, `:app:bundleRelease`, so both
   share one R8 mapping; for a Play Console upload by hand: nothing here uploads
   to Play), and **`release-mapping-<version>`**, the R8 mapping file (90-day
   retention, `if-no-files-found: error`).

**The R8 mapping is not attached to the GitHub Release, deliberately.** It is the
deobfuscation key — publishing it would undo the obfuscation for everyone — but
without it a field stack trace from that build is unreadable, and it cannot be
regenerated afterwards. The build passes `-PuploadCrashlyticsMapping=true`, so
the Crashlytics Gradle plugin uploads the mapping to Firebase and field crashes
stay readable there for the life of the project (TD-40). The 90-day workflow
artifact is only the fallback for a manual `retrace`. Local builds never upload.

### `publish`

Creates the **GitHub Release** with the APK and the AAB attached, gated on the
`release` environment.

### Required secrets (repo or `release` environment)

| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded release keystore |
| `KEY_ALIAS` | Signing key alias |
| `KEY_PASSWORD` | Key password |
| `STORE_PASSWORD` | Keystore password |
| `SEMPER_API_CERT_PINS` | Optional. Comma-separated `sha256/…=` CertificatePinner pins for the API host, passed to Gradle as env. Empty ships system trust only, and the APK inspection warns |

### Required variables (repo or `release` environment)

| Variable | Description |
|----------|-------------|
| `SEMPER_API_BASE_URL` | HTTPS base URL of the API Gateway (preferred) or Cloud Run service |
| `SEMPER_ENTITLEMENT_PUBKEYS` | Public key(s) the app verifies signed entitlements with, passed to Gradle as env. Public material, so a variable, not a secret |

### Backend staging / production

Use [`deploy-backend.yml`](../../.github/workflows/deploy-backend.yml): choose
`staging` or `production`, supply GCP project/region. Updates deploy with
`no_traffic`, tag a candidate, smoke `/readyz` with an ID token, then promote.
First-time Cloud Run creates omit `no_traffic` (Cloud Run rejects it on create).
See [CI.md](CI.md) § Backend deploy and [ENVIRONMENTS.md](ENVIRONMENTS.md).

#### Bumping backend dependencies

The image installs from `backend/requirements.lock` with `--require-hashes`, so a
version bump is two files, in this order:

```bash
cd backend
# 1. Edit requirements.txt (the direct dependency you actually want to move).
# 2. Regenerate the hashed lock from it on Python 3.12 (CI and Cloud Run runtime).
#    Do not compile the lock on 3.13 — the header and markers must match runtime.
#    Compile on Linux: uvicorn[standard] pulls uvloop there; a Windows lock omits
#    it and `--require-hashes` then fails in CI.
pip install pip-tools
pip-compile --generate-hashes --output-file requirements.lock requirements.txt
```

CI tier 4 then proves the lock resolves under `--require-hashes` on Python 3.12
*and* that every direct dependency's **version** matches `requirements.txt`, so a
stale or hand-edited lock fails in CI rather than in the Cloud Build step of a
deploy. Never edit `requirements.lock` by hand — the hashes will not match and
the image will fail to build. Never bump txt without regenerating the lock.

**If you have no Linux Python 3.12 to hand, do not improvise.** Compiling on
Windows or on 3.13 produces a lock that installs fine locally and then fails
`--require-hashes` in CI: `uvicorn[standard]` pulls `uvloop` on Linux and swaps
in `colorama` on Windows, and the marker set differs between 3.12 and 3.13. Run
the [`Backend lock`](../../.github/workflows/backend-lock.yml) workflow instead —
Actions → Backend lock → Run workflow, and give it the branch. It compiles in
the documented environment and pushes the lock to that branch.

**Dependabot cannot do this step.** It bumps `requirements.txt` and has no way to
produce a hashed lock, so every backend Dependabot PR arrives with the two files
out of step and tier 4 red. Run `Backend lock` against the Dependabot branch to
put the lock on the PR. Its push is made with the workflow's `GITHUB_TOKEN`,
and GitHub starts no new workflow run for a push made with that token, so the
PR's checks stay red on the old commit: re-run CI from the PR (Checks → Re-run
all jobs) or push any follow-up commit yourself. A human push also stops
Dependabot rebasing that PR. A `Backend lock` check also runs on any PR touching either
file: when the committed lock is stale it fails and attaches the regenerated file
as the `requirements-lock` artifact, so the fix is a download rather than a
toolchain install.

### Local gate before triggering release

```bash
./gradlew ciReleaseGate
```

This mirrors CI **tiers 1 and 5** locally (quality gates + unit tests + R8 +
release assemble). It does **not** run the backend suite, the emulator tier, or
the two always-on gates. Run those separately:

```bash
python scripts/render_legal_pages.py --check                     # legal-pages
cd backend && pytest tests/ -q --cov=app --cov-fail-under=93     # tier 4
./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64     # tier 3
```

Engine host and sanitizer suites are not part of this repo's gate — they run in
`sempermechanics/semper-dic-engine` against the commit this repo pins.

## After the release

- Verify a fresh install from the distributed artifact (not a dev build).
- **Upgrade smoke (manual):** install the previous Release APK → create a local
  analysis (and optionally enqueue upload/restore) → install the new APK
  *without uninstalling* → open Home, reopen the session, confirm queued
  WorkManager unique work still resolves. Prefs migrate via `AppSettings.migrate`
  on cold start.
- Watch Firestore (sessions collection) and Cloud Logging for the first synced sessions.
- Paste the GitHub Release changelog into Play / website **What’s new** when
  publishing that channel.
- Open a milestone for the next version and triage incoming beta feedback
  (Discussions on `sempermechanics/website`, support mail, in-app Send feedback)
  into issues against it.
- **Follow up:** when a reported item ships, reply on the thread or mail and
  close/check the milestone item so reporters know it landed.

## History rewrite / re-clone

If git history is rewritten (for example to purge the old prebuilt OpenCV SDK
under `app/src/main/cpp/opencv/` from every commit), collaborators must
**re-clone** or hard-reset to the rewritten tip. Force-pushed branches invalidate
existing local clones' merge bases. Coordinate with the team before rewriting;
document the rewrite in the PR / release notes. Do **not** force-push `main` for
routine fixes — prefer `git revert`.

# Readiness run 2026-10-28 — report

Second production-readiness review of `semperdic-app`, closed out on 2026-10-29. The Admin
scoped it to this repository only, all lanes, and the read-only GCP posture check against
`indicvision-dic-app` / `indicvision-dic-app-auth`.

Records:
- [FINDINGS.yaml](FINDINGS.yaml): 98 findings, with final statuses.
- [BASELINE.md](BASELINE.md) and [INVENTORY.md](INVENTORY.md).
- [screens/](screens/): before and after.
- The Admin's answers, verbatim, in [ADMIN_DECISIONS.md](../ADMIN_DECISIONS.md) ("Run 2026-10-28").
- The rules in [RUBRIC_ADDENDA.md](../RUBRIC_ADDENDA.md):
  - ADDENDUM-2: the domain lock, with uniform replies.
  - ADDENDUM-3: "Demo" / "Licensed".
  - ADDENDUM-4: lower-case "semper".

The verifiers' logs, mutation diffs and raw dumps stay in the session scratchpad, as last run's did.

| | |
|---|---|
| Start | `main` `046d54c5`, engine `f072da0` (v0.6.1) |
| Readiness head | `claude/readiness-20261028` at `the PR head; its last code commit is `1f07be311`, and later commits change only docs`. Merged from the backend, console, ops and app streams and three rework rounds. Not merged to `main`, not deployed |
| Engine | semper-dic-engine #27 (TE2-002) and #28 (TE2-004): open, CI green, for the Admin to merge. The app stays pinned to v0.6.1 (Admin, Phase 4); adopting the next tag is TD-353, an ORACLE-CHANGE |

**Verification.** Every finding was checked by an independent Phase 2 verifier (95 CONFIRMED, 2
NEEDS_ADMIN, 1 NEEDS_DEVICE, 0 not reproduced). Every fix was then re-verified by a fresh agent
that had not seen the fix reasoning:

| Round | Head | Scope | Result |
|---|---|---|---|
| R1 | `4fcc5829` | backend, console, ops and docs fixes | 38 VERIFIED, 5 REOPENED, 3 PENDING (engine, device) |
| R2 | `e41a9136` | 26 app fixes, the R1 rework, the full gate set | 22 VERIFIED, 8 REOPENED, 1 PENDING (TE1-003). `check_activity_scope_work` red |
| R3 | `906fb32d` | rework round 1 | 10 of 11 VERIFIED; AE1-111 REOPENED (rename dialog). All gates green; unit tests 3/3 runs clean |
| R4 | `d011d96e` | rework round 2 | TE1-105 VERIFIED; AE1-111 REOPENED (the Re-run sheet and four plain dialogs) |
| R5 | ``1f07be31`` | rework round 3 (the last the Admin allows) | AE1-111 VERIFIED: 19 Home surfaces found by reading `ui/home/`, each kept through a rotation and font scale 2.0 and back, with its state, one copy, and its action on the right row; 16 mutations all caught. All gates green |

## 1. Summary and go/no-go

**Verdict: No-go.** Six P1s are open, and none is risk-accepted in writing. None of them can
be fixed in this repository:

| P1 | What | Who clears it |
|---|---|---|
| TE6-103 | The readiness fixes (this run's and #489's) are not live: no backend or Hosting deploy since #489 | Admin: §4 A2, A3 |
| TE6-001 | No alert reaches anyone; monitoring-as-code is not applied | Admin: §4 A4 |
| TE6-011 | GCP posture: 17 FAIL, the same as 2026-10-24 | Admin: §4 A5 |
| TE4-001 | Signed offline entitlement not live: no signing key, no pinned key in release builds | Admin: §4 A6 |
| TE5-003 | Operator identity, Grievance Officer and region are placeholders in the legal pages | Counsel (left pending by the Admin): §4 A12 |
| TE5-004 | The Privacy Policy omits Firebase Analytics and App Check / Play Integrity | Counsel (left pending by the Admin): §4 A12 |

The rule (the prompt's §10): No-go whenever a P0 is open, or a P1 is open without written risk
acceptance. No P0 was found. Each open P1 has a row in the gate's
[Risk acceptance](../../PRODUCTION_READINESS_GATE.md#risk-acceptance) table for the Admin to sign
or clear (A13).

Fixed in this run and verified: the P1s TE3-102 (domain lock), TE5-101 (msgpack CVE), TE1-001
(certificate pins, now required by the release gate), AE1-101 (a dark-mode switch closed the
app), AE1-102 (touch targets), AE1-103 (consent dialog at font scale 2.0), and every other P1 that
was not legal or external.

### Scorecard

**Technical readiness** (the prompt's §10): area score = weight × criteria passing ÷ criteria applicable.

**Criteria per area:**
- Each finding in the area. Observations and duplicates are left out. A finding passes only when
  `fixed-verified`, or `fixed-device-accepted` (TE2-003).
- The area's gate checks in §2. A check fails while it waits on an Admin action.

**Accepted partials** count as not passing.

The script is `lead/scorecard.py` in the session scratchpad.

| Area | Weight | Findings (criteria) | Passing now | Gate checks | Passing | Passed/applicable | Score |
|---|--:|--:|--:|--:|--:|--:|--:|
| Core app | 25 | 17 | 11 | 6 | 5 | 16/23 | 17.4 |
| Backend security | 20 | 6 | 6 | 6 | 6 | 12/12 | 20.0 |
| Licensing | 20 | 3 | 2 | 2 | 1 | 3/5 | 12.0 |
| Operator console | 15 | 0 | 0 | 6 | 6 | 6/6 | 15.0 |
| Release and monitoring | 10 | 10 | 7 | 6 | 3 | 10/16 | 6.2 |
| Compliance | 10 | 3 | 0 | 3 | 1 | 1/6 | 1.7 |
| **Technical readiness** | 100 | | | | | | **72.3** |

**Aesthetic readiness** (reported separately): **92.1 %** (58 of 63 weighted points). This is the share of AE-1, AE-2 and
AE-3 findings that pass now, weighted 2× for core flows and 1× for secondary screens. Observations
and duplicates are left out.
- **Core flows:** a P1, or a finding on Home, the wizard, a run, the viewer, export, sign-in or consent.
- **Open:** AE1V-016 (beta-notice wording: Admin/counsel), AE1-011 (accepted
  partial, TD-331), and the two legal ones (AE2-103, AE3-113).

**Counts by status** (all 98 findings):

| Status | Findings |
|---|--:|
| `fixed-verified` | 66 |
| `fixed-device-accepted` | 1 |
| `fixed-pending-engine-merge` | 2 |
| `accepted-partial` | 2 |
| `admin-action` | 8 |
| `pending-legal` | 5 |
| `observation` | 12 |
| `duplicate` | 2 |
| **all** | **98** |

### Go/no-go per gate

**Gates:**
- G1 native and measurement output
- G2 security, authz, privacy
- G3 backend contract and ops
- G4 licensing
- G5 Android
- G6 release
- UX

| Gate | P0/P1 open | Status | What is missing |
|---|---|---|---|
| G1 | — | **Go with conditions** | Merge engine #27 and #28 (A9). Real-data validation at the pin, TE2-005 (A11). The precision decision, AE1-017 (A14). The oracles are bit-exact; arm64 smoke 12/12 on the S22+ |
| G2 | TE5-003, TE5-004 (legal, pending) | **No-go** | Counsel (A12), or both rows signed (A13). TE3-102 and TE5-101 are fixed; TE5-101's lock is live only after A2 |
| G3 | TE6-001, TE6-011 | **No-go** | Monitoring applied (A4); posture FAILs fixed (A5); or both rows signed (A13) |
| G4 | TE4-001 | **No-go** | The entitlement key and its pins (A6), or the row signed. The pinned path is tested on a device (TE4-101) |
| G5 | — | **Go with conditions** | The install-over from 1036, TE1-003 (A10). TD-356 (owner call, A14) |
| G6 | TE6-103 | **No-go** | The PR's first `CI OK` (A1); the backend and Hosting deploys (A2, A3); `SEMPER_API_CERT_PINS` set (A7). Releases fail until A7 is done |
| UX | — | **Go with conditions** | AE1V-016 (A14). The legal-page a11y and wording items with counsel (A12) |

Signatures go in the gate's [Sign-off](../../PRODUCTION_READINESS_GATE.md#sign-off) table. This
report does not sign anything.

## 2. Gate statuses

Unless noted, numbers are from the independent re-verifiers' runs on the final heads: R3 at
`906fb32d` for everything, then R4 and R5 for the app after rework rounds 2 and 3.

| Gate | Command | Baseline (`046d54c5`) | Final |
|---|---|---|---|
| Android tier 1 + release | `gradlew.bat ciReleaseGate` | PASS, 2852 tests | PASS, 2920 tests, 0 failed, 5 skipped (pre-existing). Lint 0 errors, 7 warnings; detekt and lint baselines empty |
| Unit-test stability | `:app:testDebugUnitTest --rerun-tasks` ×3 (R3) | — | 3/3 clean. One JVM exit with no failing test was seen once during rework round 1 (the TD-312 symptom) and not again |
| Kover | `:app:koverLog :app:koverVerify` | 82.24 / 63.45 % (floors 78 / 60) | **82.65 / 63.80 %**. Floors raised to **80 / 61** (TE1-102) |
| Oracles | `DatFieldOracleTest`, `SummaryGifOracleTest` | pass | pass; `app/src/test/resources/oracles/` unchanged |
| Instrumented, API 36.1 (`emulator-5584`) | `connectedDebugAndroidTest` with `notTestFile` (live tests excluded; `log=true` dry run first) | — | 68/68 |
| Instrumented, API 30 (`emulator-5582`) | the same | 58 run, 4 fail | 64/68; the four are `VideoFrameExtractionDeviceTest` (TD-328), unchanged |
| arm64 (Galaxy S22+) | `EnginePipelineSmokeTest` | not run in CI | 12/12, twice (TE2-003; [docs/engine/TESTING.md](../../../engine/TESTING.md)) |
| Release APK | `scripts/check_release_apk.py` | PASS (cert-pins WARN) | Without pins: exit 0, and exit 1 with `--require-pins`. With the four GTS pins: `--require-pins` exits 0 |
| Backend tests | `cd backend && python -m pytest tests/ -q --cov=app --cov-fail-under=93` | 2297 pass, 1 fail (Windows clock), 96.87 % | 2479 pass, 0 fail, **97.00 %**. Includes Schemathesis (TE3-007) and the OpenAPI breaking-change test (TE3-105) |
| ruff (with bandit `S`) | `ruff check` | clean | clean |
| pip-audit | `pip-audit -r requirements.lock --require-hashes --disable-pip --strict` | **FAIL**: msgpack 1.2.1, CVE-2026-114613 | clean (msgpack 1.2.4, regenerated on Linux by `backend-lock.yml`, run 40665143475) |
| Console tests | `node --test` with V8 coverage, `--lines=96` | 465 pass, 98.76 % | 490 pass, **98.80 %** |
| Console a11y | `npm run test:a11y` | 20 pass | 54 pass, 2 skipped (AE2-103's `fixme`, legal) |
| Lighthouse | `npm run lighthouse` (new, AE2-110) | not available | thresholds 95 / 95, in CI as `console-lighthouse` |
| Console wiring | `python scripts/check_console.py` | pass | pass |
| Repo checks | `check_doc_paths`, `check_framework_coverage`, `check_kotlin_file_size`, `check_swallowed_cancellation`, `check_activity_scope_work`, `check_engine_pin`, `render_legal_pages --check`, `unittest discover scripts/tests` | pass | pass. `check_activity_scope_work` was red at R2 and fixed with no allowlist row |
| Legal diff | `git diff 046d54c5 -- docs/legal firebase-hosting/public/privacy firebase-hosting/public/terms` | — | empty |
| GCP posture | `scripts/check_gcp_posture.sh` (read-only) | **17 FAIL**, 0 UNKNOWN | unchanged: nothing external was done. The script now prints all 28 items and exits 4 on any UNKNOWN (TE6-102) |

**New CI jobs** (they run first on this run's PR):
- `android-deps-osv`: OSV-Scanner 2.7.0 on the Gradle lockfile.
- `semgrep`: 1.180.0, no rule exclusions; inline `nosemgrep` only. It found the token leak fixed at `backend/app/deps.py:134`.
- `console-lighthouse`.
- The engine golden-run check, inside `check_engine_pin.py` (TE2-102).

**Not run locally:**
- gitleaks: CI's `secret-scan` runs it.
- shellcheck.
- Tier 3, which signs in to staging.
- Tier 5 on GitHub runners.

All of these run on the PR (A1).

## 3. Findings ledger

Every record is in [FINDINGS.yaml](FINDINGS.yaml). For each one:
- `status`: the final status.
- `reverify`: the round and outcome.
- `fix_commits`.

**Statuses:**

| Status | Meaning |
|---|---|
| `fixed-verified` | Fixed here; an independent re-verifier marked it VERIFIED; the Admin accepted it at Checkpoint 2 |
| `fixed-device-accepted` | TE2-003: run by the lead on the S22+; the Admin accepted it on that evidence |
| `fixed-pending-engine-merge` | TE2-002, TE2-004: fixed in the engine repository, in PRs awaiting the Admin's merge |
| `accepted-partial` | AE1-010, AE1-011: last run's accepted partials, still accurate (TD-359, TD-331) |
| `admin-action` | Only the Admin can clear it; see §4 |
| `pending-legal` | Left for counsel by the Admin |
| `observation` | Reported only |
| `duplicate` | Folded into another record |

### Open findings

| Id | Sev | Gate | Status | What it needs |
|---|---|---|---|---|
| TE6-103 | P1 | G6 | admin-action | Deploy the backend and Hosting from the merged `main` (A2, A3) |
| TE6-001 | P1 | G3 | admin-action | Monitoring: a channel, then `apply-monitoring.sh --apply` (A4; TD-217) |
| TE6-011 | P1 | G3 | admin-action | The 17 posture FAILs (A5; TD-221–TD-225, TD-226, TD-215) |
| TE4-001 | P1 | G4 | admin-action | The entitlement key, then `SEMPER_ENTITLEMENT_PUBKEYS` (A6; TD-215) |
| TE5-003 | P1 | G2 | pending-legal | Counsel (A12; TD-36) |
| TE5-004 | P1 | G2 | pending-legal | Counsel (A12; TD-317) |
| AE1-017 | P2 | G1 | admin-action | Precision per quantity: an owner decision, which changes reported output (A14; TD-342) |
| AE1V-016 | P2 | UX | admin-action | Beta-notice data-use wording: sign-off (A14; TD-343) |
| TE1-003 | P2 | G5 | admin-action | The install-over from 1036 with a versionCode ≥ 1037 candidate (A10; TD-344) |
| TE2-005 | P2 | G1 | admin-action | DIC Challenge sample 5 and PMMA at the pin (A11; TD-327) |
| TE2-002 | P2 | G1 | fixed-pending-engine-merge | Merge semper-dic-engine #27 (A9) |
| TE2-004 | P2 | G1 | fixed-pending-engine-merge | Merge semper-dic-engine #28 (A9) |
| AE2-103 | P2 | UX | pending-legal | The privacy table is generated legal output (A12) |
| AE3-113 | P2 | UX | pending-legal | Legal-text terminology for counsel (A12) |
| TE5-103 | P3 | G2 | pending-legal | The Privacy Policy §5.1 path (A12) |
| AE1-010 | P2 | UX | accepted-partial | TD-359 |
| AE1-011 | P3 | UX | accepted-partial | TD-331 |

**Remainders filed as TECH_DEBT rows this run:**
- TD-354: held invites are never promoted or expired.
- TD-355: a theme switch removes a pinned shortcut.
- TD-356: `AppSettings.migrate` runs on the main thread.
- TD-359: user-visible English still built in Kotlin (AE1-010's accepted partial).
- TD-361: the wizard's "Leave setup?" question and Home's two first-run prompts leak their window on a rotation (R5's and the round-3 fixer's observation, outside AE1-111's criterion: each stays shown).
- TD-362: the run launcher reads the cache directory on the main thread in release builds (R3's note).
- TD-360: no screenshot-test or Android a11y-check tool. Adding one is a new dependency the Admin did not approve; AE1-117.

TD-355, TD-356 and TD-360 were put to the Admin as owner calls at Checkpoint 2. No preference was
given, so they stay open (A14).

**Other records:**
- Observations (12): reported only, in FINDINGS.yaml.
- Duplicates (2): TE5-012 → TE1-001, and TE5-102 → TE6-103.

**Two notes from the verifiers** (not findings):
- The sweep's frame name now wraps inside the extension (`.pn`/`g`) at font scale 2.0 (cosmetic).
- The + sheet's coach tip starts again at "1 of 2" after a rotation (R5; cosmetic).

## 4. External actions owed by the Admin

The numbering is this report's. "Gate A-n" is a row in the gate's
[Admin actions](../../PRODUCTION_READINESS_GATE.md#admin-actions-external) table, which keeps
the standing detail. Order matters for A1 → A2 → A3.

| # | Action | Exact command or console step | Clears | Done when |
|---|---|---|---|---|
| A1 | Merge this run | Review and merge the PR from `claude/readiness-20261028` to `main`. It carries the `full-ci` label, so Tier 3, Tier 5, the sanitizer, OSV, semgrep and Lighthouse all run | TE6-103 (part) | `CI OK` green on the PR, including `secret-scan` |
| A2 | Deploy the backend, staging then production | `gh workflow run deploy-backend.yml -R sempermechanics/semperdic-app --ref main -f environment=staging -f project=indicvision-dic-app`. Check `/readyz` and Tier 3 on `main`. Then the same command with `-f environment=production` (needs the required reviewer) | TE6-103; makes TE5-101, TE3-102, the Schemathesis-found 502s and #489's fixes live | `/readyz` 200; the gateway job reports no diff; a CHANGELOG entry with both revisions |
| A3 | Deploy Hosting after A2, keeping the cleared legal pages | From a clean worktree of the merged `main`: `git checkout b940cc29 -- firebase-hosting/public/privacy/index.html firebase-hosting/public/terms/index.html`, then `API_BASE_URL=<INDIC_API_BASE_URL repo variable> ./scripts/deploy-console.sh` ([DEPLOY_CHECKLIST_2026-10.md](../../DEPLOY_CHECKLIST_2026-10.md) §4) | TE6-103; makes the AE2 and AE3 console fixes and the session-ended latch live | `scripts/check_live_console.py` passes; the live `/privacy` and `/terms` equal `b940cc29`'s files |
| A4 | Monitoring (gate A1) | Console → Monitoring → Alerting → Edit notification channels → add one. Then `scripts/apply-monitoring.sh --project indicvision-dic-app --notification-channel <id>` (dry run), the same command with `--apply`, then the `run.invoker` grant it prints | TE6-001; TD-217 | 13 policies, 3 uptime checks, 4 dashboards; a test notification arrives |
| A5 | Posture FAILs (gate A2) | By console or `gcloud`: a lien and a `Prod` tag on both projects; a second owner through a Google group; Android-package and referrer restrictions on the Firebase API keys; Firestore delete protection; DATA_READ + DATA_WRITE audit logs. Then I re-run `scripts/check_gcp_posture.sh --app-project indicvision-dic-app --auth-project indicvision-dic-app-auth --api-sa indic-api@indicvision-dic-app.iam.gserviceaccount.com --backup-bucket indicvision-dic-app-firestore-backups` (read-only) | TE6-011; TD-221–TD-225 | FAIL only on 3.1 (until A8 in the gate, Play) and 3.5 (until A6); 0 UNKNOWN |
| A6 | Entitlement key (gate A4) | [BACKEND_SETUP_GCP.md](../../../backend/BACKEND_SETUP_GCP.md) A8 in order: a P-256 key in Secret Manager, the runtime-SA grant, `ENTITLEMENT_SIGNING_SECRET`, a deploy. Then `gh variable set SEMPER_ENTITLEMENT_PUBKEYS -R sempermechanics/semperdic-app --body '<kid>:<base64 SPKI DER>'` (the format is in `app/build.gradle.kts:84-88`; several `kid:key` pairs, comma-separated, during a rotation) and `gh variable set SEMPER_REQUIRE_ENTITLEMENT_PINS -R sempermechanics/semperdic-app --body true` | TE4-001; TD-215 | `/v1/config` carries `entitlement`; a release's `entitlement-pins` check passes; posture 3.5 PASS |
| A7 | Certificate pins (gate A5) | `gh variable set SEMPER_API_CERT_PINS -R sempermechanics/semperdic-app --body 'sha256/hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc=,sha256/Vfd95BwDeSQo+NUYxVEEIlvkOlWY2SalKK1lPhzOx78=,sha256/QXnt2YHvdHR3tJYmQIr0Paosp6t/nggsEGD4QJZ3Q0g=,sha256/mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c='` (the GTS Root R1–R4 SPKIs and their source: [RELEASING.md § Certificate pins](../../RELEASING.md#certificate-pins-semper_api_cert_pins)) | TE1-001's release side | A release's `cert-pins` check passes. **Until this is set, every release fails `--require-pins`** |
| A8 | Two read-only checks before A2's production deploy | Both are spelled out in [DEPLOY_CHECKLIST_2026-10.md](../../DEPLOY_CHECKLIST_2026-10.md) (the first two boxes). (1) For each `licenses` document with `kind` `institution` (Firestore console, read-only): flag an empty `domainLock`, and list the seats and pending `licenseInvites` at another domain. They stay, but IT can no longer add such an address. (2) The display names in `NOTIFY_FROM` and `NOTIFY_SECURITY_FROM` write "semper" (`backend/app/config.py:355-366`) | TE3-102, ADDENDUM-4 | Both boxes ticked |
| A9 | Engine PRs | Review and merge semper-dic-engine [#27](https://github.com/sempermechanics/semper-dic-engine/pull/27) and [#28](https://github.com/sempermechanics/semper-dic-engine/pull/28). Whichever merges second needs its CHANGELOG conflict resolved. The app pin stays at v0.6.1 (TD-353) | TE2-002, TE2-004 | Both merged into engine `main` with CI green |
| A10 | Install-over smoke (gate A13) | Build a release candidate with versionCode ≥ 1037 (the release workflow). On one device: install the v1.2-beta.4 APK (1036) from the GitHub release, create an analysis, then `adb install -r <candidate>.apk`; open the analysis | TE1-003; TD-344 | Recorded in CHANGELOG for that release |
| A11 | Real-data validation at the pin | DIC Challenge sample 5 and PMMA 3-point bend at `f072da0`, by [engine/VALIDATION.md](../../../engine/VALIDATION.md) | TE2-005; TD-327 | Dated rows in VALIDATION.md |
| A12 | Counsel (gate A10) | The operator and Grievance Officer fields; the processor list (Analytics, App Check / Play Integrity); §5.1's path; the legal-text terminology (AE3-113); the privacy table's keyboard reach (AE2-103). Then `python scripts/render_legal_pages.py` and A3's method without the hold | TE5-003, TE5-004, TE5-103, AE3-113, AE2-103; TD-36, TD-277, TD-317 | Rows closed; pages re-rendered (`--check` passes) and deployed |
| A13 | Risk acceptance | Sign the [Risk acceptance](../../PRODUCTION_READINESS_GATE.md#risk-acceptance) row (owner, expiry, compensating control) for each P1 still open at launch, or clear it by its action | the six P1s in §1 | Each row signed, then Sign-off |
| A14 | Owner decisions | TD-355 (pinned shortcut on a theme switch); TD-356 (`AppSettings.migrate` on main once per start); TD-360 (a screenshot or a11y-check dependency); TD-342 (precision per quantity; changes output); TD-343 (beta-notice wording) | AE1-017, AE1V-016, AE1-117 | Each recorded in ADMIN_DECISIONS and its TD row |
| A15 | Optional: rotate a test password | The password of the test account shown in the run's screenshot `shot.png` (deleted from the AVD on 2026-10-28). Rotate it in Firebase Auth and update `~/.gradle/gradle.properties` | — | Rotated |
| A16 | Website FAQ | In the separate `semperdic/website` repository, `faq.astro`: the AE3-103 correction (the same text as [docs/app/FAQ.md](../../../app/FAQ.md)) and ADDENDUM-4's lower-case "semper" | AE3-103's website half | The website deploy shows both |
| A17 | Play-dependent (gate A8) | The Play account, listing, Data safety, closed-track rehearsal; then App Check `monitor` → `enforce` | TD-226, TD-229–TD-232 | Those rows closed |

## 5. What this run did not cover

- **The legal items:** the Admin left them pending (§4 A12). No file under `docs/legal/`,
  `firebase-hosting/public/privacy/` or `firebase-hosting/public/terms/` changed.
- **Live checks:** nothing was deployed, so no check ran against staging or production
  beyond the read-only posture script. Staging shares the production Firestore (TD-209).
- **TalkBack:** not run. AE1-107 (expanded/collapsed state) was judged from the code and a
  Robolectric test.
- **Phase 8:** the fork port to material_testing is next, after A1.

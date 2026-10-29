# Readiness review — Admin decisions

Every decision the Admin gives during a readiness run, verbatim and dated. Kept across
runs. Findings are in `docs/ops/readiness/<yyyymmdd>/FINDINGS.yaml`; general rules
the Admin states go to [RUBRIC_ADDENDA.md](RUBRIC_ADDENDA.md).

## Run 2026-10-24

### Scope (asked before Phase 0, 2026-10-24)

| Question | Answer (verbatim) |
|---|---|
| Which repository should this readiness run cover? | "Both repositories" |
| Which assessment lanes should Phase 1 run? | "All lanes (Recommended)" |
| May the read-only posture script run, and against which projects? | "Yes, both projects" — `--app-project indicvision-dic-app --auth-project indicvision-dic-app-auth --api-sa indic-api@indicvision-dic-app.iam.gserviceaccount.com --backup-bucket indicvision-dic-app-firestore-backups` |
| (Plan review) | "multitask when safe using subagents" |

### Checkpoint 1 — triage (2026-10-24)

Asked with AskUserQuestion; answers verbatim. Findings in
[20261024/FINDINGS.yaml](20261024/FINDINGS.yaml).

| Finding(s) | Severity | Decision (verbatim) |
|---|---|---|
| MT-TE2-001 | P0 | "Fix now (Recommended)" |
| TE4-001 | P1 | "Fix now (Recommended)" — code half (release-gate check); key provisioning stays the Admin's (TD-215) |
| TE5-001 | P1 | "Fix now (Recommended)" — the Admin dispatches the backend deploy |
| TE1-001 | P1 | "Defer" — TECH_DEBT (with TD-218) |
| AE1-014 | P1 | "Fix now: gate it" |
| AE1-016 | P1 | "Fix now (Recommended)" |
| TE4-002 | P1 | "Fix now (Recommended)" |
| TE3-006 | P1 | "Fix now: snapshot only" |
| AE1-001, AE1-002 | P1 | "Fix now (Recommended)" |
| AE1-006 | P1 | "Fix now (Recommended)" |
| AE1-009 | P1 | "Fix now (Recommended)" |
| AE2-002, AE2-004 | P1 | "Fix now (Recommended)" |
| TE5-002 | P1 | "Reject: keep pre-ticked" — reason: "Product decision stands" |
| TE5-003, TE5-004 | P1 | "Defer (owner/counsel)" — TD-36 / TD-317, line references to be refreshed |
| TE6-001, TE6-011 | P1 | "Defer to Admin actions" — TD-217; posture re-run |
| Technical P2/P3 (TE-1…TE-6) | P2, P3 | "Fix all now" |
| Aesthetic P2/P3 (AE-1…AE-3, AE2V) | P2, P3 | "Fix all now" |
| material_testing-only P2/P3 (MT-*) | P2, P3 | "Fix with the P0 port (Recommended)" |
| Criterion conflict `-ffast-math` (TE2-001) | — | "Keep fast-math (Recommended)" → [ADDENDUM-1](RUBRIC_ADDENDA.md) |

Stop-and-ask items within the approved groups (2026-10-24):

| Item | Decision (verbatim) |
|---|---|
| New dependencies: AE2-001 axe-core + Playwright; TE3-007 Schemathesis/Hypothesis | "Approve axe-core + Playwright" (Schemathesis/Hypothesis not approved → TE3-007 deferred) |
| TE5-001 lock regeneration (pip-compile on Python 3.12) | "Install Python 3.12 here" |
| TE4-004 kill switches on `GET /v1/sessions/{sid}/uploads`, `PUT /v1/sessions/{sid}/metadata` | "Approve (Recommended)" |
| AE3-001/003/004/008 wording in Terms / Privacy Policy (held for counsel, TD-277) | "App + console only (Recommended)" — legal-text changes listed for counsel |

Open P1s without a fix in this run (TE1-001, TE5-003, TE5-004, TE6-001, TE6-011) block a
Go unless risk-accepted in writing in the gate's "Risk acceptance" table. TE5-002 is
rejected, so it does not block. Observations (11) are reported only. AE-1 visual
(emulator) findings and the posture result arrive after this checkpoint and are triaged
as an addendum.

### Checkpoint 1 addendum — AE-1 visual (emulator) findings (2026-10-24)

Captured on the Semper_API30 AVD (121 screens in [20261024/screens/](20261024/screens/)),
verified from the screenshots, uiautomator dumps and source. Ids renumbered `AE1V-*`.

| Finding(s) | Severity | Decision (verbatim) |
|---|---|---|
| AE1V-001, AE1V-002, AE1V-003, AE1V-008 | P1 | "Fix now (Recommended)" (each asked separately) |
| AE1V-010, AE1V-004, AE1V-005, AE1V-007 | P1 | "Fix now (Recommended)" (each asked separately) |
| AE1V-012 | P1 | "Fix now (Recommended)" |
| AE1V-006 → AE1-006, AE1V-009 → TE5-002, AE1V-013 → AE1-019 | — | duplicates; the earlier decisions apply |
| Visual P2/P3 (AE1V-011, -014, -015, -016, -018, -020, -021) | P2, P3 | "Fix all now" |

### Checkpoint 2 — acceptance (2026-10-26)

Asked with AskUserQuestion after the Phase 5 fresh re-verify. The re-verify ran on the
readiness head `2ebe7a2a`, with main's #484 merged in, and covered 102 fix ids: 84
VERIFIED, 10 REOPENED, 6 NEEDS_ADMIN, 2 NEEDS_DEVICE. Answers verbatim.

| Finding(s) | Re-verify | Decision (verbatim) |
|---|---|---|
| The 84 VERIFIED fixes | VERIFIED | "Accept all 84 (Recommended)" |
| AE3-002, AE1-004, AE1-007, AE1-009, AE1V-021, TE1-006 | REOPENED (small, in repo) | "Rework now (Recommended)" |
| AE1-011, AE1-010, TE2-002, TE2-004 | REOPENED (partial; the rest is large or in the engine repo) | "Accept partial + TD rows (Recommended)": AE1-011 → TD-331 (TD-345 was filed and withdrawn as its duplicate); AE1-010: the PDF report is English-only by decision; TE2-002 → TD-346; TE2-004 → TD-347 and TD-326 |
| New in the after screenshots: the ROI info pill covers the top handles at font scale 2.0; Save graph looks enabled on the lattice's empty state | — | "Fix in the rework batch (Recommended)": new P3 findings AE1V-023, AE1V-024 |

After the rework, a second fresh re-verify at `6324ea7f` found AE1-004, AE1-007, AE1-009,
AE1V-021, AE1V-023 and AE1V-024 VERIFIED, and AE3-002 and TE1-006 REOPENED. The verifier
judged sound the colour guard's exemption for files that write output (`*Export.kt`,
`*Encoder.kt`). Asked 2026-10-26; answers verbatim:

| Finding(s) | Decision (verbatim) |
|---|---|
| AE3-002: the record-integrity strings say "device" for the signing device key | "Keep 'device' for the key": only "registered devices" becomes "registered phones" |
| TE1-006: the quota card, the seat check and the clock re-seal still read on main | "Rework once more (Recommended)" |
| The ROI info pill cuts its values at font scale 2.0 (follow-up to AE1V-023); the Details histogram labels are monospace (AE1V-025, P3) | "Fix in the same batch (Recommended)" |

Round 2's wrapping pill left the ROI canvas about 82 dp tall at font scale 2.0 on a
360×800 dp phone; round 1 had left about 210 dp. Asked 2026-10-26:

| Finding | Decision (verbatim) |
|---|---|
| AE1V-023: the canvas height at font scale 2.0 | "One more small fix (Recommended)": the pill reserves the lines its current mode needs, the bottom bar drops the hidden Manual fields in Draw mode, and the canvas resizes only on a mode switch |

The third re-verify, at `2363b12b`, found AE1V-023 and AE1V-025 VERIFIED, with Draw mode
at about 256 dp. It reopened two items:
- AE3-002: two support-mail lines still said "device", so it went back to rework under
  the rule above.
- TE1-006: its code passes for Home, and the reopening was only about TD-338's list of
  the calls left on main. The lead corrected that list in `111f8060`.

Not asked at this checkpoint: NEEDS_ADMIN items wait on owner decisions or Admin actions.
- AE3-003 → TD-340/TD-341.
- AE1-017 → TD-342.
- AE1V-016 → TD-343.
- TE6-002 and TE6-003: `apply-monitoring.sh --apply`.
- TE2-005 → TD-327.

NEEDS_DEVICE items:
- TE1-003 → TD-344.
- TE2-003: the arm64 smoke.

## Run 2026-10-28

### Scope (asked after Phase 0, 2026-10-28)

| Question | Answer (verbatim) |
|---|---|
| Which repositories should this readiness run cover? | "semperdic-app only (Recommended)" |
| Which assessment lanes should Phase 1 run? | "All lanes (Recommended)" |
| May the read-only GCP posture script run, and against which projects? | "Yes, last run's projects" — `--app-project indicvision-dic-app --auth-project indicvision-dic-app-auth --api-sa indic-api@indicvision-dic-app.iam.gserviceaccount.com --backup-bucket indicvision-dic-app-firestore-backups` |

### During Phase 1 (2026-10-28)

| Question | Answer (verbatim) |
|---|---|
| May I delete /sdcard/shot.png on the Semper_API30 emulator (a 2026-10-22 screenshot showing a test account's password)? | "Yes, delete it (Recommended)" — deleted by the lead; `ls` and MediaStore no longer list it. Closes A17 of the 2026-10-24 run. Rotating that test account's password stays the Admin's call. |
| (lead) Delete `semperdic-app/posture_20261024.log` (A18) | Approved with the plan below; deleted 2026-10-28 (git-ignored file; a copy of its rows is in the session scratchpad as evidence) |

### Scope of the fix round (asked while planning, 2026-10-28)

The Admin, on the pending list: "leaving legal items as pending, let's plan to complete
everything else". The plan was approved as written. Legal items left pending: TE5-003,
TE5-004, TE5-103, AE3-113, AE2-103, TD-36, TD-277, TD-317, every `docs/legal/` change; the
hosted `/privacy` and `/terms` stay at `b940cc29`.

| Question | Answer (verbatim) |
|---|---|
| TE3-102: institution IT can seat any verified Demo account from any domain, and the add/import replies reveal whether an address is already licensed or invited elsewhere. How should IT adds work? | "Enforce domain lock (Recommended)" |
| TE2-101: phones (arm64) use fused multiply-add in one OpenCV routine; every CI-tested build (x86_64) does not. How should it be handled? | "Document + arm64 check (Recommended)" |
| TE2-003 (arm64 solver smoke) and TE1-003 (install-over from v1.2-beta.4, versionCode 1036) need a real arm64 phone and the 1036 APK. The only one attached is the Galaxy S22+ benchmark phone. May I use it? | "Yes, use the S22+" |
| Which missing check tools may be added as dev/CI dependencies? | "Schemathesis (TE3-007), Lighthouse (AE2-110), Android dependency scan (TE5-105), semgrep (TE5-105)" |
| AE3-003 / TD-340 / TD-341: what should the two tiers be called in the app, console and backend? (Terms wording stays with counsel.) | "Demo / Licensed (Recommended)" |
| AE3-110: the console says "Semper DIC" in tab titles, landing pages and the staff authenticator entry, against ADR-019's "Semper". Rename what? | "evrything, issuer too. but its "semper" not "Semper"" |
| The brand is lowercase "semper". Where should that apply? | "Everywhere user-facing" |
| TE1-001 / TD-318: release builds ship no certificate pin. Which pin set should release builds require? | "Google Trust Services roots (Recommended)" |
| TE2-002 and TE2-004 need changes in the engine repository, a new engine tag and a pin bump. Include them? | "Yes, separate engine PRs" |

### During Phase 4 (2026-10-28)

| Question | Answer (verbatim) |
|---|---|
| The fork's app is named "Semper Classroom" in some console and app text. Under the lower-case brand rule, how should it read? | "semper Classroom (Recommended)" |
| On the operator desk, the Demo analysis cap now reads "Demo limit (25)". Keep that or shorten it? | "Demo limit (25) (Recommended)" |
| Engine PRs #27/#28 add tests only. But a new tag from engine main also brings 4 commits that change solver output in edge cases. How should the app's engine pin move? | "Keep v0.6.1 for now (Recommended)" — TE2-002 and TE2-004 are fixed in the engine repository (semper-dic-engine#27, #28, to merge into engine `main`); the app stays pinned at v0.6.1 for this release. Adopting the next engine tag is its own ORACLE-CHANGE with DICe / steel_24 re-validation (TD row) |

### Checkpoint 2: acceptance (2026-10-29)

The pack showed, for each of the 72 "fix now" findings: its commits, files and line counts, the
guarding test and its fail-then-pass or mutation evidence, its re-verify round (R1 to R4), the gate
results on `d011d96ef`, and the before and after screens.

| Question | Answer (verbatim) |
|---|---|
| Accept the 67 independently verified fixes, plus TE2-003 on the S22+ evidence (arm64 smoke 12/12 twice)? | "Accept all (Recommended)" |
| AE1-111: Home's other dialogs and the Re-run picker sheet still close on rotation or a font-size change. What should happen to them? | "Rework round 3 (Recommended)" |
| TE3-102 held invites add a new kind of doc (licenseInvites/held_{licenseId}_{hash}) to an existing Firestore collection. Accept that schema addition? | "Accept (Recommended)" |
| Which tracked remainders do you accept as owner calls (TD-355 shortcut, TD-356 migrate, TD-360 no a11y tool)? | No preference given. All three stay open as owner decisions in REPORT §4; none is accepted |

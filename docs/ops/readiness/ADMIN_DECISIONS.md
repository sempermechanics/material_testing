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
| AE1-011, AE1-010, TE2-002, TE2-004 | REOPENED (partial; the rest is large or in the engine repo) | "Accept partial + TD rows (Recommended)": AE1-011 → TD-345; AE1-010: the PDF report is English-only by decision; TE2-002 → TD-346; TE2-004 → TD-347 and TD-326 |
| New in the after screenshots: the ROI info pill covers the top handles at font scale 2.0; Save graph looks enabled on the lattice's empty state | — | "Fix in the rework batch (Recommended)": new P3 findings AE1V-023, AE1V-024 |

Not asked at this checkpoint: NEEDS_ADMIN items wait on owner decisions or Admin actions.
- AE3-003 → TD-340/TD-341.
- AE1-017 → TD-342.
- AE1V-016 → TD-343.
- TE6-002 and TE6-003: `apply-monitoring.sh --apply`.
- TE2-005 → TD-327.

NEEDS_DEVICE items:
- TE1-003 → TD-344.
- TE2-003: the arm64 smoke.

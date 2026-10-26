# Readiness run 2026-10-24…26 — report

Production-readiness review of `semperdic-app` (**S**) and its fork `material_testing`
(**M**), closed out on 2026-10-26. Records: [FINDINGS.yaml](FINDINGS.yaml) (139 findings),
[BASELINE.md](BASELINE.md), [INVENTORY.md](INVENTORY.md), the Admin's answers in
[ADMIN_DECISIONS.md](../ADMIN_DECISIONS.md) (Checkpoints 1 and 2) and the rules in
[RUBRIC_ADDENDA.md](../RUBRIC_ADDENDA.md). Fix logs, re-verify results and gate output were
kept outside the repository, in the session scratchpad, like the baseline's logs.

| Tree | Start | Readiness head |
|---|---|---|
| S | `875e2334` (engine `f072da0`, v0.6.1) | `claude/readiness-20261024` at `6324ea7f` (code head `57e799b8`; `6324ea7f` adds only screenshots). It merges the backend, ops and console branches (`0fd3aaa9`) and `main`'s #484 (`69b72eb6`). Not pushed, not merged, not deployed |
| M | `839d4285` (engine `1284edf`, v0.2.3) | `claude/readiness-20261024-engine-pin` at `fb436770`. Not pushed |

Re-verify: the Phase 5 re-verify ran on `2ebe7a2a` and covered 102 fix ids (84 VERIFIED,
10 REOPENED, 6 NEEDS_ADMIN, 2 NEEDS_DEVICE; ADMIN_DECISIONS, Checkpoint 2). Six reopened
ids were reworked, and three findings were added after it (AE1V-023 and AE1V-024 at
Checkpoint 2, AE1V-025 in its second round). Those nine ids are **PENDING-REVERIFY-2**
below: AE3-002, AE1-004, AE1-007, AE1-009, AE1V-021, TE1-006, AE1V-023, AE1V-024 and
AE1V-025. Their second re-verify and the rework after it are the lead's to record here.

## 1. Summary and go/no-go

**Verdict: No-go** until all of the following hold:

1. **The five P1s left unfixed are fixed or risk-accepted in writing.** They are TE1-001,
   TE5-003, TE5-004, TE6-001 and TE6-011. The rule is the Admin's (ADMIN_DECISIONS,
   Checkpoint 1). Their rows are in the gate's
   [Risk acceptance](../../PRODUCTION_READINESS_GATE.md#risk-acceptance) table, unsigned and
   marked "Admin to sign" (A10).
2. **The second re-verify is in.** It must return VERIFIED for AE1-009, the one P1 among the
   PENDING-REVERIFY-2 ids. It must also settle the other eight.
3. **The branch is merged to `main` through a PR whose `CI OK` passes** (A1, A2). That run
   is the first in CI for `engine-pin` and `engine-host-sanitized`, and the first in
   GitHub Actions for `console-a11y`. It also runs gitleaks, which was not run locally.
4. **The backend is deployed from that `main`** (A4). Until then, production runs the lock
   that TE5-001 found holding 19 known advisories, and TE4-004's switches do not exist
   there.

TE5-002 (pre-ticked consent) does not block: it was rejected ("Product decision stands").
Observations are reported only.

### Scorecard

Counts use `severity_final`. FINDINGS.yaml gives each finding the axis `technical` or
`aesthetic` only. The **Objective** rows are therefore a cut, not a third axis: gate G1
(native code and measurement output), whose findings are also counted under Technical or
Aesthetic. *Open* means not settled in the repository: NEEDS_ADMIN / NEEDS_DEVICE /
PENDING-REVERIFY-2. *Deferred* includes the five blocking P1s and the seven
material_testing findings left for Phase 8.

| Axis | Severity | Found | Fixed + verified | Accepted partial | Deferred | Rejected | Open (Admin / device / re-verify 2) | Duplicate | Observation |
|---|---|---:|---:|---:|---:|---:|---|---:|---:|
| Technical | P0 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 |
| Technical | P1 | 17 | 8 | 0 | 5 | 1 | 1 (0 / 0 / 1) | 2 | 0 |
| Technical | P2 | 38 | 25 | 3 | 3 | 0 | 7 (4 / 2 / 1) | 0 | 0 |
| Technical | P3 | 13 | 13 | 0 | 0 | 0 | 0 | 0 | 0 |
| Technical | observation | 9 | 0 | 0 | 0 | 0 | 0 | 0 | 9 |
| **Technical** | **all** | 78 | 47 | 3 | 8 | 1 | 8 (4 / 2 / 2) | 2 | 9 |
| Aesthetic | P1 | 13 | 12 | 0 | 0 | 0 | 0 | 1 | 0 |
| Aesthetic | P2 | 32 | 21 | 0 | 4 | 0 | 4 (2 / 0 / 2) | 3 | 0 |
| Aesthetic | P3 | 11 | 4 | 1 | 1 | 0 | 5 (0 / 0 / 5) | 0 | 0 |
| Aesthetic | observation | 5 | 0 | 0 | 0 | 0 | 0 | 0 | 5 |
| **Aesthetic** | **all** | 61 | 37 | 1 | 5 | 0 | 9 (2 / 0 / 7) | 4 | 5 |
| Objective (G1) | P0 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 |
| Objective (G1) | P1 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 |
| Objective (G1) | P2 | 6 | 1 | 2 | 0 | 0 | 3 (2 / 1 / 0) | 0 | 0 |
| Objective (G1) | P3 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 |
| Objective (G1) | observation | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 2 |
| **Objective (G1)** | **all** | 11 | 4 | 2 | 0 | 0 | 3 (2 / 1 / 0) | 0 | 2 |
| **All findings** | **all** | 139 | 84 | 4 | 13 | 1 | 17 (6 / 2 / 9) | 6 | 14 |
| All | P0 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 |
| All | P1 | 30 | 20 | 0 | 5 | 1 | 1 (0 / 0 / 1) | 3 | 0 |
| All | P2 | 70 | 46 | 3 | 7 | 0 | 11 (6 / 2 / 3) | 3 | 0 |
| All | P3 | 24 | 17 | 1 | 1 | 0 | 5 (0 / 0 / 5) | 0 | 0 |
| All | observation | 14 | 0 | 0 | 0 | 0 | 0 | 0 | 14 |

### Go/no-go per gate

Gates as the lanes defined them: G1 native/measurement, G2 security/authz/privacy, G3
backend contract/ops, G4 licensing, G5 Android, G6 release, UX (aesthetic).

| Gate | Findings | Verified | P0/P1 not verified | Status | What is missing |
|---|---:|---:|---|---|---|
| G1 | 11 | 4 | — (P0 MT-TE2-001 VERIFIED on the fork branch) | **Go with conditions** for Semper. **No-go** for a Semper Classroom release | Semper: real-data validation at the pin, TD-327 (A14); the arm64 smoke (A13); the precision decision, TD-342 (A12). Fork: `fb436770` on its `main` (A3, Phase 8) |
| G2 | 19 | 12 | TE5-003, TE5-004 (deferred) | **No-go** | TE5-003 and TE5-004 fixed by counsel and the owner (A11) or signed (A10). TE5-001's lock is live only after A4 |
| G3 | 15 | 7 | TE6-001, TE6-011 (deferred) | **No-go** | Monitoring applied with a channel (A6); posture FAILs fixed (A9); or both signed (A10) |
| G4 | 14 | 8 | — | **Go with conditions** | AE1V-023, AE1V-024 and AE1V-025 (P3) re-verified. The entitlement key, TD-215 (A7), before offline licensing is enforced |
| G5 | 4 | 2 | — | **Go with conditions** | TE1-006 re-verified; the 1036 install-over smoke, TD-344 (A13) |
| G6 | 14 | 11 | TE1-001 (deferred) | **No-go** | Certificate pins chosen (A8) or TE1-001 signed (A10); the PR's first `CI OK` with the new release checks (A1) |
| UX | 62 | 40 | AE1-009 (PENDING-REVERIFY-2) | **No-go until re-verified** | AE1-009 VERIFIED; AE3-002, AE1-004, AE1-007 and AE1V-021 settled; owner decisions TD-340, TD-341 and TD-343 (A12) |

## 2. Gate statuses

Numbers are from the lead's runs on the two heads below. Logs are in the session scratchpad.

**Merged head `2ebe7a2a`** (backend, ops and console merged; `main`'s #484 merged in):

| Gate | Command | Result |
|---|---|---|
| Backend tests | `cd backend && python -m pytest tests/ -q --cov=app --cov-fail-under=93` | 2262 passed, 83 skipped; coverage **96.87 %** (floor 93) |
| ruff | `ruff check app/ tests/ scripts/ ../scripts/` (with bandit `S` rules since TE5-010) | clean |
| pip-audit | `pip-audit -r requirements.lock --require-hashes --disable-pip --strict` (the lock, since TE5-001) | clean |
| Console tests | `node --test firebase-hosting/tests/*.test.mjs`; `node scripts/console_coverage.mjs … --lines=96` | 444 pass; coverage **98.76 %** (floor 96) |
| Console accessibility | `npm run test:a11y` in `firebase-hosting/` (axe-core + Playwright, AE2-001) | 20 checks pass |
| Firestore rules | `firebase emulators:exec --only firestore "node --test"` in `firestore-rules-tests/` | 61 pass |
| Script checks | `check_doc_paths`, `check_framework_coverage`, `render_legal_pages --check`, `check_console`, `check_engine_pin`, `unittest discover scripts/tests` and the others | all pass |

**Rework head `57e799b8`** (`6324ea7f` adds only screenshots):

| Gate | Command | Result |
|---|---|---|
| Android tier 1 + release | `gradlew.bat ciReleaseGate :app:koverLog :app:koverVerify :app:compileDebugAndroidTestKotlin` | BUILD SUCCESSFUL: 2843 unit tests, 0 failed; spotless, detekt, lint clean, no baseline entry; R8, `assembleRelease` |
| Oracles | `DatFieldOracleTest`, `SummaryGifOracleTest` (in the run above) | pass; `app/src/test/resources/oracles/` unchanged |
| Kover | `:app:koverLog :app:koverVerify` | lines **81.97 %** (floor 78) |
| Console tests | as above | 444/444 |

**GCP posture**, run 2026-10-24 with `scripts/check_gcp_posture.sh` (read-only; flags as
in ADMIN_DECISIONS, Scope). Output redacted: **17 FAIL, 0 UNKNOWN**. 3.4-check-revoked moved
from FAIL to PASS on `semper-api` and passes on staging too. The FAILs, all fixed by console
or `gcloud` (A9):
- 2.1: no lien, no `Prod` tag, one owner, granted to a user (×4 checks on each project, 8).
- 2.4: Firebase API keys without app/site restriction (2).
- 2.7: delete protection off on the app project (1).
- 3.1: `APP_CHECK_MODE=off` on both services (2; TD-226).
- 3.5: no `ENTITLEMENT_SIGNING_KEY` on both services (2; TD-215).
- 3.7: no DATA_READ + DATA_WRITE audit logs on either project (2).

**Not run locally:**
- gitleaks: not installed; CI's `secret-scan` job runs it.
- shellcheck: not installed; CI runs it.
- The API-30 instrumented suite: `VideoFrameExtractionDeviceTest` ×4 fails there, pre-existing (TD-328).
- The new CI jobs `engine-pin` and `engine-host-sanitized`: never run in CI; they run on the PR.
- The backend-lock workflow's Linux check of the lock regenerated on Windows (TE5-001).

## 3. Findings ledger

One row per finding, in FINDINGS.yaml order. Decision: Checkpoint 1 (CP1) unless marked
CP2. Commits are on `claude/readiness-20261024` unless marked material_testing. "(TD row)"
means the commit only filed the TECH_DEBT row.

| Id | Axis | Gate | Severity | Decision (short; verbatim in ADMIN_DECISIONS) | Outcome | Fix commit(s) |
|---|---|---|---|---|---|---|
| MT-TE2-001 | T | G1 | P0 | Fix now | VERIFIED | `bab8d6f0`, `fb436770` (material_testing) |
| AE1-001 | A | UX | P1 | Fix now | VERIFIED | `e6015560`, `041fba00` |
| AE1-002 | A | UX | P1 | Fix now | VERIFIED | `e6015560` |
| AE1-006 | A | UX | P1 | Fix now | VERIFIED | `05cf4fa4` |
| AE1-009 | T | UX | P1 | Fix now; rework now (CP2) | PENDING-REVERIFY-2 | `5074cf33`, `ceb263ae` |
| AE1-014 | T | G4 | P1 | Fix now: gate it | VERIFIED | `92bf1383` |
| AE1-016 | T | G1 | P1 | Fix now | VERIFIED | `b91a2fe7` |
| AE1V-001 | A | UX | P1 | Fix now | VERIFIED | `012d1b57`, `89c80260` |
| AE1V-002 | A | UX | P1 | Fix now | VERIFIED | `012d1b57` |
| AE1V-003 | A | UX | P1 | Fix now | VERIFIED | `012d1b57`, `2294e285`, `84c68ee0` |
| AE1V-004 | T | UX | P1 | Fix now | VERIFIED | `8d26716b` |
| AE1V-005 | T | UX | P1 | Fix now | VERIFIED | `8d26716b` |
| AE1V-006 | A | UX | P1 | Duplicate; earlier decision applies | duplicate→AE1-006 | — |
| AE1V-007 | A | UX | P1 | Fix now | VERIFIED | `05cf4fa4` |
| AE1V-008 | A | UX | P1 | Fix now | VERIFIED | `012d1b57` |
| AE1V-009 | T | G2 | P1 | Duplicate; earlier decision applies | duplicate→TE5-002 | — |
| AE1V-010 | A | UX | P1 | Fix now | VERIFIED | `486fb59a`, `37bff590` |
| AE1V-012 | A | UX | P1 | Fix now | VERIFIED | `e6015560` |
| AE2-002 | A | UX | P1 | Fix now | VERIFIED | `c2cfc227` |
| AE2-004 | A | UX | P1 | Fix now | VERIFIED | `830f7cfc` |
| TE1-001 | T | G6 | P1 | Defer (TECH_DEBT, with TD-218) | DEFERRED→TD-318 | `7535e69d` (TD row) |
| TE3-006 | T | G3 | P1 | Fix now: snapshot only | VERIFIED | `e576f741` |
| TE4-001 | T | G4 | P1 | Fix now (code half; key = TD-215) | VERIFIED | `c9fefccc` |
| TE4-002 | T | G4 | P1 | Fix now | VERIFIED | `18b3ac65`, `64f87f65` |
| TE4-003 | T | G4 | P1 | Duplicate; earlier decision applies | duplicate→TE3-001 | — |
| TE5-001 | T | G2 | P1 | Fix now (Admin deploys) | VERIFIED | `4fc82b14` |
| TE5-002 | T | G2 | P1 | Reject: keep pre-ticked | REJECTED | — |
| TE5-003 | T | G2 | P1 | Defer (owner/counsel) | DEFERRED→TD-36 | `7535e69d` (TD row) |
| TE5-004 | T | G2 | P1 | Defer (owner/counsel) | DEFERRED→TD-317 | `7535e69d` (TD row) |
| TE6-001 | T | G3 | P1 | Defer to Admin actions | DEFERRED→TD-217 | — |
| TE6-011 | T | G3 | P1 | Defer to Admin actions | DEFERRED→TD-221–TD-225 | — |
| AE1-003 | A | UX | P2 | Fix all now | VERIFIED | `e6015560` |
| AE1-004 | A | UX | P2 | Fix all now; rework now (CP2) | PENDING-REVERIFY-2 | `e6015560`, `dac42179`, `57e799b8` |
| AE1-005 | A | UX | P2 | Fix all now | VERIFIED | `e6015560` |
| AE1-010 | T | UX | P2 | Fix all now; accept partial (CP2) | ACCEPTED-PARTIAL (PDF English-only by decision; TD-334) | `5074cf33`, `804c7334` |
| AE1-012 | A | UX | P2 | Fix all now | VERIFIED | `05cf4fa4` |
| AE1-013 | T | UX | P2 | Fix all now | VERIFIED | `6707004b` |
| AE1-015 | A | G4 | P2 | Fix all now | VERIFIED | `0718319e` |
| AE1-017 | T | G1 | P2 | Fix all now | NEEDS_ADMIN→TD-342 | `93070031` |
| AE1-019 | A | UX | P2 | Fix all now | VERIFIED | `0e6d6210` |
| AE1V-011 | A | UX | P2 | Fix all now | VERIFIED | `adc4c4cf` |
| AE1V-013 | A | UX | P2 | Duplicate; earlier decision applies | duplicate→AE1-019 | — |
| AE1V-014 | A | UX | P2 | Fix all now | VERIFIED | `012d1b57`, `dde38d32` |
| AE1V-015 | T | UX | P2 | Fix all now | VERIFIED | `7314c16b` |
| AE1V-016 | A | UX | P2 | Fix all now | NEEDS_ADMIN→TD-343 | `9a34bf0b` |
| AE1V-018 | A | UX | P2 | Fix all now | VERIFIED | `486fb59a` |
| AE2-001 | A | UX | P2 | Fix all now | VERIFIED | `3a9fb8b5` |
| AE2-003 | A | UX | P2 | Fix all now | VERIFIED | `056a1e36` |
| AE2-005 | A | UX | P2 | Fix all now | VERIFIED | `97ff09e0` |
| AE2-006 | A | UX | P2 | Fix all now | VERIFIED | `4c8bea9d`, `a46b2d88` |
| AE2-007 | A | UX | P2 | Fix all now | VERIFIED | `04e68fc6` |
| AE2-009 | A | UX | P2 | Fix all now | VERIFIED | `c54729b1` |
| AE2-010 | A | UX | P2 | Fix all now | VERIFIED | `26ebf63b` |
| AE2V-001 | A | UX | P2 | Fix all now | VERIFIED | `66fdcefa` |
| AE2V-002 | A | UX | P2 | Duplicate; earlier decision applies | duplicate→AE2-006 | — |
| AE2V-003 | A | UX | P2 | Duplicate; earlier decision applies | duplicate→AE2-007 | — |
| AE3-001 | A | UX | P2 | Fix all now | VERIFIED | `dc9dcffb`, `ffd468fd`, `ad5059d4` |
| AE3-002 | A | UX | P2 | Fix all now; rework now (CP2); keep 'device' for the key (CP2 round 2) | PENDING-REVERIFY-2 | `dc9dcffb`, `ad5059d4`, `e570834e` |
| AE3-003 | A | UX | P2 | Fix all now | NEEDS_ADMIN→TD-340, TD-341 | `dc9dcffb`, `ad5059d4` |
| AE3-004 | A | UX | P2 | Fix all now | VERIFIED | `ad5059d4` |
| AE3-005 | A | UX | P2 | Fix all now | VERIFIED | `dc9dcffb`, `ad5059d4` |
| AE3-006 | A | UX | P2 | Fix all now | VERIFIED | `dc9dcffb`, `ffd468fd` |
| AE3-007 | A | UX | P2 | Fix all now | VERIFIED | `ad5059d4` |
| MT-001 | T | G6 | P2 | Fix with the P0 port | DEFERRED→Phase 8 (fork port) | — |
| MT-AE1-001 | A | UX | P2 | Fix with the P0 port | DEFERRED→Phase 8 (fork port) | — |
| MT-AE2-001 | A | UX | P2 | Fix with the P0 port | DEFERRED→Phase 8 (fork port) | — |
| MT-AE2-002 | A | UX | P2 | Fix with the P0 port | DEFERRED→Phase 8 (fork port) | — |
| MT-AE3-001 | A | UX | P2 | Fix with the P0 port | DEFERRED→Phase 8 (fork port) | — |
| MT-TE5-001 | T | G2 | P2 | Fix with the P0 port | DEFERRED→Phase 8 (fork port) | — |
| TE1-003 | T | G5 | P2 | Fix all now | NEEDS_DEVICE→TD-344 | `53c09cb6` |
| TE1-004 | T | G5 | P2 | Fix all now | VERIFIED | `0444b326` |
| TE1-005 | T | G5 | P2 | Fix all now | VERIFIED | `40bd4811` |
| TE1-006 | T | G5 | P2 | Fix all now; rework now (CP2); rework once more (CP2 round 2) | PENDING-REVERIFY-2 | `98f8a050`, `49c8d25e` |
| TE1-007 | T | G6 | P2 | Fix all now | VERIFIED | `1d33a1e1` |
| TE2-002 | T | G1 | P2 | Fix all now; accept partial (CP2) | ACCEPTED-PARTIAL→TD-346 | `d5e5291a` |
| TE2-003 | T | G1 | P2 | Fix all now | NEEDS_DEVICE→A13 (arm64 smoke) | `d8a7687e` |
| TE2-004 | T | G1 | P2 | Fix all now; accept partial (CP2) | ACCEPTED-PARTIAL→TD-347, TD-326 | `c7c7b931` |
| TE2-005 | T | G1 | P2 | Fix all now | NEEDS_ADMIN→TD-327 | `0c84ba52` |
| TE2-006 | T | G1 | P2 | Fix all now | VERIFIED | `690f57e2` |
| TE3-001 | T | G2 | P2 | Fix all now | VERIFIED | `75ec068b` |
| TE3-002 | T | G2 | P2 | Fix all now | VERIFIED | `75ec068b` |
| TE3-003 | T | G2 | P2 | Fix all now | VERIFIED | `75ec068b` |
| TE3-007 | T | G3 | P2 | Fix all now; dependency not approved | DEFERRED→TD-319 | `7535e69d` (TD row) |
| TE3-008 | T | G3 | P2 | Fix all now | VERIFIED | `7ff2e9d3` |
| TE3-009 | T | G3 | P2 | Fix all now | VERIFIED | `f6266ee8` |
| TE4-004 | T | G4 | P2 | Fix all now | VERIFIED | `15beec58`, `b4134c33` |
| TE4-005 | T | G4 | P2 | Fix all now | VERIFIED | `fd970c1c` |
| TE4-006 | T | G4 | P2 | Fix all now | VERIFIED | `7b28746f` |
| TE5-005 | T | G2 | P2 | Fix all now | VERIFIED | `e8c74275` |
| TE5-007 | T | G2 | P2 | Fix all now | VERIFIED | `2ca74d64` |
| TE6-002 | T | G3 | P2 | Fix all now | NEEDS_ADMIN→TD-217 (A1) | `f2b50227` |
| TE6-003 | T | G3 | P2 | Fix all now | NEEDS_ADMIN→TD-217 (A1) | `f2b50227` |
| TE6-004 | T | G3 | P2 | Fix all now | VERIFIED | `8d3d77be` |
| TE6-005 | T | G6 | P2 | Fix all now | VERIFIED | `58c368ad` |
| TE6-006 | T | G6 | P2 | Fix all now | VERIFIED | `6bdffaae` |
| TE6-007 | T | G6 | P2 | Fix all now | VERIFIED | `09e7fd02` |
| TE6-008 | T | G6 | P2 | Fix all now | VERIFIED | `fd811aea` |
| TE6-009 | T | G6 | P2 | Fix all now | VERIFIED | `e912af3d` |
| TE6-010 | T | G3 | P2 | Fix all now | VERIFIED | `a30686dd` |
| TE6-012 | T | G6 | P2 | Fix all now | VERIFIED | `8e19ecad` |
| TE6-015 | T | G6 | P2 | Fix all now | VERIFIED | `66c9dacc` |
| AE1-007 | A | UX | P3 | Fix all now; rework now (CP2) | PENDING-REVERIFY-2 | `e6015560`, `e21afdb4` |
| AE1-011 | A | UX | P3 | Fix all now; accept partial (CP2) | ACCEPTED-PARTIAL→TD-345 | `64631979` |
| AE1-018 | T | G1 | P3 | Fix all now | VERIFIED | `b91a2fe7` |
| AE1V-020 | A | UX | P3 | Fix all now | VERIFIED | `afdf7c3a` |
| AE1V-021 | A | UX | P3 | Fix all now; rework now (CP2) | PENDING-REVERIFY-2 | `afdf7c3a`, `9b54bc57`, `ba1f906b` |
| AE2-008 | A | UX | P3 | Fix all now | VERIFIED | `4e791716`, `7303291a`, `fe6ad8ca` |
| AE2-011 | A | UX | P3 | Fix all now | VERIFIED | `c4868232` |
| AE3-008 | A | UX | P3 | Fix all now | VERIFIED | `dc9dcffb`, `ad5059d4` |
| MT-AE3-002 | A | UX | P3 | Fix with the P0 port | DEFERRED→Phase 8 (fork port) | — |
| TE1-002 | T | G6 | P3 | Fix all now | VERIFIED | `2408b8e0` |
| TE3-004 | T | G2 | P3 | Fix all now | VERIFIED | `d084849d` |
| TE3-005 | T | G3 | P3 | Fix all now | VERIFIED | `75ec068b` |
| TE3-010 | T | G3 | P3 | Fix all now | VERIFIED | `02e30d26` |
| TE3-011 | T | G2 | P3 | Fix all now | VERIFIED | `bb397ef1` |
| TE4-007 | T | G4 | P3 | Fix all now | VERIFIED | `d7d1655b`, `9edccd27` |
| TE5-006 | T | G2 | P3 | Fix all now | VERIFIED | `7745d826` |
| TE5-008 | T | G2 | P3 | Fix all now | VERIFIED | `73442ca6` |
| TE5-009 | T | G2 | P3 | Fix all now | VERIFIED | `7d72d265` |
| TE5-010 | T | G2 | P3 | Fix all now | VERIFIED | `67dc8858` |
| TE6-013 | T | G6 | P3 | Fix all now | VERIFIED | `eb526fa1` |
| TE6-014 | T | G6 | P3 | Fix all now | VERIFIED | `bf40b182` |
| AE1-008 | A | UX | observation | Reported only | observation | — |
| AE1-020 | A | UX | observation | Reported only | observation | — |
| AE1V-017 | A | UX | observation | Reported only | observation | — |
| AE1V-019 | A | UX | observation | Reported only | observation | — |
| AE1V-022 | A | G4 | observation | Reported only | observation | — |
| AE1V-023 | A | G4 | P3 | Fix in the rework batch (CP2) | PENDING-REVERIFY-2 | `ca66d9c1` |
| AE1V-024 | A | G4 | P3 | Fix in the rework batch (CP2) | PENDING-REVERIFY-2 | `224a9f7f` |
| AE1V-025 | A | G4 | P3 | Fix in the same batch (CP2 round 2) | PENDING-REVERIFY-2 | — |
| MT-TE3-001 | T | G3 | observation | Reported only | observation | — |
| TE1-008 | T | G6 | observation | Reported only | observation | — |
| TE2-001 | T | G1 | observation | Keep fast-math (ADDENDUM-1) | observation | — |
| TE2-007 | T | G1 | observation | Reported only | observation | — |
| TE3-012 | T | G3 | observation | Reported only | observation | — |
| TE4-008 | T | G4 | observation | Reported only | observation | — |
| TE5-011 | T | G2 | observation | Reported only | observation | — |
| TE5-012 | T | G2 | observation | Reported only | observation | — |
| TE6-016 | T | G3 | observation | Reported only | observation | — |

## 4. External actions owed by the Admin

The numbering is this report's. The standing rows live in the gate's
[Admin actions](../../PRODUCTION_READINESS_GATE.md#admin-actions-external) table, cited here
as "gate A-n". Order matters for A1 → A4 → A5.

| # | Action | Why | Finding / TD | How to verify |
|---|---|---|---|---|
| A1 | Merge `origin/main` into `claude/readiness-20261024`, push it, open the PR to `main` | Nothing is pushed. `main` gained #486 (it records the #484 deploy) after this branch merged #484, so the top of CHANGELOG.md and CONTEXT's Current state conflict: keep #486's deploy entry and this branch's Current state. `engine-pin`, `engine-host-sanitized` and `console-a11y` have not run in GitHub Actions | TE2-006, TE2-004, AE2-001, TE5-001 | `CI OK` green on the PR, including those three jobs, `secret-scan` and the backend-lock Linux check |
| A2 | Secret scan | gitleaks was not installed here; nothing on this branch was scanned locally | — | The PR's `secret-scan` job passes; or `gitleaks detect --config .gitleaks.toml` |
| A3 | material_testing: push `claude/readiness-20261024-engine-pin` (`fb436770`) and open its PR. Cut no release from `839d4285`, and confirm no internal build of it reached testers | `839d4285` pins engine v0.2.3; an incremental run fails with a missing JNI symbol (P0) | MT-TE2-001 | Fork `CI OK` green, including `check_jni_parity`; `git ls-tree origin/main engine` on the fork shows `f072da0` |
| A4 | Deploy the backend from `main` after the merge: `deploy-backend.yml`, staging then production (required reviewer) | Lock bump: pyjwt 2.15.1, urllib3 2.8.0, certifi 2026.10.23; the live lock has 19 advisories. Kill switches on `GET …/uploads` and `PUT …/metadata` (`backend/app/routers/sessions.py:140`, `:223`). `/readyz` within 8 s. uvicorn `--no-access-log`. Device history answers 503 on a Firestore outage. Roster-import line numbers and 413. Quota wording. No gateway change: `backend/gateway/` is unchanged since `46ac8b5e` | TE5-001, TE4-004, TE3-010, TE3-011, TE3-008, AE2-008, AE3-001 | `/readyz` 200 on the candidate; the gateway job reports no diff; Tier 3 on `main` green after the staging deploy; a CHANGELOG entry with both revisions |
| A5 | Deploy Hosting after A4, from a worktree of the merged `main`. Take `firebase-hosting/public/privacy/index.html` and `firebase-hosting/public/terms/index.html` from `b940cc29`. Then run `API_BASE_URL=<INDIC_API_BASE_URL> ./scripts/deploy-console.sh` ([checklist](../../DEPLOY_CHECKLIST_2026-10.md) §4) | Ships the console fixes (AE2-001…AE2-011, AE3 wording, AE3-002's **New phone**, which OPERATING_MANUAL already names). A plain-`main` deploy publishes #474's legal text, which counsel has not cleared (TD-277); that happened on 2026-10-26 and was redeployed the same day (#486). The import messages read the new backend's codes (AE2-008) | AE2-*, AE3-*, TE5-007 | `check_live_console.py` passes; the live `/privacy` and `/terms` equal `b940cc29`'s files; the IT roster shows a **Phone** column |
| A6 | Create a notification channel, then run `scripts/apply-monitoring.sh --project indicvision-dic-app --notification-channel <id>` (dry run), then `--apply`, then the `run.invoker` grant it prints | No alert reaches anyone (P1). The new gateway uptime checks and the five new policies exist only as code | TE6-001, TE6-002, TE6-003; TD-217; gate A1 | 13 policies, 3 uptime checks and 4 dashboards exist; a test notification reaches the channel |
| A7 | Provision the entitlement key ([BACKEND_SETUP_GCP.md](../../../backend/BACKEND_SETUP_GCP.md) A8), then set the release variables `SEMPER_ENTITLEMENT_PUBKEYS` and `SEMPER_REQUIRE_ENTITLEMENT_PINS=true` | TE4-001's code half is done: `release.yml` passes `--require-entitlement-pins` once either variable is set (`.github/workflows/release.yml:263`). Until the key exists, no token is issued and the cached licence decides offline | TE4-001; TD-215; gate A4 | `/v1/config` carries `entitlement`; posture 3.5 PASS; a release's `entitlement-pins` check PASS |
| A8 | Decide certificate pins: set `SEMPER_API_CERT_PINS`, then have `--require-pins` added; or sign TE1-001 in Risk acceptance | The release gate never requires a pin (P1, deferred) | TE1-001, TE5-012; TD-318, TD-218; gate A5 | `cert-pins` PASS in a release, or the TE1-001 row signed |
| A9 | Fix the 17 posture FAILs by console or `gcloud`, then re-run `scripts/check_gcp_posture.sh` | P1, deferred to the Admin | TE6-011; TD-221–TD-225, TD-226, TD-215; gate A2 | A run with FAIL only on 3.1 (until Play, gate A8) and 3.5 (until A7), recorded in the gate |
| A10 | Sign the five Risk acceptance rows (owner, expiry, compensating control), or clear each by its fix | The Go rule (ADMIN_DECISIONS, Checkpoint 1) | TE1-001, TE5-003, TE5-004, TE6-001, TE6-011 | Each row signed in the gate, then the Sign-off table |
| A11 | Counsel on the legal text | Operator fields are still bracketed. The Privacy Policy omits Analytics, App Check and IP data. #474's demo-upload text is held. The AE3 wording for the Terms and Privacy Policy was listed for counsel, not edited. Whether a pre-ticked consent holds under DPDP s.6 is TE5-002's external action | TE5-003, TE5-004, TE5-002, AE3-001/-003/-004/-008; TD-277, TD-36, TD-317; gate A10 | TD-36, TD-317 and TD-277 closed; pages re-rendered (`render_legal_pages.py --check`) and deployed by A5's method without the hold |
| A12 | Owner decisions | The paid-tier name (TD-340). The capitalisation of Demo (TD-341). Precision per quantity, which changes reported output (TD-342). Sign-off of the beta-notice wording (TD-343). The support window for 1036 holders on Android 7–10 (TE1-008). Gate A14's two | AE3-003, AE1-017, AE1V-016, TE1-008; gate A14 | Each decision recorded in ADMIN_DECISIONS and its TD row |
| A13 | Device checks | Install the 1036 release, create an analysis, install this build over it (TD-344). Run `EnginePipelineSmokeTest` on one arm64 phone or AVD ([RELEASING.md](../../RELEASING.md) step 5); no automated job runs the solver on arm64 | TE1-003, TE2-003; TD-344; gate A13 | Both recorded in CHANGELOG for the next release |
| A14 | Real-data validation at the pin `f072da0`: DIC Challenge sample 5, PMMA 3-point bend and `steel_24` | [VALIDATION.md](../../../engine/VALIDATION.md) says "Not recorded at this pin" for them | TE2-005; TD-327 | Dated rows in VALIDATION.md |
| A15 | Permission to download the Zenodo source data (the gauge-point files and `3pointPMMA.zip`, about 749 MB) for the fork's `real_data_*.py compare` | [FORK_SYNC.md](../../FORK_SYNC.md) step 4 after an engine bump. The P0 fix was checked only against the arrays the fork's tests pin | MT-TE2-001 | `compare` run on the fork at `f072da0`; result in its CHANGELOG |
| A16 | Semper Classroom Firestore migration: dry run `backend/scripts/rename_classroom_app.py --project indicvision-dic-app`, then `--apply` once the dual-writing backend serves all traffic and a rollback past it is ruled out | ADR-030's hygiene step; it has not run. Staging shares the production database | TD-330 | The dry run's counts, then `--apply`; a second run is a no-op |
| A17 | Delete `/sdcard/shot.png` on the Semper_API30 AVD | A screenshot from this run shows a credential | — | `adb -s <serial> shell ls /sdcard/shot.png` reports no such file |
| A18 | Delete `posture_20261024.log` from the root of the main checkout (`semperdic-app`) | It is the unredacted posture output and names the owner account; only the redacted copy was used here | TE6-011 | The file is gone, and `git status` there does not list it |

## 5. What this run did not cover

- **Devices and stores.** No physical device: emulator checks ran on the Semper_API30 AVD
  (API 30, x86_64) only, never on arm64. No Play Console, no Play Integrity.
- **Live services.** No signed-in console session, no live staging calls: staging shares
  the production Firestore. One baseline test reached staging by accident and was refused
  before any write (BASELINE, "Incident").
- **Live behaviour of the fixes.** Nothing is deployed, so no backend or console fix has
  been observed live (A4, A5).
- **Local tools.** gitleaks and shellcheck were not installed.
- **Engine.** The engine's own repository: its tests are not this CI. `engine-host-sanitized`
  was never built here (no cmake or OpenCV). Left as handoffs to the engine repo: the
  1-vs-N thread test (TD-346), an arm64 CI job (no TD row) and metamorphic tests (TD-347).
- **Measurement validation.** Real-data validation at the pin (TD-327, A14) and the
  Zenodo compare (A15).
- **External testing.** No load test (TD-249). No pentest (TD-240). No schema fuzzing:
  Schemathesis and Hypothesis were not approved (TE3-007, TD-319).
- **Licence states.** Demo-locked and offline states on the debug emulator: the bypass
  always installs a licensed account (AE1V-022).
- **Fork.** material_testing beyond its P0 (Phase 8).
- **Owner and counsel.** Every decision that is theirs (A10–A12).

## 6. Phase 8 plan: port to material_testing (Semper Classroom)

1. **Prerequisites.**
   - A3: the fork's engine-pin PR merged to the fork's `main`.
   - A1: this branch merged to S `main`.
   - The user's `google-services.json` for `com.sempermechanics.semperclassroom`, which
     blocks the fork rename (Phase C).
2. **Base.** Build on the fork branch `claude/semper-classroom-app` (not pushed). It is
   fork `main` `6e42ed00` plus the sync of S `25334af1` (`48ffe3ce`), the rename, the
   Firebase client and floors 75/59. There, `ciReleaseGate` is green at 77.10 / 61.70 %.
3. **Sync.** Sync S `main` with the readiness merge per [FORK_SYNC.md](../../FORK_SYNC.md):
   - steps 1–5, including step 5's gitlink-equals-S check and `check_jni_parity`;
   - keep the fork's CONTEXT and TECH_DEBT;
   - keep the fork's inverted `SessionIndexExtrasTest` "dropped" case.
4. **Re-pick the TD ids against both registers before the merge.** The fork's register
   holds TD-319–TD-329 for readiness and calls TD-318 its next free id. S took TD-318 and
   TD-320–TD-347, and TD-330 is the rename's.
5. **Fix the fork-only findings with the port** (Checkpoint 1: "Fix with the P0 port"):
   MT-001, MT-AE1-001, MT-AE2-001, MT-AE2-002, MT-AE3-001, MT-AE3-002 and MT-TE5-001.
   Each gets a failing-first test, as in Phase 4.
6. **Fork-specific follow-ups.**
   - Set `SEMPER_RELEASE_CERT_SHA256` to the fork's own signer (TE1-007's default pins
     only S's key).
   - Port `check_jni_parity` back to S (TD-339).
   - The fork's `LabWorkflowDeviceTest` pinch failure on API 30 (TD-328).
7. **Gates on the fork.**
   - `ciReleaseGate`, `koverVerify` at 75/59 or higher (measured − 2);
     `compileDebugAndroidTestKotlin`.
   - The instrumented suite with a `notTestFile` exclusion list.
   - The real-data compare, if A15 is granted.
8. **Re-verify, then decide.** A fresh verifier re-runs every MT id. Then a fork checkpoint
   with the Admin. The fork's release is cut only after that, then
   `rename_classroom_app.py` (A16).

## 7. Inconsistencies in the run's records

- TD-331 and TD-345 both log AE1-011's remainder (the literal `textSize` values); one row
  should go.
- Two Open TD rows describe defects that the rework fixed, pending the second re-verify:
  - TD-333 is AE1V-024's defect, fixed in `224a9f7f`;
  - TD-335 is AE1-009's residual (`RunEnd.kt:101`), fixed in `ceb263ae`.
  TECH_DEBT holds only unresolved debt, so remove each row once its id is VERIFIED.
- AE1-010: Checkpoint 2 says the PDF is English-only by decision, but TD-334 (PDF labels
  are Kotlin literals) is Open with a fix plan. Close it as decided, or record the decision
  in it.
- ADMIN_DECISIONS (Checkpoint 1) counts 11 observations. That is `type: observation`;
  `severity_final: observation` has 14, adding TE2-001, TE4-008 and AE1-020 (P2 →
  observation).
- Two fixes have no finding record: TE6-017 (`e0b32dee`, posture script under Git Bash;
  TD-325 cites it) and BASELINE-APK-UTF8 (`b33370fa`).
- The rework log gives Kover lines as 81.96 % (19116 / 23323). The gate printed
  81.9721 %, which is the number used here.

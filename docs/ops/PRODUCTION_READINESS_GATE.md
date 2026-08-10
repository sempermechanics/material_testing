# Production readiness — completion gate

Re-score of the production readiness rubric after Phases 1–6 repo work and the
Step 4 pilot rollout. Integration branch is **`main`**. This is an **ops gate**,
not a substitute for counsel or console verification. Do not treat
PARTIAL/UNKNOWN as PASS.

Secrets / Environments reality: [ENVIRONMENTS.md](ENVIRONMENTS.md).

## Score summary (post-remediation, repository + pilot evidence)

| Band | Count (approx.) | Notes |
|------|-----------------|-------|
| PASS / improved to PASS | Security headers, deny-all rules (checked in), validation, erasure without silent 2k cap, readiness, structured logs, async notify, authz tests, pagination/export, deploy template, legal drafts, candidate smoke deploy, attested uploads pin, live assetlinks + legal Hosting | Repo + pilot |
| PARTIAL | Distributed rate limit (gateway YAML + in-process), backups/PITR, Crashlytics, WorkManager, GitHub Environment *reviewers* on Free plan | Need console / plan upgrade |
| UNKNOWN | Firebase API-key restrictions, App Check, Auth abuse limits, vendor retention, cookie inventory, branch ruleset if plan blocks it | External — see below |
| FAIL remaining | None intended in security/data-integrity **repo** scope; remaining gaps are console / process | |

Strict binary PASS against all applicable external controls is **not** claimed.

## Launch blockers (must clear or risk-accept)

### Security / data integrity

- [ ] Deploy deny-all `firestore.rules` and confirm client SDK cannot read/write.
- [ ] Confirm Firebase API key restrictions + App Check posture.
- [ ] Confirm Auth abuse / enumeration protections in Firebase console.
- [x] Deploy API Gateway with `openapi.yaml` quotas (`__CLOUD_RUN_URL__`
      substituted) — pilot gateway is live; re-confirm quotas in console.
- [ ] Confirm Cloud Run ingress, SA roles, Shared Drive Manager rights.
- [ ] Run and record one **Firestore restore drill**. The drill is automated
      (`.github/workflows/firestore-restore-drill.yml`) but needs a
      **`restore-drill` GitHub Environment** (separate from `production-backup`)
      plus one recorded RTO
      ([FIRESTORE_DATA_PROTECTION.md](../backend/FIRESTORE_DATA_PROTECTION.md)).
- [ ] Confirm PITR / scheduled export job actually scheduled in GCP. The export
      script already refuses to run without PITR, and now also fails if the
      `challenges.expireAt` TTL policy is not ACTIVE.
- [x] Cloud Tasks queue and IAM for async session provisioning
      ([BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) §A6) — pilot
      `indic-provision` + `TASKS_*` vars. Keep vars set on redeploy.

### Authorization / quality

- [x] CI green including backend authz suite (`test_http_authz.py`), plus a route
      inventory (`test_route_authz_matrix.py`) that fails when any route ships
      without a written-down auth tier, and cross-user negative tests for every
      protected route.
- [x] Firestore emulator tier runs in CI (`FIRESTORE_EMULATOR_HOST` is set in the
      Tier 4 job). It found three real defects the in-memory fake could not
      express — see "Contention fixes" below.
- [x] Coverage gated at 75% (`--cov-fail-under`), currently 78%.
- [x] Dependency audit (`pip-audit`) and hashed lock verification in CI.
- [x] **Dependency-outage readiness test**: `test_health.py` asserts `/healthz`
      stays 200 while both dependencies raise, and `/readyz` returns 503 with the
      stable code. `test_verify_degradation.py` asserts a Drive outage during
      `?verify=true` deletes nothing.

### Deployment

- [x] GitHub Environments `staging`, `production`, `release`, `production-backup`
      exist. On Free private orgs, **secrets/vars are often repo-level** (Environment
      protection_rules may be empty). Still open: required reviewers when the plan
      allows; create **`restore-drill`**. See [ENVIRONMENTS.md](ENVIRONMENTS.md).
- [ ] Branch ruleset / protection with required `CI OK` (may be unavailable on
      Free — enforce by process until then).
- [x] Staging candidate smoke before traffic shift
      (`deploy-backend.yml`: tagged revision + ID-token `/readyz`). Record a
      deliberate rollback drill when convenient.
- [x] `INDIC_API_BASE_URL` available to release builds; signed release shipped
      with `-PrequireCloudApi=true` (e.g. `v0.1.0-beta.1`).
- [x] Production `REQUIRE_ATTESTED_UPLOADS=1` pinned from the GitHub var on
      deploy. Leaving the var empty on the next deploy **clears** the flag —
      keep it set.

### Compliance

- [x] [PRIVACY_POLICY.md](../legal/PRIVACY_POLICY.md) and
      [TERMS_OF_SERVICE.md](../legal/TERMS_OF_SERVICE.md) are **rendered** into
      `firebase-hosting/public/{privacy,terms}/` by
      `scripts/render_legal_pages.py`; CI (`legal-pages`) fails on drift. Hosting
      has been deployed so public URLs are live.
- [x] Crash reporting and analytics are **opt-in**: disabled in the manifest,
      a first-run notice, and a Settings toggle that also deletes queued reports
      on withdrawal. Named funnel events (sign-in, analysis, cloud transfer,
      export, feedback) fire only when diagnostics are enabled.
- [x] In-app **Send feedback** (Settings → Help & support) plus release
      follow-up process in [RELEASING.md](RELEASING.md) (milestone triage + reply
      when fixed).
- [x] Cloud account export (`GET /v1/me/export`) is reachable from the app —
      Settings → Your data → "Download my cloud account data".
- [x] Release signing cert listed in
      `firebase-hosting/public/.well-known/assetlinks.json` (debug + release).
- [ ] Counsel review of legal templates (governing law, bases).
- [ ] Record cookie inventory per [COOKIE_CONSENT.md](../legal/COOKIE_CONSENT.md);
      no banner without non-essential cookies.
- [ ] Confirm Crashlytics / Cloud Logging / Resend retention in vendor consoles
      and replace the UNKNOWN entries in the policy's retention table.

## Contention fixes found by the emulator tier

Wiring the Firestore emulator into CI immediately falsified three assumptions the
in-memory fake could not test (it applies transactional writes immediately, with
no isolation and no retries):

| Defect | Symptom in production | Fix |
|---|---|---|
| `bump_session_progress` used a read-modify-write transaction on one hot document | Concurrent completions exhausted the client's 5 retries → `Aborted: Transaction lock timeout` → **500 on the last files of an otherwise successful upload** | Atomic `firestore.Increment`, no transaction |
| `consume_nonce` raised when it lost the transaction race | Replay attempts returned 500 instead of 401 | Fail closed: contention → deny |
| `complete_file` raised when it lost the race | A client retry could 500 instead of resolving idempotently | Re-read and answer `already` / `""` |

## External UNKNOWN register (do not invent PASS)

| Control | Evidence |
|---------|----------|
| Firebase MCP / active project | Confirm in console |
| GitHub Environments | Shells exist (`staging`, `production`, `release`, `production-backup`); `restore-drill` may still need creating; reviewers often empty on Free |
| Secrets / vars | Prefer **repo-level** on Free private orgs; Environment names still select workflow targets |
| Branch protection / ruleset | Confirm on `main`; 403 / unavailable → process-only until plan allows |

## Risk acceptance

Any remaining PARTIAL/UNKNOWN item at launch needs an explicit owner, expiry
date, and compensating control written below:

| Item | Owner | Accept until | Compensating control |
|------|-------|--------------|----------------------|
| _tbd_ | | | |

## Sign-off

| Role | Name | Date | Result |
|------|------|------|--------|
| Engineering | | | |
| Security / ops | | | |
| Product / legal | | | |

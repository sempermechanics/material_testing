# Production readiness — completion gate

Re-score of the 33-check production readiness rubric after Phases 1–6 repo work
on branch `damodar`. This is an **ops gate**, not a substitute for counsel or
console verification. Do not treat PARTIAL/UNKNOWN as PASS.

## Score summary (post-remediation, repository evidence)

| Band | Count (approx.) | Notes |
|------|-----------------|-------|
| PASS / improved to PASS | Security headers, deny-all rules (checked in), validation, erasure without silent 2k cap, readiness, structured logs, async notify, authz tests, pagination/export, deploy template, legal drafts | Repo-side |
| PARTIAL | Distributed rate limit (gateway YAML + in-process), backups/PITR scripts, Crashlytics, WorkManager, etc. | Need console proof |
| UNKNOWN | Firebase API-key restrictions, App Check, Auth abuse limits, API Gateway quotas live, Cloud Run IAM, GitHub environments/secrets/branch protection, vendor retention, public legal URL live, cookie inventory | External — see below |
| FAIL remaining | None intended in security/data-integrity **repo** scope after Phases 1–4; any remaining FAIL must be console gaps marked UNKNOWN with risk acceptance | |

Strict binary PASS against all applicable external controls is **not** claimed.

## Launch blockers (must clear or risk-accept)

### Security / data integrity

- [ ] Deploy deny-all `firestore.rules` and confirm client SDK cannot read/write.
- [ ] Confirm Firebase API key restrictions + App Check posture.
- [ ] Confirm Auth abuse / enumeration protections in Firebase console.
- [ ] Deploy API Gateway with `openapi.yaml` quotas (`__CLOUD_RUN_URL__` substituted).
- [ ] Confirm Cloud Run ingress, SA roles, Shared Drive Manager rights.
- [ ] Run and record one **Firestore restore drill**. The drill is now automated
      and self-verifying (`.github/workflows/firestore-restore-drill.yml`,
      monthly + on demand): it imports, **waits**, and compares the restored
      database against the manifest written beside each export, failing on any
      mismatch. Remaining work is to run it once and record the measured RTO
      ([FIRESTORE_DATA_PROTECTION.md](../backend/FIRESTORE_DATA_PROTECTION.md)).
- [ ] Confirm PITR / scheduled export job actually scheduled in GCP. The export
      script already refuses to run without PITR, and now also fails if the
      `challenges.expireAt` TTL policy is not ACTIVE.
- [ ] Create the Cloud Tasks queue and IAM for async session provisioning
      ([BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) §A6). Without it
      the service provisions inline — correct, but large analyses will time out.

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

- [ ] Create GitHub Environments `staging`, `production`, `release` with required
      reviewers; populate WIF secrets and vars (today: **0 environments /
      empty secrets** observed → UNKNOWN).
- [ ] Branch protection with required `ci-ok` (today: 404 / unavailable → UNKNOWN).
- [ ] One **staging rollback drill** (failed `/readyz` → traffic to previous revision).
- [ ] Set `INDIC_API_BASE_URL` on `release` environment; ship a release build
      with `-PrequireCloudApi=true`.

### Compliance

- [x] [PRIVACY_POLICY.md](../legal/PRIVACY_POLICY.md) and
      [TERMS_OF_SERVICE.md](../legal/TERMS_OF_SERVICE.md) are **rendered** into
      `firebase-hosting/public/{privacy,terms}/` by
      `scripts/render_legal_pages.py`; CI (`legal-pages`, a required check) fails
      if the published pages drift from the Markdown. The in-app links already
      point at those URLs. **Still needs a Hosting deploy to go live.**
- [x] Crash reporting and analytics are **opt-in**: disabled in the manifest,
      a first-run notice, and a Settings toggle that also deletes queued reports
      on withdrawal.
- [x] Cloud account export (`GET /v1/me/export`) is reachable from the app —
      Settings → Your data → "Download my cloud account data". The policy
      previously advertised a right with no way to exercise it.
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

| Control | Evidence at audit time |
|---------|------------------------|
| Firebase MCP / active project | Unauthenticated / no project |
| `gcloud` CLI | Not installed on auditor machine |
| GitHub Environments | 0 |
| GitHub Actions secrets list | Empty |
| Branch protection | 404 |

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

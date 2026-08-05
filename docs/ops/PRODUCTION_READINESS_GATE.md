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
- [ ] Run and record one **Firestore restore drill**
      ([FIRESTORE_DATA_PROTECTION.md](../backend/FIRESTORE_DATA_PROTECTION.md)).
- [ ] Confirm PITR / scheduled export job actually scheduled in GCP.

### Authorization / quality

- [ ] CI green including backend authz suite (`test_http_authz.py`).
- [ ] Optional: Firestore emulator tier with `FIRESTORE_EMULATOR_HOST`.
- [ ] One **dependency-outage readiness test**: break Drive or Firestore and
      confirm `/readyz` → 503 with stable codes; `/healthz` stays 200.

### Deployment

- [ ] Create GitHub Environments `staging`, `production`, `release` with required
      reviewers; populate WIF secrets and vars (today: **0 environments /
      empty secrets** observed → UNKNOWN).
- [ ] Branch protection with required `ci-ok` (today: 404 / unavailable → UNKNOWN).
- [ ] One **staging rollback drill** (failed `/readyz` → traffic to previous revision).
- [ ] Set `INDIC_API_BASE_URL` on `release` environment; ship a release build
      with `-PrequireCloudApi=true`.

### Compliance

- [ ] Publish [PRIVACY_POLICY.md](../legal/PRIVACY_POLICY.md) and
      [TERMS_OF_SERVICE.md](../legal/TERMS_OF_SERVICE.md) at stable public HTTPS
      URLs; point in-app links (`legal_privacy_url` / `legal_terms_url`) at them.
- [ ] Counsel review of legal templates (governing law, bases).
- [ ] Record cookie inventory per [COOKIE_CONSENT.md](../legal/COOKIE_CONSENT.md);
      no banner without non-essential cookies.
- [ ] Confirm Crashlytics / Cloud Logging / Resend retention in vendor consoles.

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

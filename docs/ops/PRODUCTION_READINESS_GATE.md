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
- [ ] Counsel review of the Terms (India law, Chennai arbitration, liability
      cap, indemnity, class waiver) and the Privacy Policy bases before public
      distribution. Drafted as a risk exercise, not legal advice.
- [ ] Fill the bracketed operator fields in both legal documents and
      regenerate the pages: `[OPERATOR LEGAL NAME]`, `[REGISTERED ADDRESS]`,
      `[GRIEVANCE OFFICER NAME]` / `[EMAIL]` (DPDP Act 2023 requires one),
      `[GCP REGION …]`. `render_legal_pages.py --check` does not catch these.
- [x] Clickwrap: Terms acceptance is an affirmative in-app act recorded
      server-side (`users/{uid}.termsAccepted`) with a version, and the
      product-improvement consent is a separate, pre-ticked but declinable,
      withdrawable option
      ([AUTH_SETUP.md](../backend/AUTH_SETUP.md) §3a).
- [ ] Record cookie inventory per [COOKIE_CONSENT.md](../legal/COOKIE_CONSENT.md);
      no banner without non-essential cookies.
- [ ] Confirm Crashlytics / Cloud Logging / Resend retention in vendor consoles
      and replace the UNKNOWN entries in the policy's retention table.

### Legal rollout (branch `feat/legal-terms-clickwrap`)

Pending before the rewritten Terms and the clickwrap gate go to real users.
Order matters: the backend and gateway must serve the new routes before an app
build that calls them ships, or every sign-in ends at an unrecordable gate.

- [ ] Appoint the **Grievance Officer** (DPDP Act 2023) and fill the bracketed
      fields in [TERMS_OF_SERVICE.md](../legal/TERMS_OF_SERVICE.md) and
      [PRIVACY_POLICY.md](../legal/PRIVACY_POLICY.md); regenerate and **deploy
      Hosting** so `/terms/` and `/privacy/` show the new text before the app
      links to it.
- [ ] Deploy the backend **and** redeploy API Gateway from the updated
      `backend/gateway/openapi.yaml` (`POST /v1/me/terms`, `PUT /v1/me/consents`);
      confirm with a curl that both reach Cloud Run through the gateway.
- [ ] Manual device pass of the gate: fresh install → password sign-up → Terms
      screen before Pending/Home → Agree writes `users/{uid}.termsAccepted`;
      Google sign-in and email link show the same screen; Decline and Back sign
      out; Settings → Your data toggle flips `improvementConsent` and
      `GET /v1/me/export` shows both records; bumping `TERMS_VERSION` on a dev
      backend re-gates an approved user on next launch.
- [ ] Every **existing** user is re-gated once on their first launch after the
      rollout (no `termsAccepted` on file). Tell pilot users beforehand; nobody
      is locked out — PENDING accounts can accept too.
- [ ] The Privacy Policy §2.8 promises a **separate, pseudonymised improvement
      dataset**, deletion from it within 30 days of withdrawal, and a 36-month
      cap on raw content. No tooling exists for that yet. Until it does, do not
      copy any synced content into an improvement dataset, consent or not — the
      toggle records the choice; it does not license a process that is not built.
- [ ] Update the **Google Play Data safety** form: analysis content may be used
      for app improvement (optional, user-controlled), and account data now
      includes terms-acceptance / consent records.
- [ ] Console: `source: console` is reserved for consent changes made without
      `X-Device-Id`; the web console has no consent UI yet. Either add one or
      note that withdrawal is app- or support-mailbox-only (the policy says both).

### Licensing rollout (PR #100 → `feat/licensing-go-live`)

Everything pending, deferred or delayed for taking licensing live. The hard
constraint: **installed builds that predate licensing (v1.1-beta.5, v1.2-beta.0)
must keep working** — every pre-existing account resolves to demo on the first
request after the deploy, so demo must be able to do what those builds do.
Product decision: *demo analyses are recorded (images and results are uploaded
and stored) but demo has no backup/restore feature.* Recording is open;
retrieval (`/content`, bundle) is licensed. Ops steps in order:

**Before the deploy**

- [x] Full CI green on the #100 tip (run `35433845913` after the E741 fix);
      `gh pr merge 100 --merge`.
- [x] `feat/licensing-go-live` merged to `main` (#110, `08e959d`, 2026-09-19)
      **before** any backend deploy from `main` — it removes the `403` on
      `POST /v1/sessions` that would make every old build's upload worker
      retry forever.
- [ ] `feat/console-domain-cors` merged to `main` before the deploy: CORS
      (`CONSOLE_ORIGINS` + gateway `allowCors`), the `/auth/*` rewrites, and
      the app's second continue host. Without it the dashboards cannot call
      the API from any origin.
- [ ] Create the six repository variables `deploy-backend.yml` now pins:
      `DEMO_MAX_ANALYSES`, `LICENSED_MAX_SESSIONS_PER_USER`,
      `ADMIN_WEB_MFA_ENABLED` (`1`), `APP_CHECK_MODE` (`off`),
      `SELF_DEVICE_CHANGE_COOLDOWN_DAYS` (`30`), `CONSOLE_ORIGINS`
      (`https://app.sempermechanics.com,https://indicvision-dic-app-auth.firebaseapp.com`).
      Unset resolves to those defaults, but set them so the value is a
      decision, not an accident.
- [ ] Choose `DEMO_MAX_ANALYSES` from a read-only Firestore survey: it must be
      ≥ max(live `MAX_SESSIONS_PER_USER`, the largest per-user `maxSessions`
      override, the largest per-uid session count) or an existing user 409s on
      the next upload. Confirm no user document already carries `mode` /
      `licenseId`.
- [ ] `gcloud run services describe indic-api --format=yaml > indic-api-before.yaml`;
      note the serving revision (rollback target) and confirm
      `REQUIRE_ATTESTED_UPLOADS=1`, no `DEV_INSECURE_AUTH`.
- [ ] PITR on and a fresh export (`gh workflow run firestore-backup.yml`); note
      the path. Firestore is never rolled back by the deploy.

**Staging** (same project → same Firestore; migration 002 is a no-op on
pre-licensing documents)

- [ ] `deploy-backend.yml` with `environment=staging`; `migrate_schema.py`
      dry-run (expect 0 changes) then `--apply`; ledger row written.
- [ ] `GET /v1/config` with an ID token → `mode: demo`, `plan: demo`,
      `cloudBackupEnabled: false`, `maxSessions: <DEMO_MAX_ANALYSES>`.
- [ ] `OPTIONS /v1/me` with `Origin: https://app.sempermechanics.com` and
      `Access-Control-Request-Method: GET` against the staging Cloud Run URL →
      200 with `access-control-allow-origin` echoed.
- [ ] Old-APK pass (debug build from `bcc467a`, `INDIC_API_BASE_URL` = staging):
      sign in, back up one analysis (201 + upload completes), **restore fails
      once with "rejected" and does not loop**, delete, export.
- [ ] Mint an individual licence against that account → response carries
      `claimedByUid` → `/v1/config` flips to `mode: licensed` → restore
      succeeds. Mint against an address with no account → invite retained.

**Production**

- [ ] `deploy-backend.yml` with `environment=production` (candidate → `/readyz`
      → promote; auto-rollback on failure). Read the "Describe live env"
      warning: after promote, `--remove-env-vars MAX_SESSIONS_PER_USER,PRO_MAX_SESSIONS_PER_USER`.
- [ ] Gateway: new `api-config` from the substituted spec — all three
      placeholders, `__GATEWAY_HOST__` included — `gateways update`,
      `PREV_CFG` recorded ([BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md)
      "Redeploying the gateway"). Unauthenticated `/v1/config` → **401**, not
      404; the preflight above → **200** through the gateway.
- [ ] Verify with the **installed, unmodified** old app: sign-in, new backup,
      delete, export succeed; restore shows one refusal.
- [ ] Verify with a new-app build: Terms gate, one account receives a demo key
      (`licenses/` gains a `createdByUid: system` document), Home shows no
      sync badge and Settings shows no Cloud/Analyses-data section on demo.
- [ ] Custom domain `app.sempermechanics.com` on the `indicvision-dic-app-auth`
      Hosting site: TXT verification + A records in **Netlify DNS**, certificate
      issued, `https://app.sempermechanics.com/.well-known/assetlinks.json` 200.
- [ ] Consoles last: Identity Platform + TOTP enabled and
      `app.sempermechanics.com` an authorised domain
      ([console README](../../firebase-hosting/public/console/README.md)), `firebase deploy --only
      firestore:indexes`, `./scripts/deploy-console.sh`, hand-check `/login`
      as staff (a licence list loading is the CORS proof) and as an account
      holder.
- [ ] Marketing site (`IndicVision/semper-website`, Netlify): "Sign in" in the
      nav, `/dashboard/` page, `_redirects` for `/login`, `/account`,
      `/terms/*` → `app.sempermechanics.com`. `curl -sI https://sempermechanics.com/terms/`
      → 301 → 200 (it is 404 today, and `legal.py` links it).
- [ ] 24 h log watch: `feature_not_licensed` only from restore/bundle by demo
      accounts (never from `POST /v1/sessions`); `app_check_required` **= 0**;
      `session_quota_exceeded`; `license_device_mismatch`; `mfa_required`;
      5xx rate; Cloud Tasks backlog.

**Deferred / risk-accepted for launch**

- [ ] **Legal wording for demo recording.** Terms §7.2 licenses "syncing or
      uploading Your Content" generically, but the Privacy Policy reads as
      user-elected sync ("metrics you sync", "the cloud path is optional").
      On demo the upload is automatic with no toggle. Add one sentence to
      Privacy §2.3 and Terms §6.1 stating demo analyses are uploaded and
      stored and cannot be restored on the Demo plan; regenerate with
      `scripts/render_legal_pages.py`; deploy Hosting. **Before the next app
      release**, together with the bracketed operator fields above.
- [ ] Google Play **Data safety** form: demo analysis content (images,
      results) is uploaded to the operator's cloud.
- [ ] Floating institution pools: not minted at launch; the lease routes ship
      dormant.
- [ ] `APP_CHECK_MODE` stays `off`. Move to `monitor` only once an App Check
      build is the fleet; **never** `enforce` while a pre-App-Check build is
      installed (every request from it would 403).
- [ ] §20.5 skew fallbacks (`plan` mirror, `/v1/campus/*` aliases) stay until
      adoption of a `mode`-reading build is high enough; retire in that order.
- [ ] Gateway deploy job (TD-27): manual runbook for now.
- [ ] Per-user `maxSessions` override ignored in demo (TD-28): lifting one demo
      account's cap means attaching a licence.
- [ ] Old-app restore UX: a pre-licensing build shows a generic "rejected"
      message on restore; there is no way to tell it "licence required".
      Accepted — it fails once and stops.
- [ ] Password-reset custom action URL stays
      `https://indicvision-dic-app-auth.firebaseapp.com/finishReset` (TD-29):
      builds before `AUTH_HOST = app.sempermechanics.com` intercept only that
      host. Switch it, and drop the legacy filters, once Play vitals show no
      such build installed.

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

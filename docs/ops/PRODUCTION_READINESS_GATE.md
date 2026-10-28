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
| PARTIAL | Distributed rate limit (gateway YAML + in-process), backups/PITR, Crashlytics, WorkManager | Need console setup |
| UNKNOWN | Firebase API-key restrictions, App Check, Auth abuse limits, vendor retention, cookie inventory | External — see below |
| FAIL remaining | None intended in security/data-integrity **repo** scope; remaining gaps are console / process | |

Strict binary PASS against all applicable external controls is **not** claimed.

The item-by-item check against the external readiness framework (2026-10-18) is
[Framework cross-check](#framework-cross-check-2026-10-18) below.

## Launch blockers (must clear or risk-accept)

### Security / data integrity

- [x] Deploy deny-all `firestore.rules` (`./scripts/deploy-firestore.sh rules`) and confirm
      client SDK cannot read/write. Deployed to `indicvision-dic-app` 2026-09-24;
      anonymous REST reads of `users`, `licenses`, `sessions` and a create all
      answer `403 PERMISSION_DENIED` ("Missing or insufficient permissions").
      The Auth project has no Firestore database. CI's `firestore-rules` job now proves
      the denial on the emulator for every collection `backend/app` uses
      (`firestore-rules-tests/`, [CI.md](CI.md)).
- [ ] Confirm Firebase API key restrictions + App Check posture.
      `scripts/check_gcp_posture.sh --app-project … --auth-project … --api-sa … --backup-bucket …`
      (read-only) prints PASS / FAIL / UNKNOWN for these and for liens, the Prod tag,
      owners, budgets, max-instances, PITR, delete protection, the backup schedule,
      anonymous auth, `CHECK_REVOKED_ON_STEP_UP`, the entitlement key's Secret
      Manager ref and Data Access audit logs. First run 2026-10-22 (`--backup-bucket
      indicvision-dic-app-firestore-backups`, both services): **18 FAIL, 0 UNKNOWN**.
      PASS: 2.5 (anonymous off; email and google.com on), 2.6 (budgets on both
      projects, max-instances 10 on both services), 2.7-pitr (7 days), 2.7-backup
      (daily schedule, 7 days), 3.4-iam, 3.4-check-revoked on staging. FAIL on both
      projects: 2.1 (no lien, no Prod tag, one owner, granted to a user not a group),
      2.4 (the Firebase auto-created Browser key, and on the Auth project the Android
      key, have no app/site/server restriction), 3.7 (no DATA_READ + DATA_WRITE audit
      logs for firebase, firebaserules, firebaseappcheck, firestore). FAIL on the app
      project: 2.7-delete-protection. FAIL on services: 3.1 `APP_CHECK_MODE=off`
      (both; TD-226), 3.4 `CHECK_REVOKED_ON_STEP_UP=false` on `semper-api` (TD-214;
      `true` since the 2026-10-23 deploy, `semper-api-39900623312-1`), 3.5 no `ENTITLEMENT_SIGNING_KEY` (both;
      TD-215). Every fix is a console or `gcloud` change by the operator.
      **Re-run 2026-10-24** (same flags, output redacted;
      [readiness report](readiness/20261024/REPORT.md) §2): **17 FAIL, 0 UNKNOWN**. The FAILs
      are the same items. 3.4-check-revoked is now PASS on both services.
- [ ] Confirm Auth abuse / enumeration protections in Firebase console.
- [x] Deploy API Gateway with `openapi.yaml` quotas (`__CLOUD_RUN_URL__`
      substituted) — pilot gateway is live; re-confirm quotas in console.
- [ ] Confirm Cloud Run ingress, SA roles, Shared Drive Manager rights. Ingress
      checked 2026-09-24: `all` on `semper-api`, as intended
      ([BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) "Leave Cloud Run ingress at its
      default"); an anonymous `GET /v1/config` on the `run.app` URL of production and
      staging answers `403 Forbidden`. SA roles reviewed 2026-09-24: `run.invoker`
      on `semper-api` is `indic-gw`, `indic-api`, `indic-deployer` and the owner's
      user (no `allUsers`); `indic-api` holds only `datastore.user`,
      `cloudtasks.enqueuer`, `logging.logWriter`; `indic-backup` only
      `datastore.importExportAdmin` + `datastore.viewer`; the default compute SA
      has no `roles/editor`. Open: `indic-deployer`'s project-wide
      `storage.admin` and `iam.serviceAccountUser` (TD-71), and Shared Drive
      Manager rights for `indic-api`.
- [x] Run and record one **Firestore restore drill** (TD-156). **Passed 2026-10-10**,
      run [38023354431](https://github.com/sempermechanics/semperdic-app/actions/runs/38023354431)
      of `.github/workflows/firestore-restore-drill.yml`, into the drill project
      `indicvision-dic-restore-drill` (set up 2026-10-09 with
      `scripts/setup-restore-drill.sh`). **RTO: 111 s** for the import.
      **Recovery point:** the export `20261009T090852Z` (2026-10-09 09:08:52 UTC),
      the newest daily export at the time. Verification (`scripts/firestore_verify.py`
      against the export's `manifest.json`): every collection's document count
      matched and a 25-session `session → files` sample matched; the drill
      database was purged afterwards. The drill now runs monthly; a failed run
      means the restore path is unproven again
      ([FIRESTORE_DATA_PROTECTION.md](../backend/FIRESTORE_DATA_PROTECTION.md)).
- [x] Confirm PITR / scheduled export job actually scheduled in GCP. The export
      script already refuses to run without PITR, and now also fails if the
      `challenges.expireAt` TTL policy is not ACTIVE. Checked 2026-09-24:
      `pointInTimeRecoveryEnablement` is `POINT_IN_TIME_RECOVERY_ENABLED`, the
      `challenges.expireAt` TTL is `ACTIVE`, and the daily `firestore-backup.yml`
      (`17 2 * * *`) succeeded on its scheduled runs of 2026-09-22, -23 and -24.
- [x] Cloud Tasks queue and IAM for async session provisioning
      ([BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) §A6) — pilot
      `indic-provision`, renamed `semper-provision` / `semper-provision-staging` in #146 (2026-09-23), + `TASKS_*` vars. Keep vars set on redeploy.

### Authorization / quality

- [x] CI green including backend authz suite (`test_http_authz.py`), plus a route
      inventory (`test_route_authz_matrix.py`) that fails when any route ships
      without a written-down auth tier, and cross-user negative tests for every
      protected route.
- [x] Firestore emulator tier runs in CI (`FIRESTORE_EMULATOR_HOST` is set in the
      Tier 4 job). It found three real defects the in-memory fake could not
      express — see "Contention fixes" below.
- [x] Coverage gated at 93 % (`--cov-fail-under`), measured 95.47 % (2026-10-08); with
      the emulator tier appended, 95.54 %, gated at 93 % in CI tier 4.
- [x] Dependency audit (`pip-audit`) and hashed lock verification in CI.
- [x] **Dependency-outage readiness test**: `test_health.py` asserts `/healthz`
      stays 200 while both dependencies raise, and `/readyz` returns 503 with the
      stable code. `test_verify_degradation.py` asserts a Drive outage during
      `?verify=true` deletes nothing.

### Deployment

- [x] GitHub Environments `staging`, `production`, `release`, `production-backup`
      and `restore-drill` exist. **Secrets are repo-level**, and so are most
      variables (`staging`, `production` and `production-backup` hold a few of their
      own). `production` requires a reviewer (2026-10-22, TD-228); no environment has
      a branch policy. See [ENVIRONMENTS.md](ENVIRONMENTS.md).
- [x] Branch ruleset with required `CI OK`: ruleset `main` (id 24720948, set
      2026-10-08) on the default branch requires `CI OK` and blocks force-push
      and deletion (`gh api repos/sempermechanics/semperdic-app/rules/branches/main`).
- [x] Staging candidate smoke before traffic shift
      (`deploy-backend.yml`: tagged revision + ID-token `/readyz`). Record a
      deliberate rollback drill when convenient.
- [x] `SEMPER_API_BASE_URL` available to release builds; signed release shipped
      with `-PrequireCloudApi=true` (e.g. `v0.1.0-beta.1`).
- [x] `/uploads` always device-attested: the `REQUIRE_ATTESTED_UPLOADS`
      window was retired 2026-09-26 (TD-45), so there is no flag left to
      clear on deploy.

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
      Settings → Your data → "Download cloud data".
- [x] Release signing cert listed in
      `firebase-hosting/public/.well-known/assetlinks.json` (debug + release).
- [ ] Counsel review of the Terms (India law, Chennai arbitration, liability
      cap, indemnity, class waiver) and the Privacy Policy bases before public
      distribution. Drafted as a risk exercise, not legal advice.
- [ ] Fill the bracketed operator fields in both legal documents and
      regenerate the pages: `[OPERATOR LEGAL NAME]`, `[REGISTERED ADDRESS]`,
      `[GRIEVANCE OFFICER NAME]` / `[EMAIL]` (DPDP Act 2023 requires one),
      `[GCP REGION …]`. `render_legal_pages.py --check` does not catch these.
      **They are live** on the hosted pages as literal text (TD-36, checked
      2026-09-23): `PRIVACY_POLICY.md:5` (name, address), `:7` (Grievance
      Officer name, email), `:18` (name), `:153` (region);
      `TERMS_OF_SERVICE.md:6` (name, address), `:438` (name), `:439`
      (address) — rendered into `firebase-hosting/public/privacy/index.html`
      and `terms/index.html`. Values come from the operator; nobody else
      should fill them.
- [x] Clickwrap: Terms acceptance is an affirmative in-app act recorded
      server-side (`users/{uid}.termsAccepted`) with a version, and the
      product-improvement consent is a separate, pre-ticked but declinable,
      withdrawable option
      ([AUTH_SETUP.md](../backend/AUTH_SETUP.md) §3a).
- [ ] Record cookie inventory per [COOKIE_CONSENT.md](../legal/COOKIE_CONSENT.md);
      no banner without non-essential cookies. **Recorded 2026-10-22 from the
      code and the live site**: no response on app.sempermechanics.com sets a
      cookie (`curl -sI` on `/login`, `/account`, `/console/*`, `/terms/`,
      `/privacy/`, the continue pages and `/__/auth/*`), no page writes one, and
      the only storage is Firebase Auth's IndexedDB / web-storage session and
      the console's three `semper.*` sessionStorage keys, all strictly
      necessary; no analytics or tag script, and the CSP would refuse one.
      Verdict: **no banner**. Open only for a signed-in DevTools capture by the
      operator to match the table (COOKIE_CONSENT.md, "Re-checking" step 3).
- [ ] Confirm Crashlytics / Cloud Logging / Resend retention in vendor consoles
      and replace the UNKNOWN entries in the policy's retention table.
      **UNKNOWN entries replaced 2026-10-22** (`PRIVACY_POLICY.md` §4, rendered
      to `firebase-hosting/public/privacy/index.html`, not deployed). Cloud
      Logging is read, not assumed: `gcloud logging buckets list --location=global`
      on `indicvision-dic-app` and `indicvision-dic-app-auth` → `_Default` 30
      days, `_Required` 400 days (locked, set by Google); no buckets in
      `asia-south1`; `gcloud logging sinks list` shows only the two default
      sinks, so no `audit-trail` bucket existed then (TD-237; the `audit-trail` bucket (`asia-south1`, 365 days) and its sink on `jsonPayload.event="audit_row"` were created and the bucket locked on 2026-10-23). Crashlytics (90
      days, [Firebase privacy](https://firebase.google.com/support/privacy)) and
      Resend (30 days, [Resend pricing](https://resend.com/pricing)) are the
      vendors' published defaults, marked "to be confirmed" in the policy: open
      until the operator confirms both in the Firebase console and the Resend
      dashboard.

### Legal rollout (branch `feat/legal-terms-clickwrap`)

Pending before the rewritten Terms and the clickwrap gate go to real users.
Order matters: the backend and gateway must serve the new routes before an app
build that calls them ships, or every sign-in ends at an unrecordable gate.

- [ ] Appoint the **Grievance Officer** (DPDP Act 2023) and fill the bracketed
      fields in [TERMS_OF_SERVICE.md](../legal/TERMS_OF_SERVICE.md) and
      [PRIVACY_POLICY.md](../legal/PRIVACY_POLICY.md); regenerate and **deploy
      Hosting** so `/terms/` and `/privacy/` show the new text before the app
      links to it.
- [x] Deploy the backend **and** redeploy API Gateway from the updated
      `backend/gateway/openapi.yaml` (`POST /v1/me/terms`, `PUT /v1/me/consents`);
      confirm with a curl that both reach Cloud Run through the gateway. Checked
      2026-09-24 on config `v202609241122-44`: both answer an anonymous call with
      `401 "Jwt is missing"` (the route exists and wants a token), where an
      undefined path answers `404 "not defined by this API"`. A signed-in call
      reaching Cloud Run is the Terms gate in the device pass below.
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
- [x] `feat/console-domain-cors` merged to `main` before the deploy (#112,
      then #113 for the space-separated `CONSOLE_ORIGINS`): CORS
      (`CONSOLE_ORIGINS` + gateway `allowCors`), the `/auth/*` rewrites, and
      the app's second continue host. Without it the dashboards cannot call
      the API from any origin.
- [x] Create the six repository variables `deploy-backend.yml` now pins:
      `DEMO_MAX_ANALYSES`, `LICENSED_MAX_SESSIONS_PER_USER`,
      `ADMIN_WEB_MFA_ENABLED` (`1`), `APP_CHECK_MODE` (`off`),
      `SELF_DEVICE_CHANGE_COOLDOWN_DAYS` (`30`), `CONSOLE_ORIGINS`
      (`https://app.sempermechanics.com,https://indicvision-dic-app-auth.firebaseapp.com`).
      Unset resolves to those defaults, but set them so the value is a
      decision, not an accident.
- [x] Choose `DEMO_MAX_ANALYSES` from a read-only Firestore survey (**25**): it must be
      ≥ max(live `MAX_SESSIONS_PER_USER`, the largest per-user `maxSessions`
      override, the largest per-uid session count) or an existing user 409s on
      the next upload. Confirm no user document already carries `mode` /
      `licenseId`.
- [x] `gcloud run services describe indic-api --format=yaml > indic-api-before.yaml`
      (rollback revision `indic-api-31896308319-1`);
      note the serving revision (rollback target) and confirm
      `REQUIRE_ATTESTED_UPLOADS=1`, no `DEV_INSECURE_AUTH`.
- [x] PITR on and a fresh export (`gh workflow run firestore-backup.yml`); note
      the path. Firestore is never rolled back by the deploy.

**Staging** (same project → same Firestore; migration 002 is a no-op on
pre-licensing documents)

- [x] `deploy-backend.yml` with `environment=staging`; `migrate_schema.py`
      dry-run (expect 0 changes) then `--apply`; ledger row written.
- [x] `GET /v1/config` with an ID token → `mode: demo`, `plan: demo`,
      `cloudBackupEnabled: false`, `maxSessions: <DEMO_MAX_ANALYSES>`.
- [x] `OPTIONS /v1/me` with `Origin: https://app.sempermechanics.com` and
      `Access-Control-Request-Method: GET` against the staging Cloud Run URL →
      200 with `access-control-allow-origin` echoed. Through the staging
      gateway it was **405** until `x-google-endpoints.name` was the API's
      managed service name (#114, #115) — ESPv2 ignores `allowCors` on any
      other name.
- [x] Old-APK pass (debug build from `bcc467a`, `SEMPER_API_BASE_URL` = staging):
      sign in, back up one analysis (201 + upload completes), **restore fails
      once with "rejected" and does not loop**, delete, export. Two things
      the pass taught: the old build shows the raw `detail`, so the refusal
      now carries a sentence (`feature_not_licensed: Restore isn't available
      in demo mode.`, #116) and the same for a refused bundle download in the
      current build (#117); and **on-device export stays ungated on every
      installed pre-licensing build** — it never touched the backend, and
      the backend has no forced-update gate. Accepted: share gating arrives
      with the next app release, not the deploy.
- [ ] Mint an individual licence against that account (the operator's own
      test account, from the operator desk once the consoles are up) → response carries
      `claimedByUid` → `/v1/config` flips to `mode: licensed` → restore
      succeeds. Mint against an address with no account → invite retained.
      **Attempted 2026-09-22 and it answered 500** — `_write_license` returned
      the `SERVER_TIMESTAMP` sentinel it had just written, which the response
      cannot serialise, so both attempts created a licence and reported
      failure (#131). Two time-limited keys from that are on the desk:
      `SEMP-EQUZ` (redeemed) and the unused duplicate `SEMP-5MZP`. Revoke the
      duplicate, then re-issue perpetual and uncapped once #131 is deployed.

**Production**

- [x] `deploy-backend.yml` with `environment=production` (candidate → `/readyz`
      → promote; auto-rollback on failure). Read the "Describe live env"
      warning: after promote, `--remove-env-vars MAX_SESSIONS_PER_USER,PRO_MAX_SESSIONS_PER_USER`
      **and then `update-traffic`** — the deploy pins traffic to the
      candidate revision, so the env change lands in a revision serving 0 %
      until moved (2026-09-21: `indic-api-00066-x2r`, image `d6b1b64`).
- [x] Gateway: new `api-config` from the substituted spec — all three
      placeholders, `__MANAGED_SERVICE__` included — `gateways update`,
      `PREV_CFG` recorded ([BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md)
      "Redeploying the gateway"). Unauthenticated `/v1/config` → **401**, not
      404; the preflight above → **200** through the gateway. Done
      2026-09-21 on `semper-gw` (`v202609211150`, rollback `v202608081145`).
      The legacy `indic-gw` gateway and its `indic-api` gateway API were
      deleted 2026-09-23 (last client call 2026-09-10).
- [x] Verify with the **installed, unmodified** old app: sign-in, new backup,
      delete, export succeed; restore shows one refusal. (Prod log 2026-09-21:
      `POST /v1/sessions` 200, uploads complete, `DELETE /v1/sessions/…` 200,
      one `403 feature_not_licensed` from restore.)
- [ ] Verify with a new-app build: Terms gate, one account receives a demo key
      (`licenses/` gains a `createdByUid: system` document), Home shows no
      sync badge and Settings shows no Cloud/Analyses-data section on demo.
- [x] Custom domain `app.sempermechanics.com` on the `indicvision-dic-app-auth`
      Hosting site: TXT verification + A records in **Netlify DNS**, certificate
      issued, `https://app.sempermechanics.com/.well-known/assetlinks.json` 200.
- [x] Repository made public (2026-09-22) so Actions minutes stop being
      billed; the engine submodule already was. Nothing else changed — 0
      repository secrets are exposed by it, the 19 Actions variables hold no
      credential, and deploys authenticate by Workload Identity Federation.
- [x] Consoles: Identity Platform + TOTP enabled (TOTP is the only factor;
      `mfa.providerConfigs[0].totpProviderConfig.adjacentIntervals: 5`,
      `enabledProviders` empty so no SMS) and `app.sempermechanics.com` an
      authorised domain
      ([console README](../../firebase-hosting/public/console/README.md)); `firebase deploy --only
      firestore:indexes` (production had **no** composite index before; the
      file held seven entries Firestore refuses, #118); `./scripts/deploy-console.sh`
      (`/login`, `/account`, `/auth/finishSignIn`, `/terms/`,
      `assetlinks.json` all 200 on the custom domain; preflight from that
      origin through `semper-gw` → 200 with the origin echoed).
- [x] Hand-check `/login` as staff: TOTP enrolment (QR, #129) and the
      operator desk listing licences — the CORS proof from a real browser.
      What the first staff sign-in cost, all merged: `<base>` href, theme,
      CSP origins, `init.json`, one SDK origin, no-cache (#120–#128); the
      role gate, so a non-operator sees where they *can* go rather than a
      mint form that refuses (#130); and the re-authentication loop that made
      revoke unusable — firebase-auth resolves a redirect's second-factor
      challenge against `auth.redirectUser`, never making it current, so the
      fresh `auth_time` was invisible and every revoke bounced back to Google
      (#132, which also resumes the revoke on the return leg).
- [x] Deploy #131 (mint 500) to production: `deploy-backend.yml`
      `environment=production project=indicvision-dic-app region=asia-south1`.
      The workflow pins env from the repository variables, so this is also the
      first deploy to carry `ADMIN_EMAILS` for both operators — space-separated
      since #134, because the deploy action splits `env_vars` pairs on commas.
      **Done 2026-09-23:** serving `indic-api-35821056144-1` (`7593692`); env,
      `/readyz` and the gateway's 401 checked, no errors since. Rollback:
      `update-traffic --to-revisions=indic-api-00067-mbp=100`.
- [x] Deploy the #138 console (`scripts/deploy-console.sh`): the desk reports
      every revoke and mint outcome, and asks before a second live licence for
      the same address. **Done 2026-09-23** from `402c59d`: `config.js` carries
      the gateway host, `operator.js` matches `main`, `/login`, `/account`,
      `/auth/finishSignIn`, `/terms/` and `assetlinks.json` 200, preflight from
      the custom domain through `semper-gw` 200.
- [x] Revoke `SEMP-5MZP` from the desk (the 500-retry duplicate beside `SEMP-EQUZ`).
      **Done 2026-09-23** by the operator on the deployed #138 desk.
- [x] Hand-check `/login` as an ordinary account holder (`/account` only).
      **Done 2026-09-23**: `/login` read `/v1/me` and
      `/v1/institutions/licenses`, found neither staff role nor institution,
      and sent the account to `/account`, which showed **demo**; every
      preflight and call 200 through the production gateway.
- [x] Marketing site (`IndicVision/semper-website`, Netlify): "Sign in" in the
      nav, `/dashboard/` page, `_redirects` for `/login`, `/account`,
      `/terms/*` → `app.sempermechanics.com`. `curl -sI https://sempermechanics.com/terms/`
      → 301 → 200 (was 404, and `legal.py` links it; live 2026-09-22).
- [x] A 429 on a device-signed route leaves the nonce unspent (#154): the
      bucket is a route dependency resolved before `verified_device`, and the
      429 sends `Retry-After`. **Done 2026-09-24**: production
      `semper-api-35957034833-1` from `2fdb44c`; a four-analysis erase from the
      Pixel 6 got one 429, and the app's unchanged resend 2 s later returned
      200 (was 401 `nonce_invalid_or_replayed`, leaving analyses in the cloud).
- [ ] 24 h log watch: `feature_not_licensed` only from restore/bundle by demo
      accounts (never from `POST /v1/sessions`); `app_check_required` **= 0**;
      `session_quota_exceeded`; `device_quota_exceeded`; `license_device_mismatch`; `mfa_required`;
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
      **Text done 2026-10-22** (`PRIVACY_POLICY.md` §2.3 "Demo plan" bullet,
      `TERMS_OF_SERVICE.md` §6.1 last sentence; both rendered, `--check`
      passes), matching the code: a demo account has no Save-to-cloud toggle
      and uploads unless device management turned cloud backup off
      (`data/cloud/CloudSync.kt:327-345`); `POST /v1/sessions` is open to demo
      (`backend/app/routers/sessions.py:339-357`), and file content and the
      bundle answer 403 `feature_not_licensed` (`backend/app/routers/files.py:80`,
      `backend/app/routers/sessions.py:310`). Terms **Version** stays 2026-09-15, so no
      re-acceptance; whether the addition is material is counsel's call
      (TD-277). Open for the Hosting deploy, which is the operator's.
- [ ] Google Play **Data safety** form: demo analysis content (images,
      results) is uploaded to the operator's cloud.
- [ ] Floating institution pools: not minted at launch; the lease routes ship
      dormant.
- [ ] `APP_CHECK_MODE` stays `off`. Move to `monitor` only once an App Check
      build is the fleet; **never** `enforce` while a pre-App-Check build is
      installed (every request from it would 403).
- [x] App Check APIs (`firebaseappcheck`, `playintegrity`) enabled on
      `indicvision-dic-app-auth` and the Android app registered with Play
      Integrity (2026-09-23); Firebase Auth enforcement stays `UNENFORCED`.
- [ ] Play developer account → link the project in Play Console → App
      integrity, and add the Play app-signing SHA-256 to the Firebase Android
      app ([AUTH_SETUP.md §3.2](../backend/AUTH_SETUP.md)). Until then no build
      can get a token and `monitor` would count 100 % missing.
- [ ] §20.5 skew fallbacks 6–9 (the app's `config.plan` / old `plan` pref
      reads, the `plan` mirror in `/v1/config` and licence summaries, and
      `normalize_mode` / `normalize_kind` reading old stored values) stay until
      adoption of a `mode`-reading build is high enough; retire in that order.
      Shims 1–5 (ops/IT wire aliases, `PRO_MAX_SESSIONS_PER_USER`, the
      unattested `/uploads` read) were retired 2026-09-26.
- [x] Gateway deploy job (TD-27): `deploy-backend.yml`'s `gateway` job; first
      `apply` 2026-09-24 (run 35992296245, config `v202609241122-44`).
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

**Latency and failure visibility (`perf/backend-latency-and-failures`)**

- [x] Grant the runtime SA `iam.serviceAccountUser` **on itself only**, so
      Cloud Tasks accepts its OIDC tasks. Without it every enqueue 403s
      (`iam.serviceAccounts.actAs`) and large sessions provision inline:
      `gcloud iam service-accounts add-iam-policy-binding indic-api@indicvision-dic-app.iam.gserviceaccount.com --member=serviceAccount:indic-api@indicvision-dic-app.iam.gserviceaccount.com --role=roles/iam.serviceAccountUser --project indicvision-dic-app`.
      Granted 2026-09-23 (verified with `get-iam-policy`). Check still owed:
      the next backup's session reaches `UPLOADING` through the queue, and no
      `provision_enqueue_failed` events once this branch is deployed.
- [x] Give `indic-deployer@` `run.invoker` on `indic-api-staging` before the
      next staging deploy: the workflow now deploys staging private
      (`--no-allow-unauthenticated`), so the candidate smoke needs it.
      Granted 2026-09-23; staging invokers are now `indic-gw@`,
      `indic-deployer@` and `domain:indicvision.com`.
- [x] Deploy to production (min-instances 1, one worker, client nonces,
      `/readyz` no longer on the gateway — redeploy the gateway config from
      `backend/gateway/openapi.yaml`). Deployed 2026-09-23: `c0c0ce3` as
      `indic-api-35835537292-1` (run `35835537292`, minScale 1), gateway
      `semper-gw` on `v202609230812` (the diff against `v202609211150` was
      the `/readyz` block only). Checked: `/readyz` through the gateway → 404,
      unauthenticated `/v1/config` → 401, the console preflight → 200 with
      ACAO, invokers unchanged. Superseded the same day by the rename
      (#146): production is `semper-api-35844549945-1` (`d518179`) behind
      `v202609230845`; `indic-api`, its revisions and the older gateway
      configs are deleted. Rollback: `gcloud run deploy semper-api --image
      …/cloud-run-source-deploy/semper-api:rollback-c0c0ce3` (the
      `c0c0ce3` image, kept under the new package). [Note 2026-09-26:
      `c0c0ce3` predates ADR-007 and one licence per person; the rollback
      target is now `semper-api:rollback-prev`, which each deploy moves.]
- [ ] A signed call from a new build carries a `t1.` nonce and no
      `POST /v1/challenge` precedes it (next app release).
- [x] A session over `INLINE_PROVISION_MAX_FILES` (8) files reaches
      `UPLOADING` through `semper-provision` with no `provision_enqueue_failed`
      event (proves the Tasks grant). A phone backup cannot prove it: a bundle
      is 3 files and provisions inline. Two Pixel 6 backups on 2026-09-23 did
      that — `POST /v1/sessions` 6.06 s / 4.05 s, provisioning 5.37 s / 3.43 s.
      **Proven 2026-09-23** with the threshold set to `0` for the test, so the
      3-file Pixel 6 bundle took the queue path, then removed:
      staging (`semper-provision-staging`) and production (`semper-provision`,
      revision `semper-api-00006-gq5`) each logged `session_provision_queued`,
      a `Google-Cloud-Tasks` `POST /v1/tasks/provision-session` 200 and
      `session_provisioned` (production `latencyMs` 2752, `folderMs` 1653);
      `POST /v1/sessions` returned in 1.20 s and all three files completed.
- [x] Inline provisioning after the folder change (check-and-create run
      concurrently, no name search for the new session folder): a phone backup's
      `session_provisioned` shows `folderMs` and `latencyMs` well under the
      3.43 s above. Pixel 6 on 2026-09-23 against #149: `latencyMs` 2401,
      `folderMs` 1233, `POST /v1/sessions` 3.12 s (from 3.43 s / 4.05 s);
      the rest is opening the three resumable uploads, already in parallel.
- [x] Prune the tagged `cand-*` revisions so none holds a warm instance.
      Every deploy re-created one, so the promote now removes them
      (`chore/prune-cand-tags`, TD-32).
- [x] The first staging deploy after `chore/prune-cand-tags` promotes with
      `--to-latest` and leaves no `cand-*` tag (`gcloud run services describe
      semper-api-staging --format='value(spec.traffic)'`); then production.
      2026-09-23 (#151, `b65ff66`): `semper-api-staging-35856393597-1` and
      `semper-api-35856726929-1`, both `latestRevision` 100 %, no tags.

## Contention fixes found by the emulator tier

Wiring the Firestore emulator into CI immediately falsified three assumptions the
in-memory fake could not test (it applies transactional writes immediately, with
no isolation and no retries):

| Defect | Symptom in production | Fix |
|---|---|---|
| `bump_session_progress` used a read-modify-write transaction on one hot document | Concurrent completions exhausted the client's 5 retries → `Aborted: Transaction lock timeout` → **500 on the last files of an otherwise successful upload** | Atomic `firestore.Increment`, no transaction |
| `consume_nonce` raised when it lost the transaction race | Replay attempts returned 500 instead of 401 | Fail closed: contention → deny |
| `complete_file` raised when it lost the race | A client retry could 500 instead of resolving idempotently | Re-read and answer `already` / `""` |

## Readiness run 2026-10-24

The production-readiness run of 2026-10-24…26 on branch `claude/readiness-20261024`. The
[report](readiness/20261024/REPORT.md) has its scorecard, the ledger of all 139 findings,
the gate results and the Phase 8 fork plan. Findings are in
[FINDINGS.yaml](readiness/20261024/FINDINGS.yaml) and decisions in
[ADMIN_DECISIONS.md](readiness/ADMIN_DECISIONS.md). The branch was merged on 2026-10-26 as
#489 (`6d2bde6c`); on 2026-10-28 it was still not deployed.

- **Verdict: No-go** until all of the following hold:
  - the five P1s left unfixed (TE1-001, TE5-003, TE5-004, TE6-001, TE6-011) are fixed, or
    signed in [Risk acceptance](#risk-acceptance) below, where they are entered unsigned;
  - the branch merges with `CI OK`, including the first CI runs of `engine-pin` and
    `engine-host-sanitized`;
  - the backend is deployed from that `main`.
- **Re-verify.** Every P1 re-verified this run is VERIFIED, AE1-009 included (round 2, at
  `6324ea7f`). AE3-002 (P2) was VERIFIED at `b0f614ca` after rework round 4.
- **Posture.** Re-run 2026-10-24: 17 FAIL, 0 UNKNOWN (see Launch blockers above); the
  2026-10-28 run failed the same 17 items (readiness TE6-011).
- **Cross-check.** Rows 1.5, 2.1, 3.4, 3.7, 4.1, 4.3, 6.4, 6.5, 7.2, 7.3 and D3 now cite
  the branch. No status moved.
- **Admin actions.** The run's one-off actions are the report's §4 (A1–A18: push and PRs,
  the backend and Hosting deploys, deleting a credential screenshot and the unredacted
  posture log). Standing actions stay in the table below.

## Admin actions (external)

The one list of what only the Admin (the owner, with console and `gcloud` access, or
counsel) can do; the deploy checklist and CONTEXT point here instead of keeping their
own copies. Each row's detail is in its TECH_DEBT row. Tick a row here when its
done-when holds, and close or update the TD row in the same PR. Consolidated 2026-10-24
from the readiness review.

| # | Action | TD | Blocks | How | Done when |
|---|---|---|---|---|---|
| A1 | [ ] Monitoring live | TD-217 | production launch | Create a notification channel (Console → Monitoring → Alerting → Edit notification channels); `scripts/apply-monitoring.sh --project indicvision-dic-app --notification-channel <id> --apply`; then the `run.invoker` grant it prints under "By hand, once" ([DEPLOY_CHECKLIST_2026-10.md](DEPLOY_CHECKLIST_2026-10.md) §5) | 13 policies, 3 uptime checks and 4 dashboards exist, and a test notification reached the channel |
| A2 | [ ] GCP posture fixed | TD-221, TD-222, TD-223, TD-224, TD-225, TD-227 | production launch | Re-run `scripts/check_gcp_posture.sh` (read-only; flags as in the launch blockers above), then fix each FAIL by console or `gcloud`: lien and `Prod` tag, a second owner through a Google group, Firestore delete protection, Android-package / referrer restrictions on the Firebase API keys, DATA_READ + DATA_WRITE audit logs for firebase, firebaserules, firebaseappcheck and firestore; record the Firestore location and enumeration protection | A posture run with no FAIL but 3.1 (A8) and 3.5 (A4) and 0 UNKNOWN, recorded in this gate. Exit 0 once 3.1 and 3.5 pass too; exit 1 while any FAIL remains; exit 4 is an incomplete run (an item could not be read), never a pass (TE6-102) |
| A3 | [ ] Revocation acceptance | TD-214 | — (live since 2026-10-23) | Disable a test user in Firebase Auth; within the hour a console step-up answers 401 `invalid_token`, and an `AUTH_DENIED` row with `stage="revoked"` is written | Result recorded in TD-214 and the row closed |
| A4 | [ ] Entitlement signing key, then its pins | TD-215 | offline licence enforcement | [BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) A8 in order (P-256 key in Secret Manager, runtime-SA grant, `ENTITLEMENT_SIGNING_SECRET`, deploy); then the release variables `SEMPER_ENTITLEMENT_PUBKEYS` and `SEMPER_REQUIRE_ENTITLEMENT_PINS=true` ([ENVIRONMENTS.md](ENVIRONMENTS.md)) | `/v1/config` carries `entitlement`, and a release's `entitlement-pins` check passes |
| A5 | [ ] Certificate pins decided | TD-218, TD-318 | — | Choose a pin set (leaf or intermediate SPKI and a backup, following the gateway chain) and set `SEMPER_API_CERT_PINS`, or accept system trust in Risk acceptance below | TD-318 fixed or risk-accepted |
| A6 | [ ] Remaining lever drills | TD-220 | — | On staging: the `_SEMPER_CLASSROOM` pair with a Semper Classroom build; `ENTITLEMENT_OFFLINE_GRACE_DAYS`; `deploy-backend.yml` with `rollback_drill=true`; a revision rollback plus an env change by [RELEASING.md](RELEASING.md) "After a revision rollback"; confirm which lever variables are environment-scoped (Settings → Environments) | Each recorded with its run id in RELEASING's drill list |
| A7 | [ ] Deploy follow-through | — | — | [DEPLOY_CHECKLIST_2026-10.md](DEPLOY_CHECKLIST_2026-10.md) §6: staff and IT phones re-register with TOTP, the 24 h log watch, the erasure check, ending the staging QC test account | §6 ticked |
| A8 | [ ] Play, then App Check enforce | TD-229, TD-230, TD-231, TD-232, TD-226 | first Play release | Play developer account and listing; the Play app-signing SHA-256 on the Firebase Android app; the Data safety form ([PLAY_DATA_SAFETY.md](../legal/PLAY_DATA_SAFETY.md)); the pre-launch report; a closed-track rollout rehearsal by [RELEASING.md](RELEASING.md) stages; then `APP_CHECK_MODE` `monitor` → `enforce` once no build without a token is installed | Those rows closed |
| A9 | [ ] Crashlytics alerts | TD-252 | — | Firebase console → Crashlytics → velocity alerts, routed to the on-call; send a test | A test alert reached the on-call |
| A10 | [ ] Counsel | TD-277, TD-241, TD-36 | publishing the legal pages | Sign-off on the Terms; the breach playbook and a tabletop ([INCIDENT_RESPONSE.md](INCIDENT_RESPONSE.md)); the operator's legal fields in `docs/legal/` | Those rows closed |
| A11 | [ ] Support targets | TD-258 | B2B contracts | Set response targets and maintenance windows in [SUPPORT_POLICY.md](SUPPORT_POLICY.md) | TD-258 closed |
| A12 | [ ] Penetration test | TD-240 | production launch | A third-party test on [PENTEST_SCOPE.md](PENTEST_SCOPE.md) | Report filed, findings owned |
| A13 | [ ] Device smoke of the next release | TD-219 | that release | [RELEASING.md](RELEASING.md) step 5 (the smoke, and `EnginePipelineSmokeTest` on one arm64 device or arm64 AVD) and TD-219's device checks (installs on Android 11, refused on Android 10), recorded in [CHANGELOG.md](CHANGELOG.md) | Recorded for the release |
| A14 | [ ] Owner decisions | TD-319 | — | Whether the backend refuses device-signed upload and restore below `MIN_SUPPORTED_VERSION_CODE` by `X-App-Version` ([RELEASING.md](RELEASING.md), Rollback levers); whether Schemathesis or Hypothesis may be added (TD-319) | Each decided and recorded |

## External UNKNOWN register (do not invent PASS)

| Control | Evidence |
|---------|----------|
| Firebase MCP / active project | Confirm in console |
| GitHub Environments | All five exist (`staging`, `production`, `release`, `production-backup`, `restore-drill`); `production` requires the owner as reviewer (2026-10-22); none has a branch policy, and `restore-drill` has no variables (2026-10-08) |
| Secrets / vars | Prefer **repo-level** on Free private orgs; Environment names still select workflow targets |
| Branch protection / ruleset | Ruleset `main` (id 24720948, 2026-10-08): requires `CI OK`, no force-push, no deletion; no bypass actors |
| Project liens, `Prod` tag, owners through groups (framework 2.1) | Read 2026-10-22 and 2026-10-24 (`scripts/check_gcp_posture.sh`): FAIL on both projects, no lien, no `Prod` tag, one owner, a user not a group (TD-221) |
| Firebase API-key restrictions (2.4) | API list only, no app or origin restriction (2026-10-08; TD-222) |
| Enabled sign-in providers, anonymous auth, enumeration protection (2.5) | Providers read 2026-10-22 (posture PASS: anonymous off; email and google.com on); enumeration protection not read (TD-223) |
| Billing budgets and who their alerts reach (2.6) | Budgets read 2026-10-22 (posture PASS: a budget on both projects, max-instances 10 on both services); who their alerts reach not read (TD-224) |
| Data Access audit logs for Firebase, Rules, App Check, Firestore (3.7) | Read 2026-10-22 and 2026-10-24 (posture FAIL): no DATA_READ + DATA_WRITE for firebase, firebaserules, firebaseappcheck, firestore (TD-225) |
| Production Firestore location (2.9) | Not read; setup says asia-south1 (TD-227) |
| Play app-signing SHA-256 on the Firebase Android app (1.6) | No Play account linked (TD-229) |
| Crashlytics velocity alerts and routing (7.1) | Not read (TD-252) |
| `roles/firebaseauth.admin` for `indic-api@` on `indicvision-dic-app-auth` (3.4) | Granted (posture 3.4-iam PASS, 2026-10-22); `CHECK_REVOKED_ON_STEP_UP=true` on `semper-api` since 2026-10-23; acceptance owed (TD-214) |
| Entitlement signing secret, deploy SA `secretmanager.viewer` (3.5, 4.1) | Not created (TD-215) |
| Monitoring applied (`scripts/apply-monitoring.sh`) (7.2) | Not applied (TD-217) |

## Framework cross-check (2026-10-18)

Every item of the external readiness framework
([READINESS_FRAMEWORK_2026-10.md](READINESS_FRAMEWORK_2026-10.md)), checked
against the branch `claude/new-session-jhfhj9` on 2026-10-18 (W1 backend, W2
console, W3 Android and W4 CI/ops merged). The framework assumes Cloud Functions,
client-side Firestore rules and Identity Platform tenants; Semper is FastAPI on
Cloud Run, reaches Firestore only through the Admin SDK behind deny-all rules
(`firestore.rules:7-9`), has no Cloud Functions or Cloud Storage, and its tenant is
the institution licence (`backend/app/routers/institutions.py:35-55`;
[ADR-023](../adr/ADR-023-signed-offline-entitlement.md)). Rows are mapped onto
that. Rows 3.4, 4.3, 5.1, 5.3, 5.5–5.7, 6.1, 6.2, 7.3, 7.4, 8.1, 8.5, B-offline,
B-clock, B-refresh, C-console, C-emm and D1, D2, D4 were brought up to the
follow-up branch `claude/readiness-followups-jhfhj9` at `75bac5f` the same day
(D1 and D2 moved to PASS). Rows 3.4, 4.1, 5.6, 6.4, 8.4, B-model, B-first-activation, B-refresh,
B-resilience, C-provisioning, C-console, C-emm, D5 and E-tamper were brought up to
the account-anchored entitlement change at `2371bec`
([ADR-024](../adr/ADR-024-account-anchored-entitlements.md)); no status moved, so the
count stands. TD-259 and TD-272 closed; TD-274, TD-276 and TD-277 joined owner cells.
TD-279 closed on 2026-10-19 (no row's status moved); row 8.4's evidence names the aligned Terms.
TD-269, TD-270 and TD-276 closed on 2026-10-19 after #448 and #449 merged; they left the owner
cells of rows 3.5, 5.3, 7.4, 8.1 and B-model, and no status moved. Row 1.3 moved
from GAP to PASS with [ADR-028](../adr/ADR-028-tamper-evident-records.md) (#471).
Rows 1.5, 2.1, 3.4, 3.7, 4.1, 4.3, 6.4, 6.5, 7.2, 7.3 and D3 were brought up to the readiness
branch `claude/readiness-20261024` on 2026-10-26, each naming its finding ids
([Readiness run 2026-10-24](#readiness-run-2026-10-24)). No status moved.

**Count:** PASS 12, PARTIAL 41, GAP 9, EXTERNAL 12, N-A 2 (the table below;
`scripts/check_framework_coverage.py` fails when a count written in this section
disagrees with it).

Status: **PASS** (built or done, with evidence), **PARTIAL** (part built, the rest
owned), **GAP** (not built), **EXTERNAL** (a console, Play or human step; the repo
holds the verifier or runbook), **N-A** (does not apply, with the reason). Every
row that is not PASS or N-A names its owner: a TECH_DEBT row (TD-nnn) or a
FUTURE_IMPROVEMENTS entry (FI-nn). App paths are from
`app/src/main/java/com/sempermechanics/semper/` unless they start at the
repository root. The Android half was not compiled where it was written; CI run
39158239853 on `ae4c24a` compiled and tested it, and the follow-up branch's Android
changes have not run in CI yet (TD-219).
`python scripts/check_framework_coverage.py` fails when an item is missing or an
owner id does not resolve.

### Part A

| Item | Framework requirement (short) | Status | Evidence | Owner |
|---|---|---|---|---|
| 1.1 | Crash-free and ANR thresholds defined | EXTERNAL | Thresholds written: crash-free users 99.5 %, user-perceived ANR 0.47 % ([RELEASING.md](RELEASING.md), "Staged rollout on Google Play"). No closed track to measure them on | TD-231 |
| 1.2 | Pre-launch report clean | EXTERNAL | No Play listing; builds ship as signed APKs from GitHub Releases ([RELEASING.md](RELEASING.md), release checklist step 6) | TD-230 |
| 1.3 | Measurement records tamper-evident, survive process death and offline | PASS | Offline and process death: analysis is on the phone, the wizard survives a kill ([ADR-005](../adr/ADR-005-wizard-process-death.md)). Tamper evidence: each run writes a signed `record_manifest.json` beside its `.dat` files (SHA-256 per file, run spec, app and engine pin, device-key ECDSA signature; the `.dat` bytes and oracles unchanged), committed last so a killed run leaves none; the viewer and the PDF cover show Verified (this phone's own key only) / Signed on another device / Changed / Not signed / Not recorded ([ADR-028](../adr/ADR-028-tamper-evident-records.md)). Verify: `RecordManifestTest`, `RecordManifestRunTest`, `RecordManifestBackupTest`; the kill-and-replay `RecordManifestProcessDeathTest` (instrumented) passed on the Semper_API30 AVD (API 30), 2026-10-22, 1 test, 0 failures; the device check (Verified, then Changed after a one-bit edit, then Signed on another device after a restore on a second emulator) passed 2026-10-23 ([ADR-028](../adr/ADR-028-tamper-evident-records.md) action items) | — |
| 1.4 | Offline-first data layer, idempotent sync, conflict policy | PASS | Analyses are local; uploads are unique WorkManager work with `KEEP` (`data/cloud/CloudSync.kt:408`); an in-flight session is reused for the same `localSessionId` (`backend/app/repo/sessions.py:493-517`); file completion is idempotent (`backend/app/repo/sessions.py:596-603`); staged bytes are identical across attempts (CONTEXT.md, Invariants); metadata replace is [ADR-013](../adr/ADR-013-session-metadata-replace.md) | — |
| 1.5 | Release build hardening, checked by APK inspection in CI | PARTIAL | R8 on release (`app/build.gradle.kts:236`), `DEV_AUTH_BYPASS` false in release (`:235`), Play Integrity is the only App Check provider (`:371`); `scripts/check_release_apk.py` in `.github/workflows/release.yml:258` and Tier 5 (`.github/workflows/ci.yml:950`). Not yet run on a real APK. Since the readiness run (2026-10-24) the check also: requires an R8 mapping that renames app classes (`r8-mapping`, `scripts/check_release_apk.py:430-447`, TE1-002); fails on consent collection left on (`consent-defaults`, `:509`, TE5-005); checks the release signer against the pinned certificate (`signing-key`, `:476-480`; `--expected-signer-sha256`, `.github/workflows/release.yml:262`, TE1-007); and fails a release without entitlement pins once a key variable is set (`:467`; `.github/workflows/release.yml:263`, TE4-001). `build-release` needs `verify-tag` and `verify-ci` (`.github/workflows/release.yml:140`; `scripts/check_release_tag.py`, `scripts/check_release_ci.py`; TE6-008, TE6-009). Certificate pins stay WARN (`:454-456`; TE1-001) | TD-218, TD-219, TD-318 |
| 1.6 | Play app-signing SHA in Firebase | EXTERNAL | Release certificate in `firebase-hosting/public/.well-known/assetlinks.json`; no Play account linked (Licensing rollout, "Play developer account" above) | TD-229 |
| 2.1 | Dev/staging/prod projects; Prod tag, lien, two or more owners, groups | EXTERNAL | `scripts/check_gcp_posture.sh:145-186` reports lien, tag, owners and groups; runs 2026-10-22 and 2026-10-24: FAIL on both projects (no lien, no `Prod` tag, one owner, a user not a group; checklist above). Staging shares the production project and database ([ENVIRONMENTS.md](ENVIRONMENTS.md)) | TD-221, TD-248 |
| 2.2 | Rules default-deny; every collection has explicit rules | PASS | `firestore.rules:7-9` denies every client; tested per collection on the emulator (`firestore-rules-tests/rules.test.mjs:173-186`) for every collection the backend names (`firestore-rules-tests/collections.mjs:19-112`, guarded by `collections.test.mjs`). No Cloud Storage. Tenant-scoped rules have nothing to scope: no client reaches Firestore | — |
| 2.3 | Licence and seat mutations only server-side; client writes fail | PASS | Only the backend's Admin SDK writes `licenses`, `seats`, `devices`; client writes to each are `assertFails` on the emulator (`firestore-rules-tests/rules.test.mjs:175`). The "Cloud Functions" half is Cloud Run here | — |
| 2.4 | API keys restricted to required APIs | EXTERNAL | Keys limited to an API list but not to the app or origins ([TECH_DEBT.md](TECH_DEBT.md), "Checked by hand"); posture item 2.4 (`scripts/check_gcp_posture.sh:187-207`) | TD-222 |
| 2.5 | Unused auth providers off, anonymous especially | EXTERNAL | Posture item 2.5 (`scripts/check_gcp_posture.sh:208-235`); "Confirm Auth abuse / enumeration protections" open above | TD-223 |
| 2.6 | Budget alerts and spend caps | EXTERNAL | No Functions; the cap is `--max-instances=10` (`.github/workflows/deploy-backend.yml:368`); budgets unverified (posture 2.6, `scripts/check_gcp_posture.sh:236-260`) | TD-224 |
| 2.7 | PITR, daily backups, restore drill | PASS | PITR on and daily export running, checked 2026-09-24 ("Confirm PITR" above); `.github/workflows/firestore-backup.yml:5`; monthly drill `.github/workflows/firestore-restore-drill.yml:12`, passed 2026-10-10 with an import RTO of 111 s and a count and relationship diff ("Run and record one Firestore restore drill" above). Posture item `2.7-backup` passes on a daily managed schedule or, given `--backup-bucket`, an export younger than 48 h, and reports UNKNOWN without the bucket (`scripts/check_gcp_posture.sh:306-343`); not yet run | TD-217 |
| 2.8 | 500/50/5 capacity plan, load test, no hotspots | GAP | No load test; seat counts are one counter per licence document (`backend/app/repo/claims.py:255`); staging shares production data | TD-249, TD-248 |
| 2.9 | Firestore location chosen and documented | PARTIAL | Setup creates it at asia-south1 (`docs/backend/BACKEND_SETUP_GCP.md:49`, `:81`); §5 says `nam5` (`docs/backend/CLOUD_ARCHITECTURE_GCP.md:542`); the policy's region is a placeholder (`docs/legal/PRIVACY_POLICY.md:153`) | TD-227, TD-36 |
| 3.1 | App Check enforced (Play Integrity) | EXTERNAL | Built for device callers (`backend/app/deps.py:86-120`), `APP_CHECK_MODE=off` (`.github/workflows/deploy-backend.yml:348`); Firestore needs none (deny-all), no Storage or Functions | TD-226, TD-229 |
| 3.2 | Rules unit tests in CI, cross-tenant test per collection | PASS | Job `firestore-rules` (`.github/workflows/ci.yml:832-872`) runs `@firebase/rules-unit-testing` on the emulator: signed out, owner, another user and admin-looking claims, per collection (`firestore-rules-tests/rules.test.mjs:173-186`); required by `CI OK` (`.github/workflows/ci.yml:1145`), which the `main` ruleset requires ("Branch ruleset" above) | — |
| 3.3 | Replay protection on activation functions | N-A | `consumeAppCheckToken` exists only for Node.js Cloud Functions; Semper has none. Request replay is refused by single-use device-signed nonces (`backend/app/repo/devices.py:133`; `backend/tests/test_device_auth.py:130`, `:172`) | — |
| 3.4 | Revocation checked on sensitive calls | PARTIAL | `verify_id_token(check_revoked=True)` on the browser step-up (`backend/app/step_up.py:117-229`; `backend/app/google_auth.py:64`), off by default (`backend/app/config.py:273-275`) until the Auth IAM grant; tests `backend/tests/test_revocation.py`. The check, `revoke_sessions` and `delete_auth_user` share a per-instance Auth Admin budget (`AUTH_ADMIN_CALLS_PER_SECOND` 10, `AUTH_ADMIN_BURST` 20; `backend/app/rate_limit.py:138-147`); spent, the check answers 503 `auth_check_busy`, not `invalid_token` (`backend/app/step_up.py:207-215`), which the consoles explain in one sentence (`firebase-hosting/public/console/messages.js:19`). Posture 3.4-check-revoked PASS on `semper-api` and `semper-api-staging` (2026-10-24); the acceptance check is owed | TD-214 |
| 3.5 | Secrets out of APK and repo; signing key in KMS or Secret Manager | PARTIAL | gitleaks on every event (`.github/workflows/ci.yml:38-79`); the signing key is mounted only as a Secret Manager reference (`.github/workflows/deploy-backend.yml:266-286`). the deploy checks that the runtime SA can read it (`scripts/check_secret_access.sh`). Key not created; IAM not reviewed; no KMS | TD-215, TD-236 |
| 3.6 | MASVS v2 assessment, third-party pentest | PARTIAL | Self-assessment [MASVS_ASSESSMENT.md](MASVS_ASSESSMENT.md); scope [PENTEST_SCOPE.md](PENTEST_SCOPE.md); no test run | TD-240 |
| 3.7 | Cloud Audit Logs for Firebase Management, Rules, App Check | EXTERNAL | Posture item 3.7 (`scripts/check_gcp_posture.sh:384-399`); runs 2026-10-22 and 2026-10-24: FAIL, no DATA_READ + DATA_WRITE audit logs for firebase, firebaserules, firebaseappcheck, firestore | TD-225 |
| 4.1 | Signed entitlement, pinned key, rotation by `kid` | PARTIAL | ES256, not Ed25519 ([ADR-023](../adr/ADR-023-signed-offline-entitlement.md)): `backend/app/entitlement_token.py:123-193`; app `data/account/entitlement/EntitlementVerifier.kt:64-106`, pins `EntitlementPins.kt:32`. Tests: tampered, unknown `kid`, rotated (`backend/tests/test_entitlement_token.py:113`, `:126`, `:139`; `EntitlementVerifierTest`). No key provisioned. The app's copy of the backend's token vector must stay byte-equal to it (`backend/tests/test_entitlement_token.py:382`, TE4-005), and the sealed record has a committed v1 golden (`app/src/test/resources/entitlement/sealed_v1.txt`, TE1-004). Paid entitlements come only from the account (roster seat, individual licence, claim code on the web account page; [ADR-024](../adr/ADR-024-account-anchored-entitlements.md)) and every cloud route re-checks them on the server; share alone rests on the token | TD-215, TD-219, TD-274 |
| 4.2 | Non-exportable Keystore key, attestation verified server-side | GAP | Non-exportable P-256 Keystore key (`data/account/DeviceKeys.kt:75-86`) bound into the token as `dkt` (`backend/app/entitlement_token.py:110-119`); no attestation challenge or server verification | TD-234 |
| 4.3 | Grace period, refresh cadence, clock-rollback detection | PARTIAL | Grace 14 days, capped at licence end plus grace and lease end (`backend/app/entitlement_token.py:201-215`); `refreshAfter` 24 h (`:242`), and a token past it, or lapsed, queues a one-off `/v1/config` at launch and on return to the foreground (`data/LicenseConfigWorker.kt:123-138`; `data/account/entitlement/EntitlementRefresh.kt:57-61`); rollback over 10 min ends grace (`data/account/entitlement/EntitlementClock.kt:89`, `:140-155`); HMAC-sealed state, with a lost key or a crash mid-seal read as no token rather than tampering (`SealedEntitlement.kt:37-63`; `SealedRecordStore.kt`; `EntitlementPolicy.kt:137-144`); the quota screens show the cap enforced (`LicenseEntitlements.shownAnalysisCap`, `data/account/LicenseEntitlements.kt:229`); time-travel tests `EntitlementClockTest`, at the production token shape (grace 14 days, `graceUntil` = `exp`) since readiness TE4-002, and the backend's boundary test at the production default (`backend/tests/test_licenses_terms.py:147`). The follow-up branch's Android changes have not run in CI; deleting the key reverts to the cached config | TD-219, TD-261 |
| 4.4 | Revocation propagates at next check-in; "pending" until the device confirms | PARTIAL | Every request with `X-Device-Id` re-validates the lock and drops to Demo (`backend/app/deps.py:181-188`); `/v1/config` every 4 h (`data/LicenseConfigWorker.kt:84`); "revocation pending" inferred from `lastSeenAt` (`backend/app/roster_io.py:239-255`), not confirmed by the device | TD-238 |
| 4.5 | IT releases a seat without the device; an audit-log entry results | PASS | IT: `PATCH …/seats/{uid}` with `clearDeviceLock` (`backend/app/routers/roster.py:335-367`), recorded as `INSTITUTION_SEAT_PATCH`; staff: `backend/app/routers/admin.py:386-416`; history `GET …/device-history` (`admin.py:473-490`); tests `backend/tests/test_licenses_institution.py:345`, `backend/tests/test_device_history.py:85` | — |
| 5.1 | Tenant console: seat dashboard, device list, user CRUD, CSV import/export | PARTIAL | Import, export, summary tiles (`backend/app/routers/roster.py:172-273`; `firebase-hosting/public/console/roster-tools.js`, shared by the institution page and, on the staff routes, the operator desk's roster card); add, hold, remove; an exported address imports back (`backend/app/roster_io.py:134-143`). No per-device last check-in or bulk release; no usability test; routes need the index and gateway deploy | TD-255, TD-256, TD-216 |
| 5.2 | Four-role RBAC | GAP | Two all-or-nothing roles: staff (`backend/app/deps.py:339-350`) and licence `adminEmails` (`backend/app/routers/institutions.py:53`) | FI-19 |
| 5.3 | Append-only audit log, exportable, customer-visible | PARTIAL | Rows only added (`audit.record`, `backend/app/audit.py:71`, `:93`), held so by `backend/tests/test_audit_append_only.py`, the migration loader's `APPEND_ONLY` guard (`backend/scripts/migrations/__init__.py:32`) and the rules test (`firestore-rules-tests/rules.test.mjs:188-215`, an Org Owner's claims included; #457, not merged); each row also logged as an `audit_row` line for a locked bucket (`audit.py:56-68`); per-licence JSON/CSV export (`backend/app/routers/roster.py:275-314`; `backend/app/repo/audit_log.py:122-161`) and console viewer (`firebase-hosting/public/console/audit-log.js`, on the institution page and the operator desk); addresses in rows are hashed (`audit.py:27-38`). Against project IAM, the locked `audit-trail` log bucket holds each row's copy for 365 days (2026-10-23, TD-237 closed); rows before 2026-10-18 were given `licenseId` by the backfill (`backend/scripts/stamp_audit_licence_ids.py`, run 2026-10-23), and the export check for them is owed | TD-246, TD-216 |
| 5.4 | SAML/OIDC SSO per tenant | GAP | Password, email link, Google; TOTP for consoles | FI-17 |
| 5.5 | Deprovisioning revokes refresh tokens, frees the seat at next check-in | PARTIAL | Suspend revokes refresh tokens (`backend/app/routers/admin.py:75-97`), best-effort until the IAM grant and within the Auth Admin budget (`backend/app/google_auth.py:96-101`); a removed seat frees at once and the holder drops to Demo at the next request (`backend/app/repo/seats.py:339-390`). No IdP-driven path | TD-214, FI-21 |
| 5.6 | EMM managed configuration | PARTIAL | `app/src/main/res/xml/app_restrictions.xml`; reader and change receiver (`data/policy/ManagedConfigReader.kt:38-70`); backup constraints (`data/cloud/CloudSync.kt:396-398`); the Google sheet limited to `accountEmailDomain` (`ui/auth/GoogleSignInHelper.kt:60`), compiled in CI run 39158239853. `enrollmentInvite` removed 2026-10-18: nothing is entered in the app, entitlements attach to the account ([ADR-024](../adr/ADR-024-account-anchored-entitlements.md)). Not tried in an EMM | TD-257 |
| 5.7 | Self-service docs, admin API or SCIM | PARTIAL | Contract [ADMIN_API.md](../backend/ADMIN_API.md): IT's roster routes, the same routes for staff under `/v1/admin/licenses/{id}`, and the licence search in a body (`POST /v1/admin/licenses/search`); tests `backend/tests/test_roster_bulk.py`, `backend/tests/test_route_authz_matrix.py`, `backend/tests/test_licence_search_post.py`. Every change needs an interactive step-up; no SCIM | FI-20, FI-18 |
| 6.1 | Monotonic `versionCode`; backend publishes minimum and recommended | PARTIAL | `versionCode` = 1000 + run number ([RELEASING.md](RELEASING.md), Versioning); `minSupportedVersionCode`, `recommendedVersionCode` on the authenticated `/v1/config`, each app its own pair (`backend/app/routers/account.py:162-169`; `settings.release_gate`, `backend/app/config.py:319-335`); gate `data/policy/VersionGate.kt:33`, `ui/limit/UpdateRequiredActivity.kt:23`, whose Update opens the Play listing for a Play install and the GitHub Releases page otherwise (`ui/limit/UpdateSource.kt:28-36`). No Remote Config: `/v1/config` is the channel. Drilled on staging 2026-10-22: a minimum of 200 opened the update screen on two emulators ([RELEASING.md](RELEASING.md#rollback-levers)), and `RECOMMENDED_VERSION_CODE=200` showed the once-only Home nudge (run 39762144670); the `_MATERIAL_TESTING` pair is not drilled | TD-220, TD-219 |
| 6.2 | In-app updates, immediate and flexible | GAP | The gate opens the Play listing or, for a sideloaded build, the release page (`ui/limit/UpdateRequiredActivity.kt:49-58`) | TD-233 |
| 6.3 | Staged rollout with written halt criteria | PARTIAL | Stages, halt criteria and levers in [RELEASING.md](RELEASING.md); AAB built and attached (`.github/workflows/release.yml:206`, `:329`). Not rehearsed | TD-231 |
| 6.4 | Risky features behind flags; licensing fail-open/fail-closed switch | PARTIAL | `KILL_SWITCHES` for backup, share, restore, upload (`backend/app/config.py:28-44`, `:336-349`; `data/policy/FeatureGates.kt:21-40`), enforced server-side since 2026-10-18: `upload` / `backup` refuse `POST /v1/sessions` and `POST /v1/files/{id}/complete`, `restore` refuses file content and the bundle, 503 `feature_paused` with `Retry-After` (`deps.unless_paused`, `backend/app/deps.py:301-336`; `backend/tests/test_kill_switches.py`), which the app waits out (`data/cloud/UploadWorkOutcomes.kt:33`, `data/cloud/restore/DownloadFailure.kt:44`); since readiness TE4-004, `upload` / `backup` also refuse `GET /v1/sessions/{sid}/uploads` and `PUT /v1/sessions/{sid}/metadata` (`backend/app/routers/sessions.py:140`, `:223`), which the app treats as a pause too (`data/cloud/UploadErrors.kt:66`, `data/cloud/SessionMetadataSync.kt:101-103`), live at the next backend deploy; `share` is the app's own and published only; the grace length is the licensing policy lever (`backend/app/config.py:306`). Flag drill on staging 2026-10-22 for `backup`, `share`, `restore` ([RELEASING.md](RELEASING.md#rollback-levers)); the grace length not drilled | TD-220 |
| 6.5 | Expand/contract; N-2 compatibility CI | GAP | Expand/contract by runbook ([FIRESTORE_SCHEMA_RUNBOOK.md](../backend/FIRESTORE_SCHEMA_RUNBOOK.md)); wire contracts pinned (`backend/tests/test_wire_contracts.py`); the request side of the API is pinned by a committed OpenAPI snapshot (`backend/contracts/app-openapi.json`, `backend/tests/test_openapi_snapshot.py`; [ADR-029](../adr/ADR-029-api-contract-snapshot.md), readiness TE3-006; responses are TD-320); no N-2 job | TD-251 |
| 6.6 | Release notes; deprecation notice period in the contract | PARTIAL | GitHub Release changelog and [CHANGELOG.md](CHANGELOG.md); release-notes rule in [RELEASING.md](RELEASING.md). No notice period in the Terms | TD-258 |
| 7.1 | Crashlytics velocity alerts; Performance Monitoring | PARTIAL | Crashlytics with consent (`diagnostics/CrashReportingTree.kt:23-41`); alerts are console settings, unconfirmed; no Performance SDK | TD-252, TD-253 |
| 7.2 | SLOs and burn-rate alerts | PARTIAL | 99.5 % availability SLO and fast and slow burn alerts as code (`ops/monitoring/slo/availability.json`, `ops/monitoring/policies/slo-burn-fast.json`); applied by `scripts/apply-monitoring.sh`, not yet run. Since readiness TE6-003 the gateway that users call is probed from outside too (`ops/monitoring/uptime/gateway-healthz.json`, `gateway-config.json`; policy `ops/monitoring/policies/uptime-gateway.json`); the SLO still counts the `run.app` service | TD-217 |
| 7.3 | Licence anomaly signals | PARTIAL | `activation` and `checkout` events per licence, app, build and uid (`backend/app/routers/licenses.py:19-38`), `devlock_drop` with uid (`backend/app/repo/devlock.py:319-328`); activations-per-licence alert (`ops/monitoring/policies/activation-anomaly.json`); the licensing and security dashboards (`ops/monitoring/dashboards/licensing.json`, `security.json`). Not yet applied; no Play Integrity ratio. Readiness TE6-002 added alert policies for device-lock mismatches, activation and checkout refusals per build, App Check refusals and failed revocation checks (`ops/monitoring/policies/devlock-mismatch.json`, `licensing-refusals.json`, `app-check-refused.json`, `revocation-check-failed.json`), and TE6-007 the refused share per build on the licensing dashboard; none applied yet | TD-217, TD-234 |
| 7.4 | Structured logs with tenant and request id, no clear-text PII | PARTIAL | Allow-listed fields incl. `requestId`, `licenseId`, `appId` (`backend/app/observability.py:24-32`); emails hashed (`:225-232`; `backend/app/tasks.py:183`); the staff search sends its address in a body (`POST /v1/admin/licenses/search`, `backend/app/routers/admin.py:161-169`); audit rows hash addresses (`backend/app/audit.py:27-38`). The GET's `q` still answers for one release | TD-273 |
| 8.1 | Data map | PARTIAL | [DATA_MAP.md](../legal/DATA_MAP.md), derived from the code. No DPIA; findings recorded | TD-241, TD-271 |
| 8.2 | DPDP readiness by 13 May 2027; Rule 7 playbook | PARTIAL | [INCIDENT_RESPONSE.md](INCIDENT_RESPONSE.md) "Personal-data breach"; Grievance Officer and operator fields unfilled; no tabletop | TD-241, TD-36 |
| 8.3 | GDPR for EU customers | N-A | The service is not offered in the EU, EEA or UK (`docs/legal/PRIVACY_POLICY.md:25-29`); revisit before serving them | — |
| 8.4 | Play Data safety consistent with SDKs | EXTERNAL | Answers to submit in [PLAY_DATA_SAFETY.md](../legal/PLAY_DATA_SAFETY.md), from `app/build.gradle.kts:378-440` and the release APK's permissions; not submitted, two OWNER DECISIONs (delete-account URL, Analytics sharing settings). `AD_ID` and the `ACCESS_ADSERVICES_*` permissions are removed (`app/src/main/AndroidManifest.xml:16-27`). The app is consumption-only ([ADR-024](../adr/ADR-024-account-anchored-entitlements.md)); the Terms it links say so (6.6) and keep fees for access arranged outside the app, for counsel | TD-232, TD-277, TD-317 |
| 8.5 | Export and deletion per tenant and per user | PARTIAL | Per user: `GET /v1/me/export`, `DELETE /v1/me` (`backend/app/routers/account.py:192`, `:294`), Auth user deleted after TD-214 (`:350`), which removes the sign-in for both apps (ADR-023 decision note; the app's confirmation says so, `app/src/main/res/values/strings.xml:321`); erasure also deletes the account's `auth_links` rows and blanks the address on every seat it held (`backend/app/repo/account.py:156-244`). Per institution: none. The seat search needs its index deployed first | FI-23, TD-271 |

### Parts B–E

| Key | Framework section | Status | Evidence | Owner |
|---|---|---|---|---|
| B-model | Part B "Model": licence, seat, device records | PARTIAL | Licence `kind`, `seating` (assigned / floating), `maxSeats`, `graceDays`, term; seat per member with lease; device record with key, model, app version, status ACTIVE / REVOKED / SUPERSEDED ([CLOUD_ARCHITECTURE_GCP.md §5](../backend/CLOUD_ARCHITECTURE_GCP.md#5-firestore-schema)). One device per app per account ([ADR-010](../adr/ADR-010-device-binding-per-app.md)) instead of `maxDevicesPerSeat`; entitlement sources are a roster seat, an email-locked licence or a claim code, all attached to the account ([ADR-024](../adr/ADR-024-account-anchored-entitlements.md)); the demo cap counts stored analyses per account and per device; offline grace is fleet-wide; no `revocation_pending` device status | TD-238 |
| B-first-activation | Part B "First activation (online, required)" | PARTIAL | Online sign-in, device key registration (`backend/app/repo/devices.py:80-106`), first `/v1/config` issues the token, and a first sign-in asks again once the device is registered, before Home opens (`EntitlementRefresh.afterSignIn`, `data/account/entitlement/EntitlementRefresh.kt:81`; `data/account/AccessStatusResolver.kt:62-79`). Nothing is typed: a paid entitlement is already on the account ([ADR-024](../adr/ADR-024-account-anchored-entitlements.md)). No attestation chain, Integrity verdict or revocation check at activation | TD-234, TD-214 |
| B-offline | Part B "Offline operation" | PARTIAL | Signature against pinned keys, `dev` and `dkt` against this install's key (the Keystore-bound check in place of a signed local nonce), time window (`EntitlementVerifier.kt:64-94`, `EntitlementClock.kt:140-155`). A lapsed token (tampered record, grace over, rollback, expiry) narrows to Demo; a token that does not verify is ignored and the cached config decides (`EntitlementPolicy.kt:91-105`, `:177-184`); local results are never locked. A token past `refreshAfter` asks for a fresh one when online (`data/LicenseConfigWorker.kt:123-138`). Untested in CI on the follow-up branch | TD-219 |
| B-clock | Part B "Clock-tampering resistance" | PARTIAL | `lastServerTime`, wall-clock high-water mark, 10-minute tolerance, `elapsedRealtime` across boots, HMAC under a Keystore key (`data/account/entitlement/EntitlementClock.kt:89-155`, `EntitlementStore.kt:186-226`). Deleting the key, with or without the record, reverts to the cached config (`EntitlementPolicy.kt:137-144`) | TD-261, TD-219 |
| B-refresh | Part B "Refresh and check-in" | PARTIAL | `refreshAfter` 24 h in the token, read by the app (`data/account/entitlement/EntitlementRefresh.kt:57-61`); `/v1/config` every 4 h; after each floating checkout or heartbeat the app refetches `/v1/config` so the token follows the lease, and a failed fetch queues a retried one-off refresh (`data/account/SeatLease.kt:79-87`; `data/LicenseConfigWorker.kt:109-120`); a floating token past its lease opens the seat screen (`EntitlementPolicy.kt:126-129`); floating tokens capped at the lease end (`backend/app/entitlement_token.py:213-214`); "Refresh licence" in Settings and on the limit screen fetches on demand (`ui/limit/LicenseRefresh.kt:62-72`). No Integrity re-check | TD-234 |
| B-revocation | Part B "Revocation, reassignment, replacement" | PARTIAL | Seat revoke frees the slot immediately (the "policy option"; `backend/app/repo/seats.py:366-372`); reassignment is add and remove, each with an audit-log row; device force-release (`backend/app/routers/admin.py:90-116`, `roster.py:335-367`); old tokens bounded by `graceUntil`. No device recall; no device confirmation | TD-238, TD-234 |
| B-resilience | Part B "Resilience controls (MASVS-RESILIENCE)" | GAP | R8 only (`app/build.gradle.kts:236`); no signature, installer, debugger or Frida checks. Share has no server route, so a modified APK can share without a licence (accepted) | TD-235, TD-274 |
| B-distribution | Part B "Distribution caveat" | EXTERNAL | Builds are sideloaded from GitHub Releases, so no Play Integrity verdict is available to them; staging is not a separate project | TD-229, TD-248 |
| B-build-vs-buy | Part B "Build vs. buy" | PASS | Built in-house on the existing licensing backend; vendors rejected in [ADR-023](../adr/ADR-023-signed-offline-entitlement.md) "Options considered" | — |
| C-tenancy | Part C "Tenancy" | PARTIAL | Tenant = institution licence, scoped by `adminEmails` with 404 across tenants (`backend/app/routers/institutions.py:35-55`); Identity Platform tenants rejected for now ([ADR-023](../adr/ADR-023-signed-offline-entitlement.md)). No per-tenant IdP | FI-17 |
| C-provisioning | Part C "Provisioning" | PARTIAL | Phase 1 built: CSV import and export, invitations by address (`backend/app/repo/invites.py:155-163`); a one-time claim code on the web account page joins an institution by email domain or recovers a failed invite (`POST /v1/me/claim`, `backend/app/routers/licenses.py:195-256`). No JIT SSO, no SCIM, no IdP deactivation | FI-18, FI-21, FI-17 |
| C-console | Part C "Console essentials" | PARTIAL | Utilisation tiles incl. active in 7 days and revocation pending, expiring warning, audit viewer with export (`firebase-hosting/public/console/roster-tools.js`, `audit-log.js`; on `console/institution/` for IT and on the operator desk for staff); the account page redeems a claim code while no licence is attached (`firebase-hosting/public/console/account/claim.js`). No per-device check-in or app version, no bulk release, no role management, no API tokens | TD-255, FI-19, FI-20 |
| C-emm | Part C "EMM" | PARTIAL | Keys `accountEmailDomain`, `cloudBackupAllowed`, `backupOnUnmeteredOnly` (`enrollmentInvite` removed 2026-10-18, [ADR-024](../adr/ADR-024-account-anchored-entitlements.md)) read through `RestrictionsManager` with the change broadcast (`data/policy/ManagedConfigReader.kt:48-70`). No `tenantId` / `ssoProviderId` / `updateMode`; untested in an EMM | TD-257 |
| C-update-control | Part C "Update control for IT" | PARTIAL | Minimum and recommended versions per app, not per institution; supported versions current plus two ([SUPPORT_POLICY.md](SUPPORT_POLICY.md)). No per-institution minimum or defer flag; no N-2 CI | FI-22, TD-251 |
| D1 | Part D 1 "Versioning" | PASS | SemVer `versionName` and monotonic `versionCode` ([RELEASING.md](RELEASING.md), Versioning). Each backend deploy labels the service and the new revision `release-train`, from the `release_train` input or the newest `v*` tag (`.github/workflows/deploy-backend.yml:39-47`, `:144-164`, `:321-322`); first applied at the next deploy | — |
| D2 | Part D 2 "Firestore schema evolution" | PASS | Expand/contract runbook, `SCHEMA_VERSION` stamped by the backend and `migrate_schema.py` ([FIRESTORE_SCHEMA_RUNBOOK.md](../backend/FIRESTORE_SCHEMA_RUNBOOK.md); `backend/app/repo/_base.py:40`), on every seat write too (`backend/app/repo/claims.py:253`; `backend/tests/test_seat_schema_version.py`) | — |
| D3 | Part D 3 "Rules and functions deployment" | PARTIAL | Rules tested in CI; staging deploy with candidate smoke, then promote, auto-rollback on failure (`.github/workflows/deploy-backend.yml:388-587`); previous image kept as `rollback-prev`. Production deploys wait for the owner's approval (2026-10-22); staging shares production data; no end-to-end suite against staging. Since readiness TE6-004, a post-promote check of `/readyz` and an app route can trigger the scripted rollback, with a staging-only `rollback_drill` input (`.github/workflows/deploy-backend.yml:59`, `:476-500`, `:575`); not yet run | TD-248 |
| D4 | Part D 4 "Forced update and gating" | PARTIAL | `/v1/config` carries the minimum; below it the app keeps read-only access to local results (`data/policy/FeatureGates.kt:34-40`; Home's gate `ui/home/HomeActivity.kt:369-379`). Update opens the Play listing for a Play install and the GitHub Releases page for a sideloaded build (`ui/limit/UpdateSource.kt:28-36`). No in-app update flow | TD-233, TD-219 |
| D5 | Part D 5 "Rollback" | PARTIAL | Levers written ([RELEASING.md](RELEASING.md), "Rollback levers"): kill switches (refused server-side since 2026-10-18, except `share`), minimum version, grace, backend revision, hotfix. Kill switches and the minimum version drilled on staging 2026-10-22; grace, revision rollback and hotfix not | TD-220 |
| D6 | Part D 6 "Support/SLA (B2B)" | PARTIAL | [SUPPORT_POLICY.md](SUPPORT_POLICY.md): severities, supported versions, grace tied to outages; targets, windows and notice TBD; no status page | TD-258 |
| E-rules | Part E "Security rules tests" | PASS | Emulator tests per collection: signed out, owner, other user, admin-looking claims; client writes to licence paths denied; rule coverage uploaded (`.github/workflows/ci.yml:832-872`). "Same-tenant other role" has no counterpart: no client role reaches Firestore | — |
| E-load | Part E "Load testing" | GAP | None | TD-249, TD-248 |
| E-pentest | Part E "Penetration test scope" | EXTERNAL | [PENTEST_SCOPE.md](PENTEST_SCOPE.md) | TD-240 |
| E-tamper | Part E "License bypass and tamper tests" | PARTIAL | An edited payload fails verification (`backend/tests/test_entitlement_token.py:126-191`; `EntitlementVerifierTest`) and is then ignored, so it gains nothing over the cached config; a copied sealed record fails its HMAC on another device and reads as tampered; seat over-allocation refused transactionally (`backend/app/repo/claims.py:195`); expected results per case in [PENTEST_SCOPE.md](PENTEST_SCOPE.md). A claim code is tried on the activation bucket shared with the legacy route (`backend/tests/test_claim_code.py:259`). Repackaging, Frida and rooted-device cases need the pentest; a repackaged build is expected to share without a licence (TD-274) | TD-240, TD-234, TD-235, TD-274 |
| E-offline | Part E "Offline and grace-period tests" | PARTIAL | `EntitlementClockTest` (`app/src/test/java/com/sempermechanics/semper/data/account/entitlement/EntitlementClockTest.kt`): 1 h, grace − 1 day and + 1 day, clock forward then back, back past the last check-in, time zone or DST, reboot, expiry while offline. Revocation while offline then reconnect is not a test | TD-219, TD-238 |
| E-chaos | Part E "Chaos/failure tests" | GAP | Unit-level dependency outages only (`backend/tests/test_health.py`, `backend/tests/test_verify_degradation.py`) | TD-250, TD-248 |
| E-gonogo | Part E "Go/no-go review template" | PASS | Adopted as the nine-step review in "Sign-off" below | — |

## Risk acceptance

Any remaining PARTIAL/UNKNOWN item at launch needs an explicit owner, expiry
date, and compensating control written below:

| Item | Owner | Accept until | Compensating control |
|------|-------|--------------|----------------------|
| TE1-001: release builds compile no certificate pin, and the release gate only warns (TD-318, TD-218) | Admin to sign | Admin to sign | Admin to sign |
| TE5-003: the Grievance Officer and the operator's identity are bracketed placeholders on the published Privacy Policy (TD-36) | Admin to sign | Admin to sign | Admin to sign |
| TE5-004: the Privacy Policy omits Firebase Analytics, App Check / Play Integrity and IP data (TD-317) | Admin to sign | Admin to sign | Admin to sign |
| TE6-001: no alert reaches anyone; monitoring is not applied and no notification channel exists (TD-217) | Admin to sign | Admin to sign | Admin to sign |
| TE6-011: GCP posture, 17 FAIL on 2026-10-24 and again on 2026-10-28 (TD-221–TD-225) | Admin to sign | Admin to sign | Admin to sign |

## Sign-off

The go/no-go review, adapted from the framework's Part E template (SRE Production
Readiness Review and Launch Coordination Checklist). Each step names where its
evidence lives; the review records the answer, the exceptions and their risk
owners (in "Risk acceptance" above), then the decision.

1. **Service, dependencies, owners.** Cloud Run `semper-api`, API Gateway, Firestore,
   Google Drive, Firebase Auth and App Check, Cloud Tasks, Resend
   ([CLOUD_ARCHITECTURE_GCP.md](../backend/CLOUD_ARCHITECTURE_GCP.md),
   [ENVIRONMENTS.md](ENVIRONMENTS.md)); Play Integrity once on Play (TD-229).
2. **Checklist A1–A8.** "Framework cross-check (2026-10-18)" above, each row with
   evidence and an owner; `python scripts/check_framework_coverage.py` passes.
3. **SLOs, alerts, on-call, runbooks.** [ops/monitoring/README.md](../../ops/monitoring/README.md)
   (applied: TD-217); [INCIDENT_RESPONSE.md](INCIDENT_RESPONSE.md): activation
   outage, mass revocation, signing-key compromise, Firestore restore, App Check
   rollback. On-call rota: name it here.
4. **Capacity.** Expected institutions, devices and peak activations; load test
   results (TD-249, blocked by TD-248).
5. **Security.** Pentest findings, every critical and high closed or accepted
   (TD-240; [MASVS_ASSESSMENT.md](MASVS_ASSESSMENT.md)).
6. **Data.** Last restore drill (above), [DATA_MAP.md](../legal/DATA_MAP.md), DPA and
   DPDP status (TD-241, TD-36).
7. **Release.** Rollout percentages, halt criteria and rollback levers
   ([RELEASING.md](RELEASING.md)); the levers drilled (TD-220).
8. **Customer readiness.** Admin guide ([ADMIN_API.md](../backend/ADMIN_API.md),
   [OPERATING_MANUAL.md](../OPERATING_MANUAL.md) Appendix D), EMM guide (TD-257),
   support policy ([SUPPORT_POLICY.md](SUPPORT_POLICY.md); values TD-258).
9. **Decision:** Go / Go with conditions (each condition dated) / No-go, signed by
   the engineering lead, security and product below.

| Role | Name | Date | Result (Go / Go with conditions / No-go) |
|------|------|------|--------|
| Engineering | | | |
| Security / ops | | | |
| Product / legal | | | |

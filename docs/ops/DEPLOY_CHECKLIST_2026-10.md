# Deploy checklist: readiness change (2026-10)

What has to happen, in order, to put the readiness change live: #448, #449, #454
and #455, merged to `main` on 2026-10-19 (`main` @ `2de605c`). Nothing in it is
deployed yet. Tick the boxes in a copy, or in the PR that records the deploy.

Everything deploys **from semperdic-app only**. Never deploy Hosting from
material_testing: any Hosting deploy replaces the whole site.

## 0. Prerequisites (once)

- [ ] **Google credentials** for whoever runs steps 1, 4 and 5: Firestore index
      admin and Firebase Hosting admin on `indicvision-dic-app`, plus
      `roles/monitoring.editor` and `roles/logging.configWriter`.
- [ ] **Revocation (TD-214)**, optional for the first deploy. Grant
      `gcloud projects add-iam-policy-binding indicvision-dic-app-auth --member=serviceAccount:indic-api@indicvision-dic-app.iam.gserviceaccount.com --role=roles/firebaseauth.admin`,
      then set the repo variable `CHECK_REVOKED_ON_STEP_UP=true`. Not before the
      grant: without it the check fails closed and refuses every step-up.
- [ ] **Offline entitlement (TD-215)**, optional for the first deploy. Follow
      [BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) A8 in its order:
      create the P-256 key in Secret Manager; grant the runtime SA
      `secretAccessor` on it and the deploy SA `secretmanager.viewer`; only then
      set `ENTITLEMENT_SIGNING_SECRET`, `ENTITLEMENT_KID` and
      `SEMPER_ENTITLEMENT_PUBKEYS`. The deploy checks the runtime SA's grant
      (`scripts/check_secret_access.sh`) and stops with the command to run when it
      is missing. Without a key the backend behaves as today and issues no token.

## 1. Firestore indexes (TD-216, TD-271)

- [ ] `PROJECT=indicvision-dic-app scripts/deploy-firestore.sh indexes`. Never
      with `--force`: it deletes any TTL policy the file omits.
- [ ] Wait for READY: the composite `audit_logs (licenseId ASC, ts DESC)`, and
      the `seats.uid` / `deleted_seats.uid` collection-group overrides
      (`gcloud firestore indexes fields describe uid --collection-group=seats`).

## 2. Backend: staging, then production

Actions → `deploy-backend.yml` (`.github/workflows/deploy-backend.yml`).

- [ ] **Staging:** `environment=staging`, `project=indicvision-dic-app`,
      `region=asia-south1`, `gateway_mode=dry-run`. Read the gateway diff: it
      adds `/v1/me/claim`, `/v1/admin/licenses/search` and the roster and audit
      paths.
- [ ] **Staging smoke:** `/readyz` answers 200; an anonymous `POST /v1/me/claim`
      answers **401, not 404**; `GET /v1/config` carries `killSwitches`,
      `minSupportedVersionCode` and, if the key was set, `entitlement`.
- [ ] **Staging gateway:** run again with `gateway_mode=apply`.
- [ ] **Production:** `environment=production`, first `gateway_mode=dry-run`,
      then `apply`. The candidate revision is smoke-tested on `/readyz` before
      traffic moves, and rolls back on failure.
- [ ] For the first deploy, leave unset (or 0): `MIN_SUPPORTED_VERSION_CODE`,
      `RECOMMENDED_VERSION_CODE`, their `_MATERIAL_TESTING` pair, and
      `KILL_SWITCHES`.

## 3. Gateway checks (after `apply`)

- [ ] An anonymous call to each new path answers 401: `/v1/me/claim`,
      `/v1/admin/licenses/search`,
      `/v1/institutions/licenses/{id}/seats/import`, `…/export`, `…/summary`,
      and `…/audit`.
- [ ] `GET …/audit` answers 200 for a licence administrator.

## 4. Hosting: consoles, account page, aligned Terms

- [ ] From a **clean `main` checkout**:
      `API_BASE_URL=<INDIC_API_BASE_URL repo variable> ./scripts/deploy-console.sh`.
      Never a bare `firebase deploy`.
- [ ] The script runs `scripts/check_live_console.py`; it must pass (no `__`
      placeholder left live).
- [ ] By hand: `/account` shows the licence facts and, for a demo account, the
      "Redeem a claim code" card; `/terms` shows 6.6 "No purchases in the App";
      the operator desk's search works.

## 5. Monitoring (TD-217)

- [ ] Dry run:
      `scripts/apply-monitoring.sh --project indicvision-dic-app --notification-channel <id> --staging-service semper-api-staging`.
- [ ] The same with `--apply`: log metrics, uptime check, SLO and burn-rate
      alerts, four dashboards.
- [ ] `scripts/check_gcp_posture.sh` against both projects, with
      `--backup-bucket <FIRESTORE_BACKUP_BUCKET>`; record the output in
      [PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md).

## 6. After the deploy

- [ ] **Staff and IT phones re-register with a second factor** (ADR-010
      amendment, device trust). A device registered before this backend has no
      `stepUpEligible`, so its signed staff or IT calls answer `403
      mfa_required`. On each such phone: sign out, sign in completing the TOTP
      second factor (that registers the device again, stamped). Holders are not
      affected; the browser consoles are not affected.

- [ ] 24 h log watch: `feature_not_licensed` only from restore and bundle;
      `app_check_required` = 0; the rate of `session_quota_exceeded` and
      `device_quota_exceeded`; `feature_paused` only if a kill switch was set;
      5xx rate; Cloud Tasks backlog.
- [ ] One erasure of a test account: its `ACCOUNT_DELETE` row says
      `seatScanComplete: true` (TD-271).
- [ ] Move the merged-and-deployed entries from [CONTEXT.md](../../CONTEXT.md)
      "Current state" into [CHANGELOG.md](CHANGELOG.md), in both repos.

## 7. App releases (separate from the backend)

- [ ] Release builds from `main`: Semper, and Material Testing after its sync
      (material_testing#139). Both now require **Android 11 (minSdk 30)**.
      Steps: [RELEASING.md](RELEASING.md).
- [ ] Set `SEMPER_ENTITLEMENT_PUBKEYS` before the release build if offline tokens
      should be enforced; `SEMPER_API_CERT_PINS` is optional (TD-218).
- [ ] Device smoke (TD-219): installs on Android 11 and is refused on Android 10;
      Refresh licence; a demo account sees locked features; the device-limit
      screen.
- [ ] Only after those builds are out, raise `MIN_SUPPORTED_VERSION_CODE` if
      older builds must update; drill it on staging first (TD-220).

## Still external (not deploy steps)

Counsel's sign-off on the Terms (TD-277); Play Console: signing SHA, Data safety,
pre-launch report (TD-229 to TD-232); App Check enforce (TD-226); liens, budgets
and API-key restrictions (TD-221 to TD-225); the penetration test (TD-240). All
are rows in [TECH_DEBT.md](TECH_DEBT.md).

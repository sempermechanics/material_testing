# Deploy checklist: readiness change (2026-10)

What has to happen, in order, to put the readiness change live: #448, #449, #454
and #455, merged to `main` on 2026-10-19 (`main` @ `2de605c`). Deployed 2026-10-22 from
`b940cc29`, with #457 and test accounts (#460); the boxes below are ticked for that
deploy, and what it did is in [CHANGELOG.md](CHANGELOG.md). §5 and most of §6 are still open.

Everything deploys **from semperdic-app only**. Never deploy Hosting from
material_testing: any Hosting deploy replaces the whole site.

## 0. Prerequisites (once)

- [x] **Google credentials** for whoever runs steps 1, 4 and 5: Firestore index
      admin and Firebase Hosting admin on `indicvision-dic-app`, plus
      `roles/monitoring.editor` and `roles/logging.configWriter`.
- [ ] **Revocation (TD-214)**, optional for the first deploy. Grant
      `gcloud projects add-iam-policy-binding indicvision-dic-app-auth --member=serviceAccount:indic-api@indicvision-dic-app.iam.gserviceaccount.com --role=roles/firebaseauth.admin`,
      then set the repo variable `CHECK_REVOKED_ON_STEP_UP=true`. Not before the
      grant: without it the check fails closed and refuses every step-up.
      (2026-10-22: the grant is in place, made for test accounts; the variable is not set.)
- [ ] **Offline entitlement (TD-215)**, optional for the first deploy. Follow
      [BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) A8 in its order:
      create the P-256 key in Secret Manager; grant the runtime SA
      `secretAccessor` on it and the deploy SA `secretmanager.viewer`; only then
      set `ENTITLEMENT_SIGNING_SECRET`, `ENTITLEMENT_KID` and
      `SEMPER_ENTITLEMENT_PUBKEYS`. The deploy checks the runtime SA's grant
      (`scripts/check_secret_access.sh`) and stops with the command to run when it
      is missing. Without a key the backend behaves as today and issues no token.
- [ ] **New-device mail (TD-295, TD-309)**, optional, and only once #457 is merged.
      Verify a sender in Resend (for example `Semper security <security@yourdomain.com>`),
      add `NOTIFY_SECURITY_FROM` to `deploy-backend.yml`'s `env_vars` block (it is not
      passed today, TD-309), then set the repo variable. Unset, that mail is off and the
      access-request mail is unaffected ([BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md)).

- [x] **Test accounts ([ADR-027](../adr/ADR-027-test-accounts.md))**, optional, and
      only once the branch carrying them is merged. Creating, resetting and ending
      one calls Firebase Auth admin, so it needs the same
      `roles/firebaseauth.admin` grant on `indicvision-dic-app-auth` as the
      revocation item above (without it each call answers 503 `internal_error`).
      Then set the repo variable `TEST_ACCOUNT_DOMAINS` to the domain(s) staff may
      use, space-separated, for example `qa.sempermechanics.com`: a domain the
      company controls and no customer uses. `deploy-backend.yml` already passes
      it in `env_vars` (`TEST_ACCOUNT_DOMAINS=${{ vars.TEST_ACCOUNT_DOMAINS }}`);
      unset, it is empty and every create answers 403 `test_accounts_disabled`.

## 1. Firestore indexes (TD-216, TD-271)

- [x] `PROJECT=indicvision-dic-app scripts/deploy-firestore.sh indexes`. Never
      with `--force`: it deletes any TTL policy the file omits. (2026-10-22: it stopped
      on a 409 before creating anything, TD-316; the missing indexes went in by gcloud
      and the REST API, as the [CHANGELOG](CHANGELOG.md) entry shows.)
- [x] Wait for READY: the composite `audit_logs (licenseId ASC, ts DESC)`, and
      the `seats.uid` / `deleted_seats.uid` collection-group overrides
      (`gcloud firestore indexes fields describe uid --collection-group=seats`).

## 2. Backend: staging, then production

Actions → `deploy-backend.yml` (`.github/workflows/deploy-backend.yml`).

- [x] **Staging:** `environment=staging`, `project=indicvision-dic-app`,
      `region=asia-south1`, `gateway_mode=dry-run`. Read the gateway diff: it
      adds `/v1/me/claim`, `/v1/admin/licenses/search` and the roster and audit
      paths.
- [ ] **Staging smoke:** `/readyz` answers 200; an anonymous `POST /v1/me/claim`
      answers **401, not 404**; `GET /v1/config` carries `killSwitches`,
      `minSupportedVersionCode` and, if the key was set, `entitlement`. (2026-10-22: the
      first two held, the claim after the gateway apply; `/v1/config` needs a signed-in
      caller and was not read.)
- [x] **Staging gateway:** run again with `gateway_mode=apply`.
- [x] **Production:** `environment=production`, first `gateway_mode=dry-run`,
      then `apply`. The candidate revision is smoke-tested on `/readyz` before
      traffic moves, and rolls back on failure.
- [x] **Licence fan-out task (TD-294, [ADR-026](../adr/ADR-026-licence-fanout-marker.md))**,
      once #457 is merged. A staff revoke or terms edit schedules a Cloud Task on
      `TASKS_QUEUE`, so check that the environment's `TASKS_QUEUE` and
      `TASKS_TARGET_BASE_URL` are set (provisioning already uses them); unset, no task
      is scheduled and the action relies on its inline run alone. The callback
      `POST /v1/tasks/license-fanout` is reached by Cloud Tasks straight on Cloud Run
      with the invoker SA's OIDC token, like `/v1/tasks/provision-session`, and is
      kept off the gateway (`backend/tests/test_gateway_parity.py:45`): the gateway
      diff must not add it. On staging, a revoke of a test licence leaves a
      `license-fanout-<id>` task that runs about 6 minutes later and finds nothing
      pending.
- [x] **Test-account expiry job (ADR-027)**, with the test-accounts item in §0. A
      Cloud Scheduler job per environment calls the backend daily; like the Cloud
      Tasks callbacks it goes straight to Cloud Run with the invoker SA's OIDC token,
      and `POST /v1/tasks/test-account-expiry` is kept off the gateway
      (`backend/tests/test_gateway_parity.py`): the gateway diff must not add it.
      With the environment's `TASKS_TARGET_BASE_URL` (the Cloud Run URL) and
      `TASKS_INVOKER_SA`, and `cloudscheduler.googleapis.com` enabled:
      `gcloud scheduler jobs create http semper-test-account-expiry --project indicvision-dic-app --location asia-south1 --schedule "17 3 * * *" --time-zone Etc/UTC --http-method POST --uri "$TASKS_TARGET_BASE_URL/v1/tasks/test-account-expiry" --oidc-service-account-email "$TASKS_INVOKER_SA" --oidc-token-audience "$TASKS_TARGET_BASE_URL"`
      (staging: its own job name and URL). Whoever creates it needs
      `iam.serviceAccounts.actAs` on the invoker SA. Check with
      `gcloud scheduler jobs run semper-test-account-expiry --location asia-south1`:
      the request log shows 200 and the answer `{"expiredTestAccounts": 0, ...}`.
      Without the job, accounts past their date are not erased; their licence has
      still lapsed, and the staff list shows them `expired`.
- [x] For the first deploy, leave unset (or 0): `MIN_SUPPORTED_VERSION_CODE`,
      `RECOMMENDED_VERSION_CODE`, their `_MATERIAL_TESTING` pair, and
      `KILL_SWITCHES`.

## 3. Gateway checks (after `apply`)

- [x] An anonymous call to each new path answers 401: `/v1/me/claim`,
      `/v1/admin/licenses/search`,
      `/v1/institutions/licenses/{id}/seats/import`, `…/export`, `…/summary`,
      and `…/audit`. (2026-10-22: all of them on staging; on production the GETs
      `licenses/search`, `test-accounts` and `…/summary`.)
- [ ] `GET …/audit` answers 200 for a licence administrator.
- [x] With test accounts: an anonymous `GET /v1/admin/test-accounts` and
      `POST /v1/admin/test-accounts` answer 401, and
      `POST /v1/tasks/test-account-expiry` through the gateway answers 404. (Staging;
      production's `GET` answers 401.)

## 4. Hosting: consoles, account page, aligned Terms

- [x] From a **clean `main` checkout**:
      `API_BASE_URL=<INDIC_API_BASE_URL repo variable> ./scripts/deploy-console.sh`.
      Never a bare `firebase deploy`. From Windows PowerShell, set the variable there
      (`$env:API_BASE_URL = gh variable get INDIC_API_BASE_URL`) and run the script
      through Git Bash; PowerShell 5.1 drops quotes nested inside `bash -c "…"`.
- [x] The script runs `scripts/check_live_console.py`; it must pass (no `__`
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
      5xx rate; Cloud Tasks backlog. Once #457 is in: `license_fanout_enqueue_failed`
      = 0, and each `license_fanout_finished_late` is a revoke or edit whose call died
      part-way and the task finished (TD-294).
- [ ] One erasure of a test account: its `ACCOUNT_DELETE` row says
      `seatScanComplete: true` (TD-271).
- [ ] With test accounts, on staging: create a QC account (`days: 1`), sign it in
      on two phones or emulators (both licensed, no new-device mail), check its
      upload lands under `Research Storage/_test/{uid}`, then end it from the
      console; the audit trail has `test_account_created` and `test_account_ended`,
      and its Firebase user is gone.
- [ ] **Audit trail lock (TD-237)**, once #457 is deployed (it writes the `audit_row`
      lines) and the legal/privacy owners have set the retention period: create the
      `audit-trail` log bucket at that retention, the sink on
      `jsonPayload.event="audit_row"`, then lock the bucket (irreversible), by the
      commands in [FIRESTORE_DATA_PROTECTION.md](../backend/FIRESTORE_DATA_PROTECTION.md),
      "The audit trail is append-only". Check: a staff licence edit's row reaches the
      bucket, and lowering its retention is refused. Then the Firestore TTL the
      retention asks for. Needs `roles/logging.configWriter` (§0).
- [ ] **Audit rows before 2026-10-18 (TD-246)**, only if the owner chooses the
      backfill: `backend/scripts/stamp_audit_licence_ids.py` dry-run, then `--apply
      --decision "…"`, after the backend that stamps new rows is live.
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

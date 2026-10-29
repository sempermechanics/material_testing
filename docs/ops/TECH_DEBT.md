# Tech debt

What is **owed** or **deliberately deferred**, and nothing else. A row is removed
when it is fixed or closed. Its history stays in git
(`git log -S'TD-NN' -- docs/ops/TECH_DEBT.md`; before 2026-10-27, search the row's old
number from [td-renumber-2026-10-27.csv](td-renumber-2026-10-27.csv)), and what shipped is in
[CHANGELOG.md](CHANGELOG.md). Forward-looking ideas are in
[FUTURE_IMPROVEMENTS.md](FUTURE_IMPROVEMENTS.md).

**Re-verified against the code on 2026-10-08** at `main` @ `8ae203ce`. Evidence is
`path:line`, from `app/src/main/java/com/sempermechanics/semper/` unless it starts
at the repo root. On that date, the 87 rows that were fixed or closed were removed
(under their old numbers; see below).

TD numbers are this repo's own ([ADR-032](../adr/ADR-032-own-td-numbers.md)). They
restarted at TD-1 on 2026-10-27: the open rows took TD-1–TD-60 in their old order, and
every other number still cited here (a closed row, or a row only semperdic-app held)
took TD-61–TD-275, also in old order. Until then the numbers were shared with
semperdic-app. [td-renumber-2026-10-27.csv](td-renumber-2026-10-27.csv) maps each old
number to its new one, for git history, old PRs and semperdic-app's register, and
`scripts/td_numbers.py translate` rewrites an upstream diff before a sync applies it
([FORK_SYNC.md](FORK_SYNC.md#td-and-adr-numbers)). **The next free is TD-277**; take it
without checking semperdic-app's register.

**Closed 2026-10-27 on `claude/example-followups`** (#156), and removed from the register.

- **TD-57**, numbered TD-349 on that branch (Credits & licences named only the example data and the engine's DICe, OpenCV
  and Eigen licences). The screen is now one list of every part of the app with its licence:
  the example sets, the engine (OpenCV, Eigen and the codecs OpenCV links into `libsemper`)
  and every library in the APK, from the AboutLibraries plugin run offline
  (`res/raw/aboutlibraries.json`, `app/build.gradle.kts`). The full texts, and the notices
  Google bundles inside Play services and Firebase (`CollectPlayServicesNotices` writes them
  to `app/build/licences/<variant>/`), are on the website at sempermechanics.com/licences/,
  built by semper-website's `tools/build-licences.py`. The app keeps what has to travel with
  it: each set's credit, DICe's `LICENSE.DICe`, and the short BSD, MIT, IJG, zlib, libpng
  and libtiff notices in `assets/licences/` (`ui/examples/LicencePage.kt`).
  `LicencesActivityTest` fails when a dependency arrives under a licence with no notice there.

**Closed 2026-10-27 by #154** (`claude/example-s22-fixes`), and removed from the register.

- **TD-275** (the example sets' "an earlier run of this app" values came from engine 0.2.x).
  Steel and PMMA were re-run through Try it on engine 0.6.1 on a Galaxy S22+ (Android 16,
  debug build of `5b3b3948`): steel E 148.9 GPa over frames 1–26, R² 0.9952, 35 of 40
  photos on the curve, the same as the Pixel 6's ΔL / L₀ run; PMMA E from the graph
  2.00 GPa, average 2.10 GPa. Both `example.json` app rows and both READMEs now carry
  these and name engine 0.6.1 ([REAL_WORLD_VALIDATION.md](../app/REAL_WORLD_VALIDATION.md#try-it-on-a-galaxy-s22-2026-10-27)).

**Closed 2026-10-26 by the sync of semperdic-app `25334af1`** (its #471–#486), and removed
from the register.

- **TD-199** (`audit_logs` append-only in code but not against IAM, with no retention).
  Closed in semperdic-app on 2026-10-23 on the shared project: new audit rows carry
  `expireAt` for one-year retention (its #467), the `audit_logs.expireAt` TTL policy was
  deployed with `scripts/deploy-firestore.sh indexes`, and the `audit-trail` log bucket
  (`asia-south1`, 365 days) and its sink on `jsonPayload.event="audit_row"` were created
  and the bucket locked; lowering its retention answered `FAILED_PRECONDITION` (its
  CHANGELOG, 2026-10-23, quoted in [CHANGELOG.md](CHANGELOG.md)). This repo deploys none of it;
  its backend copy has stamped `expireAt` since the 2026-10-22 sync of `968637cb`
  (`backend/app/repo/audit_log.py`).

**Closed 2026-10-22 on `chore/engine-0.6.1`** (semperdic-app#470, picked with
`cherry-pick -x`), and removed from the register.

- **TD-236** (the 2026-10-19 review's engine fixes were not in the app). The pin moved to
  engine 0.6.0 in #142 and to 0.6.1 (`f072da0`) here: 0.5.0's fixes, 0.6.0's AKAZE seeding
  and 0.6.1's two fixes (an anchor the median test cannot check is not kept; the OpenCV
  modules the engine links are always built, so `app/build.gradle.kts` no longer passes the
  list). The lab JVM tests use recorded arrays and pass unchanged. On a Pixel_8 AVD (API 37,
  x86_64) the instrumented suite passed 71/71 at 0.6.0, with `LabWorkflowDeviceTest` and
  `WizardDraftRestoreTest`; the real steel and PMMA frames through this app's JNI matched the
  host solve, and on 0.6.1's engine the PMMA deflection RMSE is 0.0080 mm and E from the
  graph 2.002 GPa, as on 0.4.2 (steel E 148.9 GPa, unchanged). The Pixel 6 numbers stay TD-8.

**Closed 2026-10-22 on `chore/engine-0.6.1`**, and removed from the register.

- **TD-236** (the 2026-10-19 review's engine fixes were not in the app). The pin moved to
  engine 0.6.0 in #462 and to 0.6.1 here: 0.5.0's fixes E1–E10, 0.6.0's AKAZE seeding and
  0.6.1's two fixes (an anchor the median test cannot check is not kept; the OpenCV modules
  the engine links are always built, so `app/build.gradle.kts` no longer passes the list).
  `-5` reads as no seed (`EngineFailure.ENGINE_ERROR_SEED`, `RunStop.SeedFailed`); the app
  passes 23 metrics floats and leaves slot 23 unread. The `.dat` and GIF oracles are JVM
  fixtures that call no engine and pass unchanged. The checks it asked for:
  `Perf.SubsetSolveThroughput` (a host test) median 4751 solves/s over 15 runs on v0.6.0,
  above the 4557 floor; `HotPathMicroBenchmark` ran in CI's Benchmarks job on #462; the
  instrumented suite passed 60/60 on a Pixel_8 AVD (API 37, x86_64), with the real steel and
  PMMA frames through the JNI matching the host solve. The Pixel 6 numbers stay TD-8.

**Closed 2026-10-18 on `claude/readiness-followups-jhfhj9`** (at `75bac5f`, on top of
#448; neither merged nor deployed), and removed from the register. The entry moves to
[CHANGELOG.md](CHANGELOG.md) once it is merged and deployed. Cites are at `75bac5f`.
The Android rows are closed in code; their tests first run in the branch's first
Tier 1 (TD-14).

- **TD-200** (Auth Admin calls had no budget). `rate_limit.auth_admin_bucket`, one
  shared key per instance, `AUTH_ADMIN_CALLS_PER_SECOND` 10 and `AUTH_ADMIN_BURST` 20
  (`backend/app/config.py:259-260`, `backend/app/rate_limit.py:138-147`). Over it,
  `revoke_sessions` and `delete_auth_user` make no call and answer False
  (`backend/app/google_auth.py:96-101`, `:110`, `:125`); the step-up revocation check
  answers 503 `auth_check_busy` with `Retry-After`, logged `auth_admin_rate_limited`
  (`backend/app/deps.py:554-562`); a quota error from the Auth project is still a 401
  logged `revocation_check_failed` (`:571-576`). Tests `backend/tests/test_revocation.py:266-356`.
  The console has no sentence for the 503: TD-217.
- **TD-201** (addresses in audit rows). `audit.address_tag` / `address_tags`
  (`observability.short_hash`, `backend/app/audit.py:35-47`): an individual mint records
  `emailLockHash`; an institution mint, a convert and an `adminEmails` edit record
  `adminEmailHashes` and `adminEmailCount` (`backend/app/routers/admin.py:27-40`, `:234`,
  `:258`, `:342`, `:434`). Tests `backend/tests/test_audit_addresses.py`. The
  free-text `note` on `ADMIN_LICENSE_EXTEND` followed as TD-216, closed 2026-10-19.
- **TD-202** (erasure leftovers). `delete_all_user_data` deletes every `auth_links` row
  whose `uid` is the account and blanks `email` on every seat the account held, live or
  in a deleted licence's hold, revoking a live one first (`backend/app/repo/account.py:156-244`);
  the `ACCOUNT_DELETE` detail gains `authLinks`, `seatsBlanked` and `seatScanComplete`
  (`:241-243`, recorded at `backend/app/routers/account.py:363`). Tests
  `backend/tests/test_erasure_personal_data.py`. The seat search needs a new index
  deployed before the backend: TD-47.
- **TD-203** (an address in the search URL). `POST /v1/admin/licenses/search` takes
  `{q, limit, page_token, include_demo, include_revoked}` (`AdminLicenseSearch`,
  `backend/app/models.py:446-460`; an extra field is 422) at `admin`, on `admin_bucket`,
  and answers as the GET does (`backend/app/routers/admin.py:166-174`); its gateway path
  is declared before `/{licenseId}` (`backend/gateway/openapi.yaml:626-644`, pinned by
  `backend/tests/test_gateway_parity.py`); the desk searches with it
  (`firebase-hosting/public/console/operator/licences.js:157`). The GET still answers
  `q` for one release and logs `deprecated_query_search` without the value
  (`admin.py:159-161`). Tests `backend/tests/test_licence_search_post.py`. Retiring the
  GET's `q`: TD-48.
- **TD-204** (licensing events without uid). The handler passes `uid` to `activation`
  and `checkout` (`backend/app/routers/licenses.py:19-31`, `:62-64`, `:98-100`), and
  `_devlock_drop` passes the user's (`backend/app/repo/devlock.py:295`). Tests
  `backend/tests/test_release_gate_and_events.py:161`, `:175`, `:197`.
- **TD-205** (seats without `schemaVersion`). Every seat write stamps it: claim
  (`backend/app/repo/claims.py:224`, `:253`), roster (`repo/seats.py:215`, `:245`,
  `:297`, `:359`), lease (`repo/leases.py:99`, `:172`, `:216`), licence edits
  (`repo/license_admin.py:152`, `:335`), device lock (`repo/devlock.py:150`), restore
  (`repo/deletion.py:204`) and erasure (`repo/account.py:202`). Tests
  `backend/tests/test_seat_schema_version.py`.
- **TD-207** (`setHostedDomainFilter` never compiled). Compiled and tested in CI run
  39158239853 on `ae4c24a` (#448's head): Tier 1, Tier 3, Tier 4, Tier 5 and the rules
  job passed; material_testing#137 passed Tier 1 and Tier 5 in its run 39156925646. The
  EMM field test is TD-44.
- **TD-208** (no release train on backend deploys). `deploy-backend.yml` input
  `release_train` (`.github/workflows/deploy-backend.yml:39-47`); empty, the newest `v*`
  tag reachable from the deployed commit (`git describe`, with `fetch-depth: 0` at
  `:93`), else `untagged`; lowercased, other characters to `-`, at most 63
  (`:136-156`). Set as the Cloud Run label `release-train` on the service and the new
  revision (`:300-301`); a revision without it after promote is a warning, not a
  rollback (`:425-442`). Proven by the first deploy from the branch.
- **TD-209** (screens showed the server's cap). `LicenseEntitlements.shownAnalysisCap`
  (`data/account/LicenseEntitlements.kt:224`), through `EntitlementPolicy.shownCap`
  (`data/account/entitlement/EntitlementPolicy.kt:116`), is what Home's quota card and
  the session-limit screen show (`ui/home/HomeQuotaCard.kt:73`;
  `ui/limit/SessionLimitActivity.kt:63`, `:74`). Tests `EntitlementPolicyTest`,
  `LicenseEntitlementsTest`.
- **TD-210** (`refreshAfter` unread). A unique one-off `license-config-now`
  (`WorkTags.LICENSE_CONFIG_NOW_NAME`, `data/cloud/WorkTags.kt:71`): `/v1/config` only,
  expedited, `KEEP`, retried with backoff up to `EntitlementRefresh.MAX_ATTEMPTS` (6,
  about 15 minutes; `data/account/entitlement/EntitlementRefresh.kt:48`, `:89`)
  (`LicenseConfigWorker.refreshNow`, `data/LicenseConfigWorker.kt:106-117`). Queued at
  launch and on each return to the foreground when the token is past `refreshAfter` or
  lapsed (`refreshNowIfDue`, `:121-126`, through the `AppForeground` listener
  `foregroundCheck`, `:134-139`, installed at `SemperApp.kt:56-61`; it was
  `installForegroundCheck` until #457 merged the app's three foreground counts into
  `data/AppForeground.kt`).
  Tests `EntitlementRefreshTest`, `LicenseConfigWorkerTest`.
- **TD-211** (non-malicious events read as tampering). `SealedRecordStore`
  (`data/account/entitlement/SealedRecordStore.kt`) orders the writes, and
  `EntitlementPolicy.reading` (`EntitlementPolicy.kt:137-144`) reads them: a first seal
  marks itself durably, makes the key, then writes the record and clears the mark in one
  `commit()`; a clear deletes the key before the record. A record with no key (a lost
  Keystore key, a clear cut short) and a key with the mark but no record (a crash
  mid-seal) read as no token and are tidied; only a key with neither record nor mark, or
  a MAC that does not match, reads as tampered. Every write is `commit()`
  (`EntitlementStore.kt:207-225`). Tests `SealedRecordStoreTest`, `EntitlementPolicyTest`.
  It widens TD-46: deleting the key alone now also reads as no token.
- **TD-212** (floating refresh after a heartbeat). After a checkout or heartbeat, a failed
  `/v1/config` fetch queues the one-off refresh above
  (`SeatLease.refreshConfigAfterCheckout`, `data/account/SeatLease.kt:79-87`; from
  `heartbeatBestEffort`, `:68`, and the seat screen, `ui/limit/SeatRequiredActivity.kt:76`).
  A floating token past `graceUntil` or `exp` now opens the seat screen rather than
  demo's limits (`EntitlementPolicy.seatRequired`, `EntitlementPolicy.kt:126-129`;
  `LicenseEntitlements.isSeatRequiredToStart`, `LicenseEntitlements.kt:133-135`). Tests
  `SeatLeaseTest`, `EntitlementPolicyTest`, `LicenseEntitlementsTest`.
- **TD-213** (exported addresses did not import back). `roster_io.undefused` drops the
  one `'` the export adds before a formula lead (`backend/app/roster_io.py:112-120`,
  used at `:88`). Tests `backend/tests/test_roster_bulk.py:338`, `:546`, `:550`.
- **TD-214** (Update opened Play for a sideloaded build). `UpdateSource.urls`
  (`ui/limit/UpdateSource.kt:28-36`): a build Play installed opens its listing; any other
  opens `https://github.com/sempermechanics/material_testing/releases`, chosen from
  `getInstallSourceInfo` (`UpdateRequiredActivity.openStore`,
  `ui/limit/UpdateRequiredActivity.kt:49-58`); Home's banner goes through it
  (`ui/home/HomeActivity.kt:360`). The repository is public (GitHub REST API, checked
  2026-10-18), so the page needs no GitHub account. Tests `UpdateSourceTest`,
  `UpdateRequiredActivityTest`. In-app updates stay TD-27.

**Closed 2026-10-18 on the same branch at `2371bec`** by the account-anchored
entitlement change ([ADR-024](../adr/ADR-024-account-anchored-entitlements.md)), and
removed from the register. Cites are at `2371bec`.

- **TD-206** (`enrollmentInvite` parsed and never read; `activateLicense` with no
  caller). Superseded by ADR-024: entitlements attach to the account, and nothing is
  entered in the app. The key leaves `app/src/main/res/xml/app_restrictions.xml`
  (now `accountEmailDomain`, `cloudBackupAllowed`, `backupOnUnmeteredOnly`) and
  `ManagedConfig` (ffc41b7); the key screen the branch had added (c748630, never
  released) leaves with `ui/account/`, `CloudApi`/`SemperApi.activateLicense` and
  `LicenseActivateRequest` (e084f39). A one-time code is redeemed on the web account
  page instead (`POST /v1/me/claim`, `backend/app/routers/licenses.py:195-256`;
  `firebase-hosting/public/console/account/claim.js`). Tests
  `backend/tests/test_claim_code.py`, `firebase-hosting/tests/account-claim.test.mjs`,
  `PolicyTest`. The EMM field test stays TD-44; the legacy route's fixture is TD-52.
- **TD-217** (no console sentence for `auth_check_busy`). `COMMON.auth_check_busy`
  (`firebase-hosting/public/console/messages.js:18`, "The server is busy checking
  sign-ins — wait a few seconds and try again."), added by 581a530, which also routes
  the operator desk's step-up writes (revoke, unbind, approve, withdraw invite) through
  `explain()`; the claim card relies on the same sentence
  (`firebase-hosting/public/console/account/claim.js:24-26`). `api()` still does not
  wait and retry; the tests pin that it neither steps up, signs out nor resends
  (`firebase-hosting/tests/auth.test.mjs:135-140`; `operator.test.mjs:413`;
  `account-claim.test.mjs:239`).

**Closed 2026-10-19 in semperdic-app #454** (after its #448 and #449 merged; here by the sync), and
removed from the register.

- **TD-216** (a licence edit's free-text `note` kept as typed in `audit_logs`).
  `_audited_patch` records `noteChanged` and `noteLength`, never the text
  (`backend/app/routers/admin.py:34-45`). Test
  `test_a_note_edit_records_its_length_not_its_text` (`backend/tests/test_audit_addresses.py`).
- **TD-218** (a phone released by staff still counted its previous holder's analyses
  toward the next holder's demo cap). `forget_device_sessions` drops `deviceId` from
  the account's sessions created from the released phones
  (`backend/app/repo/devices.py:82-107`); the staff release calls it
  (`backend/app/routers/admin.py:118-126`, audited as `sessionsForgotten`), and so does
  a staff or IT licence lock clear (`backend/app/repo/seats.py:224-229`). The holder's own
  clear does not: a second account on the same phone could otherwise shed the first
  one's count. The analyses stay the account's and still count toward its own cap.
  Tests `test_after_a_staff_release_the_next_holder_is_not_held_by_the_last`
  (`backend/tests/test_demo_device_cap.py`), `test_a_staff_clear_stops_the_old_phone_counting_the_holders_analyses`
  and `test_the_holders_own_clear_keeps_the_phone_counting`
  (`backend/tests/test_device_binding_per_app.py`); the query's shape is declared in
  `backend/tests/test_firestore_query_indexes.py`.
- **TD-215** (a secret the runtime SA cannot read stopped every deploy at the candidate's
  start). `scripts/check_secret_access.sh` reads the secret's and then the project's IAM
  policy and fails, naming the `add-iam-policy-binding` to run, when neither grants an
  access role to the runtime SA; an unreadable policy is a warning. `deploy-backend.yml`
  runs it before mounting `ENTITLEMENT_SIGNING_KEY` (`.github/workflows/deploy-backend.yml:258-291`).
  Tests against the stub `gcloud` in `backend/tests/test_script_ops_gcp.py`.

**Closed 2026-10-19 on `claude/readiness-followups-jhfhj9`**, and removed from the
register.

- **TD-219** (the device half of the demo cap answered the account's 409, so the
  limit screen's Re-check lifted a stop the next upload refused). The device half now
  answers its own `409 device_quota_exceeded` (`backend/app/errors.py:53`, in
  `CLIENT_BRANCHED` at `:278`; `backend/app/routers/sessions.py:499`), which the app
  classifies as a quota failure (`data/cloud/UploadErrors.kt:73`) and holds as its own
  stop (`data/UploadFailures.kt:161`; `AccountCache.setDeviceLimitReached`,
  `data/net/AccountCache.kt:150`). `setQuota`, which Re-check calls, does not lift it;
  a delete on the phone (`:136`), sign-out or a licence does (`SessionQuota.deviceStop`,
  read only while the server counts the account as demo). The limit screen says the
  phone is full and hides the account's counter (`ui/limit/SessionLimitActivity.kt:57-64`,
  `limit_body_device`). A delete, not an upload's success, lifts the stop: while it
  holds no new analysis starts, so the only uploads are of sessions already admitted.
  A build without the code fails that one backup as refused (`Kind.REJECTED`) rather
  than showing the limit screen. Tests `backend/tests/test_demo_device_cap.py`
  (`test_another_accounts_analyses_on_this_device_fill_the_demo_cap`),
  `QuotaGateTest`, `DicUploadWorkerTest`, `UploadErrorsTest`,
  `SessionLimitDeviceStopTest`.

**Closed 2026-10-19 on `ccr-603fe346-1bw2fa`** (#140, open; neither merged nor
deployed), found and fixed by the same review, so none had a TD number. Recorded here
so the register says what is no longer owed; the entry moves to
[CHANGELOG.md](CHANGELOG.md) once it is merged and released. Cites are at `91699d09`.

- **Load-log parsing** (review M1–M4). A load column in kgf, kp or tf reads in newtons
  (`LoadUnit.KGF`, `TF`, `data/mechanical/MachineLoadCsv.kt:12`, `:15`; bare kg as kgf
  with `KG_READ_AS_KGF`, `:312-317`); the decimal mark comes from the data rows after
  the header, not the first ten lines (`:155-160`); the time column's unit is read,
  ms, min or h, else s with `TIME_UNIT_ASSUMED_S` (`readTimes`, `:331-346`); and
  photo times that are all whole seconds match within 1 s with a `TIME_COARSE` chip
  (`MachineLoadMapper.COARSE_MATCH_TOLERANCE_MS`, `data/mechanical/MachineLoadMapper.kt:104`;
  `PhotoCaptureTime.wholeSeconds`, `ui/analysis/load/PhotoCaptureTime.kt:71-74`). Tests
  `MachineLoadCsvTest`, `MachineLoadMapperTest`. Left: the delimiter (TD-240), the
  precision flag (TD-238).
- **E-fit window** (M5). The first straight run is refitted over 10–40 % of its Rp0.2
  (ISO 6892-1 Annex G), kept only with three frames at R² ≥ 0.995
  (`report/ElasticModulus.kt:40-41`, `fit` `:66-75`, `refit` `:99`): a 200 GPa
  Ramberg–Osgood curve reads 199.5 GPa, was 185.3. Test `ElasticModulusTest`. The
  validation re-run is TD-53.
- **Bending strain** (M6). With the edge taps the strain is the outer-fibre slope of
  Exx across the thickness (`report/BeamDeflection.kt:94-121`, used at
  `report/StressStrain.kt:144`); without them no strain is read (`measuresStrain`,
  `:146`), where the curve used to plot a region mean of about zero. Test
  `StressStrainTest`. Left: a tilted beam (TD-239), validation (TD-53).
- **Unsaved re-run kept the old test** (M15). Closed by the merge of `main`
  (`697b5da4`), which brought semperdic-app `6989327`'s deletion of
  `ui/analysis/run/UnsavedRerun.kt`: a saved re-run is a new record built from
  `RunSpec.mechanical`, so lab-4's minimal fix to that path left with it. Home's
  Re-run as a copy still drops the test (TD-220).

**Closed later on 2026-10-19 on the same branch** (#140, open; neither merged nor
released), and removed from the register. Cites are at `6e4ce099`; the commits are in
`git log db19b900..6e4ce099 --first-parent`. TD-237 was done in part (its row says what).

- **TD-238** (a photo time's precision guessed from its value). `PhotoCaptureTime.read`
  returns a `CaptureStamp`, the time and whether EXIF gave `SubSecTimeOriginal`
  (`data/mechanical/CaptureStamp.kt:11`; `ui/analysis/load/PhotoCaptureTime.kt:47-48`,
  `:63`, `:66`). The flag rides with each frame (`DeformedFrame.captureSubSec`,
  `ui/analysis/frames/DeformedFrame.kt:33`) and the reference (`LabWizardState.refCapture`,
  `ui/analysis/wizard/LabWizardState.kt:54`), is saved in the Bundle
  (`ui/analysis/wizard/WizardState.kt:48`, `:120`, `:161`) and the draft, and reaches
  `MachineLoadMapper.mapPhotos` (`data/mechanical/MachineLoadMapper.kt:172-186`; from
  `ui/analysis/wizard/LabInputs.kt:85`), which matches a batch as coarse when any time lacks
  sub-seconds (`CaptureStamp.coarse`, `CaptureStamp.kt:36-41`). A Bundle or draft from
  before the flag reads as unknown, and an all-unknown batch keeps the whole-second reading.
  Tests `CaptureStampTest`, `MachineLoadMapperTest` (`:172`), `LabInputsTest`,
  `WizardStateLabTest` (`:108`), `PhotoCaptureTimeTest`. `e52b9015`.
- **TD-239** (bending strain read as Exx, not along the beam). `Probe.strainAlongBeam`
  (`report/BeamDeflection.kt:91-95`) reads ε = Exx·c² + 2·Exy·c·s + Eyy·s² along the axis
  square to the taps (`:78-84`), used by `outerFibreStrainMilli` (`:115-145`); taps along an
  image axis read Exx or Eyy exactly as before, so every session the editor makes is
  bit-identical, and a tilted pair is named "outer-fibre strain along the beam"
  (`report/StressStrain.kt:354-359`). A pure-bending face reads 1.000 mε at 0°, ±10°, 30°
  and 90° (Exx alone read 0.9608 at 10°). Test `StressStrainTest` (`:276`). The editor
  never places a tilted pair: TD-246. `7927d78b`.
- **TD-240** (the delimiter guessed from the first ten lines). `sniffDelimiter`
  (`data/mechanical/MachineLoadCsv.kt:207`) splits the file with each candidate, finds that
  split's first numeric row and scores the header and the next `SNIFF_ROWS` (20, `:117`)
  rows on it (`Vote`, `:231-234`; called at `:147`); ties still prefer `;` then tab. Tests
  `MachineLoadCsvTest` (`:274`, `:309`). `5fc62bd6`.

**Closed later still on 2026-10-19 on the same branch** (#140, open; neither merged nor
released), and removed from the register. Cites are at `afefdb45`; the commits are in
`git log c2068036..afefdb45`. Their tests first run in the branch's next Tier 1; the
gesture and device tests named are not run yet. TD-146 was found already fixed.

- **TD-246** (the beam-edge taps held one above the other). `BeamTapPlacement.place`
  (`ui/analysis/load/BeamTapPlacement.kt:41-51`) keeps the second tap where it lands
  unless it is within `SNAP_DEGREES` (3°, `:33`) of the top mark's vertical or horizontal
  line, where `snap` (`:57-65`) puts it on that line, so a level beam's pair stays exactly
  one above the other and saves and reads bit-identically (Exx, `downX` = 0). A later tap
  moves the mark nearer along the tap line (`nearerTop`, `:68-74`); moving the top carries
  the bottom sideways by as much. The editor shows the tilt (`tiltDegrees`, `:80-84`;
  `ui/analysis/BeamEdgeTapActivity.kt:167`) and the overlay turns both crosshairs with
  the tap line. `BeamDeflection` is unchanged. Tests `BeamTapPlacementTest` (`:31`, `:70`,
  `:108`); `StressStrainTest` reads a 10°, −10° and 30° beam from taps the editor placed
  (1.000 mε, `:301`) and a 2° beam snapped level (`:318`); on a device
  `BeamTapEditorGestureTest` (`:77`, `:91`). STUDENT_LAB_WORKFLOW.md no longer asks for a
  level beam. `5e72e541`.
- **TD-237** (`ViewerArgs` carried the lab test as five loose fields). `ViewerArgs.labSession`
  holds the `LabSession` itself (`ui/viewer/ViewerArgs.kt:81`); on the Intent it is still
  its five extras, written by `putLabSession` (`:140-146`, called at `:133`) and read by
  `Reader.readLabSession` (`:313`, at `:294`), each missing one filled from the record, keys
  and value types unchanged. Home passes `record.labSession`
  (`ui/home/SessionOpenHelper.kt:72`), a fresh run `MechanicalTestInputs.labSession`
  (`ui/analysis/wizard/AnalysisNavHelper.kt:144`; `data/mechanical/MechanicalTestInputs.kt:26`,
  no curve correction until the viewer sets one), and the benchmark seed one
  (`app/src/benchmark/java/com/sempermechanics/semper/benchmark/BenchmarkSeedActivity.kt:68`,
  caught by Tier 1's benchmark-variant compile). Tests `ViewerArgsTest` (`:351`, `:495`,
  `:517`), `RunSpecTest` (`:156`). `8a45c7f1`; the seed `afefdb45`.
- **TD-220** (Home's Re-run dropped the lab test). `LabRerun`
  (`ui/analysis/rerun/LabRerun.kt:31`, fork-only) takes the record's test type,
  cross-section, strain axis, geometry (dimensions, beam-edge taps, deflection scale and
  bias) and, when typed on the phone, the saved loads (`of`, `:73-83`). Home puts them on
  the Re-run Intent (`putOn`, `:39-44`; `ui/home/HomeActivity.kt:251`), the wizard applies
  them on first open, before the load card is built (`from` / `applyTo`, `:86`, `:47`;
  `ui/analysis/StaticAnalysisActivity.kt:168`), and once the photos are copied puts each
  typed mass back on its frame and lands on step 1 when a load log is still owed
  (`applyLoadsTo`, `:60-66`; `StaticAnalysisActivity.kt:463`); `typedKg` (`:105-107`) gives
  a typed 0.5 kg back as 0.5. A plain DIC record re-runs as before. Tests `LabRerunTest`
  (`:55`, `:62`, `:71`, `:84`, `:95`, `:105`). Left, recorded in OPERATING_MANUAL §10 and not
  owed as rows: a tensile curve correction is not carried (`LabRerun` has no
  `curveCorrection`; set again in Results), and a machine load log is not kept with an
  analysis, so it is imported again, onto re-run frames that carry no capture time
  (`RerunLoader.copy`, `ui/analysis/rerun/RerunLoader.kt:82-88`, no `captureTimeMs`). The
  wizard Activity grew past ADR-015's size: TD-247. `a40f6cbf`.
- **TD-146** (Settings → Account read "Licensed as SEMP-…" on a Demo account). Already
  fixed when looked at: the row shows only while `LicenseEntitlements.isLicensed`
  (`ui/settings/SettingsAccountSection.kt:36-39`), and its comment says why (`:30-34`).
  The line is in `ab4b73d6`, this clone's shallow root (2026-10-18), so the fixing commit
  is not in this history; semperdic-app closed its own TD-146 earlier. Tests
  `SettingsLocalSectionsTest` (`:101`, `:115`, `:125`).

**Closed 2026-10-21 by the sync of semperdic-app `14b279d6`** (its #453, #457–#460;
the merge `c064e0e0` on `ccr-603fe346-1bw2fa` and its follow-ups, neither merged into
`main` nor released), and removed from the register. Cites are at `ef7032af`.

- **TD-241** (the rest of #457 not synced). The merge brings #457 whole and puts the lab
  hooks on its types. The lab wizard state is one field of the wizard's inputs
  (`WizardInputs.lab`, `ui/analysis/wizard/WizardInputs.kt:87`), read as
  `AnalysisViewModel.lab` and changed only through `edit` (`editLab`,
  `ui/analysis/wizard/LabWizardState.kt:62`, `:65`); the eleven delegated view-model vars
  (`LabField`) are gone, and the load card, Re-run, the reference and video imports and
  the draft restore each change it in one edit (`LabRerun.applyLoadsTo`,
  `ui/analysis/rerun/LabRerun.kt:60`; `AnalysisVideoExtractController.kt:126`;
  `ReferenceImportController.kt:67`; `WizardDraftBinding.kt:128`, `:154`). Its Bundle keys
  are unchanged (`Bundle.putLab` / `restoreLab`, `LabWizardState.kt:85`, `:106`; called
  from `WizardState.kt:124`, `:161`). The logic in `LabInputs.kt` is pure functions of
  `WizardInputs` (`withMachineLoadsMatched` and the rest) plus view-model reads and
  edits; a run names its test from the spec frozen at Compute, never from the wizard
  (`RunSpec.testTypeWire`, `LabInputs.kt:192`; `RunChannels.kt:139`, `SweepAnalysis.kt:67`), so
  U1's native-thread reads are gone with #457's. A result opened right after a run reads the
  lab test from its saved row (`ViewerArgs.of`, `ui/viewer/ViewerArgs.kt:227`; a sweep
  that solved nothing from its spec, `:274`), the builder Home uses, so U2's two builders
  are one (`AnalysisNavHelper.resultArgs` and `SessionOpenHelper.argsFor` are gone).
  Home's **Which test?** and the lab Re-run moved into `HomeNewAnalysis`
  (`ui/home/HomeNewAnalysis.kt:76`, `:140`, `:173`; the pending type kept across a
  recreation, `:83`, `:88`). The viewer's lab parts ride #457's narrow hosts: the summary
  and chrome take the Results as a parameter (`ViewerSummaryController.kt:85`,
  `ViewerChromeController.kt:28`), `ShareControllerHost` names the lab test, stress model
  and loads (`ViewerShareController.kt:44-50`), and Results' Adjust curve / deflection save
  through `ResultViewerViewModel.saveOnSession` (`:112`), off the Activity's
  `lifecycleScope` (ADR-016). Tests `LabInputsTest`, `LabWizardStateTest`,
  `WizardStateLabTest`, `LabRerunTest` and `RunSpecTest` (the lab test through
  `ViewerArgs.of`) are adapted to the new API; `LabWorkflowDeviceTest` and
  `WizardDraftRestoreTest` are adapted and not yet run on a device. #457's three source
  checks pass with no fork entry in their allowlists.
- **TD-247** (`StaticAnalysisActivity.kt` over 500 lines). The Activity is 493 lines: the
  lab test it opens on moved beside the load card (`openLabTest`,
  `ui/analysis/StaticAnalysisLoadCard.kt:41`, called at `StaticAnalysisActivity.kt:163`),
  and `check_kotlin_file_size.py` passes with no allowlist entry (`8bc42c33`).
- **TD-194** (a retriever frame timed as asked). Fixed by #457's `RetrieverFrameClock`
  (`imaging/video/RetrieverFrameClock.kt`), now here.
- semperdic-app's TD-221–TD-225 (data layer), TD-230 and TD-232–TD-234 (backend), TD-242
  and TD-243, which described this repo's copies too, closed here with the code that fixed
  them. Its TD-231 (a licence-wide fan-out across documents) is still open in its register.

## Lint and detekt

Baselines stay empty: `app/lint-baseline.xml` and `app/detekt-baseline.xml`.
`./gradlew :app:lintDebug` fails on errors.

**Open warnings** (do not baseline, do not `warningsAsErrors` until decided):

- `OldTargetApi`: disabled in `app/build.gradle.kts:349` until a deliberate
  `targetSdk` 36→37 bump PR. Do not re-enable casually.
- Two lint warnings in lab code: `InflateParams` at `ui/analysis/load/TypedLoadsSheet.kt:100`,
  and `PluralsCandidate` on `results_yield_fmt` (`app/src/main/res/values/strings.xml:921`).

Size and complexity findings are silenced with targeted `@file:Suppress`, not a
baseline. Prefer extracting over widening those lists. On 2026-10-08, 65 main
files carry `@file:Suppress`: most often `MagicNumber` 30, `ReturnCount` 21,
`TooManyFunctions` 21, `LongParameterList` 12 and `TooGenericExceptionCaught` 10.
Another 10 carry `@file:SuppressLint`. Catalog version-availability lint IDs are
disabled; bump dependencies in deliberate PRs.

Orphaned strings from the 2026-08-18 workflow audit are listed in
[../app/WORKFLOWS.md](../app/WORKFLOWS.md) §11. None of them fails a gate, so they
are removed as they are found, not in a sweep.

## Open register

Priority = (Impact + Risk) × (6 − Effort).

| ID | Category | Item | I | R | E | P | Status |
|----|----------|------|---|---|---|---|--------|
| TD-1 | App | Two auth continue hosts (`AUTH_HOST` / `LEGACY_AUTH_HOST`, `data/account/AuthLinks.kt:25,32`, both in `AUTH_HOSTS` at `:40`; four `autoVerify` App Link filters over two hosts in the manifest, `app/src/main/AndroidManifest.xml:115`, `:126` for `app.sempermechanics.com` and `:140`, `:149` for the `firebaseapp.com` host, whose comments at `:113`, `:125` still say the URLs live in `AuthRepository`; `AuthHostsTest.kt:17` pins the legacy host). The Firebase Console's password-reset custom action URL is still on `LEGACY_AUTH_HOST` (`…firebaseapp.com/finishReset`; [PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md), "Password-reset custom action URL"), so the legacy filters still carry every reset link | 1 | 2 | 2 | **8** | Deferred until Play vitals show no legacy-only build installed; then switch the reset URL to `AUTH_HOST` first and drop the legacy filters after (the reset URL reported 2026-10-19 by #457's agents; a Console setting, not re-checked from here) |
| TD-2 | Ops | The API Gateway runs in asia-northeast1 (`.github/workflows/deploy-backend.yml:439`), Cloud Run in asia-south1 (`:34`). API Gateway has no asia-south1 location, so every call crosses Tokyo↔Mumbai. Over 14 days the gateway added p50 135 ms / p95 1.0 s | 2 | 1 | 3 | **9** | **Accepted, deferred.** The legacy `indic-gw` / `indic-api` pair is gone (checked 2026-09-24). Two options wait for a latency complaint or a public launch, and both need an app release: new builds call Cloud Run directly, or a global HTTPS load balancer with a serverless NEG (about $20–25 a month). See [../perf/backend-cost.md](../perf/backend-cost.md) |
| TD-3 | Legal | 10 bracketed operator fields in `docs/legal/PRIVACY_POLICY.md` (:5 ×2, :7 ×2, :18, :153) and `docs/legal/TERMS_OF_SERVICE.md` (:6 ×2, :438, :439) are published as literal text on the hosted pages (`firebase-hosting/public/privacy/index.html:25`, `:31`, `:102`; `firebase-hosting/public/terms/index.html:25`, `:117`; live on 2026-10-08). `render_legal_pages.py --check` does not catch them | 2 | 5 | 1 | **35** | Operator task (the values). Listed in [PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md) |
| TD-4 | Backend | Four compat shims from the `campus`→`institution` / `plan`→`mode` rename remain (CLOUD_ARCHITECTURE_GCP §20.5), all waiting on the installed-app fleet. (6) The app reads `config.plan` when `mode` is neither licensed nor demo (`data/net/AppRemoteConfig.kt:199-204`, `data/net/ApiDtos.kt:75`). (7) The app falls back to the old `plan` pref key (`AppRemoteConfig.kt:216-225`; key at `data/prefs/PrefFiles.kt:121`). (8) The backend writes a `plan` mirror: `_mode_patch` (`backend/app/repo/_base.py:126-134`), called from `repo/claims.py`, `repo/devlock.py`, `repo/license_admin.py`, `repo/mint.py` and `repo/seats.py`; `/v1/config` (`repo/user_config.py:219`); licence summaries and mint docs (`repo/mint.py:61`, `:127`); and `backend/app/licenses.py:81`. (9) Stored `plan` / `campus` values are still read: `normalize_mode` / `normalize_kind` (`licenses.py:86`, `:98`), `_license_mode` and `_is_institution` (`repo/_base.py:137-142`, `:156-158`) | 2 | 2 | 2 | **16** | Open; shims 1–5 were retired on 2026-09-26. Remove 6 and 7 together when Play Console shows a `mode`-reading build is the whole fleet, then 8. Remove 9 when migration 002 has reached every document and 8 has stopped writing the mirror (no users without `mode`, no licences with `kind == "campus"`) |
| TD-5 | Build | Every Gradle run warns "Deprecated Gradle features … incompatible with Gradle 10". It comes from one call, `ReportingExtension.file(String)` in detekt `1.23.8`'s `DetektPlugin.apply` (`gradle/libs.versions.toml:31`); nothing in our build scripts. The wrapper is 9.8.0 and cannot move to 10 until detekt fixes it | 1 | 2 | 1 | **15** | Open. Check by hand: Dependabot's Gradle group ignores minor and patch updates (`.github/dependabot.yml:31-48`), and detekt 2 is a new group id (`dev.detekt`). On 2026-10-08, 1.23.8 is still the newest 1.x and `dev.detekt` is at `2.0.0-alpha.6`. Do not bump the wrapper to 10 before then |
| TD-6 | Performance | The Pixel 6 (`oriole`) startup references in `benchmark/gates.json` (wizard cold start 369 ms at `:14`; cold 378, warm 81) were taken on 2026-09-25 with nothing recorded about the phone's state. On 2026-09-26 the same build read +23 % and a reference build failed its own gate in 2 of 4 rounds as the phone warmed. So a trip cannot be told from the phone's state | 2 | 2 | 2 | **16** | **Partly fixed** ([ADR-008](../adr/ADR-008-startup-gates-phone-state.md)): `scripts/startup_ab.py` A/B runs (10 % `abMargin`); `DeviceStateRule` and the gates' `state` check (thermal 0, plugged in); 15 cold-start iterations (`StartupBenchmark.kt:60`). Open: re-take the `oriole` references under that protocol and record the phone's state in the label. The parent's 2026-10-05 re-take was of its own app and is not copied here |
| TD-7 | Backend / App | One public key per device id, whatever the app: `devices/{deviceId}` is written by `register_device` (`backend/app/repo/devices.py:111`, write at `:158`) and read by `verified_device` (`backend/app/deps.py:375`, key at `:443`, in `_signature_ok`), and the id is `and-{ANDROID_ID}` (`data/account/DeviceKeys.kt:62`), which Android shares between apps signed with one key. Two such apps on one phone (debug builds of Semper and Material Testing) take the key from each other, and the other's signed calls fail `401 bad_signature` (`deps.py:422`; since #457 at once, before the signature, when the record names the other app, `:379-389`). Bounded since 2026-10-10: retired `com.indicvision.*` ids may not register (`apps.RETIRED`, `routers/devices.py:19`; `410 app_retired`, live in production since the 2026-10-10 deploy, run 38032569176), and a refused call registers again and is re-sent once, at most once a minute per process (`data/net/SemperApiCalls.kt:68`, `data/net/DeviceKeyRecovery.kt:84`) | 1 | 2 | 3 | **9** | Open. Release builds of the two apps have different signing keys, so only testers meet it. A fix keys the device by app (`devices/{app}:{deviceId}`, or an app field checked at verification) and needs a migration of `devices/` on the production database staging shares, plus every reader of `get_device` (`device_in_use`, erasure, the consoles' device history) |
| TD-8 | Perf / docs | The on-device characterization was measured with the AKAZE-seeded engine (pin `1284edf`): its seeding row (`docs/perf/on-device-characterization.md:65`) and the step=20 note (`:45`) describe a seeder engine 0.3.0 replaced with the anchor lattice. The note at `:10` says so, but no number for the current pin exists | 1 | 1 | 2 | **8** | Open. Re-run `SyntheticWorkloadDriver` LIGHT and LARGE on a Pixel 6 at the 0.6.0 pin and replace the tables. Engine 0.6.0 seeds the anchor lattice from an AKAZE + RANSAC homography again (with the lattice kept), so the seeding row measures AKAZE plus the lattice, not the 0.3.0–0.5.x phase correlation |
| TD-9 | Ops / IAM | **Framework 3.4, 5.5.** The runtime SA `indic-api@` holds no Firebase Auth role on the Auth project `indicvision-dic-app-auth`, so `revoke_sessions` and `delete_auth_user` (`backend/app/google_auth.py:104`, `:121`) return False and only say so in the audit row (`sessionsRevoked`, `routers/admin.py:84-96`; `authUserDeleted`, `backend/app/services/account.py:124`, `:132`; since #457 for every Auth user linked to the account). `CHECK_REVOKED_ON_STEP_UP` is off by default (`backend/app/config.py:245-247`; `deploy-backend.yml:340`) because the check fails closed without the grant (`backend/app/step_up.py:173-174`, `:223-229`). `check_gcp_posture.sh` item 3.4-iam passes only on `roles/firebaseauth.admin`, `roles/firebase.admin` or a custom role with `firebaseauth.users.update` and `.delete` (`scripts/check_gcp_posture.sh:315-353`) | 3 | 3 | 1 | **30** | **Live 2026-10-23; acceptance owed.** `CHECK_REVOKED_ON_STEP_UP=true` serves on `semper-api-39900623312-1` (`gcloud run services describe semper-api`, 2026-10-23). The grant is in place (`roles/firebaseauth.admin` for `indic-api@` on `indicvision-dic-app-auth`, `gcloud projects get-iam-policy`, 2026-10-22) and `CHECK_REVOKED_ON_STEP_UP=true` is set for production (repo variable) and staging (environment variable, deployed 2026-10-22), by owner decision of 2026-10-22. Owed: the acceptance check below ([BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) A8). Acceptance (3.4 Verify): "Test: disable a user, then call an activation function within the 1-hour token window and expect rejection"; here, a console step-up call within the hour answers 401 `invalid_token` with an `AUTH_DENIED` row, stage `revoked` |
| TD-10 | Ops / licensing | **Framework 4.1, 4.3, 3.5.** No entitlement signing key exists yet, so `/v1/config` carries no `entitlement` (`backend/app/entitlement_token.py:264-266`, `routers/account.py:177-191`) and every app keeps the pre-token behaviour (`EntitlementPolicy.intake`, `data/account/entitlement/EntitlementPolicy.kt:151`, answers IGNORE when the build pins no key). Owed: the P-256 key in Secret Manager, the repo variable `ENTITLEMENT_SIGNING_SECRET` that `deploy-backend.yml:258-278` mounts, `roles/secretmanager.viewer` for the deploy SA and `secretAccessor` on the secret for the runtime SA, and the public key in `SEMPER_ENTITLEMENT_PUBKEYS` for release builds ([RELEASING.md](RELEASING.md) variables) | 3 | 2 | 1 | **25** | **Operator task**, in [BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) A8. Ship a release that pins the key before relying on the token. Acceptance (4.1 Verify): "Unit tests: tampered payload rejected, unknown `kid` rejected, rotated key accepted" (`backend/tests/test_entitlement_token.py:110`, `:123`, `:136`; app `EntitlementVerifierTest`), plus a production `/v1/config` that carries a token the app verifies |
| TD-11 | Ops / deploy | **Framework 5.1, 5.3.** The roster import, export, summary and audit routes need, before they answer: the composite index `audit_logs (licenseId ASC, ts DESC)` (`backend/firestore.indexes.json:88-102`; without it the audit page fails `FAILED_PRECONDITION`), and eight gateway paths (`backend/gateway/openapi.yaml:870-963`, `:1228-1305`; ESPv2 404s an undeclared path). The follow-up branch `claude/readiness-followups-jhfhj9` adds to the same deploy: the `seats.uid` / `deleted_seats.uid` collection-group index for erasure (TD-47), `POST /v1/admin/licenses/search` at the gateway (`openapi.yaml:650-668`), which the desk's search calls, and the staff desk's roster tools on `/v1/admin/licenses/{id}/…` (declared since #448); and, for ADR-024, `POST /v1/me/claim` at the gateway (`openapi.yaml:207-226`), which the account page's claim card calls, beside the backend that enforces kill switches and the demo device cap. Order: indexes (`scripts/deploy-firestore.sh indexes`, wait for the build), backend, gateway `apply`, Hosting (`scripts/deploy-console.sh`), then monitoring (`scripts/apply-monitoring.sh`, TD-12) | 2 | 2 | 1 | **20** | **Operator task** at the next deploy. Acceptance (5.7 Verify, "Doc review. API contract tests."): an anonymous call to each new path through the gateway answers 401, not 404, and `GET …/audit` answers 200 for a licence administrator; after the follow-up, an anonymous `POST /v1/admin/licenses/search` and an anonymous `POST /v1/me/claim` answer 401 |
| TD-12 | Ops / monitoring | **Framework 7.1-7.3; the verifier for 2.1-3.7.** `ops/monitoring/` and `scripts/check_gcp_posture.sh` are tested only against a stub `gcloud` and `curl` (`backend/tests/test_script_ops_gcp.py`, `scripts/tests/fixtures/gcp/`). Unproven until a real run: the uptime check's OIDC auth to the private service, the burn-rate filter syntax, and the JSON shapes the posture script parses. Posture item `2.7-backup` passes on a daily managed backup schedule, or, given `--backup-bucket`, on an export from `firestore-backup.yml` younger than 48 h; without `--backup-bucket` and with no schedule it reports UNKNOWN (`scripts/check_gcp_posture.sh:279-313`) | 2 | 2 | 1 | **20** | **Operator task.** `scripts/apply-monitoring.sh --project indicvision-dic-app --notification-channel <id> --apply`, then the posture script against both projects with `--backup-bucket` set to the `FIRESTORE_BACKUP_BUCKET` variable; record the output in the gate. Acceptance (7.2 Verify): "SLO dashboard and burn-rate alerts"; (2.1 Verify) "`gcloud` output listing liens and IAM bindings" |
| TD-13 | CI / release | **Framework 1.5.** `scripts/check_release_apk.py` is tested on hand-written `aapt2` output (`scripts/tests/fixtures/apk/`, `backend/tests/test_script_check_release_apk.py`); Tier 5 has since run it on real tool output (Status). Since the sync of semperdic-app `b1c15346` (its readiness TE1-001) the Release workflow runs the check with `--require-pins` (`.github/workflows/release.yml:280`), so a release of this app fails while this repository's `SEMPER_API_CERT_PINS` (`:203`) is empty; and the signer is checked only once `SEMPER_RELEASE_CERT_SHA256` names this app's certificate (`:268`; semperdic-app's own default is not this app's key) | 1 | 2 | 1 | **15** | **Partly done.** Tier 5 of CI run 39158239853 (`ae4c24a`) ran the step "Inspect release APK hardening" on a signed release APK and passed, so the check reads real `aapt2` output and the fixture concern is met (re-checked 2026-10-19). semperdic-app decided the pins (the four GTS roots, its RELEASING.md "Certificate pins"). Open, Admin actions on this repository before its next release: set `SEMPER_API_CERT_PINS` to the same pin set, and `SEMPER_RELEASE_CERT_SHA256` to the SHA-256 of this app's release certificate (alias `materialtesting`). Acceptance (1.5 Verify): "APK inspection in CI (MASWE-0067 'Debuggable Flag Not Disabled', MASWE-0089 'Code Obfuscation Not Implemented')" |
| TD-14 | App / CI | **Framework 1.5, 4.1, 4.3, 5.6, 6.1, 6.4.** The Android half of the 2026-10-18 readiness change (minSdk 24 to 30, `app/build.gradle.kts:130`; ES256 entitlement verification, grace and clock rollback under `data/account/entitlement/`; the version gate `data/policy/VersionGate.kt:33` and `ui/limit/UpdateRequiredActivity.kt:22`; kill switches `data/policy/KillSwitch.kt:11`; managed configuration `data/policy/ManagedConfigReader.kt:38-70`; consent-gated breadcrumbs `diagnostics/CrashReportingTree.kt:23-41`; certificate pins and entitlement keys from the environment, `app/build.gradle.kts:80-93`) was written where `dl.google.com` was blocked, so it was not compiled there. The follow-up branch `claude/readiness-followups-jhfhj9` adds more Android code of the same kind (`SealedRecordStore`, the one-off `license-config-now` refresh, `UpdateSource`, the shown cap, the floating-lapse route to the seat screen; and, for ADR-024, `ui/limit/LicenseRefresh`, `data/policy/FeatureLocks`, `ui/common/dialog/LicenseLock`, `ui/home/HomeLicenseNotice` and the paused-feature handling, with the `ui/account/` package removed) that no CI run has compiled yet | 2 | 2 | 1 | **20** | **Partly closed.** The compile and unit-test half is done: CI run 39158239853 on #448's head `ae4c24a` passed Tier 1, Tier 3 (emulator), Tier 4, Tier 5 and the rules job; run 39206485965 on #449's head `95f5607` passed the same with `full-ci`, the follow-up's Robolectric suites and Kover included; material_testing#137 and #138 passed Tier 1, 3 and 5 (runs 39156925646, 39206773873). Both merged 2026-10-19. On emulators, 2026-10-22, a debug APK from `main`: Android 11 (API 30, x86_64 Google APIs image) installs it and opens Home with no crash; Android 10 (API 29) refuses it, `INSTALL_FAILED_OLDER_SDK … Requires newer sdk version #30 (current version is #29)`. The version gate raised on staging opened the update screen on both emulators (TD-15, 2026-10-22). Open, on a device: a token verified against a provisioned key (TD-10), managed configuration in an EMM (TD-44). Acceptance (4.3 Verify): "Time-travel tests (Part E)" (`EntitlementClockTest`, `SealedRecordStoreTest`, `EntitlementRefreshTest`), green in run 39206485965, and a device smoke of the first release built from `main` |
| TD-15 | Ops / release | **Framework 6.1, 6.4.** The release levers are deploy variables: `MIN_SUPPORTED_VERSION_CODE`, `RECOMMENDED_VERSION_CODE` and their `_SEMPER_CLASSROOM` pair (`_MATERIAL_TESTING` before ADR-030, still read as a fallback; each app gets its own, `settings.release_gate(app)`), `KILL_SWITCHES`, `ENTITLEMENT_OFFLINE_GRACE_DAYS` (`deploy-backend.yml:327-334`; `backend/app/config.py:278-317`). None has been moved on a running service and watched on a phone | 2 | 2 | 1 | **20** | **Partly closed** (2026-10-22): `KILL_SWITCHES`, `MIN_SUPPORTED_VERSION_CODE` and `RECOMMENDED_VERSION_CODE` drilled on staging with two emulators and set back ([RELEASING.md](RELEASING.md#rollback-levers)): the share, backup and restore pauses, the backend's own 503 `feature_paused`, the update screen, and `RECOMMENDED_VERSION_CODE`'s once-only Home nudge. Open: the `_SEMPER_CLASSROOM` pair with a Semper Classroom build, and `ENTITLEMENT_OFFLINE_GRACE_DAYS`. Drill on `semper-api-staging` with a staging build: set each, redeploy, confirm the app's response, set it back; record the result in [RELEASING.md](RELEASING.md). Acceptance (6.4 Verify): "Flag toggle drill in staging"; (6.1 Verify) "Test: raise the minimum, confirm the gating UX" |
| TD-16 | Ops / GCP | **Framework 2.1.** Production project protection is unrecorded: a lien on `resourcemanager.projects.delete`, a `Prod` tag, two or more owners, owners through Google groups. `scripts/check_gcp_posture.sh:118-159` reports each; it has not been run against `indicvision-dic-app` or `indicvision-dic-app-auth` ([PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md), "Confirm Firebase API key restrictions") | 2 | 3 | 1 | **25** | **Operator task.** Run the posture script; add the lien and the tag; move owners to a group. Acceptance (2.1 Verify): "`gcloud` output listing liens and IAM bindings. Firebase launch checklist items marked complete." |
| TD-17 | Ops / security | **Framework 2.4.** The Firebase API keys on both projects are limited to a list of APIs but carry no Android package / SHA-1 or referrer restriction (checked 2026-10-08; "Checked by hand" below). Posture item 2.4 (`scripts/check_gcp_posture.sh:160-180`) | 2 | 3 | 1 | **25** | **Operator task.** Restrict the Android key to the app ids with the release and Play signing SHA-1s, and the browser key to the Hosting origins. Acceptance (2.4 Verify): "API key restriction export" |
| TD-18 | Ops / security | **Framework 2.5.** The enabled sign-in providers and Auth abuse / enumeration protection are unconfirmed ([PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md), "Confirm Auth abuse / enumeration protections"). The app uses password, email link and Google; anonymous sign-in is unused. Posture item 2.5 (`scripts/check_gcp_posture.sh:181-208`) | 1 | 3 | 1 | **20** | **Operator task.** Acceptance (2.5 Verify): "Auth provider config screenshot" |
| TD-19 | Ops / cost | **Framework 2.6.** Spend is capped only by Cloud Run `--max-instances=10` (`deploy-backend.yml:341`) and the gateway quotas; there are no Cloud Functions to cap. A billing budget with alerts is not shown to exist or to reach anyone (posture item 2.6, `scripts/check_gcp_posture.sh:209-233`), and no budget notification disables billing | 2 | 2 | 2 | **16** | **Operator task**, then deferred: a budget whose Pub/Sub notification caps spend is optional. Acceptance (2.6 Verify): "Billing config. Alert test fired to on-call." |
| TD-20 | Ops / security | **Framework 3.7.** Data Access audit logs (DATA_READ, DATA_WRITE) for Firebase Management, Firebase Rules, App Check and Firestore are not confirmed on. Posture item 3.7 (`scripts/check_gcp_posture.sh:354-369`) | 1 | 2 | 1 | **15** | **Operator task.** Acceptance (3.7 Verify): "Log sink configuration" |
| TD-21 | Ops / security | **Framework 3.1.** `APP_CHECK_MODE` is `off` (`backend/app/config.py:230`; `deploy-backend.yml:324`). `_require_app_check` asks only callers that send `X-Device-Id` (`backend/app/deps.py:87-121`). Firestore needs no App Check (every client is denied, `firestore.rules:7-9`), and there is no Storage or Functions. `enforce` would 403 every installed build without App Check, and no build can get a Play Integrity token before TD-23 | 3 | 3 | 2 | **24** | **Blocked by TD-23.** `off` to `monitor`, watch `app_check_missing` / `app_check_invalid` reach zero across the installed fleet, then `enforce`; rollback is the variable ([INCIDENT_RESPONSE.md](INCIDENT_RESPONSE.md)). Acceptance (3.1 Verify): "Enforcement metrics in the App Check console show unverified requests rejected"; here, `AUTH_DENIED` rows with stage `app_check` and no rise in refused genuine calls |
| TD-22 | Ops / data | **Framework 2.9.** The production Firestore location is not recorded. Setup creates the database at `$REGION` = asia-south1 (`docs/backend/BACKEND_SETUP_GCP.md:49`, `:81`); the schema section says "`nam5`/regional to match Cloud Run region" (`docs/backend/CLOUD_ARCHITECTURE_GCP.md:550`); the Privacy Policy still holds `[GCP REGION, e.g. asia-south1 (Mumbai)]` (`docs/legal/PRIVACY_POLICY.md:153`, TD-3). Not readable from the authoring environment (no credentials) | 1 | 2 | 1 | **15** | **Operator task.** `gcloud firestore databases describe --database='(default)' --project indicvision-dic-app --format='value(locationId)'`; write it into §5 and, through TD-3, the policy. Acceptance (2.9 Verify): "Location documented in the DPA and architecture record" |
| TD-23 | Ops / Play | **Framework 1.6.** No Play developer account is linked to the project, and the Play app-signing SHA-256 is not on the Firebase Android app ([PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md), "Play developer account"); until then no build can obtain an App Check token. The release certificate is in `firebase-hosting/public/.well-known/assetlinks.json`, which `release.yml` checks | 3 | 2 | 2 | **20** | **Operator task.** Acceptance (1.6 Verify): "Firebase console screenshot. Sign-in works on a Play-distributed build, not just a sideloaded one." |
| TD-24 | Ops / Play | **Framework 1.2.** No Play listing exists, so there is no pre-launch report. Builds are distributed as signed APKs from GitHub Releases ([RELEASING.md](RELEASING.md), release checklist step 6) | 1 | 2 | 1 | **15** | **Operator task** once a listing exists. Acceptance (1.2 Verify): "Play Console pre-launch report attached to the review" |
| TD-25 | Ops / release | **Framework 1.1, 6.3.** The halt thresholds are written ([RELEASING.md](RELEASING.md), "Staged rollout on Google Play"), but there is no closed track to measure them on and no rehearsal of a staged rollout and halt | 2 | 2 | 2 | **16** | **Operator task** after TD-23. Acceptance (1.1 Verify): "Crashlytics dashboard for the release candidate on closed-track testers. Play Console Android vitals."; (6.3 Verify) "Release runbook. Rehearsal on closed track." |
| TD-26 | Legal / Play | **Framework 8.4.** The Play Data safety form is not submitted. The answers are ready to copy, in the form's order, in [PLAY_DATA_SAFETY.md](../legal/PLAY_DATA_SAFETY.md), checked against `app/build.gradle.kts:378-440`, the merged release manifest, DATA_MAP and the Privacy Policy; they cover both gate items ([PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md), "Update the Google Play Data safety form" and "Google Play Data safety form: demo analysis content"). The CI release APK of `main` at `968637cb` (run 39775004430, `aapt2 dump permissions`) declared `com.google.android.gms.permission.AD_ID`, `ACCESS_ADSERVICES_AD_ID` and `ACCESS_ADSERVICES_ATTRIBUTION`, merged in by `play-services-measurement-impl` / `-api` 24.0.0 (Firebase Analytics); nothing reads the advertising ID (no `AdvertisingIdClient` in the app; `diagnostics/Diagnostics.kt:79` only toggles collection). The manifest now removes all three (`app/src/main/AndroidManifest.xml:16-27`) and sets `google_analytics_adid_collection_enabled=false` (`:53-57`); the merger report lists the three as `REJECTED`. `v1.2-beta.4` and every earlier build still carry them, so the Advertising ID declaration can say No only for a build with this change. Two OWNER DECISIONs are open in that file: the delete-account web URL Play requires (`app.sempermechanics.com/account` needs a sign-in and only points to the app, `firebase-hosting/public/console/messages.js:69-74`), and confirming the Analytics property has no Ads link, Google signals off and data sharing off (else three types become Shared) | 2 | 3 | 1 | **25** | **Operator task.** Settle the two OWNER DECISIONs, upload a build with the manifest change, copy the answers and submit; then tick both gate items. Acceptance (8.4 Verify): "Cross-check against the SDK inventory" |
| TD-27 | App | **Framework 6.2.** No Play In-App Updates: below `minSupportedVersionCode` the gate sends the user to the store or website (`UpdateRequiredActivity.openStore`, `ui/limit/UpdateRequiredActivity.kt:49`). Needs Play distribution (TD-23) and a priority set through the Play Developer API | 1 | 1 | 2 | **8** | **Deferred** until Play distribution. Acceptance (6.2 Verify): "Internal app sharing test of both flows" |
| TD-28 | Licensing / security | **Framework 4.2; Part B first activation, check-in and replacement; 7.3 (Play Integrity ratio).** Only the `dkt` binding is built (`backend/app/entitlement_token.py:102-111`). The device key is a non-exportable P-256 Keystore key with no attestation challenge and no StrongBox request (`data/account/DeviceKeys.kt:75-86`), and `register_device` stores its PEM unverified (`backend/app/repo/devices.py:111-158`, the key at `:132`). The backend reads no Play Integrity verdict (App Check is the only Integrity use, `app/build.gradle.kts:371`), so no failure ratio can be logged, and device recall is not used | 3 | 3 | 4 | **12** | **Deferred.** Server-side chain verification against both Google roots at registration; a standard-request Integrity verdict at activation and check-in with a graded policy; device recall after the beta form. Acceptance (4.2 Verify): "Activation on emulator or rooted device fails or is flagged according to policy" |
| TD-29 | App / resilience | **Framework Part B resilience controls (MASVS-RESILIENCE-1 to 4).** R8 obfuscation is the only resilience control (`app/build.gradle.kts:236`): no signature or installer check, no debugger or Frida detection, and no graded server response (flag, shorten grace, refuse) | 2 | 2 | 3 | **12** | **Deferred**; the server half depends on TD-28. Acceptance (Part E tamper tests): "Repackage the APK with patched verification and confirm Play Integrity `appIntegrity` fails at the next check-in and the server flags the device"; "Use Frida to hook the signature-verification function" |
| TD-30 | Backend / security | **Framework 3.5.** The entitlement signing key is a PEM in Secret Manager mounted into the process environment (`backend/app/config.py:268`; `deploy-backend.yml:258-278`), so the running service holds the private key. Cloud KMS asymmetric signing (`EC_SIGN_P256_SHA256`) would keep it out of the process, at one KMS call per issued token | 2 | 2 | 3 | **12** | **Deferred.** Acceptance (3.5 Verify): "Secret scanning (e.g., gitleaks) in CI. IAM policy review." |
| TD-31 | Licensing | **Framework 4.4; Part B revocation.** A device never confirms a revocation. It learns of one at its next `/v1/config` (`PERIOD_HOURS` = 4, `data/LicenseConfigWorker.kt:82`) or when its entitlement's `graceUntil` passes; the console's "revocation pending" is inferred from `lastSeenAt` against `revokedAt` (`backend/app/roster_io.py:219-235`; `backend/app/repo/reconcile.py:60`), so it cannot show "hold pending on the device" for a seat put on hold | 2 | 2 | 3 | **12** | **Deferred.** The app reports the entitlement it now holds (`lic`, `mode`, `iat`) on its next call, and the seat records `ackedAt`. Acceptance (4.4 Verify): "End-to-end test with the device offline, then online" |
| TD-32 | Security | **Framework 3.6; Part E penetration test scope.** No third-party test has been run. The scope is [PENTEST_SCOPE.md](PENTEST_SCOPE.md); the self-assessment it starts from is [MASVS_ASSESSMENT.md](MASVS_ASSESSMENT.md) | 3 | 3 | 3 | **18** | **External.** Commission before general availability; every critical and high finding closed or risk-accepted in the gate. Acceptance (3.6 Verify): "Third-party pentest report mapped to MASVS controls (scope in Part E)" |
| TD-33 | Legal / compliance | **Framework 8.2.** DPDP obligations apply from 13 May 2027. The Rule 7 playbook is [INCIDENT_RESPONSE.md](INCIDENT_RESPONSE.md). The Privacy Policy's breach clause (`docs/legal/PRIVACY_POLICY.md:233-239`) promises notice to the authority "within the period required by law" and to users "without undue delay", which counsel should align with Rule 7; the Grievance Officer is unnamed (TD-3); counsel review is open; no tabletop has been run | 3 | 3 | 2 | **24** | **External** (counsel, operator). Acceptance (8.2 Verify): "Tabletop exercise record" |
| TD-34 | Backend / compliance | **Framework 5.3.** Audit rows written before 2026-10-18 carry no `licenseId` (stamped from then on, `backend/app/audit.py:81-83`, from `licence_of`, `:42`), and the per-licence export reads only stamped rows (`page_license_audit`, `backend/app/repo/audit_log.py:123-162`; docstring `:130-132`), so a licence's earlier history is absent from its export. A backfill rewrites rows in an append-only collection (TD-199). The backfill is written, not run (2026-10-19 on #457, `9aa8cd1`): `backend/scripts/stamp_audit_licence_ids.py` adds only `licenseId`, derived from each row's `target` as `record` does, to rows that lack it; dry-run by default, `--apply` refuses without `--decision` (`stamp`, `:80-88`) and records the decision and the counts in `_migrations/td246_stamp_audit_licence_ids` (`:54-55`); idempotent. Tests `backend/tests/test_script_stamp_audit_licence_ids.py` (`:60`, `:71`, `:97`, `:108`, `:115`) | 1 | 2 | 2 | **12** | **Backfill applied; acceptance owed.** The owner approved the backfill on 2026-10-22. Since then the same pass also stamps `expireAt` = `ts` + `AUDIT_RETENTION_DAYS` on rows that lack it (TD-199; rows without `ts` are skipped and counted; `_updates`, `backend/scripts/stamp_audit_licence_ids.py:59-77`; the ledger also records `retentionDays` and both counts, `:95`, `:123-126`; tests `backend/tests/test_script_stamp_audit_licence_ids.py:90`, `:115`). The operator ran the dry run and then `--apply --decision` on 2026-10-23, after the backend that stamps new rows was deployed and before the TTL policy was created; the counts are in the ledger (`_migrations/td246_stamp_audit_licence_ids`), and a dry-run rerun found 6145 rows, all with `expireAt`, 48 with `licenseId` and 6097 whose target names no licence, with 0 left to stamp. Owed: the acceptance check below, then remove this row. Acceptance (5.3 Verify): "Attempt to edit or delete audit entries as Org Owner and expect failure", and an export for a range before 2026-10-18 either returns its events or says that it cannot |
| TD-35 | App / data | **Framework 1.3.** Measurement records are not tamper-evident: a `.dat` is eight floats per point with no MAC, signature or sealed timestamp (`field/DicResult.kt:20-24`), and its bytes are pinned by the bit-exact oracles (`app/src/test/resources/oracles/`). Process death is survived through the wizard draft ([ADR-005](../adr/ADR-005-wizard-process-death.md)) | 2 | 2 | 3 | **12** | **Built here by the 2026-10-26 sync of semperdic-app `25334af1`; close after this app's check.** [ADR-028](../adr/ADR-028-tamper-evident-records.md) (closed there, its #471): every run that is not cancelled ends in `RunSession.commitRecord`, which writes a signed `record_manifest.json` (SHA-256 per `.dat` and `field_ranges.bin`, the run spec, the app and engine pin; `data/integrity/RecordManifestWriter.kt`, `data/integrity/RecordSigner.kt`), read back by `data/integrity/RecordVerifier.kt`; the `.dat` bytes are unchanged. Owed here: `RecordManifestProcessDeathTest` and the lab workflow on an emulator, and a check that no lab file ending in `.dat` is written after the commit ([FORK_SYNC.md](FORK_SYNC.md), "Tamper-evident records"). Acceptance (1.3 Verify): "Instrumented tests that kill the process mid-test-run and replay. Compare stored records with the source data." |
| TD-36 | Ops / backend | **Framework 2.1 (three projects), 2.8, Part D3, Part E.** Staging runs in the production project against the production Firestore database: `semper-api` and `semper-api-staging` are both in `indicvision-dic-app` under one runtime SA ([ENVIRONMENTS.md](ENVIRONMENTS.md), resource table). Load and chaos tests (TD-37, TD-38) cannot run against it, and TD-7's fix becomes a production migration | 2 | 3 | 4 | **10** | **Deferred.** A staging project with its own Firestore, Auth (or Auth tenant), Drive root, queue and gateway; `deploy-backend.yml`'s staging target moves to it. Acceptance (2.1 Verify): "`gcloud` output listing liens and IAM bindings" for three projects |
| TD-37 | Backend / perf | **Framework 2.8; Part E load testing.** No load test exists. Seat counts are one counter per licence document (`seatsUsed` / `leasesActive` increments, `backend/app/repo/claims.py:255`, `backend/app/repo/seats.py:366-372`), the hot spot Part E names for a 500-device onboarding. Blocked by TD-36 | 2 | 2 | 3 | **12** | **Blocked by TD-36.** Acceptance (2.8 Verify): "Load test report (Part E) and Key Visualizer screenshot with no hotspots" |
| TD-38 | Backend / ops | **Framework Part E chaos/failure tests.** No fault injection against a running environment. In unit form: `/healthz` stays 200 and `/readyz` 503s with both dependencies down (`backend/tests/test_health.py`), and a Drive outage during verify deletes nothing (`backend/tests/test_verify_degradation.py`). Found 2026-10-19: a Firestore outage on any route but `/readyz` (once the client's retries give up) reaches the edge middleware and answers 500 `internal_error` (`backend/app/main.py:195-203`); only the readiness ping maps it to 503 `firestore_unreachable` (`ping`, `backend/app/repo/_base.py:199-208`, through `obs.DependencyError`). Blocked by TD-36 | 1 | 2 | 3 | **9** | **Blocked by TD-36** (the drill only). The 503 decision (owner, 2026-10-22) is implemented: a route's Firestore `UNAVAILABLE` / `DEADLINE_EXCEEDED`, raw or inside api-core's `RetryError`, answers 503 `firestore_unreachable` with `Retry-After: 30` (`_is_firestore_outage`, `backend/app/main.py:249-258`, from the edge catch-all at `:198-205`; header on every `obs.DependencyError`, `backend/app/main.py:261-272`, `backend/app/deps.py:294-298`; `backend/tests/test_firestore_outage.py`). The app retries such a 503 like any other (`data/net/RetryOnTransient.kt:77-81`). Acceptance (Part E): "Disable the check-in function in staging. Inject Firestore `UNAVAILABLE`/`DEADLINE_EXCEEDED` errors. … Restore Firestore from PITR into a new database, then repoint staging." |
| TD-39 | Release | **Framework 6.5.** Backend changes follow expand/contract ([FIRESTORE_SCHEMA_RUNBOOK.md](../backend/FIRESTORE_SCHEMA_RUNBOOK.md); the §20.5 skew fallbacks) and the wire bodies are pinned (`backend/tests/test_wire_contracts.py`, over `contracts/`), but no job runs released app builds against a new backend | 2 | 2 | 3 | **12** | **Deferred.** Acceptance (6.5 Verify): "Compatibility matrix CI job (N-2 versions)" |
| TD-40 | Observability | **Framework 7.1 (alerts).** Crashlytics velocity alerts and their routing are Firebase-console settings outside `ops/monitoring/` (`ops/monitoring/README.md:104-108`); none is confirmed | 1 | 2 | 1 | **15** | **Operator task.** Acceptance (7.1 Verify): "Alert routing test" |
| TD-41 | Observability / app | **Framework 7.1 (Performance Monitoring).** The Performance Monitoring SDK is not in the app (`app/build.gradle.kts:354-418`). Adding it changes the Data safety answers (TD-26) and has to sit behind the diagnostics consent (`AppSettings.diagnosticsEnabled`) | 1 | 1 | 2 | **8** | **Deferred.** Acceptance (7.1 Verify): "Alert routing test", on startup, sync and activation traces |
| TD-42 | Backend / console | **Framework 5.1; Part C console essentials.** Built: tiles for assigned, on hold, active in 7 days, invited, revocation pending and expiring (`backend/app/roster_io.py:202-243`), CSV import and export. Missing: per-device last check-in and app version on a roster row (`_seat_public`, `backend/app/repo/institution_admin.py:157-172`), and a bulk "release stale devices" action | 2 | 1 | 3 | **9** | **Deferred.** Acceptance (5.1 Verify): "Usability test with an IT admin persona. Task completion time recorded." |
| TD-43 | Product | **Framework 5.1.** No usability test of the institution console with an IT-administrator persona has been run | 2 | 1 | 2 | **12** | **External.** Acceptance (5.1 Verify): "Usability test with an IT admin persona. Task completion time recorded." |
| TD-44 | App / EMM | **Framework 5.6; Part C EMM.** The managed-configuration schema (`app/src/main/res/xml/app_restrictions.xml`: `accountEmailDomain`, `cloudBackupAllowed`, `backupOnUnmeteredOnly`; `enrollmentInvite` removed 2026-10-18, ADR-024) and its reader (`data/policy/ManagedConfigReader.kt:38-70`) have not been tried in an EMM. Managed configurations reach only production-track or private apps, and `ssoProviderId` / `updateMode` wait on FI-17 / FI-22 | 2 | 1 | 2 | **12** | **External.** Acceptance (5.6 Verify): "Test in at least one EMM (e.g., Intune, or Android Management API test DPC)" |
| TD-45 | Ops / support | **Framework Part D6, 6.6.** [SUPPORT_POLICY.md](SUPPORT_POLICY.md) leaves response targets and maintenance windows to the business (TBD); there is no status page; the Terms state no deprecation notice period | 1 | 2 | 2 | **12** | **External** (business). Acceptance (6.6 Verify): "Release notes archive"; (Part D6) "publish severity definitions, response targets, a supported-version policy …, maintenance windows, and a status page" |
| TD-46 | Licensing / risk | **Framework 4.3; Part B clock-tampering resistance (residual risk).** On a rooted phone, removing the sealed entitlement record's HMAC key makes the store read as never having held a token, so the app falls back to the unsigned cached `/v1/config`. Since TD-211 (closed above, on the follow-up branch) that holds for deleting the key alone as well as both: a record with no key reads as no token and is dropped, so a lost Keystore key is not mistaken for tampering (`EntitlementPolicy.reading`, `data/account/entitlement/EntitlementPolicy.kt:137-144`; `SealedRecordStore.load`, `data/account/entitlement/SealedRecordStore.kt`; no token is `TokenStatus.NotUsed`, unbounded, `EntitlementPolicy.kt:91-92`). Writing the first-seal mark (`PrefFiles.Entitlement.SEALING`, in the same preference file) beside a deleted record has the same effect. Only a key with neither record nor mark, or a MAC mismatch, is detected as tampering | 1 | 2 | 3 | **9** | **Accepted as residual risk** (the framework: "This is not tamper-proof on a rooted device … The server-side bound (`exp`, seat revocation at next check-in) is the real control"). Revisit with TD-28 / TD-29. Acceptance (4.3 Verify): "Time-travel tests (Part E)"; a test that a missing record with a present key and no first-seal mark reads as tampered (`EntitlementPolicyTest`, `SealedRecordStoreTest`) |
| TD-47 | Ops / deploy | **Framework 8.5.** Erasure finds every seat an account held with a collection-group query on `uid` (`_seats_of`, `backend/app/repo/account.py:156-183`), which needs the single-field COLLECTION_GROUP index on `seats.uid` and `deleted_seats.uid` (`backend/firestore.indexes.json:161-204`, fieldOverrides). Until it is deployed and built the query fails: erasure blanks only the current licence's seat, logs `erasure_seat_scan_failed` (`backend/app/repo/account.py:177-178`), and the `ACCOUNT_DELETE` row says `seatScanComplete: false`. Accounts erased before the change keep their `auth_links` rows and seat addresses; no sweep for them is written | 2 | 2 | 1 | **20** | **Operator task** at the next deploy: `scripts/deploy-firestore.sh indexes` and wait for the overrides to build, then the backend (the order in TD-11). Then decide on a one-off sweep for earlier erasures (an `auth_links` row or a seat whose `uid` has no `users` document). Acceptance (8.5 Verify): "Scripted test"; here, `gcloud firestore indexes fields describe uid --collection-group=seats` lists an ascending COLLECTION_GROUP index, and the next erasure's `ACCOUNT_DELETE` row has `seatScanComplete: true` |
| TD-48 | Backend / privacy | **Framework 7.4.** After TD-203 (closed above) `GET /v1/admin/licenses` still answers `q` for one release (`backend/app/routers/admin.py:141-163`) and logs `deprecated_query_search` with the caller's uid, never the value (`:159-161`). A staff script or a desk page cached before the Hosting deploy that sends `?q=<address>` still puts an address in the request logs | 1 | 1 | 1 | **10** | **Open, low.** Once the release after the follow-up has shipped and `deprecated_query_search` shows no caller for 30 days, drop `q` from the GET and from its description in `backend/gateway/openapi.yaml:623-649`. Acceptance (7.4 Verify): "Log sampling review"; here, no `deprecated_query_search` line in the 30 days before removal |
| TD-49 | Licensing / risk | **Framework Part B resilience controls; Part E licence bypass and tamper tests (residual risk).** Share (PNG, GIF, PDF, CSV, ZIP) has no backend route: the app decides it from the cached `/v1/config` and the signed token (`LicenseEntitlements.shareLicensed`, `data/account/LicenseEntitlements.kt:85-86`; `ShareCenter.access`, `ui/viewer/share/ShareCenter.kt:246-247`; guard `:47-51`). A modified APK that skips the check, or a hooked `EntitlementVerifier`, shares without a licence. It gains no cloud access: every cloud route checks the account on the server ([ADR-024](../adr/ADR-024-account-anchored-entitlements.md), "Server-side checks per request") | 1 | 2 | 3 | **9** | **Accepted as residual risk** (ADR-024 decision 5). Revisit with TD-29, or as soon as share gains a server route (a server-rendered export, a share link): that route then refuses a demo account with 403 `feature_not_licensed`, as restore does. Acceptance (Part E): the pentest's repackaged-APK case ([PENTEST_SCOPE.md](PENTEST_SCOPE.md)) records that a patched build shares and that its cloud calls are still refused for a demo account; for any future share route, a backend test that a demo account is refused |
| TD-50 | Licensing / product | **Framework 4.1 (entitlement enforced server-side).** The demo cap counts analyses **stored at once**, per account (`count_user_sessions`, `backend/app/repo/sessions.py:394-412`) and, since 2026-10-18, per device (`count_device_sessions`, `:415-439`; applied for demo at `backend/app/routers/sessions.py:546-547`). Deleting an analysis frees its place (`DELETE /v1/sessions/{sid}`, `sessions.py:142-143`), so a demo user can delete and continue indefinitely; erasing the account deletes its sessions (`backend/app/repo/account.py:226-227`), so erase-and-rejoin resets both counts | 1 | 1 | 3 | **6** | **Accepted by decision** (product owner, 2026-10-18; ADR-024 "Why stored-at-once"): the cap is a nudge, not a meter, and an ever-created count would need a counter that survives erasure. Revisit only if the product owner changes the cap's meaning. Acceptance (4.1 Verify): a backend test that, at the demo cap on one device, deleting one analysis lets the next `POST /v1/sessions` through (the stored-at-once behaviour pinned), beside `backend/tests/test_demo_device_cap.py` |
| TD-51 | Legal / Play | **Framework 8.4 (Play policy consistency).** The Terms the app links (`legal_terms_url`, `app/src/main/res/values/strings.xml:484`; opened from `ui/settings/SettingsFooterSection.kt:35`, `ui/settings/SettingsYourDataSection.kt:75` and `ui/auth/TermsActivity.kt:63`) were aligned with ADR-024 on 2026-10-19: 6.2 names the account-anchored sources (seat, email-locked licence, claim code on the account page), the new 6.6 says the App sells, prices and takes payment for nothing, and "licence key" became "claim code" in 2.3, 3.3 and 5 (`docs/legal/TERMS_OF_SERVICE.md:53-55,73-74,126-127,149-180`; rendered to `firebase-hosting/public/terms/index.html`). The **Version** stays 2026-09-15, so no user is asked to accept again: under 17.1 the edit is treated as non-material (it describes how access is obtained and what the App does not do; no right or fee changes). Fees, refunds and price changes for access arranged outside the App (6.2–6.5) stay. Whether that holds under Google Play's Payments policy (https://support.google.com/googleplay/android-developer/answer/10281818), and whether the edit is material, are for counsel. On 2026-10-22 §6.1 gained one sentence under the same Version: demo analyses are uploaded and stored automatically and cannot be restored on the Demo plan (`docs/legal/TERMS_OF_SERVICE.md:151-158`, with the matching Privacy §2.3 bullet, `docs/legal/PRIVACY_POLICY.md:55-61`); it describes what the App already does, and whether it is material is counsel's call too. Until counsel answers, the hosted `/privacy` and `/terms` stay at `b940cc29` (Hosting redeployed that way 2026-10-23, [checklist](DEPLOY_CHECKLIST_2026-10.md) §4) | 2 | 1 | 1 | **15** | **External** (counsel). Text done; counsel's word owed. If counsel calls the edit material, bump **Version** with `backend/app/legal.py:10`, `LegalTerms.TERMS_VERSION` (`app/src/main/java/com/sempermechanics/semper/data/account/LegalTerms.kt:18`) and the `contracts/` fixtures in one commit. Acceptance (8.4 Verify; here, the Play policy review): counsel's sign-off recorded in the gate, and `python scripts/render_legal_pages.py --check` passing |
| TD-52 | Backend / contracts | **Framework 6.5 (expand/contract).** `POST /v1/licenses/activate` stays for installed builds and support recovery (`backend/app/routers/licenses.py:49-81`), and with it `contracts/license_activate_request.json`, read only by the backend's wire-contract test (`backend/tests/test_wire_contracts.py:51`); the app's contract test no longer asserts it (e084f39). No released build of Semper calls the route from a screen (v1.2-beta.4, `33dce87d`, has `SemperApi.activateLicense` and no caller) | 1 | 1 | 1 | **10** | **Open, low.** When the route's `activation` events without `via` show no caller for 30 days, remove the route, its gateway path, `LicenseActivate` (`backend/app/models.py:177-179`) and the fixture together, keeping `license_activate_response.json`, which the seat routes still answer in. Acceptance (6.5 Verify): the wire-contract suite passes without the request fixture, and `backend/tests/test_gateway_parity.py` passes without the path |
| TD-53 | App / lab | [REAL_WORLD_VALIDATION.md](../app/REAL_WORLD_VALIDATION.md) predates #140's maths. The steel case's E is now refitted over 10–40 % of Rp0.2 (`ElasticModulus.WINDOW_LOW` / `WINDOW_HIGH`, `report/ElasticModulus.kt:40-41`; `refit`, `:99`), which on the stored curve reads R² 0.978 and is refused (`docs/app/REAL_WORLD_VALIDATION.md:194-198`), so a fresh run could land either side of 0.995. Bending strain is now the outer-fibre slope across the tapped thickness (`BeamDeflection.Probe.outerFibreStrainMilli`, `report/BeamDeflection.kt:115-145`; along the beam since TD-239), not the region mean the PMMA case was read with (Case 2, `:252-341`). Neither case has been re-run on engine 0.4.2, and the PMMA outer-fibre strain has not been compared with the dataset authors' | 3 | 2 | 2 | **20** | Open (found 2026-10-19, review M5/M6). Re-run Case 1 and Case 2 by the doc's own steps (`:207`, `:432`) on a device; record E (first pass and refit), Rp0.2 and the bending ε–σ slope against the authors' strain; update `RealSteelModulusTest` / `RealPmmaBendingTest` if the stored curves change |
| TD-54 | App / CI | The unit-test JVM sometimes exits part-way with no failing test: Gradle says only "Process 'Gradle Test Executor 1' finished with non-zero exit value 1" and the suite stops early (171-238 of ~385 result files written). Seen 2026-10-19 on #453 (`firebase-bom` 34.19.0 → 35.0.2, `gradle/libs.versions.toml:11`) in 2 of its 3 Tier 1 runs, before and after `main` was merged into it, and once on material_testing (full_ci on `dd47f985`); `main` at the same code without the bump passed every Tier 1 that day. TD-187 had the same message from a full heap and raised the worker to 2 GB (`app/build.gradle.kts:317`), but nothing in these logs names a cause. Diagnostics added: the worker writes a heap dump on an OutOfMemoryError, its error file on a crash and a GC log into `app/build/test-crash/` (`app/build.gradle.kts`, `test.jvmArgs`), and Tier 1 prints them on a failure with the last test classes to finish and the end of the worker's output (`scripts/ci_test_crash_report.py`, step "Report what ended the unit-test JVM" in `.github/workflows/ci.yml`) | 3 | 2 | 2 | **20** | Open, watch (carried from semperdic-app on 2026-10-21; this repo's Tier 1 runs the same diagnostics). The diagnostics merged in #459 (`eeaaeb5`); #453 then ran three Tier 1s in a row on `77ebc10` (its PR run and full_ci runs 39326760336 and 39329773513) with no worker exit and merged (`9992576`), so the cause was not observed. Seen again 2026-10-22 on semperdic-app #471 (run 39771568675, head `9f6b97d7`), the first since the diagnostics: no `hs_err` file and no heap dump, and the GC log never passes 850 MB of a 1096 MB committed heap (2 GB max), so neither a crash nor an OutOfMemoryError ended the worker: something called `System.exit`/`halt` or killed it. It stopped after `ui.analysis.run.RunDoneMomentTest` (248 of 367 result files, 1997 tests, none failing); the next classes, `RunEndTest` and `RunRecordSaveTest`, pass alone. Its `main` passed Tier 1 at `27652ce3` and `56ec4963` the same hour. Next: a `Runtime` shutdown hook in the worker that prints every thread's stack, to name a `System.exit` caller (it does not run on `halt` or a SIGKILL, which would then be the answer). Close it unwatched if 20 more Tier 1 runs on `main` pass without one. **Reproducible since 2026-10-26:** with two test workers (`-PunitTestForks=2`, `app/build.gradle.kts`), one worker ended this way in 3 of 3 local runs on Windows (`claude/ci-faster-benchmarks`, one run with JaCoCo off), each time after every class had written its result (404 of 404) and with no `hs_err`, no heap dump, the GC logs under 400 MB; one worker passed the same suite. So the suite stays on one worker (it would save ~2 min of Tier 1), and two workers are now a way to make the exit happen on demand. The worker's captured output is `output-events.bin` from Gradle 9.8, which `ci_test_crash_report.py` now reads as well as `output.bin` (it read nothing before) |
| TD-55 | Legal / privacy | **Framework 8.4 (policy matches the Data safety answers).** The Privacy Policy's subprocessor row names Firebase Auth, Firestore, Drive, Crashlytics and Cloud Logging but not Firebase Analytics or App Check / Play Integrity (`docs/legal/PRIVACY_POLICY.md:19`), though §2.4 (`:56`) describes Analytics; and nothing in it says Analytics derives an approximate location from the IP, which the Data safety answers declare ([PLAY_DATA_SAFETY.md](../legal/PLAY_DATA_SAFETY.md), Step 2). Play reviews the form against the linked policy | 2 | 2 | 1 | **20** | Open. Name Analytics and App Check / Play Integrity in §1 and add the IP-derived approximate location (with consent only) to §2.4, then regenerate and deploy Hosting; the policy's owner makes the edit |
| TD-56 | Backend | The rename of Material Testing to Semper Classroom ([ADR-030](../adr/ADR-030-semper-classroom-app-identity.md)) keeps the old names as the stored truth, because a backend from before the rename knows only them: the short name `materialtesting` stored on `devices/{id}.app` and `sessions/{sid}.app` (`apps.stored_name`, `_STORED_NAMES`; written at `backend/app/repo/devices.py:171` and `backend/app/repo/sessions.py:537`, and by `backend/scripts/tag_session_apps.py`) and read as `semperclassroom` (`apps.LEGACY_MATERIAL_TESTING`, `_LEGACY_NAMES`, `canonical`, `from_name`); the old id `com.sempermechanics.materialtesting` mapped to the app (`apps._BY_APPLICATION_ID`), sent the old token claim (`_TOKEN_CLAIM_BY_APPLICATION_ID`) and retired (`RETIRED`); the `MaterialTesting` fields as the only ones read (`apps.read_field`, through `_LEGACY_SUFFIX` / `legacy_field`) and as the mirror in `written_fields`, and so in `spread`; the roster CSV column `deviceIdLockMaterialTesting` (`roster_io.ROSTER_COLUMNS`); the account console's `?app=materialtesting` (`firebase-hosting/public/console/account/account.js`, the `unbindMt` handler); and the `*_MATERIAL_TESTING` release gate fallback (`_env_int_renamed`, `backend/app/config.py:18`, used at `:321-326`). Each is dead once no backend from before the rename can serve and no installed build sends the old id | 1 | 2 | 2 | **12** | Open. Order: (1) once no pre-rename backend can serve (none deployed and rollback past the rename ruled out) and the access log shows no `com.sempermechanics.materialtesting` call for 30 days (`gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="semper-api" AND jsonPayload.appId="com.sempermechanics.materialtesting"' --freshness=30d --limit=20`), run `backend/scripts/rename_classroom_app.py --apply`, then `--apply --retag` (owner: staging shares production Firestore), and a dry run with `--retag` finds nothing to copy, prune or retag; (2) flip `apps.read_field` to the new field and `apps.stored_name` to the new name, and send `?app=semperclassroom` from the account console; (3) stop the mirrors in `written_fields` (and so `spread`), move the roster column to `deviceIdLockSemperClassroom`, and drop the alias, the old id and the `*_MATERIAL_TESTING` env fallback; (4) delete the stored `*MaterialTesting` fields with a migration. Audit rows keep `materialtesting`. Acceptance: `grep -rn -i materialtesting backend/app` finds only comments |
| TD-58 | Engine / docs | `engine/examples/samples/oht_cfrp/expected.json` says `"image_size": [512, 512]` (`:5`) and suggests `rect` `[40, 40, 432, 432]` (`:8`), but the images are 400 × 1040 px, so the rect runs past the right edge; DICe's own grid covers x 61–341, y 61–971 (`DICe_solution_01.txt`). The app's bundled copy carries its own corrected manifest (`app/src/main/assets/examples/dice_oht_cfrp/example.json`, ROI `[40, 40, 320, 950]`) | 1 | 1 | 1 | **3** | Open; fixed upstream by [semper-dic-engine#26](https://github.com/sempermechanics/semper-dic-engine/pull/26) (`image_size` [400, 1040], rect `[40, 40, 320, 950]` as `[x, y, w, h]`, the app's ROI), not yet merged; closes with the pin bump that brings it. Acceptance: `image_size` is `[400, 1040]` and the rect lies inside the image |
| TD-59 | Ops / Play | **Play App Signing key for Semper Classroom.** No Play app exists yet. Semper Classroom is signed with the release key of Material Testing (alias `materialtesting`; [ADR-030](../adr/ADR-030-semper-classroom-app-identity.md), Decision), and three things rest on that certificate: Android gives both apps the same `ANDROID_ID`, so the phone is already the account's Semper Classroom device (ADR-030, Consequences); `firebase-hosting/public/.well-known/assetlinks.json` lists its SHA-256, which `release.yml` checks; and the Semper Classroom Firebase Android app is registered with its SHA-1 (`app/google-services.json`). If Play App Signing generates a new key, the Play build has another certificate: a different device id (a single-phone account meets `device_conflict`), App Links and the Firebase SHA to redo, and sideloaded Semper Classroom builds from GitHub Releases cannot update from Play (uninstalling deletes their on-device analyses). Material Testing installs never upgrade to Semper Classroom, whichever key: a new package is a new app | 3 | 3 | 1 | **30** | **Operator task**, at Play app creation, before the first upload. Choose "use existing app signing key" and upload the release key (alias `materialtesting`) with Play's PEPK tool; keep a separate upload key. Tell testers of sideloaded Semper Classroom builds that the Play build updates them in place. Acceptance: the app-signing SHA-256 on Play Console's App integrity page equals the one in `assetlinks.json`, and a GitHub-installed build updates from the internal track without an uninstall ([PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md#google-play-submission-semper-classroom)) |
| TD-60 | Ops / Play | **Reviewer access for the Play listing.** A new account starts PENDING unless `AUTO_APPROVE` or a verified `AUTO_APPROVE_HD` domain applies (`backend/app/repo/users.py:65-79`, `:267`), and every first sign-in meets the Terms screen before Pending or Home, so a reviewer's fresh sign-up can stall, which Play rejects as inaccessible functionality. A real account also holds one phone per app (ADR-010), which the pre-launch report's several devices would break. An [ADR-027](../adr/ADR-027-test-accounts.md) test account fits both: staff mint it in `TEST_ACCOUNT_DOMAINS`, already APPROVED, with a licence attached and up to `maxDevices` (1..10) phones per app; a `ci` account is perpetual and gives up its oldest phone when full, where a QC account expires. Nothing for App access is written yet | 2 | 2 | 1 | **20** | **Operator task**, in the submission. Create a `ci` test account from the staff console and accept the Terms on it once; put its address and password in Play Console's App access section (never in the repo), with sample inputs so an analysis runs without a specimen: a speckle reference and deformed pair, a tensile clip and its load log from the sets in [REAL_WORLD_VALIDATION.md](../app/REAL_WORLD_VALIDATION.md), hosted where the reviewer can download them. Acceptance: on an internal-track build, a fresh install signed in with that account reaches Home and completes one analysis from those inputs |
| TD-258 | App / tests | Instrumented tests that fail on API 30 (minSdk), where CI's Tier 3 runs API 34 only: `imaging/video/VideoFrameExtractionDeviceTest` ×4 (`keyframeModeExtractsEachIFrame`, `keyframeModeOnAnIndexedH264AviExtractsEachIFrame`, `keyframeModeOnTheRetrieverRungExtractsEachIFrame`, `portraitClipComesOutUpright`: `expected:<[0, 30, 60]> but was:<[0, 30, 60, 89]>`, or every 6th frame) on the `Semper_API30` AVD, 2026-10-29 (78/83 there before TD-276's fix, 79/83 after; 83/83 on API 36.1). semperdic-app carries the same row (its TD-328) | 2 | 3 | 2 | **20** | Open, shared code: fix lands upstream first (FORK_SYNC). Decide whether API 30's keyframe extraction (an extra or every-6th frame) is the app's bug or the platform's, fix or bound the assertion per API level with a reason, and run Tier 3 (or a weekly job) on an API 30 image too. This app's `LabWorkflowDeviceTest` pinch failure from the same run is fixed (TD-276) |
| TD-276 | Tests | `LabWorkflowDeviceTest.theViewerScrubsZoomsPansAndProbes` checks pinch-close only against the pinch floor (`app/src/androidTest/java/com/sempermechanics/semper/e2e/LabWorkflowDeviceTest.kt:195-204`; `TouchImageView.pinchFloorScale`, `ui/viewer/inspect/TouchImageView.kt:411`). On `Semper_Smoke` (API 36.1, 1080x2400, image view edge to edge) UiAutomator's `pinchClose` leaves the scale at 10.0 after `pinchOpen` reached it, slow or fast, before or after the probe tap; on `Semper_API30` the same calls zoom out to the floor (5.4; rest 6.75). Not known whether the app or the injected gesture is at fault, so the test does not assert a zoom-out | 1 | 2 | 2 | **8** | Open (sync of semperdic-app `b1c15346`, 2026-10-29). Check by hand on an API 36 phone: pinch out of a zoomed lab result. If it zooms out, the harness is at fault; add `closed < zoomed` on the API levels where the injection works |

## Deferred by design

| Item | Why |
|---|---|
| Auth-gated UI E2E | `FirebaseAuthIntegrationTest` signs the CI test account in on Tier 3 (TD-188), but through the SDK: no test drives the sign-in screens against real Firebase, and one that did would need the emulator's keyboard and the email-link flow's mailbox |
| `StartupTimingMetric` benchmarks on an API 37 emulator | `startActivityAndWait` confirms launches through `dumpsys gfxinfo framestats`, which is empty there for every activity. `StartupBenchmark` / `ScreenBenchmark` need a phone or an older image (a Pixel 6 on API 37 works: [perf/startup.md](../perf/startup.md)); `ViewerScrubBenchmark` avoids the API and runs |
| Float16 / ZNSSD quantisation for field data | Would change reported numbers; rejected under the bit-exactness requirement |
| In-memory X/Y compaction (derive coordinates from the grid) | Lossless and worth about 25 %, but a larger change that also touches the native writer: the `.dat` stores X and Y for every point (`field/DicResult.kt:20-24`) |

## Checked by hand, not from the repository

Branch protection requiring `CI OK`; API-key restrictions (owned by TD-17); GitHub
Environment branch rules on `production` (none; since 2026-10-22 each production deploy
waits for the owner's approval, which closed TD-198 in semperdic-app). The open checkboxes are in
[PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md).

## Perf and quality gates (do not loosen)

See [../../engine/docs/PERF_BASELINE_bd44af0.md](../../engine/docs/PERF_BASELINE_bd44af0.md):
≥ 4557 solves/s on the host, the smoke and DICe floors, and `-O3 -ffast-math` / OpenMP /
LTO kept on the release pipeline. **No CI job in this repository enforces the
solve-rate floor:** it is checked by hand in the engine repo when the `engine` pin
moves. `koverVerify` (lines 71, branches 57, `app/build.gradle.kts`; measured 73.93 / 59.40 %
on 2026-10-14) runs in CI tier 1, `ciReleaseGate` and the release workflow.

The CI benchmark job ("Benchmarks (macro + micro)") is emulator **smoke**, with no
numeric thresholds. Macrobenchmark suppresses `EMULATOR,LOW-BATTERY,UNLOCKED`.
`HotPathMicroBenchmark` suppresses
`EMULATOR,DEBUGGABLE,LOW-BATTERY,UNLOCKED,ACTIVITY-MISSING,NOT-AOT-COMPILED`
(`.github/workflows/ci.yml:917`, `:919`). Keep API 34: API 37's
`dumpsys gfxinfo framestats` is empty. Run it with the `run_benchmark` dispatch
input or the `benchmark` label.

# ADR-027: Staff test accounts for QC and CI

**Status:** Accepted (the product owner's decisions, 2026-10-19). Backend, console and
app merged in #460 (2026-10-20) and deployed 2026-10-22 from `b940cc29`, on for
`TEST_ACCOUNT_DOMAINS=qa.sempermechanics.com`
([CHANGELOG](../ops/CHANGELOG.md)). Amended 2026-10-22 (product owner): a full
CI account gives up its oldest phone instead of refusing a new one (TD-313).
**Date:** 2026-10-19
**Deciders:** product owner, backend owner
**Register:** TD-314 (a QC account's phone cannot be released on its own)

Paths are from `backend/app/` unless they start at the repository root.

## Context

QC staff test release builds on several phones at once, and CI's instrumented
tests sign in with a Firebase account. Neither fits a real account:

- **One phone per app.** A real account holds one ACTIVE device per app
  (`repo/devices.py`, `register_device`: `device_conflict` for a second one), its
  signed requests must come from the device its `activeDeviceId` names
  (`deps._device_is_bound`, ADR-010 device trust), and an individual licence is
  locked to the first phone that signs in (`repo/devlock.py`). A QC bench of
  three phones needed three accounts, three licences and a staff release
  between rounds.
- **The release hold and the device mail.** A released phone is held off for
  `DEVICE_RELEASE_HOLD_HOURS` (`repo.released_device_held`), and a new device
  mails the holder (`notify.device_registered`, from `routers/devices.py`). On a
  QC bench both are noise.
- **Their data is mixed with customers'.** Uploads land beside customers' in
  `Research Storage/user/{uid}` (`drive.ensure_session_folders`), and their
  analyses count toward each phone's demo cap (`repo.count_device_sessions`), so
  a QC phone handed to someone on demo arrived already full.
- **Making one was manual.** Staff signed up an address, approved it, minted a
  licence for it and remembered to delete it. The CI login
  (`scripts/create_ci_firebase_account.sh`) is a Firebase user only: no users
  doc and no licence, so it cannot call the backend.

## Decision

The product owner's decisions, 2026-10-19:

1. **Who.** Internal QC staff and automated CI. External verifiers are out of
   scope.
2. **Sign-in.** Staff create the login from the console: an address and a
   generated password. The account is pre-approved and licensed, and the
   password is shown once.
3. **Devices.** A test account may be active on up to N phones per app at once
   (staff set N: default 3, at most 10). No release hold and no new-device mail.
   A test account is never an admin.
4. **Data.** The account is tagged and ends. Uploads go to a separate Drive
   folder; the tag reaches analytics and audit; audit rows are kept as for every
   account; test accounts are left out of the demo counts; after the end a
   staff job suspends the account and erases its data.

How the backend carries them out:

- **One tag, one test.** `users/{uid}.testAccount` is
  `{purpose: "qc"|"ci", createdByUid, createdAt, expiresAt (null only for ci),
  maxDevices (1..10), note (<= 200)}`, absent on every real account. Every
  branch that treats a test account differently asks `repo.test_account_of(user)`
  (`repo/_base.py`), and a real account, for which it answers None, takes the
  path it took before. Only `repo/testaccounts.py` writes the tag.
- **Safety rails.** Create is refused 403 `test_accounts_disabled` unless the
  address's domain is in `TEST_ACCOUNT_DOMAINS` (exact match; empty by default,
  which turns the feature off), so a test account can never be made on a
  customer's address. It is refused 409 `email_in_use` for an `ADMIN_EMAILS`
  address (so `role` stays "user" and staff device trust, `stepUpEligible`, is
  untouched) and for one that has a users doc, a Firebase Auth user, a licence
  or an invite. Create, extend, a new password and end take the fresh staff
  step-up (`attested_or_mfa_admin_fresh`); the list is an admin read.
- **Create** (`services/testaccounts.py`, `create`): the Firebase Auth user
  (address, a 20-character `token_urlsafe(15)` password, `emailVerified`), then
  the users doc as APPROVED with its sign-in link, then an individual licence
  minted against the address with `testAccount: true` and the note, timed to
  `expiresAt` with no grace, or perpetual for a CI account with no end. The mint
  attaches it to the account at once (`_attach_to_existing_holder`); a lost race
  leaves the invite, which the first sign-in claims. A failure after the Auth
  user exists removes the users doc and the Auth user. The password is in the
  201 answer (`Cache-Control: no-store`) and the Auth project only: no log line,
  audit row or exception names it.
- **Devices.** Registration admits a test account while it has fewer than
  `maxDevices` ACTIVE devices for the app, counted inside the registration
  transaction after the user document is read (every registration writes that
  document, so two phones at once retry and count each other);
  `activeDeviceId` names the latest. `verified_device` accepts any ACTIVE device
  of the account for the asking app; the app check after it is unchanged. The
  licence lock (`devlock._test_lock_verdict`) binds nothing: a device ACTIVE for
  the account and app is licensed, so is an unregistered phone while there is
  room (as an unbound lock lets a first phone through), anything else is Demo
  for that request only. The router skips the release hold and the device mail
  for a test account.
- **CI rotates** (amendment, 2026-10-22). Every CI run is a fresh emulator with
  a new key, so a `ci` account would fill its `maxDevices` in a few runs and
  then fail every one. When a `ci` account is full, the same transaction marks
  its oldest ACTIVE devices for the app SUPERSEDED (by `registeredAt`, a missing
  one first; `repo/devices.py`, `_oldest_active_devices`) and admits the new one;
  the router audits each as `DEVICE_ROTATED` with `{app, by, testAccount}`. A
  `qc` account is still refused `device_conflict`, and no other account or app
  is touched.
- **Separation.** A test account's sessions keep their device as
  `testDeviceId` and carry `testAccount: true`, so `count_device_sessions`, which
  matches on `deviceId`, does not count them; they still count toward the
  account's own cap. Uploads go under `Research Storage/_test/{uid}`
  (`drive.TEST_USERS_FOLDER`), and the erasure's fallback looks there. The licence
  desk hides `testAccount` licences unless `include_test=true`. Audit rows for the
  staff actions (`test_account_created`, `_extended`, `_password_reset`,
  `_ended`, `_expired`), the account's device registrations and its session
  creates carry `testAccount: true`. `GET /v1/config` carries
  `testAccount: {purpose, expiresAt}` (null for a real account), which the app
  uses for its analytics user property and Crashlytics key, under the existing
  consent.
- **End.** `DELETE /v1/admin/test-accounts/{uid}` suspends the account, revokes
  every sign-in's refresh tokens, revokes its test licences (so the erasure's
  `_give_back_license` does not re-promise them to the address), then runs the
  holder erasure's own steps (`services/account.erase_data`: Drive, Firestore,
  Auth users) and audits `test_account_ended` as staff. `POST
  /v1/tasks/test-account-expiry`, called daily by Cloud Scheduler with the
  invoker's OIDC token and kept off the gateway like the Cloud Tasks callbacks,
  ends every account whose `expiresAt` has passed as `test_account_expired`, and
  answers `{expiredTestAccounts, ended, failed}`.
- **No new collection.** The tag lives on `users`, the licence flag on
  `licenses`, the session fields on `sessions`; no Firestore rule or rules-test
  collection changes. The two new queries are single-field (`testAccount.purpose
  in`, `testAccount.expiresAt <`), served by the automatic indexes.

## Options

| Option | Why not |
|--------|---------|
| A `test_accounts` collection beside `users` | Every request path reads `users/{uid}` already; a second document would be a second read on each, and a new collection for the rules and their tests |
| Raise the device limit for anyone staff choose (a per-account `maxDevices` without a tag) | Lets a customer's licence run on several phones; the tag is what keeps it to logins staff made on a company domain |
| Let staff create the Firebase user by hand, then approve and mint as today | Three steps, an address that can be a customer's, and nothing that ends it |
| A shared QC login on one account | One person's sign-out signs everyone out; an audit row cannot say who; a leak means rotating it for everybody |
| Exclude test accounts from the demo count with a second count query | One more query on every demo upload of every real account; keeping the device under another field costs a real account nothing |
| Firebase Auth custom claims for the tag | The tag must be readable where the users doc is already read, and staff change `expiresAt` without forcing a token refresh |

## Trade-offs

- **The password crosses the console once.** It is generated server-side and
  shown once; staff hand it over. A password manager share is safer than chat,
  and the manual says so; the backend cannot enforce it.
- **More phones per login.** A leaked test password reaches up to `maxDevices`
  phones per app instead of one. It is bounded by the domain rail (only company
  addresses), the end date, **New password** (which revokes refresh tokens) and
  End. Any ACTIVE device of a test account signs, so the ADR-010 rule that only
  the bound device signs does not hold for test accounts; their ACTIVE set is
  what registration bounds.
- **A phone not yet registered is licensed while there is room.** For a config
  call only, as an unbound licence already is for a real account's first phone;
  every signed route still needs a registered device.
- **No per-phone release.** A QC phone beyond `maxDevices` is refused at
  registration. Freeing a place is End and create again (TD-314); a staff
  release of a test account's phones was not part of the decision.
- **A CI phone can be retired under a running job.** Two CI runs at once on a
  full account can each retire the other's phone; the loser's next signed call
  answers `device_not_active`. Tier 3 runs one emulator per workflow run, and
  the round trip registers right before it uploads, so the window is the
  length of one test.
- **The listing filters test licences after the page is read.** Firestore
  cannot select "field absent" without dropping every licence that lacks it,
  which is every real one, so a page of the desk can hold fewer rows than asked
  for, with more to come.
- **Extend sets the date.** `days` is from now, so a smaller number ends the
  account sooner; that is staff's call.

## Consequences

- Deploy: `TEST_ACCOUNT_DOMAINS` (repo variable, passed by `deploy-backend.yml`),
  `roles/firebaseauth.admin` for the runtime SA on the Auth project (as TD-214),
  and the Cloud Scheduler job
  ([DEPLOY_CHECKLIST_2026-10.md](../ops/DEPLOY_CHECKLIST_2026-10.md)). Without the
  job nothing is erased at the end, but the licence has lapsed and the staff
  list shows `expired`.
- The CI login becomes a `purpose: "ci"` test account
  (`scripts/create_ci_firebase_account.sh --store`, [OPERATING_MANUAL.md](../OPERATING_MANUAL.md)
  Appendix E), so it has an account and a licence. Tier 3 signs it in against
  staging (`StagingRoundTripDeviceTest`: config, register, one small backup,
  delete) when given `STAGING_API_BASE_URL` (repo variable) and the
  `STAGING_TEST_EMAIL` / `STAGING_TEST_PASSWORD` secrets, and skips otherwise.
- `contracts/config_response.json` carries `testAccount`; the app's DTO reads it.
- Routes: [ADMIN_API.md](../backend/ADMIN_API.md); the gateway declares the five
  admin routes and not the task.

## Action items

- [x] Backend: tag, routes, expiry job, device, licence, Drive and demo-count
      branches; `backend/tests/test_test_accounts.py`.
- [x] Console and app (#460).
- [x] Deploy (2026-10-22): `TEST_ACCOUNT_DOMAINS=qa.sempermechanics.com`, the Auth admin
      grant for `indic-api@`, and the Scheduler jobs `semper-test-account-expiry` and
      `semper-test-account-expiry-staging` (daily 03:17 UTC; a manual run of each succeeded).
- [ ] On staging: a QC account (`days: 1`) on two phones, its upload under
      `Research Storage/_test/{uid}`, then End
      ([checklist](../ops/DEPLOY_CHECKLIST_2026-10.md) §6). Done 2026-10-22 but
      the End (a 7-day account on two emulators, one backup).
- [x] TD-313: Tier 3 against staging with the CI test account, and CI rotation
      (`StagingRoundTripDeviceTest` passed on an emulator against staging,
      2026-10-22; the rotation needs the backend deployed before CI runs it often).

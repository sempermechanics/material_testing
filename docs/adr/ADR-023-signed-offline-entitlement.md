# ADR-023: A signed, time-limited entitlement the app verifies offline

**Status:** Accepted, built (backend and app); not yet live: no signing key is provisioned (TD-215)
**Date:** 2026-10-18
**Deciders:** app owner
**Framework items:** 4.1, 4.3, 3.5, 6.4; Part B ([READINESS_FRAMEWORK_2026-10.md](../ops/READINESS_FRAMEWORK_2026-10.md))

> **ADR-024 (2026-10-18).** [ADR-024](ADR-024-account-anchored-entitlements.md)
> anchors paid entitlements to the account and removes the in-app key entry.
> It supersedes nothing in this record: the token never depended on a key being
> redeemed, only on `/v1/config` (decision 3). ADR-024 adds two fetches to
> decision 3's triggers: a first sign-in asks `/v1/config` again once the device
> is registered, before Home opens (`EntitlementRefresh.afterSignIn`,
> `app/src/main/java/com/sempermechanics/semper/data/account/entitlement/EntitlementRefresh.kt:81`),
> and "Refresh licence" asks on demand. Share has no backend route, so no server
> check decides it: the app shares when the cached `/v1/config` licenses it, and
> a token can only narrow that (decision 7). With no token (none is issued until
> a signing key exists, TD-215) or one that does not verify, share and the
> offline licence rest on the TLS-delivered cache alone (TD-274).

## Context

Analysis is offline; licensing was not. The app cached the last `/v1/config`
answer and trusted it indefinitely while offline: an account that stopped
reaching the backend kept whatever mode and features it last saw, with no
bound in time and nothing to stop an edited cache. Revocation reached a device
only at its next `/v1/config` (`LicenseConfigWorker`, every 4 hours,
`app/src/main/java/com/sempermechanics/semper/data/LicenseConfigWorker.kt:82`),
and a device that never came back online never learned of it.

The readiness framework asks for a server-signed entitlement with a pinned
public key, rotation by `kid`, a bounded offline grace and clock-rollback
detection (items 4.1, 4.3; Part B). It names Ed25519 and Cloud Functions;
Semper's backend is FastAPI on Cloud Run, Firestore is reached only through the
Admin SDK behind deny-all rules (`firestore.rules:7-9`), and the app's floor is
Android 11 (minSdk 30, `app/build.gradle.kts:130`).

## Decision

1. **ES256, not Ed25519.** The token is a compact JWS signed with ECDSA P-256 /
   SHA-256. Android's JCA provides Ed25519 only from API 33, above the minSdk
   of 30; ES256 verifies with the platform's own `Signature("SHA256withECDSA")`
   on every supported version and needs no new dependency on either side (the
   backend already pins `cryptography`).
2. **The contract** (`backend/app/entitlement_token.py:13-51`, the app's
   `data/account/entitlement/EntitlementVerifier.kt:53`):
   - header `{"alg":"ES256","kid":"<kid>","typ":"semper-ent+jws"}`; signature
     JOSE raw `r‖s`, 64 bytes, not DER (`entitlement_token.py:123-132`);
   - claims `iss` = `semper-api`, `sub` (uid), `app`, `dev` (device id), `dkt`
     (base64url SHA-256 of the device key's DER SubjectPublicKeyInfo,
     `:110-119`), `mode`, `features` {`share`, `backup`, `maxAnalyses`}, `lic`,
     `iat`, `refreshAfter` = `iat` + 24 h, `graceUntil`, `exp` = `graceUntil`
     (`build_claims`, `:218-250`);
   - `graceUntil` is `iat` + `ENTITLEMENT_OFFLINE_GRACE_DAYS` (default 14,
     `backend/app/config.py:306`), capped for a licensed account at the
     licence's end plus its own grace (`licenseGraceEndsAt`) and, on a floating
     seat, at the lease's end (`leaseExpiresAt`) (`grace_until`, `entitlement_token.py:201-216`).
     The default 14 is pinned by
     `test_the_offline_grace_and_the_licence_grace_default_to_14_days`
     (`backend/tests/test_release_gate_and_events.py`), which also tells it from
     `LICENSE_GRACE_DAYS_DEFAULT`, the licence's own grace past expiry, also 14;
   - `lic` is the licence id only while `mode` is `licensed`; a demo token
     carries `lic: null` and an uncapped grace (`:245`);
   - `mode` and `features` are read from the same resolved config the plain
     `/v1/config` JSON returns, so the token never grants more than the answer it
     rides in (`app_config`, `backend/app/routers/account.py:157-189`).
3. **Delivery.** `GET /v1/config` adds `entitlement` when the service holds a
   key, the caller sent `X-Device-Id`, and that device is an active device of
   this account with a registered key (`entitlement_token.py:277-310`). Anything
   else omits the field and fails nothing (`routers/account.py:182-206`). After
   each floating-seat checkout or heartbeat the app fetches `/v1/config` again,
   so the token follows the lease; when that fetch fails it queues the one-off
   refresh, unique work `license-config-now`, expedited and retried with backoff
   up to six attempts, about 15 minutes (`SeatLease.refreshConfigAfterCheckout`,
   `app/src/main/java/com/sempermechanics/semper/data/account/SeatLease.kt:79-87`;
   `LicenseConfigWorker.refreshNow`, `data/LicenseConfigWorker.kt:106-117`;
   `EntitlementRefresh.MAX_ATTEMPTS`, `data/account/entitlement/EntitlementRefresh.kt:48`).
   The same refresh is queued at launch and on each return to the foreground
   when the stored token is past `refreshAfter` or has lapsed
   (`LicenseConfigWorker.foregroundCheck`, an `AppForeground` listener, `:121-139`;
   `EntitlementRefresh.refreshDue`, `data/account/entitlement/EntitlementRefresh.kt:57`), so `refreshAfter` is a server-side lever; the 4-hour periodic
   refresh stays (TD-264, TD-266, closed 2026-10-18).
4. **The key.** A P-256 private key in Secret Manager, mounted as
   `ENTITLEMENT_SIGNING_KEY` only when the repo variable
   `ENTITLEMENT_SIGNING_SECRET` names a secret that exists
   (`.github/workflows/deploy-backend.yml:266-286`), never a plain env value or
   the repository. Its `kid` is `ENTITLEMENT_KID` (default `ent-1`,
   `config.py:302`). Setup: [BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) A8.
5. **Pinning and rotation by `kid`.** Release builds pin public keys from
   `SEMPER_ENTITLEMENT_PUBKEYS`, `kid:base64SPKI` entries separated by commas
   (`app/build.gradle.kts:88-93`; parsed by `EntitlementPins.parse`,
   `data/account/entitlement/EntitlementPins.kt:32`). Rotation: ship a build
   pinning old and new `kid`, wait for adoption, switch `ENTITLEMENT_KID` and the
   secret, then drop the old `kid` from a later build. A build that pins no key
   ignores tokens entirely (`EntitlementPolicy.intake`, `EntitlementPolicy.kt:151`); so does a
   build whose pins do not include the token's `kid` (decision 7).
6. **Offline checks on the device.** `alg`, `typ`, the signature against the
   key pinned for its `kid`, then `iss`, `app`, `dev`, and `dkt` against this
   install's Keystore key (`EntitlementVerifier.verify`, `EntitlementVerifier.kt:64-94`);
   then time
   (`EntitlementClock.evaluate`, `EntitlementClock.kt:140`):
   - effective time is the later of "last server time + wall-clock movement" and
     "last server time + accumulated `elapsedRealtime`", so a wall clock set back
     while the phone runs gains nothing. Across a power-off only the wall clock
     can count the time the phone was off: see Trade-offs for the one case it
     does not (readiness TE4-102);
   - a wall clock more than 10 minutes behind the highest seen
     (`ROLLBACK_TOLERANCE_MS`, `:89`) ends grace until the next server time;
   - the token and clock state are stored together, sealed with an HMAC under a
     Keystore key (`SealedEntitlement`, `EntitlementStore`), and every write is
     `commit()`, on disk before anything relies on it, except the clock's
     once-a-minute write over a record already beside its key, which is
     `apply()` so a main-thread gate check does not wait on the disk
     (`SealedRecordStore.update`, readiness TE1-006). The write order is
     `SealedRecordStore`'s (`data/account/entitlement/SealedRecordStore.kt`): a
     first seal marks itself, makes the key, then writes the record and clears
     the mark in one commit; a clear deletes the key before the record. What
     the pieces on disk mean is `EntitlementPolicy.reading`
     (`EntitlementPolicy.kt:137-144`): a record whose MAC does not match, or a
     key with neither record nor first-seal mark (a deleted record), reads as
     **tampered**; a record with no key (a lost Keystore key, a clear cut
     short) and a key with the mark but no record (a crash mid-seal) read as
     **no token** and are tidied away, so neither drops a licensed user to Demo
     (TD-265, closed 2026-10-18). A record whose MAC matches but whose `v` is
     above this build's (a phone that went back a version) also reads as **no
     token**, and the next save reseals it; a format bump must keep reading v1,
     which `SealedEntitlementTest` pins with a committed record
     (`app/src/test/resources/entitlement/sealed_v1.txt`, readiness TE1-004).
7. **What narrows to Demo, and what is ignored.** Only four things drop the
   account to Demo entitlements (`TokenStatus.Lapsed`; `EntitlementPolicy.bound`,
   `EntitlementPolicy.kt:91-105`): a sealed record that was altered or deleted
   while its key stayed (decision 6), the end of `graceUntil`, a clock rollback,
   and `exp` passing. On a floating seat the last two mean the lease the token
   was issued under ran out unrenewed, so starting work opens the seat screen,
   which checks out a seat and fetches a fresh token, rather than applying
   demo's limits (`EntitlementPolicy.seatRequired`, `EntitlementPolicy.kt:126-129`;
   `LicenseEntitlements.isSeatRequiredToStart`, `data/account/LicenseEntitlements.kt:138-140`).
   While a token is lapsed the quota card and the limit screen show the cap
   enforced, not the server's `maxSessions` (`LicenseEntitlements.shownAnalysisCap`,
   `:224`; TD-263, closed 2026-10-18). A token that
   fails verification on the device (a `kid` this build does not pin, a bad
   signature, the wrong `app` or `dev` or `dkt`, or a device key that cannot be
   read) is treated as **no token** (`TokenStatus.NotUsed`,
   `EntitlementPolicy.kt:177-184`): the cached `/v1/config` decides, as before
   tokens. The reason: the stored token can only have come from the backend
   (the record is sealed on this device), so refusing it protects nothing that
   stripping the token would not give away equally, while lapsing it would drop
   every licensed user to Demo, online too, the moment the signing key and a
   build's pins disagree (a mis-provisioned key, a rotation shipped before its
   pin). Either way nothing is locked: local analyses stay readable and
   exportable, and the token only ever narrows the cached config, never widens it.
8. **Rollout-safe.** No key: no token, and every build behaves as before. A
   build without pins: tokens ignored. The grace length is the server-side lever
   for the fail-open / fail-closed policy the framework asks for in 6.4.

## Options considered

- **Ed25519 as the framework names.** Rejected for the API floor (decision 1).
  The token format would otherwise be the same.
- **A licensing vendor (Keygen, Cryptlex, LicenseSpring).** Rejected. Seats,
  invites, floating leases, reconciliation and the institution console already
  exist on Firestore ([CLOUD_ARCHITECTURE_GCP.md §20](../backend/CLOUD_ARCHITECTURE_GCP.md)),
  and the framework's own comparison notes that none of them supplies a
  tenant-scoped console for customer IT. A vendor would add a second source of
  truth for seats and a third party on the licence path for what remains: one
  signing function of about 300 lines.
- **Identity Platform multi-tenancy, one tenant per customer.** Rejected for
  now. Semper's tenant is the institution licence: its `adminEmails` scope
  every IT route (`institution_admin_context`, `backend/app/routers/institutions.py:36-57`),
  and cross-institution access answers 404. Its `domainLock` scopes whom IT may
  add (since 2026-10-28, readiness TE3-102, ADDENDUM-2): a single add or an
  import row off the domain is 422 `member_off_domain`, decided before the
  person is looked up, and an address another licence holds or promises is
  answered as an unknown one, a pending invite that is held and never claimed
  (`add_institution_member`, `hold_institution_invite`;
  `backend/app/repo/institution_admin.py`, `backend/app/repo/invites.py`), so
  IT's replies say nothing about other licences. Semper staff, on the same
  roster routes under `/v1/admin`, may add an external collaborator off the
  domain and are told `member_already_licensed`. Tenants would split one account
  across pools and change every sign-in path for no gain until a customer needs
  its own IdP; per-institution SSO is FI-17.
- **App Check replay protection on an activation function**
  (`consumeAppCheckToken`). Not applicable: it exists only for Cloud Functions
  written in Node.js, and Semper has no Functions. Request replay is already
  refused by single-use device-signed nonces (`consume_nonce`,
  `backend/app/repo/devices.py:133`; `backend/tests/test_device_auth.py:130`, `:172`).
- **Cloud KMS signing now.** Deferred (TD-236): it keeps the private key out of
  the process but adds a KMS call per issued token; Secret Manager with one
  accessor meets item 3.5's wording.

## Trade-offs

- An offline device keeps its entitlement until `graceUntil`: revocation is not
  instant for a device that does not come online. A floating seat cannot be
  reclaimed from an offline device before its lease ends; the cap at
  `leaseExpiresAt` bounds that.
- The clock rules are not tamper-proof on a rooted device. Deleting the HMAC
  key, with or without the sealed record, reads as "no token" and falls back to
  the cached config (TD-261, accepted). Since decision 6 reads a lost key as no
  token rather than tampering, deleting the key alone now does the same as
  deleting both; that is the price of not dropping a licensed user to Demo when
  the Keystore loses a key. `exp` and revocation at the next check-in are the
  server's controls.
- A token that does not verify is ignored rather than refused (decision 7), so
  an unverifiable token gives the same result as no token: the cached config,
  bounded by the server at the next online request.
- The private key is in the service's memory (TD-236).
- **Offline grace is 14 days of powered-on time for a user who sets the clock
  back across power-offs** (readiness TE4-102, 2026-10-28). `elapsedRealtime`
  restarts at boot, so a reboot counts the time since boot
  (`EntitlementClock.advance`, `EntitlementClock.kt:119-131`), and the
  rollback rule fires only when the wall clock is more than 10 minutes behind
  the highest seen (`:123`). A user who powers off and, before the next boot,
  sets the wall clock to about where it stood (the high-water mark) is
  credited with neither the time the phone was off nor a rollback: check in,
  run 1 day, power off 30 days, set the clock back to day 1, and the token
  still reads as within grace after 31 calendar days. The KDoc says so
  (`EntitlementClock.kt:78-80`). This is within decision 6's scope (casual
  clock changes made useless, not a rooted or determined user), and the
  server's controls stand: `exp`, and revocation at the next check-in. Not
  pinned by an app test yet (an `EntitlementClockTest` case, app stream).
  Hardening, once a key is live (TD-215): a shorter
  `ENTITLEMENT_OFFLINE_GRACE_DAYS`, or ending grace on a boot whose wall clock
  has not passed the previous boot's last reading.

## Consequences

- Backend: `backend/app/entitlement_token.py`, `routers/account.py` (`/v1/config`
  carries `entitlement`, `minSupportedVersionCode`, `recommendedVersionCode`,
  `killSwitches`); tests `backend/tests/test_entitlement_token.py` and the shared
  vector `backend/tests/fixtures/entitlement_vector.json`, which the app's
  `EntitlementVectorTest` also verifies.
- App: `data/account/entitlement/` (ten files since 2026-10-18, with
  `SealedRecordStore` and `EntitlementRefresh`); offline and tamper tests
  `EntitlementClockTest`, `EntitlementVerifierTest`, `EntitlementPolicyTest`,
  `SealedEntitlementTest`, `SealedRecordStoreTest`, `EntitlementRefreshTest` under `app/src/test/java/com/sempermechanics/semper/data/account/entitlement/`.
- minSdk 30: Android 7–10 phones can no longer install or update the app.
- Release gate per app: `/v1/config` sends each app its own
  `minSupportedVersionCode` / `recommendedVersionCode`
  (`settings.release_gate(app)`, `backend/app/config.py:328-335`), since Semper and
  Material Testing number their builds separately.

**Decision note (2026-10-18): account deletion and the shared Firebase user.**
`DELETE /v1/me` now also deletes the Firebase Auth user
(`backend/app/services/account.py:124`). Semper and Material Testing sign in to the
same Auth project, so this removes the sign-in for both apps. That matches the
erase itself, which is account-wide: it removes the sessions, files and devices of
both apps, and (since 2026-10-18, TD-243) the account's `auth_links` rows and the
address on every seat it held (`backend/app/repo/account.py:206-244`). The app's
delete confirmation says the sign-in goes for both apps
(`delete_account_body`, `app/src/main/res/values/strings.xml:321`). Recorded as intended, not as
debt.

## Action items

- [x] Backend signing, tests and the shared vector (2026-10-18).
- [x] App verification, grace, clock rollback and sealed state (2026-10-18).
- [x] First CI Tier 1 run on the branch compiles and passes the app side: CI run
      39158239853 on `ae4c24a` (Tier 1, 3, 4, 5 and rules). The follow-up branch's
      first run is still owed (TD-219).
- [ ] Provision the key and the pinned public key (TD-215).
- [ ] Later work: key attestation verified server-side, a Play Integrity verdict
      at activation and check-in, device recall (TD-234); anti-tamper detection
      with graded responses (TD-235); KMS signing (TD-236); device
      acknowledgement of revocation (TD-238).
- [x] Residuals left by the 2026-10-18 review, closed the same day on
      `claude/readiness-followups-jhfhj9`: quota screens while a token is
      lapsed (TD-263), `refreshAfter` unread (TD-264), a lost key or unflushed
      write reading as tampered (TD-265), floating-seat refresh after a
      heartbeat (TD-266). Their tests first run in that branch's Tier 1 (TD-219).

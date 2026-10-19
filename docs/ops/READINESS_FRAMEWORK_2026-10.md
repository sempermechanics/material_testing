> External reference document, dated 2026-10-18 and reproduced verbatim below this note. Semper's item-by-item mapping is the "Framework cross-check (2026-10-18)" section of [PRODUCTION_READINESS_GATE.md](PRODUCTION_READINESS_GATE.md).

# Semper Production-Readiness Framework: Android + Firebase/GCP, Hybrid Offline Licensing, Customer-IT Operability

Semper is production-ready only when four things have been shown with evidence, not assumed: (1) the server is the only authority for entitlements and the device verifies a signed, time-limited license offline; (2) Firestore rules, App Check and auth are enforced and tested in CI against the emulator; (3) customer IT can manage seats, users and devices themselves through a tenant-scoped admin console with audit logs, without opening a ticket; and (4) the developer team can stage, halt, gate and roll back releases and backend changes. This report turns those conditions into a checklist where every item has a verification method. It recommends building license issuance in-house on Firebase with Ed25519-signed entitlements, unless the team cannot staff ongoing security maintenance. In that case Keygen is the closest fit among the vendors reviewed.

## TL;DR

- **Readiness gate:** Use Google's Firebase launch checklist, the SRE Production Readiness Review model and OWASP MASVS v2 as the baseline. Add Semper-specific gates: emulator-tested security rules, enforced App Check, separate dev/staging/prod projects, Firestore PITR plus scheduled backups with a tested restore, budget alerts plus spend caps, and staged Play rollouts with Remote Config kill switches.
- **Licensing architecture:** First activation is online. After that the device holds a server-signed (Ed25519) entitlement token, bound to a hardware-backed Keystore key and checked against Play Integrity at activation and refresh. It has a bounded offline grace period, clock-rollback detection and server-side seat revocation that takes effect at the next check-in. Customer IT should hear plainly that revocation is not instant for offline devices.
- **Operator manageability:** Use Identity Platform multi-tenancy (one tenant per customer, SAML/OIDC per tenant) for SSO. Identity Platform has no documented native SCIM, so bulk CSV import and a custom SCIM endpoint have to be built. Add managed Google Play private apps plus managed configurations so customer IT can push tenant ID and enrollment config through their EMM. The console needs seat dashboards, device release/reassign and an append-only audit log.

## Key Findings

1. **Authoritative baselines exist and should be adopted, not reinvented.** Google's Firebase launch checklist asks teams to test all changes in the Firebase Local Emulator Suite, to "Start *enforcing* Firebase App Check for every service that supports it", to use different Firebase projects for development, testing and production, and to protect the production project with project liens and a "Prod" tag. [google](https://firebase.google.com/support/guides/launch-checklist) Google SRE's Production Readiness Review exists to "verify that a service meets accepted standards of production setup and operational readiness", and the SRE book's Launch Coordination Checklist (Appendix E) [sre](https://sre.google/sre-book/launch-checklist/) gives a template for the go/no-go review. [sre](https://sre.google/sre-book/evolving-sre-engagement-model/)
2. **Budget alerts do not stop spend.** The Firebase launch checklist says that "budget alerts are *not* budget *caps*". It strongly recommends spend caps for Cloud Functions and similar services, which "will pause the applicable service if your project reaches the budget set for that service." [google](https://firebase.google.com/support/guides/launch-checklist) A runaway Cloud Function or a leaked API key is a billing incident as well as a security one.
3. **Firestore recovery is good but has to be configured.** Point-in-time recovery (PITR) lets you read data from up to seven days in the past at one-minute granularity. Google Cloud's Firestore "Plan disaster recovery" guide documents PITR write-back with "a recovery time objective (maximum time for recovery) of 0" and states "The recovery point objective (maximum possible data loss) is 1 minute." Scheduled backups run daily or weekly, are kept for up to 14 weeks, and restore only to a new database in the same project. Neither is on by default.
4. **Client-side licensing checks are defense-in-depth, not security.** OWASP MASVS lists "Authorization Enforced Only Locally Instead of on the Server-side" as a weakness (MASWE-0042). [owasp](https://mas.owasp.org/MASVS/controls/MASVS-RESILIENCE-2/) OWASP says resilience controls (MASVS-RESILIENCE-1 to 4: platform integrity, anti-tampering, anti-static and anti-dynamic analysis) "provide additional protection" and that "All apps must also fulfill the rest of the OWASP MASVS controls". [owasp](https://mas.owasp.org/MASVS/11-MASVS-RESILIENCE/) The server must remain the source of truth for seats. On-device checks only raise the cost of bypassing them.
5. **Device identity has to be engineered.** Since Android 8.0, ANDROID_ID "is unique for each combination of app-signing key, user, and device" and changes "if the device is factory reset or if the signing key rotates". [android](https://developer.android.com/about/versions/oreo/android-8.0-changes) [googleblog](https://android-developers.googleblog.com/2017/04/changes-to-device-identifiers-in.html) It is fine as a soft hint but not as a binding anchor. Hardware-backed key attestation produces a certificate chain that must be verified "on a separate server that you trust". [googlesource](https://android.googlesource.com/platform/frameworks/base/+/0da25a6/docs/html/preview/features/key-attestation.jd) Play Integrity adds app, device and account verdicts. Its device recall feature, in beta, lets you "read three custom values or bits for each device" (shared across all apps in the developer account) that persist across reinstall and reset, per Android Developers' device recall documentation.
6. **Native multi-tenant SSO is available; SCIM is not.** Identity Platform tenants are "unique silos of users and configurations within a single Identity Platform project" and support SAML and OIDC federation per tenant, with Admin SDK APIs for tenant and provider management and bulk user import. [google](https://docs.cloud.google.com/identity-platform/docs/multi-tenancy) [google](https://docs.cloud.google.com/identity-platform/docs/multi-tenancy-managing-tenants?authuser=1) No Identity Platform documentation describes SCIM provisioning. Google Cloud's SCIM support belongs to Workforce Identity Federation, which serves employees, not customer identities. [google](https://docs.cloud.google.com/iam/docs/workforce-identity-federation) [google](https://docs.cloud.google.com/iam/docs/federated-identity-supported-services)
7. **Revocation has a built-in latency.** Firebase ID tokens "are short lived and last for an hour". Revoking refresh tokens takes effect for tokens already issued only if the backend checks revocation status. [google](https://firebase.google.com/docs/auth/admin/manage-sessions) For an offline license, revocation takes effect only when the device next checks in or its grace period runs out. This must be designed for and disclosed to customers.
8. **Enterprise distribution has hard constraints.** Managed configurations "can only [be applied] to apps published to production" and "are not compatible with apps distributed to closed tracks". [google](https://developers.google.com/android/work/play/emm-api/managed-configurations) Private apps published through the Custom App Publishing API are "permanently private" and appear in managed Google Play "in as little as five minutes, compared to over two hours via the Play Console." [google](https://developers.google.com/android/work/play/custom-app-api?authuser=3)
9. **Play rollout controls are manual and one-way.** A staged rollout percentage "won't increase automatically". Halting stops new users from receiving the release, but users who already updated stay on it. Staged rollouts cannot be used for an app's first release. [google +2](https://support.google.com/googleplay/android-developer/answer/6346149?hl=en) Real rollback for Semper therefore depends on server-side feature flags and backward-compatible backends, not on Play.
10. **India DPDP has a dated deadline.** The DPDP Rules, 2025 (G.S.R. 846(E)) were notified in November 2025. Substantive obligations, including notice, security safeguards, breach reporting and data-principal rights, apply from 13 May 2027. [sansalegal +2](https://www.sansalegal.com/post/how-to-comply-with-the-dpdp-act-2023-before-the-may-2027-enforcement-deadline) Rule 7 requires notifying affected data principals and the Data Protection Board "without delay", followed by a detailed report to the Board within 72 hours. [dpdpa +2](https://www.dpdpa.com/dpdparules/rule7.html)

## Details

### Part A — Production-Readiness Checklist (with verification methods)

Each item is a gate. "Verify" says what evidence the readiness review should see.

#### A1. App quality (Android)

| # | Item | Verify |
|---|---|---|
| 1.1 | Crash-free and ANR thresholds defined (e.g., crash-free users ≥ 99.5% on internal/closed tracks) | Crashlytics dashboard for the release candidate on closed-track testers. Play Console Android vitals. |
| 1.2 | Pre-launch report clean (crashes, accessibility, security warnings) | Play Console pre-launch report attached to the review |
| 1.3 | Measurement integrity: instrument readings, units and calibration metadata are stored with tamper-evident timestamps and survive process death and offline periods | Instrumented tests that kill the process mid-test-run and replay. Compare stored records with the source data. |
| 1.4 | Offline-first data layer: Firestore offline persistence or a local DB queue, idempotent sync, conflict policy documented | Airplane-mode test matrix (see Part E). Duplicate-write detection in the backend. |
| 1.5 | Release build hardening: not debuggable, R8 obfuscation on, no debug App Check provider or test credentials | APK inspection in CI (MASWE-0067 "Debuggable Flag Not Disabled", MASWE-0089 "Code Obfuscation Not Implemented") [owasp](https://mas.owasp.org/MASVS/controls/MASVS-RESILIENCE-2/) |
| 1.6 | Release SHA-1/SHA-256 of the Play app signing key registered in Firebase project settings | Firebase console screenshot. Sign-in works on a Play-distributed build, not just a sideloaded one. |

#### A2. Backend / Firebase

| # | Item | Verify |
|---|---|---|
| 2.1 | Three projects (dev, staging, prod), prod tagged "Prod", project lien applied, ≥2 Owners, access granted through Google groups | `gcloud` output listing liens and IAM bindings. Firebase launch checklist items marked complete. [google](https://firebase.google.com/support/guides/launch-checklist) |
| 2.2 | Firestore/Storage rules default-deny; every collection has explicit tenant-scoped rules | Rules unit tests (A3.2) with coverage report from the emulator |
| 2.3 | Licensing and seat mutations happen only in Cloud Functions (Admin SDK); clients cannot write to `licenses/`, `seats/`, `devices/` | Rules tests asserting client writes fail on these paths |
| 2.4 | API keys restricted to the required Firebase APIs | API key restriction export |
| 2.5 | Unused auth providers disabled, "especially anonymous authentication" [google](https://firebase.google.com/support/guides/launch-checklist) | Auth provider config screenshot |
| 2.6 | Blaze plan, budget alerts, and spend caps on Cloud Functions | Billing config. Alert test fired to on-call. |
| 2.7 | Firestore PITR enabled, daily scheduled backups (retention up to 14 weeks), quarterly restore drill to a new database [google](https://docs.cloud.google.com/firestore/native/docs/disaster-recovery) | Restore drill record with measured RTO, data diff and sign-off |
| 2.8 | Capacity plan follows the "500/50/5" rule: start at no more than 500 ops/sec on a new collection and increase by 50% every 5 minutes; avoid monotonically increasing document IDs [google +2](https://firebase.google.com/docs/firestore/understand-reads-writes-scale) | Load test report (Part E) and Key Visualizer screenshot with no hotspots |
| 2.9 | Firestore location chosen deliberately per data-residency commitments (immutable after creation) | Location documented in the DPA and architecture record |

#### A3. Security

| # | Item | Verify |
|---|---|---|
| 3.1 | App Check enforced on Firestore, Storage and Functions, using the Play Integrity provider | Enforcement metrics in the App Check console show unverified requests rejected |
| 3.2 | Rules unit tests run in CI with `@firebase/rules-unit-testing` (the only library that supports mocking auth in Security Rules) using `assertSucceeds`/`assertFails`; a cross-tenant access test for every collection [google](https://firebase.google.com/docs/firestore/security/test-rules-emulator) [axonbuild](https://axonbuild.com/blog/firebase-security-rules) | CI log. Every PR that touches `firestore.rules` is blocked on tests. |
| 3.3 | Replay protection on license-activation functions (`consumeAppCheckToken: true`; reject when `request.app.alreadyConsumed`) [google](https://firebase.google.com/docs/reference/functions/firebase-functions.runtimeoptions) | Integration test that replays a captured token and expects rejection. Note: replay protection is beta and Node.js only. [google](https://firebase.google.com/docs/app-check/cloud-functions) |
| 3.4 | Server checks revocation on sensitive calls (`verifyIdToken(..., checkRevoked=true)`) | Test: disable a user, then call an activation function within the 1-hour token window and expect rejection |
| 3.5 | Secrets in Secret Manager, not in the APK, repo or function environment files; license signing private key in Cloud KMS or Secret Manager with IAM limited to the signer function's service account | Secret scanning (e.g., gitleaks) in CI. IAM policy review. |
| 3.6 | MASVS v2 assessment done (STORAGE, CRYPTO, AUTH, NETWORK, PLATFORM, CODE, RESILIENCE, PRIVACY) using MASTG tests [ptkd](https://ptkd.com/journal/owasp-masvs-level-2-checklist) | Third-party pentest report mapped to MASVS controls (scope in Part E) |
| 3.7 | Cloud Audit Logs enabled for Firebase Management, Security Rules and App Check [google](https://firebase.google.com/support/guides/launch-checklist) | Log sink configuration |

#### A4. Licensing (detailed design in Part B)

| # | Item | Verify |
|---|---|---|
| 4.1 | Entitlement tokens are signed server-side (Ed25519); the public key is pinned in the app; key rotation is supported through a `kid` header and multiple pinned keys | Unit tests: tampered payload rejected, unknown `kid` rejected, rotated key accepted |
| 4.2 | Device binding to a non-exportable Keystore key, with attestation verified server-side at activation | Activation on emulator or rooted device fails or is flagged according to policy |
| 4.3 | Grace period, refresh cadence and clock-rollback detection implemented as specified | Time-travel tests (Part E) |
| 4.4 | Seat revocation and reassignment propagate at the next check-in; customer IT sees "pending revocation" until the device confirms | End-to-end test with the device offline, then online |
| 4.5 | Lost or replaced device flow: customer IT can release a seat without the device | Admin console test. Audit log entry produced. |

#### A5. Operator (customer IT) manageability

| # | Item | Verify |
|---|---|---|
| 5.1 | Tenant-scoped admin console: seat dashboard (assigned, active, offline-pending, expiring), device list with last check-in, user CRUD, bulk CSV import/export | Usability test with an IT admin persona. Task completion time recorded. |
| 5.2 | RBAC: at minimum Org Owner, License Admin, User Admin and Read-only Auditor, enforced by custom claims plus rules | Rules tests per role |
| 5.3 | Append-only audit log (who, what, when, before/after) exportable as CSV/JSON; customer-visible | Attempt to edit or delete audit entries as Org Owner and expect failure |
| 5.4 | SSO: SAML/OIDC per tenant through Identity Platform; JIT provisioning optional; per-tenant enforcement of SSO-only login | Test against Entra ID, Okta and Google Workspace IdPs |
| 5.5 | Deprovisioning: disabling a user in the IdP or console revokes refresh tokens and frees the seat at the next check-in | Timed test, documented SLA (e.g., "≤ 1 h online; ≤ grace period offline") |
| 5.6 | EMM support: managed configuration schema (`app_restrictions.xml`) exposing tenant ID, SSO provider ID, optional enrollment token, update policy [bayton](https://bayton.org/android/what-are-managed-configurations/) | Test in at least one EMM (e.g., Intune, or Android Management API test DPC) |
| 5.7 | Self-service documentation and an admin API (or SCIM) for automation | Doc review. API contract tests. |

#### A6. Release and update

| # | Item | Verify |
|---|---|---|
| 6.1 | Semantic versioning; `versionCode` monotonic; backend publishes `minSupportedVersionCode` and `recommendedVersionCode` via Remote Config and an authenticated endpoint | Test: raise the minimum, confirm the gating UX |
| 6.2 | In-app updates: immediate flow for priority ≥4, flexible for lower priorities; priority set via the Play Developer API (it cannot be set in Play Console) [proandroiddev](https://proandroiddev.com/in-app-update-d83bb484797f?gi=0824da67ecfa) | Internal app sharing test of both flows |
| 6.3 | Staged rollout procedure with written halt criteria (crash-free floor, license-activation error rate) | Release runbook. Rehearsal on closed track. |
| 6.4 | Every risky feature behind Remote Config flags, including a licensing "fail-open/fail-closed" policy switch | Flag toggle drill in staging |
| 6.5 | Backend changes are expand/contract; old app versions within the support window are tested against the new backend | Compatibility matrix CI job (N-2 versions) |
| 6.6 | Release notes per version and per customer-visible backend change; deprecation notice period stated in the contract | Release notes archive |

#### A7. Observability

| # | Item | Verify |
|---|---|---|
| 7.1 | Crashlytics with velocity alerts; Performance Monitoring on startup, sync and activation | Alert routing test |
| 7.2 | Cloud Monitoring SLOs: activation success rate, check-in success rate, function error rate and latency, Firestore error codes | SLO dashboard and burn-rate alerts |
| 7.3 | License anomaly signals: activations per license per day, Play Integrity failure ratio, recent device activity spikes | Dashboard. Synthetic abuse test triggers an alert. |
| 7.4 | Structured logs carry tenant ID and request ID with no personal data in clear text | Log sampling review |

#### A8. Compliance and privacy

| # | Item | Verify |
|---|---|---|
| 8.1 | Data map: what personal data (technician names, emails, device IDs, location if any) is stored, where, and for how long | DPIA / records of processing |
| 8.2 | DPDP readiness by 13 May 2027: notices, security safeguards, Rule 7 breach playbook (notify principals and Board without delay; detailed report within 72 h) [dpdpa](https://www.dpdpa.com/dpdparules/rule7.html) [sansalegal](https://www.sansalegal.com/post/how-to-comply-with-the-dpdp-act-2023-before-the-may-2027-enforcement-deadline) | Tabletop exercise record |
| 8.3 | GDPR (EU customers): Firebase Data Processing and Security Terms and SCCs accepted; processor/controller roles defined in the customer DPA | Signed DPA. Firebase terms acceptance. |
| 8.4 | Play Data safety form consistent with actual SDK data collection | Cross-check against the SDK inventory |
| 8.5 | Data export and deletion per tenant and per user | Scripted test |

### Part B — Recommended Licensing Architecture

**Model.** A `License` belongs to a tenant (customer org) and has a SKU, term (subscription start/end), `seatPolicy` (`named` or `floating`), `maxSeats`, `maxDevicesPerSeat`, and `offlineGraceDays`. A `Seat` is assigned to a user (named) or leased (floating). A `Device` record holds the attested public key, model, app version, last check-in, and status (`active`, `revocation_pending`, `revoked`).

**First activation (online, required).**
1. The user signs in through the tenant's IdP (Identity Platform tenant) or email/password.
2. The app generates an EC P-256 key in the Android Keystore (StrongBox if available, otherwise TEE), non-exportable, with a server-issued challenge as `attestationChallenge`.
3. The app sends the attestation chain, a Play Integrity token (standard request with request hash), an App Check token and the Firebase ID token to an `activateDevice` callable function.
4. The function verifies:
   - the ID token, with revocation checked;
   - the App Check token, consumed for replay protection;
   - the Play Integrity verdict (`appIntegrity` = PLAY_RECOGNIZED, `deviceIntegrity` per policy);
   - the attestation chain up to Google's root, including the challenge match and security level.
   It then allocates a seat transactionally and writes the device record.
5. The function returns a signed entitlement token: compact JWS with EdDSA (Ed25519), signed by a key held in Cloud KMS or Secret Manager. Claims: `tenantId`, `licenseId`, `seatId`, `uid`, `deviceKeyThumbprint`, `features`, `iat`, `nbf`, `exp` (= subscription end, capped), `refreshAfter` (e.g., 24 h), `graceUntil` (= last server time + `offlineGraceDays`), `minAppVersion`, `kid`.

**Offline operation.** At each launch and before each test run, the app:
- verifies the JWS signature with the pinned public key or keys;
- checks that `deviceKeyThumbprint` matches its Keystore key;
- signs a local nonce with the Keystore key, which proves the token was not copied to another device;
- confirms that local time is within [`lastServerTime`, `graceUntil`].

**Clock-tampering resistance (engineering design).** Persist `lastServerTime` from every successful check-in, plus a monotonic high-water mark of local wall-clock time observed. Treat any wall-clock reading earlier than the high-water mark minus a tolerance (e.g., 10 minutes) as rollback. Rollback should end the grace period early or force an online check. It should not hard-lock the device. Also accumulate elapsed time with `SystemClock.elapsedRealtime()` deltas across boots. Store these values in an encrypted file whose integrity is protected by an HMAC under a Keystore key. This is not tamper-proof on a rooted device. Its purpose is to make casual clock rollback ineffective. The server-side bound (`exp`, seat revocation at next check-in) is the real control.

**Refresh and check-in.** When online and past `refreshAfter`, the app calls `checkIn`, which re-verifies Play Integrity (rate-limited) and returns a new token or a revocation instruction. In a floating policy, the seat is a lease that lasts until `graceUntil`. A floating seat therefore cannot be reclaimed from an offline device before its lease expires. This is the main trade-off customer IT must understand. Short leases (e.g., 8–24 h) suit shared lab tablets that stay online. Named seats with longer grace periods (e.g., 7–14 days) suit field or shop-floor devices that are often offline.

**Revocation, reassignment, replacement.**
- *Revocation:* IT revokes in the console. The server marks the device `revocation_pending`, revokes the user's refresh tokens if the user is also removed, and the device deletes its token at its next check-in. The seat is counted free immediately (policy option) or at `graceUntil` (strict option). Make this choice explicit per contract.
- *Reassignment:* releasing a seat from user A and assigning it to user B is a single transactional function call with an audit entry.
- *Device replacement/loss:* IT force-releases the device. The old token stays cryptographically valid until `graceUntil`, which bounds the exposure. Play Integrity device recall can flag a device that was released for abuse, so that re-activation after a factory reset is detectable. Android Developers' device recall (beta) documentation says you can "read three custom values or bits for each device", that "All the apps in your Google Play developer account have access to the same three values per device", and that enabling it first requires completing "the device recall beta interest form".
- *Factory reset or app reinstall:* the Keystore key is lost, so the device must re-activate online. ANDROID_ID survives reinstall but not factory reset, so use it only as an advisory hint to match a re-activation to a previous device record. [googleblog](https://android-developers.googleblog.com/2017/04/changes-to-device-identifiers-in.html)

**Resilience controls (MASVS-RESILIENCE).** Use R8 obfuscation, signature and installer checks (supplemented by Play Integrity `appIntegrity` at check-in) and debugger/Frida detection, with graded responses: flag on the server, shorten the grace period, then refuse. Do not crash. OWASP states these controls should not hinder legitimate users. [owasp](https://mas.owasp.org/MASVS/11-MASVS-RESILIENCE/) That matters for lab devices that may be MDM-managed or run on unusual OEM builds.

**Distribution caveat.** Play Integrity's `appIntegrity` and `accountDetails` verdicts assume Play-installed builds. [android](https://developer.android.com/google/play/integrity/overview) Managed Google Play private apps are Play-distributed. Firebase App Distribution and sideloaded builds are not, and should use a separate staging project and App Check debug or other providers.

#### Build vs. buy

| Option | Offline support | Seat/floating | Operator console & audit | Fit for Semper | Cost signals (public pages; verify before purchase) |
|---|---|---|---|---|---|
| **In-house on Firebase (recommended)** | Full control (signed JWS, custom grace) | Any model | Must build; reuse Identity Platform tenants | Best fit: identity, tenants, App Check and Play Integrity already in the stack | Engineering and maintenance cost. Firebase usage. |
| **Keygen (Cloud/CE/EE)** | Ed25519-signed and optionally AES-256-GCM-encrypted license files with embedded expiry. Keygen Relay serves air-gapped sites. [keygen](https://keygen.sh/docs/choosing-a-licensing-model/offline-licenses/) [github](https://github.com/keygen-sh/keygen-relay) | Node-locked, user-locked and floating | Admin dashboard. Audit logs on Std and Ent tiers. SAML/SSO only on Ent. [keygen](https://keygen.sh/pricing/) | Strong offline primitives. You still build the customer-IT console and tenant mapping. | Free Dev tier: 100 active licensed users. Paid tiers via a slider. Premium Support SLA $995/mo (Ent). Free self-hosted CE. [keygen +2](https://keygen.sh/pricing/) |
| **Cryptlex** | Node-locked offline activation via request/response files. Hosted floating "is not suitable for offline machines". On-prem LexFloatServer for offline floating. [cryptlex](https://cryptlex.com/docs/licensing-models/offline-licenses) [cryptlex](https://cryptlex.com/docs/on-premise-floating-licenses/offline-floating-license) | Node-locked, named-user and floating [cryptlex](https://cryptlex.com/docs/sdks-and-apis/lexactivator#hosted-floating-licenses) | Customer portal (mid tier). Audit logs (Business tier). [cryptlex](https://cryptlex.com/pricing) | Mature, but offline floating needs a server at the customer site | Starter $100/mo (1,000 active activations); $300/mo tier adds hosted floating and customer portal; Business $600/mo adds on-prem floating and audit logs [cryptlex](https://cryptlex.com/pricing) |
| **LicenseSpring** | Android/Java SDK with configurable grace period and offline license files. [licensespring](https://licensespring.com/developers/android) [licensespring](https://docs.licensespring.com/frequently-asked-questions/faq) The Java Floating Client does not support offline activation or grace period. [licensespring](https://docs.licensespring.com/sdks/java-sdk/java-modules) | Node-locked and floating | Vendor portal | Viable for named or per-device seats. Weak for offline floating. | Not verified in this research |
| **10Duke** | Not evaluated in this research | — | — | Evaluate only if enterprise identity-based licensing is a hard requirement | — |
| **Google Play Billing** | Play-managed entitlement | Per Google account, consumer-oriented | None for customer IT | Poor fit for org-purchased seats managed by IT. Not recommended for B2B seats. | Play service fee |

**Recommendation.** Build in-house. Semper already relies on Identity Platform, App Check and Cloud Functions. The hard parts (tenant isolation, SSO, the customer-IT console) must be built whichever vendor is chosen, because none of the vendors reviewed provides a tenant-scoped console for customer IT on a Firebase identity model. The licensing core itself (signed tokens, seat transactions, check-in) is small. It does, however, carry security responsibility: key custody in KMS, key rotation, pentesting. If the team cannot commit to that, use Keygen as the entitlement backend behind your own Cloud Functions facade, so the app talks only to your API. That keeps a later exit cheap. Avoid hosted-floating products for offline sites.

### Part C — Operator Manageability Design

- **Tenancy.** One Identity Platform tenant per customer. Store `tenantId` as a custom claim and enforce it in every rule (`request.auth.token.tenantId == resource.data.tenantId`). Tenant creation and SAML/OIDC provider setup should be done through the Admin SDK by the developer team (admin), with a self-service SSO setup wizard for customer IT later. [google](https://docs.cloud.google.com/identity-platform/docs/multi-tenancy-managing-tenants?authuser=1)
- **Provisioning.** Phase 1: CSV import/export plus invitation emails, with JIT provisioning on first SSO login, mapped to a default role and gated by available seats. Phase 2: a SCIM 2.0 endpoint (Users and Groups) built as Cloud Functions that call the Admin SDK. This has to be built because Identity Platform does not document SCIM. Map IdP group membership to Semper roles and seat assignment, and map IdP deactivation to seat release plus token revocation.
- **Console essentials.** Seat utilisation (assigned, active in the last 7 days, offline-pending, revocation-pending), expiring-subscription warnings, per-device last check-in and app version, a bulk "release stale devices" action, role management, an audit log viewer with export, and an API token page for automation.
- **EMM.** Publish as a managed Google Play private app to each customer's Organization ID, or offer the public listing with managed-Play support. [google](https://support.google.com/googleplay/work/answer/6145139?hl=en) [google](https://developers.google.com/android/management/apps) Expose managed configuration keys (`tenantId`, `ssoProviderId`, `enrollmentToken` (short-lived, single-use, never a long-lived license key), `allowCellularSync`, `updateMode`). Read them through `RestrictionsManager` and react to `ACTION_APPLICATION_RESTRICTIONS_CHANGED`. [appaloosa](https://www.appaloosa.io/blog/enterprise-mobility/managed-android-app-configuration) Because managed configs apply only to production-track apps, customer-specific beta builds should use separate private apps, not closed tracks. [google](https://developers.google.com/android/work/play/emm-api/managed-configurations)
- **Update control for IT.** Offer per-tenant `minSupportedVersion` and `targetVersion` in the backend. Version pinning for EMM-managed fleets is ultimately controlled by the customer's EMM update policy. Semper's part is to honour a tenant-level "defer non-critical updates" flag, to keep N-2 app versions supported by the backend, and to give advance notice before raising the minimum version.

### Part D — Release and Update Management (developer team)

1. **Versioning:** SemVer for marketing versions and a monotonic `versionCode`. Tag backend deployments with the same release train ID.
2. **Firestore schema evolution:** expand/contract only. (a) Add new fields while still writing old fields. (b) Deploy readers that accept both. (c) Backfill with a batched migration that respects the 500/50/5 rule. (d) Remove old fields only after the minimum supported app version no longer reads them. Store a `schemaVersion` in each document.
3. **Rules and functions deployment:** CI deploys to staging and runs rules tests and an end-to-end suite against staging. Prod deploys need manual approval. Keep the previous rules release and function versions so they can be redeployed immediately.
4. **Forced update and gating:** Remote Config and the check-in response both carry `minAppVersion`. Below the minimum, the app enters the immediate in-app update flow but still allows read-only access to locally stored results, so lab data is never hostage to an update.
5. **Rollback:** Play cannot recall a build. Halting a staged rollout leaves existing updaters on the bad version, and halting a fully rolled-out release restores the previous version only for new and eligible users. [salari](https://salari.dev/writing/phased-release-vs-staged-rollout-what-halting-does/) [vmobify](https://vmobify.com/blog/google-play-release-controls) The real rollback levers are Remote Config kill switches, backend compatibility, and shipping a hotfix with a higher `versionCode`.
6. **Support/SLA (B2B):** publish severity definitions, response targets, a supported-version policy (e.g., current and two prior minors), maintenance windows, and a status page. Tie licensing-service SLOs (activation and check-in availability) to the grace period. A 7-day grace period means an outage shorter than 7 days does not affect offline users.

### Part E — Verification Methods

- **Security rules tests:** Run them in the emulator in CI. For each collection, test owner allowed, same-tenant other role, cross-tenant denied, unauthenticated denied, and client writes to license paths denied. Use the emulator coverage report to find untested expressions. [axonbuild](https://axonbuild.com/blog/firebase-security-rules)
- **Load testing:** Use a script (e.g., k6 or Locust calling callable functions, or Admin-SDK load generators against staging) to model peak activation (e.g., a customer onboarding 500 devices at once) and steady-state check-ins. Ramp according to 500/50/5. Watch function cold starts, concurrency limits, Firestore contention on the seat counter (use sharded counters or per-seat documents, not a single counter document) and Key Visualizer hotspots.
- **Penetration test scope:** Mobile (MASVS v2 / MASTG, all eight groups). Backend (callable functions authz, IDOR across tenants, rules bypass, App Check bypass, replay). Identity (SAML/OIDC misconfiguration, token revocation). Admin console (RBAC escalation, CSRF/XSS if web). Licensing-specific abuse cases.
- **License bypass and tamper tests:**
  - Repackage the APK with patched verification and confirm Play Integrity `appIntegrity` fails at the next check-in and the server flags the device.
  - Use Frida to hook the signature-verification function.
  - Copy the token and the app's data directory to a second device and confirm the Keystore proof fails.
  - Run on a rooted device or emulator and confirm the policy response.
  - Edit the JWS payload and confirm rejection.
  - Replay activation requests and confirm App Check replay protection.
  - Exceed the seat count with parallel activations and confirm transactional enforcement.
- **Offline and grace-period tests:**
  - Airplane mode for 1 hour, for `offlineGraceDays − 1`, and for `offlineGraceDays + 1`.
  - Clock set forward, then back.
  - Clock rolled back past `lastServerTime`.
  - Time zone or DST change.
  - Reboot during the offline period.
  - Revocation while offline, then reconnect.
  - Subscription expiry while offline.
- **Chaos/failure tests:** Disable the check-in function in staging. Inject Firestore `UNAVAILABLE`/`DEADLINE_EXCEEDED` errors. Simulate a Play Integrity API outage, where policy should fail open within the grace period. Exceed quota. Restore Firestore from PITR into a new database, then repoint staging.
- **Go/no-go review template (adapted from the SRE PRR and Launch Coordination Checklist):**
  1. Service description, dependencies (Firebase products, Play Integrity, IdPs) and owners.
  2. Checklist A1–A8 status, with evidence links and exceptions plus risk owner.
  3. SLOs, alerts, on-call rota, runbooks (activation failure, mass revocation, signing-key compromise, Firestore restore).
  4. Capacity: expected tenants, devices and peak activations, plus load test results.
  5. Security: pentest findings, all critical and high items closed.
  6. Data: backup and restore drill result, data map, DPA status.
  7. Release: rollout plan with percentages, halt criteria and rollback levers.
  8. Customer readiness: admin guide, EMM guide, support SLA.
  9. Decision: Go / Go with conditions (dated) / No-go, signed by engineering lead, security and product.

## Recommendations

1. **Before any customer pilot:**
   - enforce App Check;
   - lock licensing paths to Cloud Functions only;
   - put rules unit tests into CI;
   - set up three Firebase projects with liens;
   - enable PITR and daily backups;
   - turn on spend caps for Functions;
   - move the signing key into KMS or Secret Manager.
2. **Choose seat semantics per customer contract.** Use named seats with a 7–14 day grace period for offline-heavy labs, and floating seats with short leases only where devices are reliably online. Document that offline revocation takes effect at the next check-in or at grace expiry.
3. **Ship the customer-IT console with audit logs and CSV import before SSO.** Add Identity Platform SAML/OIDC tenants next, then SCIM once a customer requires it.
4. **Add the managed configuration schema now.** It is cheap, and it lets EMM-managed customers onboard devices without entering keys by hand.
5. **Commission an external MASVS-mapped pentest** that includes licensing abuse cases before general availability.
6. **Plan DPDP compliance work to finish before 13 May 2027.** Run a breach tabletop exercise against the 72-hour Board report requirement. [sansalegal](https://www.sansalegal.com/post/how-to-comply-with-the-dpdp-act-2023-before-the-may-2027-enforcement-deadline)

## Caveats

- **Sources and their limits:**
  - Vendor pricing comes from public pages and changes often. Keygen's paid tiers are behind an interactive slider, and third-party listings quoting fixed prices may be out of date. [permitcore](https://permitcore.dev/blog/permitcore-vs-keygen-cryptolens-licensespring) LicenseSpring and 10Duke pricing and features were not independently verified here.
  - Play Integrity device recall and App Check replay protection are labelled beta, and replay protection is Node.js-only.
  - Android key attestation implementers who verify chains directly must trust both Google attestation roots. Android Developers' key attestation guide states the new root "will begin signing attestation certificate chains on February 1, 2026" and asks developers to "add the new root certificate to your trust stores by March 31, 2026". That rotation has now taken place.
  - Sources differ on the DPDP Rules notification date (13 vs. 14 November 2025). [scrut](https://www.scrut.io/post/dpdp-rules) BitScore's entry for G.S.R. 846(E), a secondary source not checked against the Gazette, says "The stamp reading 14 November on the cover page is the e-gazette upload reference, not the date of publication". It also says Rule 1(4) "commences rules 3, 5 to 16, 22 and 23 eighteen months after publication, on 13 May 2027."
  - The statement that Identity Platform lacks SCIM rests on its absence from Google's documentation, not on an explicit statement from Google.
- **Design judgments, not documented requirements:**
  - The clock-tampering design, the grace-period ranges and the thresholds (crash-free %, lease lengths) are engineering recommendations.
  - On a fully rooted device, any offline check can eventually be bypassed. The architecture limits the damage through server-side expiry and seat accounting rather than claiming the app cannot be bypassed.
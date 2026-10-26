# Support policy (draft)

Readiness Part D6 and item 6.6 ([READINESS_FRAMEWORK_2026-10.md](READINESS_FRAMEWORK_2026-10.md)).
**Draft.** Every value marked **TBD** is a business commitment the operator sets;
nothing here is an offer to a customer until it is in a signed contract or the
Terms. Owned by TD-258.

Support contact: the support address configured for the deployment
(`SUPPORT_EMAIL`, default `support@sempermechanics.com`, `backend/app/config.py`),
shown in the app under Settings → Help & support.

## Severity definitions

Shared with [INCIDENT_RESPONSE.md](INCIDENT_RESPONSE.md).

| Severity | Definition | First response | Update cadence | Target to restore |
|---|---|---|---|---|
| SEV1 | Service unusable for most users, personal data exposed, or licensing defeated at scale | **TBD** | **TBD** | **TBD** |
| SEV2 | A main path (sign-in, backup, restore, roster) broken for many users | **TBD** | **TBD** | **TBD** |
| SEV3 | A path broken for some users, with a workaround | **TBD** | — | **TBD** |
| SEV4 | Question, cosmetic issue, single-user problem | **TBD** | — | — |

Support hours and time zone: **TBD**.

## What an outage means for an offline user

Analysis runs on the phone and never waits for the backend. A licensed device
holding a signed entitlement keeps it for up to `ENTITLEMENT_OFFLINE_GRACE_DAYS`
(default 14 days) after it was issued, capped at the licence's end plus its grace
([ADR-023](../adr/ADR-023-signed-offline-entitlement.md)). A backend outage shorter
than that changes no offline user's entitlement; uploads and restores queue on the
phone and resume. Availability objective for the API: 99.5 % of requests over 28
days (`ops/monitoring/slo/availability.json`); an internal objective, not an SLA.

## Supported app versions

- **Supported:** the current release and the two releases before it.
- **Enforced** through `MIN_SUPPORTED_VERSION_CODE` on the backend for Semper, and
  `MIN_SUPPORTED_VERSION_CODE_SEMPER_CLASSROOM` for Semper Classroom (its `_MATERIAL_TESTING`
  name before [ADR-030](../adr/ADR-030-semper-classroom-app-identity.md) is read while the new one is unset), whose builds are
  numbered separately (`settings.release_gate(app)`, `backend/app/config.py:308-335`;
  each app receives only its own pair on `/v1/config`): a build below it keeps the session list, the viewer
  and exports, and cannot start a new analysis, upload or restore
  (`app/src/main/java/com/sempermechanics/semper/data/policy/FeatureGates.kt:34-40`).
  `RECOMMENDED_VERSION_CODE` (or its `_SEMPER_CLASSROOM` twin) shows a nudge once per version pair. A local debug build with
  versionCode 1 is never gated
  (`app/src/main/java/com/sempermechanics/semper/data/policy/AppPolicy.kt:37`).
- **Minimum Android version:** Android 11 (API 30) from the 2026-10-18 change.
  Phones on Android 7–10 can no longer install or update the app.
- Backend changes keep the supported versions working (expand / contract,
  [FIRESTORE_SCHEMA_RUNBOOK.md](../backend/FIRESTORE_SCHEMA_RUNBOOK.md)); a CI job
  that proves it for N-2 is owed (TD-251).

## Deprecation notice

Notice before raising the minimum version or removing a customer-visible backend
behaviour: **TBD** (period, channel). The Terms state none today (TD-258). The
in-app nudge (`RECOMMENDED_VERSION_CODE_MATERIAL_TESTING` for this app,
`RECOMMENDED_VERSION_CODE` for Semper) is the earliest technical notice; raise it
one release before the minimum.

## Release notes

Per app version: the GitHub Release changelog and [CHANGELOG.md](CHANGELOG.md);
the same text goes to the Play listing's "What's new" ([RELEASING.md](RELEASING.md)).
Per customer-visible backend change: a CHANGELOG entry naming the deploy.

## Maintenance windows

**TBD.** Backend deploys are zero-downtime (candidate revision, smoke, promote;
`.github/workflows/deploy-backend.yml`), so no routine window is needed; a window
is for Firestore restores and schema migrations that stop writers.

## Status page

None yet (TD-258). Until one exists, incidents are communicated by email to
affected institution contacts.

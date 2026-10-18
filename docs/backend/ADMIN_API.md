# Admin API — institution and staff routes as an automation contract

The routes the consoles call, written down for customer IT and Semper staff who
script against them (readiness item 5.7,
[READINESS_FRAMEWORK_2026-10.md](../ops/READINESS_FRAMEWORK_2026-10.md)). The
source of truth is the code: `backend/app/routers/roster.py`,
`backend/app/routers/institutions.py`, `backend/app/routers/admin.py`; each
route's auth tier is asserted in `backend/tests/test_route_authz_matrix.py:76-175`,
and every path must also be declared in `backend/gateway/openapi.yaml` (the gateway
404s anything else). Design background:
[CLOUD_ARCHITECTURE_GCP.md §20](CLOUD_ARCHITECTURE_GCP.md#20-licensing--entitlements).

**Limits of automation today.** Every institution route takes the browser step-up
below, which needs an interactive sign-in with a second factor no older than
`ADMIN_WEB_REAUTH_SECONDS` (default 900 s, `backend/app/config.py:175`). There are no
machine tokens (FI-20) and no SCIM (FI-18). A script can run inside one such
window, using the ID token of a signed-in session.

## Conventions

- **Base URL:** the API Gateway host (production `https://semper-gw-86wx7pp1.an.gateway.dev`;
  [ENVIRONMENTS.md](../ops/ENVIRONMENTS.md)).
- **Authentication:** `Authorization: Bearer <Firebase ID token>` from the Auth
  project. The gateway checks it and forwards it as `X-Forwarded-Authorization`;
  the backend verifies it again (`backend/app/deps.py:56-70`).
- **Errors:** JSON `{"detail": "<code>"}`, sometimes `"<code>: <suffix>"`. The
  code is stable; the status per code is `backend/app/errors.py:283-327`
  ([ADR-020](../adr/ADR-020-backend-refusal-model.md)). A 429 carries `Retry-After`.
- **Paging:** `page_size` (or `limit`) and `page_token`; the answer's `page` block
  carries `nextPageToken` and `hasMore`.
- **Ids:** `{license_id}` is the licence document id (the key's SHA-256, never the
  key); `{uid}` is the member's account id; `{invite_key}` is the invite id from the
  seats listing. Addresses never appear in a path.

## Auth tiers

| Tier | Who | Proof | Refusals |
|---|---|---|---|
| `user` | any approved account | ID token | 401 `invalid_token`, 403 `not_approved` |
| `institution-stepup` | an address in this licence's `adminEmails`, verified | ID token + TOTP second factor on a sign-in younger than `ADMIN_WEB_REAUTH_SECONDS` (`backend/app/routers/institutions.py:58-74`) | 403 `email_not_verified`; 404 `license_not_found` for a licence that does not name the caller, exactly as for one that does not exist; then 403 `mfa_required` / `reauth_required` |
| `admin` | Semper staff (`role: admin` or `ADMIN_EMAILS`) | ID token (`backend/app/deps.py:290-301`) | 403 `not_admin` |
| `admin-stepup` | staff | a signed request from the staff phone, or the browser step-up as above | as above |
| `admin-stepup-fresh` | staff | as `admin-stepup`, with a sign-in younger than `ADMIN_WEB_REVOKE_REAUTH_SECONDS` (default 120 s) | `reauth_required` |

With `CHECK_REVOKED_ON_STEP_UP=true` (after TD-214), a step-up call by a revoked or
disabled Firebase user is 401 `invalid_token`.

## Institution routes (customer IT)

Prefix `/v1/institutions/licenses/{license_id}`. Rate limits: `institution_bucket`
(1 request/s, burst 10) for every route; bulk import also `roster_import_bucket`
(burst 3, then one a minute); export, summary and audit also `roster_report_bucket`
(0.5/s, burst 10) (`backend/app/rate_limit.py`). Each change writes one
`audit_logs` row with an `INSTITUTION_…` action.

| Method and path | Tier | Request | Response (200) | Errors |
|---|---|---|---|---|
| `GET /v1/institutions/licenses` (no prefix) | `user` | — | `{"licenses": [...]}`: the institution licences whose `adminEmails` hold the caller's verified address (`institutions.py:77-97`) | 403 `email_not_verified` |
| `GET …/seats` | `institution-stepup` | `page_size` (≤ 1000, default 500), `page_token` | `{license, seats[], invites[], page}`; a seat is `uid`, `email`, device locks, `status` (`active` / `disabled` / `revoked`), lease fields, times (`roster.py:128-152`) | 404 `license_not_found` |
| `POST …/seats` | `institution-stepup` | `{"email": "…"}` | `{licenseId, seat, invite}`: exactly one of the two is set (`:154-168`) | 400 `invalid_email`; 409 `member_already_licensed`, `license_seats_exhausted`, `license_seat_disabled`; 503 `claim_contended` (retry) |
| `POST …/seats/import` | `institution-stepup` | `{"csv": "<file text>"}`: one column `email`, or the first column when there is no header; ≤ 500 rows, ≤ 512 000 characters (`backend/app/roster_io.py:22-25`) | `{licenseId, rows, counts, results[]}`; `counts` has `seated`, `invited`, `exists`, `duplicate`, `invalid`, `refused`, `unprocessed`; each result is `{row, email, outcome, error}` (`roster.py:170-241`) | 400 `roster_import_empty`, `roster_import_malformed`, `roster_import_no_email_column`; 413 `roster_import_too_many_rows`. Re-import the same file to finish `unprocessed` rows: rows already on the roster answer `exists` |
| `GET …/seats/export` | `institution-stepup` | — | `text/csv`, every seat then every invite, `email` first, so the file imports back (`:243-258`); a cell starting with `=`, `+`, `-` or `@` is prefixed with `'` against spreadsheet formulas, and such an address does not yet import back (TD-267) | — |
| `GET …/summary` | `institution-stepup` | — | `{licenseId, assigned, onHold, activeLast7Days, invited, revocationPending, revocationPendingNoCheckIn, removed, expiringWithin30Days, seating, maxSeats, …}` (`roster.py:260-272`; `roster_io.py:189-230`) | — |
| `GET …/audit` | `institution-stepup` | `format` = `json` or `csv`; `since`, `until` (ISO date or instant, UTC; a bare `until` date includes that day); `page_size` (1–5000, default 100); `page_token` | JSON `{licenseId, events[], page}`, newest first; each event `{id, ts, uid, action, outcome, target, detail}`, no IP or device id. CSV: the same page, next token in the `X-Next-Page-Token` response header (`roster.py:274-313`; `backend/app/audit.py:154-193`). The CORS answer does not expose that header, so only a non-browser client (a script) can read it and page on; a browser gets one page. The console's CSV export therefore asks for 5,000 events and says so when it stopped at that ceiling (`firebase-hosting/public/console/institution/audit-log.js`); page CSV from a script, or narrow `since` / `until` | 400 `invalid_audit_range`. Events written before 2026-10-18 are absent (TD-246) |
| `PATCH …/seats/{uid}` | `institution-stepup` | `{"clearDeviceLock": true}` and/or `{"enabled": false}` | `{licenseId, seat}` (`:334-366`) | 400 `empty_patch`; 404 `seat_not_found`; 409 `seat_revoked` |
| `DELETE …/seats/{uid}` | `institution-stepup` | — | `{licenseId, uid, revoked: true}`: the holder drops to Demo, keeps their data, and the slot is freed (`:368-380`) | 404 `seat_not_found` |
| `DELETE …/invites/{invite_key}` | `institution-stepup` | — | `{licenseId, inviteId, revoked: true}` (`:315-332`) | 404 `invite_not_found` |

What a removal means for a phone: online, on its next request; offline, when its
signed entitlement's `graceUntil` passes (at most 14 days by default,
[ADR-023](../adr/ADR-023-signed-offline-entitlement.md)). The summary's
`revocationPending` counts removals the holder has not yet been seen after.

## Staff routes (Semper)

The same roster routes are mounted for staff under
`/v1/admin/licenses/{license_id}` for any institution licence: reads at `admin`,
changes at `admin-stepup` (`backend/app/routers/admin.py:494-520`), with
`admin_bucket` and `ADMIN_…` audit actions. Staff-only routes:

| Method and path | Tier | Purpose |
|---|---|---|
| `GET /v1/admin/users` | `admin` | List accounts, `status` filter, paged |
| `POST /v1/admin/users/{uid}/approve` | `admin-stepup` | Approve a PENDING account |
| `POST /v1/admin/users/{uid}/revoke` | `admin-stepup` | Suspend an account and revoke its refresh tokens (best-effort until TD-214; the audit row says `sessionsRevoked`) |
| `PATCH /v1/admin/users/{uid}/config` | `admin-stepup` | Per-user limit overrides |
| `POST /v1/admin/device-releases` | `admin-stepup` | `{"email"}`: free a Demo account's registered phone |
| `GET /v1/admin/licenses` | `admin` | Licences, newest first; `q` searches exact address, domain or key prefix (TD-244: the address is in the URL) |
| `GET /v1/admin/licenses/{license_id}` | `admin` | One licence |
| `POST /v1/admin/licenses` | `admin-stepup` | Mint; the plaintext key is returned once |
| `PATCH /v1/admin/licenses/{license_id}` | `admin-stepup` | Terms, renewal, `clearDeviceLock` |
| `POST /v1/admin/licenses/{license_id}/revoke` | `admin-stepup-fresh` | Whole-licence revoke |
| `DELETE /v1/admin/licenses/{license_id}` | `admin-stepup-fresh` | Delete into a 30-day hold |
| `GET /v1/admin/deleted-licenses`, `POST …/{license_id}/restore` | `admin`, `admin-stepup` | The hold |
| `POST /v1/admin/licenses/{license_id}/convert` | `admin-stepup` | Individual to institution |
| `PATCH /v1/admin/licenses/{license_id}/seats/{uid}/device` | `admin-stepup` | Clear one seat's device lock |
| `GET /v1/admin/licenses/{license_id}/reconcile` | `admin` | Per-seat check of what a licence actually entitles; `revokedStillRunning` |
| `GET /v1/admin/licenses/{license_id}/device-history` | `admin` | Recent device moves |

## Examples

```bash
API=https://semper-gw-86wx7pp1.an.gateway.dev
TOKEN=<ID token of a signed-in session with a fresh second factor>
LIC=<licence id from GET /v1/institutions/licenses>

curl -sS -H "Authorization: Bearer $TOKEN" "$API/v1/institutions/licenses/$LIC/summary"

curl -sS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data "$(python3 -c 'import json,sys; print(json.dumps({"csv": open(sys.argv[1]).read()}))' roster.csv)" \
  "$API/v1/institutions/licenses/$LIC/seats/import"

curl -sS -H "Authorization: Bearer $TOKEN" -o audit.csv \
  "$API/v1/institutions/licenses/$LIC/audit?format=csv&since=2026-10-01&page_size=5000"
```

## Contract tests

`backend/tests/test_roster_bulk.py` (import, export, summary, audit),
`backend/tests/test_route_authz_matrix.py` (tiers and cross-tenant refusals),
`backend/tests/test_gateway_parity.py` (every route declared at the gateway).

# Consoles

Static pages on the existing auth Hosting site. No build step, no framework, no
`package.json` — the site is served as files, and a toolchain for four pages
would cost more than it saves.

| Path | Who | What it can do |
|---|---|---|
| `/login` (`/console/`) | Anyone with an account | Signs in and forwards to whichever dashboard below is theirs. |
| `/account` (`/console/account`) | Anyone with an account | See the licence, its term, the seat and the saved analyses; give a floating seat back, move the licence to a new device, download an analysis. The last two need **2FA**. |
| `/console/institution` | IT staff named in a licence's `adminEmails` | Add and remove roster members, withdraw an unclaimed invitation, see who holds a seat, put a member on hold, clear a device lock. |
| `/console/operator` | Semper staff (`ADMIN_EMAILS` / `role=admin`) **with 2FA** | Issue individual and institution licences, extend a term, revoke a key, drive any institution roster, approve accounts. |

## The front door

`/login` is the one address to hand out. It signs the caller in, reads
`GET /v1/me` and `GET /v1/institutions/licenses`, and forwards: `role=admin`
to the operator console, an address named on a live institution licence to the
roster (deep-linked when there is exactly one), and everyone else to their own
account. Somebody who is both gets a switcher rather than a guess, and
`/login?stay=1` always shows it.

Nothing is inferred from the email domain and nothing is remembered in the
browser, so an account that changes hands routes correctly the first time.

Both pretty addresses are Hosting **rewrites** into `/console/`, which has two
consequences worth knowing before editing them. A header is matched against the
request path and knows nothing about the rewrite, so the relaxed console CSP is
restated for `{/login,/account}` in `firebase.json` and the two values must stay
identical. And a relative path in the page would resolve against the site root
at those addresses, so both pages carry a `<base href>`.

## Why the operator console needs a second factor

Every state-changing `/v1/admin/*` route wants proof beyond an ID token. On the
phone that proof is `verified_device` — an ECDSA signature over the request,
from a device keypair registered in Firestore. It exists so that a stolen
session cookie or ID token cannot mint a licence, approve an account or revoke
a key. A browser cannot produce one, which is why this page used to be
read-only.

The browser path is the deliberate substitute, not its removal. A console
caller is accepted only when the ID token records a **completed second factor**
and the sign-in behind it is **recent** (`ADMIN_WEB_REAUTH_SECONDS`, default 15
minutes). Both halves matter: the factor check refuses a stolen password-only
token, and the freshness check stops a token that leaks later from carrying
authority for its full hour.

It is weaker than device binding and worth saying so plainly: someone who
phishes a live MFA session inside the window can act, which the attestation
path made impossible. `ADMIN_WEB_MFA_ENABLED=0` withdraws the browser path
entirely and restores attestation-only admin.

Enrolment is TOTP **only** — the project enrols no SMS factor, so `auth.js`
challenges TOTP and reports anything else rather than half-handling a factor it
cannot complete. Every dashboard — operator, institution, and account — enrols
and challenges that factor before it loads. Consequential account actions
(moving a licence, downloading an analysis) and institution roster changes
also require a fresh second factor on the API side.

Enrolment shows the secret for manual entry rather than a QR code. Every QR
service is somebody else's server and the payload is the TOTP secret itself, so fetching a picture would hand away the factor that protects
licence issuance. The CSP forbids third-party images in any case.

## Confirming destructive actions

Revoking a licence drops a whole institution to demo, so the page asks more
than once: password (or Google) re-auth plus authenticator code, a plain
confirmation naming who is affected, then typing the **key prefix**. The API
also refuses a revoke on a session older than
`ADMIN_WEB_REVOKE_REAUTH_SECONDS`. Nothing is deleted either way — revoking
withdraws entitlement and leaves every saved analysis in place.

## Deploying

Use [`scripts/deploy-console.sh`](../../../scripts/deploy-console.sh) so the
placeholders are always restored, even if deploy fails mid-way. Never commit a
live hostname.

```bash
# From the repo root. The API Gateway host the app talks to, and the Firebase
# Auth domain (must be an authorised domain on the project).
API_BASE_URL="https://your-gateway-host" \
AUTH_DOMAIN="your-project.firebaseapp.com" \
./scripts/deploy-console.sh
```

The script copies `config.js` / `firebase.json`, substitutes `__API_BASE_URL__`,
`__API_ORIGIN__`, and `__AUTH_DOMAIN__`, runs `firebase deploy --only hosting`,
then a `trap` puts the templates back.

### Go-live checklist (Identity Platform + consoles)

Same Firebase project as the app (`indicvision-dic-app-auth`). Do **not** open a
second Auth directory.

1. Confirm which Hosting site owns `sempermechanics.com` (`firebase hosting:sites:list`). `/login` is a rewrite on that site once console files are deployed.
2. Upgrade the project to **Identity Platform**, enable the **TOTP** second factor, leave **SMS** off. Add `sempermechanics.com` (and any preview channel) to authorised domains.
3. Put your address in `ADMIN_EMAILS` / `role: admin` for the operator desk.
4. Deploy Cloud Run with the intended `DEMO_MAX_ANALYSES`, and keep `ADMIN_WEB_MFA_ENABLED=1`. Redeploy **API Gateway** so checkout / release / unbind / bundle / campus aliases are on the public surface — the backend workflow alone does not.
5. `firebase deploy --only firestore:indexes` from the backend indexes file.
6. Run `./scripts/deploy-console.sh` with the live gateway and Auth domain.
7. Hand-check: enrol TOTP at `/login` as staff, as institution IT, and as an account holder; revoke a test licence only after password + TOTP (+ key prefix); sign in on the phone and complete the authenticator challenge.

Identity Platform itself is free to enable. Email/social stays free to the usual
MAU tier; TOTP has no SMS charge when SMS stays off.

The console's Content-Security-Policy is scoped to `/console/**` and the two
addresses that rewrite into it. Every other page — the legal pages, the auth
continue-URLs — keeps the strict
`default-src 'self'` policy. Only `connect-src` is widened, for the API and
Firebase Auth's token endpoints; `script-src` is **not**, because the Firebase
SDK is served from Hosting's own `/__/firebase/` namespace, which is
same-origin.

Firebase Auth must have this Hosting domain in its authorised domains, or the
sign-in popup is rejected.

## Downloading an analysis

`GET /v1/sessions/{sid}/bundle` answers with a zip rather than JSON, so it does
not go through `api()` — that helper reads the whole response as text and parses
it, which would both corrupt an archive and throw on its first byte. `apiBlob()`
is the same call shape for a binary body, keeping the one thing that matters:
the retry on `reauth_required`, so a tab left open past the re-authentication
window does not report a refusal for a download the caller is entitled to.

The backend streams the archive so its own memory stays flat; the browser still
holds the whole thing, because a page cannot write to disk incrementally without
the File System Access API. An analysis is tens of megabytes, which is a cost
worth paying for a download that behaves the same everywhere.

## The second seat count

The **Verify** button on an institution licence asks
`GET /v1/admin/licenses/{id}/reconcile` what that licence still entitles, and
opens a panel comparing it with what IT believes.

The two numbers cannot be made to agree by being more careful, which is the
point of showing both. A revoke reaches the seat inside a transaction, the
holder's user document just after it, and the holder's *device* only when the
app next fetches `/v1/config`. `seatsUsed` — the only number the institution
console has — moves at the first of those three.

Each seat is placed in a bucket with a reason, and the panel prints the reason
in words rather than leaving a code to be looked up. Two of them matter:

- **`still_licensed`** is a fault. The demotion never landed and the backend
  would still answer `licensed`. Revoking the seat again repairs it — that path
  is idempotent and re-runs the demotion.
- **`no_checkin_since_revoke`** is not. The record is right; the device has
  simply not been back to hear it.

The read costs one user lookup per seat, so it is never run for the whole
table: nothing happens until someone presses **Verify**, and the result is
cached only until the next action changes a licence, at which point the panel
re-checks itself. That is deliberate — a revoke is exactly the moment to ask
again whether it landed.

The licence row's Seats cell picks up the second number once it exists, so the
verified count sits beside the intended one where the mismatch is visible.

See §20.12 of [the architecture doc](../../../docs/backend/CLOUD_ARCHITECTURE_GCP.md)
for why confirmation takes two conditions rather than one.

## Note on the licence table

`maxSeats` means two different things and the table says which:

- **assigned** — it caps the roster. `seatsUsed/maxSeats` is the whole story.
- **floating** — it caps *concurrent* seats. The roster is uncapped, so the
  table shows both `leasesActive/maxSeats in use` and the roster size.

Reading a floating licence's `seatsUsed` as though it were the cap is the
easiest mistake to make here.

# Consoles

Two static pages on the existing auth Hosting site. No build step, no
framework, no `package.json` — the site is served as files, and a toolchain for
two pages would cost more than it saves.

| Path | Who | What it can do |
|---|---|---|
| `/console/institution` | IT staff named in a licence's `adminEmails` | Add and remove roster members, withdraw an unclaimed invitation, see who holds a seat, put a member on hold, clear a device lock. |
| `/console/operator` | Semper staff (`ADMIN_EMAILS` / `role=admin`) **with 2FA** | Issue individual and institution licences, extend a term, revoke a key, drive any institution roster, approve accounts. |

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

Enrolment is TOTP, and the page shows the secret for manual entry rather than a
QR code. Every QR service is somebody else's server and the payload is the TOTP
secret itself, so fetching a picture would hand away the factor that protects
licence issuance. The CSP forbids third-party images in any case.

The institution console needs no second factor because
`institution_admin_context` is token-only **by design** — it was written for IT
working from a browser or curl, not from the licensed device, and it can only
ever reach the licences that name the caller. See CLOUD_ARCHITECTURE_GCP §20.4.

## Confirming destructive actions

Revoking a licence drops a whole institution to demo, so the page asks twice:
once as a plain confirmation naming who is affected, then by making the
operator **type the key prefix**. A yes/no dialog is muscle memory by the third
licence of the afternoon; typing `SEMP-4K2P` is not something a hand does
absent-mindedly. Nothing is deleted either way — revoking withdraws
entitlement and leaves every saved analysis in place.

## Deploying

Two placeholders are substituted before deploy, the same way
`backend/gateway/openapi.yaml` substitutes `__CLOUD_RUN_URL__`. Never commit a
live hostname.

```bash
# The API Gateway host the app talks to, and the Firebase Auth domain.
API="https://your-gateway-host"
AUTH_DOMAIN="your-project.firebaseapp.com"

sed -i.bak "s|__API_BASE_URL__|${API}|g"        public/console/config.js
sed -i.bak "s|__API_ORIGIN__|${API}|g"          firebase.json
sed -i.bak "s|__AUTH_DOMAIN__|${AUTH_DOMAIN}|g" firebase.json
firebase deploy --only hosting
# Then restore the templates so the placeholders stay in git:
mv public/console/config.js.bak public/console/config.js
mv firebase.json.bak firebase.json
```

The console's Content-Security-Policy is scoped to `/console/**` alone. Every
other page — the legal pages, the auth continue-URLs — keeps the strict
`default-src 'self'` policy. Only `connect-src` is widened, for the API and
Firebase Auth's token endpoints; `script-src` is **not**, because the Firebase
SDK is served from Hosting's own `/__/firebase/` namespace, which is
same-origin.

Firebase Auth must have this Hosting domain in its authorised domains, or the
sign-in popup is rejected.

## Note on the licence table

`maxSeats` means two different things and the table says which:

- **assigned** — it caps the roster. `seatsUsed/maxSeats` is the whole story.
- **floating** — it caps *concurrent* seats. The roster is uncapped, so the
  table shows both `leasesActive/maxSeats in use` and the roster size.

Reading a floating licence's `seatsUsed` as though it were the cap is the
easiest mistake to make here.

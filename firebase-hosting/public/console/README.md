# Consoles

Two static pages on the existing auth Hosting site. No build step, no
framework, no `package.json` — the site is served as files, and a toolchain for
two pages would cost more than it saves.

| Path | Who | What it can do |
|---|---|---|
| `/console/institution` | IT staff named in a licence's `adminEmails` | **Everything.** Add and remove roster members, see who holds a seat, put a member on hold, clear a device lock. |
| `/console/operator` | Semper staff (`ADMIN_EMAILS` / `role=admin`) | **Read-only.** Look up licences and accounts awaiting approval. |

## Why the operator console is read-only

Every mutating `/v1/admin/*` route requires `verified_device` — an ECDSA
signature over the request, from a device keypair registered in Firestore. A
browser cannot produce one, and that is the point: it means a stolen session
cookie or ID token cannot mint a licence, approve an account or revoke a key.

Making those operations reachable from a browser would mean removing that
control. Minting, renewing, revoking and approving therefore stay on the phone
admin screen and the staff CLI. This page is for looking things up.

The institution console has no such limit because `institution_admin_context`
is token-only **by design** — it was written for IT working from a browser or
curl, not from the licensed device. See CLOUD_ARCHITECTURE_GCP §20.4.

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

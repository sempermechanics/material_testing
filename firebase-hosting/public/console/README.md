# Consoles

Static pages on the existing auth Hosting site. No build step, no framework, no
`package.json` — the site is served as files, and a toolchain for four pages
would cost more than it saves.

Each page is an HTML document plus one ES module beside it — `router.js`,
`account/account.js`, `institution/institution.js`, `operator/operator.js` —
loaded with `<script type="module" src="…">`. **Not inline.** The console
`script-src` is `'self'` with no `'unsafe-inline'` (see below), so an inline
script on these pages does not run at all: the page renders and every button is
dead. `auth.js` and `config.js` are shared by all four.

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

1. The dashboards live on **`app.sempermechanics.com`**, a custom domain of this Hosting site (`indicvision-dic-app-auth`). `sempermechanics.com` itself is the marketing site on Netlify, which only links here and redirects `/login`, `/account` and `/terms/` to this host. Add the custom domain in Firebase Console → Hosting (TXT verification, then the A records) — the DNS zone is Netlify DNS. Until the certificate is issued the site still answers on `indicvision-dic-app-auth.firebaseapp.com`.
2. Upgrade the project to **Identity Platform**, enable the **TOTP** second factor, leave **SMS** off. Add `app.sempermechanics.com` (and any preview channel) to authorised domains.
3. Put your address in `ADMIN_EMAILS` / `role: admin` for the operator desk.
4. Deploy Cloud Run with the intended `DEMO_MAX_ANALYSES` (no lower than any live user's session count — every pre-licensing account becomes demo), keep `ADMIN_WEB_MFA_ENABLED=1` and `APP_CHECK_MODE=off`; `deploy-backend.yml` pins all three from repository variables. Then redeploy **API Gateway** from the committed spec (`api-configs create` + `gateways update`, see [BACKEND_SETUP_GCP.md](../../../docs/backend/BACKEND_SETUP_GCP.md) "Redeploying the gateway") so checkout / release / unbind / bundle / campus aliases are on the public surface — the backend workflow alone does not. The full ordered checklist is the "Licensing rollout" section of [PRODUCTION_READINESS_GATE.md](../../../docs/ops/PRODUCTION_READINESS_GATE.md).
5. `firebase deploy --only firestore:indexes` from the backend indexes file.
6. Run `./scripts/deploy-console.sh` with the live gateway and Auth domain. `AUTH_DOMAIN` stays `indicvision-dic-app-auth.firebaseapp.com`: it is the popup origin (`authDomain` from `/__/firebase/init.js`), not the page's own host.
7. Hand-check: enrol TOTP at `/login` as staff, as institution IT, and as an account holder; revoke a test licence only after password + TOTP (+ key prefix); sign in on the phone and complete the authenticator challenge.

Identity Platform itself is free to enable. Email/social stays free to the usual
MAU tier; TOTP has no SMS charge when SMS stays off.

The console's Content-Security-Policy is scoped to `/console/**` and the two
addresses that rewrite into it. Every other page — the legal pages, the auth
continue-URLs — keeps the strict
`default-src 'self'` policy. `connect-src` is widened for the API and
Firebase Auth's token endpoints. `script-src` is `'self'` plus two Google
origins: Hosting's `/__/firebase/<ver>/firebase-*.js` files are same-origin
stubs that `import … from "https://www.gstatic.com/firebasejs/…"`, and the
popup sign-in loads gapi from `https://apis.google.com` — without both the page
renders and *Sign in* does nothing (the first production deploy proved it).
There is still no `'unsafe-inline'`, which is why no page may carry an inline
`<script>` body or an `onclick=` attribute: the policy admits module files from
those three origins and nothing else.

Firebase Auth must have this Hosting domain in its authorised domains, or the
sign-in popup is rejected.

### CORS

The pages are served from Hosting and call the API Gateway on another origin
with an `Authorization` header, so the browser preflights every request. Two
things answer that preflight, and both must be in place or the page renders
and every button silently does nothing: the gateway config carries
`x-google-endpoints … allowCors: true` (so ESPv2 forwards the unauthenticated
`OPTIONS` instead of refusing it), and Cloud Run's `CONSOLE_ORIGINS` names the
page origin (`app.sempermechanics.com` and the `firebaseapp.com` host by
default; add a preview channel while testing). The phone never sends an
`Origin` and is untouched by either.

### Chrome

`console.css` is the marketing site's theme (`semper-website/css/style.css`:
its `:root` tokens, DM Sans / Plus Jakarta Sans, the `.btn-primary` /
`.btn-secondary` shapes, the footer-meta strip) at console density, so
`sempermechanics.com` → *Sign in* reads as one product. Take a value from
there before inventing one here. The brand mark and favicon are copies of the
site's `assets/semper/semper-mark.webp` / `semper-icon.webp`; the fonts come
from Google Fonts, which is why the console CSP names
`fonts.googleapis.com` (`style-src`) and `fonts.gstatic.com` (`font-src`) — the
site-wide policy does not. Every page declares `<base href="/console/…">` so it
works at its rewritten address too, which is why the console CSP's `base-uri`
is `'self'` rather than `'none'`; `check_console.py` refuses the pair any other
way. `chrome.js` holds the footer year: the CSP is `script-src 'self'`, so an
inline one-liner would never run.

## Checking them

There is no compiler here, so nothing else in the repository fails when a
page's wiring goes stale — the page loads, looks right, and does nothing.
[`scripts/check_console.py`](../../../scripts/check_console.py) is the gate
that reads them instead, and runs as the **Console pages** CI job:

| It checks | Because |
|---|---|
| No inline script or `on*=` handler | The CSP above forbids both; such code never executes |
| Every module loads and parses as an ES module | A typo in one is otherwise found by a browser, in production |
| Every `$("id")` is an id its own page defines | Renaming an element silently unwires the code that used it |
| `__API_BASE_URL__`, `__API_ORIGIN__`, `__AUTH_DOMAIN__` still hold placeholders | A deploy that fails to restore them commits a live hostname |
| Every rewrite destination exists | `/login` pointing at a missing file 404s |
| The two console CSPs are identical | The rewrite addresses would otherwise be served a different policy |
| Every `/v1` path a console calls is in `gateway/openapi.yaml` | ESPv2 is an allowlist; an undeclared route 404s in production |

Run it directly with `python scripts/check_console.py`. Node is used for the
syntax check when it is on `PATH` and skipped with a note when it is not.

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

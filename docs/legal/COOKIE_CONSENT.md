# Cookie consent — Firebase Hosting pages

**Status:** inventory recorded 2026-10-22 from the code and the live site; no
banner needed (strictly necessary storage only)

Semper’s Android client does not use a web cookie banner. The first-party web
surfaces are the Firebase Hosting pages under
[`firebase-hosting/public/`](../../firebase-hosting/public/), served at
app.sempermechanics.com: the consoles (`/login`, `/account`, `/console/*`), the
legal pages (`/terms/`, `/privacy/`), the auth continue pages (`finishSignIn`,
`finishReset`, also under `/auth/`), the 404 page, and Firebase's reserved
`/__/auth/*` and `/__/firebase/init.json`.

## Production inventory (2026-10-22)

How it was read: the page sources in `firebase-hosting/public/`, the CSP in
[`firebase.json`](../../firebase-hosting/firebase.json), `curl -sI` on every
path above (no `Set-Cookie` on any response), `curl -s` of the live HTML for
the scripts and stylesheets each page loads, and a text search of the live
Firebase Auth SDK (`www.gstatic.com/firebasejs/12.4.0/firebase-auth.js`) and
Hosting's `/__/auth/handler.js` and `/__/auth/iframe.js`. Nobody signed in, so
the browser-side entries below come from the code, not a DevTools capture.

**Cookies: none.** No response sets one, and no page script writes
`document.cookie`. The Auth SDK reads a `__FIREBASE_DEFAULTS__` cookie if one
exists and only writes cookies under `browserCookiePersistence`, which the
console never selects (`console/auth.js` calls `getAuth` with the defaults and
no `setPersistence`). The API is called cross-origin with an
`Authorization: Bearer` header and fetch's default credentials mode, which
sends no cookies to another origin (`console/api.js:65-72`;
[CLOUD_ARCHITECTURE_GCP.md](../backend/CLOUD_ARCHITECTURE_GCP.md): "bearer
tokens, no cookies").

| Where | Storage | Key | Set by | Purpose | Kept | Class |
|-------|---------|-----|--------|---------|------|-------|
| `/login`, `/account`, `/console/*` | IndexedDB `firebaseLocalStorageDb`, store `firebaseLocalStorage` (localStorage, then sessionStorage, as fallbacks when IndexedDB is unavailable) | `firebase:authUser:<apiKey>:[DEFAULT]` | Firebase Auth SDK (default browser persistence) | Keeps the signed-in user and refresh token | Until sign-out or the browser's site data is cleared | Strictly necessary |
| same | sessionStorage | `firebase:pendingRedirect:…`, `firebase:redirectUser:…` | Firebase Auth SDK | Completes the Google sign-in / re-authentication redirect | The tab | Strictly necessary |
| same | localStorage / sessionStorage | `__sak` | Firebase Auth SDK | Availability probe, removed at once | Momentary | Strictly necessary |
| `/__/auth/handler`, `/__/auth/iframe` (same origin) | IndexedDB, localStorage, sessionStorage | Firebase redirect-event and auth-state entries | Hosting's Firebase Auth handler | Passes the redirect result back to the page | The tab / until sign-out | Strictly necessary |
| `/login`, `/account`, `/console/*` | sessionStorage | `semper.reauthStarted`, `semper.afterReauth`, `semper.resume` | `console/auth.js:97-117` | Step-up re-authentication: loop guard, the note shown on return, the action to resume | The tab; each read clears it | Strictly necessary |
| `/terms/`, `/privacy/`, `/finishSignIn/`, 404 | — | — | — | No script at all | — | — |
| `/finishReset/` | — | — | `finishReset/reset.js` | Forwards to `/__/auth/action`; stores nothing | — | — |

**Third-party resources** (consoles only; the legal, continue and 404 pages
load nothing from another origin):

| Origin | What | Cookies on this site |
|--------|------|----------------------|
| `www.gstatic.com/firebasejs/12.4.0/` | Firebase App and Auth SDK modules | None |
| `apis.google.com` | gapi loader used by the auth handler / iframe | None |
| `identitytoolkit.googleapis.com`, `securetoken.googleapis.com` | Sign-in and token refresh calls | None |
| `fonts.googleapis.com`, `fonts.gstatic.com` | DM Sans and Plus Jakarta Sans | None (the request carries the visitor's IP address and user agent to Google) |
| The Semper API origin (`__API_ORIGIN__`, filled at deploy) | Console API calls | None |
| `accounts.google.com` (navigation, not embedded) | Google sign-in, which the user starts | Google's own cookies on Google's domain, under Google's terms |

No analytics, advertising, A/B or tag-manager script is loaded, and the CSP
would refuse one: `script-src` is `'self'` plus the two Google origins above
(`firebase.json`).

**Banner verdict: not needed.** Every entry is strictly necessary for a
sign-in the user asked for, or for showing the page. Re-run this inventory, and
add consent **before** the tag loads, if a page ever gains a non-essential
script, cookie or storage key; widening `script-src` in `firebase.json` is the
signal.

## Re-checking

1. `curl -sI https://app.sempermechanics.com/<path>` on each path above:
   expect no `Set-Cookie`.
2. `curl -s` each page and list its `<script>` / `<link>` origins; compare
   with the CSP.
3. For the browser side, sign in on the live console with DevTools →
   Application open and compare Cookies, Local / Session Storage and IndexedDB
   with the table. (Not done for 2026-10-22: the table is from the code.)

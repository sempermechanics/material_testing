/* Sign-in, second factor, and API access shared by both consoles.
 *
 * The Firebase SDK is loaded from www.gstatic.com — the console CSP's
 * `script-src` names that origin (and apis.google.com, which the auth iframe
 * loads); see firebase-hosting/firebase.json. Only the project config comes
 * from Hosting's reserved namespace (/__/firebase/init.json); the SDK copies
 * under /__/firebase/<ver>/ are not used, for the reason at the imports below.
 *
 * ── Redirect, never popup ─────────────────────────────────────────────────
 * Google sign-in and every Google re-authentication go through
 * signInWithRedirect / reauthenticateWithRedirect. A popup needs a user
 * gesture that is still fresh when the SDK opens the window, and two of the
 * places this file re-authenticates have none: straight after sign-in, to
 * enrol the second factor, and inside an API retry after `reauth_required`.
 * Browsers block those popups silently — the first staff sign-in on the live
 * domain did exactly that. A redirect has no such rule.
 *
 * The redirect flow needs the auth handler on the page's own origin, or the
 * browsers that partition third-party storage lose the result on the way
 * back. Every Hosting host — the firebaseapp.com default and a custom domain
 * alike — serves /__/auth/handler, so `authDomain` is set to the page's host
 * rather than the project default from init.json. The Google OAuth client
 * must list https://<host>/__/auth/handler as a redirect URI (README).
 *
 * There is no build step and no framework here on purpose: the site is static
 * files, and a toolchain for two pages would cost more than it saves.
 *
 * ── Why the second factor lives here ──────────────────────────────────────
 * Every state-changing /v1/admin/* route wants proof beyond an ID token. On
 * the phone that proof is a device attestation — an ECDSA signature from an
 * enrolled key. A browser cannot produce one, so the staff console proves
 * itself the other way the backend accepts: a completed second factor plus a
 * RECENT sign-in (see backend/app/deps.py attested_or_mfa_admin).
 *
 * "Recent" is the part that shapes this file. The backend rejects a token
 * whose auth_time is older than its window, so a console left open for an
 * hour must re-authenticate before it can act. `api()` handles that
 * transparently: on `reauth_required` it re-authenticates once — a redirect
 * round-trip through Google — and the operator repeats the action; a
 * `sessionStorage` note tells them so when the page comes back.
 */
// Both SDK modules from one origin. Hosting's /__/firebase/12.4.0/firebase-app.js
// is a full copy of the package while its firebase-auth.js imports @firebase/app
// from www.gstatic.com, so mixing them puts initializeApp and getAuth on two
// different registries: "Component auth has not been registered yet".
import { initializeApp } from "https://www.gstatic.com/firebasejs/12.4.0/firebase-app.js";
import {
  getAuth,
  GoogleAuthProvider,
  EmailAuthProvider,
  signInWithRedirect,
  reauthenticateWithRedirect,
  updateCurrentUser,
  getRedirectResult,
  reauthenticateWithCredential,
  signOut,
  onAuthStateChanged,
  multiFactor,
  getMultiFactorResolver,
  TotpMultiFactorGenerator,
} from "https://www.gstatic.com/firebasejs/12.4.0/firebase-auth.js";
import { API_BASE_URL } from "./config.js";
import { qrSvg } from "./qr.js";

// Hosting's /__/firebase/init.js is the classic-SDK script
// (`firebase.initializeApp({...})`), not a module — there is nothing to
// import from it. The same config as JSON is one fetch away; top-level await
// holds every page's module until it is here, which is what they want anyway.
const firebaseConfig = await fetch("/__/firebase/init.json").then((r) => {
  if (!r.ok) throw new Error(`hosting/init-error: /__/firebase/init.json ${r.status}`);
  return r.json();
});

// Own host as authDomain: see "Redirect, never popup" above. The default
// firebaseapp.com host also serves /__/auth/*, so this is a no-op there.
const app = initializeApp({ ...firebaseConfig, authDomain: window.location.host });
const auth = getAuth(app);
const provider = new GoogleAuthProvider();

/** Error codes this module raises, so pages can branch instead of matching prose. */
export const ERR_NO_SECOND_FACTOR = "console_no_second_factor";
export const ERR_CANCELLED = "console_cancelled";
export const ERR_NO_PASSWORD = "console_no_password";
export const ERR_REAUTH_INCOMPLETE = "console_reauth_incomplete";

/* Redirect bookkeeping. `REAUTH_STARTED` stops a step-up that keeps failing
   from bouncing the tab to Google forever; `AFTER_REAUTH` carries the one line
   the operator should read when the page comes back. sessionStorage is
   per-tab and may be unavailable (private mode), so every access is guarded. */
const REAUTH_STARTED = "semper.reauthStarted";
const AFTER_REAUTH = "semper.afterReauth";
const RESUME = "semper.resume";
const REAUTH_RETRY_SECONDS = 120;

function stash(key, value) {
  try {
    if (value == null) window.sessionStorage.removeItem(key);
    else window.sessionStorage.setItem(key, value);
  } catch (_) { /* no storage: the guard degrades to "always redirect" */ }
}

function unstash(key) {
  try {
    const value = window.sessionStorage.getItem(key);
    window.sessionStorage.removeItem(key);
    return value;
  } catch (_) {
    return null;
  }
}

/** Seconds since this session's sign-in (the token's auth_time), or Infinity. */
async function authAge(user = auth.currentUser) {
  if (!user) return Infinity;
  const result = await user.getIdTokenResult();
  const at = Date.parse(result.authTime);
  return Number.isFinite(at) ? (Date.now() - at) / 1000 : Infinity;
}

/**
 * Leave for Google and never come back to this promise: the page unloads.
 * Holding the caller here stops an `api()` retry from firing with the stale
 * token in the moment between the SDK's navigation and the actual unload.
 *
 * `resume` is what the page was in the middle of, handed back to
 * `requireSignIn`'s callback on the return leg so the operator does not
 * start over inside the backend's freshness window.
 */
async function leaveForGoogle(start, note, resume) {
  stash(REAUTH_STARTED, String(Date.now()));
  stash(AFTER_REAUTH, note);
  stash(RESUME, resume == null ? null : JSON.stringify(resume));
  await start();
  await new Promise(() => {});
}

/** The `resume` stashed by `leaveForGoogle`, or null. */
function unstashResume() {
  try {
    return JSON.parse(unstash(RESUME));
  } catch (_) {
    return null;
  }
}

/* ---------------------------------------------------------------- challenge */

/** Ask the operator for something, returning null if they cancel. */
function ask(message) {
  const value = window.prompt(message);
  return value == null ? null : value.trim();
}

/**
 * Complete a second-factor challenge raised during sign-in or re-auth.
 *
 * TOTP only. The project enrols no SMS factor, so a phone hint cannot reach
 * this code: handling one meant carrying an invisible reCAPTCHA and two more
 * SDK imports for a branch nothing can enter. An unrecognised factor is
 * reported rather than half-handled — a page that cannot challenge a factor
 * should say so, not fail obscurely inside the SDK.
 */
async function resolveChallenge(error) {
  const resolver = getMultiFactorResolver(auth, error);
  const hint = resolver.hints.find(
    (h) => h.factorId === TotpMultiFactorGenerator.FACTOR_ID,
  );
  if (!hint) throw new Error(ERR_NO_SECOND_FACTOR);

  const code = ask("Enter the 6-digit code from your authenticator app:");
  if (!code) throw new Error(ERR_CANCELLED);
  const result = await resolver.resolveSignIn(
    TotpMultiFactorGenerator.assertionForSignIn(hint.uid, code),
  );
  // After a re-authentication *redirect* the SDK resolves the challenge
  // against the user it stashed for the round trip, not the one it restored
  // as `currentUser` on this page load. The fresh tokens then land on an
  // object nobody holds, `currentUser` keeps the old auth_time, and every
  // step-up looks as if it never happened. Adopt the user that actually
  // re-authenticated. (A plain sign-in is already current; a same-page
  // password re-auth resolves against `currentUser` itself.)
  if (result.user && result.user !== auth.currentUser) {
    await updateCurrentUser(auth, result.user);
  }
  return result;
}

/** Sign in. The result — and any second-factor challenge — arrives in
 * `finishRedirect()` after the round-trip. */
function signIn() {
  return signInWithRedirect(auth, provider);
}

/**
 * Collect the result of a sign-in or re-authentication redirect, resolving
 * the second-factor challenge it raises for an enrolled account. Null when
 * this page load is not the return leg of a redirect.
 */
async function finishRedirect() {
  try {
    const result = await getRedirectResult(auth);
    if (result) stash(REAUTH_STARTED, null);
    return result;
  } catch (e) {
    if (e.code !== "auth/multi-factor-auth-required") throw e;
    const result = await resolveChallenge(e);
    stash(REAUTH_STARTED, null);
    return result;
  }
}

/* ------------------------------------------------------------------ step-up */

/**
 * Re-authenticate so the token carries a fresh `auth_time`, then force a token
 * refresh so the *next* request actually carries it.
 *
 * `getIdToken(true)` is not optional here. Without it Firebase serves the
 * cached token, which still has the old auth_time, and the retry fails
 * identically to the call that triggered it — an infinite-looking loop that
 * looks like a backend bug.
 *
 * Google accounts re-auth by redirect, which unloads the page: this function
 * then never returns, the operator comes back signed in afresh and repeats
 * what they were doing. Email/password accounts may pass `password`, which
 * re-authenticates in place. A redirect that came back without a fresh
 * session is not retried — `ERR_REAUTH_INCOMPLETE` instead of a tab that
 * bounces to Google until closed.
 */
export async function stepUp({ password, note, resume } = {}) {
  const user = auth.currentUser;
  if (!user) throw new Error("not_signed_in");
  try {
    if (password != null && password !== "") {
      if (!user.email) throw new Error(ERR_NO_PASSWORD);
      await reauthenticateWithCredential(
        user,
        EmailAuthProvider.credential(user.email, password),
      );
    } else {
      const started = Number(unstash(REAUTH_STARTED));
      if (started && Date.now() - started < REAUTH_RETRY_SECONDS * 1000) {
        throw new Error(ERR_REAUTH_INCOMPLETE);
      }
      await leaveForGoogle(
        () => reauthenticateWithRedirect(user, provider),
        note || "Re-authenticated. Repeat what you were doing — the request " +
          "that asked for it was not sent.",
        resume,
      );
    }
  } catch (e) {
    if (e.code === "auth/multi-factor-auth-required") await resolveChallenge(e);
    else throw e;
  }
  return auth.currentUser.getIdToken(true);
}

/** Whether this account has any second factor enrolled at all. */
export function hasSecondFactor(user = auth.currentUser) {
  return Boolean(user && multiFactor(user).enrolledFactors.length);
}

/** The enrolled factors, for display. */
export function enrolledFactors(user = auth.currentUser) {
  return user ? multiFactor(user).enrolledFactors : [];
}

/** Whether this ID token records a completed second factor for *this* session. */
export async function sessionHasSecondFactor(user = auth.currentUser) {
  if (!user) return false;
  const result = await user.getIdTokenResult();
  const firebase = result.claims && result.claims.firebase;
  return Boolean(firebase && firebase.sign_in_second_factor);
}

/**
 * Enrol TOTP if missing, then ensure this session completed a second factor.
 * Every dashboard login uses this before routing or loading data.
 */
export async function ensureDashboardMfa() {
  const user = auth.currentUser;
  if (!user) throw new Error("not_signed_in");
  if (!hasSecondFactor(user)) {
    setStatus("Enrol an authenticator app to open any Semper dashboard.");
    const enrolment = await beginTotpEnrolment(user.email);
    await enrolInPage(enrolment, user.email);
    setStatus("Authenticator enrolled.");
  }
  if (!(await sessionHasSecondFactor())) {
    setStatus("Confirming your second factor with Google…");
    await stepUp({ note: "Second factor confirmed." });
  }
}

/* --------------------------------------------------------------- enrolment */

/**
 * Begin TOTP enrolment. Returns the secret and a QR URL to show, plus a
 * `finish(code)` that completes it.
 *
 * TOTP rather than SMS for a new enrolment: no per-message cost, no delivery
 * failure abroad, and not interceptable by SIM swap — which matters more here
 * than elsewhere, because this factor is what stands between a stolen password
 * and the ability to mint licences.
 *
 * Enrolment needs a recent sign-in (Firebase's window is five minutes), so
 * this steps up first rather than letting Firebase throw
 * `auth/requires-recent-login` at the operator — but only when the sign-in is
 * not already fresh. It almost always is: enrolment runs straight after the
 * first sign-in, and a redirect there would loop.
 */
const ENROL_FRESH_SECONDS = 240;

export async function beginTotpEnrolment(accountLabel) {
  if ((await authAge()) > ENROL_FRESH_SECONDS) {
    await stepUp({ note: "Re-authenticated. Enrol your authenticator app now." });
  }
  const user = auth.currentUser;
  const session = await multiFactor(user).getSession();
  const secret = await TotpMultiFactorGenerator.generateSecret(session);
  return {
    secret: secret.secretKey,
    qrUrl: secret.generateQrCodeUrl(accountLabel || user.email, "Semper DIC"),
    finish: (code) =>
      multiFactor(user).enroll(
        TotpMultiFactorGenerator.assertionForEnrollment(secret, code),
        "Authenticator app",
      ),
  };
}

/**
 * The enrolment card: a QR code to scan, the key for anyone who cannot, and
 * the code box. Built here rather than in each page's HTML because every
 * console needs it and the account page is the only one with a place of its
 * own for it. Resolves once Firebase accepts a code; rejects ERR_CANCELLED.
 *
 * The secret and the account go in as text nodes — never through innerHTML —
 * and the SVG is the library's own output for a URI we built, so the one
 * innerHTML below carries nothing a user typed.
 */
function enrolInPage(enrolment, account) {
  const card = document.createElement("section");
  card.className = "card enrol";
  card.innerHTML = `
    <h2>Set up two-factor authentication</h2>
    <p class="muted">Scan this with Google Authenticator, Microsoft
      Authenticator, Authy, 1Password or any authenticator app, then enter
      the 6-digit code it shows.</p>
    <div class="qr"></div>
    <details>
      <summary>Can't scan? Enter the key by hand</summary>
      <p class="mono key"></p>
      <p class="muted">Account: <span class="mono account"></span> · Issuer:
        Semper DIC · Time-based, 6 digits, 30 seconds</p>
    </details>
    <div class="row">
      <input inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]*"
             placeholder="6-digit code" size="12" />
      <button class="confirm">Confirm</button>
      <button class="secondary cancel">Cancel</button>
    </div>
    <p class="muted err feedback"></p>`;
  card.querySelector(".qr").innerHTML = qrSvg(enrolment.qrUrl);
  card.querySelector(".key").textContent = enrolment.secret;
  card.querySelector(".account").textContent = account;

  const anchor = document.getElementById("status") || document.querySelector("main");
  anchor.insertAdjacentElement("afterend", card);
  const input = card.querySelector("input");
  const feedback = card.querySelector(".feedback");
  input.focus();

  return new Promise((resolve, reject) => {
    const done = (fn, value) => { card.remove(); fn(value); };
    const confirm = async () => {
      const code = input.value.trim();
      if (!code) return;
      feedback.textContent = "";
      card.querySelector(".confirm").disabled = true;
      try {
        await enrolment.finish(code);
        done(resolve);
      } catch (e) {
        card.querySelector(".confirm").disabled = false;
        feedback.textContent =
          `That code was not accepted (${e.code || e.message}). ` +
          "Check the phone's clock is set automatically, then try the next code.";
      }
    };
    card.querySelector(".confirm").addEventListener("click", confirm);
    input.addEventListener("keydown", (e) => { if (e.key === "Enter") confirm(); });
    card.querySelector(".cancel").addEventListener("click", () =>
      done(reject, new Error(ERR_CANCELLED)));
  });
}

/* ------------------------------------------------------------------- shell */

/**
 * Run `onReady(user, resume)` once someone is signed in *and* has completed
 * dashboard MFA (enrolled TOTP + this session's second factor). Called by
 * every console before it fetches anything. `resume` is whatever the page
 * stashed before a re-authentication redirect that has just completed, or
 * null.
 */
export function requireSignIn(onReady) {
  const signInBtn = document.getElementById("signIn");
  const signOutBtn = document.getElementById("signOut");
  const who = document.getElementById("who");
  const appEl = document.getElementById("app");

  signInBtn.addEventListener("click", () => {
    signIn().catch((e) => setStatus(`Sign-in failed: ${e.code || e.message}`, true));
  });
  signOutBtn.addEventListener("click", () => signOut(auth));

  // The return leg of a redirect resolves here. The auth listener below
  // waits for it: after a re-authentication the SDK reports the persisted
  // user before the second-factor prompt is answered, and judging that stale
  // token would send the page back to Google (or trip the loop guard). A
  // cancelled TOTP prompt during sign-in simply leaves nobody signed in.
  const afterReauth = unstash(AFTER_REAUTH);
  const stashedResume = unstashResume();
  const redirectDone = finishRedirect()
    .then((result) => {
      if (result && afterReauth) setStatus(afterReauth);
      return result ? stashedResume : null;
    })
    .catch((e) => {
      if (e.message === ERR_CANCELLED) {
        setStatus("Sign-in cancelled — the authenticator code was not entered.");
        return null;
      }
      setStatus(`Sign-in failed: ${e.code || e.message}`, true);
      return null;
    });

  const signedOut = document.getElementById("signedOut");

  // The page is started once per signed-in account. The return leg of a
  // re-authentication fires this listener twice — once for the user restored
  // from storage, again when `resolveChallenge` makes the re-authenticated
  // object current — and both calls wait on the same redirect, so without
  // this the page loaded twice and both copies were handed the same `resume`:
  // two revoke confirmations, and the second load wiping whatever the first
  // reported.
  let readyUid = null;
  let resumeHanded = false;
  onAuthStateChanged(auth, async () => {
    const stashed = await redirectDone;
    // Read after the redirect settled: a resolved re-authentication may have
    // replaced the user object the listener was called with.
    const user = auth.currentUser;
    const signedIn = Boolean(user);
    signInBtn.hidden = signedIn;
    signOutBtn.hidden = !signedIn;
    if (signedOut) signedOut.hidden = signedIn;
    who.textContent = signedIn ? user.email : "";
    if (!signedIn) {
      readyUid = null;
      appEl.hidden = true;
      return;
    }
    if (user.uid === readyUid) return;
    readyUid = user.uid;
    // Whatever was stashed is handed over once, on the first ready state.
    const resume = resumeHanded ? null : stashed;
    resumeHanded = true;
    try {
      await ensureDashboardMfa();
      appEl.hidden = false;
      onReady(user, resume);
    } catch (e) {
      readyUid = null;
      if (e.message === ERR_CANCELLED) {
        setStatus("Two-factor authentication is required for every dashboard.");
        await signOut(auth);
        return;
      }
      appEl.hidden = true;
      if (e.message === ERR_REAUTH_INCOMPLETE) {
        setStatus("Re-authentication with Google did not complete. Sign out, " +
          "then sign in again.", true);
        return;
      }
      setStatus(`Could not open the dashboard: ${e.code || e.message}`, true);
    }
  });
}

/**
 * A call to the Semper API carrying a fresh ID token.
 *
 * On `reauth_required` the operator is re-authenticated once and the call is
 * retried, because that response means "prove it again", not "you may not".
 * Exactly once: a second failure is a real refusal, and retrying forever would
 * trap someone in a popup loop.
 *
 * Throws an Error whose message is the backend's own error code where there is
 * one, so callers can distinguish e.g. `no_floating_seat` from a real fault
 * rather than showing every failure as "something went wrong".
 */
/**
 * A request-validation 422 carries a list of `{msg, loc}` rather than a code;
 * as an Error message that list read "[object Object]". Its sentences are
 * the useful part ("maxAnalyses must be at least 25…").
 */
function errorDetail(detail) {
  if (!Array.isArray(detail)) return detail;
  return detail
    .map((d) => String((d && d.msg) || d).replace(/^Value error, /, ""))
    .join("; ");
}

export async function api(path, options = {}, { allowStepUp = true } = {}) {
  const send = async () => {
    const user = auth.currentUser;
    if (!user) throw new Error("not_signed_in");
    const token = await user.getIdToken();
    const resp = await fetch(`${API_BASE_URL}${path}`, {
      ...options,
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
        ...(options.headers || {}),
      },
    });
    const text = await resp.text();
    const body = text ? JSON.parse(text) : {};
    if (!resp.ok) throw new Error(errorDetail(body.detail) || `http_${resp.status}`);
    return body;
  };

  try {
    return await send();
  } catch (e) {
    if (e.message === "reauth_required" && allowStepUp) {
      await stepUp();
      return send();
    }
    throw e;
  }
}

/**
 * A call that answers with bytes rather than JSON — today, a session bundle.
 *
 * `api()` cannot serve this: it reads the whole response as text and parses
 * it as JSON, which would both corrupt a zip and throw on its first byte. The
 * step-up retry is why this is not a bare `fetch` either — the bundle route
 * sits at the user step-up tier, so a tab left open past the re-authentication
 * window answers `reauth_required` to a download the caller is entitled to.
 *
 * Failures are still JSON, so the error path reads the body the way `api()`
 * does and throws the backend's own code.
 */
export async function apiBlob(path, options = {}) {
  const send = async () => {
    const user = auth.currentUser;
    if (!user) throw new Error("not_signed_in");
    const token = await user.getIdToken();
    const resp = await fetch(`${API_BASE_URL}${path}`, {
      ...options,
      headers: { Authorization: `Bearer ${token}`, ...(options.headers || {}) },
    });
    if (!resp.ok) {
      let code = `http_${resp.status}`;
      // A refusal from the gateway rather than the app is not JSON, and the
      // status line is then the whole of what we know.
      try {
        code = JSON.parse(await resp.text()).detail || code;
      } catch { /* keep http_<status> */ }
      throw new Error(code);
    }
    return resp.blob();
  };

  try {
    return await send();
  } catch (e) {
    if (e.message === "reauth_required") {
      await stepUp();
      return send();
    }
    throw e;
  }
}

/**
 * Hand a Blob to the browser as a download.
 *
 * The backend streams the archive so that its own memory stays flat; the
 * browser still holds the whole thing, because a page cannot write to the
 * filesystem incrementally without the File System Access API, which is not
 * available everywhere and would need a permission prompt of its own. An
 * analysis is tens of megabytes, so this is a cost worth paying for a
 * download that works the same way in every browser.
 */
export function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  // Not immediately: Safari reads the href after the click returns.
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
}

/** Write a message into the page's status line. */
export function setStatus(message, isError = false) {
  const el = document.getElementById("status");
  if (!el) return;
  el.textContent = message;
  el.className = isError ? "muted err" : "muted";
}

// Pure helpers live in util.js, where `node --test` can reach them; the pages
// keep importing them from here.
export { esc, when } from "./util.js";

/**
 * Confirm a destructive act by making the operator type the thing's name.
 *
 * A yes/no dialog is muscle memory by the third licence of the afternoon;
 * typing `SEMP-4K2P` is not something a hand does absent-mindedly. Revoking
 * the wrong licence drops a whole institution to demo, so the friction is the
 * point.
 */
export function confirmByTyping(label, what) {
  const typed = window.prompt(
    `This cannot be undone.\n\nType ${label} to ${what}:`,
  );
  return typed != null && typed.trim() === label;
}

/**
 * Fresh password (or Google re-auth) plus TOTP before whole-licence revoke.
 *
 * The backend refuses a revoke on a stale MFA session
 * (ADMIN_WEB_REVOKE_REAUTH_SECONDS, 120 s). Step up here so the token's
 * auth_time is new. A Google account steps up by redirect, which unloads the
 * page: `resume` is handed back to the desk on the return leg so it can
 * finish the revoke with one confirmation, inside the window, rather than
 * asking the operator to find the row and retype the key.
 */
const REVOKE_FRESH_SECONDS = 90;

export async function stepUpForRevoke(resume) {
  if ((await authAge()) < REVOKE_FRESH_SECONDS) return;
  const password = ask(
    "Re-enter your account password to revoke this licence.\n\n" +
      "Leave blank to re-authenticate with Google, then enter your " +
      "authenticator code when asked.",
  );
  if (password === null) throw new Error(ERR_CANCELLED);
  await stepUp({
    password: password || undefined,
    note: "Re-authenticated.",
    resume,
  });
}

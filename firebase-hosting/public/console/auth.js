/* Sign-in, second factor, and API access shared by both consoles.
 *
 * The Firebase SDK is loaded from Firebase Hosting's own reserved namespace
 * (/__/firebase/...), which is served from THIS origin. That is what lets the
 * strict `script-src 'self'` policy stay as it is — a CDN script would have
 * needed it relaxed. Only `connect-src` has to be widened, and only for the
 * console paths (see firebase-hosting/firebase.json).
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
 * transparently: on `reauth_required` it re-authenticates once and retries,
 * so an operator sees a popup rather than an error.
 */
import { initializeApp } from "/__/firebase/12.4.0/firebase-app.js";
import {
  getAuth,
  GoogleAuthProvider,
  signInWithPopup,
  reauthenticateWithPopup,
  signOut,
  onAuthStateChanged,
  multiFactor,
  getMultiFactorResolver,
  TotpMultiFactorGenerator,
} from "/__/firebase/12.4.0/firebase-auth.js";
import { firebaseConfig } from "/__/firebase/init.js";
import { API_BASE_URL } from "./config.js";

const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
const provider = new GoogleAuthProvider();

/** Error codes this module raises, so pages can branch instead of matching prose. */
export const ERR_NO_SECOND_FACTOR = "console_no_second_factor";
export const ERR_CANCELLED = "console_cancelled";

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
  return resolver.resolveSignIn(
    TotpMultiFactorGenerator.assertionForSignIn(hint.uid, code),
  );
}

/** Sign in, resolving a second-factor challenge if one is raised. */
async function signIn() {
  try {
    return await signInWithPopup(auth, provider);
  } catch (e) {
    if (e.code === "auth/multi-factor-auth-required") return resolveChallenge(e);
    throw e;
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
 */
export async function stepUp() {
  const user = auth.currentUser;
  if (!user) throw new Error("not_signed_in");
  try {
    await reauthenticateWithPopup(user, provider);
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
 * Enrolment needs a recent sign-in, so this steps up first rather than letting
 * Firebase throw `auth/requires-recent-login` at the operator.
 */
export async function beginTotpEnrolment(accountLabel) {
  await stepUp();
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

/* ------------------------------------------------------------------- shell */

/**
 * Run `onReady(user)` once someone is signed in, wiring the header's sign-in
 * and sign-out buttons. Called by both consoles before they fetch anything.
 */
export function requireSignIn(onReady) {
  const signInBtn = document.getElementById("signIn");
  const signOutBtn = document.getElementById("signOut");
  const who = document.getElementById("who");
  const appEl = document.getElementById("app");

  signInBtn.addEventListener("click", () => {
    signIn().catch((e) => {
      if (e.message === ERR_CANCELLED) return;
      setStatus(`Sign-in failed: ${e.code || e.message}`, true);
    });
  });
  signOutBtn.addEventListener("click", () => signOut(auth));

  onAuthStateChanged(auth, (user) => {
    const signedIn = Boolean(user);
    signInBtn.hidden = signedIn;
    signOutBtn.hidden = !signedIn;
    who.textContent = signedIn ? user.email : "";
    appEl.hidden = !signedIn;
    if (signedIn) onReady(user);
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
    if (!resp.ok) throw new Error(body.detail || `http_${resp.status}`);
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

/** Escape text before it reaches innerHTML. Emails and names come from users. */
export function esc(value) {
  return String(value ?? "").replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );
}

/** A short, readable rendering of an ISO instant, or an em dash for null. */
export function when(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleString();
}

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

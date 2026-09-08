/* Sign-in and API access shared by both consoles.
 *
 * The Firebase SDK is loaded from Firebase Hosting's own reserved namespace
 * (/__/firebase/...), which is served from THIS origin. That is what lets the
 * strict `script-src 'self'` policy stay as it is — a CDN script would have
 * needed it relaxed. Only `connect-src` has to be widened, and only for the
 * console paths (see firebase-hosting/firebase.json).
 *
 * There is no build step and no framework here on purpose: the site is static
 * files, and a toolchain for two pages would cost more than it saves.
 */
import { initializeApp } from "/__/firebase/12.4.0/firebase-app.js";
import {
  getAuth,
  GoogleAuthProvider,
  signInWithPopup,
  signOut,
  onAuthStateChanged,
} from "/__/firebase/12.4.0/firebase-auth.js";
import { firebaseConfig } from "/__/firebase/init.js";
import { API_BASE_URL } from "./config.js";

const auth = getAuth(initializeApp(firebaseConfig));

/**
 * Run `onReady(user)` once someone is signed in, wiring the header's sign-in
 * and sign-out buttons. Called by both consoles before they fetch anything.
 */
export function requireSignIn(onReady) {
  const signInBtn = document.getElementById("signIn");
  const signOutBtn = document.getElementById("signOut");
  const who = document.getElementById("who");
  const app = document.getElementById("app");

  signInBtn.addEventListener("click", () => {
    signInWithPopup(auth, new GoogleAuthProvider()).catch((e) =>
      setStatus(`Sign-in failed: ${e.code || e.message}`, true),
    );
  });
  signOutBtn.addEventListener("click", () => signOut(auth));

  onAuthStateChanged(auth, (user) => {
    const signedIn = Boolean(user);
    signInBtn.hidden = signedIn;
    signOutBtn.hidden = !signedIn;
    who.textContent = signedIn ? user.email : "";
    app.hidden = !signedIn;
    if (signedIn) onReady(user);
  });
}

/**
 * A call to the Semper API carrying a fresh ID token.
 *
 * The token is fetched per call rather than cached: it expires hourly, and
 * getIdToken() serves a cached one until it is close to expiry anyway.
 *
 * Throws an Error whose message is the backend's own error code where there is
 * one, so callers can distinguish e.g. `no_floating_seat` from a real fault
 * rather than showing every failure as "something went wrong".
 */
export async function api(path, options = {}) {
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

// Run: node --test "firebase-hosting/tests/*.test.mjs"
// What a refusal reads as (console/messages.js `explain`, util.js
// `authErrorText`): every code a console route can return has a sentence; a
// gateway status or a dropped connection says the server did not answer; and
// a code nobody wrote a sentence for is shown as a reference after a
// sentence, never as the message itself.
import { test } from "node:test";
import assert from "node:assert/strict";
import { explain, COMMON, REASONS, GATEWAY } from "../public/console/messages.js";
import { authErrorText, reauthFailureText } from "../public/console/util.js";

/** An ApiError as api.js builds it, without loading auth.js. */
const refusal = (status, detail) => Object.assign(new Error(detail || `http_${status}`), {
  name: "ApiError", status, code: (detail || `http_${status}`).split(":")[0].trim(),
});

test("every platform and route code the consoles meet has a sentence", () => {
  for (const code of [
    "internal_error", "firestore_unreachable", "feature_paused", "request_too_large",
    "empty_patch", "user_not_found", "invite_not_found",
  ]) {
    assert.equal(typeof REASONS[code], "string", code);
    assert.doesNotMatch(REASONS[code], /_/, `${code} reads as a sentence`);
    // After what the page was doing, not in place of it.
    assert.equal(explain(refusal(409, code), {}, (t) => `Could not do it: ${t}`), `Could not do it: ${REASONS[code]}`, code);
  }
  // A gateway refusal is its status alone (api.js: http_<status>).
  for (const status of [500, 502, 503, 504]) {
    assert.equal(explain(refusal(status)), GATEWAY[status], String(status));
    assert.doesNotMatch(GATEWAY[status], /http/);
  }
  assert.equal(explain(refusal(418), {}, (t) => t), "the server refused it (ref: http_418)");
});

test("a dropped connection says the server did not answer", () => {
  assert.equal(explain(new TypeError("Failed to fetch"), {}, (t) => `Could not delete: ${t}`),
    `Could not delete: ${REASONS.network}`);
  assert.equal(explain(new TypeError("NetworkError when attempting to fetch resource.")), REASONS.network);
  assert.doesNotMatch(REASONS.network, /fetch/i);
});

test("a code with no sentence is a reference, after the page's own words", () => {
  assert.equal(explain(refusal(409, "strange_code"), {}, (text) => `Could not do it: ${text}`),
    "Could not do it: the server refused it (ref: strange_code)");
  assert.equal(explain(refusal(409, "strange_code: 12")), "the server refused it (ref: strange_code)");
  // A validation 422's sentences are already words: shown as they came.
  assert.equal(explain(refusal(422, "maxAnalyses must be at least 25")), "maxAnalyses must be at least 25");
  // A page's own sentence and a common one still win.
  assert.equal(explain(refusal(429, "rate_limited")), COMMON.rate_limited);
  assert.equal(explain(refusal(409, "x_y"), { x_y: "Mine." }), "Mine.");
});

test("Firebase sign-in codes read as words; an unknown one is a reference", () => {
  assert.equal(authErrorText({ code: "auth/network-request-failed" }),
    "the sign-in service did not answer — check your connection");
  assert.equal(authErrorText({ code: "auth/too-many-requests" }), "too many attempts — wait a few minutes");
  assert.equal(authErrorText({ code: "auth/odd-thing" }), "an unexpected sign-in error (ref: auth/odd-thing)");
  assert.equal(authErrorText(new Error("hosting/init-error: /__/firebase/init.json 404")),
    "an unexpected sign-in error (ref: hosting/init-error)");
  assert.equal(reauthFailureText("auth/network-request-failed"),
    "the sign-in service did not answer — check your connection");
  assert.equal(reauthFailureText("weird"), "re-authentication did not finish (ref: weird)");
});

test("an expired sign-in says to sign in again, and a step-up's Firebase failure reads as words", () => {
  assert.equal(explain(refusal(401, "invalid_token"), {}, (t) => `Could not issue: ${t}`),
    "Your sign-in has expired. Sign in again, then repeat that.");
  // A step-up failure reaching a page's fallback was "Firebase: Error (auth/…)."
  const lapsed = Object.assign(new Error("Firebase: Error (auth/invalid-multi-factor-session)."),
    { code: "auth/invalid-multi-factor-session" });
  assert.equal(explain(lapsed, {}, (t) => `Could not create it: ${t}`),
    "Could not create it: the authenticator code came too late");
  for (const code of ["auth/user-token-expired", "auth/invalid-user-token", "auth/requires-recent-login"]) {
    assert.match(authErrorText({ code }), /sign in again/, code);
  }
});

test("an account at Firebase's authenticator limit is told who can clear it", () => {
  assert.match(authErrorText({ code: "auth/maximum-second-factor-count-exceeded" }),
    /as many authenticators as it can — ask semper support/);
});

// AE3-108: a kill switch reads one way everywhere: paused right now.
test("a paused feature says it is paused right now", () => {
  assert.equal(explain(refusal(503, "feature_paused"), {}, (t) => `Could not export: ${t}`),
    "Could not export: That is paused right now. Try again later.");
});

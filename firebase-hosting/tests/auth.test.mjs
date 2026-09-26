// Run: node --test "firebase-hosting/tests/*.test.mjs"
// Behaviour of console/auth.js — API calls, step-up, second factor, enrolment
// and the revoke gate — against the fake Firebase SDK in fakes/ (see
// harness.mjs for how the gstatic imports are redirected).
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import {
  test, fake, FakeUser, readyUser, reset, settle, stillPending, rejection,
  net, json, storage, prompts, page, $, loadAuth,
} from "./harness.mjs";

// Dynamic, so that these load after harness.mjs registered its hooks.
const auth = await loadAuth();
const { API_BASE_URL } = await import("../public/console/config.js");

const REAUTH_STARTED = "semper.reauthStarted";
const AFTER_REAUTH = "semper.afterReauth";
const RESUME = "semper.resume";

beforeEach(reset);
afterEach(settle);

function signIn(user) {
  fake.auth.currentUser = user;
  return user;
}

/** A step-up that finishes in the page: the SDK raises the TOTP challenge instead of navigating. */
function stepUpCompletesInPage(user, code = "123456") {
  fake.onReauthWithRedirect = async () => { throw fake.mfaError({ user }); };
  prompts.answer(code);
}

const bearer = (request) => request.headers.Authorization;

/* ------------------------------------------------------------------- api */

test("api sends the ID token as a bearer to API_BASE_URL + path", async () => {
  signIn(readyUser());
  net.reply(json(200, { ok: true }));
  const out = await auth.api("/v1/admin/licenses", {
    method: "POST",
    body: JSON.stringify({ seats: 3 }),
    headers: { "X-Extra": "1" },
  });
  assert.deepEqual(out, { ok: true });
  assert.equal(net.requests.length, 1);
  const [request] = net.requests;
  assert.equal(request.url, `${API_BASE_URL}/v1/admin/licenses`);
  assert.equal(request.method, "POST");
  assert.equal(request.body, '{"seats":3}');
  assert.deepEqual(request.headers, {
    Authorization: "Bearer token-uid-1-v1",
    "Content-Type": "application/json",
    "X-Extra": "1",
  });
  // The cached token: nothing forces a refresh on an ordinary call.
  assert.deepEqual(fake.callsTo("getIdToken"), [["uid-1", false]]);
});

test("api refuses to send anything without a signed-in user", async () => {
  const e = await rejection(auth.api("/v1/me"));
  assert.equal(e.message, "not_signed_in");
  assert.equal(net.requests.length, 0);
});

test("api throws the backend's error code, or http_<status> when there is none", async () => {
  signIn(readyUser());
  net.reply(
    json(409, { detail: "no_floating_seat" }),
    json(500),
    json(422, { detail: [
      { msg: "Value error, maxAnalyses must be at least 25", loc: ["body"] },
      { msg: "seats must be positive", loc: ["body"] },
    ] }),
    json(200),
  );
  assert.equal((await rejection(auth.api("/v1/a"))).message, "no_floating_seat");
  assert.equal((await rejection(auth.api("/v1/b"))).message, "http_500");
  assert.equal((await rejection(auth.api("/v1/c"))).message,
    "maxAnalyses must be at least 25; seats must be positive");
  assert.deepEqual(await auth.api("/v1/d"), {});
});

test("reauth_required leaves for Google once and never retries with the stale token", async () => {
  signIn(readyUser());
  net.reply(json(403, { detail: "reauth_required" }));
  const call = auth.api("/v1/admin/licenses/L1/revoke", { method: "POST" });
  assert.ok(await stillPending(call), "the page is unloading; the call must not settle");
  assert.equal(net.requests.length, 1);
  assert.deepEqual(fake.callsTo("reauthenticateWithRedirect"), [["uid-1", "google.com"]]);
  assert.ok(Date.now() - Number(storage.getItem(REAUTH_STARTED)) < 5000);
  assert.match(storage.getItem(AFTER_REAUTH), /Repeat what you were doing/);
});

test("a step-up that completes in the page retries once, with a refreshed token", async () => {
  const user = signIn(readyUser({ authAgeSeconds: 3600 }));
  stepUpCompletesInPage(user, " 123456 ");
  net.reply(json(403, { detail: "reauth_required" }), json(200, { revoked: true }));
  assert.deepEqual(await auth.api("/v1/admin/licenses/L1/revoke", { method: "POST" }),
    { revoked: true });
  assert.deepEqual(net.requests.map(bearer), ["Bearer token-uid-1-v1", "Bearer token-uid-1-v2"]);
  assert.deepEqual(fake.callsTo("resolveSignIn"),
    [[{ kind: "signIn", enrollmentId: "totp-1", code: "123456" }]]);
  assert.match(prompts.asked[0], /6-digit code/);
});

test("a second reauth_required after the step-up is a refusal, not a loop", async () => {
  const user = signIn(readyUser());
  stepUpCompletesInPage(user);
  net.reply(json(403, { detail: "reauth_required" }), json(403, { detail: "reauth_required" }));
  const e = await rejection(auth.api("/v1/admin/licenses/L1/revoke", { method: "POST" }));
  assert.equal(e.message, "reauth_required");
  assert.equal(net.requests.length, 2);
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 1);
});

test("allowStepUp:false surfaces reauth_required without re-authenticating", async () => {
  signIn(readyUser());
  net.reply(json(403, { detail: "reauth_required" }));
  const e = await rejection(auth.api("/v1/me", {}, { allowStepUp: false }));
  assert.equal(e.message, "reauth_required");
  assert.equal(net.requests.length, 1);
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 0);
  assert.equal(storage.getItem(REAUTH_STARTED), null);
});

test("mfa_required is handed to the page, not stepped up", async () => {
  // No factor on the token at all: re-authenticating cannot fix that, and the
  // pages word it themselves (operator.js, account.js).
  signIn(readyUser());
  net.reply(json(403, { detail: "mfa_required" }));
  const e = await rejection(auth.api("/v1/admin/licenses", { method: "POST" }));
  assert.equal(e.message, "mfa_required");
  assert.equal(net.requests.length, 1);
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 0);
});

test("a cancelled authenticator prompt during step-up surfaces ERR_CANCELLED and sends nothing more", async () => {
  const user = signIn(readyUser());
  stepUpCompletesInPage(user, null);
  net.reply(json(403, { detail: "reauth_required" }));
  const e = await rejection(auth.api("/v1/admin/licenses/L1/revoke", { method: "POST" }));
  assert.equal(e.message, auth.ERR_CANCELLED);
  assert.equal(net.requests.length, 1);
  assert.equal(fake.callsTo("resolveSignIn").length, 0);
});

test("a Google redirect started under 120 s ago is not repeated by an api retry", async () => {
  signIn(readyUser());
  storage.setItem(REAUTH_STARTED, String(Date.now() - 30_000));
  net.reply(json(403, { detail: "reauth_required" }));
  const e = await rejection(auth.api("/v1/admin/licenses/L1/revoke", { method: "POST" }));
  assert.equal(e.message, auth.ERR_REAUTH_INCOMPLETE);
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 0);
  assert.equal(storage.getItem(REAUTH_STARTED), null, "the guard is spent once read");
});

/* --------------------------------------------------------------- apiBlob */

test("apiBlob sends the bearer without a JSON content type and returns the bytes", async () => {
  signIn(readyUser());
  net.reply(new Response(new Uint8Array([0x50, 0x4b, 0x03, 0x04]), { status: 200 }));
  const blob = await auth.apiBlob("/v1/sessions/s1/bundle");
  assert.deepEqual([...new Uint8Array(await blob.arrayBuffer())], [0x50, 0x4b, 0x03, 0x04]);
  assert.equal(net.requests[0].url, `${API_BASE_URL}/v1/sessions/s1/bundle`);
  assert.deepEqual(net.requests[0].headers, { Authorization: "Bearer token-uid-1-v1" });
});

test("apiBlob throws the JSON error code, or http_<status> for a gateway refusal", async () => {
  signIn(readyUser());
  net.reply(json(404, { detail: "session_not_found" }), new Response("<html>bad gateway", { status: 502 }));
  assert.equal((await rejection(auth.apiBlob("/v1/sessions/s1/bundle"))).message, "session_not_found");
  assert.equal((await rejection(auth.apiBlob("/v1/sessions/s1/bundle"))).message, "http_502");
});

test("apiBlob steps up on reauth_required and retries once with a refreshed token", async () => {
  const user = signIn(readyUser());
  stepUpCompletesInPage(user);
  net.reply(json(403, { detail: "reauth_required" }), new Response("zip", { status: 200 }));
  const blob = await auth.apiBlob("/v1/sessions/s1/bundle");
  assert.equal(await blob.text(), "zip");
  assert.deepEqual(net.requests.map(bearer), ["Bearer token-uid-1-v1", "Bearer token-uid-1-v2"]);
});

/* ---------------------------------------------------------------- stepUp */

test("stepUp needs a signed-in user", async () => {
  assert.equal((await rejection(auth.stepUp())).message, "not_signed_in");
});

test("a password steps up in place and forces a token refresh", async () => {
  const user = signIn(new FakeUser({ providers: ["password"], password: "hunter2", authAgeSeconds: 3600 }));
  const token = await auth.stepUp({ password: "hunter2" });
  assert.equal(token, "token-uid-1-v2");
  assert.deepEqual(fake.callsTo("reauthenticateWithCredential"),
    [["uid-1", { providerId: "password", email: "operator@example.com", password: "hunter2" }]]);
  assert.deepEqual(fake.callsTo("getIdToken").at(-1), ["uid-1", true]);
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 0);
  assert.ok(Date.now() - user.authTime < 5000);
});

test("a password step-up on an enrolled account answers the TOTP challenge", async () => {
  const user = signIn(new FakeUser({ providers: ["password"], password: "pw", factors: [{}] }));
  prompts.answer(" 654321 ");
  await auth.stepUp({ password: "pw" });
  assert.deepEqual(fake.callsTo("resolveSignIn"),
    [[{ kind: "signIn", enrollmentId: "totp-1", code: "654321" }]]);
  assert.equal(user.secondFactor, true);
});

test("an account without an email cannot step up with a password", async () => {
  signIn(new FakeUser({ email: null, providers: ["password"] }));
  assert.equal((await rejection(auth.stepUp({ password: "pw" }))).message, auth.ERR_NO_PASSWORD);
  assert.equal(fake.callsTo("reauthenticateWithCredential").length, 0);
});

test("a challenge with no TOTP factor is reported, not half-handled", async () => {
  signIn(new FakeUser({ providers: ["password"] }));
  fake.onReauthWithCredential = async () => {
    throw fake.mfaError({ hints: [{ uid: "p1", factorId: "phone" }] });
  };
  assert.equal((await rejection(auth.stepUp({ password: "pw" }))).message,
    auth.ERR_NO_SECOND_FACTOR);
  assert.equal(prompts.asked.length, 0);
});

test("a wrong authenticator code rejects with Firebase's code", async () => {
  signIn(new FakeUser({ providers: ["password"] }));
  fake.onReauthWithCredential = async () => {
    throw fake.mfaError({ reject: "auth/invalid-verification-code" });
  };
  prompts.answer("000000");
  const e = await rejection(auth.stepUp({ password: "pw" }));
  assert.equal(e.code, "auth/invalid-verification-code");
});

test("a Google step-up stashes the note and resume, then never returns", async () => {
  signIn(readyUser());
  const resume = { action: "revoke", id: "L1" };
  const call = auth.stepUp({ note: "Re-authenticated.", resume });
  assert.ok(await stillPending(call));
  assert.deepEqual(fake.callsTo("reauthenticateWithRedirect"), [["uid-1", "google.com"]]);
  assert.equal(storage.getItem(AFTER_REAUTH), "Re-authenticated.");
  assert.deepEqual(JSON.parse(storage.getItem(RESUME)), resume);
  assert.deepEqual(fake.callsTo("getIdToken"), [], "no request may fire with the stale token");
});

test("the redirect-loop guard: under 120 s refused unless the operator asked; older retried", async () => {
  signIn(readyUser());
  storage.setItem(REAUTH_STARTED, String(Date.now() - 30_000));
  assert.equal((await rejection(auth.stepUp())).message, auth.ERR_REAUTH_INCOMPLETE);
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 0);

  storage.setItem(REAUTH_STARTED, String(Date.now() - 30_000));
  assert.ok(await stillPending(auth.stepUp({ operatorAsked: true })));
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 1);

  storage.setItem(REAUTH_STARTED, String(Date.now() - 121_000));
  assert.ok(await stillPending(auth.stepUp()));
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 2);
});

test("a resolved challenge adopts the user that re-authenticated", async () => {
  // After a redirect the SDK resolves against the user it stashed for the
  // round trip, not currentUser; without adoption currentUser keeps the old
  // auth_time and every step-up looks as if it never happened.
  const stale = signIn(new FakeUser({ providers: ["password"], factors: [{}], authAgeSeconds: 3600 }));
  const fresh = new FakeUser({ providers: ["password"], factors: [{}], authAgeSeconds: 3600 });
  fake.onReauthWithCredential = async () => { throw fake.mfaError({ user: fresh }); };
  prompts.answer("123456");
  const token = await auth.stepUp({ password: "pw" });
  assert.deepEqual(fake.callsTo("updateCurrentUser"), [["uid-1"]]);
  assert.equal(fake.auth.currentUser, fresh);
  assert.notEqual(fake.auth.currentUser, stale);
  assert.equal(token, "token-uid-1-v2");
  assert.equal(fresh.secondFactor, true);
});

/* ------------------------------------------------------- second factor */

test("sessionHasSecondFactor reads the token's sign_in_second_factor claim", async () => {
  assert.equal(await auth.sessionHasSecondFactor(), false, "signed out");
  signIn(new FakeUser({ factors: [{}], secondFactor: false }));
  assert.equal(await auth.sessionHasSecondFactor(), false, "enrolled, but not proved this session");
  signIn(new FakeUser({ factors: [{}], secondFactor: true }));
  assert.equal(await auth.sessionHasSecondFactor(), true);
});

test("hasSecondFactor and enrolledFactors read the enrolment, not the session", () => {
  assert.equal(auth.hasSecondFactor(), false);
  assert.deepEqual(auth.enrolledFactors(), []);
  signIn(new FakeUser({ factors: [{ displayName: "Phone app" }] }));
  assert.equal(auth.hasSecondFactor(), true);
  assert.equal(auth.enrolledFactors()[0].displayName, "Phone app");
  signIn(new FakeUser());
  assert.equal(auth.hasSecondFactor(), false);
});

/* -------------------------------------------------------- dashboard MFA */

test("ensureDashboardMfa needs a signed-in user", async () => {
  assert.equal((await rejection(auth.ensureDashboardMfa())).message, "not_signed_in");
});

test("ensureDashboardMfa passes an enrolled session that proved its factor, touching nothing", async () => {
  signIn(readyUser());
  await auth.ensureDashboardMfa();
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 0);
  assert.equal(fake.callsTo("generateSecret").length, 0);
  assert.equal(page.inserted.length, 0);
});

test("ensureDashboardMfa sends an enrolled session without the factor back to Google", async () => {
  signIn(new FakeUser({ factors: [{}], secondFactor: false }));
  assert.ok(await stillPending(auth.ensureDashboardMfa()));
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 1);
  assert.equal(storage.getItem(AFTER_REAUTH), "Second factor confirmed.");
  assert.equal($("status").textContent, "Confirming your second factor with Google…");
});

/** The enrolment card auth.js put on the page. */
function enrolCard() {
  const card = page.inserted.find((e) => e.className === "card enrol");
  assert.ok(card, "enrolment card shown");
  return card;
}

test("an account with no factor must enrol TOTP in the page before anything else", async () => {
  const user = signIn(new FakeUser({ email: "it@example.edu" }));
  const done = auth.ensureDashboardMfa();
  await settle();
  assert.equal($("status").textContent, "Enrol an authenticator app to open any Semper dashboard.");
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 0, "a fresh sign-in enrols without a step-up");

  const card = enrolCard();
  assert.equal(card.anchor, $("status"));
  assert.equal(card.position, "afterend");
  assert.match(card.querySelector(".qr").innerHTML, /^<svg/);
  assert.equal(card.querySelector(".key").textContent, "TESTSECRETBASE32");
  assert.equal(card.querySelector(".account").textContent, "it@example.edu");
  const input = card.querySelector("input");
  assert.ok(input.focused);

  // An empty box does nothing; a wrong code says so and leaves the card up.
  card.querySelector(".confirm").click();
  input.value = "000000";
  card.querySelector(".confirm").click();
  await settle();
  assert.match(card.querySelector(".feedback").textContent, /auth\/invalid-verification-code/);
  assert.equal(card.querySelector(".confirm").disabled, false);
  assert.ok(!card.removed);

  // Enter submits too.
  input.value = " 123456 ";
  input.dispatch("keydown", { key: "Enter" });
  await settle();
  assert.deepEqual(fake.callsTo("enroll").map((c) => c[0].code), ["000000", "123456"]);
  assert.deepEqual(fake.callsTo("enroll")[1],
    [{ kind: "enrol", secretKey: "TESTSECRETBASE32", code: "123456" }, "Authenticator app"]);
  assert.ok(card.removed);
  assert.equal(user.factors.length, 1);

  // Enrolled, but this session's token has not proved the factor yet.
  assert.ok(await stillPending(done));
  assert.equal($("status").textContent, "Confirming your second factor with Google…");
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 1);
});

test("cancelling enrolment rejects ERR_CANCELLED and enrols nothing", async () => {
  signIn(new FakeUser());
  const done = auth.ensureDashboardMfa();
  await settle();
  const card = enrolCard();
  card.querySelector(".cancel").click();
  assert.equal((await rejection(done)).message, auth.ERR_CANCELLED);
  assert.ok(card.removed);
  assert.equal(fake.callsTo("enroll").length, 0);
});

test("enrolment steps up first only when the sign-in is older than four minutes", async () => {
  signIn(new FakeUser({ authAgeSeconds: 200 }));
  await auth.beginTotpEnrolment("a@example.com");
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 0);
  assert.equal(fake.callsTo("generateSecret").length, 1);

  reset();
  signIn(new FakeUser({ authAgeSeconds: 300 }));
  assert.ok(await stillPending(auth.beginTotpEnrolment("a@example.com")));
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 1);
  assert.equal(fake.callsTo("generateSecret").length, 0);
});

/* ---------------------------------------------------------- revoke gate */

test("stepUpForRevoke does nothing inside 90 s of the last sign-in", async () => {
  signIn(new FakeUser({ providers: ["password", "google.com"], authAgeSeconds: 60 }));
  await auth.stepUpForRevoke({ action: "revoke", id: "L1" });
  assert.equal(prompts.asked.length, 0);
  assert.equal(fake.calls.filter((c) => c[0].startsWith("reauth")).length, 0);
});

test("a stale Google-only operator goes straight to Google, past the loop guard", async () => {
  signIn(readyUser({ authAgeSeconds: 600 }));
  storage.setItem(REAUTH_STARTED, String(Date.now() - 10_000));
  const resume = { action: "revoke", id: "L1" };
  assert.ok(await stillPending(auth.stepUpForRevoke(resume)));
  assert.equal(prompts.asked.length, 0, "no password prompt a Google-only account could not answer");
  assert.equal($("status").textContent, "Re-authenticating with Google to revoke this licence…");
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 1);
  assert.deepEqual(JSON.parse(storage.getItem(RESUME)), resume);
  assert.equal(storage.getItem(AFTER_REAUTH), "Re-authenticated.");
});

test("stepUpForRevoke names a delete as a delete", async () => {
  signIn(readyUser({ authAgeSeconds: 600 }));
  assert.ok(await stillPending(auth.stepUpForRevoke({ action: "delete", id: "L1" })));
  assert.equal($("status").textContent, "Re-authenticating with Google to delete this licence…");
});

test("a stale password-only operator must type the password; cancel or blank refuses", async () => {
  signIn(new FakeUser({ providers: ["password"], password: "pw", factors: [{}], authAgeSeconds: 600 }));
  prompts.answer(null);
  assert.equal((await rejection(auth.stepUpForRevoke({ action: "revoke" }))).message, auth.ERR_CANCELLED);
  assert.match(prompts.asked[0], /password to revoke this licence/);
  assert.match(prompts.asked[0], /Then enter your authenticator code/);

  prompts.answer("");
  assert.equal((await rejection(auth.stepUpForRevoke({ action: "revoke" }))).message, auth.ERR_CANCELLED);
  assert.equal(fake.calls.filter((c) => c[0].startsWith("reauth")).length, 0);

  prompts.answer("pw", "123456");
  await auth.stepUpForRevoke({ action: "revoke" });
  assert.equal(fake.callsTo("reauthenticateWithCredential")[0][1].password, "pw");
  assert.equal(fake.callsTo("resolveSignIn").length, 1);
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 0);
});

test("a password-and-Google operator may leave the password blank to use Google", async () => {
  signIn(readyUser({ providers: ["password", "google.com"], authAgeSeconds: 600 }));
  prompts.answer("");
  assert.ok(await stillPending(auth.stepUpForRevoke({ action: "delete", id: "L2" })));
  assert.match(prompts.asked[0], /password to delete this licence/);
  assert.match(prompts.asked[0], /Leave blank to re-authenticate with Google/);
  assert.equal(fake.callsTo("reauthenticateWithCredential").length, 0);
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 1);
});

/* ------------------------------------------------------ confirmByTyping */

test("confirmByTyping accepts only the exact label", () => {
  prompts.answer(" SEMP-4K2P ", "semp-4k2p", null, "SEMP-4K2");
  assert.equal(auth.confirmByTyping("SEMP-4K2P", "revoke it"), true);
  assert.equal(auth.confirmByTyping("SEMP-4K2P", "revoke it"), false);
  assert.equal(auth.confirmByTyping("SEMP-4K2P", "revoke it"), false);
  assert.equal(auth.confirmByTyping("SEMP-4K2P", "revoke it"), false);
  assert.equal(prompts.asked[0], "This cannot be undone.\n\nType SEMP-4K2P to revoke it:");
});

// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The account page's "Redeem a claim code" card (console/account/claim.js):
// offered only while no licence is attached, the code tidied as it is typed,
// sent once in a POST body to /v1/me/claim, the licence re-read on success,
// and every refusal said as a sentence. The code itself must never reach a
// URL, the page's text, or the step-up's storage.
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import {
  test, reset, settle, openPage, json, net, sent, page, $, loadAuth, deferred, fake, storage,
} from "./harness.mjs";

await loadAuth();
const { ACCOUNT, COMMON } = await import("../public/console/messages.js");
const CLAIM = ACCOUNT.claim;

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

const CODE = "SEMP-AB2C-DE3F-GH4J-KM5N";
const DEMO = { mode: "demo", held: false };
const LICENSED = { mode: "licensed", held: true, kind: "institution", prefix: "SEMP-UNI1" };

/**
 * The page, with /v1/me answering `license` until a claim succeeds and
 * `after` from then on, and the claim route answering `claim`.
 */
async function open({ license = DEMO, after = LICENSED, claim, routes = {} } = {}) {
  let claimed = false;
  await openPage("account", {
    routes: {
      "GET /v1/me": () => json(200, { email: "user@example.com", license: claimed ? after : license }),
      "GET /v1/sessions": () => json(200, { sessions: [], quota: { used: 0, max: 25 } }),
      "GET /v1/institutions/licenses": () => json(200, { licenses: [] }),
      "POST /v1/me/claim": async (request) => {
        const response = await (claim ? claim(request) : json(200, { config: { mode: "licensed" } }));
        if (response.ok) claimed = true;
        return response;
      },
      ...routes,
    },
  });
}

/** Type `text` into the box as a person would: the value changes, then `input` fires. */
function type(text, caret) {
  const input = $("claimCode");
  input.value = text;
  if (caret !== undefined) input.selectionStart = caret;
  input.dispatch("input");
}

async function redeem(text = CODE) {
  type(text);
  $("claimRedeem").click();
  await settle();
}

const status = () => [$("status").textContent, $("status").className];
const facts = () => $("facts").querySelectorAll("dd").map((el) => el.textContent);
const bodies = () => net.requests.filter((r) => r.url.endsWith("/v1/me/claim")).map((r) => JSON.parse(r.body));

/** The code appears in no request URL and nowhere in the page's text. */
function nowhere(code = CODE) {
  for (const r of net.requests) assert.ok(!r.url.includes(code), `the code is in ${r.url}`);
  assert.ok(!page.root.textContent.includes(code), "the code is in the page's text");
  assert.ok(![...storage.map.values()].some((v) => v.includes(code)), "the code is in sessionStorage");
}

/* ------------------------------------------------------------ shown when */

test("an account on the demo is offered the card, with what a claim code does", async () => {
  await open();
  assert.equal($("claimCard").hidden, false);
  assert.equal($("claimHelp").textContent,
    "A claim code adds a licence or an institution seat to this account.");
  assert.equal($("claimRedeem").disabled, true, "nothing typed, nothing to redeem");
  assert.match($("explain").textContent, /if you were given a claim code, redeem it below\.$/);
});

test("the card says nothing of prices, buying or payment, and links nowhere", async () => {
  await open();
  const card = $("claimCard");
  assert.doesNotMatch(card.textContent, /\b(price|buy|purchase|pay|payment|checkout|order|shop)\b/i);
  assert.equal(card.querySelectorAll("a").length, 0);
  for (const sentence of [CLAIM.intro, CLAIM.shape, CLAIM.done, CLAIM.failed, ...Object.values(CLAIM.refusals)]) {
    assert.doesNotMatch(sentence, /\b(price|buy|purchase|pay|payment|checkout|shop)\b/i);
  }
});

test("an account with a licence attached is not offered it, even one that has lapsed", async () => {
  await open({ license: LICENSED });
  assert.equal($("claimCard").hidden, true);
  reset();
  await open({ license: { mode: "demo", held: true, expiresAt: "2026-01-01T23:59:59Z", graceEndsAt: "2026-01-15T23:59:59Z" } });
  assert.equal($("claimCard").hidden, true);
  reset();
  await open({ license: { mode: "licensed" } }); // a backend without `held`
  assert.equal($("claimCard").hidden, true);
});

test("the card waits for /v1/me, and stays away when it cannot be read", async () => {
  const me = deferred();
  await open({ routes: { "GET /v1/me": () => me.promise } });
  assert.equal($("claimCard").hidden, true, "not offered before the account is known");
  me.resolve(json(503, { detail: "unavailable" }));
  await settle();
  assert.equal($("claimCard").hidden, true);
});

/* ---------------------------------------------------------------- typing */

test("what is typed is tidied: upper case, no spaces, only letters, digits and dashes", async () => {
  await open();
  type("  semp-ab2c de3f\n'gh4j'-km5n ");
  assert.equal($("claimCode").value, "SEMP-AB2CDE3FGH4J-KM5N");
  assert.equal($("claimRedeem").disabled, false);
  type("smp-ab");
  assert.equal($("claimCode").value, "SMP-AB", "already tidy apart from case");
  type("");
  assert.equal($("claimRedeem").disabled, true);
});

test("the caret stays among the characters it was between", async () => {
  await open();
  const input = $("claimCode");
  let range;
  input.setSelectionRange = (start, end) => { range = [start, end]; };
  // "ab cd|ef": the caret after "d"; the space goes, so it moves back one.
  type("ab cdef", 5);
  assert.equal(input.value, "ABCDEF");
  assert.deepEqual(range, [4, 4]);
  range = undefined;
  type("ABCDEF", 3);
  assert.equal(range, undefined, "a tidy value is left alone, caret and all");
});

test("a code that is not shaped like one is caught before it is sent", async () => {
  await open();
  for (const text of ["SEMP-AB", "SEMP--ABCD-EFGH", "-SEMPABCDEFGH", "SEMPABCDEFGH-", "A".repeat(65)]) {
    await redeem(text);
    assert.deepEqual(status(), [CLAIM.shape, "muted err"], text);
  }
  assert.deepEqual(sent(/claim/), []);
  assert.equal($("claimCode").focused, true);
});

/* --------------------------------------------------------------- redeem */

test("Redeem posts the tidied code in the body, then re-reads the account", async () => {
  await open();
  await redeem(` ${CODE.toLowerCase()} `);
  assert.deepEqual(sent(/\/v1\/me/), ["GET /v1/me", "POST /v1/me/claim", "GET /v1/me"]);
  assert.deepEqual(bodies(), [{ code: CODE }]);
  const [request] = net.requests.filter((r) => r.url.endsWith("/v1/me/claim"));
  assert.equal(request.headers["Content-Type"], "application/json");
  assert.match(request.headers.Authorization, /^Bearer /);
  nowhere();
});

test("after a claim the licence it attached is shown, the box is emptied, and the app is pointed at", async () => {
  await open();
  await redeem();
  assert.deepEqual(status(), [
    "Claim code redeemed. Open the app and tap Refresh licence, or wait for its next check.", "muted",
  ]);
  assert.equal(status()[0], CLAIM.done);
  assert.equal($("claimCode").value, "");
  assert.equal($("claimRedeem").disabled, true);
  assert.deepEqual(facts().slice(0, 2), ["Licensed", "Institution · SEMP-UNI1"]);
  assert.equal($("pills").querySelector(".pill").textContent, "Licensed");
  assert.equal($("claimCard").hidden, true, "a licence attached: nothing more to claim");
  nowhere();
});

test("Enter in the box redeems as the button does", async () => {
  await open();
  type(CODE);
  $("claimCode").dispatch("keydown", { key: "a" });
  await settle();
  assert.deepEqual(sent(/claim/), []);
  $("claimCode").dispatch("keydown", { key: "Enter" });
  await settle();
  assert.deepEqual(sent(/claim/), ["POST /v1/me/claim"]);
});

test("one claim at a time: the box and the button are held while it is sent", async () => {
  const answer = deferred();
  await open({ claim: () => answer.promise });
  type(CODE);
  $("claimRedeem").click();
  await settle();
  assert.equal($("claimCode").disabled, true);
  assert.equal($("claimRedeem").disabled, true);
  assert.equal(status()[0], "Redeeming your claim code…");
  $("claimCode").dispatch("keydown", { key: "Enter" });
  await settle();
  assert.deepEqual(sent(/claim/), ["POST /v1/me/claim"], "sent once");
  answer.resolve(json(200, { config: {} }));
  await settle();
  assert.equal($("claimCode").disabled, false);
  assert.equal(status()[0], CLAIM.done);
});

test("a claim that worked but whose account re-read failed says both", async () => {
  let reads = 0;
  await open({ routes: { "GET /v1/me": () => (reads++ === 0
    ? json(200, { email: "user@example.com", license: DEMO })
    : json(503, { detail: "unavailable" })) } });
  await redeem();
  assert.deepEqual(status(), [`${CLAIM.done} Could not read your account: unavailable`, "muted err"]);
  assert.equal($("claimCode").value, "");
  assert.equal($("claimCard").hidden, true, "whether a licence is attached is unknown");
});

/* -------------------------------------------------------------- refusals */

const REFUSALS = [
  [404, "license_not_found", CLAIM.refusals.notFound],
  [403, "license_revoked", CLAIM.refusals.revoked],
  [403, "license_expired", CLAIM.refusals.expired],
  [403, "license_email_mismatch", CLAIM.refusals.emailMismatch],
  [403, "license_device_mismatch", CLAIM.refusals.deviceMismatch],
  [409, "license_already_redeemed", CLAIM.refusals.alreadyRedeemed],
  [409, "already_licensed", CLAIM.refusals.alreadyLicensed],
  [409, "license_seats_exhausted", CLAIM.refusals.seatsExhausted],
  [409, "license_seat_disabled", CLAIM.refusals.seatDisabled],
  [409, "device_in_use", CLAIM.refusals.deviceInUse],
  [409, "claim_contended", CLAIM.refusals.busy],
  [503, "claim_contended", CLAIM.refusals.busy],
  [503, "device_lock_contended", CLAIM.refusals.busy],
  [429, "rate_limited", CLAIM.refusals.rateLimited],
  [403, "not_approved", CLAIM.refusals.notApproved],
  [403, "email_not_verified", CLAIM.refusals.emailNotVerified],
  [403, "mfa_required", COMMON.mfa_required],
  [503, "auth_check_busy", COMMON.auth_check_busy],
];

test("every refusal is said as its own sentence, and the code is kept to correct", async () => {
  const said = new Set();
  for (const [code, detail, sentence] of REFUSALS) {
    reset();
    await open({ claim: () => json(code, { detail }) });
    await redeem();
    assert.deepEqual(status(), [sentence, "muted err"], detail);
    assert.equal(typeof sentence, "string", detail);
    assert.equal($("claimCode").value, CODE, "kept, to fix and send again");
    assert.equal($("claimCode").disabled, false);
    assert.equal($("claimRedeem").disabled, false);
    assert.equal($("claimCard").hidden, false);
    assert.deepEqual(sent(/\/v1\/me/), ["GET /v1/me", "POST /v1/me/claim"], "nothing re-read");
    nowhere();
    said.add(sentence);
  }
  assert.equal(said.size, REFUSALS.length - 2, "one sentence per code; the three busy answers share one");
});

test("a refusal the page has no sentence for, or no answer at all, gets the general one", async () => {
  for (const claim of [
    () => json(500, { detail: "internal_error" }),
    () => json(404, undefined), // the gateway, before the route is declared on it
    () => json(422, { detail: [{ msg: "String should have at most 64 characters" }] }),
    () => Promise.reject(new TypeError("Failed to fetch")),
  ]) {
    reset();
    await open({ claim });
    await redeem();
    assert.deepEqual(status(), [CLAIM.failed, "muted err"]);
    nowhere();
  }
});

test("an old sign-in steps up through Google, and the code is left in no storage", async () => {
  await open({ claim: () => json(403, { detail: "reauth_required" }) });
  await redeem();
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 1);
  assert.match(storage.getItem("semper.afterReauth"), /Repeat what you were doing/);
  assert.deepEqual(sent(/claim/), ["POST /v1/me/claim"], "not repeated with the stale token");
  nowhere();
});

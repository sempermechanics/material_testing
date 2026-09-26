// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The /login front door (console/router.js): where each signed-in account is
// sent. router.js starts itself on import, so each case imports a fresh copy
// (`?case=N`); they all share one auth.js and the fake SDK (harness.mjs).
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { test, fake, readyUser, reset, settle, net, json, location, $, loadAuth } from "./harness.mjs";

// Loaded first so the fake Auth instance exists before a case signs anyone in.
await loadAuth();

beforeEach(reset);
afterEach(settle);

let cases = 0;

/** Sign `user` in, answer the two lookups, and run a fresh router. */
async function route({ user = readyUser({ email: "someone@example.edu" }), me = {}, licenses = { licenses: [] } } = {}) {
  fake.auth.currentUser = user;
  net.routes = {
    "/v1/me": () => (me instanceof Response ? me : json(200, { email: user && user.email, role: "user", ...me })),
    "/v1/institutions/licenses": () => (licenses instanceof Response ? licenses : json(200, licenses)),
  };
  cases += 1;
  await import(`../public/console/router.js?case=${cases}`);
  await settle();
}

/** The dashboards the switcher offers, in order. */
function offered() {
  assert.equal($("choices").hidden, false, "switcher shown");
  return [...$("links").innerHTML.matchAll(/<a href="([^"]+)"/g)].map((m) => m[1]);
}

test("a signed-out visitor is not routed and nothing is asked of the API", async () => {
  await route({ user: null });
  assert.equal(net.requests.length, 0);
  assert.deepEqual(location.replaced, []);
  assert.equal($("signedOut").hidden, false);
});

test("staff go to the operator console, by replace", async () => {
  await route({ me: { role: "admin" } });
  assert.deepEqual(location.replaced, ["operator/"]);
  assert.deepEqual(net.requests.map((r) => r.headers.Authorization),
    ["Bearer token-uid-1-v1", "Bearer token-uid-1-v1"]);
  assert.equal($("signedOut").hidden, true);
});

test("an IT contact for one licence is deep-linked to it", async () => {
  await route({ licenses: { licenses: [{ id: "LIC/1 a" }] } });
  assert.deepEqual(location.replaced, ["institution/?license=LIC%2F1%20a"]);
});

test("an IT contact for several licences lands on the roster page", async () => {
  await route({ licenses: { licenses: [{ id: "A" }, { id: "B" }] } });
  assert.deepEqual(location.replaced, ["institution/"]);
});

test("everyone else goes to their own account", async () => {
  await route();
  assert.deepEqual(location.replaced, ["account/"]);
});

test("an unverified address administers nothing, so it goes to its account", async () => {
  await route({ licenses: json(403, { detail: "email_not_verified" }) });
  assert.deepEqual(location.replaced, ["account/"]);
});

test("staff who also run an institution get a switcher, not a guess", async () => {
  await route({ me: { role: "admin", email: "both@example.com" }, licenses: { licenses: [{ id: "A" }] } });
  assert.deepEqual(location.replaced, []);
  assert.deepEqual(offered(), ["operator/", "institution/", "account/"]);
  assert.equal($("choicesTitle").textContent, "Signed in as both@example.com");
});

test("?stay=1 shows the menu instead of forwarding", async () => {
  location.search = "?stay=1";
  await route({ me: { role: "admin" } });
  assert.deepEqual(location.replaced, []);
  assert.deepEqual(offered(), ["operator/", "account/"]);
});

test("a failed licence lookup is reported as uncertainty, not routed on", async () => {
  await route({ me: { role: "admin" }, licenses: json(500) });
  assert.deepEqual(location.replaced, []);
  assert.deepEqual(offered(), ["operator/", "institution/", "account/"]);
  assert.equal($("status").textContent,
    "Could not check whether you administer an institution licence (http_500).");
  assert.equal($("status").className, "muted err");
});

test("no answer from /v1/me offers all three dashboards", async () => {
  await route({ me: json(503, { detail: "unavailable" }) });
  assert.deepEqual(location.replaced, []);
  assert.deepEqual(offered(), ["operator/", "institution/", "account/"]);
  assert.equal($("status").textContent, "Could not tell where to send you: unavailable");
});

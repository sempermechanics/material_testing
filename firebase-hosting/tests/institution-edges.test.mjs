// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The institution roster page (console/institution/institution.js) on the
// answers institution.test.mjs does not send: a licence list with no list in
// it, a roster summary without its counts, and the controls pressed with no
// licence open or off any button.
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { test, reset, settle, openPage, json, net, sent, readyUser, $, loadAuth, deferred } from "./harness.mjs";

await loadAuth();

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

const SEATS = "/v1/institutions/licenses/L1/seats";
const summary = () => $("summary").textContent.replace(/\s+/g, " ").trim();
const status = () => [$("status").textContent, $("status").className];

async function open({ roster, search = "", licenses = [{ id: "L1", keyPrefix: "SEMP-UNI1" }] } = {}) {
  await openPage("institution", {
    search,
    routes: {
      "GET /v1/institutions/licenses": () => json(200, { licenses }),
      "GET /v1/me": () => json(200, { role: "user" }),
      "GET /v1/institutions/licenses/L1/summary": () => json(200, {}),
      ...(roster ? { [`GET ${SEATS}`]: () => json(200, roster) } : {}),
    },
  });
}

test("a licence list answer without a list reads as administering nothing", async () => {
  await openPage("institution", {
    user: readyUser({ email: "it@uni.edu" }),
    routes: { "GET /v1/institutions/licenses": () => json(200, {}) },
  });
  assert.equal($("app").hidden, true);
  assert.equal($("notAdmin").hidden, false);
  assert.equal($("notAdminWho").textContent, "it@uni.edu");
  assert.deepEqual(status(), ["", "muted"]);
});

test("Add with no licence open sends nothing", async () => {
  await open();
  $("addEmail").value = "new@uni.edu";
  $("add").click();
  await settle();
  assert.deepEqual(sent(/seats/), []);
  assert.equal($("addEmail").value, "new@uni.edu", "kept for when a licence is open");
});

test("a click between the licence buttons opens nothing", async () => {
  await open();
  $("licenceChoices").click();
  await settle();
  assert.equal($("licenseId").value, "");
  assert.deepEqual(sent(/seats/), []);
});

test("a roster summary without its counts reads them as none", async () => {
  await open({ search: "?license=L1", roster: { license: { seating: "floating", maxSeats: 3 }, seats: [], invites: [] } });
  assert.equal(summary(), "licence shared seats unused 0 of 3 seats in use right now, across 0 people on the roster.");

  reset();
  await open({ search: "?license=L1", roster: { license: { maxSeats: 3 }, seats: [] } });
  assert.equal(summary(), "licence one seat each unused 0 of 3 seats taken.", "assigned when the seating is not said");
  assert.equal($("rosterCard").hidden, false);
});

test("a roster with nothing but seats in its answer still renders", async () => {
  await open({ search: "?license=L1", roster: { seats: [{ uid: "u1", email: "a@uni.edu", status: "active" }] } });
  assert.equal(summary(), "licence one seat each unused 0 of unlimited seats taken.");
  assert.equal($("rows").querySelectorAll("tr").length, 1);
});

// AE2-105: the licence list answered 401 invalid_token (the sign-in is over).
// The page signed out, but wrote the reason, then went on and drew the
// roster with its buttons under Sign in, and the status line ended empty.
test("an expired sign-in on the licence list says so and draws nothing behind Sign in", async () => {
  await openPage("institution", {
    search: "?license=L1",
    routes: {
      "GET /v1/institutions/licenses": () => json(401, { detail: "invalid_token" }),
      [`GET ${SEATS}`]: () => json(401, { detail: "invalid_token" }),
    },
  });
  await settle();
  assert.deepEqual(status(), ["Your sign-in has expired. Sign in again.", "muted err"]);
  assert.equal($("app").hidden, true);
  assert.equal($("signIn").hidden, false);
  assert.equal($("rows").querySelectorAll("button").length, 0, "no roster, no Remove or Pause");
  assert.deepEqual(sent(/summary/), [], "no seat counts read for a signed-out page");
});

// AE2-105, rework: the roster read answered 401 first and signed the page
// out; the licence list answered 200 after, and the page went on to show
// the dashboard (Add member, Export, sort, Retry) behind Sign in.
test("a licence list answered after the session ended does not bring the dashboard back", async () => {
  const list = deferred();
  await openPage("institution", {
    search: "?license=L1",
    routes: {
      [`GET ${SEATS}`]: () => json(401, { detail: "invalid_token" }),
      "GET /v1/institutions/licenses": () => list.promise,
    },
  });
  await settle();
  assert.deepEqual(status(), ["Your sign-in has expired. Sign in again.", "muted err"]);
  list.resolve(json(200, { licenses: [{ id: "L1", keyPrefix: "SEMP-UNI1" }] }));
  await settle();
  assert.equal($("app").hidden, true, "the dashboard stays hidden");
  assert.equal($("signIn").hidden, false);
  assert.deepEqual(status(), ["Your sign-in has expired. Sign in again.", "muted err"]);
  assert.deepEqual(sent(/summary/), [], "nothing more is read for a signed-out page");
});

/** A request the test fails when it chooses, as `fetch` does with no network. */
function failing() {
  let fail;
  const promise = new Promise((_, reject) => { fail = reject; });
  return { promise, fail: () => fail(new TypeError("Failed to fetch")) };
}

const ENDED = ["Your sign-in has expired. Sign in again.", "muted err"];

// AE2-105, rework 2: the roster read answered 401 and signed the page out;
// the licence list then failed (503, or no network). Its catch wrote "Could
// not list…" over the session line and returned true, so the dashboard came
// back behind Sign in and the roster load went on.
for (const [how, answer] of [
  ["a 503", (list) => list.resolve(json(503, { detail: "service_unavailable" }))],
  ["a network error", (list) => list.fail()],
]) {
  test(`a licence list failing with ${how} after the session ended keeps the session-ended state`, async () => {
    const list = how === "a 503" ? deferred() : failing();
    await openPage("institution", {
      search: "?license=L1",
      routes: {
        [`GET ${SEATS}`]: () => json(401, { detail: "invalid_token" }),
        "GET /v1/institutions/licenses": () => list.promise,
      },
    });
    await settle();
    assert.deepEqual(status(), ENDED);
    answer(list);
    await settle();
    assert.equal($("app").hidden, true, "the dashboard stays hidden");
    assert.equal($("signIn").hidden, false);
    assert.deepEqual(status(), ENDED);
    assert.equal($("rows").querySelectorAll("button").length, 0, "no roster, no Retry");
    assert.deepEqual(sent(/summary/), [], "nothing more is read for a signed-out page");
  });
}

// The same gap in the roster load: an export sent while the roster read was
// out answered 401 and ended the session. The roster's answer, whatever it
// was, cleared or wrote over the session line, and a 200 went on to read the
// seat counts with no one signed in ("Could not count seats … not_signed_in").
for (const [how, answer] of [
  ["a 200", (roster) => roster.resolve(json(200, { license: { maxSeats: 3 }, seats: [], invites: [] }))],
  ["a 503", (roster) => roster.resolve(json(503, { detail: "service_unavailable" }))],
  ["a network error", (roster) => roster.fail()],
]) {
  test(`a roster read answering ${how} after the session ended keeps the session-ended state`, async () => {
    const roster = how === "a network error" ? failing() : deferred();
    await openPage("institution", {
      routes: {
        "GET /v1/institutions/licenses": () => json(200, { licenses: [{ id: "L1", keyPrefix: "SEMP-UNI1" }] }),
        [`GET ${SEATS}`]: () => roster.promise,
        [`GET ${SEATS}/export`]: () => json(401, { detail: "invalid_token" }),
        "GET /v1/me": () => json(200, { role: "user" }), // the dashboard switch's
      },
    });
    $("licenseId").value = "L1";
    $("load").click();
    $("exportRoster").click();
    await settle();
    const ended = status(); // the export's own line: sign in again, then repeat that
    assert.match(ended[0], /^Your sign-in has expired\. Sign in again/);
    answer(roster);
    await settle();
    assert.equal($("app").hidden, true);
    assert.equal($("signIn").hidden, false);
    assert.deepEqual(status(), ended);
    assert.deepEqual(sent(/summary/), [], "no seat counts read for a signed-out page");
  });
}

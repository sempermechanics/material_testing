// Run: node --test "firebase-hosting/tests/*.test.mjs"
// People and devices on the operator desk (console/operator/people.js), past
// what operator.test.mjs pins: a refused New device, every part of a device
// history line and an empty or unreadable history, the accounts waiting for
// approval (rows, Approve, Reload, failures), and releasing a phone from an
// unlicensed account.
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { test, reset, settle, json, net, confirms, alerts, sent, $, loadAuth, deferred } from "./harness.mjs";
import { IND, openDesk, status, tableRows, rowButton, offline, bodyOf, PENDING } from "./operator-desk.mjs";

await loadAuth();
const { when } = await import("../public/console/util.js");

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

async function click(button) {
  button.click();
  await settle();
}

/* ------------------------------------------------------------ licence */

test("a refused New device says so", async () => {
  await openDesk({ routes: { [`PATCH /v1/admin/licenses/${IND.id}`]: () => json(409, { detail: "license_revoked" }) } });
  confirms.answer(true);
  await click(rowButton("device", IND.id));
  assert.match(confirms.asked[0], /^Unbind SEMP-IND1 from the device it is on\?\n\nThe next device they sign in on takes it\./);
  assert.deepEqual(status(), ["Could not unbind: the server refused it (ref: license_revoked)", "muted err"]);
});

test("after a licence's row is redrawn, focus is back on that row", async () => {
  await openDesk({ routes: { [`PATCH /v1/admin/licenses/${IND.id}`]: () => json(200, { ...IND, note: "moved" }) } });
  const device = rowButton("device", IND.id);
  device.focus();
  device.closest("details").open = true;
  confirms.answer(true);
  await click(device);
  // The menu closed when its item was chosen, and the row is a new element.
  assert.equal(document.activeElement.localName, "summary");
  assert.equal(document.activeElement.closest("tr").cells[0].textContent, "SEMP-IND1");
  const edit = rowButton("edit", IND.id);
  edit.focus();
  $("filter").dispatch("input");
  assert.equal(document.activeElement.dataset.edit, IND.id, "a redraw keeps the focused button");
  assert.notEqual(document.activeElement, edit);
});

const HISTORY = `GET /v1/admin/licenses/${IND.id}/device-history?limit=30`;

test("a device history line names what was left, signed out and bound, per app", async () => {
  await openDesk({ routes: { [HISTORY]: () => json(200, { events: [
    // `apps.spread` records Semper Classroom's half under both names.
    { ts: "2026-10-07T11:21Z", action: "clear", uid: "staff", detail: {
      previousDeviceId: "dev-1", releasedDeviceId: "dev-9", previousDeviceIdSemperClassroom: "sc-1",
      previousDeviceIdMaterialTesting: "sc-1",
    } },
    // A row from before ADR-030 holds only the old name, and keeps it.
    { ts: "2026-10-06T09:00Z", action: "clear", uid: "staff", detail: { previousDeviceIdMaterialTesting: "mt-1" } },
    // The new name alone is not read (`apps.read_field`).
    { ts: "2026-10-05T09:00Z", action: "clear", uid: "staff", detail: { previousDeviceIdSemperClassroom: "new-1" } },
    // The registered phone is the lock's own: said once, as "left".
    { action: "clear", detail: { previousDeviceId: "dev-2", releasedDeviceId: "dev-2" } },
    { ts: "2026-10-07T11:25Z", action: "bind", uid: "u1" },
  ] }) } });
  await click(rowButton("history", IND.id));
  // Each move's time as every other time on the desk is shown (when): local,
  // its zone named, not the raw ISO string.
  assert.deepEqual(alerts, [
    "Device history for SEMP-IND1\n\n" +
    `${when("2026-10-07T11:21Z")}  clear  by staff  left dev-1  signed out dev-9  left sc-1 (Semper Classroom)\n` +
    `${when("2026-10-06T09:00Z")}  clear  by staff  left mt-1 (Semper Classroom)\n` +
    `${when("2026-10-05T09:00Z")}  clear  by staff\n` +
    "—  clear  by —  left dev-2\n" +
    `${when("2026-10-07T11:25Z")}  bind  by u1`,
  ]);
});

test("no moves recorded says so; a history that cannot be read is reported", async () => {
  await openDesk({ routes: { [HISTORY]: () => json(200, {}) } });
  await click(rowButton("history", IND.id));
  assert.deepEqual(alerts, ["No device moves recorded for SEMP-IND1 yet."]);

  reset();
  await openDesk({ routes: { [HISTORY]: () => json(503, { detail: "unavailable" }) } });
  await click(rowButton("history", IND.id));
  assert.deepEqual(alerts, []);
  assert.deepEqual(status(), ["Could not load device history: unavailable", "muted err"]);
});

/* ---------------------------------------------------------- accounts */

const userRows = () => tableRows("userRows");
const WAITING = [
  // Bound by this backend: Semper Classroom's phone under both names.
  { uid: "u1", email: "a@lab.org", displayName: "Ann", activeDeviceId: "device-abcdef-123",
    activeDeviceIdSemperClassroom: "sc-device-xyz-9", activeDeviceIdMaterialTesting: "sc-device-xyz-9" },
  { uid: "u2" },
];

test("accounts waiting for approval are listed with their phones, or 'Nobody waiting.'", async () => {
  await openDesk({ users: WAITING });
  assert.deepEqual(userRows(), [
    ["a@lab.org", "Ann", "device-abc…; Semper Classroom sc-device-…", "Approve"],
    ["u2", "—", "—", "Approve"],
  ]);

  reset();
  await openDesk({ users: [] });
  assert.deepEqual(userRows(), [["Nobody waiting."]]);
});

test("an account not yet migrated shows its Semper Classroom phone from the old field", async () => {
  // ADR-030: until TD-330, an account may hold only activeDeviceIdMaterialTesting.
  await openDesk({ users: [{ uid: "u3", email: "c@lab.org", activeDeviceIdMaterialTesting: "mt-device-xyz-9" }] });
  assert.deepEqual(userRows(), [["c@lab.org", "—", "Semper Classroom mt-device-…", "Approve"]]);

  // The new field alone is stale (H1): a backend from before the rename
  // cleared the old one and left it.
  reset();
  await openDesk({ users: [{ uid: "u4", email: "d@lab.org", activeDeviceIdSemperClassroom: "stale-xyz-99" }] });
  assert.deepEqual(userRows(), [["d@lab.org", "—", "—", "Approve"]]);
});

test("the desk's index counts who is waiting, and shows no count when nobody is", async () => {
  const badge = () => [$("pendingCount").textContent, $("pendingCount").hidden];
  await openDesk({ users: WAITING });
  assert.deepEqual(badge(), ["2", false]);

  reset();
  await openDesk({ routes: { [PENDING]: () => json(200, { users: WAITING, page: { hasMore: true } }) } });
  assert.deepEqual(badge(), ["2+", false], "a read that stopped at its page says there are more");

  reset();
  await openDesk({ users: [] });
  assert.deepEqual(badge(), ["", true]);
});

test("a pending list that cannot be read says so in the table; Reload asks again", async () => {
  let n = 0;
  await openDesk({ routes: {
    [PENDING]: () => (n++ ? json(200, { users: WAITING.slice(1) }) : json(503, { detail: "unavailable" })),
  } });
  assert.deepEqual(userRows(), [["Could not load: unavailable Retry"]]);
  assert.equal($("status").textContent, "", "the licence table still loaded");
  await click($("reloadUsers"));
  assert.deepEqual(sent(/users/), [PENDING, PENDING]);
  assert.deepEqual(userRows(), [["u2", "—", "—", "Approve"]]);
});

test("Approve posts for that account, says so and re-reads who is waiting", async () => {
  let n = 0;
  const answer = deferred();
  await openDesk({ routes: {
    [PENDING]: () => json(200, { users: n++ ? WAITING.slice(1) : WAITING }),
    "POST /v1/admin/users/u1/approve": () => answer.promise,
  } });
  const approve = $("userRows").querySelector('button[data-approve="u1"]');
  await click(approve);
  assert.equal(approve.disabled, true, "one approval per click");
  answer.resolve(json(200, {}));
  await settle();
  assert.deepEqual(sent(/approve|users/), [PENDING, "POST /v1/admin/users/u1/approve", PENDING]);
  assert.deepEqual(status(), ["Approved.", "muted"]);
  assert.deepEqual(userRows().map((r) => r[0]), ["u2"]);
});

test("a refused approval says why and leaves the button to press again", async () => {
  await openDesk({ users: WAITING, routes: {
    "POST /v1/admin/users/u2/approve": () => json(404, { detail: "user_not_found" }),
  } });
  const approve = $("userRows").querySelector('button[data-approve="u2"]');
  await click(approve);
  assert.deepEqual(status(), ["Could not approve: No Semper account has that address.", "muted err"]);
  assert.equal(approve.disabled, false);
  assert.deepEqual(sent(/users/), [PENDING, "POST /v1/admin/users/u2/approve"], "not re-read after a refusal");
});

test("a click in the pending table off any Approve button sends nothing", async () => {
  await openDesk({ users: WAITING });
  await click($("userRows").querySelector("td"));
  assert.deepEqual(sent(/approve/), []);
});

/* ----------------------------------------------------- release a phone */

const RELEASE = "POST /v1/admin/device-releases";

async function release(email, confirm = true) {
  $("releaseEmail").value = email;
  if (email.trim()) confirms.answer(confirm);
  await click($("releasePhone"));
}

test("Release phone asks first, naming the account; declining sends nothing", async () => {
  await openDesk();
  await release("pat@lab.org", false);
  assert.match(confirms.asked[0], /^Release the phone of pat@lab\.org\?/);
  assert.deepEqual(sent(/device-releases/), []);
  assert.equal($("releaseEmail").value, "pat@lab.org");
  assert.equal($("releasePhone").disabled, false);
});

test("releasing a phone needs an address; nothing is sent without one", async () => {
  await openDesk();
  await release("  ");
  assert.deepEqual(status(), ["Enter the account's email.", "muted err"]);
  assert.deepEqual(sent(/device-releases/), []);
});

test("a release names each phone it freed, per app, and clears the box", async () => {
  const answer = deferred();
  await openDesk({ routes: { [RELEASE]: () => answer.promise } });
  await release(" pat@lab.org ");
  assert.equal($("releasePhone").disabled, true);
  assert.deepEqual(bodyOf(net, "POST", "/v1/admin/device-releases"), { email: "pat@lab.org" });
  answer.resolve(json(200, { email: "pat@lab.org", releasedDeviceId: "dev-1", releasedDeviceIdSemperClassroom: "sc-1",
    releasedDeviceIdMaterialTesting: "sc-1" }));
  await settle();
  assert.deepEqual(status(), ["Released dev-1 and sc-1 (Semper Classroom) for pat@lab.org; the new phone can sign in.", "muted"]);
  assert.equal($("releaseEmail").value, "");
  assert.equal($("releasePhone").disabled, false);
});

test("a release with only a Semper Classroom phone, or none, says which", async () => {
  // `apps.spread` answers Semper Classroom's phone under both names.
  await openDesk({ routes: { [RELEASE]: () => json(200, { email: "pat@lab.org", releasedDeviceId: "",
    releasedDeviceIdSemperClassroom: "sc-1", releasedDeviceIdMaterialTesting: "sc-1" }) } });
  await release("pat@lab.org");
  assert.deepEqual(status(), ["Released sc-1 (Semper Classroom) for pat@lab.org; the new phone can sign in.", "muted"]);

  // A backend from before ADR-030 answers only the old name.
  reset();
  await openDesk({ routes: { [RELEASE]: () => json(200, { email: "pat@lab.org", releasedDeviceIdMaterialTesting: "mt-1" }) } });
  await release("pat@lab.org");
  assert.deepEqual(status(), ["Released mt-1 (Semper Classroom) for pat@lab.org; the new phone can sign in.", "muted"]);

  reset();
  await openDesk({ routes: { [RELEASE]: () => json(200, {}) } });
  await release("Pat@Lab.org");
  assert.deepEqual(status(), ["Pat@Lab.org had no phone registered; any phone can sign in.", "muted"],
    "the address typed, when the answer names none");
});

test("a refused release is explained, and the address stays for another try", async () => {
  for (const [reply, text] of [
    [() => json(409, { detail: "license_device_clear_required" }),
      "Could not release: That account is licensed: use New device on its licence."],
    [() => json(429, { detail: "rate_limited" }), "Could not release: Too many requests just now — wait a moment and try again."],
    [() => json(404, { detail: "user_not_found" }), "Could not release: No Semper account has that address."],
    [offline, "Could not release: The server could not be reached — check your connection and try again."],
  ]) {
    reset();
    await openDesk({ routes: { [RELEASE]: reply } });
    await release("pat@lab.org");
    assert.deepEqual(status(), [text, "muted err"], text);
    assert.equal($("releaseEmail").value, "pat@lab.org");
    assert.equal($("releasePhone").disabled, false);
  }
});

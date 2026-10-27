// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The operator desk's Test accounts card (console/operator/test-accounts.js):
// the create form's body (QC days, CI without an end, phones at once), the
// password shown once in its dialog and gone from the page and storage when
// it closes, each refusal, the list read on demand, Extend, New password and
// End now (typed address), the fresh step-up that leaves for Google carrying
// the change, and the return leg that finishes it or says it was not sent.
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import {
  test, reset, settle, json, net, confirms, prompts, sent, fake, storage, readyUser, $, loadAuth, deferred,
} from "./harness.mjs";
import { openDesk, status, tableRows, offline, bodyOf } from "./operator-desk.mjs";

await loadAuth();
const { day } = await import("../public/console/util.js");

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

const LIST = "GET /v1/admin/test-accounts";
const CREATE = "POST /v1/admin/test-accounts";
// 20 URL-safe characters, as the backend makes them; plainly a placeholder.
const ISSUED = "test-only-0000000000";
const ENDS = "2026-11-18T23:59:59Z";

const QC = {
  uid: "uid-qc-1", email: "qc-1@test.example", purpose: "qc", expiresAt: ENDS, maxDevices: 3,
  note: "QC rack", createdAt: "2026-10-19T10:00:00Z", activeDevices: 1, status: "active",
};
const CI = {
  uid: "uid-ci-1", email: "ci@test.example", purpose: "ci", expiresAt: null, maxDevices: 10,
  note: "", createdAt: "2026-10-01T10:00:00Z", activeDevices: 0, status: "active",
};

const created = (over = {}) => json(201, {
  uid: "uid-new", email: "new@test.example", password: ISSUED, purpose: "qc",
  expiresAt: ENDS, maxDevices: 3, licenseId: "lic-t1", ...over,
});
const listed = (accounts = [QC, CI]) => () => json(200, { accounts });

/** Fill the create form, as typed. */
function fill({ email = "new@test.example", purpose, days, maxDevices, note } = {}) {
  $("taEmail").value = email;
  if (purpose) {
    $("taPurpose").value = purpose;
    $("taPurpose").dispatch("change");
  }
  if (days !== undefined) $("taDays").value = days;
  if (maxDevices !== undefined) $("taMaxDevices").value = maxDevices;
  if (note !== undefined) $("taNote").value = note;
}

async function create() {
  $("taCreate").click();
  await settle();
}

const createBody = () => bodyOf(net, "POST", "/v1/admin/test-accounts");
const accountRows = () => tableRows("testAccountRows");
const rowButton = (kind, uid) => $("testAccountRows").querySelector(`button[data-ta-${kind}="${uid}"]`);
const dialog = () => $("taPasswordDialog");

/** Nothing the page keeps holds the password: storage, the status line, the dialog's text. */
function passwordForgotten() {
  for (const value of storage.map.values()) assert.ok(!value.includes(ISSUED), "not in sessionStorage");
  assert.ok(!$("status").textContent.includes(ISSUED), "not in the status line");
  assert.equal($("taPassword").textContent, "", "not in the page");
}

/** Open the list (Show), answering `accounts`. */
async function showList(accounts = [QC, CI], routes = {}) {
  await openDesk({ routes: { [LIST]: listed(accounts), ...routes } });
  $("loadTestAccounts").click();
  await settle();
}

/* ---------------------------------------------------------------- create */

test("a QC account is created with the form's values and its password is shown once", async () => {
  const answer = deferred();
  await openDesk({ routes: { [CREATE]: () => answer.promise, [LIST]: listed([QC]) } });
  assert.equal($("testAccountsWrap").hidden, true, "the list is read on demand");
  fill({ email: " new@test.example ", note: " Pixel 6 " });
  $("taCreate").click();
  await settle();
  assert.equal($("taCreate").disabled, true, "one click, one account");
  assert.deepEqual(status(), ["Creating the test account…", "muted"]);
  assert.deepEqual(createBody(),
    { email: "new@test.example", purpose: "qc", days: 30, maxDevices: 3, note: "Pixel 6" });

  answer.resolve(created());
  await settle();
  assert.equal($("taCreate").disabled, false);
  assert.equal(dialog().open, true);
  assert.equal($("taPassword").textContent, ISSUED);
  assert.equal($("taPasswordEmail").textContent, "new@test.example");
  assert.deepEqual(status(), [
    `Test account new@test.example created (QC): approved and licensed, ends ${day(ENDS)}, ` +
    "on up to 3 phones at once. Copy its password now.", "muted"]);
  assert.ok(!$("status").textContent.includes(ISSUED));
  assert.deepEqual([$("taEmail").value, $("taNote").value], ["", ""], "ready for the next one");
  assert.deepEqual(sent(/test-accounts/), [CREATE, LIST], "the list is read again");
  assert.equal($("testAccountsWrap").hidden, false);

  $("taPasswordClose").click();
  assert.equal(dialog().open, false);
  passwordForgotten();
  assert.equal($("taPasswordEmail").textContent, "");
});

test("closing the dialog with Escape forgets the password too", async () => {
  await openDesk({ routes: { [CREATE]: () => created(), [LIST]: listed() } });
  fill();
  await create();
  assert.equal($("taPassword").textContent, ISSUED);
  dialog().close();
  dialog().dispatch("close");
  passwordForgotten();
});

test("a CI account may run without an end; QC always has one", async () => {
  await openDesk({ routes: {
    [CREATE]: () => created({ purpose: "ci", expiresAt: null, maxDevices: 1 }), [LIST]: listed(),
  } });
  fill({ purpose: "ci", maxDevices: "1" });
  assert.equal($("taDays").value, "", "the untouched 30 is cleared for CI");
  assert.equal($("taDaysHint").textContent, "(1–90, or blank: never ends)");
  await create();
  assert.deepEqual(createBody(), { email: "new@test.example", purpose: "ci", days: null, maxDevices: 1 },
    "blank days is sent as null: no expiry");
  assert.match(status()[0], /created \(CI\): approved and licensed, never ends, on up to 1 phone at once/);

  $("taPurpose").value = "qc";
  $("taPurpose").dispatch("change");
  assert.equal($("taDays").value, "30", "back to QC, the default returns");
  assert.equal($("taDaysHint").textContent, "(1–90)");
  $("taDays").value = "12";
  $("taPurpose").value = "ci";
  $("taPurpose").dispatch("change");
  assert.equal($("taDays").value, "12", "a number typed is kept");
});

test("a form the backend would refuse is stopped before anything is sent", async () => {
  for (const [form, text] of [
    [{ email: "" }, "Enter the test account's email address."],
    [{ email: "not-an-address" }, "Enter the test account's email address."],
    [{ days: "" }, "Days is 1 to 90: a QC account always ends."],
    [{ days: "91" }, "Days is 1 to 90: a QC account always ends."],
    [{ purpose: "ci", days: "0" }, "Days is 1 to 90, or blank for a CI account that never ends."],
    [{ maxDevices: "11" }, "Phones at once is 1 to 10."],
    [{ note: "x".repeat(201) }, "The note is at most 200 characters."],
  ]) {
    reset();
    await openDesk();
    fill(form);
    await create();
    assert.deepEqual(status(), [text, "muted err"], text);
    assert.deepEqual(sent(/test-accounts/), []);
  }
});

test("each refusal of a create is said, and no dialog opens", async () => {
  for (const [reply, text] of [
    [() => json(403, { detail: "test_accounts_disabled" }),
      "Test accounts are switched off on this server, or that address is not on one of its " +
      "test domains (TEST_ACCOUNT_DOMAINS)."],
    [() => json(409, { detail: "email_in_use" }),
      "That address already has a Semper account or sign-in. A test account needs an address nobody has used."],
    [() => json(403, { detail: "mfa_required" }), "Set up two-factor authentication first."],
    [() => json(403, { detail: "not_admin" }), "That account is not a Semper operator."],
    [() => json(422, { detail: [{ msg: "Value error, days must be at most 90", loc: ["body"] }] }),
      "Could not create new@test.example: days must be at most 90"],
    [offline, "Could not create new@test.example: The server could not be reached — check your connection and try again."],
  ]) {
    reset();
    await openDesk({ routes: { [CREATE]: reply } });
    fill();
    await create();
    assert.deepEqual(status(), [text, "muted err"], text);
    assert.equal(dialog().open, false);
    assert.equal($("taCreate").disabled, false);
  }
});

/* ------------------------------------------------------------- step-up */

test("a stale sign-in goes to Google carrying the form, and nothing is sent", async () => {
  await openDesk({ user: readyUser({ authAgeSeconds: 600 }) });
  fill({ note: "rack" });
  await create();
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 1);
  assert.equal($("status").textContent, "Re-authenticating with Google to create this test account…");
  assert.deepEqual(JSON.parse(storage.getItem("semper.resume")), {
    action: "testAccountCreate",
    body: { email: "new@test.example", purpose: "qc", days: 30, maxDevices: 3, note: "rack" },
  });
  assert.deepEqual(sent(/test-accounts/), []);
});

test("a password operator who cancels the step-up sends nothing", async () => {
  await openDesk({ user: readyUser({ providers: ["password"], authAgeSeconds: 600 }) });
  fill();
  prompts.answer(null);
  await create();
  assert.match(prompts.asked[0], /password to create this test account/);
  assert.deepEqual(status(), ["Cancelled — nothing was sent.", "muted"]);
  assert.deepEqual(sent(/test-accounts/), []);
});

test("the return leg creates the stashed account after one confirmation", async () => {
  const body = { email: "new@test.example", purpose: "ci", days: null, note: "CI" };
  confirms.answer(true);
  await openDesk({
    resume: { action: "testAccountCreate", body },
    routes: { [CREATE]: () => created({ purpose: "ci", expiresAt: null }), [LIST]: listed() },
  });
  await settle();
  assert.equal(confirms.asked[0], "Re-authenticated. Create the test account new@test.example now?");
  assert.deepEqual(createBody(), body);
  assert.equal($("taPassword").textContent, ISSUED);
});

test("a return leg declined, malformed, or without a fresh sign-in sends nothing", async () => {
  confirms.answer(false);
  await openDesk({ resume: { action: "testAccountEnd", uid: CI.uid, email: CI.email } });
  await settle();
  assert.deepEqual(status(), ["Cancelled — nothing was sent.", "muted"]);

  reset();
  await openDesk({ resume: { action: "testAccountExtend", uid: CI.uid, email: CI.email, days: 400 } });
  await settle();
  assert.equal(confirms.asked.length, 0, "a stash the form would refuse is dropped");

  reset();
  await openDesk({
    resume: { action: "testAccountPassword", uid: CI.uid, email: CI.email },
    redirect: null,
  });
  await settle();
  assert.deepEqual(status(), [
    "Did not give ci@test.example a new password: the Google sign-in did not finish. Try it again.",
    "muted err"]);
  assert.deepEqual(sent(/test-accounts/), []);
});

/* ---------------------------------------------------------------- list */

test("the list shows purpose, end, phones, status and note, and a row per account", async () => {
  await showList([QC, CI, { ...QC, uid: "uid-old", email: "old@test.example", status: "ended", note: "<b>x</b>" }]);
  assert.equal($("loadTestAccounts").textContent, "Refresh");
  assert.deepEqual(accountRows(), [
    ["qc-1@test.example", "QC", day(ENDS), "1 of 3", "active", "QC rack", "Extend New password End now"],
    ["ci@test.example", "CI", "Never", "0 of 10", "active", "—", "Extend New password End now"],
    ["old@test.example", "QC", day(ENDS), "1 of 3", "ended", "<b>x</b>", "Extend New password End now"],
  ]);
  assert.equal($("testAccountRows").querySelectorAll("b").length, 0, "a note is never markup");
  assert.equal($("testAccountRows").querySelectorAll("span").map((s) => s.className).join(),
    "pill ok,pill ok,pill off");
});

test("an empty list says so, and a failed one offers Retry", async () => {
  await showList([]);
  assert.deepEqual(accountRows(), [["No test accounts."]]);

  reset();
  let fail = true;
  await openDesk({ routes: {
    [LIST]: () => (fail ? json(403, { detail: "test_accounts_disabled" }) : json(200, { accounts: [QC] })),
  } });
  $("loadTestAccounts").click();
  await settle();
  assert.match(accountRows()[0][0], /^Test accounts are switched off on this server/);
  fail = false;
  $("testAccountRows").querySelector("button[data-retry]").click();
  await settle();
  assert.equal(accountRows().length, 1);
});

test("a late list answer is not drawn over a newer one", async () => {
  const first = deferred();
  let calls = 0;
  await openDesk({ routes: {
    [LIST]: () => (++calls === 1 ? first.promise : json(200, { accounts: [CI] })),
  } });
  $("loadTestAccounts").click();
  await settle();
  $("loadTestAccounts").disabled = false;
  $("loadTestAccounts").click();
  await settle();
  first.resolve(json(200, { accounts: [QC] }));
  await settle();
  assert.deepEqual(accountRows().map((r) => r[0]), ["ci@test.example"]);
});

/* ------------------------------------------------------------- actions */

test("Extend asks for days, sends them, and reads the list again", async () => {
  await showList([QC], { [`POST /v1/admin/test-accounts/${QC.uid}/extend`]: () => json(200, { expiresAt: ENDS }) });
  prompts.answer("45");
  rowButton("extend", QC.uid).click();
  await settle();
  assert.equal(prompts.asked[0], "Days from today, 1 to 90. The account and its licence then end that many days from now.");
  assert.deepEqual(bodyOf(net, "POST", `/${QC.uid}/extend`), { days: 45 });
  assert.deepEqual(status(), [`qc-1@test.example now ends ${day(ENDS)}.`, "muted"]);
  assert.deepEqual(sent(/test-accounts/), [LIST, `POST /v1/admin/test-accounts/${QC.uid}/extend`, LIST]);
});

test("Extend without a new end in the answer, refused, out of range or cancelled", async () => {
  await showList([QC], { [`POST /v1/admin/test-accounts/${QC.uid}/extend`]: () => json(200, {}) });
  prompts.answer("7");
  rowButton("extend", QC.uid).click();
  await settle();
  assert.deepEqual(status(), ["qc-1@test.example extended to 7 days from today.", "muted"]);

  for (const answer of ["91", "1.5", "seven"]) {
    prompts.answer(answer);
    rowButton("extend", QC.uid).click();
    await settle();
    assert.deepEqual(status(), ["Days is 1 to 90 — nothing was changed.", "muted err"], answer);
  }
  prompts.answer(null);
  rowButton("extend", QC.uid).click();
  await settle();
  assert.equal(sent(/extend/).length, 1, "only the first was sent");

  reset();
  await showList([QC], { [`POST /v1/admin/test-accounts/${QC.uid}/extend`]: () => json(404, { detail: "user_not_found" }) });
  prompts.answer("7");
  rowButton("extend", QC.uid).click();
  await settle();
  assert.deepEqual(status(), ["Could not extend qc-1@test.example: No Semper account has that address.", "muted err"]);
});

test("New password confirms, then shows the new one once", async () => {
  const path = `/v1/admin/test-accounts/${QC.uid}/password`;
  await showList([QC], { [`POST ${path}`]: () => json(200, { password: ISSUED }) });
  confirms.answer(false);
  rowButton("password", QC.uid).click();
  await settle();
  assert.deepEqual(sent(/password/), [], "declined: nothing sent");

  confirms.answer(true);
  rowButton("password", QC.uid).click();
  await settle();
  assert.match(confirms.asked[1], /The old one stops working at once, and every phone signed in with it is signed out/);
  assert.deepEqual(sent(/password/), [`POST ${path}`]);
  assert.equal(dialog().open, true);
  assert.equal($("taPassword").textContent, ISSUED);
  assert.deepEqual(status(), ["New password for qc-1@test.example; its phones are signed out. Copy it now.", "muted"]);
  $("taPasswordClose").click();
  passwordForgotten();
});

test("New password on a stale sign-in goes to Google with no password in the stash", async () => {
  await openDesk({ user: readyUser({ authAgeSeconds: 600 }), routes: { [LIST]: listed([QC]) } });
  $("loadTestAccounts").click();
  await settle();
  confirms.answer(true);
  rowButton("password", QC.uid).click();
  await settle();
  assert.deepEqual(JSON.parse(storage.getItem("semper.resume")),
    { action: "testAccountPassword", uid: QC.uid, email: QC.email });
  assert.deepEqual(sent(/password/), []);
});

test("End now needs the address typed, then deletes and reads the list again", async () => {
  const path = `/v1/admin/test-accounts/${QC.uid}`;
  await showList([QC], { [`DELETE ${path}`]: () => new Response(null, { status: 204 }) });
  confirms.answer(true);
  prompts.answer("qc-1@test.example ".toUpperCase());
  rowButton("end", QC.uid).click();
  await settle();
  assert.match(confirms.asked[0], /its analyses and cloud backups are erased, and its sign-in is deleted\. Its audit rows are kept\./);
  assert.deepEqual(status(), ["Not ended — the address did not match.", "muted"]);
  assert.deepEqual(sent(/DELETE/), []);

  confirms.answer(true);
  prompts.answer("qc-1@test.example");
  rowButton("end", QC.uid).click();
  await settle();
  assert.equal(prompts.asked[1], "This cannot be undone.\n\nType qc-1@test.example to end this test account:");
  assert.deepEqual(sent(/DELETE/), [`DELETE ${path}`]);
  assert.deepEqual(status(), ["qc-1@test.example ended: suspended, its data erased and its sign-in deleted.", "muted"]);
  assert.equal(sent(/GET \/v1\/admin\/test-accounts/).length, 2);
});

test("the return leg of End now finishes it", async () => {
  confirms.answer(true);
  await openDesk({
    resume: { action: "testAccountEnd", uid: QC.uid, email: QC.email },
    routes: { [`DELETE /v1/admin/test-accounts/${QC.uid}`]: () => new Response(null, { status: 204 }), [LIST]: listed([]) },
  });
  await settle();
  assert.equal(confirms.asked[0], "Re-authenticated. End qc-1@test.example now?");
  assert.deepEqual(sent(/DELETE/), [`DELETE /v1/admin/test-accounts/${QC.uid}`]);
});

test("the return leg of Extend finishes it with the stashed days", async () => {
  const path = `/v1/admin/test-accounts/${QC.uid}/extend`;
  confirms.answer(true);
  await openDesk({
    resume: { action: "testAccountExtend", uid: QC.uid, email: QC.email, days: 14 },
    routes: { [`POST ${path}`]: () => json(200, {}), [LIST]: listed([QC]) },
  });
  await settle();
  assert.equal(confirms.asked[0], "Re-authenticated. Extend qc-1@test.example to 14 days from today now?");
  assert.deepEqual(bodyOf(net, "POST", path), { days: 14 });
});

/* ------------------------------------------------------ release one phone */

const PHONES = {
  ...QC, activeDevices: 2, devices: [
    { deviceId: "and-phone-1", app: "semper", model: "Pixel 6", registeredAt: "2026-10-19T10:00:00Z" },
    { deviceId: "and-phone-<2>", app: "semperclassroom", model: "", registeredAt: null },
  ],
};
const RELEASE = `/v1/admin/test-accounts/${QC.uid}/devices/and-phone-1/release`;
const releaseButton = (deviceId) => $("testAccountRows").querySelector(
  `button[data-ta-release="${QC.uid}"][data-ta-device="${deviceId}"]`);

test("the list shows each active phone with its own Release", async () => {
  await showList([PHONES, CI]);
  // Model and app, the id under them (or the id alone, without a model), Release.
  assert.deepEqual(accountRows()[0].slice(3, 4), [
    "2 of 3 Pixel 6 Semper and-phone-1 Release and-phone-<2> Semper Classroom Release"]);
  assert.deepEqual(accountRows()[1].slice(3, 4), ["0 of 10"], "no phones, no buttons");
  const phones = $("testAccountRows").querySelectorAll("li");
  assert.equal(phones.length, 2, "one line per phone");
  assert.ok(releaseButton("and-phone-1"));
  assert.ok(releaseButton("and-phone-<2>"), "an id is text, never markup");
  assert.equal(releaseButton("and-phone-1").getAttribute("aria-label"), "Release Pixel 6 (and-phone-1)",
    "each Release names its phone to a screen reader");
  assert.equal(releaseButton("and-phone-1").className, "danger", "a release cannot be undone");
  assert.equal(rowButton("end", QC.uid).className, "danger");
  assert.equal(rowButton("extend", QC.uid).className, "secondary");
});

test("a phone registered before ADR-030 is labelled Semper Classroom too", async () => {
  // devices/{id}.app is never rewritten, so it may still say materialtesting.
  await showList([{ ...QC, activeDevices: 1, devices: [
    { deviceId: "old-phone", app: "materialtesting", model: "Pixel 5", registeredAt: null },
  ] }]);
  assert.deepEqual(accountRows()[0].slice(3, 4), ["1 of 3 Pixel 5 Semper Classroom old-phone Release"]);
});

test("Release confirms, sends, says so and reads the list again", async () => {
  await showList([PHONES], { [`POST ${RELEASE}`]: () => json(200, { ...QC, activeDevices: 1 }) });
  confirms.answer(false);
  releaseButton("and-phone-1").click();
  await settle();
  assert.deepEqual(sent(/release/), [], "declined: nothing sent");

  confirms.answer(true);
  releaseButton("and-phone-1").click();
  await settle();
  assert.match(confirms.asked[1], /^Release Pixel 6 \(and-phone-1\) from qc-1@test\.example\?/);
  assert.match(confirms.asked[1], /cannot register for it again; its analyses stay\./);
  assert.deepEqual(sent(/test-accounts/), [LIST, `POST ${RELEASE}`, LIST]);
  assert.deepEqual(status(), [
    "Phone and-phone-1 released from qc-1@test.example: its place is free, and that phone " +
    "cannot register for this account again.", "muted"]);
});

test("a phone that is no longer active, or a cancelled step-up, is said", async () => {
  await showList([PHONES], { [`POST ${RELEASE}`]: () => json(404, { detail: "device_not_found" }) });
  confirms.answer(true);
  releaseButton("and-phone-1").click();
  await settle();
  assert.deepEqual(status(), [
    "That phone is no longer one of this account's active phones. Refresh the list.", "muted err"]);

  reset();
  await openDesk({ user: readyUser({ providers: ["password"], authAgeSeconds: 600 }),
    routes: { [LIST]: listed([PHONES]) } });
  $("loadTestAccounts").click();
  await settle();
  confirms.answer(true);
  prompts.answer(null);
  releaseButton("and-phone-1").click();
  await settle();
  assert.match(prompts.asked[0], /password to release this phone/);
  assert.deepEqual(status(), ["Cancelled — nothing was sent.", "muted"]);
  assert.deepEqual(sent(/release/), []);
});

test("Release on a stale sign-in goes to Google carrying the phone", async () => {
  await openDesk({ user: readyUser({ authAgeSeconds: 600 }), routes: { [LIST]: listed([PHONES]) } });
  $("loadTestAccounts").click();
  await settle();
  confirms.answer(true);
  releaseButton("and-phone-1").click();
  await settle();
  assert.equal($("status").textContent, "Re-authenticating with Google to release this phone…");
  assert.deepEqual(JSON.parse(storage.getItem("semper.resume")), {
    action: "testAccountDeviceRelease", uid: QC.uid, email: QC.email,
    deviceId: "and-phone-1", app: "semper",
  });
  assert.deepEqual(sent(/release/), []);
});

test("the return leg of Release finishes it, and a stash without a phone is dropped", async () => {
  confirms.answer(true);
  await openDesk({
    resume: { action: "testAccountDeviceRelease", uid: QC.uid, email: QC.email,
      deviceId: "and-phone-1", app: "semper" },
    routes: { [`POST ${RELEASE}`]: () => json(200, QC), [LIST]: listed([QC]) },
  });
  await settle();
  assert.equal(confirms.asked[0],
    "Re-authenticated. Release phone and-phone-1 from qc-1@test.example now?");
  assert.deepEqual(sent(/release/), [`POST ${RELEASE}`]);

  reset();
  await openDesk({ resume: { action: "testAccountDeviceRelease", uid: QC.uid, email: QC.email } });
  await settle();
  assert.equal(confirms.asked.length, 0);
  assert.deepEqual(sent(/release/), []);
});

/* ------------------------------------------------------------------ copy */

test("Copy puts the password on the clipboard, or says to copy it by hand", async () => {
  const had = Object.getOwnPropertyDescriptor(navigator, "clipboard");
  const written = [];
  let ok = true;
  Object.defineProperty(navigator, "clipboard", {
    configurable: true,
    value: { writeText: (text) => { written.push(text); return ok ? Promise.resolve() : Promise.reject(new Error("no")); } },
  });
  try {
    await openDesk({ routes: { [CREATE]: () => created(), [LIST]: listed() } });
    fill();
    await create();
    $("taCopyPassword").click();
    await settle();
    assert.deepEqual(written, [ISSUED]);
    assert.deepEqual(status(), ["Password copied.", "muted"]);
    ok = false;
    $("taCopyPassword").click();
    await settle();
    assert.deepEqual(status(), ["Copy failed — select and copy it by hand.", "muted err"]);
  } finally {
    if (had) Object.defineProperty(navigator, "clipboard", had);
    else delete navigator.clipboard;
  }
});

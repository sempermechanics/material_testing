// Run: node --test "firebase-hosting/tests/*.test.mjs"
// DOM-free console helpers (public/console/util.js). CI runs these in the
// console-pages job.
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  esc, when, day, licenceState, licenceStatePill, holdsLicence, leaseHeld, seatCells, seatDevices, inviteCells,
  APP_NAMES, classroomField,
  errorDetail, licenceListPath, licenceSearchBody, searchableLicenceText, upsertLicence, alreadyLicensedId,
  isoDay, emailList, licenceEditPatch, daysLeft, reauthMethods, unfinishedStepUpText,
  normaliseClaimCode, claimCodeShaped, MAX_CLAIM_CODE,
  reauthFailureText, testAccountBody, testAccountEnds, extendDays, TEST_ACCOUNT, localDayRange,
} from "../public/console/util.js";

const NOW = Date.parse("2026-09-23T12:00:00Z");

test("esc neutralises markup from user-supplied text", () => {
  assert.equal(esc(`<img src=x onerror="a('b')">&`),
    "&lt;img src=x onerror=&quot;a(&#39;b&#39;)&quot;&gt;&amp;");
  assert.equal(esc(null), "");
  assert.equal(esc(undefined), "");
});

test("when renders an em dash for nothing or garbage", () => {
  assert.equal(when(null), "—");
  assert.equal(when("not a date"), "—");
  assert.notEqual(when("2026-09-23T12:00:00Z"), "—");
});

test("when names the time zone it is shown in", () => {
  const at = new Date("2026-09-23T12:00:00Z");
  const zone = new Intl.DateTimeFormat(undefined, { timeZoneName: "short" })
    .formatToParts(at).find((p) => p.type === "timeZoneName").value;
  assert.ok(when(at.toISOString()).endsWith(zone), `${when(at.toISOString())} ends with ${zone}`);
});

test("an audit date range is the local days chosen, as instants", () => {
  // The rows are shown in local time (when), so the filter is local days too:
  // until includes its day, up to the next local midnight.
  assert.deepEqual(localDayRange({ since: "2026-10-18", until: "2026-10-18" }), {
    since: new Date(2026, 9, 18).toISOString(),
    until: new Date(2026, 9, 19).toISOString(),
  });
  assert.deepEqual(localDayRange({ since: "", until: "" }), { since: "", until: "" });
  assert.deepEqual(localDayRange({ since: "garbage", until: "2026-12-31" }), {
    since: "garbage", until: new Date(2027, 0, 1).toISOString(),
  });
});

test("only a real licence counts as held, and a backend without held is read from mode", () => {
  assert.equal(holdsLicence({ mode: "licensed", held: true }), true);
  assert.equal(holdsLicence({ mode: "demo", held: true }), true, "lapsed, still attached");
  assert.equal(holdsLicence({ mode: "demo", held: false, prefix: "SEMP-DEMO" }), false);
  assert.equal(holdsLicence({ mode: "licensed" }), true);
  assert.equal(holdsLicence({ mode: "demo" }), false);
  assert.equal(holdsLicence(undefined), false);
});

test("a lease is held only until it ends", () => {
  assert.equal(leaseHeld("2026-09-23T12:05:00Z", NOW), true);
  assert.equal(leaseHeld("2026-09-23T11:55:00Z", NOW), false);
  assert.equal(leaseHeld("2026-09-23T12:00:00Z", NOW), false);
  assert.equal(leaseHeld(null, NOW), false);
  assert.equal(leaseHeld("", NOW), false);
});

test("an expired lease is not shown as held (TD-50)", () => {
  const html = seatCells(
    { uid: "u1", email: "a@uni.edu", status: "active", leaseExpiresAt: "2026-09-23T11:00:00Z" },
    NOW,
  );
  assert.doesNotMatch(html, /until/);
  assert.match(html, /pill off">—/);
});

test("a live lease shows its end", () => {
  const html = seatCells(
    { uid: "u1", email: "a@uni.edu", status: "active", leaseExpiresAt: "2026-09-23T13:00:00Z" },
    NOW,
  );
  assert.match(html, /pill ok">until /);
});

test("seat cells: four columns, status labels, escaped member", () => {
  const html = seatCells({ uid: "u<2>", status: "disabled", deviceIdLock: "abcdefghijklmnop" }, NOW);
  assert.equal(html.match(/<td/g).length, 4);
  assert.match(html, /paused/);
  assert.match(html, /u&lt;2&gt;/);
  assert.match(html, /abcdefghij…/);
  assert.match(seatCells({ uid: "u", status: "revoked" }, NOW), /removed/);
  assert.match(seatCells({ uid: "u", status: "active" }, NOW), /not yet/);
});

test("a seat shows each app's device, escaped (ADR-010)", () => {
  assert.equal(seatDevices({}), "");
  assert.equal(seatDevices({ deviceIdLock: "abcdefghijklmnop" }), "abcdefghij…");
  // Semper Classroom's lock is read from the old field, which every backend
  // writes beside the new one (ADR-030, TD-330).
  const bound = (id) => ({ deviceIdLockMaterialTesting: id, deviceIdLockSemperClassroom: id });
  assert.equal(
    seatDevices({ deviceIdLock: "abcdefghijklmnop", ...bound("<sc>defghijklmn") }),
    "abcdefghij…; semper Classroom &lt;sc&gt;defghi…",
  );
  assert.equal(seatDevices(bound("zyxwvutsrqpo")), "semper Classroom zyxwvutsrq…");
  assert.match(seatCells({ uid: "u", status: "active", ...bound("zyxwvutsrqpo") }, NOW),
    /semper Classroom zyxwvutsrq…/);
  // A seat not yet migrated (ADR-030) holds only the old field.
  assert.equal(seatDevices({ deviceIdLockMaterialTesting: "mnopqrstuvwx" }), "semper Classroom mnopqrstuv…");
  assert.equal(
    seatDevices({ deviceIdLockSemperClassroom: "newnewnewnew", deviceIdLockMaterialTesting: "oldoldoldold" }),
    "semper Classroom oldoldoldo…",
    "the old field, the one every backend writes, wins until TD-330",
  );
  // The new field alone is one a backend from before the rename left
  // behind when it cleared the old one: stale, so not shown (H1).
  assert.equal(seatDevices({ deviceIdLockSemperClassroom: "zyxwvutsrqpo" }), "");
  assert.doesNotMatch(seatCells({ uid: "u", status: "active", deviceIdLockSemperClassroom: "zyxwvutsrqpo" }, NOW),
    /semper Classroom/);
});

test("semper Classroom's fields are read from the name before ADR-030 only", () => {
  assert.equal(classroomField({ releasedDeviceIdSemperClassroom: "a", releasedDeviceIdMaterialTesting: "b" },
    "releasedDeviceId"), "b");
  assert.equal(classroomField({ releasedDeviceIdMaterialTesting: "b" }, "releasedDeviceId"), "b");
  assert.equal(classroomField({ releasedDeviceIdSemperClassroom: "a" }, "releasedDeviceId"), undefined,
    "the new field alone may be stale (H1), as apps.read_field reads it");
  assert.equal(classroomField({ releasedDeviceIdSemperClassroom: "a", releasedDeviceIdMaterialTesting: "" },
    "releasedDeviceId"), undefined, "a cleared old field is cleared, whatever the new one says");
  assert.equal(classroomField({}, "releasedDeviceId"), undefined);
  assert.equal(classroomField(undefined, "previousDeviceId"), undefined, "an event with no detail");
  assert.equal(classroomField(null, "previousDeviceId"), undefined);
});

test("both of semper Classroom's short names are labelled semper Classroom", () => {
  assert.equal(APP_NAMES.semper, "semper");
  assert.equal(APP_NAMES.semperclassroom, "semper Classroom");
  assert.equal(APP_NAMES.materialtesting, "semper Classroom");
});

test("invite cells line up with seat cells", () => {
  const html = inviteCells({ id: "k", email: "new@uni.edu" });
  assert.equal(html.match(/<td/g).length, 4);
  assert.match(html, /invited/);
});

test("a licence end shows the day it was set to, in any time zone", () => {
  // Stored as the last second of the chosen day, UTC. Rendered locally this
  // read as the next day anywhere east of UTC.
  const shown = day("2026-06-30T23:59:59Z");
  assert.match(shown, /30/);
  assert.ok(shown.endsWith(" (UTC)"), `${shown} says it is a UTC day`);
  assert.doesNotMatch(shown, /1|Jul/);
  assert.equal(day(null), "—");
  assert.equal(day("garbage"), "—");
});

test("a licence's state follows its end and grace, not only its status", () => {
  const lic = (over) => ({ status: "redeemed", ...over });
  assert.equal(licenceState(lic({}), NOW), "redeemed");
  assert.equal(licenceState(lic({ status: "revoked" }), NOW), "revoked");
  assert.equal(licenceState(lic({
    expiresAt: "2026-09-30T23:59:59Z", graceEndsAt: "2026-10-14T23:59:59Z",
  }), NOW), "redeemed");
  assert.equal(licenceState(lic({
    expiresAt: "2026-09-20T23:59:59Z", graceEndsAt: "2026-10-04T23:59:59Z",
  }), NOW), "in grace");
  assert.equal(licenceState(lic({
    expiresAt: "2026-09-01T23:59:59Z", graceEndsAt: "2026-09-15T23:59:59Z",
  }), NOW), "expired");
  // No grace: expiry is the cliff.
  assert.equal(licenceState(lic({ expiresAt: "2026-09-22T23:59:59Z" }), NOW), "expired");
  assert.match(licenceStatePill(lic({ expiresAt: "2026-09-22T23:59:59Z" }), NOW), /pill off/);
});

test("an error detail splits at its first colon only (TD-116)", () => {
  assert.deepEqual(errorDetail("no_license"), { code: "no_license", rest: "" });
  // An ISO instant carries colons of its own.
  assert.deepEqual(
    errorDetail("device_change_too_soon: 2026-10-25T08:00:00+00:00"),
    { code: "device_change_too_soon", rest: "2026-10-25T08:00:00+00:00" },
  );
  assert.deepEqual(errorDetail(undefined), { code: "", rest: "" });
});

test("the licence list asks the backend to leave Demo and revoked rows out", () => {
  assert.equal(licenceListPath({ limit: 50 }), "/v1/admin/licenses?limit=50&include_revoked=false");
  assert.equal(
    licenceListPath({ limit: 50, showDemo: true, showRevoked: true, pageToken: "abc" }),
    "/v1/admin/licenses?limit=50&include_demo=true&page_token=abc",
  );
  // A search is never a query parameter (TD-244): it is licenceSearchBody.
  assert.equal(
    licenceListPath({ limit: 50, showRevoked: true, q: "a@b.com" }),
    "/v1/admin/licenses?limit=50",
  );
});

test("a licence search is a JSON body: the text, the page size, any page token and the toggles", () => {
  const hidden = { include_demo: false, include_revoked: false };
  assert.deepEqual(JSON.parse(licenceSearchBody({ q: " a@b.com ", limit: 50 })),
    { q: "a@b.com", limit: 50, ...hidden });
  assert.deepEqual(JSON.parse(licenceSearchBody({ q: "uni.edu", limit: 50, pageToken: "t2" })),
    { q: "uni.edu", limit: 50, page_token: "t2", ...hidden });
  assert.deepEqual(JSON.parse(licenceSearchBody({ q: null })), { q: "", ...hidden });
  // The boxes the list's query sends, sent the same way: a ticked "Show Demo
  // keys" has the backend include Demo keys in a search too.
  assert.deepEqual(JSON.parse(licenceSearchBody({ q: "a@b.com", showDemo: true, showRevoked: true })),
    { q: "a@b.com", include_demo: true, include_revoked: true });
});

test("only an address, a domain or a key prefix is sent as a search", () => {
  assert.equal(searchableLicenceText("a@b.com"), true);
  assert.equal(searchableLicenceText(" uni.edu "), true);
  assert.equal(searchableLicenceText("SEMP-K7QX"), true);
  assert.equal(searchableLicenceText("semp-k7qx-aaaa"), true);
  assert.equal(searchableLicenceText("renewal"), false);
  assert.equal(searchableLicenceText("a@b"), false);
  assert.equal(searchableLicenceText(""), false);
});

test("a refused mint names the licence the address already holds", () => {
  assert.equal(alreadyLicensedId("email_already_licensed: 1c2fff4d30"), "1c2fff4d30");
  assert.equal(alreadyLicensedId("email_already_licensed:abc"), "abc");
  assert.equal(alreadyLicensedId("email_already_licensed"), "");
  assert.equal(alreadyLicensedId("invite_exists"), "");
  assert.equal(alreadyLicensedId(undefined), "");
});

test("a changed licence replaces its row and a new one goes on top", () => {
  const list = [{ id: "a", note: "x", seatsUsed: 2 }, { id: "b" }];
  const changed = upsertLicence(list, { id: "a", seatsUsed: 0 });
  assert.deepEqual(changed, [{ id: "a", note: "x", seatsUsed: 0 }, { id: "b" }]);
  assert.equal(list[0].seatsUsed, 2);
  assert.deepEqual(upsertLicence(list, { id: "c" }).map((l) => l.id), ["c", "a", "b"]);
  assert.equal(upsertLicence(list, null), list);
});

const timed = {
  id: "t", kind: "individual", duration: "timed", expiresAt: "2027-03-31T23:59:59Z",
  graceDays: 14, maxAnalyses: null, note: "PO 1",
};
const form = (over = {}) => ({
  expiry: "2027-03-31", perpetual: false, graceDays: "14", supportUntil: "",
  maxAnalyses: "", note: "PO 1", ...over,
});

test("an unchanged dialog sends nothing", () => {
  assert.equal(licenceEditPatch(timed, form()).error, "Nothing changed.");
});

test("a later end is an extension, an earlier one a confirmed downgrade", () => {
  assert.deepEqual(licenceEditPatch(timed, form({ expiry: "2028-03-31" })),
    { patch: { expiresAt: "2028-03-31T23:59:59Z" }, shortens: false, error: "" });
  assert.deepEqual(licenceEditPatch(timed, form({ expiry: "2027-01-31" })),
    { patch: { expiresAt: "2027-01-31T23:59:59Z", allowShorten: true }, shortens: true, error: "" });
});

test("never expires drops the end; giving a perpetual licence one is a downgrade", () => {
  assert.deepEqual(licenceEditPatch(timed, form({ perpetual: true })).patch, { perpetual: true });
  const perpetual = { ...timed, duration: "perpetual", expiresAt: null, graceDays: null };
  const out = licenceEditPatch(perpetual, form({ expiry: "2027-06-30", graceDays: "" }));
  assert.deepEqual(out.patch, { expiresAt: "2027-06-30T23:59:59Z", allowShorten: true });
  assert.equal(out.shortens, true);
  assert.equal(licenceEditPatch(perpetual, form({ expiry: "", graceDays: "" })).error, "Nothing changed.");
});

test("the cap is set, cleared, or left alone on a demo key", () => {
  assert.deepEqual(licenceEditPatch(timed, form({ maxAnalyses: "500" })).patch, { maxAnalyses: 500 });
  const capped = { ...timed, maxAnalyses: 500 };
  assert.deepEqual(licenceEditPatch(capped, form({ maxAnalyses: "" })).patch, { clearMaxAnalyses: true });
  assert.equal(licenceEditPatch(timed, form({ maxAnalyses: "9", capLocked: true })).error, "Nothing changed.");
});

test("an institution's seats, seating and IT contacts", () => {
  const inst = { ...timed, kind: "institution", maxSeats: 10, seating: "assigned",
    adminEmails: ["it@uni.edu"] };
  const base = { maxSeats: "10", seating: "assigned", adminEmails: "it@uni.edu" };
  assert.deepEqual(licenceEditPatch(inst, form({ ...base, maxSeats: "25", seating: "floating",
    adminEmails: "IT@uni.edu, dean@uni.edu" })).patch,
  { maxSeats: 25, seating: "floating", adminEmails: ["it@uni.edu", "dean@uni.edu"] });
  assert.match(licenceEditPatch(inst, form({ ...base, adminEmails: " " })).error, /IT contact/);
  assert.match(licenceEditPatch(inst, form({ ...base, maxSeats: "" })).error, /Seats/);
});

test("a time-limited licence cannot lose its date by accident", () => {
  assert.match(licenceEditPatch(timed, form({ expiry: "" })).error, /Never expires/);
});

test("helpers", () => {
  assert.equal(isoDay("2027-03-31T23:59:59Z"), "2027-03-31");
  assert.equal(isoDay(null), "");
  assert.deepEqual(emailList(" A@x.org, a@x.org ,, b@x.org"), ["a@x.org", "b@x.org"]);
});

test("days left in the deleted hold", () => {
  const now = Date.parse("2026-09-25T12:00:00Z");
  assert.equal(daysLeft("2026-10-25T12:00:00Z", now), 30);
  assert.equal(daysLeft("2026-09-25T13:00:00Z", now), 1);
  assert.equal(daysLeft("2026-09-24T00:00:00Z", now), 0);
  assert.equal(daysLeft(null, now), 0);
});

test("only an account with a password is asked for one", () => {
  assert.deepEqual(reauthMethods([{ providerId: "google.com" }]),
    { password: false, google: true });
  assert.deepEqual(reauthMethods([{ providerId: "password" }, { providerId: "google.com" }]),
    { password: true, google: true });
  assert.deepEqual(reauthMethods([{ providerId: "password" }]),
    { password: true, google: false });
  assert.deepEqual(reauthMethods(null), { password: false, google: false });
});

test("a failed re-authentication says the action was not sent", () => {
  assert.equal(
    unfinishedStepUpText({ action: "delete", id: "x", reauthFailed: "incomplete" }, "SEMP-2YCY"),
    "SEMP-2YCY was not deleted: the Google sign-in did not finish. Delete it again to retry.");
  assert.equal(
    unfinishedStepUpText({ action: "revoke", id: "x", reauthFailed: "cancelled" }, "SEMP-2YCY"),
    "SEMP-2YCY was not revoked: the authenticator code was not entered. Revoke it again to retry.");
  assert.match(
    unfinishedStepUpText({ action: "delete", id: "x", reauthFailed: "auth/network-request-failed" }, "K"),
    /was not deleted: the sign-in service did not answer — check your connection\. /);
});

test("a failed re-authentication is said in words, an unknown reason as a reference", () => {
  assert.equal(reauthFailureText("cancelled"), "the authenticator code was not entered");
  assert.equal(reauthFailureText("auth/x"), "re-authentication did not finish (ref: auth/x)");
});

/* ------------------------------------------------------- test accounts */

const taForm = (over = {}) => ({ email: "qc@test.example", purpose: "qc", days: "30", maxDevices: "3", note: "", ...over });

test("a test account's body is the form's values, numbers as numbers, an empty note left out", () => {
  assert.deepEqual(testAccountBody(taForm({ email: " qc@test.example ", note: " rack " })), {
    body: { email: "qc@test.example", purpose: "qc", days: 30, maxDevices: 3, note: "rack" },
  });
  assert.deepEqual(testAccountBody(taForm({ maxDevices: "" })).body,
    { email: "qc@test.example", purpose: "qc", days: 30 }, "blank phones: the backend's default");
  assert.deepEqual(testAccountBody(taForm({ days: "90", maxDevices: "10" })).body.days, 90);
  assert.deepEqual(testAccountBody(taForm({ days: "1", maxDevices: "1" })).body.maxDevices, 1);
  assert.equal(testAccountBody(taForm({ note: "x".repeat(TEST_ACCOUNT.maxNote) })).body.note.length, 200);
});

test("a CI account without days never ends; a QC one must end", () => {
  assert.deepEqual(testAccountBody(taForm({ purpose: "ci", days: " " })).body.days, null);
  assert.deepEqual(testAccountBody(taForm({ purpose: "ci", days: "7" })).body.days, 7);
  assert.match(testAccountBody(taForm({ days: "" })).error, /a QC account always ends/);
});

test("each field the backend would refuse is named before anything is sent", () => {
  for (const [over, why] of [
    [{ email: "" }, /email address/],
    [{ email: "a@b" }, /email address/],
    [{ email: "a b@c.d" }, /email address/],
    [{ purpose: "demo" }, /QC or CI/],
    [{ days: "0" }, /1 to 90/],
    [{ days: "91" }, /1 to 90/],
    [{ days: "2.5" }, /1 to 90/],
    [{ days: "-3" }, /1 to 90/],
    [{ purpose: "ci", days: "100" }, /or blank for a CI account/],
    [{ maxDevices: "0" }, /Phones at once is 1 to 10/],
    [{ maxDevices: "11" }, /Phones at once is 1 to 10/],
    [{ note: "x".repeat(201) }, /at most 200/],
  ]) {
    const out = testAccountBody(taForm(over));
    assert.equal(out.body, undefined, JSON.stringify(over));
    assert.match(out.error, why, JSON.stringify(over));
  }
});

test("Extend takes a whole number of days, 1 to 90", () => {
  assert.equal(extendDays(" 45 "), 45);
  assert.equal(extendDays("90"), 90);
  for (const text of ["", "0", "91", "1.5", "ten", null]) assert.equal(extendDays(text), null, String(text));
});

test("a test account ends on its UTC day, or never", () => {
  assert.equal(testAccountEnds({ expiresAt: "2026-11-18T23:59:59Z" }), day("2026-11-18T23:59:59Z"));
  assert.equal(testAccountEnds({ expiresAt: null }), "Never");
  assert.equal(testAccountEnds(null), "Never");
});

test("a past end is refused before the typed key is asked for", () => {
  const out = licenceEditPatch(timed, form({ expiry: "2026-09-22" }), NOW);
  assert.match(out.error, /already passed/);
  assert.equal(out.shortens, false);
  // Today still has hours left: the end is the last second of the day.
  assert.equal(licenceEditPatch(timed, form({ expiry: "2026-09-23" }), NOW).error, "");
  // An expired licence can still take a note without moving its end.
  const expired = { ...timed, expiresAt: "2026-09-01T23:59:59Z" };
  assert.deepEqual(
    licenceEditPatch(expired, form({ expiry: "2026-09-01", note: "renewal due" }), NOW).patch,
    { note: "renewal due" });
});

test("a claim code is tidied character by character: upper case, letters, digits and dashes", () => {
  assert.equal(normaliseClaimCode(" semp-ab2c de3f\t'gh4j'\n-km5n "), "SEMP-AB2CDE3FGH4J-KM5N");
  assert.equal(normaliseClaimCode("ünï-cødé"), "N-CD", "nothing outside ASCII letters and digits");
  assert.equal(normaliseClaimCode(null), "");
  assert.equal(normaliseClaimCode(undefined), "");
  // Tidying the part before a caret gives where the caret goes.
  const raw = "ab cd ef";
  assert.equal(normaliseClaimCode(raw.slice(0, 5)).length, 4);
});

test("a claim code is shaped as dash-split groups, eight to sixty-four long", () => {
  for (const ok of ["SEMP-AB2C-DE3F-GH4J-KM5N", "ABCD2345", "A-B-C-D-E-F-G-H", "A".repeat(MAX_CLAIM_CODE)]) {
    assert.equal(claimCodeShaped(ok), true, ok);
  }
  for (const bad of ["", "ABC-1234", "SEMP--ABCD-EFGH", "-ABCDEFGH", "ABCDEFGH-", "abcdefgh",
    "ABCD EFGH", "A".repeat(MAX_CLAIM_CODE + 1), null, undefined]) {
    assert.equal(claimCodeShaped(bad), false, String(bad));
  }
});

/* ------------------------------------------------------------ import graph */
// auth.js holds the Firebase SDK and a top-level await on the project config.
// A page that wants a DOM helper or an API call should not need to know that,
// and a module that only draws or formats must not load Firebase at all, so
// node --test can run it bare and a page can show a dialog before sign-in.

import { readFileSync, readdirSync } from "node:fs";
import { dirname, join, resolve as resolvePath } from "node:path";
import { fileURLToPath } from "node:url";

const CONSOLE_DIR = fileURLToPath(new URL("../public/console/", import.meta.url));

function staticImports(file) {
  const source = readFileSync(file, "utf8");
  return [...source.matchAll(/^\s*(?:import|export)\s[^;]*?from\s+"([^"]+)"/gms)].map((m) => m[1]);
}

function reaches(file, target, seen = new Set()) {
  if (seen.has(file)) return false;
  seen.add(file);
  for (const spec of staticImports(file)) {
    if (spec.startsWith("https:")) {
      if (target === "https:") return true;
      continue;
    }
    const next = resolvePath(dirname(file), spec);
    if (next === target || reaches(next, target, seen)) return true;
  }
  return false;
}

test("the DOM and formatting modules load no Firebase", () => {
  for (const name of ["util.js", "dialogs.js", "status.js", "messages.js"]) {
    const file = join(CONSOLE_DIR, name);
    assert.equal(reaches(file, join(CONSOLE_DIR, "auth.js")), false, `${name} reaches auth.js`);
    assert.equal(reaches(file, "https:"), false, `${name} imports a remote module`);
  }
});

test("auth.js re-exports nothing: each helper is imported from the module that owns it", () => {
  const source = readFileSync(join(CONSOLE_DIR, "auth.js"), "utf8");
  assert.doesNotMatch(source, /^\s*export\s*\{[^}]*\}\s*from/m);
});

test("auth.js is sign-in and the second factor only", () => {
  const lines = readFileSync(join(CONSOLE_DIR, "auth.js"), "utf8").split("\n").length;
  assert.ok(lines <= 600, `auth.js is ${lines} lines`);
  const pages = readdirSync(CONSOLE_DIR, { recursive: true })
    .filter((f) => f.endsWith(".js") && !f.startsWith("vendor"));
  for (const page of pages) {
    const source = readFileSync(join(CONSOLE_DIR, page), "utf8");
    for (const m of source.matchAll(/import\s*\{([^}]*)\}\s*from\s*"(?:\.\.?\/)+auth\.js"/g)) {
      for (const name of m[1].split(",").map((s) => s.trim()).filter(Boolean)) {
        assert.ok(!["esc", "when", "day", "api", "apiBlob", "saveBlob", "setStatus", "whileWaiting",
          "askInPage", "confirmInPage", "tellInPage", "confirmByTyping", "markFirstData"].includes(name),
        `${page} imports ${name} from auth.js`);
      }
    }
  }
});

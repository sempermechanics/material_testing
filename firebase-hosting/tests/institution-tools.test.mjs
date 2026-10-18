// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The institution page's bulk tools (console/institution/roster-tools.js and
// audit-log.js): the seat-count tiles, the roster export, the bulk import and
// the audit-log panel, on the fake SDK and DOM (harness.mjs).
import { beforeEach, afterEach, mock } from "node:test";
import assert from "node:assert/strict";
import { resolveObjectURL } from "node:buffer";
import {
  test, reset, settle, openPage, json, net, sent, $, page, loadAuth, deferred,
} from "./harness.mjs";

await loadAuth();
const { importRowCount, auditQuery, MAX_IMPORT_ROWS } = await import("../public/console/util.js");
const { rowResult, importSummary, tilesHtml } = await import("../public/console/institution/roster-tools.js");
const { auditRow } = await import("../public/console/institution/audit-log.js");

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

const L1 = "/v1/institutions/licenses/L1";
const L2 = "/v1/institutions/licenses/L2";
const roster = {
  license: { keyPrefix: "SEMP-UNI1", seating: "assigned", maxSeats: 10, seatsUsed: 2 },
  seats: [
    { uid: "u1", email: "ana@uni.edu", status: "active" },
    { uid: "u2", email: "ben@uni.edu", status: "active" },
  ],
  invites: [],
};
const counts = { assigned: 4, activeLast7Days: 2, onHold: 1, invited: 2, revocationPending: 1, expiringWithin30Days: 0 };

/** Licence L1's page, opened by deep link. */
async function open({ routes = {}, search = "?license=L1" } = {}) {
  await openPage("institution", {
    search,
    routes: {
      "GET /v1/institutions/licenses": () => json(200, { licenses: [{ id: "L1" }, { id: "L2" }] }),
      "GET /v1/me": () => json(200, { role: "user" }),
      [`GET ${L1}/seats`]: () => json(200, roster),
      [`GET ${L1}/summary`]: () => json(200, { counts }),
      [`GET ${L2}/seats`]: () => json(200, { ...roster, license: { keyPrefix: "SEMP-UNI2" }, seats: [] }),
      [`GET ${L2}/summary`]: () => json(200, { counts: { assigned: 9 } }),
      ...routes,
    },
  });
}

const status = () => [$("status").textContent, $("status").className];
const text = (el) => el.textContent.replace(/\s+/g, " ").trim();
const tiles = () => $("tiles").querySelectorAll(".tile").map((t) => [text(t), t.classList.includes("warn")]);
const tableRows = (id) => $(id).querySelectorAll("tr").map((tr) => tr.cells.map(text));

/** Open licence `id` by typing it, as IT would. */
async function openLicence(id) {
  $("licenseId").value = id;
  $("load").click();
  await settle();
}

/** Run `fn` while capturing the downloads `saveBlob` starts: [{ name, blob }]. */
async function downloads(fn) {
  mock.timers.enable({ apis: ["setTimeout"] }); // saveBlob revokes its URL after 30 s
  const saved = [];
  const append = page.body.appendChild.bind(page.body);
  page.body.appendChild = (el) => {
    saved.push({ name: el.download, blob: resolveObjectURL(el.href), el });
    return append(el);
  };
  try {
    await fn();
  } finally {
    mock.timers.reset();
  }
  return saved;
}

/* ---------------------------------------------------------------- tiles */

test("the seat counts show as tiles, the ones wanting attention marked", async () => {
  await open();
  assert.deepEqual(tiles(), [
    ["4 assigned", false], ["2 active in the last 7 days", false], ["1 on hold", false],
    ["2 invited", false], ["1 removed, device not told yet", true], ["0 seats ending within 30 days", false],
  ]);
  assert.deepEqual(sent(/summary/), [`GET ${L1}/summary`]);
});

test("counts that fail to load say so, and the roster stays", async () => {
  await open({ routes: { [`GET ${L1}/summary`]: () => json(500, { detail: "boom" }) } });
  assert.equal(text($("tiles")), "Could not count seats: boom");
  assert.equal(tableRows("rows").length, 2);
});

test("counts for a licence no longer shown are dropped, and so is its failure", async () => {
  for (const late of [json(200, { counts }), json(500, { detail: "late" })]) {
    reset();
    const slow = deferred();
    await open({ routes: { [`GET ${L1}/summary`]: () => slow.promise } });
    await openLicence("L2");
    assert.equal(tiles()[0][0], "9 assigned");
    slow.resolve(late);
    await settle();
    assert.equal(tiles()[0][0], "9 assigned");
  }
});

test("tiles read missing counts as none", () => {
  assert.match(tilesHtml(undefined), /^<div class="tile"><strong>0<\/strong> <span>assigned<\/span>/);
});

/* --------------------------------------------------------------- export */

test("Export roster CSV saves the roster under the licence's key prefix", async () => {
  await open({ routes: { [`GET ${L1}/seats/export`]: () => new Response("email\r\nana@uni.edu\r\n", { status: 200 }) } });
  const saved = await downloads(async () => {
    $("exportRoster").click();
    assert.equal($("exportRoster").disabled, true, "one export at a time");
    await settle();
  });
  assert.equal(saved.length, 1);
  assert.equal(saved[0].name, "roster-SEMP-UNI1.csv");
  assert.equal(await saved[0].blob.text(), "email\r\nana@uni.edu\r\n");
  assert.ok(saved[0].el.removed);
  assert.deepEqual(status(), ["Roster exported.", "muted"]);
  assert.equal($("exportRoster").disabled, false);
});

test("a refused export is explained", async () => {
  await open({ routes: { [`GET ${L1}/seats/export`]: () => json(429, { detail: "rate_limited" }) } });
  $("exportRoster").click();
  await settle();
  assert.deepEqual(status(), ["Too many requests just now — wait a moment and try again.", "muted err"]);
  reset();
  await open({ routes: { [`GET ${L1}/seats/export`]: () => json(500, { detail: "boom" }) } });
  $("exportRoster").click();
  await settle();
  assert.deepEqual(status(), ["Could not export: boom", "muted err"]);
});

test("with no licence open the tools send nothing", async () => {
  await open({ search: "" });
  for (const id of ["exportRoster", "auditLoad", "auditMore", "auditCsv", "auditJson"]) {
    $(id).disabled = false;
    $(id).click();
  }
  $("importText").value = "a@uni.edu";
  $("importText").dispatch("input");
  $("importSubmit").click();
  await settle();
  assert.deepEqual(sent(/L1/), []);
});

/* --------------------------------------------------------------- import */

function typeImport(value) {
  $("importText").value = value;
  $("importText").dispatch("input");
}

test("the import says how many rows it holds before it is sent", async () => {
  await open();
  assert.equal($("importCard").hidden, false);
  assert.equal($("importSubmit").disabled, true, "nothing to send yet");
  typeImport("email\nana@uni.edu\n\nben@uni.edu\n");
  assert.deepEqual([$("importPreview").textContent, $("importSubmit").disabled], ["2 rows ready", false]);
  typeImport("ana@uni.edu");
  assert.equal($("importPreview").textContent, "1 row ready");
  typeImport("email\n");
  assert.deepEqual([$("importPreview").textContent, $("importSubmit").disabled], ["", true]);
  typeImport(Array.from({ length: MAX_IMPORT_ROWS + 1 }, (_, i) => `m${i}@uni.edu`).join("\n"));
  assert.deepEqual([$("importPreview").textContent, $("importPreview").className, $("importSubmit").disabled],
    ["501 rows — at most 500 at a time", "err", true]);
});

test("a chosen file fills the import", async () => {
  await open();
  $("importFile").files = [];
  $("importFile").dispatch("change");
  await settle();
  assert.equal($("importText").value, "");
  $("importFile").files = [new Blob(["email\nana@uni.edu\n"])];
  $("importFile").dispatch("change");
  await settle();
  assert.equal($("importText").value, "email\nana@uni.edu\n");
  assert.equal($("importPreview").textContent, "1 row ready");
});

const imported = {
  rows: 5,
  counts: { seated: 1, invited: 1, exists: 1, duplicate: 1, invalid: 1, refused: 0, unprocessed: 0 },
  results: [
    { row: 2, email: "new@uni.edu", outcome: "seated", error: "" },
    { row: 3, email: "later@uni.edu", outcome: "invited", error: "" },
    { row: 4, email: "ana@uni.edu", outcome: "exists", error: "" },
    { row: 5, email: "new@uni.edu", outcome: "duplicate", error: "row 2" },
    { row: 6, email: "", outcome: "invalid", error: "invalid_email" },
  ],
};

test("an import sends the file, lists every row's result and reloads the roster", async () => {
  await open({ routes: { [`POST ${L1}/seats/import`]: () => json(200, imported) } });
  const file = "email\nnew@uni.edu\nlater@uni.edu\nana@uni.edu\nnew@uni.edu\n,\n";
  typeImport(file);
  $("importSubmit").click();
  assert.equal($("importSubmit").disabled, true, "one import at a time");
  await settle();
  const post = net.requests.find((r) => r.method === "POST");
  assert.deepEqual(JSON.parse(post.body), { csv: file });
  assert.equal($("importSummary").textContent,
    "1 added, 1 invited, 1 already on the roster, 1 repeated, 1 not added.");
  assert.equal($("importResults").hidden, false);
  assert.deepEqual(tableRows("importRows"), [
    ["2", "new@uni.edu", "on the licence now"],
    ["3", "later@uni.edu", "invited — joins at first sign-in"],
    ["4", "ana@uni.edu", "already on the roster"],
    ["5", "new@uni.edu", "repeats row 2"],
    ["6", "—", "not an email address"],
  ]);
  assert.deepEqual($("importRows").querySelectorAll("tr.err").length, 2);
  assert.deepEqual(sent(/seats|summary/), [
    `GET ${L1}/seats`, `GET ${L1}/summary`, `POST ${L1}/seats/import`, `GET ${L1}/seats`, `GET ${L1}/summary`,
  ]);
  assert.equal($("importSubmit").disabled, false, "the same file may be sent again");
});

test("an import that added nobody leaves the roster as it is", async () => {
  await open({ routes: { [`POST ${L1}/seats/import`]: () => json(200, {
    rows: 1, counts: { exists: 1 }, results: [{ row: 1, email: "ana@uni.edu", outcome: "exists", error: "" }],
  }) } });
  typeImport("ana@uni.edu");
  $("importSubmit").click();
  await settle();
  assert.equal($("importSummary").textContent, "1 already on the roster.");
  assert.deepEqual(sent(/GET .*seats$/), [`GET ${L1}/seats`]);
});

test("a refused import is explained", async () => {
  const cases = [
    ["roster_import_too_many_rows", 413, "That is more than 500 rows. Split the file and import each part."],
    ["roster_import_malformed", 400, "That file could not be read as CSV — check its quotes."],
    ["roster_import_empty", 400, "There are no addresses to import."],
    ["roster_import_no_email_column", 400, "The first line looks like a header but has no “email” column."],
    ["odd", 500, "Could not import: odd"],
  ];
  for (const [code, httpStatus, sentence] of cases) {
    reset();
    await open({ routes: { [`POST ${L1}/seats/import`]: () => json(httpStatus, { detail: code }) } });
    typeImport("ana@uni.edu");
    $("importSubmit").click();
    await settle();
    assert.deepEqual(status(), [sentence, "muted err"], code);
    assert.equal($("importResults").hidden, true);
  }
});

test("every import outcome reads as a sentence", () => {
  const said = (outcome, error = "") => rowResult({ outcome, error });
  assert.equal(said("duplicate"), "repeats an earlier row");
  assert.equal(said("refused", "license_seats_exhausted"), "no seats left on this licence");
  assert.equal(said("refused", "member_already_licensed"),
    "already has a Semper licence of their own — Semper support can move them");
  assert.equal(said("refused", "strange"), "not added: strange");
  assert.equal(said("unprocessed", "roster_row_unprocessed"), "not reached — import the file again to finish");
  assert.equal(said("novel"), "novel");
  assert.equal(importSummary({ refused: 2, unprocessed: 3 }), "2 not added, 3 not reached.");
  assert.equal(importSummary(), "Nothing to import.");
});

test("another licence opened clears the last import's results; one not yours hides the tools", async () => {
  await open({ routes: { [`POST ${L1}/seats/import`]: () => json(200, imported) } });
  typeImport("new@uni.edu");
  $("importSubmit").click();
  await settle();
  assert.equal($("importResults").hidden, false);
  await openLicence("L2");
  assert.equal($("importResults").hidden, true);
  assert.equal($("importSummary").textContent, "");
  assert.equal($("importRows").innerHTML, "");

  net.routes[`GET ${L1}/seats`] = () => json(404, { detail: "license_not_found" });
  await openLicence("L1");
  assert.equal($("importCard").hidden, true);
  assert.equal($("auditCard").hidden, true);
  assert.equal($("tiles").innerHTML, "");
});

/* ------------------------------------------------------------ audit log */

const events = [
  { id: "e3", ts: "2026-10-02T09:00:00Z", uid: "it-1", action: "INSTITUTION_SEAT_PATCH",
    target: { type: "seat", id: "L1/u2" }, detail: { enabled: false, clearDeviceLock: false, note: "", extra: { a: 1 } } },
  { id: "e2", ts: "2026-10-01T09:00:00Z", uid: "u1", action: "LICENSE_DEVICE_BIND",
    target: { type: "license", id: "L1" }, detail: {} },
];

test("Show reads the log for the dates chosen and names members by address", async () => {
  const query = "since=2026-10-01&until=2026-10-02&page_size=50";
  await open({ routes: {
    [`GET ${L1}/audit?${query}`]: () => json(200, { events, page: { nextPageToken: "e2" } }),
    [`GET ${L1}/audit?${query}&page_token=e2`]: () => json(200, {
      events: [{ id: "e1", ts: "", uid: "", action: "INSTITUTION_ROSTER_IMPORT", target: {}, detail: { rows: 3 } }],
      page: { nextPageToken: null },
    }),
  } });
  assert.equal($("auditCard").hidden, false);
  assert.equal(text($("auditRows")), "Choose dates, or none for everything, and press Show.");
  assert.deepEqual(sent(/audit/), [], "read on demand only");
  $("auditSince").value = "2026-10-01";
  $("auditUntil").value = "2026-10-02";
  $("auditLoad").click();
  await settle();
  const rows = tableRows("auditRows");
  assert.equal(rows.length, 2);
  assert.deepEqual(rows[0].slice(1), [
    "INSTITUTION_SEAT_PATCH", "it-1", "seat ben@uni.edu", 'enabled: false, clearDeviceLock: false, extra: {"a":1}',
  ]);
  assert.deepEqual(rows[1].slice(1, 4), ["LICENSE_DEVICE_BIND", "ana@uni.edu", "license"]);
  assert.equal($("auditMore").hidden, false);
  assert.deepEqual(status(), ["", "muted"]);

  $("auditMore").click();
  await settle();
  assert.deepEqual(tableRows("auditRows").map((r) => r[1]),
    ["INSTITUTION_SEAT_PATCH", "LICENSE_DEVICE_BIND", "INSTITUTION_ROSTER_IMPORT"]);
  assert.deepEqual(tableRows("auditRows")[2], ["—", "INSTITUTION_ROSTER_IMPORT", "—", "", "rows: 3"]);
  assert.equal($("auditMore").hidden, true);
});

test("an empty range says so, and a refused one is explained", async () => {
  await open({ routes: { [`GET ${L1}/audit?page_size=50`]: () => json(200, { events: [] }) } });
  $("auditLoad").click();
  await settle();
  assert.equal(text($("auditRows")), "Nothing recorded in that range.");

  net.routes[`GET ${L1}/audit?page_size=50`] = () => json(400, { detail: "invalid_audit_range" });
  $("auditLoad").click();
  await settle();
  assert.deepEqual(status(), ["Those dates are not a range: the start must come before the end.", "muted err"]);
  net.routes[`GET ${L1}/audit?page_size=50`] = () => json(500, { detail: "boom" });
  $("auditLoad").click();
  await settle();
  assert.deepEqual(status(), ["Could not load the audit log: boom", "muted err"]);
  assert.equal($("auditLoad").disabled, false);
});

test("a log page for a licence no longer shown is dropped", async () => {
  for (const late of [json(200, { events }), json(500, { detail: "late" })]) {
    reset();
    const slow = deferred();
    await open({ routes: { [`GET ${L1}/audit?page_size=50`]: () => slow.promise } });
    $("auditLoad").click();
    await settle();
    await openLicence("L2");
    slow.resolve(late);
    await settle();
    assert.equal(text($("auditRows")), "Choose dates, or none for everything, and press Show.");
    assert.equal($("auditLoad").disabled, false);
  }
});

test("the log exports as CSV and as JSON", async () => {
  await open({ routes: {
    [`GET ${L1}/audit?since=2026-10-01&format=csv&page_size=5000`]: () =>
      new Response("ts,action\r\n", { status: 200 }),
    [`GET ${L1}/audit?since=2026-10-01&page_size=5000`]: () =>
      json(200, { licenseId: "L1", events, page: { hasMore: false } }),
  } });
  $("auditSince").value = "2026-10-01";
  const saved = await downloads(async () => {
    $("auditCsv").click();
    await settle();
    assert.deepEqual(status(), ["Audit log exported.", "muted"]);
    $("auditJson").click();
    await settle();
  });
  assert.deepEqual(saved.map((s) => s.name), ["audit-SEMP-UNI1.csv", "audit-SEMP-UNI1.json"]);
  assert.equal(await saved[0].blob.text(), "ts,action\r\n");
  assert.deepEqual(JSON.parse(await saved[1].blob.text()),
    { licenseId: "L1", since: "2026-10-01", until: "", events });
  assert.deepEqual(status(), ["Audit log exported.", "muted"]);
});

test("a JSON export that hit the ceiling says so; a refused export is explained", async () => {
  await open({ routes: {
    [`GET ${L1}/audit?page_size=5000`]: () => json(200, { licenseId: "L1", events, page: { hasMore: true } }),
    [`GET ${L1}/audit?format=csv&page_size=5000`]: () => json(400, { detail: "invalid_audit_range" }),
  } });
  await downloads(async () => {
    $("auditJson").click();
    await settle();
  });
  assert.deepEqual(status(), ["Exported the newest 5,000 events — narrow the dates for the rest.", "muted"]);
  $("auditCsv").click();
  await settle();
  assert.deepEqual(status(), ["Those dates are not a range: the start must come before the end.", "muted err"]);
  net.routes[`GET ${L1}/audit?format=csv&page_size=5000`] = () => json(500, { detail: "boom" });
  $("auditCsv").click();
  await settle();
  assert.deepEqual(status(), ["Could not export: boom", "muted err"]);
  assert.equal($("auditCsv").disabled, false);
});

test("an event row without a target or an actor still renders", () => {
  assert.match(auditRow({ action: "X" }), /<td>—<\/td>/);
});

/* -------------------------------------------------------------- helpers */

test("importRowCount counts rows as the backend does, header aside", () => {
  assert.equal(importRowCount(""), 0);
  assert.equal(importRowCount(null), 0);
  assert.equal(importRowCount("email\r\na@x.edu\r\n\r\nb@x.edu"), 2);
  assert.equal(importRowCount("a@x.edu\nb@x.edu"), 2);
  assert.equal(importRowCount("email"), 0);
});

test("auditQuery sends only what is set", () => {
  assert.equal(auditQuery(), "");
  assert.equal(auditQuery({ since: "2026-10-01" }, { format: "csv", page_token: "", page_size: 5 }),
    "?since=2026-10-01&format=csv&page_size=5");
  assert.equal(auditQuery({ until: "2026-10-02" }, { page_token: null }), "?until=2026-10-02");
});

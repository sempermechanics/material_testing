// The consoles as a browser meets them, with nothing real behind them, for
// console.a11y.spec.mjs (not a test file itself).
//
// - `serveSite()` serves firebase-hosting/public as Hosting would (a folder
//   answers with its index.html) plus /__/firebase/init.json, on 127.0.0.1.
// - `stubContext(context, persona)` answers everything else the pages ask
//   for: the two Firebase SDK modules from gstatic are the test fakes
//   (tests/fakes/), signed in as `persona` with its second factor done; the
//   API (config.js keeps its `__API_BASE_URL__` placeholder, so calls go to
//   `<page>/__API_BASE_URL__/v1/…`) answers from FIXTURES; any other host is
//   refused, so the run never reaches the network.
import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

const PUBLIC = fileURLToPath(new URL("../../public/", import.meta.url));
const FAKES = fileURLToPath(new URL("../fakes/", import.meta.url));
const TYPES = {
  ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".mjs": "text/javascript",
  ".css": "text/css", ".json": "application/json", ".webp": "image/webp", ".svg": "image/svg+xml",
};
const INIT_JSON = { apiKey: "fake-api-key", projectId: "fake-project", authDomain: "127.0.0.1" };

/** Serve public/ on an ephemeral port. Resolves { origin, close }. */
export async function serveSite() {
  const server = createServer(async (req, res) => {
    const path = decodeURIComponent(new URL(req.url, "http://x").pathname);
    if (path === "/__/firebase/init.json") {
      res.writeHead(200, { "Content-Type": TYPES[".json"] });
      res.end(JSON.stringify(INIT_JSON));
      return;
    }
    let file = normalize(join(PUBLIC, path));
    if (!file.startsWith(normalize(PUBLIC))) {
      res.writeHead(403).end();
      return;
    }
    try {
      if ((await stat(file)).isDirectory()) file = join(file, "index.html");
      const body = await readFile(file);
      res.writeHead(200, { "Content-Type": TYPES[extname(file)] || "application/octet-stream" });
      res.end(body);
    } catch {
      res.writeHead(404, { "Content-Type": "text/plain" }).end("not found");
    }
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  return { origin: `http://127.0.0.1:${port}`, close: () => new Promise((r) => server.close(r)) };
}

const day = (n) => new Date(Date.now() + n * 86400e3).toISOString().slice(0, 10) + "T23:59:59Z";

const IND = {
  id: "lic-individual-1", keyPrefix: "SEMP-IND1", kind: "individual", mode: "licensed",
  status: "redeemed", duration: "timed", expiresAt: day(200), graceDays: 14, maxAnalyses: 50,
  emailLock: "pat@lab.org", note: "Prof. Chen, PO 4471", app: "semper", maxDevices: 2, devicesActive: 1,
};
const UNI = {
  id: "L1", keyPrefix: "SEMP-UNI1", kind: "institution", mode: "licensed", status: "redeemed",
  duration: "timed", expiresAt: day(24), seating: "assigned", maxSeats: 150, seatsUsed: 120,
  domainLock: "uni.edu", adminEmails: ["it@uni.edu"], app: "semperclassroom", maxDevices: 150,
  devicesActive: 2,
};
const DEMO = {
  id: "lic-demo-1", keyPrefix: "SEMP-DEMO", kind: "individual", mode: "demo", status: "redeemed",
  duration: "perpetual", createdByUid: "system", emailLock: "d@x.org",
};

/**
 * 120 seats (some paused, some removed, every other one on a phone that holds
 * its lease) and two invites: Show more is drawn, and a row has the longest
 * cells the roster shows (a lease's date and time, a device id, three buttons).
 */
const SEATS = Array.from({ length: 120 }, (_, i) => ({
  uid: `u${i}`,
  email: `member${String(i).padStart(3, "0")}@uni.edu`,
  status: i % 17 === 3 ? "disabled" : i % 23 === 5 ? "revoked" : "active",
  ...(i % 2 ? { deviceIdLock: `device-${i}-abcdef0123`, leaseExpiresAt: day(5) } : {}),
}));
const ROSTER = {
  license: UNI, seats: SEATS, invites: [{ id: "i1", email: "new@uni.edu" }, { id: "i2", email: "later@uni.edu" }],
};
const SUMMARY = { counts: {
  assigned: 120, activeLast7Days: 60, onHold: 7, invited: 2, revocationPending: 1, expiringWithin30Days: 120,
} };

/** The pool's phones, as IT and staff list them (ADR-033). */
const POOL_PHONES = {
  licenseId: "L1", app: "semperclassroom", devicesActive: 2, maxDevices: 150, pool: true, model: "activation",
  devices: [
    { deviceId: "and-phone-1", uid: "u1", app: "semperclassroom", status: "active", activatedAt: day(-3),
      activatedBy: "self", deviceModel: "Pixel 6", email: "member001@uni.edu" },
    { deviceId: "and-phone-3", uid: "u3", app: "semperclassroom", status: "active", activatedAt: day(-1),
      activatedBy: "it", deviceModel: "Galaxy S22+", email: "member003@uni.edu" },
  ],
};

/** The holder's own phones for one app: one activated, one on Demo. */
const MY_PHONES = {
  app: "semper", licenseId: "lic-individual-1", devicesActive: 1, maxDevices: 2, pool: false, model: "activation",
  devices: [
    { deviceId: "and-mine-1", model: "Pixel 6", registeredAt: day(-30), activated: true, thisDevice: false,
      activatedAt: day(-30), activatedBy: "self" },
    { deviceId: "and-mine-2", model: "Galaxy S22+", registeredAt: day(-2), activated: false, thisDevice: false },
  ],
};

/** The operator desk's Test accounts list: one account on two phones, one on none. */
const TEST_ACCOUNTS = { accounts: [
  {
    uid: "uid-qc-1", email: "qc-1@test.example", purpose: "qc", expiresAt: day(21), maxDevices: 3,
    note: "QC rack", activeDevices: 2, status: "active", devices: [
      { deviceId: "and-phone-1", app: "semper", model: "Pixel 6" },
      { deviceId: "and-phone-2", app: "semperclassroom", model: "Galaxy S22+" },
    ],
  },
  {
    uid: "uid-ci-1", email: "ci@test.example", purpose: "ci", expiresAt: null, maxDevices: 10,
    note: "", activeDevices: 0, status: "active",
  },
] };

/** Who is signed in, and what the API answers them. */
export const PERSONAS = {
  out: null,
  staff: {
    uid: "staff-1", email: "staff@sempermechanics.com",
    me: { role: "admin", email: "staff@sempermechanics.com", license: { mode: "demo", held: false } },
    administered: [UNI],
  },
  it: {
    uid: "it-1", email: "it@uni.edu",
    me: { role: "user", email: "it@uni.edu", license: { mode: "licensed", held: true, kind: "institution" } },
    administered: [UNI],
  },
  holder: {
    uid: "holder-1", email: "pat@lab.org",
    me: { role: "user", email: "pat@lab.org", license: {
      mode: "licensed", held: true, kind: "individual", keyPrefix: "SEMP-IND1", expiresAt: day(200),
      graceEndsAt: day(214),
    }, licences: {
      semper: { mode: "licensed", kind: "individual", prefix: "SEMP-IND1", expiresAt: day(200), inGrace: false,
        seating: "", held: true, pendingLicence: { licenseId: "lic-new-1", prefix: "SEMP-NEW1", kind: "individual",
          expiresAt: day(565), maxDevices: 3, supersedes: "lic-individual-1" } },
      semperclassroom: { mode: "demo", kind: "", prefix: "", expiresAt: null, inGrace: false, seating: "",
        held: false, pendingLicence: null },
    } },
    administered: [],
  },
  demo: {
    uid: "demo-1", email: "sam@gmail.test",
    me: { role: "user", email: "sam@gmail.test", license: { mode: "demo", held: false } },
    administered: [],
  },
};

/** The API's answer to `method path` for `persona`: [status, body]. */
function answer(persona, method, path) {
  if (!persona) return [401, { detail: "not_signed_in" }];
  const routes = {
    "GET /v1/me": () => persona.me,
    "GET /v1/institutions/licenses": () => ({ licenses: persona.administered }),
    "GET /v1/institutions/licenses/L1/seats": () => ROSTER,
    "GET /v1/institutions/licenses/L1/summary": () => SUMMARY,
    "GET /v1/institutions/licenses/L1/devices": () => POOL_PHONES,
    "GET /v1/me/devices": () => MY_PHONES,
    "GET /v1/institutions/licenses/L1/audit": () => ({
      events: [{ at: day(-1), action: "seat.add", actorEmail: "it@uni.edu", targetEmail: "new@uni.edu" }],
      page: {}, licenseId: "L1",
    }),
    "POST /v1/institutions/licenses/L1/seats/import": () => ({
      rows: 1, counts: { exists: 1 }, results: [{ row: 1, email: "member000@uni.edu", outcome: "exists", error: "" }],
    }),
    "GET /v1/admin/licenses": () => ({ licenses: [IND, UNI, DEMO], page: {}, demoMaxAnalyses: 25 }),
    "GET /v1/admin/licenses/L1/seats": () => ROSTER,
    "GET /v1/admin/licenses/L1/summary": () => SUMMARY,
    "GET /v1/admin/licenses/L1/devices": () => POOL_PHONES,
    "GET /v1/admin/test-accounts": () => TEST_ACCOUNTS,
    "GET /v1/admin/users": () => ({ users: [{ uid: "w1", email: "wait@lab.org", displayName: "Wai Tan" }], page: {} }),
    "GET /v1/sessions": () => ({
      sessions: [{ sessionId: "s1", specimenName: "Steel 24", app: "semper", status: "COMPLETED",
        completedCount: 12, totalBytes: 48_000_000 }],
      quota: { used: 1, max: 25 },
    }),
  };
  const route = routes[`${method} ${path}`];
  return route ? [200, route()] : [404, { detail: "not_found" }];
}

/**
 * Answer, in `context`, every request a console page makes that is not for
 * the local site: the Firebase SDK fakes, the stub API, and a refusal for
 * anything else (fonts, Google's sign-in hosts).
 */
export async function stubContext(context, persona) {
  const fakeAuth = await readFile(join(FAKES, "firebase-auth.mjs"), "utf8");
  const fakeApp = await readFile(join(FAKES, "firebase-app.mjs"), "utf8");
  // The fake SDK starts signed out; a wrapper signs `persona` in, enrolled and
  // with this session's second factor, as every dashboard needs.
  const signedIn = `
    import * as sdk from "https://www.gstatic.com/a11y/fake-auth.mjs";
    export * from "https://www.gstatic.com/a11y/fake-auth.mjs";
    export function getAuth(app) {
      const auth = sdk.getAuth(app);
      const who = ${JSON.stringify(persona && { uid: persona.uid, email: persona.email })};
      if (who) auth.currentUser = new sdk.FakeUser({ ...who, factors: [{}], secondFactor: true });
      return auth;
    }`;
  const js = (body) => ({
    status: 200, body, headers: { "Content-Type": "text/javascript", "Access-Control-Allow-Origin": "*" },
  });
  await context.route("**/*", async (route) => {
    const url = new URL(route.request().url());
    if (url.hostname === "www.gstatic.com") {
      if (url.pathname.endsWith("/firebase-app.js")) return route.fulfill(js(fakeApp));
      if (url.pathname.endsWith("/firebase-auth.js")) return route.fulfill(js(signedIn));
      if (url.pathname === "/a11y/fake-auth.mjs") return route.fulfill(js(fakeAuth));
      return route.abort();
    }
    if (url.hostname !== "127.0.0.1") return route.abort();
    const api = url.pathname.indexOf("/__API_BASE_URL__/v1/");
    if (api < 0) return route.continue();
    const [status, body] = answer(persona, route.request().method(), url.pathname.slice(api + "/__API_BASE_URL__".length));
    return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
  });
}

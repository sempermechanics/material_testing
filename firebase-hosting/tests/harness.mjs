// Shared set-up for the console tests that load auth.js (not a test file: the
// CI glob is *.test.mjs).
//
// Importing this module, before auth.js is imported, does three things:
//  1. registers firebase-hooks.mjs, so auth.js's gstatic SDK imports load the
//     fakes under fakes/ (a test file must therefore import auth.js
//     dynamically, after this module has run — see loadAuth);
//  2. installs a small browser on globalThis: `window` (location,
//     sessionStorage, prompt, confirm, alert), `document` (fake-dom.mjs: the
//     real console page's markup, parsed), and `location`;
//  3. replaces `fetch` with a scripted one that refuses anything a test did
//     not queue, so no test reaches the network.
import { register } from "node:module";
import { test as nodeTest } from "node:test";
import { fake } from "./fakes/firebase-auth.mjs";
import { document as fakeDocument, mountPage } from "./fake-dom.mjs";

register("./firebase-hooks.mjs", import.meta.url);

/**
 * node:test's `test` with a timeout. A regression that leaves auth.js waiting
 * on a Google redirect that never comes would otherwise hang the file until
 * the CI job's own timeout instead of failing the one test.
 */
export const test = (name, fn) => nodeTest(name, { timeout: 5000 }, fn);

export { fake };
export const FakeUser = fake.FakeUser;

/* ------------------------------------------------------------------- DOM */

// The DOM itself is fake-dom.mjs: the real page markup, parsed.
export { FakeElement, mountPage, page, $ } from "./fake-dom.mjs";
globalThis.document = fakeDocument;

/* ------------------------------------------------------ window, storage */

export const storage = {
  map: new Map(),
  getItem(key) {
    return this.map.has(key) ? this.map.get(key) : null;
  },
  setItem(key, value) {
    this.map.set(key, String(value));
  },
  removeItem(key) {
    this.map.delete(key);
  },
  clear() {
    this.map.clear();
  },
};

/** Scripted answers for window.prompt, in order. An unscripted prompt fails the test. */
export const prompts = {
  asked: [],
  answers: [],
  answer(...values) {
    this.answers.push(...values);
  },
};

export const location = {
  host: "console.test",
  search: "",
  replaced: [],
  replace(href) {
    this.replaced.push(href);
  },
};

/** Scripted answers for window.confirm, in order; `alerts` records window.alert. */
export const confirms = {
  asked: [],
  answers: [],
  answer(...values) {
    this.answers.push(...values);
  },
};
export const alerts = [];

globalThis.window = {
  location,
  sessionStorage: storage,
  prompt(message) {
    prompts.asked.push(message);
    if (!prompts.answers.length) throw new Error(`unscripted prompt: ${message}`);
    return prompts.answers.shift();
  },
  confirm(message) {
    confirms.asked.push(message);
    if (!confirms.answers.length) throw new Error(`unscripted confirm: ${message}`);
    return confirms.answers.shift();
  },
  alert(message) {
    alerts.push(message);
  },
};
// The pages call confirm() bare as well as window.confirm().
globalThis.confirm = (message) => window.confirm(message);
globalThis.location = location;

/* ---------------------------------------------------------------- fetch */

export const INIT_JSON = { apiKey: "fake-api-key", projectId: "fake-project", authDomain: "fake-project.firebaseapp.com" };

/** Every API request auth.js made, with the headers and body it sent. */
export const net = {
  requests: [],
  queue: [],
  /** Queue responses (Response objects, or functions of the request) in order. */
  reply(...responses) {
    this.queue.push(...responses);
  },
  /**
   * Answer by path instead of order, for calls made in parallel. Keys are
   * "METHOD /v1/path?query", "/v1/path?query", "METHOD /v1/path" or
   * "/v1/path", most specific first; values are functions of the request
   * (a Response body can be read only once).
   */
  routes: null,
  /** Requests nothing was scripted for. A page test asserts this stays empty. */
  unexpected: [],
};

function routeFor(request) {
  if (!net.routes) return null;
  const path = request.url.slice(request.url.indexOf("/v1/"));
  const bare = path.split("?")[0];
  for (const key of [`${request.method} ${path}`, path, `${request.method} ${bare}`, bare]) {
    if (key in net.routes) return net.routes[key];
  }
  return null;
}

globalThis.fetch = async (url, init = {}) => {
  url = String(url);
  if (url === "/__/firebase/init.json") return Response.json(INIT_JSON);
  const request = { url, method: init.method || "GET", headers: { ...(init.headers || {}) }, body: init.body };
  net.requests.push(request);
  const next = routeFor(request) || net.queue.shift();
  if (!next) {
    net.unexpected.push(`${request.method} ${url}`);
    throw new Error(`unexpected request: ${request.method} ${url}`);
  }
  return typeof next === "function" ? next(request) : next;
};

/** A JSON response; `undefined` gives an empty body. */
export function json(status, body) {
  return new Response(body === undefined ? "" : JSON.stringify(body), { status });
}

/* ----------------------------------------------------------------- flow */

/** Let every pending promise chain run to rest. */
export async function settle() {
  for (let i = 0; i < 25; i += 1) await new Promise((r) => setImmediate(r));
}

/**
 * Whether `promise` is still unsettled once everything else has settled —
 * how a test sees "the page left for Google and this call never returns".
 */
export async function stillPending(promise) {
  let settled = false;
  promise.then(() => { settled = true; }, () => { settled = true; });
  await settle();
  return !settled;
}

/**
 * The rejection of `promise`; a failure if it resolves, or if it is still
 * pending once everything has settled (e.g. it left for Google instead).
 */
export async function rejection(promise) {
  const pending = Symbol("pending");
  let outcome;
  try {
    outcome = await Promise.race([promise, settle().then(() => pending)]);
  } catch (e) {
    return e;
  }
  throw new Error(outcome === pending
    ? "expected a rejection; the call is still pending (did it leave for Google?)"
    : "expected a rejection; the call resolved");
}

/** Everything back to a signed-out, freshly loaded login page. */
export function reset() {
  fake.reset();
  storage.clear();
  prompts.asked = [];
  prompts.answers = [];
  confirms.asked = [];
  confirms.answers = [];
  alerts.length = 0;
  net.requests = [];
  net.unexpected = [];
  net.queue = [];
  net.routes = null;
  location.search = "";
  location.replaced = [];
  mountPage("index.html");
}

reset();

/** auth.js, loaded through the hooks above. One instance per test file (process). */
export function loadAuth() {
  return import("../public/console/auth.js");
}

/** An enrolled, second-factor session: the state every dashboard needs. */
export function readyUser(options = {}) {
  return new FakeUser({ factors: [{}], secondFactor: true, ...options });
}

let pageLoads = 0;

/**
 * Open a console page as a signed-in browser would: mount its markup, sign
 * `user` in, script the API by `routes`, and import a fresh copy of its module
 * (`?load=N`, so its module state starts empty; auth.js stays shared). With
 * `resume`, this load is the return leg of a Google re-authentication that
 * stashed it; `redirect` is what that leg's getRedirectResult yields
 * (default: the signed-in user; null for a leg that came back without one).
 * Resolves once the page has settled.
 */
export async function openPage(dir, { user = readyUser(), routes = {}, search = "", resume = null, redirect } = {}) {
  mountPage(`${dir}/index.html`);
  location.search = search;
  net.routes = routes;
  fake.auth.currentUser = user;
  if (resume) {
    storage.setItem("semper.afterReauth", "Re-authenticated.");
    storage.setItem("semper.resume", JSON.stringify(resume));
    fake.redirectResult = redirect === undefined ? { user } : redirect;
  }
  pageLoads += 1;
  await import(`../public/console/${dir}/${dir}.js?load=${pageLoads}`);
  await settle();
}

/** The requests made, as "METHOD /v1/path" lines, optionally only those matching `pattern`. */
export function sent(pattern = /./) {
  return net.requests
    .map((r) => `${r.method} ${r.url.slice(r.url.indexOf("/v1/"))}`)
    .filter((line) => pattern.test(line));
}

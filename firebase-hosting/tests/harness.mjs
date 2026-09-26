// Shared set-up for the console tests that load auth.js (not a test file: the
// CI glob is *.test.mjs).
//
// Importing this module, before auth.js is imported, does three things:
//  1. registers firebase-hooks.mjs, so auth.js's gstatic SDK imports load the
//     fakes under fakes/ (a test file must therefore import auth.js
//     dynamically, after this module has run — see loadAuth);
//  2. installs a small browser on globalThis: `window` (location,
//     sessionStorage, prompt), `document` with an element for every id in the
//     real console page, and `location`;
//  3. replaces `fetch` with a scripted one that refuses anything a test did
//     not queue, so no test reaches the network.
import { register } from "node:module";
import { readFileSync } from "node:fs";
import { test as nodeTest } from "node:test";
import { fake } from "./fakes/firebase-auth.mjs";

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

/**
 * Just enough of an element for auth.js and router.js. `querySelector` on an
 * element whose innerHTML was set returns one stable child per selector, and
 * throws when the markup does not contain that class or tag — a renamed class
 * in auth.js's enrolment card fails the test instead of passing on a phantom.
 */
export class FakeElement {
  constructor(tag, { id = null, hidden = false } = {}) {
    this.tagName = tag.toUpperCase();
    this.id = id;
    this.hidden = hidden;
    this.textContent = "";
    this.className = "";
    this.value = "";
    this.disabled = false;
    this.focused = false;
    this.removed = false;
    this.html = "";
    this.parts = new Map();
    this.listeners = {};
  }

  set innerHTML(html) {
    this.html = String(html);
    this.parts.clear();
  }

  get innerHTML() {
    return this.html;
  }

  querySelector(selector) {
    const present = selector.startsWith(".")
      ? new RegExp(`class="[^"]*\\b${selector.slice(1)}\\b`).test(this.html)
      : new RegExp(`<${selector}\\b`).test(this.html);
    if (!present) throw new Error(`fake DOM: ${selector} is not in this element's markup`);
    if (!this.parts.has(selector)) this.parts.set(selector, new FakeElement("part"));
    return this.parts.get(selector);
  }

  addEventListener(type, listener) {
    (this.listeners[type] ||= []).push(listener);
  }

  dispatch(type, event = {}) {
    for (const listener of this.listeners[type] || []) listener(event);
  }

  click() {
    if (!this.disabled) this.dispatch("click");
  }

  focus() {
    this.focused = true;
  }

  insertAdjacentElement(position, element) {
    element.anchor = this;
    element.position = position;
    page.inserted.push(element);
    return element;
  }

  remove() {
    this.removed = true;
    page.inserted = page.inserted.filter((e) => e !== this);
  }
}

const CONSOLE = new URL("../public/console/", import.meta.url);

export let page = null;

/**
 * Mount a fresh copy of a real console page: one element per `id=` in its
 * markup, `hidden` as the markup has it, plus `<main>`. Built from the file so
 * the ids a test relies on are the ids the page really has.
 */
export function mountPage(file = "index.html") {
  const html = readFileSync(new URL(file, CONSOLE), "utf8");
  const byId = new Map();
  for (const m of html.matchAll(/<(\w+)\b([^>]*)>/g)) {
    const id = /\bid="([^"]+)"/.exec(m[2]);
    if (id) byId.set(id[1], new FakeElement(m[1], { id: id[1], hidden: /\shidden(?=\s|\/|$)/.test(m[2]) }));
  }
  page = { byId, main: new FakeElement("main"), inserted: [] };
  return page;
}

export const $ = (id) => page.byId.get(id);

globalThis.document = {
  getElementById: (id) => page.byId.get(id) || null,
  querySelector: (selector) => {
    if (selector === "main") return page.main;
    throw new Error(`fake DOM: document.querySelector(${selector}) not modelled`);
  },
  createElement: (tag) => new FakeElement(tag),
};

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

globalThis.window = {
  location,
  sessionStorage: storage,
  prompt(message) {
    prompts.asked.push(message);
    if (!prompts.answers.length) throw new Error(`unscripted prompt: ${message}`);
    return prompts.answers.shift();
  },
};
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
  /** Answer by path instead of order, for calls made in parallel: { "/v1/me": () => Response }. */
  routes: null,
};

globalThis.fetch = async (url, init = {}) => {
  url = String(url);
  if (url === "/__/firebase/init.json") return Response.json(INIT_JSON);
  const request = { url, method: init.method || "GET", headers: { ...(init.headers || {}) }, body: init.body };
  net.requests.push(request);
  const route = net.routes && Object.keys(net.routes).find((path) => url.endsWith(path));
  const next = route ? net.routes[route] : net.queue.shift();
  if (!next) throw new Error(`unexpected request: ${request.method} ${url}`);
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
  net.requests = [];
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

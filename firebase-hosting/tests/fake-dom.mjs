// A small DOM for the console tests: enough of `document` and elements for
// auth.js, router.js and the three page modules, built by parsing the real
// page markup and whatever the modules write into innerHTML. Imported by
// harness.mjs, which installs `document` on globalThis.
//
// Modelled: a tree of elements with attributes, id, class, hidden, disabled,
// checked, value (a <select> takes its selected option's), dataset,
// textContent; innerHTML (parsed); querySelector(All) / matches / closest for
// simple selectors (`tag`, `.class`, `#id`, `[attr]`, `[attr="v"]`,
// `:checked`, compounded, no combinators); listeners with click bubbling;
// insertAdjacentElement, appendChild, remove; <dialog> showModal / close;
// `cells` on a row. An unmodelled selector throws rather than matching
// nothing, so a test cannot pass on a query the fake did not understand.
import { readFileSync } from "node:fs";

const VOID = new Set(["area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"]);
const TAG = /<!--[\s\S]*?-->|<!doctype[^>]*>|<(\/?)([a-zA-Z][\w-]*)((?:\s+[^\s=>/]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+))?)*)\s*(\/?)>/gi;
const ATTR = /([^\s=>/]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g;
const ENTITIES = { amp: "&", lt: "<", gt: ">", quot: '"', "#39": "'", nbsp: " ", copy: "©" };
const SIMPLE = /^([a-z][\w-]*)?((?:[.#][\w-]+)*)((?:\[[^\]]+\])*)(:checked)?$/i;

const decode = (text) => text.replace(/&(amp|lt|gt|quot|#39|nbsp|copy);/g, (_, e) => ENTITIES[e]);
const camel = (name) => name.replace(/-([a-z])/g, (_, c) => c.toUpperCase());

export let page = null;

export class FakeElement {
  constructor(tag, attributes = {}) {
    this.localName = tag.toLowerCase();
    this.tagName = tag.toUpperCase();
    this.attributes = { ...attributes };
    this.id = attributes.id ?? "";
    this.className = attributes.class ?? "";
    this.hidden = "hidden" in attributes;
    this.disabled = "disabled" in attributes;
    this.checked = "checked" in attributes;
    this.value = attributes.value ?? "";
    this.name = attributes.name ?? "";
    this.title = attributes.title ?? "";
    this.dataset = {};
    for (const [key, value] of Object.entries(attributes)) {
      if (key.startsWith("data-")) this.dataset[camel(key.slice(5))] = value;
    }
    this.childNodes = [];
    this.parentNode = null;
    this.listeners = {};
    this.html = "";
    this.open = false;
    this.focused = false;
    this.removed = false;
  }

  get children() {
    return this.childNodes.filter((n) => n instanceof FakeElement);
  }

  get cells() {
    return this.children.filter((n) => n.localName === "td" || n.localName === "th");
  }

  get classList() {
    return this.className.split(/\s+/).filter(Boolean);
  }

  getAttribute(name) {
    return name in this.attributes ? this.attributes[name] : null;
  }

  get textContent() {
    return this.childNodes.map((n) => (typeof n === "string" ? n : n.textContent)).join("");
  }

  set textContent(text) {
    this.childNodes = [String(text ?? "")];
  }

  get innerHTML() {
    return this.html;
  }

  set innerHTML(html) {
    this.html = String(html);
    this.childNodes = [];
    parseInto(this, this.html);
  }

  *descendants() {
    for (const child of this.children) {
      yield child;
      yield* child.descendants();
    }
  }

  matches(selector) {
    const m = SIMPLE.exec(selector.trim());
    if (!m) throw new Error(`fake DOM: selector ${selector} not modelled`);
    if (m[1] && m[1].toLowerCase() !== this.localName) return false;
    for (const part of m[2].match(/[.#][\w-]+/g) || []) {
      const name = part.slice(1);
      if (part[0] === "." ? !this.classList.includes(name) : this.id !== name) return false;
    }
    for (const test of m[3].match(/\[[^\]]+\]/g) || []) {
      const [, name, want] = /^\[([\w-]+)(?:="([^"]*)")?\]$/.exec(test) || [];
      if (!name) throw new Error(`fake DOM: attribute test ${test} not modelled`);
      const have = this.getAttribute(name);
      if (have == null || (want !== undefined && have !== want)) return false;
    }
    return !m[4] || this.checked;
  }

  querySelectorAll(selector) {
    return [...this.descendants()].filter((e) => e.matches(selector));
  }

  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }

  closest(selector) {
    for (let e = this; e instanceof FakeElement; e = e.parentNode) {
      if (e.matches(selector)) return e;
    }
    return null;
  }

  addEventListener(type, listener) {
    (this.listeners[type] ||= []).push(listener);
  }

  /** Fire `type` at this element only. */
  dispatch(type, event = {}) {
    const ev = { type, target: this, defaultPrevented: false, ...event };
    ev.preventDefault ||= () => { ev.defaultPrevented = true; };
    for (const listener of this.listeners[type] || []) listener(ev);
    return ev;
  }

  /** A user's click: nothing on a disabled control, else bubbles to the root. */
  click() {
    if (this.disabled) return;
    const ev = { type: "click", target: this, preventDefault() {} };
    for (let e = this; e instanceof FakeElement; e = e.parentNode) e.dispatch("click", ev);
  }

  focus() {
    this.focused = true;
  }

  appendChild(element) {
    element.parentNode = this;
    this.childNodes.push(element);
    return element;
  }

  insertAdjacentElement(position, element) {
    if (position !== "afterend") throw new Error(`fake DOM: insertAdjacentElement(${position})`);
    const parent = this.parentNode;
    element.parentNode = parent;
    parent.childNodes.splice(parent.childNodes.indexOf(this) + 1, 0, element);
    element.anchor = this;
    element.position = position;
    page.inserted.push(element);
    return element;
  }

  remove() {
    this.removed = true;
    if (this.parentNode) {
      this.parentNode.childNodes = this.parentNode.childNodes.filter((n) => n !== this);
    }
    page.inserted = page.inserted.filter((e) => e !== this);
  }

  showModal() {
    this.open = true;
  }

  close() {
    this.open = false;
  }
}

/** Parse `html` into children of `host`. */
function parseInto(host, html) {
  const stack = [host];
  let last = 0;
  const text = (s) => { if (s) stack.at(-1).childNodes.push(decode(s)); };
  for (const m of html.matchAll(TAG)) {
    text(html.slice(last, m.index));
    last = m.index + m[0].length;
    const [, closing, rawTag, rawAttrs, selfClosing] = m;
    if (!rawTag) continue; // comment or doctype
    const tag = rawTag.toLowerCase();
    if (closing) {
      const at = stack.findLastIndex((e, i) => i > 0 && e.localName === tag);
      if (at > 0) {
        if (tag === "select") settleSelect(stack[at]);
        stack.length = at;
      }
      continue;
    }
    const attributes = {};
    for (const a of (rawAttrs || "").matchAll(ATTR)) {
      attributes[a[1].toLowerCase()] = decode(a[2] ?? a[3] ?? a[4] ?? "");
    }
    const element = new FakeElement(tag, attributes);
    stack.at(-1).appendChild(element);
    if (!VOID.has(tag) && !selfClosing) stack.push(element);
  }
  text(html.slice(last));
}

/** A <select>'s value is its selected option's, or its first option's. */
function settleSelect(select) {
  const options = select.querySelectorAll("option");
  const chosen = options.find((o) => "selected" in o.attributes) || options[0];
  if (chosen) select.value = chosen.getAttribute("value") ?? chosen.textContent;
}

const CONSOLE = new URL("../public/console/", import.meta.url);

/**
 * Mount a fresh copy of a real console page (a path under public/console/),
 * parsed from the file, so the ids and controls a test relies on are the ones
 * the page really has.
 */
export function mountPage(file = "index.html") {
  const root = new FakeElement("#document");
  root.innerHTML = readFileSync(new URL(file, CONSOLE), "utf8");
  page = {
    file,
    root,
    main: root.querySelector("main"),
    body: root.querySelector("body"),
    inserted: [],
  };
  return page;
}

export const $ = (id) => document.getElementById(id);

export const document = {
  getElementById: (id) => {
    for (const e of page.root.descendants()) if (e.id === id) return e;
    return null;
  },
  querySelector: (selector) => page.root.querySelector(selector),
  querySelectorAll: (selector) => page.root.querySelectorAll(selector),
  createElement: (tag) => new FakeElement(tag),
  get body() {
    return page.body;
  },
};

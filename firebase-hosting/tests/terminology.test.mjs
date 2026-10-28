// Run: node --test "firebase-hosting/tests/*.test.mjs"
// One word per concept in what the consoles say (AE3): backup, not sync;
// phone, not device, to an account holder; Demo as the plan's name, always
// with its capital (ADDENDUM-3); the product as "semper" (ADDENDUM-4); the two
// grace clocks named apart; a seat you have, paused and resumed, removed (not
// revoked); one label for giving a seat back; "your institution's IT".
import { test } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const CONSOLE = fileURLToPath(new URL("../public/console/", import.meta.url));

function files(dir = CONSOLE) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return name === "vendor" ? [] : files(path);
    return /\.(js|html)$/.test(name) ? [path] : [];
  });
}

/**
 * The text a person can read in `source`, as a list of snippets: for HTML,
 * the text between tags and the aria-label / placeholder / title values; for
 * JavaScript, each line of code (comments dropped). The rules below are
 * phrases, so an identifier (`deviceIdLock`, `onHold`) never matches one.
 */
function visibleText(path, source) {
  if (path.endsWith(".html")) {
    const html = source.replace(/<!--[\s\S]*?-->/g, "").replace(/<(script|style)[\s\S]*?<\/\1>/g, "");
    const attrs = [...html.matchAll(/\b(?:aria-label|placeholder|title)="([^"]*)"/g)].map((m) => m[1]);
    const text = html.replace(/<[^>]+>/g, "\n").split("\n").map((s) => s.trim()).filter(Boolean);
    return attrs.concat(text);
  }
  return source.replace(/\/\*[\s\S]*?\*\//g, "").split("\n")
    .filter((line) => !/^\s*\/\//.test(line))
    .map((line) => line.replace(/\s\/\/\s.*$/, "").trim())
    .filter(Boolean);
}

/**
 * The string literals in a script, as a list of snippets: the text it can put
 * on screen. A template literal keeps its literal parts (each `${...}` reads
 * " … ") and its markup is reduced as in an HTML file, so an attribute name
 * (`data-device`) is not text. A path (`/v1/...`) or a bare key (`device_id`,
 * `license_device_clear_required`) is code, not copy.
 */
function stringLiterals(source) {
  const out = [];
  const keep = (text, markup) => {
    const parts = markup
      ? [...text.matchAll(/\b(?:aria-label|placeholder|title)="([^"]*)"/g)].map((m) => m[1])
        .concat(text.replace(/<[^>]*>/g, "\n").split("\n"))
      : [text];
    for (const part of parts.map((s) => s.trim()).filter(Boolean)) {
      if (part.startsWith("/") || /^[\w.-]*[_.-][\w.-]*$/.test(part)) continue;
      out.push(part);
    }
  };
  const templates = []; // open template literals: the text so far and the brace depth of their ${
  let depth = 0;
  let i = 0;
  const readTemplate = (text) => {
    while (i < source.length) {
      const c = source[i];
      if (c === "\\") { text += source[i + 1] ?? ""; i += 2; continue; }
      if (c === "`") { i++; keep(text, true); return; }
      if (c === "$" && source[i + 1] === "{") {
        i += 2; depth++; templates.push({ text: text + " … ", depth }); return;
      }
      text += c; i++;
    }
  };
  while (i < source.length) {
    const c = source[i];
    if (c === "/" && source[i + 1] === "/") { const n = source.indexOf("\n", i); i = n < 0 ? source.length : n; continue; }
    if (c === "/" && source[i + 1] === "*") { const n = source.indexOf("*/", i + 2); i = n < 0 ? source.length : n + 2; continue; }
    if (c === '"' || c === "'") {
      let text = "";
      i++;
      while (i < source.length && source[i] !== c && source[i] !== "\n") {
        if (source[i] === "\\") { text += source[i + 1] ?? ""; i += 2; } else text += source[i++];
      }
      i++;
      keep(text, false);
      continue;
    }
    if (c === "`") { i++; readTemplate(""); continue; }
    if (c === "{") depth++;
    if (c === "}") {
      const top = templates[templates.length - 1];
      if (top && top.depth === depth) { templates.pop(); depth--; i++; readTemplate(top.text); continue; }
      depth--;
    }
    i++;
  }
  return out;
}

/** What a person can read: for a script, only its string literals. */
function copyText(path, source) {
  return path.endsWith(".js") ? stringLiterals(source) : visibleText(path, source);
}

/** `copyText` less a script's one-word literals: a word alone there is a value, not a sentence. */
function phrases(path, source) {
  return copyText(path, source).filter((snippet) => path.endsWith(".html") || /\s/.test(snippet));
}

const RULES = [
  // AE3-001: the cloud feature is backup (a function named sync is code).
  [/\bsync(?:s|ed|ing)?\b(?!\()/i, "back up / backup, not sync", () => true],
  // AE3-002: an account holder's handset is a phone (operator jargon may say device).
  [/\bdevices?\b/i, "phone, not device, in account-holder copy", (name) => name.startsWith("account/")],
  // AE3-002: IT and operator staff see the same handset as a phone, as the app
  // and the account page call it. Only copy is read: ids, API paths and field
  // names keep "device".
  [/\bdevices?\b/i, "phone, not device, on the IT roster and the operator desk",
    (name) => !name.startsWith("account/"), copyText],
  // AE3-003 / AE3-105, ADDENDUM-3: the free tier is Demo, with its capital,
  // wherever a sentence names it ("on Demo", "drops to Demo"). A one-word
  // literal is a value compared in code (`mode === "demo"`), not copy.
  [/\bdemo\b/, "Demo, capital D (ADDENDUM-3)", () => true, phrases],
  // ADDENDUM-3: the paid tier is Licensed, with its capital, wherever a
  // sentence puts someone on it ("still Licensed", "a Licensed key").
  [/\blicensed\b/, "Licensed, capital L (ADDENDUM-3)", () => true, phrases],
  // AE3-104: the Demo analysis cap is the Demo limit; "allowance" is only the
  // offline window.
  [/\bdemo allowance\b/i, "the Demo limit (allowance is the offline window)", () => true],
  // AE3-110, ADDENDUM-4: the product is "semper", lower-case even at the
  // start of a sentence or a title, and so is the authenticator issuer.
  // Identifiers (`deviceIdLockSemperClassroom`) and the company, Semper
  // Mechanics, are not the product name.
  [/\bSemper\b(?! Mechanics)/, "semper, lower-case (ADDENDUM-4)", () => true, copyText],
  // AE3-106: freeing a licence or seat for another phone is New phone; it
  // had a second name, Unbind, in the same dialog.
  [/\bun(?:bind|bound)\b/i, "New phone, not unbind", () => true, phrases],
  // AE3-108: a kill switch is "paused right now", as the app says it.
  [/\btemporarily unavailable\b|\bswitched off for maintenance\b/i, "paused right now", () => true],
  // AE3-109: what a mint shows once is the claim code the holder's page asks
  // for (the "Licence key" box that confirms a revoke keeps its name).
  [/\bkey below\b|\bredeems? the key\b|\bCopy this key\b/i, "the claim code", () => true],
  // AE3-117: the staff approval queue is Access requests, as the app and the
  // support mail call it.
  [/\bApprovals\b|\bawaiting approval\b/, "Access requests", () => true],
  // AE3-005: two clocks, two names.
  [/\bgrace period\b/i, "after-expiry grace / offline allowance, not grace period", () => true],
  // AE3-006: have a seat; Pause / Resume; Remove; one release label.
  [/\b(?:you hold|hold (?:one|a|your) seat|on hold|Holding)\b|\bHold\b/, "have a seat / Pause / paused", () => true],
  [/\bGive up seat\b/, "Give up my seat", () => true],
  [/\bRevoke the seat\b|\bRevoked, /, "Remove, for a seat", () => true],
  // AE3-008: the institution's IT; British spelling.
  [/\bIT department\b|\borganization\b|\bInitializ/, "your institution's IT; British spelling", () => true],
  // AE3-107: an account holder's two helpers are "your institution's IT"
  // and "semper support" (the operator desk's form still has IT contacts).
  [/\bIT contacts?\b/, "your institution's IT, to an account holder", (name) => name.startsWith("account/")],
  [/\bsemper\s+contact\b|\ban\s+admin\b/i, "semper support / your institution's IT", () => true],
];

test("the consoles use one word per concept", () => {
  const found = [];
  for (const path of files()) {
    const name = relative(CONSOLE, path).replace(/\\/g, "/");
    const source = readFileSync(path, "utf8");
    const snippets = visibleText(path, source);
    for (const [pattern, want, applies, read] of RULES) {
      if (!applies(name)) continue;
      for (const snippet of read ? read(path, source) : snippets) {
        const m = pattern.exec(snippet);
        if (m) found.push(`${name}: "${m[0]}" (${want}) in: ${snippet.slice(0, 90)}`);
      }
    }
  }
  assert.deepEqual(found, []);
});

// The hosted pages outside the consoles that an account holder lands on (the
// email-link sign-in, the password reset, the 404) follow the same rules.
// /privacy and /terms are legal text, held for counsel (TD-277), so not here.
const PUBLIC = fileURLToPath(new URL("../public/", import.meta.url));
const LANDING = ["404.html", "finishSignIn", "finishReset"];

test("the hosted landing pages use the consoles' words", () => {
  const found = [];
  for (const entry of LANDING) {
    const root = join(PUBLIC, entry);
    for (const path of statSync(root).isDirectory() ? files(root) : [root]) {
      const name = relative(PUBLIC, path).replace(/\\/g, "/");
      const source = readFileSync(path, "utf8");
      for (const [pattern, want, applies, read] of RULES) {
        if (!applies(name)) continue;
        for (const snippet of read ? read(path, source) : visibleText(path, source)) {
          const m = pattern.exec(snippet);
          if (m) found.push(`${name}: "${m[0]}" (${want}) in: ${snippet.slice(0, 90)}`);
        }
      }
    }
  }
  assert.deepEqual(found, []);
});

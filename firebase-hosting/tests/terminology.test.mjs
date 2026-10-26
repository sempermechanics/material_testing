// Run: node --test "firebase-hosting/tests/*.test.mjs"
// One word per concept in what the consoles say (AE3): backup, not sync;
// phone, not device, to an account holder; Demo as the plan's name; the two
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

const RULES = [
  // AE3-001: the cloud feature is backup (a function named sync is code).
  [/\bsync(?:s|ed|ing)?\b(?!\()/i, "back up / backup, not sync", () => true],
  // AE3-002: an account holder's handset is a phone (operator jargon may say device).
  [/\bdevices?\b/i, "phone, not device, in account-holder copy", (name) => name.startsWith("account/")],
  // AE3-003: the plan is Demo: on Demo, not on the demo.
  [/\b(?:on|to) the demo\b/i, "on Demo, not on the demo", () => true],
  // AE3-005: two clocks, two names.
  [/\bgrace period\b/i, "after-expiry grace / offline allowance, not grace period", () => true],
  // AE3-006: have a seat; Pause / Resume; Remove; one release label.
  [/\b(?:you hold|hold (?:one|a|your) seat|on hold|Holding)\b|\bHold\b/, "have a seat / Pause / paused", () => true],
  [/\bGive up seat\b/, "Give up my seat", () => true],
  [/\bRevoke the seat\b|\bRevoked, /, "Remove, for a seat", () => true],
  // AE3-008: the institution's IT; British spelling.
  [/\bIT department\b|\borganization\b|\bInitializ/, "your institution's IT; British spelling", () => true],
];

test("the consoles use one word per concept", () => {
  const found = [];
  for (const path of files()) {
    const name = relative(CONSOLE, path).replace(/\\/g, "/");
    const snippets = visibleText(path, readFileSync(path, "utf8"));
    for (const [pattern, want, applies] of RULES) {
      if (!applies(name)) continue;
      for (const snippet of snippets) {
        const m = pattern.exec(snippet);
        if (m) found.push(`${name}: "${m[0]}" (${want}) in: ${snippet.slice(0, 90)}`);
      }
    }
  }
  assert.deepEqual(found, []);
});

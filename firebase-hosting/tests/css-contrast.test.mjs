// Run: node --test "firebase-hosting/tests/*.test.mjs"
// console.css against WCAG: a text box's border is a UI component boundary
// (1.4.11, 3:1 against what is next to it), and no focus style is removed
// without a replacement forced-colours mode keeps (2.4.7).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const CSS = readFileSync(new URL("../public/console/console.css", import.meta.url), "utf8");

/** The :root custom properties, by name. */
const tokens = Object.fromEntries(
  [...CSS.slice(0, CSS.indexOf("}")).matchAll(/--([\w-]+):\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]),
);

/** [r, g, b, a] (0-255, a 0-1) of a #rrggbb or rgba() value. */
function rgba(value) {
  const hex = /^#([0-9a-f]{6})$/i.exec(value);
  if (hex) return [0, 2, 4].map((i) => parseInt(hex[1].slice(i, i + 2), 16)).concat(1);
  const fn = /^rgba?\(([^)]+)\)$/.exec(value);
  if (!fn) throw new Error(`not a colour: ${value}`);
  const [r, g, b, a = "1"] = fn[1].split(",").map((s) => s.trim());
  return [Number(r), Number(g), Number(b), Number(a)];
}

/** `top` drawn over the opaque `under`. */
function over(top, under) {
  const [r, g, b, a] = rgba(top);
  const [R, G, B] = rgba(under);
  return [r * a + R * (1 - a), g * a + G * (1 - a), b * a + B * (1 - a)];
}

function luminance([r, g, b]) {
  const lin = (c) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

function contrast(a, b) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

test("a text box's border is at least 3:1 against the box and the card around it", () => {
  const rule = /\ninput, select, textarea \{([^}]*)\}/.exec(CSS);
  assert.ok(rule, "the shared input rule");
  const token = /border: 1px solid var\(--([\w-]+)\)/.exec(rule[1])[1];
  const border = over(tokens[token], tokens["bg-elevated"]);
  const box = rgba(tokens["bg-elevated"]).slice(0, 3);
  const card = rgba(tokens.card).slice(0, 3);
  assert.ok(contrast(border, card) >= 3, `--${token} vs card: ${contrast(border, card).toFixed(2)}:1`);
  assert.ok(contrast(border, box) >= 3, `--${token} vs box: ${contrast(border, box).toFixed(2)}:1`);
});

test("no focus outline is removed: forced colours draw a transparent one", () => {
  assert.doesNotMatch(CSS, /outline:\s*none/);
  assert.match(CSS, /input:focus, select:focus, textarea:focus \{[^}]*outline: 2px solid transparent/);
});

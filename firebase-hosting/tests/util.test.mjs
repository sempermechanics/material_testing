// Run: node --test "firebase-hosting/tests/*.test.mjs"
// DOM-free console helpers (public/console/util.js). CI runs these in the
// console-pages job.
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  esc, when, leaseHeld, seatCells, inviteCells,
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
  assert.match(html, /on hold/);
  assert.match(html, /u&lt;2&gt;/);
  assert.match(html, /abcdefghij…/);
  assert.match(seatCells({ uid: "u", status: "revoked" }, NOW), /removed/);
  assert.match(seatCells({ uid: "u", status: "active" }, NOW), /not yet/);
});

test("invite cells line up with seat cells", () => {
  const html = inviteCells({ id: "k", email: "new@uni.edu" });
  assert.equal(html.match(/<td/g).length, 4);
  assert.match(html, /invited/);
});

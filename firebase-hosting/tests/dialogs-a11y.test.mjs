// The in-page cards (console/dialogs.js) as a keyboard and screen-reader user
// meets them: each is an alertdialog named by its heading and described by
// its message, Escape answers it as Cancel, and focus goes back to whatever
// opened it.
import assert from "node:assert/strict";
import { test, reset, settle, page, prompts, confirms, codes, alerts, loadAuth, FakeElement } from "./harness.mjs";

const { askInPage, confirmInPage, tellInPage, codeCard, enrolInPage } = await loadAuth();
const { holdFocus } = await import("../public/console/dialogs.js");

/** A button in the page that opens a card, focused as a click would leave it. */
function trigger() {
  const button = new FakeElement("button");
  page.main.appendChild(button);
  button.focus();
  return button;
}

const byId = (id) => [...page.root.descendants()].find((e) => e.id === id);

test("a card is an alertdialog named by its heading and described by its message", async () => {
  reset();
  const asked = confirmInPage({ title: "Remove ana@uni.edu", message: "Remove ana@uni.edu from this licence?" });
  const [card] = confirms.cards;
  assert.equal(card.getAttribute("role"), "alertdialog");
  assert.equal(byId(card.getAttribute("aria-labelledby")).textContent, "Remove ana@uni.edu");
  assert.equal(byId(card.getAttribute("aria-describedby")).textContent, "Remove ana@uni.edu from this licence?");
  confirms.answer(false);
  await asked;
});

test("two cards on one page do not share an id", async () => {
  reset();
  prompts.answer(null, null);
  const one = askInPage({ title: "One", message: "m" });
  const two = askInPage({ title: "Two", message: "m" });
  const [a, b] = prompts.cards;
  assert.notEqual(a.getAttribute("aria-labelledby"), b.getAttribute("aria-labelledby"));
  await Promise.all([one, two]);
});

test("Escape answers a confirm card as Cancel, an ask card as Cancel, and closes a tell card", async () => {
  reset();
  // Left alone by the harness (a newer generation): the test presses Escape itself.
  const yes = confirmInPage({ title: "Sure?", message: "m" });
  confirms.generation += 1;
  page.inserted.at(-1).dispatch("keydown", { key: "Escape" });
  assert.equal(await yes, false);
  assert.equal(confirms.cards[0].removed, true);

  const typed = askInPage({ title: "Key", message: "m" });
  prompts.generation += 1;
  page.inserted.at(-1).dispatch("keydown", { key: "Escape" });
  assert.equal(await typed, null);

  reset();
  const told = tellInPage({ title: "History", message: "m" });
  page.inserted.at(-1).dispatch("keydown", { key: "Escape" });
  await told;
  assert.deepEqual(alerts, ["m"]);
});

test("another key does not answer the card", async () => {
  reset();
  const yes = confirmInPage({ title: "Sure?", message: "m" });
  confirms.generation += 1;
  const card = page.inserted.at(-1);
  card.dispatch("keydown", { key: "a" });
  await settle();
  assert.equal(card.removed, false);
  card.querySelector(".cancel").click();
  assert.equal(await yes, false);
});

test("focus goes back to the button that opened the card, whatever the answer", async () => {
  for (const answer of [true, false]) {
    reset();
    const opener = trigger();
    confirms.answer(answer);
    await confirmInPage({ title: "Sure?", message: "m" });
    assert.equal(document.activeElement, opener, `after ${answer ? "confirm" : "cancel"}`);
  }
  reset();
  const opener = trigger();
  prompts.answer("SEMP-1");
  await askInPage({ title: "Key", message: "m" });
  assert.equal(document.activeElement, opener);
});

test("a card opened from a menu that has closed gives focus to the menu's summary", async () => {
  reset();
  const menu = new FakeElement("details");
  const summary = new FakeElement("summary");
  const item = new FakeElement("button");
  menu.appendChild(summary);
  menu.appendChild(item);
  page.main.appendChild(menu);
  item.focus();
  menu.open = false;
  confirms.answer(true);
  await confirmInPage({ title: "Revoke", message: "m" });
  assert.equal(document.activeElement, summary);
});

test("an opener the page has since removed is not focused", async () => {
  reset();
  const opener = trigger();
  const yes = confirmInPage({ title: "Sure?", message: "m" });
  confirms.generation += 1;
  opener.remove();
  const card = page.inserted.at(-1);
  card.querySelector(".confirm").click();
  await yes;
  assert.notEqual(document.activeElement, opener);
});

test("a rejected code is announced, and the box and Try again point at why", async () => {
  reset();
  const card = codeCard();
  const el = codes.cards[0];
  const feedback = el.querySelector(".feedback");
  assert.equal(feedback.getAttribute("role"), "alert");
  assert.ok(feedback.id);
  assert.equal(el.querySelector("input").getAttribute("aria-describedby"), feedback.id);
  assert.equal(el.querySelector(".retry").getAttribute("aria-describedby"), feedback.id);
  card.close();
  const enrol = enrolInPage({ qrUrl: "otpauth://x", secret: "S", finish: async () => {} }, "a@b.test", null);
  const enrolCard = page.inserted.find((e) => e.className === "card enrol");
  const said = enrolCard.querySelector(".feedback");
  assert.equal(said.getAttribute("role"), "alert");
  assert.equal(enrolCard.querySelector("input").getAttribute("aria-describedby"), said.id);
  assert.notEqual(said.id, feedback.id);
  enrolCard.querySelector(".cancel").click();
  await enrol.catch(() => {});
});

// AE2-108: the authenticator-code and enrolment cards are prompts like the
// others, so they are announced and dismissed the same way.
test("the code card is an alertdialog named by its heading, and Escape answers it as Cancel", async () => {
  reset();
  codes.generation += 1; // the test presses the keys itself
  const card = codeCard();
  const el = page.inserted.at(-1);
  assert.equal(el.getAttribute("role"), "alertdialog");
  assert.equal(byId(el.getAttribute("aria-labelledby")).textContent, "Enter your authenticator code");
  const code = card.next();
  el.dispatch("keydown", { key: "Escape" });
  assert.equal(await code, null, "a code asked for: Escape is Cancel");
  const again = card.rejected();
  el.dispatch("keydown", { key: "Enter" });
  el.dispatch("keydown", { key: "Escape" });
  assert.equal(await again, false, "Try again offered: Escape is Cancel");
  card.close();
});

test("the enrolment card is an alertdialog named by its heading, and Escape cancels it", async () => {
  reset();
  const enrol = enrolInPage({ qrUrl: "otpauth://x", secret: "S", finish: async () => {} }, "a@b.test", null);
  const el = page.inserted.find((e) => e.className === "card enrol");
  assert.equal(el.getAttribute("role"), "alertdialog");
  assert.equal(byId(el.getAttribute("aria-labelledby")).textContent, "Set up two-factor authentication");
  el.dispatch("keydown", { key: "Escape" });
  await assert.rejects(enrol, { message: "console_cancelled" });
  assert.equal(el.removed, true);
});

// AE2-109: a button that disables itself while it works gets focus back after.
test("holdFocus gives focus back to a button the browser dropped it from, and only then", async () => {
  reset();
  const button = trigger();
  let refocus = holdFocus(button);
  button.disabled = true;
  page.body.focus(); // what a browser does when the focused button is disabled
  refocus();
  assert.equal(document.activeElement, page.body, "not while it is still disabled");
  button.disabled = false;
  refocus();
  assert.equal(document.activeElement, button);

  refocus = holdFocus(button);
  const other = trigger(); // the person moved on meanwhile
  refocus();
  assert.equal(document.activeElement, other, "focus someone moved is left where it is");

  refocus = holdFocus(button); // it did not have focus to begin with
  page.body.focus();
  refocus();
  assert.equal(document.activeElement, page.body);
});

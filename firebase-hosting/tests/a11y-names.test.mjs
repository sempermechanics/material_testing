// Every form control on the console pages has an accessible name (WCAG 1.3.1,
// 4.1.2, 3.3.2): the page markup as served, and the boxes the in-page cards
// (dialogs.js) add. A placeholder is not a name: it goes as the person types.
import assert from "node:assert/strict";
import { test, mountPage, page, prompts, codes, settle, loadAuth, reset } from "./harness.mjs";

const { askInPage, confirmByTyping, codeCard, enrolInPage } = await loadAuth();

const CONTROLS = ["input", "select", "textarea"];

/** Why `el` has no accessible name, or "" when it has one. */
function unnamed(el, root) {
  const label = el.getAttribute("aria-label");
  if (label && label.trim()) return "";
  const by = el.getAttribute("aria-labelledby");
  if (by) {
    const text = by.split(/\s+/)
      .map((id) => [...root.descendants()].find((e) => e.id === id))
      .map((e) => (e ? e.textContent.trim() : ""))
      .join("");
    return text ? "" : `aria-labelledby="${by}" names nothing`;
  }
  if (el.closest("label")) return "";
  if (el.id && root.querySelectorAll("label").some((l) => l.getAttribute("for") === el.id)) return "";
  return "no aria-label, aria-labelledby, <label for> or enclosing <label>";
}

function describe(el) {
  return `<${el.localName}${el.id ? ` id="${el.id}"` : ""}${el.className ? ` class="${el.className}"` : ""}>`;
}

function unnamedControls(root) {
  return CONTROLS.flatMap((tag) => root.querySelectorAll(tag))
    .map((el) => [el, unnamed(el, root)])
    .filter(([, why]) => why)
    .map(([el, why]) => `${describe(el)}: ${why}`);
}

for (const file of ["index.html", "account/index.html", "institution/index.html", "operator/index.html"]) {
  test(`every form control on console/${file} has an accessible name`, () => {
    mountPage(file);
    assert.deepEqual(unnamedControls(page.root), []);
  });
}

test("the ask card's box is named after what it asks for", async () => {
  reset();
  const asked = askInPage({ title: "Re-enter your password", message: "m", password: true, label: "Password" });
  const box = prompts.cards[0].querySelector("input");
  assert.equal(box.getAttribute("aria-label"), "Password");
  assert.deepEqual(unnamedControls(prompts.cards[0]), []);
  prompts.answer(null);
  assert.equal(await asked, null);
});

test("an ask card with no label is named by its title", async () => {
  reset();
  const asked = askInPage({ title: "Extend a@b.test", message: "m" });
  assert.equal(prompts.cards[0].querySelector("input").getAttribute("aria-label"), "Extend a@b.test");
  prompts.answer(null);
  await asked;
});

test("the type-to-confirm box says what to type", async () => {
  reset();
  const typed = confirmByTyping("SEMP-4K2P", "revoke this licence", { title: "Revoke SEMP-4K2P", boxLabel: "Licence key" });
  assert.equal(prompts.cards[0].querySelector("input").getAttribute("aria-label"), "Licence key");
  prompts.answer("SEMP-4K2P");
  assert.equal(await typed, true);
});

test("the authenticator-code boxes are named", async () => {
  reset();
  const card = codeCard();
  assert.deepEqual(unnamedControls(codes.cards[0]), []);
  card.close();
  const enrol = enrolInPage({ qrUrl: "otpauth://x", secret: "S", finish: async () => {} }, "a@b.test", null);
  const enrolCard = page.inserted.find((e) => e.className === "card enrol");
  assert.deepEqual(unnamedControls(enrolCard), []);
  enrolCard.querySelector(".cancel").click();
  await enrol.catch(() => {});
  await settle();
});

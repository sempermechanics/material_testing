/* In-page cards in place of the browser's own dialogs: ask, confirm, tell,
 * confirm-by-typing, and the two second-factor cards (the authenticator code
 * and TOTP enrolment).
 *
 * DOM only. Nothing here loads Firebase or calls the API: `auth.js` drives the
 * second-factor cards, and the pages drive the rest. Not `window.prompt` /
 * `confirm` / `alert`, because some browsers show none of them at all (the
 * Claude desktop app's browser pane answers "prompt() is not supported").
 */

/** The person closed a card instead of answering it. */
export const ERR_CANCELLED = "console_cancelled";

/**
 * A card in the page in place of the browser's own dialogs: a heading, the
 * message (paragraphs split on blank lines; a single line break is kept), an
 * optional text or password box, the confirm button and, unless `cancel` is
 * null, a Cancel button. Resolves what `answer` makes of the confirm (given
 * the box's trimmed text), or null on Cancel.
 *
 * Not `window.prompt` / `confirm` / `alert`, for the reason `codeCard` gives:
 * some browsers show none of them at all. The card goes after `anchor`
 * (default: the status line). The Edit dialog passes an element inside
 * itself, because a modal dialog makes everything outside it unreachable.
 * Every piece of text goes in as a text node, so a licence label or address
 * is never markup. The box is named `label` ("Password", "Licence key"), or
 * the card's title, for a screen reader: it has no visible label of its own.
 */
function pageCard(kind, {
  title, message, input = false, password = false, label, confirm, cancel = "Cancel", anchor, answer,
}) {
  const card = document.createElement("section");
  card.className = `card ${kind}`;
  card.innerHTML = `
    <h2></h2>
    <div class="lines"></div>
    <div class="row">
      <input size="24" />
      <button type="button" class="confirm"></button>
      <button type="button" class="secondary cancel"></button>
    </div>`;
  card.querySelector("h2").textContent = title;
  const lines = card.querySelector(".lines");
  for (const text of String(message).split("\n\n")) {
    const p = document.createElement("p");
    p.className = "muted line";
    p.textContent = text;
    lines.appendChild(p);
  }
  const box = card.querySelector("input");
  if (input) {
    box.type = password ? "password" : "text";
    box.autocomplete = password ? "current-password" : "off";
    box.setAttribute("aria-label", label || title);
  } else {
    box.remove();
  }
  const ok = card.querySelector(".confirm");
  ok.textContent = confirm;
  const no = card.querySelector(".cancel");
  if (cancel == null) no.remove();
  else no.textContent = cancel;

  const at = anchor || document.getElementById("status") || document.querySelector("main");
  at.insertAdjacentElement("afterend", card);
  // The desk's status line sits above a long table; bring the card into view.
  if (card.scrollIntoView) card.scrollIntoView({ block: "center" });
  (input ? box : ok).focus();

  return new Promise((resolve) => {
    const done = (value) => { card.remove(); resolve(value); };
    const yes = () => done(answer(input ? box.value.trim() : ""));
    ok.addEventListener("click", yes);
    if (input) {
      // Enter answers the card; inside the Edit form it must not also submit it.
      box.addEventListener("keydown", (e) => {
        if (e.key !== "Enter") return;
        if (e.preventDefault) e.preventDefault();
        yes();
      });
    }
    if (cancel != null) no.addEventListener("click", () => done(null));
  });
}

/**
 * Ask for one line of text in the page. Resolves the trimmed answer ("" if
 * left blank), or null on Cancel. `label` names the box (default: `title`).
 */
export function askInPage({ title, message, password = false, label, confirm = "Confirm", anchor } = {}) {
  return pageCard("ask", {
    title, message, input: true, password, label, confirm, anchor, answer: (text) => text,
  });
}

/** A yes / no question in the page. Resolves true on `confirm`, false on Cancel. */
export async function confirmInPage({ title, message, confirm = "Continue", anchor } = {}) {
  const yes = await pageCard("confirm", { title, message, confirm, anchor, answer: () => true });
  return yes === true;
}

/** Something to read, in the page, with one Close button. Resolves once closed. */
export async function tellInPage({ title, message, anchor } = {}) {
  await pageCard("tell", { title, message, confirm: "Close", cancel: null, anchor, answer: () => true });
}

/**
 * Confirm a destructive act by making the operator type the thing's name.
 *
 * A yes/no dialog is muscle memory by the third licence of the afternoon;
 * typing `SEMP-4K2P` is not something a hand does absent-mindedly. Revoking
 * the wrong licence drops a whole institution to demo, so the friction is the
 * point.
 */
export async function confirmByTyping(label, what, { title = "Confirm", anchor, boxLabel } = {}) {
  const typed = await askInPage({
    title,
    message: `This cannot be undone.\n\nType ${label} to ${what}:`,
    label: boxLabel || `Type ${label}`,
    confirm: "Confirm",
    anchor,
  });
  return typed != null && typed === label;
}

/**
 * The authenticator-code card: a code box, Confirm and Cancel under the
 * status line, and after a rejected code a Try again button.
 *
 * Not `window.prompt`: some browsers show no prompt at all (the Claude
 * desktop app's browser pane answers "prompt() is not supported"), and there
 * this was the one step of sign-in nobody could complete. It sits where the
 * enrolment card does and looks like it, so first sign-in and every later one
 * ask for the code the same way.
 *
 * `next()` resolves the trimmed code, or null on Cancel. `rejected()` says the
 * code was not accepted and resolves true on Try again (the box cleared for a
 * fresh code), false on Cancel. `close()` removes the card.
 */
export function codeCard() {
  const card = document.createElement("section");
  card.className = "card code";
  card.innerHTML = `
    <h2>Enter your authenticator code</h2>
    <p class="muted">The 6-digit code your authenticator app shows for Semper DIC.</p>
    <div class="row">
      <input inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]*" aria-label="Authenticator code"
             placeholder="6-digit code" size="12" />
      <button class="confirm">Confirm</button>
      <button class="retry" hidden>Try again</button>
      <button class="secondary cancel">Cancel</button>
    </div>
    <p class="muted err feedback"></p>`;
  const anchor = document.getElementById("status") || document.querySelector("main");
  anchor.insertAdjacentElement("afterend", card);
  const input = card.querySelector("input");
  const confirmBtn = card.querySelector(".confirm");
  const retryBtn = card.querySelector(".retry");
  const feedback = card.querySelector(".feedback");

  // Whoever is waiting on the card, next() or rejected(), and what Cancel
  // answers it with.
  let waiting = null;
  let onCancel = null;
  const answer = (value) => {
    const settle = waiting;
    waiting = null;
    if (settle) settle(value);
  };
  const confirm = () => {
    const code = input.value.trim();
    if (!code || !retryBtn.hidden) return;
    // Being checked: no second submit of the same code.
    input.disabled = true;
    confirmBtn.disabled = true;
    answer(code);
  };
  confirmBtn.addEventListener("click", confirm);
  input.addEventListener("keydown", (e) => { if (e.key === "Enter") confirm(); });
  retryBtn.addEventListener("click", () => answer(true));
  card.querySelector(".cancel").addEventListener("click", () => answer(onCancel));

  return {
    next() {
      feedback.textContent = "";
      input.value = "";
      input.disabled = false;
      confirmBtn.disabled = false;
      confirmBtn.hidden = false;
      retryBtn.hidden = true;
      input.focus();
      onCancel = null;
      return new Promise((resolve) => { waiting = resolve; });
    },
    rejected() {
      feedback.textContent = "That code was not accepted. Codes change every 30 " +
        "seconds: wait for a fresh one, from the Semper DIC entry for this account.";
      confirmBtn.hidden = true;
      retryBtn.hidden = false;
      retryBtn.focus();
      onCancel = false;
      return new Promise((resolve) => { waiting = resolve; });
    },
    close() {
      card.remove();
    },
  };
}

/**
 * The enrolment card: a QR code to scan, the key for anyone who cannot, and
 * the code box. Built here rather than in each page's HTML because every
 * console needs it and the account page is the only one with a place of its
 * own for it. Resolves once Firebase accepts a code; rejects ERR_CANCELLED.
 *
 * The secret and the account go in as text nodes — never through innerHTML —
 * and the SVG is the library's own output for a URI we built, so the one
 * innerHTML below carries nothing a user typed. Without `qrSvg` (qr.js could
 * not be fetched) the key is shown open in place of the picture.
 */
export function enrolInPage(enrolment, account, qrSvg) {
  const card = document.createElement("section");
  card.className = "card enrol";
  card.innerHTML = `
    <h2>Set up two-factor authentication</h2>
    <p class="muted">Scan this with Google Authenticator, Microsoft
      Authenticator, Authy, 1Password or any authenticator app, then enter
      the 6-digit code it shows.</p>
    <div class="qr"></div>
    <details>
      <summary>Can't scan? Enter the key by hand</summary>
      <p class="mono key"></p>
      <p class="muted">Account: <span class="mono account"></span> · Issuer:
        Semper DIC · Time-based, 6 digits, 30 seconds</p>
    </details>
    <div class="row">
      <input inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]*" aria-label="Authenticator code"
             placeholder="6-digit code" size="12" />
      <button class="confirm">Confirm</button>
      <button class="secondary cancel">Cancel</button>
    </div>
    <p class="muted err feedback"></p>`;
  if (qrSvg) {
    card.querySelector(".qr").innerHTML = qrSvg(enrolment.qrUrl);
  } else {
    card.querySelector(".qr").hidden = true;
    card.querySelector("details").open = true;
  }
  card.querySelector(".key").textContent = enrolment.secret;
  card.querySelector(".account").textContent = account;

  const anchor = document.getElementById("status") || document.querySelector("main");
  anchor.insertAdjacentElement("afterend", card);
  const input = card.querySelector("input");
  const feedback = card.querySelector(".feedback");
  input.focus();

  return new Promise((resolve, reject) => {
    const done = (fn, value) => { card.remove(); fn(value); };
    const confirm = async () => {
      const code = input.value.trim();
      if (!code) return;
      feedback.textContent = "";
      card.querySelector(".confirm").disabled = true;
      try {
        await enrolment.finish(code);
        done(resolve);
      } catch (e) {
        card.querySelector(".confirm").disabled = false;
        feedback.textContent =
          `That code was not accepted (${e.code || e.message}). ` +
          "Check the phone's clock is set automatically, then try the next code.";
      }
    };
    card.querySelector(".confirm").addEventListener("click", confirm);
    input.addEventListener("keydown", (e) => { if (e.key === "Enter") confirm(); });
    card.querySelector(".cancel").addEventListener("click", () =>
      done(reject, new Error(ERR_CANCELLED)));
  });
}

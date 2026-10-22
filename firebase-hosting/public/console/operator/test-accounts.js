/* The Test accounts card (ADR-027): sign-ins for QC staff and CI, created
 * here with a generated password, listed, and per account extended, given a
 * new password, one phone released (TD-314: its place is free, and that
 * phone cannot register for the account again), or ended now.
 *
 * Every change is held to the revoke window (`attested_or_mfa_admin_fresh`),
 * so each one first steps up with `stepUpFresh`, carrying what to finish on
 * the return leg of a Google re-authentication (`resumeTestAccount`), as a
 * revoke does. The password is shown once, in the dialog, and nowhere else:
 * not in the status line, not in storage, never logged, and taken out of the
 * page as the dialog closes. The resume carries no password either: a new
 * one is asked for only after the step-up.
 */
import { stepUpFresh } from "../auth.js";
import { api } from "../api.js";
import { askInPage, confirmInPage, confirmByTyping, ERR_CANCELLED } from "../dialogs.js";
import { setStatus } from "../status.js";
import {
  esc, day, placeholderRows, retryRow, reauthFailureText,
  TEST_ACCOUNT, testAccountBody, testAccountEnds, extendDays,
} from "../util.js";
import { explain } from "../messages.js";
import { $ } from "./state.js";

/** Sentences for the refusals particular to test accounts. */
const SENTENCES = {
  test_accounts_disabled: "Test accounts are switched off on this server, or that address is " +
    "not on one of its test domains (TEST_ACCOUNT_DOMAINS).",
  email_in_use: "That address already has a Semper account or sign-in. A test account " +
    "needs an address nobody has used.",
  device_not_found: "That phone is no longer one of this account's active phones. " +
    "Refresh the list.",
};

const PURPOSES = { qc: "QC", ci: "CI" };
const APPS = { semper: "Semper", materialtesting: "Material Testing" };

/** A phone as staff know it: its model, else its device id. */
const phoneName = (d) => (d.model ? `${d.model} (${d.deviceId})` : d.deviceId);

/** The accounts last listed, for the email a row's buttons act on. */
let accounts = [];
let loads = 0; // the latest list load; an older answer is not drawn over it
let resumed = false; // one return leg per page load

const emailOf = (uid) => (accounts.find((a) => a.uid === uid) || {}).email || uid;

/* -------------------------------------------------------------- form */

/** CI may run without an end: its days field says so, and starts blank. */
function syncPurpose() {
  const ci = $("taPurpose").value === "ci";
  $("taDaysHint").textContent = ci ? "(1–90, or blank: never ends)" : "(1–90)";
  $("taDays").placeholder = ci ? "never ends" : "";
  const days = $("taDays").value;
  if (ci && days === String(TEST_ACCOUNT.defaultDays)) $("taDays").value = "";
  if (!ci && !days) $("taDays").value = String(TEST_ACCOUNT.defaultDays);
}
$("taPurpose").addEventListener("change", syncPurpose);

$("taCreate").addEventListener("click", async () => {
  const { body, error } = testAccountBody({
    email: $("taEmail").value,
    purpose: $("taPurpose").value,
    days: $("taDays").value,
    maxDevices: $("taMaxDevices").value,
    note: $("taNote").value,
  });
  if (error) {
    setStatus(error, true);
    return;
  }
  $("taCreate").disabled = true;
  try {
    await sendCreate(body);
  } finally {
    $("taCreate").disabled = false;
  }
});

async function sendCreate(body) {
  try {
    await stepUpFresh({ action: "testAccountCreate", body }, "create this test account");
    setStatus("Creating the test account…");
    const out = await api("/v1/admin/test-accounts", {
      method: "POST", body: JSON.stringify(body),
    });
    const email = out.email || body.email;
    const ends = out.expiresAt ? `ends ${day(out.expiresAt)}` : "never ends";
    const phones = out.maxDevices ?? body.maxDevices ?? TEST_ACCOUNT.defaultDevices;
    $("taEmail").value = "";
    $("taNote").value = "";
    setStatus(`Test account ${email} created (${PURPOSES[out.purpose || body.purpose]}): ` +
      `approved and licensed, ${ends}, on up to ${phones} phone${phones === 1 ? "" : "s"} at once. ` +
      "Copy its password now.");
    showPassword(email, out.password);
    loadTestAccounts();
  } catch (e) {
    refused(e, `create ${body.email}`);
  }
}

/* ------------------------------------------------------------ password */

/** Open the shown-once dialog on `password`, for `email`. */
function showPassword(email, password) {
  $("taPasswordEmail").textContent = email;
  $("taPassword").textContent = password || "";
  $("taPasswordDialog").showModal();
}

/** Take the password out of the page: the dialog is its only place. */
function forgetPassword() {
  $("taPassword").textContent = "";
  $("taPasswordEmail").textContent = "";
}

$("taPasswordClose").addEventListener("click", () => {
  forgetPassword();
  $("taPasswordDialog").close();
});
// Escape closes a modal dialog without the button.
$("taPasswordDialog").addEventListener("close", forgetPassword);

$("taCopyPassword").addEventListener("click", () => {
  navigator.clipboard.writeText($("taPassword").textContent)
    .then(() => setStatus("Password copied."))
    .catch(() => setStatus("Copy failed — select and copy it by hand.", true));
});

/* ---------------------------------------------------------------- list */

$("loadTestAccounts").addEventListener("click", () => loadTestAccounts());

/** Read and draw the test accounts (on demand: most visits never look). */
export async function loadTestAccounts() {
  const ticket = ++loads;
  $("loadTestAccounts").textContent = "Refresh";
  $("testAccountsWrap").hidden = false;
  $("testAccountRows").innerHTML = placeholderRows(7, 2);
  $("loadTestAccounts").disabled = true;
  try {
    const data = await api("/v1/admin/test-accounts");
    if (ticket !== loads) return;
    accounts = data.accounts || [];
    $("testAccountRows").innerHTML = accounts.length
      ? accounts.map(accountRow).join("")
      : '<tr><td colspan="7" class="muted">No test accounts.</td></tr>';
  } catch (e) {
    if (ticket !== loads) return;
    $("testAccountRows").innerHTML = retryRow(7,
      explain(e, SENTENCES, (text) => `Could not load: ${text}`));
  } finally {
    if (ticket === loads) $("loadTestAccounts").disabled = false;
  }
}

function accountRow(a) {
  const uid = esc(a.uid);
  const live = a.status === "active";
  return `
    <tr>
      <td>${esc(a.email || a.uid)}</td>
      <td>${esc(PURPOSES[a.purpose] || a.purpose || "—")}</td>
      <td>${esc(testAccountEnds(a))}</td>
      <td>${esc(a.activeDevices ?? 0)} of ${esc(a.maxDevices ?? "—")}${(a.devices || []).map((d) => phoneLine(uid, d)).join("")}</td>
      <td><span class="pill ${live ? "ok" : "off"}">${esc(a.status || "—")}</span></td>
      <td class="muted">${esc(a.note || "—")}</td>
      <td class="actions">
        <button class="secondary" data-ta-extend="${uid}">Extend</button>
        <button class="secondary" data-ta-password="${uid}">New password</button>
        <button class="secondary" data-ta-end="${uid}">End now</button>
      </td>
    </tr>`;
}

/** One ACTIVE phone of the account (`uid` already escaped), with its Release. */
function phoneLine(uid, d) {
  return `
        <div>${esc(phoneName(d))} · ${esc(APPS[d.app] || d.app || "—")}
          <button class="secondary" data-ta-release="${uid}" data-ta-device="${esc(d.deviceId)}">Release</button>
        </div>`;
}

$("testAccountRows").addEventListener("click", (ev) => {
  const btn = ev.target.closest("button");
  if (!btn) return;
  if ("retry" in btn.dataset) loadTestAccounts();
  if (btn.dataset.taExtend) extend(btn.dataset.taExtend);
  if (btn.dataset.taPassword) newPassword(btn.dataset.taPassword);
  if (btn.dataset.taRelease) release(btn.dataset.taRelease, btn.dataset.taDevice);
  if (btn.dataset.taEnd) endNow(btn.dataset.taEnd);
});

/* ------------------------------------------------------------- actions */

async function extend(uid) {
  const email = emailOf(uid);
  const typed = await askInPage({
    title: `Extend ${email}`,
    message: "Days from today, 1 to 90. The account and its licence then end " +
      "that many days from now.",
    confirm: "Extend",
  });
  if (typed == null) return;
  const days = extendDays(typed);
  if (days == null) {
    setStatus("Days is 1 to 90 — nothing was changed.", true);
    return;
  }
  await sendExtend(uid, email, days);
}

async function sendExtend(uid, email, days) {
  try {
    await stepUpFresh({ action: "testAccountExtend", uid, email, days }, "extend this test account");
    const out = await api(`/v1/admin/test-accounts/${encodeURIComponent(uid)}/extend`, {
      method: "POST", body: JSON.stringify({ days }),
    });
    setStatus(out && out.expiresAt
      ? `${email} now ends ${day(out.expiresAt)}.`
      : `${email} extended to ${days} days from today.`);
    loadTestAccounts();
  } catch (e) {
    refused(e, `extend ${email}`);
  }
}

async function newPassword(uid) {
  const email = emailOf(uid);
  if (!(await confirmInPage({
    title: `New password for ${email}`,
    message: `Replace the password of ${email}?\n\n` +
      "The old one stops working at once, and every phone signed in with it " +
      "is signed out. The new one is shown once.",
    confirm: "New password",
  }))) return;
  await sendPassword(uid, email);
}

async function sendPassword(uid, email) {
  try {
    await stepUpFresh({ action: "testAccountPassword", uid, email },
      "reset this test account's password");
    const out = await api(`/v1/admin/test-accounts/${encodeURIComponent(uid)}/password`, {
      method: "POST",
    });
    setStatus(`New password for ${email}; its phones are signed out. Copy it now.`);
    showPassword(email, out.password);
  } catch (e) {
    refused(e, `reset the password of ${email}`);
  }
}

async function release(uid, deviceId) {
  const email = emailOf(uid);
  const account = accounts.find((a) => a.uid === uid) || {};
  const phone = (account.devices || []).find((d) => d.deviceId === deviceId) || { deviceId };
  const name = phoneName(phone);
  if (!(await confirmInPage({
    title: `Release a phone of ${email}`,
    message: `Release ${name} from ${email}?\n\n` +
      "Its place is free for another phone at once. This phone stops working " +
      "with the account and cannot register for it again; its analyses stay.",
    confirm: "Release",
  }))) return;
  await sendRelease(uid, email, deviceId, phone.app || "");
}

async function sendRelease(uid, email, deviceId, app) {
  try {
    await stepUpFresh({ action: "testAccountDeviceRelease", uid, email, deviceId, app },
      "release this phone");
    const phone = encodeURIComponent(deviceId);
    await api(`/v1/admin/test-accounts/${encodeURIComponent(uid)}/devices/${phone}/release`, {
      method: "POST",
    });
    setStatus(`Phone ${deviceId} released from ${email}: its place is free, and that phone ` +
      "cannot register for this account again.");
    loadTestAccounts();
  } catch (e) {
    refused(e, `release phone ${deviceId} from ${email}`);
  }
}

async function endNow(uid) {
  const email = emailOf(uid);
  if (!(await confirmInPage({
    title: `End ${email}`,
    message: `End ${email} now?\n\n` +
      "It is suspended and its phones signed out, its analyses and cloud " +
      "backups are erased, and its sign-in is deleted. Its audit rows are kept.",
  }))) return;
  if (!(await confirmByTyping(email, "end this test account", { title: `End ${email}` }))) {
    setStatus("Not ended — the address did not match.");
    return;
  }
  await sendEnd(uid, email);
}

async function sendEnd(uid, email) {
  try {
    await stepUpFresh({ action: "testAccountEnd", uid, email }, "end this test account");
    await api(`/v1/admin/test-accounts/${encodeURIComponent(uid)}`, { method: "DELETE" });
    setStatus(`${email} ended: suspended, its data erased and its sign-in deleted.`);
    loadTestAccounts();
  } catch (e) {
    refused(e, `end ${email}`);
  }
}

/** Say why a change was not made; a cancelled step-up sent nothing. */
function refused(e, what) {
  if (e.message === ERR_CANCELLED) {
    setStatus("Cancelled — nothing was sent.");
    return;
  }
  setStatus(explain(e, SENTENCES, (text) => `Could not ${what}: ${text}`), true);
}

/* --------------------------------------------------------- return leg */

/** What each stashed action does, in words, and how to finish it. */
const RESUMES = {
  testAccountCreate: {
    what: (r) => `create the test account ${r.body.email}`,
    run: (r) => sendCreate(r.body),
    valid: (r) => Boolean(r.body && testAccountBody({
      ...r.body,
      days: r.body.days == null ? "" : String(r.body.days),
      maxDevices: r.body.maxDevices == null ? "" : String(r.body.maxDevices),
    }).body),
  },
  testAccountExtend: {
    what: (r) => `extend ${r.email} to ${r.days} days from today`,
    run: (r) => sendExtend(r.uid, r.email, r.days),
    valid: (r) => Boolean(r.uid && r.email && extendDays(String(r.days))),
  },
  testAccountPassword: {
    what: (r) => `give ${r.email} a new password`,
    run: (r) => sendPassword(r.uid, r.email),
    valid: (r) => Boolean(r.uid && r.email),
  },
  testAccountDeviceRelease: {
    what: (r) => `release phone ${r.deviceId} from ${r.email}`,
    run: (r) => sendRelease(r.uid, r.email, r.deviceId, r.app || ""),
    valid: (r) => Boolean(r.uid && r.email && r.deviceId),
  },
  testAccountEnd: {
    what: (r) => `end ${r.email}`,
    run: (r) => sendEnd(r.uid, r.email),
    valid: (r) => Boolean(r.uid && r.email),
  },
};

/** Whether `resume` (from `requireSignIn`) is a test-account change. */
export function isTestAccountResume(resume) {
  return Boolean(resume && Object.hasOwn(RESUMES, resume.action));
}

/**
 * The return leg of a test-account change that went to Google for a fresh
 * sign-in. The confirmations happened on the way out; one plain one here
 * says what is about to be sent, because a page acting on load without a
 * gesture would act on a stale tab the browser restored. A leg that came
 * back without a fresh sign-in sent nothing, and says so.
 */
export async function resumeTestAccount(resume) {
  if (resumed || !isTestAccountResume(resume)) return;
  resumed = true;
  const step = RESUMES[resume.action];
  if (!step.valid(resume)) return;
  const what = step.what(resume);
  if (resume.reauthFailed) {
    setStatus(`Did not ${what}: ${reauthFailureText(resume.reauthFailed)}. ` +
      "Try it again.", true);
    return;
  }
  if (!(await confirmInPage({
    title: "Re-authenticated",
    message: `Re-authenticated. ${what[0].toUpperCase()}${what.slice(1)} now?`,
    confirm: "Continue",
  }))) {
    setStatus("Cancelled — nothing was sent.");
    return;
  }
  await step.run(resume);
}

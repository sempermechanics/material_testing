/* The end user's own page: what they hold, and what they have stored.
 *
 * Two calls answer the whole of the first card — `/v1/me` carries the
 * licence summary including seating and the lease, so nothing here has to
 * fetch limits to find out whether a seat is held. The analyses come from
 * `/v1/sessions`, which carries the quota alongside the page.
 */
import {
  requireSignIn, api, apiBlob, saveBlob, setStatus, esc, when,
  hasSecondFactor, beginTotpEnrolment, ERR_CANCELLED,
} from "../auth.js";
import { qrSvg } from "../qr.js";

const $ = (id) => document.getElementById(id);

let licence = {};        // the `license` block of /v1/me
let sessions = [];       // every page loaded so far
let nextToken = "";
let enrolment = null;

requireSignIn((user) => {
  $("signedOut").hidden = true;
  renderFactorState(user);
  loadAccount();
  loadSessions({ reset: true });
});

/* ------------------------------------------------------ second factor */

function renderFactorState(user) {
  const enrolled = hasSecondFactor(user);
  const pill = $("mfaPill");
  pill.hidden = false;
  pill.textContent = enrolled ? "2FA on" : "2FA off";
  pill.className = `pill ${enrolled ? "ok" : "warn"}`;
  $("enrolCard").hidden = enrolled;
}

$("enrolStart").addEventListener("click", async () => {
  try {
    setStatus("Re-authenticating…");
    enrolment = await beginTotpEnrolment();
    $("enrolQr").innerHTML = qrSvg(enrolment.qrUrl);
    $("enrolSecret").textContent = enrolment.secret;
    $("enrolAccount").textContent = $("who").textContent || "your Semper account";
    $("enrolStep").hidden = false;
    setStatus("");
  } catch (e) {
    if (e.message === ERR_CANCELLED) return setStatus("");
    setStatus(`Could not start enrolment: ${e.code || e.message}`, true);
  }
});

$("enrolFinish").addEventListener("click", async () => {
  if (!enrolment) return;
  const code = $("enrolCode").value.trim();
  if (!code) return;
  try {
    setStatus("Confirming…");
    await enrolment.finish(code);
    renderFactorState();
    setStatus("Two-factor authentication is on.");
  } catch (e) {
    setStatus(`That code was not accepted: ${e.code || e.message}`, true);
  }
});

/* ------------------------------------------------------------ licence */

async function loadAccount() {
  try {
    const me = await api("/v1/me");
    licence = me.license || {};
    renderLicence();
  } catch (e) {
    setStatus(`Could not read your account: ${e.message}`, true);
  }
}

function renderLicence() {
  const licensed = licence.mode === "licensed";
  const floating = licence.seating === "floating";
  const holdsSeat = licence.leaseExpiresAt &&
    new Date(licence.leaseExpiresAt) > new Date();

  const pills = [
    `<span class="pill ${licensed ? "ok" : "off"}">${licensed ? "licensed" : "demo"}</span>`,
  ];
  if (licence.kind) pills.push(`<span class="pill">${esc(licence.kind)}</span>`);
  if (licence.prefix) pills.push(`<span class="pill mono">${esc(licence.prefix)}</span>`);
  if (licence.duration === "perpetual") pills.push('<span class="pill">no end date</span>');
  if (licence.expiresAt) {
    pills.push(
      `<span class="pill ${licence.inGrace ? "warn" : ""}">` +
      `${licence.inGrace ? "expired" : "expires"} ${esc(when(licence.expiresAt))}</span>`,
    );
  }
  if (floating) {
    pills.push(
      holdsSeat
        ? `<span class="pill ok">seat held until ${esc(when(licence.leaseExpiresAt))}</span>`
        : '<span class="pill warn">no seat right now</span>',
    );
  }
  $("pills").innerHTML = `<p>${pills.join(" ")}</p>`;
  $("explain").textContent = explain(licensed, floating, holdsSeat);

  // A seat can only be given back by whoever holds it, so the button
  // appears only when there is something to give back.
  $("release").hidden = !(floating && holdsSeat);
  // Nothing to move if no licence was ever attached; the backend answers
  // `no_license` in that case, which is a worse way to find out.
  $("unbind").hidden = !licence.kind;
}

function explain(licensed, floating, holdsSeat) {
  if (!licensed && floating) {
    return "Every seat on your institution's licence is in use just now. " +
           "Your saved work is untouched, and Semper becomes licensed again " +
           "on this account as soon as a colleague finishes.";
  }
  if (!licensed) {
    return "You are on the demo. Analyses are capped and cloud backup is off. " +
           "Ask your Semper contact or your IT department for a licence — it " +
           "attaches to this address by itself, with nothing to type.";
  }
  if (licence.inGrace) {
    return "Your licence has passed its end date. Nothing is restricted yet, " +
           `but full access ends ${when(licence.graceEndsAt)} unless it is renewed.`;
  }
  if (floating) {
    return holdsSeat
      ? "You hold one of your institution's shared seats. It renews itself " +
        "while you are working, and returns to the pool when you stop."
      : "Your institution's seats are shared.";
  }
  return "Your licence is active on this account.";
}

$("release").addEventListener("click", async () => {
  if (!confirm(
    "Give up your seat? Your saved work stays exactly as it is, and you " +
    "take another seat the next time you use Semper — if one is free.",
  )) return;
  setStatus("Returning your seat…");
  try {
    await api("/v1/licenses/release", { method: "POST" });
    await loadAccount();
    setStatus("Seat returned.");
  } catch (e) {
    setStatus(`Could not return your seat: ${releaseError(e.message)}`, true);
  }
});

function releaseError(code) {
  return {
    seating_not_floating: "your licence does not use shared seats.",
    no_license: "there is no licence on this account.",
    rate_limited: "too many requests just now — wait a moment.",
  }[code] || code;
}

$("unbind").addEventListener("click", async () => {
  if (!confirm(
    "Move your licence to a different device?\n\n" +
    "Nothing is deleted and nothing is cancelled. Semper stops being " +
    "licensed on your current device, and attaches to the next device you " +
    "sign in on. Your analyses come with you once it has.",
  )) return;
  setStatus("Unlocking…");
  try {
    const out = await api("/v1/licenses/unbind", { method: "POST" });
    setStatus(
      "Done. Sign in on the new device, open Semper once so the licence " +
      "attaches, and then restore your analyses." +
      (out.nextChangeAllowedAt
        ? ` You can do this again after ${when(out.nextChangeAllowedAt)}.`
        : ""),
    );
  } catch (e) {
    setStatus(unbindError(e.message), true);
  }
});

function unbindError(code) {
  return {
    // Not a refusal of entitlement: a second factor proves who is asking,
    // not how often, so the cooldown is what stops one licence being
    // passed round a lab.
    device_change_too_soon:
      "You have moved this licence recently. Ask your IT contact or Semper " +
      "support if you need to move it again now.",
    mfa_required:
      "Set up two-factor authentication first — moving a licence needs it.",
    no_license: "There is no licence on this account to move.",
    seat_not_found: "Your seat is no longer on that licence — ask your IT contact.",
    license_not_found: "That licence no longer exists — ask your Semper contact.",
  }[code] || `Could not move your licence: ${code}`;
}

/* ----------------------------------------------------------- analyses */

$("more").addEventListener("click", () => loadSessions({ reset: false }));

async function loadSessions({ reset }) {
  if (reset) { sessions = []; nextToken = ""; }
  setStatus("Loading your analyses…");
  try {
    const page = nextToken ? `?page_token=${encodeURIComponent(nextToken)}` : "";
    const data = await api(`/v1/sessions${page}`);
    sessions = sessions.concat(data.sessions || []);
    nextToken = (data.page || {}).nextPageToken || "";
    $("more").hidden = !nextToken;
    const q = data.quota || {};
    $("quota").textContent = q.max == null
      ? `${q.used ?? sessions.length} analyses stored.`
      : `${q.used ?? sessions.length} of ${q.max} analyses stored.`;
    renderSessions();
    setStatus("");
  } catch (e) {
    setStatus(`Could not list your analyses: ${e.message}`, true);
  }
}

function renderSessions() {
  $("rows").innerHTML = sessions.map(sessionRow).join("") ||
    '<tr><td colspan="5" class="muted">Nothing backed up yet.</td></tr>';
}

function sessionRow(s) {
  const done = s.status === "COMPLETED";
  const state = {
    COMPLETED: '<span class="pill ok">saved</span>',
    UPLOADING: '<span class="pill warn">uploading</span>',
    PROVISIONING: '<span class="pill warn">starting</span>',
    PROVISION_FAILED: '<span class="pill off">failed</span>',
  }[s.status] || `<span class="pill off">${esc(s.status || "")}</span>`;
  // An analysis with nothing stored yet has no bundle to build, and the
  // backend answers 409 rather than sending an empty zip.
  const canDownload = (s.completedCount || 0) > 0;
  return `
    <tr>
      <td>${esc(s.specimen || s.localSessionId || s.sessionId)}
          <div class="muted mono">${esc(s.sessionId)}</div></td>
      <td>${state}</td>
      <td>${done ? s.completedCount : `${s.completedCount || 0} of ${s.fileCount || 0}`}</td>
      <td>${esc(size(s.totalBytes))}</td>
      <td class="actions">
        <button class="secondary" data-download="${esc(s.sessionId)}"
                ${canDownload ? "" : "disabled"}>Download</button>
      </td>
    </tr>`;
}

// One listener on the body rather than one per row: the table is rebuilt
// on every page load, and re-binding each time leaks handlers.
$("rows").addEventListener("click", (e) => {
  const sid = e.target?.dataset?.download;
  if (sid) download(sid, e.target);
});

async function download(sid, button) {
  button.disabled = true;
  setStatus("Building your download… this can take a minute for a large analysis.");
  try {
    const blob = await apiBlob(`/v1/sessions/${encodeURIComponent(sid)}/bundle`);
    saveBlob(blob, `semper-analysis-${sid}.zip`);
    setStatus("Downloaded.");
  } catch (e) {
    setStatus(downloadError(e.message), true);
  } finally {
    button.disabled = false;
  }
}

function downloadError(detail) {
  // The backend may suffix a code with ": <sentence>" (feature_not_licensed
  // does, for pre-licensing phones); the map is keyed on the code alone.
  const code = String(detail).split(":")[0].trim();
  return {
    mfa_required:
      "Set up two-factor authentication first — downloads from a browser need it.",
    feature_not_licensed:
      "Downloading needs a licence. Demo analyses stay on the device that made them.",
    file_not_uploaded: "Nothing has finished uploading in that analysis yet.",
    session_not_found: "That analysis is no longer stored.",
    rate_limited: "Too many downloads just now — wait a moment and try again.",
    drive_download_failed:
      "Storage did not answer. The analysis is intact; try again shortly.",
  }[code] || `Could not download: ${code}`;
}

/** Bytes as something a person reads, matching the app's own rounding. */
function size(bytes) {
  const n = Number(bytes || 0);
  if (!n) return "—";
  const units = ["B", "KB", "MB", "GB"];
  let i = 0;
  let v = n;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i += 1; }
  return `${v < 10 && i ? v.toFixed(1) : Math.round(v)} ${units[i]}`;
}

import {
  requireSignIn, api, setStatus, esc, when, confirmByTyping,
  stepUpForRevoke, ERR_CANCELLED,
} from "../auth.js";
import { seatCells, inviteCells } from "../util.js";

const $ = (id) => document.getElementById(id);
let licences = [];
let roster = null;      // { id, label } of the licence whose roster is open
// Reconciliation reports, keyed by licence id, kept only for this page
// load. Filled on demand: the read costs one user lookup per seat, so it
// is never run for the whole table at once.
let verified = {};
// One pending revoke per page load, whatever calls `onReady`.
let resumed = false;

requireSignIn(async (user, resume) => {
  $("signedOut").hidden = true;
  if (!(await isOperator(user))) return;
  showFactorPill();
  loadUsers();
  await loadLicences();
  // Back from the Google re-authentication a revoke asked for: finish it
  // now, while the fresh sign-in is inside the backend's window.
  if (resume && resume.action === "revoke") resumeRevoke(resume.id);
});

/**
 * Whether this account may see the desk at all. Anyone can be sent here by
 * a link, and the backend refuses every call from a non-operator, so the
 * page used to show a full mint form under a one-line refusal. Now the desk
 * stays hidden and the account is told where it can go instead.
 */
async function isOperator(user) {
  let me;
  try {
    me = await api("/v1/me");
  } catch (e) {
    $("app").hidden = true;
    setStatus(`Could not check whether ${user.email} is an operator: ${e.message}`, true);
    return false;
  }
  if (me.role === "admin") return true;
  $("app").hidden = true;
  $("notOperatorWho").textContent = user.email;
  $("notOperator").hidden = false;
  setStatus("");
  return false;
}

/* ------------------------------------------------------ second factor */

// Page code only runs once `requireSignIn` has enrolled a second factor
// and confirmed it for this session (`ensureDashboardMfa`), so the factor is
// always on here; the pill just says so.
function showFactorPill() {
  const pill = $("mfaPill");
  pill.hidden = false;
  pill.textContent = "2FA on";
  pill.className = "pill ok";
}

/* --------------------------------------------------------------- mint */

for (const radio of document.querySelectorAll('input[name="kind"]')) {
  radio.addEventListener("change", () => {
    const institution = radio.value === "institution" && radio.checked;
    $("individualFields").hidden = institution;
    $("institutionFields").hidden = !institution;
  });
}

$("duration").addEventListener("change", () => {
  const timed = $("duration").value === "timed";
  $("expiresAt").disabled = !timed;
  $("graceDays").disabled = !timed;
  if (!timed) { $("expiresAt").value = ""; $("graceDays").value = ""; }
});

const num = (id) => ($(id).value ? Number($(id).value) : null);
const str = (id) => ($(id).value.trim() || null);

$("mint").addEventListener("click", async () => {
  const kind = document.querySelector('input[name="kind"]:checked').value;
  const timed = $("duration").value === "timed";
  if (timed && !$("expiresAt").value) {
    setStatus("A time-limited licence needs an expiry date.", true);
    return;
  }
  const body = {
    kind,
    duration: $("duration").value,
    // End of the chosen day, UTC — a licence bought "until the 31st"
    // should not stop working on the morning of the 31st.
    expiresAt: timed ? `${$("expiresAt").value}T23:59:59Z` : null,
    graceDays: timed ? num("graceDays") : null,
    maxAnalyses: num("maxAnalyses"),
    note: str("note"),
    ...(kind === "institution"
      ? {
          domainLock: str("domainLock"),
          adminEmails: ($("adminEmails").value || "")
            .split(",").map((s) => s.trim()).filter(Boolean),
          maxSeats: num("maxSeats"),
          seating: $("seating").value,
        }
      : { emailLock: str("emailLock") }),
  };
  for (const k of Object.keys(body)) if (body[k] === null) delete body[k];

  // A second live licence for one address is almost never what was meant —
  // renewal is Extend — and the backend mints it anyway, only reporting that
  // the address is already promised elsewhere. Ask first, from the list
  // already on screen.
  if (kind === "individual" && body.emailLock) {
    const already = liveLicencesFor(body.emailLock);
    if (already.length && !window.confirm(
      `${body.emailLock} already holds ${already.map(labelOfLicence).join(", ")}.\n\n` +
      "To renew, cancel and use Extend on that row. Issue a second licence " +
      "anyway? It will not attach to their account.",
    )) {
      setStatus("Not issued.");
      return;
    }
  }

  $("mint").disabled = true;
  setStatus("Issuing…");
  try {
    const out = await api("/v1/admin/licenses", {
      method: "POST", body: JSON.stringify(body),
    });
    // An institution mint returns no plaintext key — there is nothing for
    // anyone to type, membership is the roster. An individual one returns
    // a key for support recovery only; delivery is the sign-in.
    if (out.key) {
      $("mintedKey").textContent = out.key;
      $("mintedBox").hidden = false;
    }
    setStatus(...mintOutcome(out, body.emailLock));
    loadLicences({ keepStatus: true });
  } catch (e) {
    setStatus(mintError(e.message), true);
  } finally {
    $("mint").disabled = false;
  }
});

/**
 * What an individual mint actually did, as [message, isError]. The licence
 * exists in every case; what varies is whether it reached the person, and
 * each way it did not is something the operator has to act on.
 */
function mintOutcome(out, email) {
  if (!out.key) return ["Institution licence issued."];
  const who = email || "that address";
  if (out.inviteError === "invite_exists") {
    return [`Issued, but ${who} is already promised another licence — this ` +
      "one will not attach. Revoke whichever of the two is not wanted.", true];
  }
  if (out.inviteError) {
    return [`Issued, but the invite for ${who} failed (${out.inviteError}) — ` +
      "it will not attach at sign-in. The key below still redeems it.", true];
  }
  if (out.claimError === "holder_already_licensed") {
    return [`Issued, but ${who} already holds a live licence, so this one was ` +
      "not attached. Revoke or extend the other one.", true];
  }
  if (out.claimError) {
    return [`Issued, but it could not be attached to ${who} (${out.claimError}). ` +
      "The key below redeems it.", true];
  }
  if (out.claimedByUid) {
    return [`Issued and attached to ${who} — licensed from their next request.`];
  }
  return [`Issued. It attaches when ${who} first signs in.`];
}

function liveLicencesFor(email) {
  const address = email.trim().toLowerCase();
  return licences.filter((l) =>
    l.kind !== "institution" && l.mode !== "demo" && l.status !== "revoked" &&
    !lapsed(l) && (l.emailLock || "").toLowerCase() === address);
}

/** Past its expiry and grace — replacing one of these is what a new mint is for. */
function lapsed(l) {
  const end = Date.parse(l.graceEndsAt || l.expiresAt || "");
  return Number.isFinite(end) && end < Date.now();
}

const labelOfLicence = (l) => l.keyPrefix || l.id.slice(0, 10);

function mintError(code) {
  return {
    mfa_required: "Enrol a second factor before issuing licences.",
    not_admin: "That account is not a Semper operator.",
    rate_limited: "Too many admin calls just now — wait a moment.",
  }[code] || `Could not issue: ${code}`;
}

$("copyKey").addEventListener("click", () => {
  navigator.clipboard.writeText($("mintedKey").textContent)
    .then(() => setStatus("Key copied."))
    .catch(() => setStatus("Copy failed — select and copy it by hand.", true));
});

/* ----------------------------------------------------------- licences */

$("reload").addEventListener("click", () => loadLicences());
$("filter").addEventListener("input", renderLicences);
$("showRevoked").addEventListener("change", renderLicences);

/**
 * Fetch and redraw the licence table.
 *
 * `keepStatus` is for the reload that follows a change: the status line then
 * holds what the change did ("SEMP-4K2P revoked.", or why it failed), and a
 * "Loading…" written over it and cleared half a second later is how every
 * result on this desk used to vanish before anyone could read it.
 */
async function loadLicences({ keepStatus = false } = {}) {
  if (!keepStatus) setStatus("Loading…");
  // Every caller of this is either a page load or something that just
  // changed a licence or a seat, so any reconciliation already on screen
  // describes a state that no longer exists.
  const wasOpen = $("verifyCard").hidden ? "" : $("verifyCard").dataset.licence;
  verified = {};
  try {
    const data = await api("/v1/admin/licenses?limit=200");
    licences = data.licenses || [];
    licencePage = data.page || {};
    renderLicences();
    if (!keepStatus) setStatus("");
    // A revoke is exactly the moment to ask again whether it landed.
    if (wasOpen) loadVerified(wasOpen);
  } catch (e) {
    setStatus(
      e.message === "not_admin"
        ? "That account is not a Semper operator."
        : `Could not load licences: ${e.message}`,
      true,
    );
  }
}

let licencePage = {};

// Revoked licences are kept — the record is the audit trail, and a revoked
// key can still be looked up — but out of the way by default: a revoke that
// left its row in place with only the pill changed read as a revoke that had
// not happened.
function renderLicences() {
  const q = $("filter").value.trim().toLowerCase();
  const showRevoked = $("showRevoked").checked;
  const revokedCount = licences.filter((l) => l.status === "revoked").length;
  const rows = licences.filter((l) =>
    (showRevoked || l.status !== "revoked") &&
    (!q || [l.keyPrefix, l.domainLock, l.emailLock, l.note]
      .some((v) => (v || "").toLowerCase().includes(q))));
  $("licenceRows").innerHTML = rows.length
    ? rows.map(licenceRow).join("")
    : '<tr><td colspan="8" class="muted">Nothing matches.</td></tr>';
  $("revokedCount").textContent = revokedCount ? ` (${revokedCount})` : "";
  const count = licencePage.count ?? licences.length;
  const hidden = !showRevoked && revokedCount
    ? `, ${revokedCount} revoked hidden` : "";
  $("licencePaging").textContent = licencePage.hasMore
    ? `Showing the first ${count}${hidden}; more exist.`
    : `${count} licence(s)${hidden}.`;
}

function seatSummary(lic) {
  // An individual licence has no seat count to show; a bare "1" here read as
  // a number someone had chosen, next to a cap column that also held numbers.
  if (lic.kind !== "institution") return "—";
  const cap = lic.maxSeats == null ? "∞" : lic.maxSeats;
  // The two counts mean different things, and conflating them is the
  // easiest mistake to make when reading this table: on a floating licence
  // maxSeats caps concurrent use, not roster size.
  const intended = lic.seating === "floating"
    ? `${lic.leasesActive ?? 0}/${cap} in use · ${lic.seatsUsed ?? 0} on roster`
    : `${lic.seatsUsed ?? 0}/${cap}`;
  return intended + verifiedNote(lic.id);
}

/** The second count, once someone has asked for it. */
function verifiedNote(id) {
  const report = verified[id];
  if (!report) return "";
  const outstanding = report.counts.revokedStillRunning;
  return outstanding
    ? ` · <span class="err">${outstanding} revoke${outstanding === 1 ? "" : "s"}` +
      ` not landed</span>`
    : ` · <span class="ok">${report.entitled} verified</span>`;
}

function licenceRow(lic) {
  const label = lic.keyPrefix || lic.id.slice(0, 10);
  const term = lic.duration === "timed"
    ? `until ${esc(when(lic.expiresAt))}${lic.graceDays ? ` +${lic.graceDays}d` : ""}`
    : "perpetual";
  const revoked = lic.status === "revoked";
  // Shown because it used to be invisible after mint: a cap typed at issue
  // time reached every holder with no trace of it on this desk.
  const cap = lic.maxAnalyses == null ? '<span class="muted">default</span>' : esc(lic.maxAnalyses);
  const actions = revoked ? "" : `
    <button class="secondary" data-extend="${esc(lic.id)}">Extend</button>
    <button class="secondary" data-cap="${esc(lic.id)}">Cap</button>
    ${lic.kind === "institution"
      ? `<button class="secondary" data-roster="${esc(lic.id)}">Roster</button>
         <button class="secondary" data-verify="${esc(lic.id)}">Verify</button>`
      : `<button class="secondary" data-device="${esc(lic.id)}">New device</button>`}
    <button class="secondary" data-history="${esc(lic.id)}">Devices</button>
    <button class="danger" data-revoke="${esc(lic.id)}">Revoke</button>`;
  return `
    <tr>
      <td class="mono">${esc(label)}</td>
      <td>${esc(lic.kind)}${lic.seating === "floating" ? " · shared" : ""}</td>
      <td>${seatSummary(lic)}</td>
      <td>${term}</td>
      <td>${cap}</td>
      <td><span class="pill ${revoked ? "off" : "ok"}">${esc(lic.status)}</span></td>
      <td class="muted">${esc(lic.domainLock || lic.emailLock || "—")}</td>
      <td class="actions">${actions}</td>
    </tr>`;
}

$("licenceRows").addEventListener("click", (ev) => {
  const btn = ev.target.closest("button");
  if (!btn) return;
  if (btn.dataset.extend) extendLicence(btn.dataset.extend);
  if (btn.dataset.cap) setAnalysisCap(btn.dataset.cap);
  if (btn.dataset.revoke) revokeLicence(btn.dataset.revoke);
  if (btn.dataset.roster) openRoster(btn.dataset.roster);
  if (btn.dataset.device) clearLicenceDevice(btn.dataset.device);
  if (btn.dataset.history) showDeviceHistory(btn.dataset.history);
  if (btn.dataset.verify) openVerified(btn.dataset.verify);
});

const labelOf = (id) => {
  const lic = licences.find((l) => l.id === id);
  return lic ? (lic.keyPrefix || lic.id.slice(0, 10)) : id.slice(0, 10);
};

async function extendLicence(id) {
  const date = window.prompt(
    `New expiry for ${labelOf(id)} (YYYY-MM-DD).\n\n` +
    "Everyone already on this licence is re-entitled immediately — " +
    "nobody re-activates and no new key is issued.",
  );
  if (!date) return;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date.trim())) {
    setStatus("Enter the date as YYYY-MM-DD.", true);
    return;
  }
  try {
    await api(`/v1/admin/licenses/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: JSON.stringify({ expiresAt: `${date.trim()}T23:59:59Z` }),
    });
    setStatus(`${labelOf(id)} extended to ${date.trim()}.`);
    loadLicences({ keepStatus: true });
  } catch (e) {
    setStatus(`Could not extend: ${e.message}`, true);
  }
}

async function setAnalysisCap(id) {
  const lic = licences.find((l) => l.id === id) || {};
  const current = lic.maxAnalyses == null ? "the licensed default" : lic.maxAnalyses;
  const raw = window.prompt(
    `Cloud analyses per person on ${labelOf(id)} (now ${current}).\n\n` +
    "Enter a number for a plan sold with a limit, or leave it empty to " +
    "remove the limit. Everyone on the licence gets the change at once.",
    lic.maxAnalyses == null ? "" : String(lic.maxAnalyses),
  );
  if (raw === null) return;
  const text = raw.trim();
  if (text && !/^\d+$/.test(text)) {
    setStatus("Enter a whole number, or leave it empty.", true);
    return;
  }
  try {
    await api(`/v1/admin/licenses/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: JSON.stringify(text ? { maxAnalyses: Number(text) } : { clearMaxAnalyses: true }),
    });
    setStatus(text
      ? `${labelOf(id)} now allows ${text} analyses per person.`
      : `${labelOf(id)} limit removed — holders get the licensed default.`);
    loadLicences({ keepStatus: true });
  } catch (e) {
    setStatus(`Could not change the limit: ${e.message}`, true);
  }
}

async function clearLicenceDevice(id) {
  // The support answer to "my phone died". Emptying the lock is the whole
  // change: the licence binds to whichever device signs in next, so
  // nothing is re-issued and nothing is typed at the customer's end.
  if (!window.confirm(
    `Unbind ${labelOf(id)} from the device it is on?\n\n` +
    "The next device they sign in on takes it. Their entitlement and " +
    "their analyses are untouched — this is not a revoke.",
  )) return;
  try {
    await api(`/v1/admin/licenses/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: JSON.stringify({ clearDeviceLock: true }),
    });
    setStatus(`${labelOf(id)} unbound — the next device to sign in takes it.`);
    loadLicences({ keepStatus: true });
  } catch (e) {
    setStatus(`Could not unbind: ${e.message}`, true);
  }
}

async function showDeviceHistory(id) {
  try {
    const data = await api(
      `/v1/admin/licenses/${encodeURIComponent(id)}/device-history?limit=30`,
    );
    const lines = (data.events || []).map((e) => {
      const prev = (e.detail && e.detail.previousDeviceId) || "";
      const next = (e.detail && e.detail.deviceId) || "";
      const who = e.uid || "—";
      return `${e.ts || "?"}  ${e.action}  by ${who}` +
        (prev ? `  left ${prev}` : "") +
        (next ? `  → ${next}` : "");
    });
    window.alert(
      lines.length
        ? `Device history for ${labelOf(id)}\n\n${lines.join("\n")}`
        : `No device moves recorded for ${labelOf(id)} yet.`,
    );
  } catch (e) {
    setStatus(`Could not load device history: ${e.message}`, true);
  }
}

/* ------------------------------------------------------- verified seats
 *
 * The roster is a statement of intent. `seatsUsed` moves the instant IT
 * revokes a seat, so the institution's own console can only ever report
 * what IT meant to happen. This asks the backend what the licence still
 * entitles, seat by seat, and names the difference.
 *
 * Two of the answers are ordinary and one is a fault, so every row says
 * which it is in words rather than leaving a code to be looked up.
 */
const SEAT_PROSE = {
  "": "On the roster and holding the licence.",
  never_claimed:
    "Invited but never signed in. Occupies a seat; entitles nobody.",
  still_licensed:
    "The revoke did not land — this account is still licensed. " +
    "Revoke the seat again to repair it.",
  no_checkin_since_revoke:
    "Revoked, but this account has not been back since. The device may " +
    "still be running on the licence it cached.",
  checked_in: "Revoked, and the account has been back since to hear it.",
  moved_on: "Revoked, and the account is on a different licence now.",
  no_account: "Revoked, and there is no account behind the seat.",
};

const SEAT_PILL = {
  active: "ok",
  revokedConfirmed: "off",
  revokedStillRunning: "warn",
};

async function openVerified(id) {
  $("verifyName").textContent = labelOf(id);
  $("verifyCard").hidden = false;
  $("verifyCard").dataset.licence = id;
  await loadVerified(id);
}

async function loadVerified(id) {
  $("verifySummary").textContent = "Checking every seat against its holder…";
  $("verifyRows").innerHTML = "";
  try {
    const report = await api(
      `/v1/admin/licenses/${encodeURIComponent(id)}/reconcile`,
    );
    verified[id] = report;
    $("verifySummary").innerHTML = verifiedSummary(report);
    $("verifyRows").innerHTML = (report.seats || []).map(verifiedRow).join("") ||
      '<tr><td colspan="4" class="muted">No seats on this licence yet.</td></tr>';
    // The Seats column now has a second number to show for this licence.
    renderLicences();
  } catch (e) {
    const msg = e.message === "kind_not_institution"
      ? "This is an individual licence: one holder, no roster, so there " +
        "are no two counts to compare."
      : `Could not check the seats: ${e.message}`;
    $("verifySummary").innerHTML = `<span class="err">${esc(msg)}</span>`;
  }
}

function verifiedSummary(report) {
  const c = report.counts;
  const parts = [
    `IT's roster says <strong>${report.intended}</strong> ` +
    `seat${report.intended === 1 ? " is" : "s are"} in use.`,
    `<strong>${report.entitled}</strong> account` +
    `${report.entitled === 1 ? " is" : "s are"} actually entitled.`,
  ];
  if (c.revokedStillRunning) {
    parts.push(
      `<span class="err"><strong>${c.revokedStillRunning}</strong> ` +
      `revoke${c.revokedStillRunning === 1 ? " has" : "s have"} not ` +
      `landed yet</span> — see below for which, and why.`,
    );
  } else {
    parts.push('<span class="ok">Every revoke has landed.</span>');
  }
  if (c.neverClaimed) {
    parts.push(
      `${c.neverClaimed} seat${c.neverClaimed === 1 ? "" : "s"} ` +
      "held by someone who has never signed in.",
    );
  }
  // A counter that disagrees with its own seats is a different fault from
  // anything the buckets describe, and worth saying out loud.
  if (report.intended !== report.intendedRecounted) {
    parts.push(
      `<span class="err">seatsUsed says ${report.intended} but there ` +
      `are ${report.intendedRecounted} live seats — the counter has ` +
      "drifted.</span>",
    );
  }
  return parts.join(" ");
}

function verifiedRow(s) {
  const prose = SEAT_PROSE[s.reason] ?? s.reason;
  const pill = SEAT_PILL[s.bucket] || "off";
  // Revoked seats are dated by the revoke; live ones have nothing to date.
  const seen = s.lastSeenAt ? esc(when(s.lastSeenAt)) : "never";
  return `
    <tr>
      <td>${esc(s.email || s.uid)}</td>
      <td><span class="pill ${pill}">${esc(s.status)}</span></td>
      <td>${esc(prose)}</td>
      <td class="muted">${seen}</td>
    </tr>`;
}

$("verifyClose").addEventListener("click", () => {
  $("verifyCard").hidden = true;
});

$("verifyReload").addEventListener("click", () => {
  const id = $("verifyCard").dataset.licence;
  if (id) loadVerified(id);
});

async function revokeLicence(id) {
  const label = labelOf(id);
  const lic = licences.find((l) => l.id === id) || {};
  const who = lic.kind === "institution"
    ? `every one of the ${lic.seatsUsed ?? 0} people on its roster`
    : "the person holding it";
  if (!window.confirm(
    `Revoking ${label} drops ${who} to demo immediately.\n\n` +
    "Their saved analyses are untouched — this withdraws entitlement, " +
    "it does not delete anything.\n\nContinue?",
  )) return;
  if (!confirmByTyping(label, "revoke this licence")) {
    setStatus("Revoke cancelled — the key did not match.");
    return;
  }
  await sendRevoke(id, label);
}

/**
 * The return leg of a revoke that went to Google for a fresh sign-in. The
 * operator already named who is affected and typed the key on the way out;
 * one plain confirmation here says which licence this page is about to
 * revoke, because a page acting on load without any gesture is a page that
 * revokes on a stale tab restored by the browser.
 */
async function resumeRevoke(id) {
  if (resumed) return;
  resumed = true;
  const label = labelOf(id);
  const lic = licences.find((l) => l.id === id);
  if (!lic) {
    setStatus(`Re-authenticated, but ${label} is no longer listed — nothing revoked.`, true);
    return;
  }
  if (lic.status === "revoked") {
    setStatus(`${label} is already revoked.`);
    return;
  }
  if (!window.confirm(`Re-authenticated. Revoke ${label} now?`)) {
    setStatus("Revoke cancelled.");
    return;
  }
  await sendRevoke(id, label);
}

async function sendRevoke(id, label) {
  try {
    await stepUpForRevoke({ action: "revoke", id });
    const out = await api(
      `/v1/admin/licenses/${encodeURIComponent(id)}/revoke`, { method: "POST" },
    );
    // Show it now, from the answer, rather than after the list round-trip.
    licences = licences.map((l) => (l.id === id ? { ...l, ...out, status: "revoked" } : l));
    renderLicences();
    setStatus(
      `${label} revoked — its holder is on demo from their next request.` +
      ($("showRevoked").checked ? "" : " Tick “Show revoked” to see it."),
    );
    loadLicences({ keepStatus: true });
    if (roster && roster.id === id) closeRoster();
  } catch (e) {
    if (e.message === ERR_CANCELLED) {
      setStatus("Revoke cancelled.");
      return;
    }
    setStatus(`Could not revoke: ${e.message}`, true);
  }
}

/* ------------------------------------------------------------- roster */

$("rosterClose").addEventListener("click", closeRoster);
function closeRoster() {
  roster = null;
  $("rosterCard").hidden = true;
}

async function openRoster(id) {
  roster = { id, label: labelOf(id) };
  $("rosterName").textContent = roster.label;
  $("rosterCard").hidden = false;
  await loadRoster();
}

async function loadRoster() {
  if (!roster) return;
  try {
    const data = await api(
      `/v1/institutions/licenses/${encodeURIComponent(roster.id)}/seats`,
    );
    const seats = (data.seats || []).map(seatRow);
    const invites = (data.invites || []).map(inviteRow);
    $("rosterRows").innerHTML = seats.concat(invites).join("") ||
      '<tr><td colspan="5" class="muted">Nobody on this licence yet.</td></tr>';
  } catch (e) {
    // Semper staff are not automatically institution admins: the seat
    // routes check adminEmails, not role=admin. Say so plainly rather
    // than showing a bare 404.
    const msg = e.message === "license_not_found"
      ? "Your account is not listed as an IT contact on this licence, " +
        "so its roster is not visible here."
      : `Could not load the roster: ${e.message}`;
    $("rosterRows").innerHTML =
      `<tr><td colspan="5" class="err">${esc(msg)}</td></tr>`;
  }
}

function seatRow(s) {
  return `
    <tr>${seatCells(s)}
      <td class="actions">
        <button class="secondary" data-device-seat="${esc(s.uid)}">New device</button>
        <button class="danger" data-seat="${esc(s.uid)}">Remove</button>
      </td>
    </tr>`;
}

function inviteRow(i) {
  return `
    <tr>${inviteCells(i)}
      <td class="actions">
        <button class="danger" data-invite="${esc(i.id)}">Withdraw</button>
      </td>
    </tr>`;
}

$("addMember").addEventListener("click", async () => {
  const email = $("memberEmail").value.trim();
  if (!roster || !email) return;
  $("rosterHint").textContent = "";
  try {
    const out = await api(
      `/v1/institutions/licenses/${encodeURIComponent(roster.id)}/seats`,
      { method: "POST", body: JSON.stringify({ email }) },
    );
    $("memberEmail").value = "";
    $("rosterHint").textContent = out.seat
      ? "Added — they are entitled now."
      : "Invited — they join the moment they first sign in.";
    loadRoster();
    loadLicences({ keepStatus: true });
  } catch (e) {
    $("rosterHint").textContent = {
      invite_exists: "That address is already promised to a different licence.",
      license_seats_exhausted: "This licence has no seats left.",
      license_seat_disabled: "That seat is on hold — re-enable it instead.",
    }[e.message] || `Could not add: ${e.message}`;
  }
});

$("rosterRows").addEventListener("click", async (ev) => {
  const btn = ev.target.closest("button");
  if (!btn || !roster) return;
  const base = `/v1/institutions/licenses/${encodeURIComponent(roster.id)}`;
  try {
    if (btn.dataset.seat) {
      const row = btn.closest("tr");
      const label = row.cells[0].textContent;
      if (!window.confirm(
        `Remove ${label} from ${roster.label}?\n\n` +
        "They drop to demo and their seat is freed. Their analyses stay.",
      )) return;
      await api(`${base}/seats/${encodeURIComponent(btn.dataset.seat)}`, { method: "DELETE" });
    } else if (btn.dataset.deviceSeat) {
      // The staff-tier seat unbind, not the institution one this card
      // reads from: Semper is not in every licence's adminEmails, and a
      // support request must not depend on that.
      await api(
        `/v1/admin/licenses/${encodeURIComponent(roster.id)}` +
        `/seats/${encodeURIComponent(btn.dataset.deviceSeat)}/device`,
        { method: "PATCH" },
      );
    } else if (btn.dataset.invite) {
      await api(`${base}/invites/${encodeURIComponent(btn.dataset.invite)}`, { method: "DELETE" });
    } else return;
    loadRoster();
    loadLicences({ keepStatus: true });
  } catch (e) {
    $("rosterHint").textContent = `Could not update: ${e.message}`;
  }
});

/* ------------------------------------------------------------- people */

$("reloadUsers").addEventListener("click", loadUsers);

async function loadUsers() {
  try {
    const data = await api("/v1/admin/users?status=PENDING&limit=50");
    const rows = data.users || [];
    $("userRows").innerHTML = rows.length
      ? rows.map((u) => `
          <tr>
            <td>${esc(u.email || u.uid)}</td>
            <td>${esc(u.displayName || "—")}</td>
            <td class="muted">${u.activeDeviceId ? esc(u.activeDeviceId.slice(0, 10)) + "…" : "—"}</td>
            <td class="actions">
              <button data-approve="${esc(u.uid)}">Approve</button>
            </td>
          </tr>`).join("")
      : '<tr><td colspan="4" class="muted">Nobody waiting.</td></tr>';
  } catch (e) {
    $("userRows").innerHTML =
      `<tr><td colspan="4" class="err">Could not load: ${esc(e.message)}</td></tr>`;
  }
}

$("userRows").addEventListener("click", async (ev) => {
  const btn = ev.target.closest("button[data-approve]");
  if (!btn) return;
  btn.disabled = true;
  try {
    await api(`/v1/admin/users/${encodeURIComponent(btn.dataset.approve)}/approve`,
              { method: "POST" });
    setStatus("Approved.");
    loadUsers();
  } catch (e) {
    setStatus(`Could not approve: ${e.message}`, true);
    btn.disabled = false;
  }
});

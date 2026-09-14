import {
  requireSignIn, api, setStatus, esc, when,
  hasSecondFactor, beginTotpEnrolment, confirmByTyping,
  stepUpForRevoke, ERR_CANCELLED,
} from "../auth.js";

const $ = (id) => document.getElementById(id);
let licences = [];
let roster = null;      // { id, label } of the licence whose roster is open
// Reconciliation reports, keyed by licence id, kept only for this page
// load. Filled on demand: the read costs one user lookup per seat, so it
// is never run for the whole table at once.
let verified = {};
let enrolment = null;

requireSignIn((user) => {
  $("signedOut").hidden = true;
  renderFactorState(user);
  loadLicences();
  loadUsers();
});

/* ------------------------------------------------------ second factor */

function renderFactorState(user) {
  const enrolled = hasSecondFactor(user);
  const pill = $("mfaPill");
  pill.hidden = false;
  pill.textContent = enrolled ? "2FA on" : "2FA off";
  pill.className = `pill ${enrolled ? "ok" : "warn"}`;
  $("enrolCard").hidden = enrolled;
  // Writes are refused by the backend without a factor; disabling them
  // here too means the operator finds out before typing a whole form
  // rather than after submitting it.
  for (const id of ["mint", "addMember"]) $(id).disabled = !enrolled;
  $("mintHint").textContent = enrolled ? "" : "Enrol a second factor first.";
}

$("enrolStart").addEventListener("click", async () => {
  try {
    setStatus("Re-authenticating…");
    enrolment = await beginTotpEnrolment();
    $("enrolSecret").textContent = enrolment.secret;
    $("enrolAccount").textContent = $("who").textContent || "your Semper account";
    $("enrolStep").hidden = false;
    setStatus("");
  } catch (e) {
    if (e.message !== ERR_CANCELLED) setStatus(`Could not start: ${e.code || e.message}`, true);
    else setStatus("");
  }
});

$("enrolFinish").addEventListener("click", async () => {
  const code = $("enrolCode").value.trim();
  if (!enrolment || !code) return;
  try {
    await enrolment.finish(code);
    setStatus("Two-factor authentication is on.");
    window.location.reload();
  } catch (e) {
    setStatus(`That code was not accepted: ${e.code || e.message}`, true);
  }
});

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
    if (out.inviteError) {
      // The licence exists and its key still redeems it, but the address
      // is already promised another licence — a conflict only ops can
      // resolve, and one that would otherwise pass silently.
      setStatus(
        `Issued, but ${$("emailLock").value} is already promised another ` +
        `licence (${out.inviteError}) — it will not attach at sign-in.`,
        true,
      );
    } else {
      setStatus(out.key ? "Issued. Copy the key below." : "Institution licence issued.");
    }
    loadLicences();
  } catch (e) {
    setStatus(mintError(e.message), true);
  } finally {
    $("mint").disabled = !hasSecondFactor();
  }
});

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

$("reload").addEventListener("click", loadLicences);
$("filter").addEventListener("input", renderLicences);

async function loadLicences() {
  setStatus("Loading…");
  // Every caller of this is either a page load or something that just
  // changed a licence or a seat, so any reconciliation already on screen
  // describes a state that no longer exists.
  const wasOpen = $("verifyCard").hidden ? "" : $("verifyCard").dataset.licence;
  verified = {};
  try {
    const data = await api("/v1/admin/licenses?limit=200");
    licences = data.licenses || [];
    const page = data.page || {};
    $("licencePaging").textContent = page.hasMore
      ? `Showing the first ${page.count}; more exist.`
      : `${page.count} licence(s).`;
    renderLicences();
    setStatus("");
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

function renderLicences() {
  const q = $("filter").value.trim().toLowerCase();
  const rows = licences.filter((l) =>
    !q || [l.keyPrefix, l.domainLock, l.emailLock, l.note]
      .some((v) => (v || "").toLowerCase().includes(q)));
  $("licenceRows").innerHTML = rows.length
    ? rows.map(licenceRow).join("")
    : '<tr><td colspan="7" class="muted">Nothing matches.</td></tr>';
}

function seatSummary(lic) {
  if (lic.kind !== "institution") return "1";
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
  const actions = revoked ? "" : `
    <button class="secondary" data-extend="${esc(lic.id)}">Extend</button>
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
      <td><span class="pill ${revoked ? "off" : "ok"}">${esc(lic.status)}</span></td>
      <td class="muted">${esc(lic.domainLock || lic.emailLock || "—")}</td>
      <td class="actions">${actions}</td>
    </tr>`;
}

$("licenceRows").addEventListener("click", (ev) => {
  const btn = ev.target.closest("button");
  if (!btn) return;
  if (btn.dataset.extend) extendLicence(btn.dataset.extend);
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
    loadLicences();
  } catch (e) {
    setStatus(`Could not extend: ${e.message}`, true);
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
    loadLicences();
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
  try {
    await stepUpForRevoke();
    await api(`/v1/admin/licenses/${encodeURIComponent(id)}/revoke`, { method: "POST" });
    setStatus(`${label} revoked.`);
    loadLicences();
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
  const held = s.leaseExpiresAt
    ? `until ${esc(when(s.leaseExpiresAt))}`
    : '<span class="muted">not holding one</span>';
  return `
    <tr>
      <td>${esc(s.email || s.uid)}</td>
      <td><span class="pill ${s.status === "active" ? "ok" : "off"}">${esc(s.status)}</span></td>
      <td class="muted">${s.deviceIdLock ? esc(s.deviceIdLock.slice(0, 10)) + "…" : "—"}</td>
      <td>${held}</td>
      <td class="actions">
        <button class="secondary" data-device-seat="${esc(s.uid)}">New device</button>
        <button class="danger" data-seat="${esc(s.uid)}">Remove</button>
      </td>
    </tr>`;
}

function inviteRow(i) {
  return `
    <tr>
      <td>${esc(i.email)}</td>
      <td><span class="pill warn">invited</span></td>
      <td class="muted">—</td>
      <td class="muted">joins at first sign-in</td>
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
    loadLicences();
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
    loadLicences();
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

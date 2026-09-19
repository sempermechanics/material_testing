import { requireSignIn, api, setStatus, esc, when } from "../auth.js";

let licenseId = "";

const $ = (id) => document.getElementById(id);

requireSignIn(() => {
  $("signedOut").hidden = true;
  // Deep-link support: ?license=... so IT can bookmark their own licence
  // rather than pasting the id every time.
  const fromUrl = new URLSearchParams(location.search).get("license");
  if (fromUrl) {
    $("licenseId").value = fromUrl;
    load();
  }
});

$("load").addEventListener("click", load);
$("licenseId").addEventListener("keydown", (e) => { if (e.key === "Enter") load(); });
$("add").addEventListener("click", addMember);
$("addEmail").addEventListener("keydown", (e) => { if (e.key === "Enter") addMember(); });

async function load() {
  licenseId = $("licenseId").value.trim();
  if (!licenseId) return;
  setStatus("Loading…");
  try {
    const data = await api(`/v1/institutions/licenses/${encodeURIComponent(licenseId)}/seats`);
    render(data);
    setStatus("");
  } catch (e) {
    $("rosterCard").hidden = true;
    $("summary").innerHTML = "";
    // The backend answers 404 identically for a licence that does not
    // exist and one you do not administer, so that probing ids tells you
    // nothing about other institutions. Say so rather than implying the id
    // was simply mistyped.
    setStatus(
      e.message === "license_not_found"
        ? "No licence with that id that you administer."
        : `Could not load: ${e.message}`,
      true,
    );
  }
}

function render(data) {
  const lic = data.license || {};
  const floating = lic.seating === "floating";
  const cap = lic.maxSeats == null ? "unlimited" : lic.maxSeats;
  const inUse = floating ? (lic.leasesActive ?? 0) : (lic.seatsUsed ?? 0);

  $("summary").innerHTML = `
    <p>
      <span class="pill">${esc(lic.keyPrefix || "licence")}</span>
      <span class="pill">${floating ? "shared seats" : "one seat each"}</span>
      <span class="pill ${lic.status === "revoked" ? "off" : "ok"}">${esc(lic.status || "")}</span>
      ${lic.expiresAt ? `<span class="pill warn">expires ${esc(when(lic.expiresAt))}</span>` : ""}
    </p>
    <p class="muted">
      ${floating
        ? `<strong>${inUse} of ${cap}</strong> seats in use right now, across
           ${data.seats.length} people on the roster.`
        : `<strong>${inUse} of ${cap}</strong> seats taken.`}
    </p>`;

  $("rosterHelp").textContent = floating
    ? "Everyone here may use Semper, but only the number of seats above at " +
      "the same time. Someone without a seat keeps their saved work and " +
      "gets one as soon as a colleague finishes."
    : "Everyone here is licensed. Removing someone frees their seat for " +
      "another member.";

  // Invites belong in the same list: to whoever manages the roster this
  // is one question — "who is on this licence" — even though only a seat
  // holds a uid and counts against the cap.
  $("rows").innerHTML =
    data.seats.map(seatRow).concat((data.invites || []).map(inviteRow)).join("") ||
    '<tr><td colspan="5" class="muted">Nobody on this licence yet.</td></tr>';
  $("rosterCard").hidden = false;
  for (const el of document.querySelectorAll("[data-act]")) {
    el.addEventListener("click", () => act(el.dataset.act, el.dataset.uid));
  }
  for (const el of document.querySelectorAll("[data-invite]")) {
    el.addEventListener("click", () => withdraw(el.dataset.invite));
  }
}

function seatRow(seat) {
  const holds = seat.leaseExpiresAt && new Date(seat.leaseExpiresAt) > new Date();
  const status = {
    active: '<span class="pill ok">active</span>',
    disabled: '<span class="pill warn">on hold</span>',
    revoked: '<span class="pill off">removed</span>',
  }[seat.status] || esc(seat.status);
  return `
    <tr>
      <td>${esc(seat.email || seat.uid)}</td>
      <td>${status}</td>
      <td>${holds ? `<span class="pill ok">until ${esc(when(seat.leaseExpiresAt))}</span>`
                   : '<span class="pill off">—</span>'}</td>
      <td class="muted">${seat.deviceIdLock ? esc(seat.deviceIdLock.slice(0, 10)) + "…" : "not yet"}</td>
      <td class="actions">
        ${seat.deviceIdLock
          ? `<button class="secondary" data-act="clear" data-uid="${esc(seat.uid)}">New device</button>`
          : ""}
        ${seat.status === "active"
          ? `<button class="secondary" data-act="hold" data-uid="${esc(seat.uid)}">Hold</button>`
          : `<button class="secondary" data-act="unhold" data-uid="${esc(seat.uid)}">Resume</button>`}
        <button class="danger" data-act="remove" data-uid="${esc(seat.uid)}">Remove</button>
      </td>
    </tr>`;
}

function inviteRow(invite) {
  return `
    <tr>
      <td>${esc(invite.email)}</td>
      <td><span class="pill warn">invited</span></td>
      <td><span class="pill off">—</span></td>
      <td class="muted">joins at first sign-in</td>
      <td class="actions">
        <button class="danger" data-invite="${esc(invite.id)}">Withdraw</button>
      </td>
    </tr>`;
}

async function withdraw(inviteId) {
  if (!confirm("Withdraw this invitation? Nobody has claimed it yet.")) return;
  setStatus("Withdrawing…");
  try {
    await api(
      `/v1/institutions/licenses/${encodeURIComponent(licenseId)}/invites/${encodeURIComponent(inviteId)}`,
      { method: "DELETE" },
    );
    await load();
  } catch (e) {
    setStatus(`Could not withdraw: ${e.message}`, true);
  }
}

async function addMember() {
  const email = $("addEmail").value.trim();
  if (!email) return;
  setStatus("Adding…");
  try {
    const out = await api(
      `/v1/institutions/licenses/${encodeURIComponent(licenseId)}/seats`,
      { method: "POST", body: JSON.stringify({ email }) },
    );
    $("addEmail").value = "";
    await load();
    // Someone who has never opened Semper is invited rather than refused,
    // so say which of the two happened — "added" and "invited" mean
    // different things to whoever is chasing them.
    setStatus(
      out.seat
        ? `${email} is on the licence now.`
        : `${email} has not signed in yet — invited. They join automatically ` +
          "the first time they do.",
    );
  } catch (e) {
    setStatus(
      e.message === "invite_exists"
        ? `${email} is already promised a place on a different licence.`
        : `Could not add ${email}: ${e.message}`,
      true,
    );
  }
}

const ACTIONS = {
  clear: { method: "PATCH", body: { clearDeviceLock: true }, verb: "Unlocking" },
  hold: { method: "PATCH", body: { enabled: false }, verb: "Holding" },
  unhold: { method: "PATCH", body: { enabled: true }, verb: "Resuming" },
  remove: { method: "DELETE", verb: "Removing" },
};

async function act(action, uid) {
  const spec = ACTIONS[action];
  if (action === "remove" && !confirm("Remove this member? Their saved analyses stay untouched.")) {
    return;
  }
  setStatus(`${spec.verb}…`);
  try {
    await api(
      `/v1/institutions/licenses/${encodeURIComponent(licenseId)}/seats/${encodeURIComponent(uid)}`,
      { method: spec.method, ...(spec.body ? { body: JSON.stringify(spec.body) } : {}) },
    );
    await load();
  } catch (e) {
    setStatus(`Could not complete that: ${e.message}`, true);
  }
}

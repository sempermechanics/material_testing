/* Pure helpers shared by the console pages: no DOM, no Firebase, so
 * `node --test` can run them (see util.test.mjs).
 *
 * The roster cells live here because the operator desk and the institution
 * page each had their own copy, and the copies drifted: the desk showed an
 * expired floating lease as "until <past time>" (TD-50). Both pages now render
 * the same four cells and add only their own action buttons.
 */

/** Escape text before it reaches innerHTML. Emails and names come from users. */
export function esc(value) {
  return String(value ?? "").replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );
}

/** A short, readable rendering of an ISO instant, or an em dash for null. */
export function when(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleString();
}

/**
 * A licence end as the calendar day it was set to, or an em dash for null.
 *
 * Licence ends are stored as the last second of the chosen day in UTC
 * (`YYYY-MM-DDT23:59:59Z`), so they are read back in UTC. `when` renders the
 * local time instead, and east of UTC that moved the day picked: an expiry set
 * for 30 June showed as 1 July, 05:29.
 */
export function day(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? "—"
    : d.toLocaleDateString(undefined, {
      timeZone: "UTC", year: "numeric", month: "short", day: "numeric",
    });
}

/**
 * Where a licence is in its life: "revoked", "expired" (past its grace too,
 * so it grants nothing), "in grace" (past its end, still fully usable), or
 * its stored status. The stored status never changes when a licence runs out,
 * so a lapsed licence used to show a green "redeemed" pill.
 */
export function licenceState(lic, now = Date.now()) {
  if (lic.status === "revoked") return "revoked";
  const ends = Date.parse(lic.expiresAt || "");
  const graceEnds = Date.parse(lic.graceEndsAt || lic.expiresAt || "");
  if (Number.isFinite(graceEnds) && graceEnds <= now) return "expired";
  if (Number.isFinite(ends) && ends <= now) return "in grace";
  return lic.status || "unused";
}

/** The pill for `licenceState`. */
export function licenceStatePill(lic, now = Date.now()) {
  const state = licenceState(lic, now);
  const tone = { revoked: "off", expired: "off", "in grace": "warn" }[state] || "ok";
  return `<span class="pill ${tone}">${esc(state)}</span>`;
}

/** Whether a floating lease ending at `iso` is still held at `now` (ms). */
export function leaseHeld(iso, now = Date.now()) {
  const end = Date.parse(iso || "");
  return Number.isFinite(end) && end > now;
}

const SEAT_STATUS = {
  active: '<span class="pill ok">active</span>',
  disabled: '<span class="pill warn">on hold</span>',
  revoked: '<span class="pill off">removed</span>',
};

/**
 * Member, Status, Seat and Device cells for one seat. The caller appends its
 * own actions cell, which is the only part the two pages do differently.
 */
export function seatCells(seat, now = Date.now()) {
  const status = SEAT_STATUS[seat.status] || esc(seat.status);
  const lease = leaseHeld(seat.leaseExpiresAt, now)
    ? `<span class="pill ok">until ${esc(when(seat.leaseExpiresAt))}</span>`
    : '<span class="pill off">—</span>';
  const device = seat.deviceIdLock ? `${esc(seat.deviceIdLock.slice(0, 10))}…` : "not yet";
  return `
      <td>${esc(seat.email || seat.uid)}</td>
      <td>${status}</td>
      <td>${lease}</td>
      <td class="muted">${device}</td>`;
}

/** The same four cells for a pending invite: nobody holds anything yet. */
export function inviteCells(invite) {
  return `
      <td>${esc(invite.email)}</td>
      <td><span class="pill warn">invited</span></td>
      <td><span class="pill off">—</span></td>
      <td class="muted">joins at first sign-in</td>`;
}

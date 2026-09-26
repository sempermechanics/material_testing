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

/**
 * A backend error detail split into its code and what follows the code.
 *
 * Some codes carry a value after a colon — `session_quota_exceeded: 25/25 …`,
 * `device_change_too_soon: <ISO instant>` — and an instant has colons of its
 * own, so only the first one separates. Pages match on `code`.
 */
export function errorDetail(detail) {
  const text = String(detail ?? "");
  const at = text.indexOf(":");
  return at < 0
    ? { code: text.trim(), rest: "" }
    : { code: text.slice(0, at).trim(), rest: text.slice(at + 1).trim() };
}

/**
 * The staff desk's licence-list URL. Demo keys and revoked licences are
 * left out by the backend unless asked for, so a page holds the licences
 * anyone sold instead of one Demo key per account.
 */
export function licenceListPath({ limit, showDemo = false, showRevoked = false, pageToken = "", q = "" }) {
  const params = new URLSearchParams({ limit: String(limit) });
  if (showDemo) params.set("include_demo", "true");
  if (!showRevoked) params.set("include_revoked", "false");
  if (q) params.set("q", q);
  else if (pageToken) params.set("page_token", pageToken);
  return `/v1/admin/licenses?${params}`;
}

/**
 * Whether the filter text is something the backend can look up: an email
 * address, a domain, or a key prefix. Those are exact matches on any page;
 * anything else only filters the rows already loaded.
 */
export function searchableLicenceText(text) {
  const t = String(text ?? "").trim();
  if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(t)) return true;
  if (/^semp-[a-z0-9]{4}/i.test(t)) return true;
  return /^[a-z0-9-]+(\.[a-z0-9-]+)+$/i.test(t);
}

/**
 * `list` with `lic` in place of the row with its id, or at the top when it
 * is new. A change refreshes its own row this way; the desk used to reload
 * the first page after every change and drop every page loaded after it.
 */
export function upsertLicence(list, lic) {
  if (!lic || !lic.id) return list;
  const at = list.findIndex((l) => l.id === lic.id);
  if (at < 0) return [lic, ...list];
  const next = list.slice();
  next[at] = { ...list[at], ...lic };
  return next;
}

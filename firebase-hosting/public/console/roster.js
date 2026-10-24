/* One institution licence's roster, as a table with its actions — the same on
 * the institution page (IT, `/v1/institutions/licenses/{id}`) and on the
 * operator desk (Semper staff, `/v1/admin/licenses/{id}`). The two used to
 * carry their own copies, and they drifted: the desk offered fewer actions
 * and called routes that refused staff (TD-191).
 *
 * The page owns the elements and what goes around the table (the licence
 * summary, where messages land); this module owns the rows, what each button
 * sends, and what a refusal says.
 */
import { api } from "./api.js";
import { confirmInPage } from "./dialogs.js";
import { explain } from "./messages.js";
import { esc, seatCells, inviteCells } from "./util.js";

/**
 * The roster of the licence at `base`: its summary, every seat and the
 * outstanding invites. The listing is paged; this follows `nextPageToken`
 * to the end, and invites come with the first page.
 */
export async function fetchRoster(base) {
  const first = await api(`${base}/seats`);
  let seats = first.seats || [];
  let token = first.page && first.page.nextPageToken;
  while (token) {
    const next = await api(`${base}/seats?page_token=${encodeURIComponent(token)}`);
    seats = seats.concat(next.seats || []);
    token = next.page && next.page.nextPageToken;
  }
  return { license: first.license || {}, seats, invites: first.invites || [] };
}

/**
 * One member's row, with what can be done to them. Each button carries the
 * member's address, so its confirmation and its name for a screen reader say
 * who it acts on: on a long roster every row's button reads "Remove".
 */
export function seatRow(seat) {
  // A removed member holds nothing to hold, resume or unlock. Resume used to
  // be offered here and reactivated the seat without taking a slot back;
  // adding the address again is how someone comes back.
  if (seat.status === "revoked") {
    return `
    <tr>${seatCells(seat)}
      <td class="actions muted">add again to restore</td>
    </tr>`;
  }
  const who = esc(seat.email || seat.uid);
  const on = `data-uid="${esc(seat.uid)}" data-email="${who}"`;
  return `
    <tr>${seatCells(seat)}
      <td class="actions">
        ${seat.deviceIdLock || seat.deviceIdLockMaterialTesting
          ? `<button class="secondary" data-act="clear" ${on} aria-label="New device for ${who}">New device</button>`
          : ""}
        ${seat.status === "active"
          ? `<button class="secondary" data-act="hold" ${on} aria-label="Hold ${who}">Hold</button>`
          : `<button class="secondary" data-act="unhold" ${on} aria-label="Resume ${who}">Resume</button>`}
        <button class="danger" data-act="remove" ${on} aria-label="Remove ${who}">Remove</button>
      </td>
    </tr>`;
}

/** A pending invitation's row. */
export function inviteRow(invite) {
  return `
    <tr>${inviteCells(invite)}
      <td class="actions">
        <button class="danger" data-invite="${esc(invite.id)}" data-email="${esc(invite.email)}"
                aria-label="Withdraw the invitation to ${esc(invite.email)}">Withdraw</button>
      </td>
    </tr>`;
}

const ACTIONS = {
  clear: { method: "PATCH", body: { clearDeviceLock: true }, verb: "Unlocking" },
  hold: { method: "PATCH", body: { enabled: false }, verb: "Holding" },
  unhold: { method: "PATCH", body: { enabled: true }, verb: "Resuming" },
  remove: { method: "DELETE", verb: "Removing" },
};

/** The confirmation an action asks for first, naming the member, or none. */
const ASK = {
  clear: (who) => ({
    title: `New device for ${who}`,
    message: `Unbind ${who} from the phone it is on?

` +
      "The next phone they sign in on takes the seat. Their seat and their " +
      "saved analyses are untouched.",
    confirm: "Unbind",
  }),
  remove: (who) => ({
    title: `Remove ${who}`,
    message: `Remove ${who} from this licence?

` +
      "They drop to the demo the next time their phone checks in. Their " +
      "saved analyses stay untouched.",
    confirm: "Remove",
  }),
};

const ACT_ERRORS = {
  seat_revoked: "That member was removed. Add their address again to restore them.",
  license_revoked: "This licence has been revoked, so its seats cannot be changed.",
  seat_busy: "That seat changed while you were acting on it. Try again.",
};

/**
 * Wire a roster table. `base()` is the open licence's path, "" when none is;
 * `report(message, isError)` shows what happened; `reload()` reads the
 * licence again after a change (the page re-renders through `render`), and
 * `retry()` (default `reload`) answers the Retry of a failed load.
 * Returns `render(seats, invites)`, which fills `rows`.
 */
export function wireRoster({ rows, email, add, base, report, reload, retry = reload }) {
  // Each action takes the roster's path before it asks, so an answer given after
  // another roster was opened (or this one closed) still goes to the one asked on.
  async function act(action, uid, who) {
    const spec = ACTIONS[action];
    const root = base();
    if (!root) return;
    if (ASK[action] && !(await confirmInPage(ASK[action](who || uid)))) return;
    report(`${spec.verb}…`);
    try {
      await api(`${root}/seats/${encodeURIComponent(uid)}`,
        { method: spec.method, ...(spec.body ? { body: JSON.stringify(spec.body) } : {}) });
      await reload();
      refocus((b) => b.dataset.uid === uid);
    } catch (e) {
      report(explain(e, ACT_ERRORS, (text) => `Could not complete that: ${text}`), true);
    }
  }

  async function withdraw(inviteId, who) {
    const root = base();
    if (!root) return;
    if (!(await confirmInPage({
      title: "Withdraw invitation",
      message: `Withdraw the invitation to ${who || "this address"}?

Nobody has claimed it yet.`,
      confirm: "Withdraw",
    }))) return;
    report("Withdrawing…");
    try {
      await api(`${root}/invites/${encodeURIComponent(inviteId)}`, { method: "DELETE" });
      await reload();
      refocus(() => false);
    } catch (e) {
      report(explain(e, {}, (text) => `Could not withdraw: ${text}`), true);
    }
  }

  /**
   * After a change the rows are rebuilt and the button that had focus is
   * gone: put focus on the same member's row again (`mine` picks its
   * button), or on the Add box when they left the roster, so a keyboard user
   * carries on where they were rather than from the top of the page.
   */
  function refocus(mine) {
    const again = Array.from(rows.querySelectorAll("button")).find(mine);
    (again || email).focus();
  }

  async function addMember() {
    const address = email.value.trim();
    if (!address || !base()) return;
    report("Adding…");
    try {
      const out = await api(`${base()}/seats`,
        { method: "POST", body: JSON.stringify({ email: address }) });
      email.value = "";
      await reload();
      // Someone who has never opened Semper is invited rather than refused,
      // so say which of the two happened — "added" and "invited" mean
      // different things to whoever is chasing them.
      report(out.seat
        ? `${address} is on the licence now.`
        : `${address} has not signed in yet — invited. They join automatically ` +
          "the first time they do.");
    } catch (e) {
      report(explain(e, {
        invite_exists: `${address} is already promised a place on a different licence.`,
        // One licence per person. Which licence is not the roster's to say;
        // Semper support can move them.
        member_already_licensed: `${address} already has a Semper licence of their own. ` +
          "Ask Semper support to move them onto this one.",
        license_seats_exhausted: "This licence has no seats left.",
        license_seat_disabled: "That seat is on hold — resume it instead.",
      }, (text) => `Could not add ${address}: ${text}`), true);
    }
  }

  // One listener on the table body: its rows are rebuilt on every load.
  rows.addEventListener("click", (ev) => {
    const btn = ev.target.closest("button");
    if (!btn) return;
    if (btn.dataset.act) act(btn.dataset.act, btn.dataset.uid, btn.dataset.email);
    else if (btn.dataset.invite) withdraw(btn.dataset.invite, btn.dataset.email);
    else if ("retry" in btn.dataset) retry();
  });
  add.addEventListener("click", addMember);
  email.addEventListener("keydown", (e) => { if (e.key === "Enter") addMember(); });

  return function render(seats, invites) {
    rows.innerHTML = seats.map(seatRow).concat(invites.map(inviteRow)).join("") ||
      '<tr><td colspan="5" class="muted">Nobody on this licence yet.</td></tr>';
  };
}

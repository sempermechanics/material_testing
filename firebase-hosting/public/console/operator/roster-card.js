/* The roster card: one institution licence's members, through the shared
 * roster component (`../roster.js`) on the staff routes.
 */
import { esc } from "../auth.js";
import { wireRoster, fetchRoster } from "../roster.js";
import { $, labelOf } from "./state.js";
import { refreshLicence } from "./licences.js";

let roster = null;      // { id, label } of the licence whose roster is open

// The staff-tier roster routes: any institution licence, whether or not
// this account is among its adminEmails (TD-191).
const rosterBase = () => (roster ? `/v1/admin/licenses/${encodeURIComponent(roster.id)}` : "");

function rosterReport(message, isError = false) {
  $("rosterHint").textContent = message;
  $("rosterHint").className = isError ? "muted err" : "muted";
}

const renderRoster = wireRoster({
  rows: $("rosterRows"),
  email: $("memberEmail"),
  add: $("addMember"),
  base: rosterBase,
  report: rosterReport,
  reload: async () => {
    if (!roster) return;
    const id = roster.id;
    await loadRoster();
    refreshLicence(id);
  },
});

$("rosterClose").addEventListener("click", closeRoster);
function closeRoster() {
  roster = null;
  $("rosterCard").hidden = true;
}

/** Close the card if it shows licence `id`, which was just revoked or deleted. */
export function closeRosterOf(id) {
  if (roster && roster.id === id) closeRoster();
}

export async function openRoster(id) {
  roster = { id, label: labelOf(id) };
  $("rosterName").textContent = roster.label;
  $("rosterCard").hidden = false;
  rosterReport("");
  await loadRoster();
}

async function loadRoster() {
  if (!roster) return;
  try {
    const data = await fetchRoster(rosterBase());
    renderRoster(data.seats, data.invites);
  } catch (e) {
    $("rosterRows").innerHTML =
      `<tr><td colspan="5" class="err">${esc(`Could not load the roster: ${e.message}`)}</td></tr>`;
  }
}

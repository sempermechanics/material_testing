/* Issue upgrade (ADR-033): a new licence for the same app, issued to the
 * holder of an individual licence that names its app. It carries the old
 * licence's terms unless the dialog names new ones: more phones, another
 * end, a cap, or a move to an institution. The holder keeps the old licence
 * until they tap Activate on the new one in the app, which revokes the old.
 *
 * Apply now is staff's fallback, for a holder whose app has no Activate: it
 * moves them onto the issued licence at once. The licence list does not say
 * which licence an upgrade waits on, so Apply now is offered here, from the
 * issue's answer, or from the refusal that names an upgrade already waiting.
 */
import { api } from "../api.js";
import { confirmInPage } from "../dialogs.js";
import { setStatus } from "../status.js";
import { emailList } from "../util.js";
import { explain } from "../messages.js";
import { $, findLicence, labelOf } from "./state.js";
import { showLicence, refreshLicence } from "./licences.js";

/** Sentences for the refusals of an issue or an apply. */
const SENTENCES = {
  license_not_upgradable: "Only a Licensed individual licence for one app upgrades this way. " +
    "One from before the split moves with To institution.",
  license_revoked: "This licence is revoked.",
  license_not_found: "That licence no longer exists. Refresh the table.",
  convert_domain_mismatch: "The holder's address is not on that domain.",
  expiry_in_past: "That date has already passed.",
  no_pending_upgrade: "That upgrade is no longer waiting: the holder has activated it already, " +
    "or it was revoked.",
};

let upgrading = null; // the licence being upgraded
let pending = ""; // the issued licence Apply now moves the holder onto

export function openUpgrade(id) {
  const lic = findLicence(id);
  if (!lic) return;
  upgrading = lic;
  pending = "";
  $("upgradeName").textContent = labelOf(id);
  $("upgradeKind").value = "individual";
  $("upgradeMaxDevices").value = "";
  $("upgradeMaxDevices").placeholder = String(lic.maxDevices ?? 1);
  $("upgradeExpiry").value = "";
  $("upgradePerpetual").checked = false;
  $("upgradeCap").value = "";
  $("upgradeCap").placeholder = lic.maxAnalyses == null ? "Licensed default" : String(lic.maxAnalyses);
  $("upgradeNote").value = "";
  $("upgradeNote").placeholder = lic.note || "";
  const email = lic.emailLock || "";
  $("upgradeDomain").value = email.includes("@") ? email.split("@")[1] : "";
  $("upgradeAdmins").value = "";
  $("upgradeSeats").value = "";
  $("upgradeFields").hidden = false;
  $("upgradedBox").hidden = true;
  $("upgradedKey").textContent = "";
  $("upgradeSave").hidden = false;
  $("upgradeApply").hidden = true;
  $("upgradeCancel").textContent = "Cancel";
  syncUpgradeFields();
  hint("", false);
  setStatus("");
  $("upgradeDialog").showModal();
}

function hint(message, isError = true) {
  $("upgradeHint").textContent = message;
  $("upgradeHint").className = isError ? "err" : "muted";
}

/** The institution fields with an institution; no date while Never expires is ticked. */
function syncUpgradeFields() {
  $("upgradeInstitution").hidden = $("upgradeKind").value !== "institution";
  $("upgradeExpiry").disabled = $("upgradePerpetual").checked;
  if ($("upgradePerpetual").checked) $("upgradeExpiry").value = "";
}

$("upgradeKind").addEventListener("change", syncUpgradeFields);
$("upgradePerpetual").addEventListener("change", syncUpgradeFields);

/** The request body, or `{ error }`. Empty fields carry over, so are not sent. */
function upgradeBody() {
  const body = { kind: $("upgradeKind").value };
  const whole = (id) => $(id).value.trim();
  const phones = whole("upgradeMaxDevices");
  if (phones) {
    if (!/^\d+$/.test(phones) || Number(phones) < 1) return { error: "Phones is a whole number, 1 or more." };
    body.maxDevices = Number(phones);
  }
  if ($("upgradePerpetual").checked) body.perpetual = true;
  else if ($("upgradeExpiry").value) body.expiresAt = `${$("upgradeExpiry").value}T23:59:59Z`;
  const cap = whole("upgradeCap");
  if (cap) {
    if (!/^\d+$/.test(cap) || Number(cap) < 1) return { error: "Analyses per person is a whole number." };
    body.maxAnalyses = Number(cap);
  }
  const note = $("upgradeNote").value.trim();
  if (note) body.note = note;
  if (body.kind === "institution") {
    body.domainLock = $("upgradeDomain").value.trim().toLowerCase();
    body.adminEmails = emailList($("upgradeAdmins").value);
    if (!body.domainLock || !body.adminEmails.length) {
      return { error: "An institution licence needs a domain and at least one IT contact." };
    }
    const seats = whole("upgradeSeats");
    if (seats) {
      if (!/^\d+$/.test(seats) || Number(seats) < 1) return { error: "Seats is a whole number." };
      body.maxSeats = Number(seats);
    }
  }
  return { body };
}

// The claim code is shown once: closing the dialog, by its button or by
// Escape, takes it out of the page.
const forgetKey = () => { $("upgradedKey").textContent = ""; };
$("upgradeCancel").addEventListener("click", () => {
  forgetKey();
  $("upgradeDialog").close();
});
$("upgradeDialog").addEventListener("close", forgetKey);

$("upgradeForm").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  const lic = upgrading;
  if (!lic) return;
  const { body, error } = upgradeBody();
  if (error) {
    hint(error);
    return;
  }
  $("upgradeSave").disabled = true;
  try {
    const out = await api(`/v1/admin/licenses/${encodeURIComponent(lic.id)}/upgrade`, {
      method: "POST", body: JSON.stringify(body),
    });
    $("upgradedKey").textContent = out.key || "";
    $("upgradedBox").hidden = !out.key;
    $("upgradeFields").hidden = true;
    $("upgradeSave").hidden = true;
    $("upgradeCancel").textContent = "Done";
    const label = labelOf(lic.id);
    const next = (out.license && out.license.keyPrefix) || "the new licence";
    if (out.license) showLicence(out.license);
    if (out.pending) {
      pending = out.license ? out.license.id : "";
      $("upgradeApply").hidden = !pending;
      hint(`Upgrade issued, waiting for the holder: ${label} keeps working until they tap ` +
        `Activate on ${next} in the app, which revokes ${label}. Apply now moves them without a phone, ` +
        "for an app that has no Activate.", false);
    } else {
      hint(`Nobody had signed in yet: the invitation moved to ${next}, and ${label} is revoked.`, false);
      refreshLicence(lic.id);
    }
    setStatus(`Upgrade for ${label} issued as ${next}.`);
  } catch (e) {
    hint(issueError(e));
  } finally {
    $("upgradeSave").disabled = false;
  }
});

/** Why an upgrade was not issued. One already waiting can be applied from here. */
function issueError(e) {
  if (e.code === "upgrade_pending") {
    pending = e.rest || "";
    $("upgradeApply").hidden = !pending;
    return `Not issued: an upgrade${pending ? ` (${labelOf(pending)})` : ""} is already waiting for the ` +
      "holder. Apply it now, or revoke it to issue another.";
  }
  return explain(e, SENTENCES, (text) => `Not issued: ${text}`);
}

/* ------------------------------------------------------- apply now */

$("upgradeApply").addEventListener("click", applyNow);

async function applyNow() {
  const lic = upgrading;
  const target = pending;
  if (!lic || !target) return;
  const label = labelOf(lic.id);
  if (!(await confirmInPage({
    title: `Apply the upgrade of ${label}`,
    message: `Move the holder of ${label} onto ${labelOf(target)} now?\n\n` +
      `${label} is revoked, and each of their phones activates again on the new licence, ` +
      "within its count. Nothing on their phones is deleted.",
    confirm: "Apply now",
    anchor: $("upgradeHint"),
  }))) return;
  $("upgradeApply").disabled = true;
  try {
    await api(`/v1/admin/licenses/${encodeURIComponent(target)}/apply-upgrade`, { method: "POST" });
    pending = "";
    $("upgradeApply").hidden = true;
    $("upgradeFields").hidden = true;
    $("upgradeSave").hidden = true;
    $("upgradeCancel").textContent = "Done";
    hint(`Applied: the holder is on ${labelOf(target)}, and ${label} is revoked.`, false);
    setStatus(`${label} moved to ${labelOf(target)}.`);
    refreshLicence(lic.id);
    refreshLicence(target);
  } catch (e) {
    hint(explain(e, SENTENCES, (text) => `Not applied: ${text}`));
  } finally {
    $("upgradeApply").disabled = false;
  }
}

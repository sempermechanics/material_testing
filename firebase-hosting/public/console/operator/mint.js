/* The Issue card: a new licence, individual or institution, for one app
 * (ADR-033) with its phone count, or one for each app. */
import { api } from "../api.js";
import { setStatus } from "../status.js";
import { alreadyLicensedId } from "../util.js";
import { explain } from "../messages.js";
import { $, appName } from "./state.js";
import { refreshLicence, searchLicences } from "./licences.js";

export const kindRadios = () => document.querySelectorAll('input[name="kind"]');

const institutionTicked = () => [...kindRadios()].some((r) => r.value === "institution" && r.checked);

/** Show the fields of whichever kind is ticked. */
export function syncKind() {
  const institution = institutionTicked();
  $("individualFields").hidden = institution;
  $("institutionFields").hidden = !institution;
  syncDevices();
}

/**
 * The Phones field says what an empty one gives, as the backend defaults it:
 * one phone on an individual licence, one a seat (a pool) on an assigned
 * institution one. A shared (floating) licence counts its seats in use
 * instead, so the field goes.
 */
function syncDevices() {
  const institution = institutionTicked();
  const floating = institution && $("seating").value === "floating";
  $("mintDevicesField").hidden = floating;
  $("mintMaxDevices").placeholder = institution ? "one a seat" : "1";
  $("mintDevicesHint").textContent = institution
    ? "Phones active at once across the whole licence, a pool. Empty gives one a seat."
    : "Phones the holder may have active at once. Empty gives 1.";
}

for (const radio of kindRadios()) radio.addEventListener("change", syncKind);
$("seating").addEventListener("change", syncDevices);
syncDevices();

/** Show the Issue card and bring it into view, the first field focused. */
export function openIssue() {
  $("issueCard").hidden = false;
  if ($("issueCard").scrollIntoView) $("issueCard").scrollIntoView({ block: "start" });
}
$("openIssue").addEventListener("click", () => {
  openIssue();
  const individual = [...kindRadios()].find((r) => r.checked)?.value !== "institution";
  $(individual ? "emailLock" : "domainLock").focus();
});
// Close takes the issued key out of the page, as closing a test account's
// password does: it is shown once, and reopening the card showed it again.
$("closeIssue").addEventListener("click", () => {
  $("issueCard").hidden = true;
  clearKeys();
});

/** Take the shown-once claim codes out of the page. */
function clearKeys() {
  $("mintedBox").hidden = true;
  $("mintedKey").textContent = "";
  $("mintedApp").textContent = "";
  $("mintedSecond").hidden = true;
  $("mintedKey2").textContent = "";
  $("mintedApp2").textContent = "";
}

$("duration").addEventListener("change", () => {
  const timed = $("duration").value === "timed";
  $("expiresAt").disabled = !timed;
  $("graceDays").disabled = !timed;
  if (!timed) { $("expiresAt").value = ""; $("graceDays").value = ""; }
});

const num = (id) => ($(id).value ? Number($(id).value) : null);
const str = (id) => ($(id).value.trim() || null);

// The apps one Issue mints for: a licence belongs to one app, so Both is
// two licences, each with its own claim code.
const MINT_APPS = { semper: ["semper"], semperclassroom: ["semperclassroom"], both: ["semper", "semperclassroom"] };

$("mint").addEventListener("click", async () => {
  const kind = document.querySelector('input[name="kind"]:checked').value;
  const timed = $("duration").value === "timed";
  if (timed && !$("expiresAt").value) {
    setStatus("A time-limited licence needs an expiry date.", true);
    return;
  }
  const phones = $("mintDevicesField").hidden ? "" : $("mintMaxDevices").value.trim();
  if (phones && (!/^\d+$/.test(phones) || Number(phones) < 1)) {
    setStatus("Phones is a whole number, 1 or more.", true);
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
    maxDevices: phones ? Number(phones) : null,
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
  const apps = MINT_APPS[$("mintApp").value] || MINT_APPS.semper;

  $("mint").disabled = true;
  clearKeys();
  setStatus("Issuing…");
  try {
    await mintEach(body, apps);
  } finally {
    $("mint").disabled = false;
  }
});

/**
 * Mint `body` once per app, in turn, showing each claim code as it comes.
 * A refusal stops there: what was issued before it stays issued, and is said.
 */
async function mintEach(body, apps) {
  const said = [];
  let failed = false;
  for (const [i, app] of apps.entries()) {
    const one = { ...body, app };
    const named = apps.length > 1 ? `${appName(app)}: ` : "";
    try {
      const out = await api("/v1/admin/licenses", {
        method: "POST", body: JSON.stringify(one),
      });
      // Both kinds return the plaintext key, once. An individual one is for
      // support recovery only; delivery is the sign-in. An institution one is
      // what members on the domain can redeem, besides IT's roster.
      if (out.key) showKey(i, app, out.key);
      const [text, isError] = mintOutcome(out, one);
      said.push(named + text);
      failed = failed || Boolean(isError);
      // Read back rather than show the mint's answer: attaching to an account
      // that already signed in happens after the licence is written.
      if (out.license) refreshLicence(out.license.id);
    } catch (e) {
      const held = alreadyLicensedId(e.message);
      const before = said.length ? `${said.join(" ")} ` : "";
      if (held) {
        showHeldLicence(one.emailLock, held, before + named);
      } else {
        setStatus(before + named + mintError(e), true);
      }
      return;
    }
  }
  setStatus(said.join(" "), failed);
}

/** Show claim code `i` (the first or the second app's) with its app. */
function showKey(i, app, key) {
  $(i ? "mintedKey2" : "mintedKey").textContent = key;
  $(i ? "mintedApp2" : "mintedApp").textContent = `For ${appName(app)}`;
  if (i) $("mintedSecond").hidden = false;
  $("mintedBox").hidden = false;
}

/**
 * What an individual mint actually did, as [message, isError]. The licence
 * exists in every case; what varies is whether it reached the person, and
 * each way it did not is something the operator has to act on.
 */
function mintOutcome(out, body) {
  if (body.kind === "institution") {
    return [`Institution licence issued. People with a verified ${body.domainLock || "domain"} ` +
      "address can redeem the claim code below on their account page, or IT adds them from the roster."];
  }
  const who = body.emailLock || "that address";
  if (out.inviteError === "invite_exists") {
    return [`Issued, but ${who} is already promised another licence — this ` +
      "one will not attach. Revoke whichever of the two is not wanted.", true];
  }
  if (out.inviteError) {
    return [`Issued, but the invite for ${who} failed (${out.inviteError}) — ` +
      "it will not attach at sign-in. The claim code below still redeems it.", true];
  }
  if (out.claimError === "holder_already_licensed") {
    return [`Issued, but ${who} already holds a live licence, so this one was ` +
      "not attached. Revoke or extend the other one.", true];
  }
  if (out.claimError) {
    return [`Issued, but it could not be attached to ${who} (${out.claimError}). ` +
      "The claim code below redeems it.", true];
  }
  if (out.claimedByUid) {
    return [`Issued and attached to ${who} — Licensed from their next request.`];
  }
  return [`Issued. It attaches when ${who} first signs in.`];
}

/**
 * One licence per person: the backend refused the mint because the address
 * holds or is promised a live licence, and named it. Put that one in front
 * of the operator — renewal is Edit on it, replacing it is revoke first.
 * It may be an institution seat, so the filter is the address, not the id.
 */
async function showHeldLicence(email, id, before = "") {
  setStatus(`${before}Not issued: ${email} already has a live licence for that app (shown below). ` +
    "To renew it, use Edit. To replace it, revoke it first, then issue again.", true);
  $("filter").value = email;
  await refreshLicence(id);
  searchLicences();
}

function mintError(e) {
  return explain(e, {
    mfa_required: "Enrol a second factor before issuing licences.",
    rate_limited: "Too many admin calls just now — wait a moment.",
  }, (text) => `Could not issue: ${text}`);
}

for (const [button, key] of [["copyKey", "mintedKey"], ["copyKey2", "mintedKey2"]]) {
  $(button).addEventListener("click", () => {
    navigator.clipboard.writeText($(key).textContent)
      .then(() => setStatus("Key copied."))
      .catch(() => setStatus("Copy failed — select and copy it by hand.", true));
  });
}

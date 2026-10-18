/* The "Redeem a claim code" card on the account page.
 *
 * A one-time claim code (from a reseller deal, for joining an institution by
 * email domain, or to recover an invite that failed) is redeemed here, on
 * the web, and never in the app: the app only uses the licence it is given,
 * and shows no purchase. So the card is offered only while the account has
 * no licence attached.
 *
 * The page tidies what is typed and catches a half-pasted code; whether the
 * code is real is the server's to say. Until it is redeemed a code is a
 * secret, so it travels only in a POST body, never in a URL, and nothing
 * here writes it anywhere but the field it was typed in, which is emptied
 * once it has been used. A step-up redirect for an old sign-in unloads the
 * page with the code in nobody's storage: it is typed again on return.
 */
import { api, setStatus, whileWaiting } from "../auth.js";
import { normaliseClaimCode, claimCodeShaped } from "../util.js";
import { explain as refusalText, ACCOUNT } from "../messages.js";

const $ = (id) => document.getElementById(id);
const CLAIM = ACCOUNT.claim;
const SAID = CLAIM.refusals;

// The licence activation codes, which `POST /v1/me/claim` answers with too.
// The step-up codes (reauth_required, mfa_required, auth_check_busy) take
// the common sentences in messages.js.
const REFUSALS = {
  license_not_found: SAID.notFound,
  license_revoked: SAID.revoked,
  license_expired: SAID.expired,
  license_email_mismatch: SAID.emailMismatch,
  license_device_mismatch: SAID.deviceMismatch,
  license_already_redeemed: SAID.alreadyRedeemed,
  already_licensed: SAID.alreadyLicensed,
  license_seats_exhausted: SAID.seatsExhausted,
  license_seat_disabled: SAID.seatDisabled,
  device_in_use: SAID.deviceInUse,
  claim_contended: SAID.busy,
  device_lock_contended: SAID.busy,
  rate_limited: SAID.rateLimited,
  not_approved: SAID.notApproved,
};

$("claimHelp").textContent = CLAIM.intro;

const input = $("claimCode");
const button = $("claimRedeem");
let busy = false;
// Re-reads /v1/me and redraws the licence card; resolves to the error it
// reported, or "". Set by the page (account.js).
let reload = async () => "";

/** Offer the card (no licence attached) or take it away. */
export function showClaim(offered) {
  $("claimCard").hidden = !offered;
}

/** What to call once a code is redeemed: the page's own /v1/me read. */
export function onClaimed(reloadAccount) {
  reload = reloadAccount;
}

function sync() {
  input.disabled = busy;
  button.disabled = busy || !input.value;
}

// Tidy as the person types or pastes, keeping the caret where it was among
// the characters that stay.
input.addEventListener("input", () => {
  const raw = input.value;
  const code = normaliseClaimCode(raw);
  if (code !== raw) {
    const at = typeof input.selectionStart === "number"
      ? normaliseClaimCode(raw.slice(0, input.selectionStart)).length
      : code.length;
    input.value = code;
    if (input.setSelectionRange) input.setSelectionRange(at, at);
  }
  sync();
});
input.addEventListener("keydown", (e) => {
  if (e.key === "Enter") redeem();
});
button.addEventListener("click", () => redeem());

async function redeem() {
  const code = normaliseClaimCode(input.value);
  if (busy || !code) return;
  if (!claimCodeShaped(code)) {
    setStatus(CLAIM.shape, true);
    input.focus();
    return;
  }
  busy = true;
  sync();
  try {
    await whileWaiting(
      api("/v1/me/claim", { method: "POST", body: JSON.stringify({ code }) }),
      CLAIM.working,
    );
  } catch (e) {
    busy = false;
    sync();
    setStatus(refusalText(e, REFUSALS, () => CLAIM.failed), true);
    input.focus();
    return;
  }
  // Used up: nothing to send again, and nothing left on the page.
  input.value = "";
  busy = false;
  sync();
  const unread = await reload();
  setStatus(unread ? `${CLAIM.done} ${unread}` : CLAIM.done, Boolean(unread));
}

sync();

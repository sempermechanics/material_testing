/* What the consoles say when the backend refuses something.
 *
 * The backend answers with a code (`backend/app/errors.py`); each page keeps
 * its own sentence for the codes that mean something particular there, and
 * falls back to the sentences below for the ones any page can meet. Codes are
 * matched exactly — `scripts/check_console.py` fails a page that matches a
 * code the backend does not declare. A code with no sentence anywhere is not
 * the message: it follows a sentence as "(ref: <code>)", for support, so
 * nothing is ever silently swallowed and nobody is shown only a code.
 */
import { errorDetail } from "./util.js";

/** Sentences any page may show for a code it has none of its own for. */
export const COMMON = {
  rate_limited: "Too many requests just now — wait a moment and try again.",
  // 503 from a step-up while this server instance's sign-in check is out of
  // budget (TD-239). The sign-in itself is fine: nothing signs out or
  // re-authenticates for it, and repeating the action shortly works.
  auth_check_busy: "The server is busy checking sign-ins — wait a few seconds and try again.",
  claim_contended: "Busy just now — try again.",
  mfa_required: "Set up two-factor authentication first.",
  reauth_required: "Sign in again, then repeat that.",
  not_admin: "That account is not a Semper operator.",
  email_not_verified: "Verify your email address first.",
};

/**
 * Why something failed, for the codes no page has words of its own for: the
 * platform's refusals, the route refusals pages share, and a fetch with no
 * answer at all. Unlike COMMON these go through the page's fallback, which
 * says what was being done: "Could not delete: The server could not be
 * reached — …".
 */
export const REASONS = {
  // Platform refusals any route can give (backend/app/main.py, deps.py).
  internal_error: "Something went wrong on the server. Try again; if it keeps happening, tell Semper support.",
  firestore_unreachable: "The server could not reach its database just now. Try again in a moment.",
  feature_paused: "That is switched off for maintenance just now. Try again later.",
  request_too_large: "That is too large to send in one go.",
  // Route refusals no page has words of its own for.
  empty_patch: "Nothing changed.",
  user_not_found: "No Semper account has that address.",
  invite_not_found: "That invitation is no longer open: it was withdrawn or claimed. Reload to see the roster.",
  // No answer at all: fetch rejected (offline, DNS, the connection dropped).
  network: "The server could not be reached — check your connection and try again.",
};

/**
 * A refusal with no JSON — the gateway's, or a server that fell over — has
 * only its status, which api.js makes the code `http_<status>`. By status.
 */
export const GATEWAY = {
  500: REASONS.internal_error,
  502: "The server did not answer — try again in a moment.",
  503: "The server did not answer — try again in a moment.",
  504: "The server did not answer — try again in a moment.",
};

/** A backend code (snake_case), as opposed to a message already in words. */
const BARE_CODE = /^[a-z][a-z0-9]*(?:_[a-z0-9]+)+$/;

/** Why `error` failed, in words, for `explain`'s fallback. */
function reason(error, code, text) {
  if (REASONS[code]) return REASONS[code];
  if (error instanceof TypeError) return REASONS.network;
  const gateway = /^http_(\d{3})$/.exec(code);
  if (gateway && GATEWAY[gateway[1]]) return GATEWAY[gateway[1]];
  // A code nobody wrote a sentence for: words, with the code kept for support.
  return BARE_CODE.test(code) || gateway ? `the server refused it (ref: ${code})` : text;
}

/**
 * The sentence for `error` (an `ApiError`, or anything with a message):
 * the page's own for its code, else a common one, else `fallback` applied to
 * why it failed (`REASONS`, `GATEWAY`; "the server refused it (ref: <code>)"
 * for a code with no sentence; the message itself when it is already words,
 * as a validation 422's are). A suffixed code (`device_change_too_soon:
 * <instant>`) is matched on the code alone.
 */
export function explain(error, sentences = {}, fallback = (text) => text) {
  const text = String(error?.message ?? error ?? "");
  const code = error?.code ?? errorDetail(text).code;
  return sentences[code] ?? COMMON[code] ?? fallback(reason(error, code, text));
}

/**
 * The account page's own words (account/account.js) that are not refusals:
 * the licence, field by field, from `/v1/me`'s `license` block; what a phone
 * does offline; and what deleting the account takes with it. Keys are
 * camelCase: a snake_case key reads as a backend code to check_console.py.
 */
export const ACCOUNT = {
  // The licence card's facts, in the order shown.
  labels: {
    mode: "Mode",
    licence: "Licence",
    seating: "Seats",
    ends: "Ends",
    graceEnds: "Grace period ends",
    lease: "Shared seat",
  },
  modes: { licensed: "Licensed", demo: "Demo" },
  kinds: { individual: "Individual", institution: "Institution" },
  seating: { assigned: "One seat each", floating: "Shared across the institution" },
  noLicence: "None — this account is on the demo",
  noEnd: "No end date",
  leaseHeld: (until) => `Held until ${until}`,
  leaseNone: "Not held right now",
  // Shown whenever a licence is attached: a phone checks its licence with
  // the server, but does not need to on every launch.
  offline:
    "On a phone, licensed features keep working offline until the offline " +
    "grace period ends; then the app falls back to the demo limits until it " +
    "reconnects. Analyses you already have stay available either way.",
  // Deleting needs the phone's device signature, so it is done in the app.
  // One account signs in to both apps, so the sign-in goes for both.
  deleteAccount:
    "Delete your account from the app on your phone — in Semper, Settings → " +
    "Your data → Delete account. It erases every analysis and cloud backup, " +
    "and removes the sign-in itself for both apps, Semper and Material " +
    "Testing, since they share one account. It cannot be undone: signing " +
    "in again with the same address starts a new, empty account.",
  // The "Redeem a claim code" card (account/claim.js), shown only while no
  // licence is attached. A claim code is how a reseller deal, joining an
  // institution by email domain, or a failed invite reaches an account; the
  // app itself takes no codes and shows no purchase. No price and no buying
  // here either.
  claim: {
    intro: "A claim code adds a licence or an institution seat to this account.",
    shape: "That does not look like a whole claim code: it is letters and digits, " +
      "at least eight, in groups split by dashes. Check it against the one you were given.",
    working: "Redeeming your claim code…",
    done: "Claim code redeemed. Open the app and tap Refresh licence, or wait for its next check.",
    failed: "The claim code could not be redeemed just now. Try again, or email " +
      "support@sempermechanics.com.",
    // One sentence per refusal, keyed in camelCase (account/claim.js maps
    // the backend's codes onto these).
    refusals: {
      notFound: "No claim code matches that. Check each character and try again.",
      revoked: "That claim code has been withdrawn. Ask whoever gave it to you, or email " +
        "support@sempermechanics.com.",
      expired: "That claim code has expired. Ask whoever gave it to you for a new one.",
      emailMismatch: "That claim code was issued to a different email address or institution. " +
        "Sign in with that account, then try again.",
      deviceMismatch: "The licence behind that claim code is in use on another phone. Move it " +
        "from the account it is on, or email support@sempermechanics.com.",
      alreadyRedeemed: "That claim code has already been used. If it was meant for you, email " +
        "support@sempermechanics.com.",
      alreadyLicensed: "This account already has a licence, so the claim code was not used. " +
        "Reload the page to see it.",
      seatsExhausted: "Every seat on that institution licence is taken. Ask your institution's " +
        "IT to free or add one.",
      seatDisabled: "Your institution has put your seat on hold. Ask your institution's IT to " +
        "resume it.",
      deviceInUse: "Your phone is registered to another Semper account. Sign in with that " +
        "account, or email support@sempermechanics.com to release the phone.",
      busy: "The server was busy and the claim code was not used. Try again in a moment.",
      rateLimited: "Too many attempts. Wait a few minutes and try again.",
      notApproved: "Your account isn't approved yet. Try again once it is.",
      emailNotVerified: "Verify your email address with your sign-in provider first, then try again.",
    },
  },
};

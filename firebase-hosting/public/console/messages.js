/* What the consoles say when the backend refuses something.
 *
 * The backend answers with a code (`backend/app/errors.py`); each page keeps
 * its own sentence for the codes that mean something particular there, and
 * falls back to the sentences below for the ones any page can meet. Codes are
 * matched exactly — `scripts/check_console.py` fails a page that matches a
 * code the backend does not declare — and a code with no sentence anywhere
 * is shown as it came, so nothing is ever silently swallowed.
 */
import { errorDetail } from "./util.js";

/** Sentences any page may show for a code it has none of its own for. */
export const COMMON = {
  rate_limited: "Too many requests just now — wait a moment and try again.",
  claim_contended: "Busy just now — try again.",
  mfa_required: "Set up two-factor authentication first.",
  reauth_required: "Sign in again, then repeat that.",
  not_admin: "That account is not a Semper operator.",
  email_not_verified: "Verify your email address first.",
};

/**
 * The sentence for `error` (an `ApiError`, or anything with a message):
 * the page's own for its code, else a common one, else `fallback` applied to
 * the raw text. A suffixed code (`device_change_too_soon: <instant>`) is
 * matched on the code alone.
 */
export function explain(error, sentences = {}, fallback = (text) => text) {
  const text = String(error?.message ?? error ?? "");
  const code = error?.code ?? errorDetail(text).code;
  return sentences[code] ?? COMMON[code] ?? fallback(text);
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
};

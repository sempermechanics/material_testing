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

/* Calls to the Semper API from a console page: the signed-in caller's ID
 * token on every request, one re-authentication on `reauth_required`, and a
 * refusal as an `ApiError` the page branches on by code.
 */
import { API_BASE_URL } from "./config.js";
import { endSession, signedInUser, stepUp } from "./auth.js";
import { errorDetail, sessionEnded } from "./util.js";

/**
 * A refusal from the Semper API.
 *
 * `code` is what a page branches on — the backend's own code, without what
 * follows its colon (`device_limit_reached: 1/1` is code
 * `device_limit_reached`, rest the count). `message` is the whole detail,
 * as every page has always shown it; `status` is the HTTP status. A gateway
 * refusal is not JSON, and then the status is all there is: `http_<status>`.
 */
export class ApiError extends Error {
  constructor(status, detail) {
    super(detail || `http_${status}`);
    this.name = "ApiError";
    this.status = status;
    const { code, rest } = errorDetail(this.message);
    this.code = code;
    this.rest = rest;
  }
}

/**
 * A request-validation 422 carries a list of `{msg, loc}` rather than a code;
 * as a message that list read "[object Object]". Its sentences are the useful
 * part ("maxAnalyses must be at least 25…").
 */
function validationDetail(detail) {
  if (!Array.isArray(detail)) return detail;
  return detail
    .map((d) => String((d && d.msg) || d).replace(/^Value error, /, ""))
    .join("; ");
}

/** The detail of a failed response, or "" when its body is not JSON. */
async function failureDetail(resp) {
  try {
    const text = await resp.text();
    return validationDetail(text ? JSON.parse(text).detail : "") || "";
  } catch {
    return "";
  }
}

/**
 * A call to the Semper API carrying a fresh ID token; `read` turns a good
 * response into the answer.
 *
 * On `reauth_required` the caller is re-authenticated once and the call is
 * retried, because that response means "prove it again", not "you may not".
 * Exactly once: a second failure is a real refusal, and retrying forever
 * would trap someone in a loop. Anything else refused throws an `ApiError`.
 *
 * A sign-in that is over — the backend refuses the token (401
 * `invalid_token`), or Firebase can no longer refresh the one this browser
 * remembered — signs the page out and throws `invalid_token`, whose sentence
 * says to sign in again. Re-authenticating cannot rescue it, and the page used
 * to show the Firebase code while still offering Sign out.
 */
async function request(path, options, read, { allowStepUp = true, json = true } = {}) {
  const send = async () => {
    const user = signedInUser();
    if (!user) throw new Error("not_signed_in");
    const token = await user.getIdToken();
    const resp = await fetch(`${API_BASE_URL}${path}`, {
      ...options,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(json ? { "Content-Type": "application/json" } : {}),
        ...(options.headers || {}),
      },
    });
    if (!resp.ok) throw new ApiError(resp.status, await failureDetail(resp));
    return read(resp);
  };

  try {
    try {
      return await send();
    } catch (e) {
      if (e.code !== "reauth_required" || !allowStepUp) throw e;
      await stepUp();
      return await send();
    }
  } catch (e) {
    if (!sessionEnded(e)) throw e;
    await endSession();
    throw e instanceof ApiError ? e : new ApiError(401, "invalid_token");
  }
}

/** A JSON call: the parsed answer, or an `ApiError`. */
export function api(path, options = {}, { allowStepUp = true } = {}) {
  return request(path, options, async (resp) => {
    const text = await resp.text();
    return text ? JSON.parse(text) : {};
  }, { allowStepUp });
}

/**
 * A call that answers with bytes rather than JSON — today, a session bundle.
 * The step-up retry is why this is not a bare `fetch`: the bundle route sits
 * at the user step-up tier, so a tab left open past the re-authentication
 * window answers `reauth_required` to a download the caller is entitled to.
 */
export function apiBlob(path, options = {}) {
  return request(path, options, (resp) => resp.blob(), { json: false });
}

/**
 * Hand a Blob to the browser as a download.
 *
 * The backend streams the archive so that its own memory stays flat; the
 * browser still holds the whole thing, because a page cannot write to the
 * filesystem incrementally without the File System Access API, which is not
 * available everywhere and would need a permission prompt of its own. An
 * analysis is tens of megabytes, so this is a cost worth paying for a
 * download that works the same way in every browser.
 */
export function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  // Not immediately: Safari reads the href after the click returns.
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
}

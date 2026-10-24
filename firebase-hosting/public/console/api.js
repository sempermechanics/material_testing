/* Calls to the Semper API from a console page: the signed-in caller's ID
 * token on every request, one re-authentication on `reauth_required`, and a
 * refusal as an `ApiError` the page branches on by code.
 */
import { API_BASE_URL } from "./config.js";
import { signedInUser, stepUp } from "./auth.js";
import { errorDetail } from "./util.js";

/**
 * A refusal from the Semper API.
 *
 * `code` is what a page branches on — the backend's own code, without what
 * follows its colon (`device_change_too_soon: <instant>` is code
 * `device_change_too_soon`, rest the instant). `message` is the whole detail,
 * as every page has always shown it; `status` is the HTTP status. A gateway
 * refusal is not JSON, and then the status is all there is: `http_<status>`.
 * `line` is the line of an uploaded file a refusal names (a roster import
 * the CSV reader stopped on), when the backend gives one, else null.
 */
export class ApiError extends Error {
  constructor(status, detail, line = null) {
    super(detail || `http_${status}`);
    this.name = "ApiError";
    this.status = status;
    const { code, rest } = errorDetail(this.message);
    this.code = code;
    this.rest = rest;
    this.line = Number.isInteger(line) ? line : null;
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

/**
 * The detail of a failed response ("" when its body is not JSON) and the
 * file line it names, if any: `{"detail": "<code>", "line": 3}`, or a detail
 * that is itself `{code, line}`.
 */
async function failureDetail(resp) {
  try {
    const text = await resp.text();
    const body = text ? JSON.parse(text) : {};
    let detail = body.detail ?? "";
    let line = body.line;
    if (detail && typeof detail === "object" && !Array.isArray(detail)) {
      line = detail.line ?? line;
      detail = detail.code ?? "";
    }
    return { detail: validationDetail(detail) || "", line };
  } catch {
    return { detail: "" };
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
    if (!resp.ok) {
      const { detail, line } = await failureDetail(resp);
      throw new ApiError(resp.status, detail, line);
    }
    return read(resp);
  };

  try {
    return await send();
  } catch (e) {
    if (e.code === "reauth_required" && allowStepUp) {
      await stepUp();
      return send();
    }
    throw e;
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

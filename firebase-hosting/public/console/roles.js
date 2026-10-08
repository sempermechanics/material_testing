/* Which dashboards an account can open, from the two answers the backend
 * gives about the caller: its `role` (/v1/me) and the institution licences
 * naming its address. The front door routes on these (router.js) and every
 * page's dashboard switch is built from them (switcher.js). No DOM here.
 */
import { api } from "./auth.js";

/**
 * The institution licences this address administers, and whether the
 * answer is trustworthy. `email_not_verified` means "none": an unverified
 * address cannot be named as an administrator.
 */
export async function licencesAdministered() {
  try {
    const out = await api("/v1/institutions/licenses", {}, { allowStepUp: false });
    return { licenses: out.licenses || [], certain: true };
  } catch (e) {
    if (e.code === "email_not_verified") return { licenses: [], certain: true };
    return { licenses: [], certain: false, why: e.message };
  }
}

/** The dashboards this account can open, in header order. */
export function dashboardsFor(me, licenses) {
  const names = [];
  if (me && me.role === "admin") names.push("operator");
  if (licenses && licenses.length) names.push("institution");
  names.push("account");
  return names;
}

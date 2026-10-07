/* The Operator | Your account switch in the header of both pages.
 *
 * Staff who also hold a licence of their own use both pages, and the front
 * door forwards them to the desk, which had no way back to their account
 * short of typing the address. Only that account sees the switch: a staff
 * address with no licence has nothing on the account page worth a tab, and
 * everyone else can open only the account page.
 *
 * Decided from the /v1/me answer the page has already read, so it costs no
 * request, and nothing is remembered in the browser.
 */
import { esc, holdsLicence } from "./util.js";

const $ = (id) => document.getElementById(id);

const TABS = [
  { name: "operator", href: "../operator/", title: "Operator" },
  { name: "account", href: "../account/", title: "Your account" },
];

/** Whether this /v1/me answer has both the desk and a licence of its own. */
export function canSwitch(me) {
  return Boolean(me) && me.role === "admin" && Boolean(holdsLicence(me.license));
}

/** Fill and show `#switch` with `current` marked, or leave it hidden. */
export function mountSwitcher(current, me) {
  if (!canSwitch(me)) return;
  $("switch").innerHTML = TABS.map((t) => t.name === current
    ? `<a href="${esc(t.href)}" aria-current="page">${esc(t.title)}</a>`
    : `<a href="${esc(t.href)}">${esc(t.title)}</a>`).join("");
  $("switch").hidden = false;
}

/* What the desk's modules share: the licences loaded so far, and how a
 * licence is named on screen. One object, so that a module that replaces a
 * list replaces it for every reader.
 */
import { APP_NAMES } from "../util.js";

export const $ = (id) => document.getElementById(id);

export const desk = {
  licences: [],
  // Licences the backend found for the filter text, or null when the filter
  // is not a search. They join the loaded rows, since a match may be on a
  // page nobody has loaded.
  searchHits: null,
  // The paging block of the last licence page read.
  licencePage: {},
  // Reconciliation reports, keyed by licence id, kept only for this page
  // load. Filled on demand: the read costs one user lookup per seat, so it
  // is never run for the whole table at once.
  verified: {},
  // DEMO_MAX_ANALYSES as the backend has it, from the licence list. Null
  // until a backend that sends it answers; the wording then leaves the
  // number out.
  demoAllowance: null,
};

export const findLicence = (id) =>
  desk.licences.find((l) => l.id === id) || (desk.searchHits || []).find((l) => l.id === id);

export const labelOf = (id) => {
  const lic = findLicence(id);
  return lic ? (lic.keyPrefix || lic.id.slice(0, 10)) : id.slice(0, 10);
};

/**
 * Why a Demo key has no Cap. A Demo holder gets the Demo limit whatever
 * the key stores, and the backend refuses the edit (`cap_on_demo_key`). It
 * used to answer 200 and change nothing, and the app kept showing "N of 25".
 */
export function demoCapNote() {
  const limit = desk.demoAllowance == null
    ? "the Demo limit" : `the Demo limit (${desk.demoAllowance})`;
  return `Demo keys use ${limit}; issue a Licensed key to raise it.`;
}

/* ------------------------------------------------- a licence per app */

/**
 * Whether a licence runs ADR-033's model: it names its app, and licenses
 * only the phones activated on it, up to its count. A licence from before
 * the split names none and keeps the one-phone lock (New phone); so does a
 * Demo key, which is never split.
 */
export const runsActivations = (lic) => Boolean(lic && lic.app) && lic.mode !== "demo";

/** An app as the desk names it ("semper", "semper Classroom"). */
export const appName = (app) => APP_NAMES[app] || app || "";

// What the backend answers for an institution licence with no count and no
// seat cap (`repo/activations._UNCOUNTED`): no limit in practice.
const UNCOUNTED = 1000000;

/**
 * A licence's phone count as the backend resolves it (`max_devices`):
 * `maxDevices`, else 1 on an individual licence and the seat cap on an
 * institution one; null for no limit.
 */
export function devicesCap(lic) {
  if (lic.maxDevices != null) return Number(lic.maxDevices);
  if (lic.kind !== "institution") return 1;
  return lic.maxSeats == null ? null : Number(lic.maxSeats);
}

/** A phone count for the page: the number, or "∞" for no limit. */
export const capText = (n) => (n == null || Number(n) >= UNCOUNTED ? "∞" : String(n));

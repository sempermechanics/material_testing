/* What the desk's modules share: the licences loaded so far, and how a
 * licence is named on screen. One object, so that a module that replaces a
 * list replaces it for every reader.
 */
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
 * Why a demo key has no Cap. A demo holder gets the demo allowance whatever
 * the key stores, and the backend refuses the edit (`cap_on_demo_key`). It
 * used to answer 200 and change nothing, and the app kept showing "N of 25".
 */
export function demoCapNote() {
  const allowance = desk.demoAllowance == null
    ? "the demo allowance" : `the demo allowance of ${desk.demoAllowance}`;
  return `Demo keys use ${allowance}; issue a licensed key to raise it.`;
}

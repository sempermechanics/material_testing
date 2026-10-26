// Every column header on the console pages says what its column holds (WCAG
// 1.3.1): an actions column's <th> carries a visually hidden "Actions", never
// nothing (readiness MT-AE2-001). axe's empty-table-header rule is a best
// practice, outside the WCAG tags tests/a11y runs, so it is checked here.
import assert from "node:assert/strict";
import { test, mountPage, page } from "./harness.mjs";

/** The id of the nearest ancestor that has one, to say which table. */
function where(el) {
  for (let e = el; e; e = e.parentNode) if (e.id) return `#${e.id}`;
  return "the page";
}

for (const file of ["index.html", "account/index.html", "institution/index.html", "operator/index.html"]) {
  test(`every <th> on console/${file} has a name`, () => {
    mountPage(file);
    const empty = page.root.querySelectorAll("th")
      .filter((th) => !th.textContent.trim() && !(th.getAttribute("aria-label") || "").trim())
      .map((th) => `<th${th.className ? ` class="${th.className}"` : ""}> in ${where(th)}`);
    assert.deepEqual(empty, []);
  });
}

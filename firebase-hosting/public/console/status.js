/* The page's status line, and the Performance marks a page load is measured by.
 *
 * DOM only: every console page has a `#status` line under its header, and
 * this is the one place that writes it.
 */

/**
 * A Performance API mark, for measuring a page load in DevTools or with
 * `performance.getEntriesByName(name)`: `semper:auth-ready` when sign-in and
 * the second factor are settled, `semper:first-data` when a page first shows
 * what it loaded (`markFirstData`). Absent support is not an error.
 */
export function mark(name) {
  try {
    performance.mark(name);
  } catch { /* no Performance API */ }
}

let firstDataMarked = false;

/** Mark the first time this page shows data it loaded (see `mark`). */
export function markFirstData() {
  if (firstDataMarked) return;
  firstDataMarked = true;
  mark("semper:first-data");
}

/** Write a message into the page's status line. */
export function setStatus(message, isError = false) {
  const el = document.getElementById("status");
  if (!el) return;
  delete el.dataset.pending;
  el.textContent = message;
  el.className = isError ? "muted err" : "muted";
}

// The × in the status bar (each page's markup) clears the message, and with it
// the pinned bar. Listened for on the document, once: this module outlives a
// page's markup in the tests, as a browser keeps it for the tab.
document.addEventListener("click", (ev) => {
  if (ev.target && ev.target.closest && ev.target.closest("#statusClose")) setStatus("");
});

/**
 * Where a card under the status line goes: after its bar, not inside it, or
 * it would be pinned to the top of the page with the message.
 */
export function statusAnchor() {
  const el = document.getElementById("status");
  return (el && el.closest(".status-bar")) || el;
}

/** Clear "Checking your sign-in…" if nothing has replaced it since. */
export function clearPendingStatus() {
  const el = document.getElementById("status");
  if (el && "pending" in el.dataset) setStatus("");
}

/** How long a wait goes before the status line says why it is long. */
export const SLOW_AFTER_MS = 4000;
export const SLOW_NOTE = "Still working — the server can take a few seconds to start.";

/**
 * Say `message` on the status line while `promise` runs, and if it is still
 * running after `after` ms, add why: the API scales to zero when idle, and
 * its first answer after that can take ten seconds or more. Resolves or
 * rejects as `promise` does; what the line says afterwards is the caller's.
 */
export async function whileWaiting(promise, message, { after = SLOW_AFTER_MS } = {}) {
  setStatus(message);
  const timer = setTimeout(() => {
    const el = document.getElementById("status");
    if (el && el.textContent === message) setStatus(`${message} ${SLOW_NOTE}`);
  }, after);
  try {
    return await promise;
  } finally {
    clearTimeout(timer);
  }
}

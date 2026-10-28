/* An institution roster's bulk tools: the seat-count tiles, the roster CSV
 * export and the bulk import. The roster table itself is roster.js; like it,
 * these serve both dashboards — the institution page on IT's routes
 * (`/v1/institutions/licenses/{id}`) and the operator desk's roster card on
 * the staff routes (`/v1/admin/licenses/{id}`). The backend serves both tiers
 * from `routers/roster.py`, so only the path differs.
 *
 * Each `wire…` takes the page's elements and `base()`, the open licence's
 * path on the page's own tier ("" when none is open), and reads it when it
 * acts, so an answer that arrives after another licence was opened still
 * goes to the one asked on.
 */
import { api, apiBlob, saveBlob } from "./api.js";
import { holdFocus } from "./dialogs.js";
import { explain } from "./messages.js";
import { esc, MAX_IMPORT_ROWS, importRowCount } from "./util.js";

/** The tiles, in order: the count's key, its label, and whether a non-zero count wants attention. */
const TILES = [
  ["assigned", "assigned", false],
  ["activeLast7Days", "active in the last 7 days", false],
  ["onHold", "paused", false],
  ["invited", "invited", false],
  ["revocationPending", "removed, phone not told yet", true],
  ["expiringWithin30Days", "seats ending within 30 days", true],
];

/** The seat counts (`GET …/summary`) as tiles. */
export function tilesHtml(summary) {
  const counts = (summary && summary.counts) || {};
  return TILES.map(([key, label, warn]) => {
    const n = Number(counts[key] ?? 0);
    return `<div class="tile${warn && n ? " warn" : ""}"><strong>${n}</strong> ` +
      `<span>${esc(label)}</span></div>`;
  }).join("");
}

/**
 * Fill `tiles` with the counts of the licence at `root`. `current()` says
 * whether that licence is still the one shown when the answer lands. A
 * failure says so in place of the tiles; the roster itself is unaffected.
 */
export async function loadTiles(tiles, root, current = () => true) {
  try {
    const summary = await api(`${root}/summary`);
    if (current()) tiles.innerHTML = tilesHtml(summary);
  } catch (e) {
    if (current()) tiles.innerHTML = `<p class="err">Could not count seats: ${esc(explain(e))}</p>`;
  }
}

/** Export roster CSV: the whole roster as a download named `roster-<name()>.csv`. */
export function wireExport({ button, base, name, report }) {
  button.addEventListener("click", async () => {
    const root = base();
    if (!root) return;
    button.disabled = true;
    report("Exporting the roster…");
    try {
      saveBlob(await apiBlob(`${root}/seats/export`), `roster-${name()}.csv`);
      report("Roster exported.");
    } catch (e) {
      report(explain(e, {}, (text) => `Could not export: ${text}`), true);
    } finally {
      button.disabled = false;
    }
  });
}

/** What a refused import row says, by the single add's code. */
const ROW_ERRORS = {
  license_seats_exhausted: "no seats left on this licence",
  member_already_licensed: "already has a semper licence of their own — semper support can move them",
  invite_exists: "already promised a place on a different licence",
  license_seat_disabled: "their seat is paused — resume it instead",
  license_revoked: "this licence has been revoked",
  claim_contended: "busy just now — import the file again",
};

/** One import row's result, as a sentence. */
export function rowResult({ outcome, error }) {
  switch (outcome) {
    case "seated": return "on the licence now";
    case "invited": return "invited — joins at first sign-in";
    case "exists": return "already on the roster";
    case "duplicate": return `repeats ${error || "an earlier row"}`;
    case "invalid": return "not an email address";
    case "refused": return ROW_ERRORS[error] || `not added — the server refused it (ref: ${error})`;
    case "unprocessed": return "not reached — import the file again to finish";
    default: return outcome;
  }
}

/**
 * The line a whole-file refusal names: the backend sends
 * `roster_import_malformed: line <n>` (the line the bad record starts on).
 * null when it names none (a backend before 2026-10-24).
 */
export function refusedLine(e) {
  const m = /^line (\d+)$/i.exec(String((e && e.rest) || "").trim());
  return m ? Number(m[1]) : null;
}

/** What a refusal naming line `n` says, by the code that refused the file. */
const IMPORT_LINE_ERRORS = {
  roster_import_malformed: (n) => `Line ${n} could not be read as CSV — check its quotes.`,
};

/** What a refused import says, by the code that refused the whole file. */
const IMPORT_ERRORS = {
  roster_import_empty: "There are no addresses to import.",
  roster_import_too_many_rows: `That is more than ${MAX_IMPORT_ROWS} rows. Split the file and import each part.`,
  // 413 over 512,000 characters, before any row is read.
  roster_import_too_large: `That file is too large to import at once. Split it into parts of at most ${MAX_IMPORT_ROWS} rows.`,
  roster_import_malformed: "That file could not be read as CSV — check its quotes.",
  roster_import_no_email_column: "The first line looks like a header but has no “email” column.",
};

/** The import counts as one sentence, the parts that are zero left out. */
export function importSummary(counts = {}) {
  const parts = [
    [counts.seated, "added"],
    [counts.invited, "invited"],
    [counts.exists, "already on the roster"],
    [counts.duplicate, "repeated"],
    [(counts.invalid || 0) + (counts.refused || 0), "not added"],
    [counts.unprocessed, "not reached"],
  ].filter(([n]) => n).map(([n, what]) => `${n} ${what}`);
  return parts.length ? `${parts.join(", ")}.` : "Nothing to import.";
}

/**
 * The import card: paste or choose a file, see how many rows it holds, send
 * it, and read each row's result. `reload()` reads the roster again after
 * an import that changed it.
 */
export function wireImport({ text, file, preview, submit, summary, results, rows, base, report, reload }) {
  function update() {
    const n = importRowCount(text.value);
    submit.disabled = n === 0 || n > MAX_IMPORT_ROWS;
    preview.textContent = n === 0 ? ""
      : n > MAX_IMPORT_ROWS ? `${n} rows — at most ${MAX_IMPORT_ROWS} at a time`
        : `${n} ${n === 1 ? "row" : "rows"} ready`;
    preview.className = n > MAX_IMPORT_ROWS ? "err" : "muted";
  }

  text.addEventListener("input", update);
  file.addEventListener("change", async () => {
    const chosen = file.files && file.files[0];
    if (!chosen) return;
    text.value = await chosen.text();
    update();
  });

  submit.addEventListener("click", async () => {
    const root = base();
    if (!root || submit.disabled) return;
    const refocus = holdFocus(submit);
    submit.disabled = true;
    report("Importing…");
    try {
      const out = await api(`${root}/seats/import`,
        { method: "POST", body: JSON.stringify({ csv: text.value }) });
      const counts = out.counts || {};
      const said = importSummary(counts);
      summary.textContent = said;
      // Every row is listed, the ones that need nothing doing too: IT reads
      // the table against the file they sent.
      rows.innerHTML = (out.results || []).map((r) => `
        <tr class="${r.outcome === "seated" || r.outcome === "invited" || r.outcome === "exists" ? "" : "err"}">
          <td>${esc(r.row)}</td><td>${esc(r.email || "—")}</td><td>${esc(rowResult(r))}</td>
        </tr>`).join("");
      results.hidden = false;
      if (counts.seated || counts.invited) await reload();
      // On the live status line as well, after the reload's own messages,
      // so the outcome is read out and not only drawn.
      report(said);
    } catch (e) {
      const line = refusedLine(e);
      const atLine = line != null && IMPORT_LINE_ERRORS[e.code];
      report(atLine ? atLine(line)
        : explain(e, IMPORT_ERRORS, (msg) => `Could not import: ${msg}`), true);
    } finally {
      update();
      refocus();
    }
  });

  update();
  /** Forget the last import's results (another licence was opened). */
  return function clear() {
    summary.textContent = "";
    rows.innerHTML = "";
    results.hidden = true;
  };
}

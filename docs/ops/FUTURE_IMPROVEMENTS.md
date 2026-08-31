# Future improvements

Proposed work, not tracked debt. [TECH_DEBT.md](TECH_DEBT.md) records what is
already known to be owed and what is deliberately deferred; this file is the
forward list that came out of the 2026-08-24 workflow-traceability pass, where
every flow in [../WORKFLOWS.md](../WORKFLOWS.md) was walked back to its files.

Ranked by the product's own priorities, in order:

1. **Accuracy** — anything that can make a reported number wrong, or make a
   wrong number hard to notice.
2. **Privacy and security** — anything touching identity, consent, erasure or
   the attestation boundary.
3. **Debuggability** — how long it takes to get from a symptom to the line.
4. **Cost** — smallest change that actually fixes the problem.

Each item names the workflow ids it affects (`A*` / `B*` / `C*` from
[../WORKFLOWS.md](../WORKFLOWS.md)) and the §E2 finding behind it. Nothing here
is scheduled; take one deliberately, with its own PR.

---

## FI-1 One pack/unpack for the viewer's session state

**Affects** A7, A8 · §E2.1 · *accuracy, debuggability*

~25 `DicKeys` extras are packed in two places — `AnalysisNavHelper.openResults`
(fresh run) and `ui/home/SessionOpenHelper.intentFor` (reopen) — and read in four
(`ResultViewerActivity`, `VsgLatticeActivity`, `ViewerSettingsSheet`,
`ViewerReportFactory`). The two packers do not write the same set, and a missing
extra silently defaults, so the ⓘ sheet, the report header and the export
filename can differ by entry path with nothing logged.

**Fix.** A `ViewerSession` value type with `fun toIntent()` / `fun from(intent)`
as the only pack and unpack. Both current packers construct it; the four readers
take the parsed object. Missing required fields fail loudly (or resolve from the
session record) instead of defaulting.

**Blast radius.** Touches five UI files and the lattice→viewer hop. No format,
no `.dat`, no wire change. A JVM test can assert round-trip equality for both
entry paths, which is the thing nothing checks today.

Already flagged as "next architecture" in [../../CONTEXT.md](../../CONTEXT.md)
and in [TECH_DEBT.md](TECH_DEBT.md) — this is the concrete shape.

## FI-5 A way back from on-screen copy to its string

**Affects** all of §A · §E2.5 · *debuggability*

Finding the code behind a message means guessing its wording well enough to grep
`strings.xml`; formatted strings and plurals defeat that.

**Fix (debug builds only).** Either append the resource entry name to strings in
a debug overlay, or ship a generated `strings-index.md` mapping every
`R.string.*` to the files that reference it. The overlay is more useful, the
index is cheaper and cannot affect release rendering. Release output must not
change either way.

## FI-6 One owner for wizard run state

**Affects** A5 · §E2.6 · *accuracy, debuggability*

`StaticAnalysisActivity` (~1600 lines), ~15 helpers and ~40 public `var`s on
`AnalysisViewModel` collectively hold what a run will use; the authoritative
record only exists once `SessionRepository.buildSessionRecord` assembles it at
commit. "What settings did this run actually use?" has no single answer until
then.

**Fix.** An immutable `RunSpec` (frames, order, ROI + mask, subset, step, strain
window, interpolator, sweep plan) built once at Compute and passed to
`DicBatchRunner` / `VsgStudyRunner` and to the session record. The wizard keeps
its mutable editing state; the run gets a snapshot. This is the larger item on
this list — do it on its own, after FI-1.

## FI-7 Retire the unattested `/uploads` reader

**Affects** C4a, C6 · *security*

`deps.device_or_legacy_reader` accepts an ID-token-only read of the resume list —
which carries Drive upload capability URIs — for testers on an older build. It is
deliberately temporary: any device header, or `REQUIRE_ATTESTED_UPLOADS=1`, still
forces full attestation, and the startup check warns while the flag is unset.

**Fix.** When `legacy_unattested_uploads` has been zero for a full release cycle,
set `REQUIRE_ATTESTED_UPLOADS=1`, then delete the wrapper and its branch in
`routers/sessions.py`. Ship the flag flip and the deletion separately so the flip
can be rolled back without a deploy of code.

## FI-9 Make the error-code contract one edit

**Affects** C1–C14, B1, B2 · §E2.7 · *debuggability*

`backend/app/errors.py` and `app/src/main/java/com/indicvision/semper/data/net/ApiErrors.kt` now hold the same
codes, and `backend/tests/test_error_codes.py` fails when they drift — but adding
a code the client branches on is still two edits in two languages.

**Fix.** Generate the Kotlin object from the Python module in a Gradle task (or
the reverse) so the second edit is a build step. Only worth doing if the code
list keeps growing; the contract test is enough while it does not.

## FI-14 Reuse debt in the newer workers and settings sections

Found by a reuse pass over the full branch-vs-`damodar` diff. None of it is a
defect today; all of it is the same shape — a new file written standalone rather
than against the helper its sibling already uses, so a future fix has to be made
twice.

- **`DicBundleDownloadWorker` is a clone of `DicRestoreWorker`**: the same
  `404 || 403 → give up` catch ladder (with raw literals, though
  `HttpStatus.NOT_FOUND`/`FORBIDDEN` exist and the package already imports
  them), a byte-identical `publishProgress`, and four WorkManager `Data` keys
  re-declared verbatim — which is precisely what `DicKeys` exists to prevent
  ("so a typo becomes a compile error instead of a silent fallback").
- **`SettingsYourDataSection.exportCloudAccountData` and `exportMyData`** are the
  same function twice; only the producer, two string resources and the MIME type
  differ. The `catch (CancellationException) { remove }` in both is dead — the
  `finally` two lines below already removes the banner.
- **The support-mail intent is built three times** (`SettingsHelpSupportSection`
  ×2, `PendingApprovalActivity` ×1), diagnostics body included.
- **`.part`/`.full` sidecar names**, which are `DriveTransfer`'s privates, are
  hand-rebuilt at five sites in `CloudRestore`; a rename there silently stops
  five cleanup paths from cleaning up.
- **The `tmp → renameTo → copy-fallback` promote idiom** now exists six times.
  One `util/AtomicFiles.promote(tmp, dest)` covers all of them.
- **Cache filenames** (`semper-account-export.json`, `roi_mask_cache.bin`,
  `temp_roi_ref.bin`) are spelled in both their writer and `CacheJanitor`'s
  reclaim set; rename one and the janitor silently stops reclaiming it. The same
  file already does this correctly for *directories*, via `EngineDebug.DIR_NAME`.
- **`routers/sessions.py`** repeats the session-ownership guard, the page-size
  clamp and the `page` response dict three times each; `routers/files.py` has the
  file-doc variant of the guard twice.

## FI-15 Lower-value efficiency items

Remaining from the 2026-08-31 efficiency pass (the three higher-impact items —
Firestore read order, SessionZip CRC reuse, viewer chrome animator guard — are
shipped):

- `DicUploadWorker`'s progress sampler writes to the WorkManager DB every 700 ms
  with no change guard (~650 of ~857 writes on a ten-minute backup are no-ops).
- `SettingsActivity` calls `listCompleted`, which has no cache, while
  `listRestorable`'s 60-second cache exists *because* "the settings page asks on
  every open".
- `session_provision` pages the session's file collection twice per provision.

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

## FI-2 Structured transfer logging for upload and restore

**Affects** B1, B2, B3 · §E2.2 · *debuggability, privacy*

`DicUploadWorker.doWork` is ~970 lines with ~38 log lines; `CloudRestore` is ~960
with 5. Both stay unsplit for good reasons, which makes the logs the only way in.
The backend already emits one structured event per request (`requestId`,
`opClass`, `outcome`, `errorCode`, `latencyMs`); the client emits prose.

**Fix.** A tiny `TransferLog` helper mirroring `backend/app/observability.py`'s
field names — phase, local session id, attempt, bytes, outcome, errorCode, and
the `X-Request-Id` the client now captures. One line per phase transition rather
than per iteration. Keep it PII-free: no specimen names, no file names, no paths.

**Blast radius.** Additive inside the existing methods; no restructuring. Pairs
with the `(ref: …)` correlation already carried in failure reasons.

## FI-3 Typed sweep-node provenance

**Affects** A5.3, A5.5, A7 · §E2.3 · *accuracy*

Skipped-combination provenance travels as four index-aligned `IntArray`s
(`SWEEP_SKIP_SUBSETS` / `_STEPS` / `_STRAIN_WINS` / `_CODES`). Nothing enforces
equal lengths or matching order, and the failure mode is a lattice node showing
another node's reason — a wrong explanation presented with full confidence.

**Fix.** One `SkippedNode(subset, step, strainWindow, code)` list, serialised as a
single extra (and stored the same way on the session record). Fold into FI-1 if
that lands first.

## FI-4 An unknown engine code must say it is unknown

**Affects** A5.5, A7 · §E2.4 · *accuracy*

`EngineFailure.cause` maps `0` and every unrecognised code to the VSG /
strain-window case. For a genuine zero-point solve that is the right guess; for a
code we have never seen it tells the user to change the strain window for a
failure nobody has diagnosed, and hides the new code from us entirely.

**Fix.** Keep the VSG wording for the codes that mean it. Add an explicit
unknown-cause branch: a truthful "the engine stopped for a reason this build does
not recognise" plus the raw code in the message, and a diagnostics event carrying
the code (a bucketed integer — no PII). Then a new engine failure mode shows up
in telemetry instead of being absorbed.

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

## FI-8 Say what the diagnostics consent actually covers — **done 2026-08-24**

**Affects** B10 · *privacy*

The toggle gated `analytics/SemperAnalytics` as well as Crashlytics while its
label named only crash reporting — the highest-priority item on this page by the
ranking above, even though the change was copy only.

**Shipped.** `setting_diagnostics` now reads **Send crash reports and usage
data**; `setting_diagnostics_sub` and `diagnostics_prompt_body` name the coarse
usage events alongside what is never sent (images, results, specimen names, file
paths). `docs/legal/PRIVACY_POLICY.md` §2.4 names the new label and the hosted
pages were regenerated with `scripts/render_legal_pages.py`. String **values**
only — no identifier changed, so no code moved and nothing about what is
collected changed.

## FI-9 Make the error-code contract one edit

**Affects** C1–C14, B1, B2 · §E2.7 · *debuggability*

`backend/app/errors.py` and `app/src/main/java/com/indicvision/semper/data/net/ApiErrors.kt` now hold the same
codes, and `backend/tests/test_error_codes.py` fails when they drift — but adding
a code the client branches on is still two edits in two languages.

**Fix.** Generate the Kotlin object from the Python module in a Gradle task (or
the reverse) so the second edit is a build step. Only worth doing if the code
list keeps growing; the contract test is enough while it does not.

## FI-11 Three 409s that are read by status, not by code

**Affects** A1, B1, C1, C2 · *accuracy, debuggability*

The typed error codes landed, but three sites still decided on the HTTP status
alone. Items 1–2 are **done** (2026-08-31 maintenance pass on PR #101); item 3
remains open — see [TECH_DEBT.md](TECH_DEBT.md) TD-6.

1. **`IndicApi.me()` maps any 409 to `DeviceConflictException`.** — **done
   2026-08-31.** `GET /v1/me` now branches on `DEVICE_IN_USE` vs
   `DEVICE_CONFLICT` via `throwForMeConflict`; added `DeviceInUseException`.
2. **`DicUploadWorker` treats every 409 as a full quota.** — **done
   2026-08-31.** `UploadWorkOutcomes.isQuotaExhausted(code, body)` tests
   `ApiErrors.hasCode(body, SESSION_QUOTA_EXCEEDED)`; `device_not_active` and
   other 409s no longer open the quota screen.
3. **`ApiException.detail` is misnamed.** It holds the whole response body, while
   `ApiErrors.detailOf` defines "detail" as the parsed field — so `e.detail` in a
   log line prints the JSON envelope. Rename it to `body` and add
   `val detail get() = ApiErrors.detailOf(body)`. Mechanical, but it changes what
   several existing log lines print, so it wants the test tier too.

## FI-10 Keep the workflow map honest automatically — **done 2026-08-24**

**Affects** all · *debuggability*

[../WORKFLOWS.md](../WORKFLOWS.md) names hundreds of files. A rotted map is worse
than none.

**Shipped.** `scripts/check_doc_paths.py` extracts every relative Markdown link
and every backticked repository path from the Markdown tree and fails when one
does not exist; it runs in the `legal-pages` CI job, which has no path filter and
so fires on every event. It checks only references that name a file, skips the
engine submodule, and carries a short allowlist for paths that are absent from a
clean checkout by design (the release keystore, generated gateway spec). It found
four stale references on its first run, all now fixed.

It deliberately does not verify prose — only that a path you are sent to exists.

---

## FI-12 Release logging is not consent-gated, and it carries identifiers

**Priority: highest.** This is the one item here that breaks a promise the
product makes in writing rather than merely making debugging harder.

`CrashReportingTree` logs to Crashlytics *and*, since the release-logging change,
mirrors every WARN/ERROR to logcat via `Log.println`. The Crashlytics half is
gated: `Diagnostics.apply()` flips `isCrashlyticsCollectionEnabled` from
`DicSettings.diagnosticsEnabled`. **The logcat half is gated by nothing** — it
writes in release builds whether or not the user consented.

That would be tolerable if the WARN/ERROR set were free of identifiers. It is
not, and three separate changes have pushed identifiers into exactly that tier:

| Where | What reaches the log |
|---|---|
| `DicUploadWorker` (8 sites, promoted `Timber.i` → `w`/`e`) | cloud and local session ids |
| `DicBundleDownloadWorker`, `SettingsActivity` | the SAF `content://` destination URI — the folder *and* document name the user picked |
| `DicBatchRunner` | `SessionStore.dirFor(...)`, an absolute path containing the session id |
| `SessionZip` (4 sites) | `raw/<the user's own deformed-image filename>` |

The `SessionZip` one is the worst of the four, because the exception it builds
travels two ways: `crashlytics.recordException` uploads the message off-device,
and `DicRestoreWorker` puts `e.message` into `KEY_ERROR`, which reaches the user
as a Toast reading *"Restore failed. Session.zip entry raw/IMG_4021.tif inflate
failed — corrupt transfer"*. A filename the user chose is thus both shown back to
them as an error and shipped to Crashlytics.

`CONTEXT.md:107` and this document's own §FI-2 both state the rule: no session
ids, no file names, no paths. The log tier was enforcing it by accident —
`isLoggable` dropped everything below WARN — and promoting those lines removed
the accident without replacing it with anything.

**Fix, in order of value:**

1. Gate the `Log.println` mirror on `DicSettings.diagnosticsEnabled` (or on
   `BuildConfig.DEBUG` alone, if field debugging was the intent). One condition.
2. Drop the identifier from the eight promoted `DicUploadWorker` messages —
   keep the promotion, which is operationally sound, and log a non-identifying
   discriminator instead.
3. Log the failure without the target in the four path/URI sites:
   `Timber.e(it, "Write Session.zip to destination failed")`.
4. Give the `SessionZip` exceptions a code-only message and keep the entry name
   on the `cause` (it is already chained). This pairs with FI-13.

**Blast radius:** small and local — one condition plus twelve message strings.
Not applied here only because none of it can be compiled or run in the current
environment (no Android SDK: `dl.google.com` is blocked by egress policy).

---

## FI-13 A retry decision keyed on an error message's wording

`RestoreDownloadOutcomes.isTerminalCorruptFailure` decides whether a failed
restore is retried forever or given up on:

```kotlin
if (error is ZipException) return true
return error.message.orEmpty().contains("corrupt transfer", ignoreCase = true)
```

The phrase `"corrupt transfer"` is hand-typed at ten sites across `SessionZip`
and `CloudRestore`. Reword any one of them — including while fixing FI-12, which
touches four of the ten — and that failure silently stops being terminal and
becomes an infinite WorkManager retry loop against a file that will never
succeed.

**Fix:** `class CorruptTransferException(message: String, cause: Throwable? = null)
: IllegalArgumentException(message, cause)`, thrown at all ten sites;
`isTerminalCorruptFailure` becomes an `is` check. This also removes the reason
the message had to carry the entry name, so it should be done together with
FI-12 item 4.

**Blast radius:** ten throw sites and one predicate, all in `data/`. Behavioural
— it changes what the retry classifier keys on — so it needs the unit tier
(`RestoreDownloadOutcomesTest`) to run, which this environment cannot do.

---

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
- **`SessionZip.crc32`** is duplicated verbatim within the same object
  (`:326` and `:429`), and the second copy is also redundant work — see FI-15.
- **Cache filenames** (`semper-account-export.json`, `roi_mask_cache.bin`,
  `temp_roi_ref.bin`) are spelled in both their writer and `CacheJanitor`'s
  reclaim set; rename one and the janitor silently stops reclaiming it. The same
  file already does this correctly for *directories*, via `EngineDebug.DIR_NAME`.
- **`routers/sessions.py`** repeats the session-ownership guard, the page-size
  clamp and the `page` response dict three times each; `routers/files.py` has the
  file-doc variant of the guard twice.

---

## FI-15 Three efficiency defects worth fixing before they compound

Quantified by an efficiency pass over the same diff. Listed together because each
is a guard or a reordering, not a restructure.

1. **One extra Firestore read on every authenticated request.**
   `get_or_create_user` reads `auth_links/{sub}` *before* `users/{sub}`, so the
   common path does two reads where it used to do one. Reading `users/{uid}`
   first and consulting `auth_links` only on a miss is behaviour-identical,
   because `users/{alias}` is never created for a linked alias. Worst on restore,
   where every download window is two backend calls: roughly −2 reads per window.

2. **Every backed-up byte is read from disk three times.** `SessionZip` reads a
   STORED member once for the mandatory CRC pre-pass, once to copy it in, and a
   third time in `verifyRoundTrip` → `crc32(member.file)`. That third read is
   also tautological: it compares the *source* CRC against the *source* CRC and
   never touches the archived bytes. Returning the CRC that `putStored` already
   computed and comparing it to `entry.crc` keeps the guarantee and cuts prepare
   I/O by a third — roughly 700 MB of flash reads per 60-frame backup.

3. **An animator storm on the viewer's primary gesture.** `TouchImageView`
   calls `publishMatrix()` on every raw touch sample, including pre-slop moves
   where the matrix provably did not change, and `bumpChrome` has no
   `chromeVisible` guard — so it cancels four `ViewPropertyAnimator`s per sample.
   At 120–240 Hz that is ~480–960 cancels per second of panning. `setContentInsets`
   in the same file already uses exactly the guard that is missing here.

Lower-value, same pass: `DicUploadWorker`'s progress sampler writes to the
WorkManager DB every 700 ms with no change guard (~650 of ~857 writes on a
ten-minute backup are no-ops); `SettingsActivity` calls `listCompleted`, which
has no cache, while `listRestorable`'s 60-second cache exists *because* "the
settings page asks on every open"; and `session_provision` pages the session's
file collection twice per provision.

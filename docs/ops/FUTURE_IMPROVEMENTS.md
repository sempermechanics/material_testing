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

~25 `DicKeys` extras were packed in two places — `AnalysisNavHelper.openResults`
(fresh run) and `ui/home/SessionOpenHelper.intentFor` (reopen) — and are read in
four (`ResultViewerActivity`, `VsgLatticeActivity`, `ViewerSettingsSheet`,
`ViewerReportFactory`). The two packers did not write the same set, and a missing
extra silently defaults, so the ⓘ sheet, the report header and the export
filename could differ by entry path with nothing logged.

**Half of it has landed.** `ViewerArgs` (`ui/viewer/`) is now the only writer:
both entry points construct it and call `toIntent`, and `ViewerArgsTest` asserts
their key sets against each other, so the two cannot drift apart again. Only the
post-run launch's two extra keys differ, and the test names them.

**Fix, what remains.** The unpack half: `fun from(intent)` on the same type, with
the four readers taking the parsed object and a missing required field failing
loudly (or resolving from the session record) instead of defaulting. Kept
separate deliberately — the write side is one file and no format change, while
the read side also has to keep working for an Intent already sitting in the back
stack when the app updates.

**Blast radius.** Four UI files and the lattice→viewer hop. No format, no
`.dat`, no wire change.

Already flagged as "next architecture" in [../../CONTEXT.md](../../CONTEXT.md)
and in [TECH_DEBT.md](TECH_DEBT.md) — this is the concrete shape.

**Decided (2026-09-23):** [ADR-003](../adr/ADR-003-viewerargs-read-side.md).
The audit found the readers' defaults disagree (subset 41 vs 0, step 5 vs 1,
ROI image-size vs 0), and the two writers disagree on values — the fresh run
passes the requested ROI while the session saves the resolved one (TD-61) — so
the read side resolves a missing field from the `SessionRecord` before falling
back to one documented default.

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

`StaticAnalysisActivity` (1,827 lines), 18 helper/controller types and 47
public `var`s on `AnalysisViewModel` (counted 2026-09-23) collectively hold
what a run will use; subset, step, overlap, strain window and interpolator
exist only in slider/radio views until params are built. The authoritative
record only exists once `SessionRepository.buildSessionRecord` assembles it at
commit. "What settings did this run actually use?" has no single answer until
then — and the viewer opened straight after a run reads the ViewModel instead,
which is TD-61. **Decided:** [ADR-004](../adr/ADR-004-runspec.md).

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
- **The support-mail intent is built four times** (`SettingsHelpSupportSection`
  ×2, `PendingApprovalActivity` ×1, `ui/limit/SessionLimitActivity.kt:83`),
  diagnostics body included.
- **`.part`/`.full` sidecar names** have no constant anywhere: they are spelled
  inline in `DriveTransfer` (:263, :317), in `IndicApi.kt:213`, and hand-rebuilt
  at five sites in `CloudRestore` (:309, :355, :598, :645, :767); a rename in one
  place silently stops the other cleanup paths from cleaning up.
- **The `tmp → renameTo → copy-fallback` promote idiom** exists six times
  exactly (`DriveTransfer.kt:350,497`, `IndicApi.kt:219`,
  `SessionEverythingExporter.kt:91`, `SessionStore.kt:467`, `SessionZip.kt:231`)
  plus two variants (`SessionRepository.kt:105`, `DicBatchRunner.kt:182`).
  One `util/AtomicFiles.promote(tmp, dest)` covers all of them.
- **Cache filenames** (`semper-account-export.json`, `roi_mask_cache.bin`,
  `temp_roi_ref.bin`) are spelled in both their writer and `CacheJanitor`'s
  reclaim set; rename one and the janitor silently stops reclaiming it. The
  `"share"` subdirectory is worse: `CacheJanitor.SHARE_SUBDIR` is private and six
  writers spell the name themselves. `EngineDebug.DIR_NAME` shows the right
  pattern.
- **`routers/sessions.py`** repeats the session-ownership guard four times
  (:119, :162, :199, :305), and the page-size clamp (with two different
  ceilings, 100 and 1000) and the `page` response dict three times each;
  `routers/files.py` has the file-doc variant of the guard twice.

All of these are scheduled in the tech-debt burn-down (TD-54 and the app-reuse
PR in [TECH_DEBT.md](TECH_DEBT.md)).

## FI-16 Licensing: the three things scale will find first

**Affects** §9 · *accuracy, cost*

Licensing landed after the traceability pass that produced this file, so it has
no `A*`/`B*`/`C*` id. Nothing below is wrong today; all three are answers that
hold at the size we are now and stop holding at some larger one, recorded here
so the first customer to hit one is not the first person to think about it.

**The bundle download is bounded by a request, not by its size.**
`GET /v1/sessions/{sid}/bundle` already streams — it never buffers the archive —
but Cloud Run still ends the request on its own timeout, and
`MAX_FILES_PER_SESSION` is 600. A slow client on a large analysis can therefore
be cut off with no partial result and no way to resume. The pattern for this is
already in the tree: mint the archive out of band through Cloud Tasks
([`backend/app/tasks.py`](../../backend/app/tasks.py), which provisions sessions the
same way) and answer with a link. Worth doing when a real download first fails,
not before — the failure is visible and costs the user only a retry.

**Reconciliation costs one user read per seat.**
`reconcile_institution_seats` is admin-tier and on demand, so a 40-seat roster
is 40 reads when a human asks. A 2000-seat one is 2000, and the operator
console offers the button per licence with nothing between it and the click.
The cheap fix is a cap with a "showing the first N" note; the durable one is to
denormalise the answer — stamp the seat when the holder is next seen, so the
report is a subcollection scan and no user reads at all. The reads are also
issued serially today (`firestore_repo.py` `reconcile_institution_seats`);
batching them with `db().get_all()` cuts latency at no extra cost (TD-62).

**Four hours is the worst case for a revoke reaching an idle device.**
`LicenseConfigWorker` refreshes `/v1/config` every four hours, so an account
that is revoked and then does nothing keeps working until that fires — by
design, and `revokedStillRunning` in the operator console is precisely the
window made visible (§20.12 of
[CLOUD_ARCHITECTURE_GCP.md](../backend/CLOUD_ARCHITECTURE_GCP.md)). If a
customer ever needs a revoke to land faster than that, the interval is not the
lever to reach for first: shortening it costs every device every day. A push
message to the revoked account is the targeted version of the same thing.

## FI-15 Lower-value efficiency items

Remaining from the 2026-08-31 efficiency pass (the three higher-impact items —
Firestore read order, SessionZip CRC reuse, viewer chrome animator guard — are
shipped):

- `DicUploadWorker`'s progress sampler writes to the WorkManager DB every 700 ms
  with no change guard (`DicUploadWorker.kt:446-458`; the "~650 of ~857 writes"
  estimate is from a measured run, not re-checkable from the code).
- `SettingsActivity` calls `listCompleted` (`SettingsActivity.kt:240`), which has
  no cache. The earlier proposal to reuse `listRestorable`'s 60-second cache was
  based on a wrong premise: `listRestorable` / `listRestorableSessions` have **no
  live caller** (`CloudRestore.kt:209-277`), so that cache is dead code. The fix
  is to move the cache onto `listCompleted` and delete the dead pair (TD-51).
- A session's file collection is paged twice on the **inline** provision path —
  once in `session_provision.py:51` and again by `list_pending_uploads` in
  `routers/sessions.py:469` — not twice inside `session_provision` as previously
  written. Each page also costs one extra document read to resolve its cursor
  (`firestore_repo.py:2985-2987`).

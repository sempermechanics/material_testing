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

## FI-8 Say what the diagnostics consent actually covers

**Affects** B10 · *privacy* · already tracked

The **Send crash reports** toggle gates `analytics/SemperAnalytics` as well as
Crashlytics. The events are PII-free buckets and
[the privacy policy](../legal/PRIVACY_POLICY.md) §2.4 describes both, but the
in-app label and the first-run prompt name only crash reporting. Fix is a copy
change to `setting_diagnostics` and `diagnostics_prompt_body`. Tracked in
[TECH_DEBT.md](TECH_DEBT.md); repeated here because a consent string that
understates its scope is the highest-priority item on this page by the ranking
above, even though the change is two lines.

## FI-9 Make the error-code contract one edit

**Affects** C1–C14, B1, B2 · §E2.7 · *debuggability*

`backend/app/errors.py` and `app/src/main/java/com/indicvision/semper/data/net/ApiErrors.kt` now hold the same
codes, and `backend/tests/test_error_codes.py` fails when they drift — but adding
a code the client branches on is still two edits in two languages.

**Fix.** Generate the Kotlin object from the Python module in a Gradle task (or
the reverse) so the second edit is a build step. Only worth doing if the code
list keeps growing; the contract test is enough while it does not.

## FI-10 Keep the workflow map honest automatically

**Affects** all · *debuggability*

[../WORKFLOWS.md](../WORKFLOWS.md) names hundreds of files. A rotted map is worse
than none.

**Fix.** A CI step (Tier 1 or the docs job) that extracts every backticked repo
path from `docs/**.md` and fails when one no longer exists. Cheap, and it catches
the common rot — renames — without pretending to verify prose. §E3 of the map has
the shell one-liner it would run.

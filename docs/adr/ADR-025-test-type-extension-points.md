# ADR-025: Test types plug into Semper through extension points, and Material Testing becomes a product flavour

**Status:** Accepted (product owner, 2026-10-22); phases 1–5 under TD-298, smallest first
**Date:** 2026-10-19
**Deciders:** app owner
**Relates to:** material_testing's ADR-009 (its own app on this backend), ADR-012
(tensile strain) and ADR-022-semper-ui-plus-lab-tests (that number collides with
this repo's [ADR-022](ADR-022-rerun-as-copy.md)); [ADR-014](ADR-014-session-app-tag.md),
[ADR-015](ADR-015-package-layout.md), [FORK_SYNC](../ops/FORK_SYNC.md)

Paths are under `app/src/main/java/com/sempermechanics/semper/` unless they start
at the repo root. `mt:` marks a path in material_testing.

**Mirrored here from semperdic-app** (its `docs/adr/ADR-025-test-type-extension-points.md`,
as of its #457 on 2026-10-19), because the decision is about this repo's future as much
as that one's. The text is semperdic-app's: "this repo" means semperdic-app, an
un-prefixed path is in its tree at `763ed7a`, and an `mt:` path is in this repo at
`511ddba`. Its "ADR-022" is semperdic-app's Re-run as a copy
([ADR-022-rerun-as-copy](ADR-022-rerun-as-copy.md), here too since the 2026-10-19 sync);
this repo's own ADR-022 is [ADR-022-semper-ui-plus-lab-tests](ADR-022-semper-ui-plus-lab-tests.md).
The two numbers collided before this record; it names the fork's by file name. TD
numbers are shared ([../ops/TECH_DEBT.md](../ops/TECH_DEBT.md)). Edit semperdic-app's
copy first and mirror the change.
Since the sync of semperdic-app `14b279d6` (2026-10-21) the fork's lab wizard state is
one field of the wizard's inputs (`WizardInputs.lab`, a `LabWizardState`), changed through
`AnalysisViewModel.edit` like the rest, not the view model's lab vars the Wizard row
below counts, and a run's result reads the lab test from its saved row
(`ViewerArgs.of`), not from `AnalysisNavHelper`.

## Context

material_testing (Material Testing, `com.sempermechanics.materialtesting`) is this
app plus a tensile and a bending test. It takes `main` by `git merge`
([FORK_SYNC](../ops/FORK_SYNC.md)). Its ADR-022 keeps the difference small: a sync
takes this repo's version of a shared file and puts the lab hooks back by hand. Its
ADR-009 and ADR-022 both call a build flavour here "the cleaner end state; not now".

**The inventory** (2026-10-19: fork `511ddba`, this repo `763ed7a`, the fork's last
sync `8ba7081` of 2026-10-14). "Fork code" means a line in the fork's copy of a file
this repo also has that is in neither `8ba7081` nor `763ed7a`. Comments and imports
are left out. This keeps changes made here since the sync, and fixes ported both
ways, out of the count.

- **53 lab-only Kotlin files** (6,716 lines): `data/mechanical/` 8, `report/` 14,
  `ui/analysis/load/` 10, `ui/viewer/mechanical/` 7 and 14 more. There are also 12
  lab-only layouts and `BeamEdgeTapActivity` in the manifest. They merge without
  conflicts and are not the problem.
- **About 54 lab branch points in 23 shared Kotlin files**, counting conditionals on
  a lab value. A pattern count over the fork code finds 47 such lines in 20 files that
  name a lab field outright, plus the `resultsOnScreen` tests in
  `ViewerChromeController`, `ViewerCaptions` and `ResultViewerActivity`. Fork code reaches further,
  into **50** shared files (494 lines). 34 of those files name a lab concept outright;
  the rest carry plumbing and the general features listed below. By the extension
  each one would become:

| Area | Where (`mt:`) | What the fork adds |
|---|---|---|
| Session | `data/session/SessionRecord.kt:128-153`, `:179`; `SessionStore.kt:208`, `:224`; `SessionRepository.kt`; `data/cloud/SessionMetadataDoc.kt:107`, `:282`; `SessionUploadMetadata.kt:42` | Eight record fields (`testType`, `crossSectionMm2`, `loadAxisX`, `geometry`, `curveCorrection`, `loadsN`, `loadSource`, `loadMapping`), `hasMachineLoads`, a `LoadsSerializer`, two setters, and the metadata `test` object and per-frame `loadN` (`withTestDoc`) |
| Wizard | `ui/analysis/wizard/AnalysisReadyGate.kt:28`, `:91-127`; `AnalysisViewModel.kt:168-201`; `WizardState.kt:70-72`, `:169-176`; `WizardDraftBinding.kt:125`; `data/prefs/WizardDraft.kt:48`, `:126`; `ui/analysis/StaticAnalysisActivity.kt:113-120`, `:156`, `:161`; `ui/analysis/run/RunSpec.kt` (`mechanical`); `AnalysisNavHelper.kt:130` | The load card, the ready gate and its reasons ("why Next is off"), the lab ViewModel vars, saved-state keys, the draft's load log, two result launchers |
| CSV | `report/AnalysisCsvWriter.kt:46-57` (one `LabSession`), `:68`, `:82`, `:189`, `:200`, `:210`, `:261-262`, `:286`, `:309` | Version 2, the test preamble, `load_N,stress_MPa` columns, a `# mechanical_results` trailer |
| Report | `report/PdfReportGenerator.kt:125`, `:231`; `report/ReportModels.kt:61` (`MechanicalCover`); `data/cloud/SessionUploadBundler.kt:422-430`; `ui/viewer/share/ViewerReportFactory.kt:69` | The cover's test block and the stress–strain pages |
| Viewer | `ui/viewer/summary/ViewerSummaryController.kt:53`, `:67`, `:116-170`, `:189`; `ResultViewerActivity.kt:127-133`, `:415-416`; `ViewerChromeController.kt:74-88`; `ViewerCaptions.kt:62`; `SettingsUsedSheet.kt:82`; `ViewerArgs.kt:290-297` (19 lines in all); `ui/home/SessionOpenHelper.kt:72-77` | Results in place of the summary animation, the chrome and title for it, a settings-used row, six lab arguments read both ways |
| Share | `ui/viewer/share/ShareCenter.kt:74-83`, `:123`; `ShareKind.kt:33` (`LAB_PDF`); `ShareExportBuilder.kt:36`, `:78`; `DataExport.kt:24`, `:100`, `:113` | The lab-report row and its export, and the PDF's closing page |
| New analysis | `ui/home/HomeActivity.kt:126-141` (`pendingTestType`); `HomeNewAnalysis.kt`, `ui/common/TestTypeSheet.kt` (lab-only) | **Which test?** before the media picker |

- **8 `IntentKeys`** (`mt:navigation/IntentKeys.kt:26-63`): `TEST_TYPE`,
  `CROSS_SECTION_MM2`, `LOAD_AXIS_X`, `LOADS_N`, `SPECIMEN_GEOMETRY`,
  `CURVE_CORRECTION`, `BEAM_THICKNESS_MM`, `BEAM_EDGE_TAPS`.
- **4 shared layouts** (in `mt:app/src/main/res/layout/`):
  `activity_result_viewer.xml` (105 lines added since the sync: `summaryResults`
  `:62`, `plotSummaryResults` `:135`), `sheet_settings_used.xml` (200 added, 100
  removed: the Results panel a second time), `activity_static_analysis.xml` (the load
  card's `ViewStub`, `:339`) and `sheet_share.xml` (`rowShareLabReport`, `:130`).
- **General features that sit in the fork as fork code**, not lab hooks:
  - plot marks and a scrub label that reads the point
    (`mt:ui/analysis/sweep/SweepPlotView.kt:62`, `:162`; `SweepPlotAxes.kt`);
  - frame capture times (`mt:ui/analysis/frames/DeformedFrame.kt:29`,
    `mt:imaging/video/VideoFrameExtractor.kt:58`, `ReferenceImportController.kt`,
    `AnalysisViewModel.kt:194-201`);
  - helpers moved out of shared files (`SweepPlotBitmap.kt`, `SweepPlotScrub.kt`,
    `util/DocumentText.kt`);
  - members widened from `private` to `internal` (`SweepPlotView.kt:108`, `:136`,
    `:170`, `:180`; `AnalysisCsvWriter.escape`).

**What the merge model costs.**

1. **Every sync merges the hooks again.** Since `8ba7081` this repo has changed 128
   main Kotlin files in 115 commits. 21 of those files are among the 50 that hold
   fork code. The fork still has `ui/analysis/run/UnsavedRerun.kt`, which ADR-022 deleted
   here, and it lacks the nine files that ADR-022 and the cancel-undo work added
   (`ui/analysis/rerun/`, `run/CancelledRun.kt`, …). So the fork's re-run has a bug
   that no longer exists here (the 2026-10-19 review's M15).
2. **Fixes to shared code cannot be cherry-picked.** The same review's UI fixes touch
   `RunChannels`, `SweepAnalysis`, `AnalysisViewModel`, `StaticAnalysisActivity`
   and `ViewerSummaryController`. The fork hooks into all of them, so those fixes
   reach it only through the next merge.
3. **The undeployed copies drift.** The fork carries `backend/` (177 files),
   `firebase-hosting/` (69), `firestore.rules`, `contracts/` (15) and `ops/` (25).
   They deploy only from this repo (`.github/workflows/deploy-backend.yml:81`,
   `if: github.repository == 'sempermechanics/semperdic-app'`; `mt:CONTEXT.md:289`).
   Twelve backend files already differ (205 lines of fixes missing), along with
   `scripts/check_secret_access.sh`. Three rows closed here on 2026-10-19 are still
   open there: TD-269, TD-270 and TD-276 (`mt:docs/ops/TECH_DEBT.md:257`, `:258`,
   `:263`). Nothing reads those copies except the fork's own CI. (Later the same day
   material_testing#139 synced `17d7bc8`, with #454, and closed those three there too;
   the copies still drift with every backend change here until they are deleted.)
4. **One lab state takes many shapes.** A lab input has to be added in
   `MechanicalTestInputs`, `RunSpec.mechanical`, `ViewerArgs` (both sides),
   `WizardState` and the draft. If one is missed, a viewer or a restored wizard
   quietly reads "no test" (`mt:CONTEXT.md:290`). This repo cannot see that
   contract, so a refactor here can break the fork without any warning: the review's
   `ViewerArgs.of(record)` (U2) and `WizardInputs` (U6) would both do so.

## Decision

Shared code exposes **seven extension points**, each with a default that does
nothing. The lab tests implement them in their own source set, and Material Testing
becomes a **product flavour** of this repo. A general feature the fork built goes to
`main` first, as ordinary Semper code.

### 1. Move the general features to `main` first

- `SweepPlotView.Mark` and `scrubShowsPoint`, so any plot can call out a point; the
  moved helpers (`SweepPlotBitmap`, `SweepPlotScrub`, `DocumentText`); the widened
  members, as the fork has them.
- Frame capture times: `DeformedFrame.captureTimeMs` / `captureSubSec`, the video
  reference's `timeMs`, `AnalysisViewModel.refCapture` / `refVideoTimeMs`,
  `data/mechanical/CaptureStamp` and `PhotoCaptureTime` (the EXIF read). Semper records them and passes them along, and does not use them
  yet.

Each one lands here as a Semper commit with its tests. After the next sync those fork
lines match this repo's, and they leave the inventory.

### 2. `com.sempermechanics.semper.ext`

`AppExtensions` holds one instance of each interface. Every method's default does
what Semper does today, so the `semper` flavour installs nothing.

```kotlin
object AppExtensions {
    var session: SessionExtension = SessionExtension.None; private set
    var wizard: WizardExtension = WizardExtension.None; private set
    // csv, report, viewer, share, newAnalysis likewise
    /** Once, before Application.onCreate returns; a second call throws. */
    fun install(session: SessionExtension = session, /* … */) { … }
    @VisibleForTesting fun resetForTest() { … }
}
```

| Interface | Called from | Replaces in the fork |
|---|---|---|
| `SessionExtension` + `SessionExtras` | `SessionRecord.extras`, `SessionStore`'s reader, `SessionMetadataDoc` upload and restore | The eight record fields, `LoadsSerializer`, `withTestDoc`, the two setters |
| `WizardExtension` | `AnalysisReadyGate.apply`; `StaticAnalysisActivity.onCreate` (one `ViewStub` slot and its result launchers); `WizardState` and `WizardDraft` save and restore; `RunSpec.extras` | The load card, the ready-gate reasons, the lab ViewModel vars, the saved-state keys |
| `CsvExtension` | `AnalysisCsvWriter`: version, preamble block, point-header suffix, per-frame suffix, trailer | The `isLabTest` branches and the `MechanicalCsv` calls |
| `ReportExtension` | `PdfReportGenerator` (a cover block, extra pages); `SessionUploadBundler`'s report | `MechanicalCover`, `drawStressStrainPages` |
| `ViewerExtension` | `ViewerSummaryController` (a panel shown in place of the animation); `ViewerChromeController`; `ViewerCaptions`; `SettingsUsedSheet` rows; `ResultViewerActivity` saved state | The `showsResults` / `resultsOnScreen` branches |
| `ShareExtension` | `ShareCenter` rows, `ShareExportBuilder`, `DataExport` | `LAB_PDF`, `rowShareLabReport`, `LabExport` |
| `NewAnalysisExtension` | Home's **+**, before the media picker; its answer goes on the wizard's Intent as `SessionExtras` | `pendingTestType` and the `TestTypeSheet` hand-off |

**Rules for the seams.**

- **`SessionExtras` is the one lab value.** It is an immutable value that the
  extension owns. `SessionRecord` stores it as one JSON object (`extras`), and
  `RunSpec`, `ViewerArgs` (one Bundle extra, `IntentKeys.EXTRAS`) and the wizard's
  saved state carry the same bytes. Shared code passes it along and never reads what
  is inside. This is the `LabSession` value the review proposes for the fork (M12),
  with a home here. The 8 lab `IntentKeys` become one, and a new lab input is added
  in one type, not five.
- **Few, coarse calls.** The ready gate asks the wizard extension once for a blocker.
  The CSV asks for one block per section. The viewer asks for a summary panel once
  per show. No hook sits on a single line of shared logic.
- **The hot loops stay whole.** The CSV row loop gets the frame's suffix as a string
  computed once per frame, the same way the motion columns already arrive
  (`writeFrame`, `report/AnalysisCsvWriter.kt:272-281`). No extension call goes inside
  `BatchAnalysis.runBatchAnalysisBody`, `ReportBuilder.buildReport`, the heatmap
  loops or `decodeDatFile`. The `.dat` and GIF oracles do not move.
- **Enums do not grow.** `ShareKind` keeps Semper's entries. An extension's share row
  has its own id and builds its own file.
- **Strings and layouts** that an extension needs live in its source set. A shared
  layout gets at most one empty slot (`ViewStub` or `FrameLayout`) per extension,
  as `mt:app/src/main/res/layout/activity_static_analysis.xml:339` already does for the load card.

### 3. A `materialTesting` product flavour, registered through androidx.startup

- `flavorDimensions += "product"`, with two flavours: `semper`
  (`applicationId com.sempermechanics.semper`) and `materialTesting`
  (`com.sempermechanics.materialtesting`). `namespace` stays
  `com.sempermechanics.semper` ([ADR-019](ADR-019-sempermechanics-app-id.md)), so
  no shared source package moves.
- `app/src/materialTesting/` holds the 53 lab files, their layouts and strings,
  `BeamEdgeTapActivity`, the flavour's `google-services.json`, and
  `LabExtensionsInitializer` (an `Initializer<Unit>` that calls
  `AppExtensions.install`). The initializer is declared under
  `androidx.startup.InitializationProvider` in that source set's manifest. The
  `semper` manifest declares none, so the merged manifest decides which product a
  build is. There is no `BuildConfig` flag and no reflection.
- Signing, the release workflow, `versionCode` (passed in by the release workflow)
  and the backend's per-app release gate (`settings.release_gate(app)`) stay per
  product, as ADR-009 and [ADR-023](ADR-023-signed-offline-entitlement.md) set them.

### 4. Delete the fork's copies

Once Material Testing builds here, material_testing keeps its history and its
releases page and stops syncing. Its `backend/`, `firebase-hosting/`,
`firestore.rules`, `contracts/` and `ops/` are deleted. Nothing deploys from them.

## Options considered

### A: Status quo, a fork synced by merge (the fork's ADR-022)

| Dimension | Assessment |
|---|---|
| Complexity | Low today, with nothing to build; it rises with every hook |
| Cost | Each sync merges the hooked files by hand (21 of the 50 on the next one); fixes to shared files wait for a sync; two copies of the backend and console |
| Scalability | A third test type adds hooks to the same files; a third product means a third fork |
| Familiarity | High: plain git, and FORK_SYNC documents it |

### B: Extension points and a product flavour (recommended)

| Dimension | Assessment |
|---|---|
| Complexity | Medium: seven interfaces, one registry, one flavour dimension |
| Cost | About 50 shared files to rewire, once, in phases; CI builds two variants |
| Scalability | A new test type is code in the flavour's source set; a new product is a new flavour |
| Familiarity | Flavours and androidx.startup are standard Android; the interfaces are new and are documented here |

### C: Split into library modules (`:core`, plus one app module per product)

| Dimension | Assessment |
|---|---|
| Complexity | High: about 430 main Kotlin files move into `:core`; R and ViewBinding cross modules; JNI and the benchmark harness sit in a library |
| Cost | Everything in B, plus the move and per-module Kover, detekt and lint setup |
| Scalability | Best: products are separate modules with boundaries the build enforces |
| Familiarity | Common in larger apps but new here; ADR-015's moves stayed inside one module |

C needs the same seams as B, because a `:core` library cannot know about the lab
either. So B is a step towards C, not a rival to it. C is worth its cost if a third
product arrives or the core is reused outside an app.

## Trade-off analysis

- **Lab code joins this repo's build.** Semper's APK contains none of it, because the
  flavour's source set is not compiled into `semper`. But this repo's CI builds and
  tests both variants. Compile and unit-test time in tier 1 roughly doubles unless
  the `materialTesting` variant runs on a narrower trigger.
- **The interfaces are a contract that Semper must keep.** In return, a refactor here
  sees the lab's needs when it compiles (the `materialTesting` variant fails to
  build), not at the next sync.
- **An interface with one real implementor** is the kind of indirection the review
  removes elsewhere (U5's `Host`). Here the second implementor is the default that
  does nothing, and the alternative is a branch in shared code, about 54 of them.
- **androidx.startup is a new dependency for one initializer.** A flavour
  `object ProductExtensions { fun install() }` called from `SemperApp.onCreate` would
  need none. androidx.startup is chosen because then the merged manifest says which
  product a build is, rather than two same-named classes in two source sets, and the
  extensions are installed before `SemperApp.onCreate` starts its work. Either
  choice can be undone.
- **Phases 1–3 keep the fork and its merges.** The gain shows at each sync, as
  conflicts shrink to the lab files, before any build change.

## Consequences

- **The fork's diff to shared Kotlin falls to about zero.** After phase 3 none of
  the 50 files holds fork code; what remains is the extension implementations,
  inside lab files. A sync conflicts only where this repo changes an extension
  interface.
- **Backup metadata stays compatible.** The fork writes
  `indic.session.metadata/7` (`mt:data/cloud/SessionUploadMetadata.kt:42`). Its
  `/4`–`/6` are lab fields while this repo's `/4` is the `ref/` layout, and no
  reader branches on a number past `/3`. The `materialTesting` flavour keeps writing
  `/7`, with the `test` object and per-frame `loadN` under the same keys;
  `SessionExtension` writes and reads them. Restoring a backup from today's fork,
  or an older one (`/4`–`/6`), gives the same `SessionExtras`. The `semper`
  flavour never restores those backups, because ADR-014 gates the restore manifest
  by app.
- **The local index stays compatible.** Today the fork's `index.json` keeps the
  eight lab fields at the top level of each record. `SessionStore` reads with
  `ignoreUnknownKeys` (`data/session/SessionStore.kt:43`), so the first build
  on extensions would drop those fields on its next write. To prevent that,
  `SessionExtension.legacyKeys` names them, and the record reader moves them into
  `extras` before decoding. A test reads a 2026-10 fork `index.json` and writes it
  back with the same meaning. The draft's load log (`loads.csv`,
  `mt:data/prefs/WizardDraft.kt:126`) is handled the same way.
- **The CSV versions stay as they are:** 1 for Semper and plain 2D DIC, 2 for a lab
  test, as the fork's ADR-022 set. `CsvExtension`'s acceptance test is
  byte-identical output (`AnalysisCsvPreambleTest` on both variants).
- **Commands change.** Tasks name a variant (`:app:testSemperDebugUnitTest`,
  `:app:assembleMaterialTestingRelease`, `koverVerify` per variant). CLAUDE.md,
  CONTRIBUTING, [CI](../ops/CI.md) and the benchmark harness change in the flavour
  PR. Coverage floors are set per variant.
- **Pinned names stay put** (ADR-015). `BeamEdgeTapActivity` keeps its fully
  qualified name in the flavour source set; the workers and `SemperNativeLib` are
  shared. The first `materialTesting` release installs over the last fork build
  (same `applicationId`, same key) only if its `versionCode` is higher.
- **The copies go last.** While the fork still merges, a directory it deleted but
  this repo still modifies gives a modify/delete conflict at every sync, settled by
  deleting it again. So phase 5 comes after the flavour, once merges stop. The rows
  still open only in the fork (TD-269, TD-270, TD-276) close with the deletion,
  since they are fixed here.
- **Superseded in part once phase 4 lands:** material_testing's ADR-009 ("own repo"
  becomes "own flavour") and its ADR-022 ("Semper's UI plus two test choices" is
  then held by the compiler, not by sync discipline).

## Action items

Phased, smallest first. Every phase can ship on its own and leaves both apps working.

1. [ ] **Move the general features to `main`** (Decision 1), then sync them into the
   fork, which drops its copies of those lines.
2. [ ] **`SessionExtras` with `SessionExtension`, and `CsvExtension`.** Here: the
   `ext/` package with the registry and those two interfaces; `SessionRecord.extras`
   and the legacy-key reader; metadata `test` and `loadN` through the extension; the
   `AnalysisCsvWriter` seams. In the fork: `LabSession` as its `SessionExtras`, and
   `MechanicalCsv` behind `CsvExtension`, installed from `SemperApp.onCreate` until
   phase 4. Tests: a legacy `index.json` round trip, `/4`–`/7` metadata restores,
   and v1 and v2 CSVs that stay byte-identical.
   *In progress:* the `ext/` package (`AppExtensions`, `SessionExtension.legacyKeys`),
   `SessionRecord.extras` and the legacy-key reader (`data/session/SessionIndexJson.kt`)
   have landed, with the legacy `index.json` round trip (`SessionIndexExtrasTest`).
   Still to come: a typed `SessionExtras`, metadata `test` and `loadN`, the draft's
   `loads.csv`, and `CsvExtension`.
3. [ ] **`WizardExtension`, `ViewerExtension`, `ShareExtension`, `ReportExtension`
   and `NewAnalysisExtension`.** One PR each, with one shared layout slot each. The
   lab `IntentKeys` become `EXTRAS`. Then re-count the fork's shared-Kotlin diff
   with the method in Context; the target is about zero.
4. [ ] **The flavour.** The `materialTesting` source set (the lab files moved as a
   pure move, under ADR-015's merge-commit rule), its `google-services.json`,
   signing and release workflow, `LabExtensionsInitializer`, the CI matrix and the
   docs. Device check: a Material Testing build installed over the last fork build
   keeps its sessions, draft and queued uploads.
5. [ ] **Delete the fork's copies** (`backend/`, `firebase-hosting/`,
   `firestore.rules`, `contracts/`, `ops/`), retire the fork's sync docs, and mark
   material_testing's ADR-009 and ADR-022 superseded in part.
6. [x] Open a TECH_DEBT row to track carrying out this record: TD-298 (2026-10-19), in
   semperdic-app's register.

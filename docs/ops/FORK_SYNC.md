# material_testing: syncing and porting

[material_testing](https://github.com/sempermechanics/material_testing) is a fork of
this repo. It adds the tensile, bending and lab-report features, ships as **Semper
Classroom** under its own app id (`com.sempermechanics.semperclassroom`,
[ADR-030](../adr/ADR-030-semper-classroom-app-identity.md); the app was Material
Testing, `com.sempermechanics.materialtesting` and before that
`com.indicvision.semper.materialtesting`, its ADR-009) and uses this repo's
backend. The repo is to be renamed `semper_classroom` (an owner step; GitHub
redirects the old URL, so the `mat` remote keeps working). It takes this `main` with a plain `git merge` on a `sync/semperdic-<sha>`
branch.

In the week to 2026-10-01, nine changes were written twice, once in each repo: the
frame ceiling, the metadata route, TD-137, TD-100, TD-101/TD-102 and TD-104–TD-105 among them.
They ended up byte-identical, but they doubled the history, split the docs (ADR-013,
WORKFLOWS C21) and collided TD numbers. The rules below stop that.

## Where a change starts

| Change | Lands in | Reaches the other repo by |
|---|---|---|
| `backend/`, `firebase-hosting/`, `firestore.*`, `.github/`, `scripts/`, `benchmark/` harness | here | the next sync |
| A fix to app code both repos have (wizard, ROI editor, viewer, Home, settings, cloud sync) | here | the next sync |
| A fix found in material_testing in shared code | here, as `git cherry-pick -x <fork sha>` if it landed there first | the next sync (identical blobs merge silently) |
| Lab, tensile, bending, loads, lab report | material_testing | never |

material_testing does not edit `backend/`; the backend deployed from here serves both
apps. The deploy and Firestore workflows only run in this repo
(`if: github.repository == …`). So every backend lever, the `_SEMPER_CLASSROOM`
pair included, is a variable here: a sync keeps material_testing's RELEASING.md
and INCIDENT_RESPONSE.md naming this repo on each `gh variable` and
`deploy-backend.yml` step (`-R sempermechanics/semperdic-app`; its MT-001).

## Syncing into material_testing

**Last sync:** semperdic-app `b1c15346` (its #490–#503: the second readiness run, #503, with
lower-case "semper", "Demo" / "Licensed" and `--require-pins`; CI speed-ups ported from
here; the console's test-account fixes), merged on material_testing's
`sync/semperdic-b1c1534` on 2026-10-29, the first sync to run `td_numbers.py translate`
(155 auto-merged lines and the conflict hunks); before it `6d2bde6c` (its #487–#489, the
production-readiness fixes, `sync/semperdic-6d2bde6`, 2026-10-26), `25334af1`
(material_testing #146), `968637cb` (material_testing #144) and `14b279d6` (on
`ccr-603fe346-1bw2fa`, 2026-10-21).
`git log --merges --grep=semperdic-app` on the fork lists them all.

1. `git fetch semperdic main` and branch `sync/semperdic-<short sha>` from its `main`.
2. `git merge semperdic/main`. Keep its `applicationId`, `rootProject.name` and
   `google-services.json`. The benchmark module and CI read the package from
   `applicationId` (ADR-008), so nothing else names the app.
3. Docs conflicts are the norm: keep its CONTEXT and TECH_DEBT, both sides of
   CHANGELOG, and both sides of any ADR both repos added to.
4. An engine bump (the `engine` gitlink) means re-running its real-data checks
   (its `real_data_steel_tensile.py` and `real_data_pmma_bending.py` scripts); its JVM
   tests use arrays recorded on the old engine and stay green either way.
5. Before pushing the merge, check the gitlink: `git ls-tree HEAD engine` must equal
   `git ls-tree semperdic/main engine` unless the fork deliberately pins another
   engine, and `python scripts/check_jni_parity.py` must pass. The sync of
   2026-10-22 (material_testing #144) resolved the gitlink to 0.2.3 although both
   parents pinned v0.6.1, so its incremental runs threw `UnsatisfiedLinkError`
   (MT-TE2-001).

### The 2026-10 package move (ADR-015)

The sync that brings in [ADR-015](../adr/ADR-015-package-layout.md) moves 149
files. The merge follows the renames for files both repos have. Files only
the fork has, and its edits to moved files, need one more pass:

1. Resolve the merge, then replay the mapping. It is idempotent, so files the
   merge already moved are skipped, and only the imports still pointing at the
   old packages are fixed, the fork's lab files included:
   `python scripts/move_kotlin_packages.py --mapping scripts/package_moves_2026_10.json`.
2. Move the fork's own non-Worker files left in `data/` too (here `data/` holds
   the six Workers and, since the quality program, the backup's steps beside
   `DicUploadWorker`). Add them to a copy of the mapping and run it again. Never
   move a `*Worker` class or an Activity (e.g. `BeamEdgeTapActivity`):
   WorkManager and the manifest record their names. On the fork's `main` of
   2026-10-01 those files, with a suggested package, are:

   | Fork-only file in `data/` | Suggested package |
   |---|---|
   | `TestType`, `MechanicalTestInputs`, `SpecimenGeometry`, `BeamEdgeTaps`, `CurveCorrection`, `TypedLoads` | `data/mechanical/` (what the wizard records about the test) |
   | `MachineLoadCsv`, `MachineLoadMapper` | `data/mechanical/` (the machine's load log, read and matched to frames) |
   | `DocumentText` | `util/` (reads a small SAF text document; nothing mechanical in it) |

   That makes `data/mechanical/` eight files. Re-list `data/` before you
   map it, since the fork may have added files since. The fork's other
   lab-only files fit the same pattern. In `ui/analysis/`, the load and
   beam-tap UI (`AnalysisLoadCard`, `Load*`, `TypedLoadsSheet`,
   `SpecimenGeometryFields`, `BeamEdgeTapOverlay`, `BeamTapPlacement`,
   `PhotoCaptureTime`) can go to `ui/analysis/load/`, `VideoKeyframes` and
   `VideoSampling` to `imaging/video/`, and `VideoSamplingSheet` to
   `ui/analysis/frames/`. In `ui/viewer/`, the mechanical results
   (`ViewerStressStrain*`, `ViewerBendingResults`, `ViewerCurveCorrection`,
   `ViewerDeflectionCorrection`, `ViewerFrameRows`) and `LabReportExporter`
   can go to `ui/viewer/mechanical/`.
3. Run `./gradlew --no-daemon spotlessApply`, then the script again with
   `--compile`. It runs the compile tasks and adds imports for any
   `Unresolved reference` until a pass fixes nothing. Fix what is left by
   hand: an inline FQCN that grew past detekt's 120 columns, a class name
   inside a string literal (the script reports these and leaves them
   alone), or a name that is declared in two packages.
4. Run the script with `--kdoc` and `--docs` for KDoc links and doc paths,
   then `python scripts/check_doc_paths.py`. `--docs` leaves dated records
   as written: `docs/ops/QUALITY_BASELINE_*`, `docs/ops/CHANGELOG.md`, the
   ADRs (`docs/adr/ADR-*.md`), `docs/engine/PERF_BASELINE_*` and dated
   `docs/perf/*-20NN-*` reports. It names each one it skipped that mentions
   a moved file. Pass `--docs-skip GLOB...` to change the list. If
   `check_doc_paths.py` then fails on a link in one of those files, fix
   that link by hand.
5. Before release, do the upgrade check in ADR-015's action items: queued
   work from the old APK must still run.

### The second move and the API changes (quality program, 2026-10)

The quality program (#311–#331,
[QUALITY_PROGRAM_RESULTS.md](QUALITY_PROGRAM_RESULTS.md)) split the large
files and moved code a second time (#331). The sync that brings it in needs
three things the merge will not do on its own.

1. **Replay both mappings, in order.** First the one above, then the second
   move: `ui/common/` into `dialog/`, `auth/`, `media/` and `transfer/`; the
   Drive code into `data/net/drive/`; and the wizard's parts out of the
   `ui/analysis/` root into its subpackages.

   ```bash
   python scripts/move_kotlin_packages.py --mapping scripts/package_moves_2026_10.json
   python scripts/move_kotlin_packages.py --mapping scripts/package_moves_2026_10_wave5.json
   ```

   Then steps 3 and 4 above (`--compile`, `--kdoc`, `--docs`,
   `check_doc_paths.py`) once, after both.
2. **Watch one collision.** The second mapping moves
   `com.sempermechanics.semper.ui.analysis.VideoSamplingSheet` to
   `ui/analysis/frames/`. The fork has its own `ui/analysis/VideoSamplingSheet`
   (step 2 above suggests the same package), and this repository now has
   `ui/analysis/frames/VideoSamplingSheet.kt` as well. Decide which one the
   fork keeps before running the mapping: otherwise the script tries to move
   the fork's file to a path this one already holds.
3. **Fix the calls to APIs that were removed or changed.** The mappings move
   files; they do not rewrite calls. The fork's own code needs these by hand:

   | Was | Now |
   |---|---|
   | `WizardDraft.discard()` | Removed. The wizard's draft is dropped through `AnalysisViewModel.discardDraft()` → `WizardDraftBinding.discard()`, which queues `WizardDraft.clear()` only while this wizard still owns the draft |
   | `RoiResolveHelper.resolve(...)` | Removed; use `Roi.forSolve(subset, hasCustomRoi, drawn, size)` (`field/Roi.kt`). `RoiResolveHelper.clipToImage` and the top-level `roiPixels` in `RoiDrawActivity.kt` are gone too |
   | `SessionRepository.saveSession(...)` | Removed; build the row with `SessionRepository.buildSessionRecord` and save it with `saveRunRecord` (`ui/analysis/run/RunRecordSave.kt`), which returns a `SessionStore.UpsertResult` |
   | `SweepSetupHelper.Callbacks.goToStep(Int, Boolean)` | `goToStep(WizardStep, Boolean)`; the page numbers are the `WizardStep` enum (`ui/analysis/wizard/WizardStep.kt`) |
   | `VisualizationEngine.quickSelect` | `HeatmapColorScale.quickSelect` (`report/HeatmapColorScale.kt`) |
   | `originalNameOr` in `ui/analysis/run` (`DicFieldIo.kt`) | `List<String>.originalNameOr(index, fallback)` in `data/session/SessionNaming.kt`. It is **not** in either mapping, so add the import by hand |
   | `VisualizationEngine` results as `Pair` / `Triple` | `ImageSize` and `BakedHeatmap` |
   | `AnalysisRunCodes`, `BatchAnalysisParams` | Removed; the run's codes are `RunStop` (`field/RunStop.kt`) and its inputs `RunSpec` |
   | `SemperApiHttp.apiException`, `Refusal` | `ApiAnswer` (`data/net/SemperApiHttp.kt`) |
   | `CloudRestore.recordFrom`, `SkippedNode.fromMetadata`, `SkippedNode.toMetadataJsonArray` | `SessionMetadataDoc` (`data/cloud/SessionMetadataDoc.kt`) reads and writes a backup's `metadata.json` |
   | `SessionStore.upsertAsync` | Removed; call `SessionStore.save` / `upsert` off the main thread |
   | `ZipDirectory.centralDirectoryOffset` | Removed |
   | `LicenseConfigWorker.UNIQUE_NAME` | The unique work names live in `WorkTags` (`data/cloud/WorkTags.kt`) |
   | `VideoFrameBatchWriter` | `FrameSink` (`imaging/video/FrameSink.kt`) |
   | `res/drawable/jet_gradient_vertical.xml` | Removed; the colour bar is drawn from the heatmap's own ramp |

   After the replay, `--compile` reports what is still unresolved; the rows
   above are what it cannot fix.

Here, a PR that mixes moves with fixes is merged with a merge commit, never a
squash ([ADR-015](../adr/ADR-015-package-layout.md)), so its pure-move commits
stay in `git log` for the fork to review one at a time.

### The app id and package move (ADR-019, 2026-10)

[ADR-019](../adr/ADR-019-sempermechanics-app-id.md) moved this app to
`com.sempermechanics.semper` and renamed the engine submodule's folder from
`native/` to `engine/`. Take it in **one** merge on the fork, together with the
fork's own id change:

1. Set `applicationId = "com.sempermechanics.materialtesting"` and register that
   app in Firebase (new `google-services.json` client). The backend already maps
   it to `materialtesting` (`backend/app/apps.py`), and `assetlinks.json` lists it.
2. Merge. Git moves the Kotlin tree (`com/indicvision/semper` →
   `com/sempermechanics/semper`) and the submodule path; then run
   `git submodule sync && git submodule update --init --recursive`, and delete
   `app/.cxx` (its CMake cache holds the old `native/` path).
3. Rewrite the fork-only files' `package` and `import` lines:
   `com.indicvision.semper` → `com.sempermechanics.semper`. Same for
   `IndicApi*` → `SemperApi*` and the `INDIC_*` build keys → `SEMPER_*` (the old
   keys are still read).

Do not take this code under the old id: the prefs files (`semper_*`) and the
Keystore alias (`SemperDeviceKeyEc`) are renamed, so an install that upgrades in
place would find them empty and lose sign-in, settings and its device key.

### The Semper Classroom rename (ADR-030, 2026-10)

[ADR-030](../adr/ADR-030-semper-classroom-app-identity.md) renames Material Testing
Semper Classroom. The backend half (short name `semperclassroom`, field suffix
`SemperClassroom`, the old names read and mirrored until TD-56) lands here and
deploys before the fork releases. On the fork, in the sync that takes it:

1. Set `applicationId = "com.sempermechanics.semperclassroom"` and the app name to
   Semper Classroom. Keep the release signing key (alias `materialtesting`): the
   fingerprints in Firebase and `assetlinks.json` are the same. Register the new
   app in Firebase (`indicvision-dic-app-auth`, with App Check) and take its
   `google-services.json` client.
2. Check that `EntitlementApp` (`data/account/entitlement/EntitlementPins.kt`) maps
   the new id to `semperclassroom`, as `apps.token_claim` does; without it the
   build pins its id and refuses every token as `WRONG_APP`. The old ids stay
   mapped to `materialtesting`.
3. The namespace and Kotlin package stay `com.sempermechanics.semper`: nothing
   moves, and the prefs and Keystore names do not change. The new id is still a
   new install, with empty storage; testers back up in Material Testing first,
   since `com.sempermechanics.materialtesting` can no longer register a device
   (`apps.RETIRED`).
4. Release it. The owner then dry-runs and applies
   `backend/scripts/rename_classroom_app.py` once the new backend serves all
   traffic (ADR-030, Rollout order;
   [FIRESTORE_SCHEMA_RUNBOOK](../backend/FIRESTORE_SCHEMA_RUNBOOK.md)).

### The naming scheme (2026-10)

A set of pure renames brought the code in line with the naming rules in
[CONTRIBUTING](../../CONTRIBUTING.md#code-style). They move no packages, so no
mapping replays them: after the merge, the fork's own files that use an old
name fail to compile, and this table is the fix. Persisted values did not
change (prefs files and keys, intent extras, WorkManager names, `index.json` and
metadata fields, analytics events, the `StrainMethod` wire value), and no
Worker, Activity, `SemperNativeLib` or `ProgressCallback` was renamed. Each
group is one commit, reviewable with `git diff -M`.

| Was | Now |
|---|---|
| **Backend exceptions** | |
| `SemperApi.ApiException`, `.NotApprovedException`, `.CloudNotConfiguredException`, `.TermsVersionMismatchException`, `.DeviceConflictException`, `.DeviceInUseException`, `.NoSeatAvailableException`, `.DeviceNotActiveException`, `.UploadLinkExpiredException` (nested) | The same names, top-level in `data/net/ApiExceptions.kt` (the typealiases there are gone): import `com.sempermechanics.semper.data.net.<Name>` |
| **Stateful `*Helper` → `*Controller`** | |
| `AnalysisDeformedBatchHelper`, `AnalysisVideoExtractHelper` (`ui/analysis/frames/`) | `AnalysisDeformedBatchController`, `AnalysisVideoExtractController` |
| `ComputeOverlayHelper` (`ui/analysis/run/`) | `ComputeOverlayController` |
| `SweepSetupHelper` (`ui/analysis/sweep/`), `SweepSetupHelperTest` | `SweepSetupController`, `SweepSetupControllerTest` |
| `AnalysisSettingsSheetHelper` (`ui/analysis/wizard/`) | `AnalysisSettingsSheetController` |
| `ViewerInspectHelper` (`ui/viewer/inspect/`), `ViewerSummaryHelper` (`ui/viewer/summary/`) | `ViewerInspectController`, `ViewerSummaryController` |
| Parameters / properties `overlayHelper`, `sweepHelper` | `overlayController`, `sweepController` |
| **Files and types** | |
| `util/OrgJsonLenient.kt` | `util/OrgJson.kt` (object `OrgJson` unchanged) |
| `ui/common/KeyboardExt.kt` | `ui/common/Keyboard.kt` (`hideKeyboard`, `commitOnDone`, `showUnlessEditing`) + `ui/common/ToggleGroups.kt` (`onButtonChecked`); the functions keep their names and package |
| `ui/common/ViewExt.kt` | `ui/common/Dp.kt` (`View.dp`) |
| `data/session/SessionRecordExt.kt` | `data/session/SessionRecordFields.kt` |
| `DriveDownload` in `data/net/drive/DriveDownloader.kt` | `data/net/drive/DriveDownload.kt` |
| `ImageEncode`, `BitmapDecode` (`imaging/`) | `ImageEncoder`, `BitmapDecoder` |
| `GoogleSignInHelper.NotConfigured` | `GoogleSignInHelper.NotConfiguredException` |
| `CloudSync.EraseResult` | `CloudSync.EraseOutcome` |
| `CloudRestore.ListResult` | `CloudRestore.ListOutcome` |
| `SessionStore.UpsertResult` | `SessionStore.UpsertOutcome` |
| `StagingResult` (`data/UploadStaging.kt`) | `StagingOutcome` |
| `RestoreStart.Result` | `RestoreStart.Outcome` |
| `SessionEverythingExporter.Result` | `SessionEverythingExporter.Export` |
| `SubsetRecommender.Result` | `SubsetRecommender.Recommendation` |
| `VsgStudyRunner.Result` | `VsgStudyRunner.SweepResult` |
| `TermsAcceptanceBody`, `ConsentUpdateBody`, `ListSessionsResponse` (`data/net/ApiDtos.kt`) | `TermsAcceptRequest`, `ConsentUpdateRequest`, `SessionsResponse` |
| Tests: `RestoreFakeApi`, `SpeckleScaleInstrumentedTest`, `ExifOrientedSizeInstrumentedTest`, `BitmapDecodeTest`, `KeyboardExtTest`, `SessionRecordExtTest` | `FakeRestoreApi`, `SpeckleScaleDeviceTest`, `ExifOrientedSizeDeviceTest`, `BitmapDecoderTest`, `KeyboardTest`, `SessionRecordFieldsTest` |
| **Functions** | |
| `DicUploadWorker` `appInForeground()`, `DicBundleDownloadWorker` `mayPackInstead()`, `UploadStaging` `inputsGoneForGood()` | `isAppInForeground()` (since 2026-10-19 gone: `AppForeground.isForeground`, `data/AppForeground.kt`, read through `DicUploadSeams.inForeground`), `canPackInstead()`, `areInputsGoneForGood()` |
| `LicenseEntitlements.seatRequiredToStart`, `.unlimitedAnalysis`, `.inGrace` | `isSeatRequiredToStart`, `hasUnlimitedAnalysis`, `isInGrace` |
| `AppRemoteConfig.inGrace` | `AppRemoteConfig.isInGrace` |
| `UploadWorkOutcomes.bundleArtifactsReady`, `.stagingInputsOnDisk`, `.stagingReusable` | `areBundleArtifactsReady`, `areStagingInputsOnDisk`, `isStagingReusable` |
| `ClientNonce.usable()` | `ClientNonce.isUsable()` |
| `DicSettings.diagnosticsAsked` | `DicSettings.wasDiagnosticsAsked` |
| `DicSettings.saveToCloud` / `setSaveToCloud` | `saveToCloudEnabled` / `setSaveToCloudEnabled` |
| `DicSettings.uploadWifiOnly` / `setUploadWifiOnly` | `wifiOnlyUploadEnabled` / `setWifiOnlyUploadEnabled` |
| `SessionStore.sameMetadataInputs` | `SessionStore.haveSameMetadataInputs` |
| `RigidBodyFit` `notable()` | `isNotable()` |
| `VsgLatticeActivity.lineCutHorizontal()` (private) | `isLineCutHorizontal()`; the `lineCutHorizontal` property elsewhere is unchanged |
| `WizardParamFields.useKeysInterpolator()` | `isKeysInterpolatorSelected()` |
| `AuthActivity.validEmail` | `isValidEmail` |
| `CloudApi.me`, `CloudApi.sessionUploads` (and every implementation, including fakes) | `getMe`, `listSessionUploads` |
| `GoogleSignInHelper.getIdToken` | `GoogleSignInHelper.requestIdToken` |
| `CloudBackupListing.load` | `CloudBackupListing.read` |
| `loadSweepFrameProfiles` (`ui/analysis/sweep/LatticeProfiles.kt`) | `readSweepFrameProfiles` |
| `StrainMethod.wireName` | `StrainMethod.wire` (value `"VSG"` unchanged) |
| `SessionStore.listAsync`, `.setSyncStateAsync` | `listOnIo`, `setSyncStateOnIo` |
| `StorageBudget.enforceAsync`, `.freeAllBackedUpAsync` | `enforceOnIo`, `freeAllBackedUpOnIo` |
| **Properties and constants** | |
| `ResultViewerActivity.viewerVm` (internal; its parts use `host.viewerVm`) | `ResultViewerActivity.viewModel` |
| `AppRemoteConfig.fetchedAtMillis`, `.licenseExpiresAtMillis` | `fetchedAtMs`, `licenseExpiresAtMs` |
| `SettingsActivity.PERCENT_MAX` (and the private copies in `UploadProgressSampler`, `TransferBannerController`, `ComputeOverlayController`, `SessionListAdapter`) | `PERCENT` |
| `VideoSamplingSheet.MIN_SEGMENT_SEC` | `MIN_SEGMENT_SECONDS` |
| Private: `MILLIS_PER_SECOND`, `*_TIMEOUT_S`, `TOKEN_TIMEOUT_S`, `mScaleDetector`, `mutableState`, `runningState` | `MS_PER_SECOND`, `*_TIMEOUT_SECONDS`, `TOKEN_TIMEOUT_SECONDS`, `scaleDetector`, `_state`, `_running` |
| **Layouts and drawables** (ViewBinding classes follow) | |
| `layout/dialog_video_sampling.xml` (`DialogVideoSamplingBinding`) | `layout/sheet_video_sampling.xml` (`SheetVideoSamplingBinding`) |
| `layout/warn_chip_row.xml` | `layout/view_warn_chip.xml` |
| `layout/settings_section_header.xml` (`SettingsSectionHeaderBinding`) | `layout/view_settings_section_header.xml` (`ViewSettingsSectionHeaderBinding`) |
| `layout/settings_scroll_content.xml` (`SettingsScrollContentBinding`) | `layout/view_settings_scroll_content.xml` (`ViewSettingsScrollContentBinding`) |
| `drawable/badge_bg.xml` | `drawable/bg_badge.xml` |
| **View ids** (binding fields follow) | |
| `wizard_step_settings_content.xml`: `tvSubsetValue`, `etSubsetSize`, `etStepSize`, `tvStepValue`, `tvOverlapValue`, `etOverlap`, `tvStrainValue`, `etStrainWindow`, `tvSweepOverlapValue` | `etSubsetValue`, `sliderSubsetSize`, `sliderStepSize`, `etStepValue`, `etOverlapValue`, `sliderOverlap`, `etStrainValue`, `sliderStrainWindow`, `etSweepOverlapValue` |
| `activity_static_analysis.xml`: `ivRefThumb`, `ivDefIcon` | `imgRefThumb`, `imgDefIcon` |
| `item_frame_order.xml`: `ivFrameThumb`; `dialog_sweep_frame_pick.xml`: `ivSweepFrameDialogPreview`; `view_settings_section_header.xml`: `ivSectionChevron` | `imgFrameThumb`, `imgSweepFramePreview`, `imgSectionChevron` |
| `activity_auth.xml`: `layoutEmail`, `layoutPassword`, `layoutConfirmPassword`, `layoutTotp`, `cardCredentials`, `cardTotp` | `tilEmail`, `tilPassword`, `tilConfirmPassword`, `tilTotp`, `credentialsCard`, `totpCard` |
| `toggleExtractMode` (`sheet_video_sampling.xml`), `togglePlotMode` and `togglePlotModeClip` (`activity_vsg_lattice.xml`), `toggleMediaSource` (`sheet_media_picker.xml`) | `rgExtractMode`, `rgPlotMode`, `plotModeClip`, `rgMediaSource` |
| `menu/menu_frame_order.xml`: `menu_frame_order_date_asc`, `_date_desc`, `_manual`, `_name_asc`, `_name_desc` | `menuFrameOrderDateAsc`, `menuFrameOrderDateDesc`, `menuFrameOrderManual`, `menuFrameOrderNameAsc`, `menuFrameOrderNameDesc` |
| **String keys** (text unchanged) | |
| `roi_hud_mode_switched`, `roi_hud_zoom`, `roi_hud_dimensions`, `video_read_error`, `video_codec_unsupported`, `error_loading_images`, `scale_dialog_title`, `scale_max_value`, `scale_min_value`, `auth_link_sent`, `auth_reset_sent`, `transfer_banner_page`, `transfer_banner_percent`, `help_support_feedback_subject`, `help_support_feedback_body`, `storage_free_up_body`, `cloud_delete_forever_body`, `about_message`, `sweep_fail_unknown`, `sweep_reason_unknown`, `restore_load_error`, `admin_load_error`, `admin_approved_toast`, `admin_denied_toast`, `admin_action_error`, `limit_body`, `license_expiring_today`, `license_expired`, `license_grace`, `request_access_none` | The same key + `_fmt` |
| `subset_low_texture_fmt` | `subset_low_texture` |
| `setting_subset`, `setting_step`, `setting_strain_window`, `setting_strain_method`, `setting_stopped_early`, `setting_frames_solved`, `setting_roi`, `setting_image_size`, `setting_px_fmt`, `setting_roi_fmt`, `setting_size_fmt`, `setting_vsg` (the settings-used sheet) | `settings_used_` + the rest (`settings_used_subset`, …) |
| `setting_diagnostics`, `setting_diagnostics_sub`, `setting_save_cloud`, `setting_save_cloud_sub`, `setting_save_cloud_sub_off`, `setting_wifi_only`, `setting_wifi_only_sub`, `setting_max_frames`, `setting_max_frames_info` (the Settings screen) | `settings_` + the rest (`settings_diagnostics`, …) |
| **Instrumented test methods** | |
| 27 snake_case or `subject_condition` names in `SpeckleScaleDeviceTest`, `ExifOrientedSizeDeviceTest`, `FirebaseAuthIntegrationTest`, `AnalysisWizardSmokeTest`, `EnginePipelineSmokeTest` | camelCase sentences (`repeatSolve_bitIdentical` → `repeatSolveIsBitIdentical`, `analysisActivity_showsToolbar` → `wizardShowsToolbar`, …); benchmark methods keep their names |
| **Sign out wording** (the one copy change: "Log Out" → "Sign out") | |
| `logout_confirm_title`, `logout_confirm_body`, `logout_success` | `sign_out_confirm_title` ("Sign out?"), `sign_out_confirm_body`, `sign_out_success` ("You have been signed out.") |
| `action_log_out` ("Log Out") | `action_sign_out` (the existing key, same text now) |
| **Sweep vocabulary** (`ui/analysis/sweep/` unless noted; "lattice" stays only for the grid of combinations) | |
| `VsgStudy`, `VsgStudyRunner` (with its nested `SweepResult`) | `SweepStudy`, `SweepStudyRunner`; `SweepStudy.vsgFor` / `DEFAULT_VSG_SAMPLES` keep VSG (the quantity) |
| `VsgPlotView`, `VsgPlotAxes`, `VsgPlotPalette`, `VsgPlotViewport` | `SweepPlotView`, `SweepPlotAxes`, `SweepPlotPalette`, `SweepPlotViewport` |
| `VsgLatticeView` | `SweepLatticeView`; `VsgLatticeActivity`, `activity_vsg_lattice.xml` and its `vsg_lattice_*` strings keep their names (the Activity is pinned) |
| `LatticeGraphExport`; files `LatticeControls.kt`, `LatticeProfiles.kt` | `SweepGraphExport`; `SweepControls.kt`, `SweepProfiles.kt` (the functions in them keep their names and package) |
| `SweepSetupController.Callbacks.startVsgSweep`; `AnalysisViewModel.launchVsgSweep`, `.runVsgSweep` (`ui/analysis/wizard/`) | `startSweep`; `launchSweep`, `runSweep` |
| `AnalysisViewModel.vsgFrameIndex` | `sweepFrameIndex` (saved-state key `"vsgFrameIndex"` unchanged); since 2026-10-19 `sweepFrame: SweepFrame` (`Middle` or `At(index)`, `ui/analysis/sweep/SweepRanges.kt`), the key and its `Int` unchanged |
| Tests: `VsgStudyTest`, `VsgStudyRunnerParamsTest`, `VsgPlotViewTest`, `VsgPlotViewportTest`, `VsgLatticeViewTest`, `LatticeControlsTest`; oracle `ui/common/vsg_plot_clamp_viewport.txt` | `SweepStudyTest`, `SweepStudyRunnerParamsTest`, `SweepPlotViewTest`, `SweepPlotViewportTest`, `SweepLatticeViewTest`, `SweepControlsTest`; `sweep_plot_clamp_viewport.txt` |
| **`Dic` prefix** (prefs file `dic_settings` and every extra's value unchanged) | |
| `data/prefs/DicSettings` | `data/prefs/AppSettings` |
| `navigation/DicKeys` | `navigation/IntentKeys` |
| Test `DicSettingsMigrateTest` | `AppSettingsMigrateTest` |
| **Runner files** (`AnalysisViewModel` extension functions; the functions keep their names) | |
| `ui/analysis/run/DicBatchRunner.kt`, test `DicBatchRunnerLimitTest` | `ui/analysis/run/BatchAnalysis.kt`, `BatchAnalysisLimitTest` |
| `ui/analysis/wizard/SweepRunner.kt` | `ui/analysis/wizard/SweepAnalysis.kt` |
| **Account cache** (prefs file `semper_session` and its keys unchanged) | |
| `data/net/TokenStore` | `data/net/AccountCache` (it caches identity, role, status and quota; Firebase holds the token) |
| **Placeholder strings** (always overwritten before the view is first drawn) | |
| `label_41`, `label_5`, `label_0`, `label_10_fps`, `label_0_00_0_00`, `label_0_frames` (`android:text` in `wizard_step_settings_content.xml`, `activity_static_analysis.xml`, `sheet_video_sampling.xml`) | Deleted; the layouts carry the same text as `tools:text` |
| **`*Ui` and friends** | |
| `ui/auth/AuthTotpUi`, `ui/viewer/share/ShareExportUi` | `AuthTotpController`, `ShareExportController` |
| `ui/analysis/wizard/AnalysisCancelGate` | `AnalysisCancel` (a shared flag, not a `*Gate`) |
| `data/account/DeviceKeyManager` | `DeviceKeys` (Keystore alias `SemperDeviceKeyEc` unchanged) |
| **Viewer sheet** | |
| `ui/viewer/ViewerSettingsSheet`, test `ViewerSettingsSheetTest` | `SettingsUsedSheet`, `SettingsUsedSheetTest` (it inflates `sheet_settings_used.xml`; strings `settings_used_*`) |
| **Nested `PrefFiles`** (file names and keys unchanged) | |
| `PrefFiles.CloudSync`, `PrefFiles.SessionDeletes` | `PrefFiles.CloudSyncPrefs`, `PrefFiles.SessionDeletesPrefs` (they no longer shadow `data/cloud/CloudSync` and `SessionDeletes`); the other ten nested objects keep their names |
| **Seconds suffix** | |
| Private: `SemperAnalytics.MS_1S`, `MS_5S`, `MS_30S` | `ONE_SECOND_MS`, `FIVE_SECONDS_MS`, `THIRTY_SECONDS_MS` (bucket values `lt_1s` … unchanged) |

### The 2026-10-19 review (#457)

Four of #457's commits are already in material_testing, cherry-picked by its #140:
`6a8f86e` (upload restage, its `4e384d51`), `e4e4dca` (Save-to-Files on a stop,
`af5bd1e2`), `a95d104` (the detekt split, `6d38abf5`) and `e2c30bb` (PDF render,
byte units, SAF copy, `91699d09`). The rest touch files the fork hooks into, so they
arrive with the next sync (its TD-241), and these API changes need the fork's lab code
fixed by hand:

| Before | After |
|---|---|
| `RunResult`, `lastRunResult`, the `last*` fields, `AnalysisNavHelper.resultArgs` / `openResults`, `WizardRunOutcomes`, `BatchRunController.Host` | Gone. A run's end is the sealed `RunEnd` (`RunEnd.of`, `ui/analysis/run/RunEnd.kt`); the saved row is `BatchAnalysisOutcome.record`, and every result opens through `ViewerArgs.of(record)` (was `SessionOpenHelper.argsFor`), so a lab argument the viewer needs must be read there; `ViewerArgs.forFailedSweep` for a sweep that solved nothing |
| A run writing `workingLocalId`, the plan and skips from the native thread | The run returns them in its outcome and the view model takes them on Main (`applyRunOutcome`, `ui/analysis/wizard/RunChannels.kt`); a lab value a run learns goes in the outcome too |
| `workingLocalId` + `workingIsSweep`; `sweepFrameIndex` | `working: WorkingSession(id, RunKind)?`; `sweepFrame: SweepFrame` (Bundle keys unchanged) |
| Field tables in `ViewerFieldPills`, `ShareExportBuilder`, `SummaryAnimation`, `AnalysisCsvWriter`, `ReportBuilder` | `field/FieldKind` |
| `ReportSource`'s copied fields, `RoiData` and its conversions | `ReportSource(frameParams, roi, size, …)`; `ReportData` carries a `Roi` |
| `VisualizationEngine`'s five heatmap entry points | `render` / `indices` over one `HeatmapRequest` |
| The viewer Activity's frame-set copies, `afterFrameSet`, `whenFrameSetLoaded`, `summaryBatchFiles`, `detailStatsText` | `ResultViewerViewModel.state: StateFlow<ViewerState>`; `batchFiles` and the like are getters over the set it opened |
| Home's restore on `lifecycleScope`; the **+** chooser and Re-run in `HomeActivity` | `HomeViewModel`; `HomeNewAnalysis` (where the fork's **Which test?** hand-off now goes) |
| `UploadWorkOutcomes.ResumeKind`, `classifyResume`; three foreground checks | `Resume.of` (`data/UploadSessionPlanner.kt`); `data/AppForeground.kt` |
| `AnalysisViewModel`'s 36 per-field `var`s (`viewModel.refBytes`, `viewModel.step`, …), `lateinit sweepController` | Gone (TD-244): read `viewModel.inputs.value`, write one `viewModel.edit { it.copy(…) }` per change (`ui/analysis/wizard/AnalysisViewModel.kt`); only `cancelRequested` is left. A lab field set through such a property (the fork's `LabRerun.applyTo`, for one) becomes a `WizardInputs` / `LabWizardState` field changed in that edit. `sweepController` is `by lazy` |
| `SessionMetadataDoc`'s nested field serializers | Top-level `internal object`s of the same names in `data/cloud/SessionMetadataSerializers.kt` (TD-229), a pure move: a sync conflict there is a move, not a change |
| `VideoFrameExtractor.getFrame` returning a `Bitmap`; retriever frames timed as asked | `RetrievedFrame(bitmap, isSyncFrame)`; times from `RetrieverFrameClock` (TD-194) |

material_testing took the rest of #457 with the sync of `14b279d6` (its TD-241, closed
there): the lab wizard state is one field of the inputs, `WizardInputs.lab`
(`LabWizardState`), changed through `edit {}` / `editLab {}` with its Bundle keys unchanged
(`putLab` / `restoreLab`); `LabInputs.kt` became pure functions of `WizardInputs`; the
lab test reaches a fresh result through `ViewerArgs.of(record)`; Home's **Which test?** and
the lab Re-run live in `HomeNewAnalysis`; and the viewer's lab parts take the Results helper
as a parameter (`ViewerSummaryController`, `ViewerChromeController`) or read
`ShareControllerHost.labSession` / `stressModel` / `loadsN`.

### Tamper-evident records (ADR-028, 2026-10)

material_testing gets the record manifest with the next sync of this `main`; nothing
in it is Semper-only. What its lab code has to know:

- Every run that is not cancelled ends in `RunSession.commitRecord` (was `commit()` in
  `BatchAnalysis` and `endSweep`), which writes `record_manifest.json` into the staging
  and then commits. The manifest hashes every `*.dat` and `field_ranges.bin` there, and
  the verifier reads any other `*.dat` in the session folder as **Changed**: a lab file
  ending in `.dat` must be staged before that call or take another extension.
- The signature is the `DeviceKeys` Keystore key (same alias, `SemperDeviceKeyEc`), so
  the fork's manifests are signed by its own install's key. Only that key reads
  **Verified**; a manifest signed by any other key reads **Signed on another device**
  (`RecordIntegrity.SignedElsewhere`). `RecordVerifier.verify(context, dir)` reads this
  phone's thumbprint through `LocalRecordKey`; tests call `verify(dir, localThumbprint)`.
- Backups carry it as `dat/record_manifest.json` in `Session.zip` (no new role, no
  backend change); restore and "Export everything" bring it along.
- API changes: `ResultViewerViewModel.listFrames(dispatcher, check, read)` and
  `ViewerState.integrity`; `ReportData.recordFingerprint`, set by the callers of
  `ReportBuilder.buildReport`; `ViewerReportFactory.Source.integrity`. The Details sheet
  (`SettingsUsedSheet.entriesFor`) gains a "Reference" row (from the new
  `ViewerArgs.refUpdateInterval`, `IntentKeys.REF_UPDATE_INTERVAL`, written only for an
  incremental run) and a "Record integrity" row, plus an `integrityWarnRow` chip in
  `sheet_settings_used.xml`.

## Porting back

List the fork's commits that touch shared paths and are not here:

```bash
git fetch mat main
git log --oneline --no-merges mat/main --not origin/main -- app/src/main .github scripts benchmark backend
```

Ignore the lab-only files (`report/Lab*`, `report/StressStrain*`, `report/Beam*`,
`ui/viewer/ViewerBending*`, `data/MachineLoad*`, `data/TypedLoads*`). Port with
`git cherry-pick -x`; when a fix is mixed into a lab commit, extract it into an
adapted commit and name the fork commit in the message.

## TD and ADR numbers

ADR numbers are shared: before taking one, read both `docs/adr/README.md`s on both
`main`s and take the next number free in **both**.

TD numbers are not, since 2026-10-27 ([ADR-032](../adr/ADR-032-own-td-numbers.md)):
this repo restarted at TD-1, and `TD-n` in semperdic-app means its own row. Take the
next free here from the note at the top of [TECH_DEBT.md](TECH_DEBT.md). A sync turns
upstream numbers into this repo's before it applies anything:

```bash
git diff OLD NEW -- PATHS | python scripts/td_numbers.py translate > upstream.patch
git apply -3 upstream.patch
```

`translate` maps each upstream number through
[td-renumber-2026-10-27.csv](td-renumber-2026-10-27.csv), using only the rows marked
`same_upstream`: TD-199 and TD-349–TD-353 meant other rows there. A number it cannot map
(one semperdic-app took after 2026-10-26, one of those, or one this repo never cited)
becomes `semperdic-app's TD-n` and is listed on stderr: give it a row and a number here
if this repo carries it, otherwise leave the prefix. Run it only on upstream text, never
on this repo's files: their numbers are already the new ones.

With a plain `git merge` (step 2), translate in two passes, as the sync of `b1c15346` did:
resolve each conflict hunk from `translate(theirs)` (a hunk that differs only by the
renumbering takes it whole; `git checkout --conflict=diff3` shows the base), then, for
every line the upstream diff `OLD..NEW` added that still stands verbatim in the merged
tree, replace it with its translation. Skip the files this repo keeps its own (CONTEXT,
TECH_DEBT, CHANGELOG). Afterwards no raw upstream `+` line citing a TD should be left,
and `check_framework_coverage.py` fails on a gate row that cites a number with no row
here.

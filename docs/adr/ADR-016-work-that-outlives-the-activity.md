# ADR-016: Work that must outlive the Activity has one home per kind

**Status:** Accepted, built (quality program, #311–#331). Amended 2026-10-19: a
static check for rule 1, proposed and then built as a source check in CI (TD-291);
see [Amendments](#amendments)
**Date:** 2026-10-03
**Deciders:** app owner

## Context

Most screens have no `configChanges`, so a rotation destroys and recreates
them. Work started on an Activity's `lifecycleScope` is cancelled with it. The
2026-10-01 review found four places where that lost work the user had asked
for:

- **Viewer exports (H9).** A share or Save-to-Files export ran in
  `ShareCenter` on the viewer's scope. Rotating mid-export cancelled it and
  dropped its progress dialog.
- **Account deletion (H7).** `CloudSync.deleteAccount` ran on the Settings
  scope. A rotation after the cloud erase cancelled the local wipe and the
  sign-out, so the phone kept its data and a signed-in session for an account
  the server no longer had.
- **Sign-out.** Settings, Terms and Pending released the floating seat and
  signed out on their own scope. A rotation during the seat release skipped
  the Firebase sign-out and the token clear, and a screen closed before the
  sign-out finished left the app on Home with no session.
- **Upload, restore, download and delete** already ran in WorkManager. They
  are the model for work that must also survive process death.

There was no rule for which of these homes new work should take, so each
screen chose its own.

## Decision

Pick the home by how long the work must live and who must see its outcome.

| The work must survive | Home | Built as |
|---|---|---|
| A configuration change, while the user stays on the screen | The screen's ViewModel (`viewModelScope`) | Viewer exports: `ResultViewerViewModel.exports` (`ui/viewer/ResultViewerViewModel.kt:93`) runs `ShareExportJobs` (`ui/viewer/share/ShareExportJobs.kt:49`); the viewer's `StateFlow<ViewerState>` (`:42`: the frame set, listed once by `listFrames` (`:72`), the frame and the field), the custom colour scales (`:90`) and the field-metrics cache (`:128`) live there too. Home's list, restores and phone erases: `HomeViewModel` (`ui/home/HomeViewModel.kt`); sign-in: `AuthViewModel` (`ui/auth/AuthViewModel.kt`). Save to Files: `SaveExportViewModel` (`ui/viewer/SaveExportViewModel.kt:28`). Settings' restore, improvement consent, data exports and phone-only erase: `SettingsViewModel` (`ui/settings/SettingsViewModel.kt:44`; `restore` `:127`, `setImprovementConsent` `:140`, `exportData` `:159`, `eraseLocalOnly` `:188`). The seat request: `SeatRequiredViewModel` (`ui/limit/SeatRequiredViewModel.kt:39`, `takeSeat` `:65`); the analysis limit's Re-check and Refresh licence: `SessionLimitViewModel` (`ui/limit/SessionLimitViewModel.kt:33`, `refreshLicense` `:70`, `recheck` `:79`). Terms acceptance: `TermsViewModel` (`ui/auth/TermsViewModel.kt:29`, `agree` `:58`). Admin approvals and the pending list: `AdminViewModel` (`ui/admin/AdminViewModel.kt:34`, `start` `:76`, `act` `:83`) |
| The screen closing, but not the process | An application-lifetime scope in a singleton `object` that exposes a `StateFlow` | Account deletion: `AccountDeletionRun` (`ui/settings/AccountDeletionRun.kt:48`, scope `:73`), with its stage on disk (`data/prefs/AccountDeletionMarker.kt`) so a new process finishes it (`resumeInterrupted`, `:141`, from `SemperApp.onCreate`). Sign-out: `SignOutRun` (`ui/common/auth/SignOutRun.kt:42`, scope `:56`) |
| Cancellation of the caller, for a short sequence that must not stop half-way | `withContext(NonCancellable)` around that sequence only | The erase → wipe → sign-out sequence, `CloudErase.deleteAccount` (`data/cloud/CloudErase.kt:46-66`) |
| Process death, or the app leaving the screen | WorkManager unique work | `oneTimeWork` + `enqueueUnique` (`data/cloud/WorkTags.kt:96`, `:119`): upload (`data/cloud/CloudSync.kt:392-398`), restore and bundle download (`data/cloud/restore/CloudRestore.kt:73-81`, `:102-112`), backup deletes (`data/cloud/SessionDeletes.kt:91-96`), metadata sends (`data/cloud/SessionMetadataSync.kt:135-139`); the licence refresh is periodic (`data/LicenseConfigWorker.kt:95`) |

**What each home promises.**

- **ViewModel.** The job and its state survive a rotation; leaving the
  screen for good (Back, or the screen finishing) clears the ViewModel and
  cancels the job, as the user expects. A job holds plain data, never the Activity, so a rotation
  frees the old screen at once (`ShareExportJobs.kt:43-47`). The recreated
  screen re-attaches to the job's state and takes each finished outcome once.
- **Application-lifetime object.** The run has an explicit state
  (`Idle` → `Running` → `Done`), and an outcome is consumed once
  (`AccountDeletionRun.consume`, `:165`; `SignOutRun.consume`, `:114`), so a
  recreated screen renders it and a second screen does not repeat it.
  `SignOutRun` also counts the live screens of each class (`:65`, `:124-141`)
  and, when the screen that asked has closed, routes to sign-in from the
  application context and leaves the outcome `Unclaimed` (`:53`, `:100-104`)
  until a screen claims it. A rotation is not a close: the recreated screen
  observes again in the same main-thread step.
- **`NonCancellable`.** Only for a sequence that is short, ends on its own
  timeouts, and is worse stopped half-way than finished; never around a
  network loop the user might want to cancel. See ADR-018 for the
  cancellation rules around it.
- **WorkManager.** Survives process death and reboots, retries with backoff,
  and reports through `WorkInfo`, which screens read with
  `TransferWorkObserver` (`ui/common/transfer/TransferWorkObserver.kt:27`).
  Worker class names are persisted, so workers never move or rename
  ([ADR-015](ADR-015-package-layout.md)).

**The wizard is the exception, on purpose.** `StaticAnalysisActivity`
absorbs every configuration change (`app/src/main/AndroidManifest.xml:207-209`),
so rotation never recreates it. Its batch and sweep runs sit on
`viewModelScope` (`ui/analysis/wizard/RunChannels.kt:131`, `:198`), but
`onDestroy` raises the run's cancel flag on every destroy
(`ui/analysis/StaticAnalysisActivity.kt:276`), and a destroy that is not a
configuration change also clears the view model and its scope: a solve
belongs to the screen that shows it. Process death is covered by the
draft ([ADR-005](ADR-005-wizard-process-death.md)), not by keeping the run.
A run's outcome is held in the view model until a started screen takes it,
once (`PendingOutcome`, `ui/analysis/wizard/RunChannels.kt:83`), so a run
that ends while the wizard is in the background is handled when it returns.

**Rule for new code.**

1. If the user would lose something they asked for when the screen rotates,
   do not run it on `lifecycleScope`. Use the screen's ViewModel.
2. If it must finish even after the user leaves the screen, and it is short,
   use an application-lifetime `object` with a `StateFlow` state and a
   consume-once outcome, like `AccountDeletionRun`.
3. If it must survive process death, or it is long or retryable (any
   transfer), use WorkManager through `oneTimeWork` / `enqueueUnique`.
4. Never hold an Activity or a View in a longer-lived home; hold the
   `Application` (`SignOutRun.kt:62`) or plain data.
5. Do not widen `NonCancellable` to make cancellation "go away"; rethrow
   `CancellationException` everywhere else (ADR-018).

Other application-lifetime scopes exist for housekeeping, not for user
requests: startup cleanup and the seat heartbeat (`SemperApp.kt:33`, `:60-67`),
the status re-check after splash (`ui/auth/StatusRecheck.kt:27`), the wizard
draft's write lane (`data/prefs/WizardDraft.kt:101`) and the shared config
fetch (`data/net/SingleFlight.kt:18`). The summary GIF's build records and
locks are process-wide by output path (`ui/viewer/summary/SummaryAnimation.kt:295-298`)
so a rotated viewer reuses a GIF the old one finished.

## Options considered

### A: One home per kind of lifetime (chosen)

Each home matches a lifetime Android already defines (configuration,
process, beyond the process). The code says which one it needed by where it
lives.

### B: Everything that matters in WorkManager

WorkManager survives everything, but it has no way to hand a result to the
screen that is waiting except through `WorkInfo`, adds scheduling latency,
and runs under constraints and quotas. A share sheet that opens seconds
later, or a sign-out that waits for a worker slot, is worse than the bug.

### C: `configChanges` on every screen

It is what the wizard does, and it is right there: a solve is minutes of
native work. Elsewhere it moves the burden to hand-written layout changes for
rotation, dark mode and font scale, and it does nothing for a screen the user
closes or for process death.

## Trade-off analysis

A leaves three patterns to learn instead of one, and an application-lifetime
`object` is global state: tests must reset it (`AccountDeletionRun.resetForTest`,
`SignOutRun.resetForTest`). In exchange each piece of work lives exactly as
long as its outcome matters, and the singletons stay small: one run at a
time, a `StateFlow`, and a consume.

## Consequences

- A rotation no longer cancels an export, an account deletion or a
  sign-out. Tests: `ShareExportJobsTest`, `ViewerRotationTest`,
  `AccountDeletionRotationTest`, `AccountDeletionStagesTest`,
  `SignOutRunTest`, `EraseCancellationTest`.
- A rotation no longer cancels a restore or a multi-row phone erase on Home,
  a consent change, a data export or a phone-only erase on Settings, a seat
  request, a limit re-check or licence refresh, a Terms acceptance, or an
  admin approval; each outcome is said once, on the screen that is there.
  Tests: `HomeRestoreRotationTest`, `HomeEraseRotationTest`,
  `AuthRotationTest`, `SettingsRotationTest`, `SeatRequiredRotationTest`,
  `SessionLimitRotationTest`, `TermsRotationTest`, `AdminRotationTest`.
- Application-lifetime runs still end with the process. Account deletion
  is the one that writes down how far it got: `AccountDeletionMarker` is
  committed before the erase is sent and when it answers, and the next
  process finishes the wipe and sign-out (`AccountDeletionRun.resumeInterrupted`).
  An erase that never answered is settled by asking `GET /v1/me/erasure`
  (`CloudErase.probeErasedAccount`), which reads the ID token alone so the
  question does not re-create the erased profile (TD-206): wiped only when
  the account is known gone, asked again at the next start when there is no
  answer. Tests: `AccountDeletionDeathTest`, `test_account_deletion_probe.py`.
- The wizard's run outcome was a `SharedFlow` with no replay, collected only
  while the wizard was started, so an outcome emitted while it was in the
  background was dropped (TD-168). It is now held until consumed
  (`PendingOutcome`); test: `RunOutcomeDeliveryTest`.

## Action items

1. [x] Viewer exports in the ViewModel (#314), then the field metrics (#328).
2. [x] Account deletion under `NonCancellable` (#311), on an application
   scope with a consume-once outcome (#316), with the erase stage recorded
   (`ErasureWatch`, `AccountDeletionRun.kt:243-267`, #329).
3. [x] `SignOutRun` with live-screen tracking and the `Unclaimed` outcome
   (#316, #329).
4. [x] TD-165: persist the deletion stage and finish the wipe at startup
   (`AccountDeletionMarker`, `AccountDeletionRun.resumeInterrupted`).
5. [x] TD-168: the wizard's run outcome is held in the ViewModel until a
   screen consumes it (`PendingOutcome`).

## Amendments

**2026-10-19, a static check for rule 1 (proposed).** Rule 1 is enforced only by
review, and two screens broke it:

- **Home's restore.** `startRestore` clears the selection, then `queueRestores`
  runs inside `lifecycleScope.launch`
  (`ui/home/HomeActivity.kt:501-526`, launch at `:518`). That block looks up each
  row's backup on the network (`CloudSync.resolveCloudIdFor`,
  `data/cloud/CloudSync.kt:312-324`) and then enqueues the restores
  (`RestoreStart.startAll`, `data/cloud/restore/RestoreStart.kt:34`). Home has no
  `configChanges` (`app/src/main/AndroidManifest.xml:209`). A rotation during the
  lookup cancels it after the selection is already gone, so nothing is queued and
  the user is not told.
- **Sign-in.** `AuthActivity.runAuth` (`ui/auth/AuthActivity.kt:299-304`), the
  email-link send (`:197`) and Google sign-in (`:213`) all run on `lifecycleScope`,
  and `mode` (`:47`) is not saved. A rotation in the middle of sign-in loses the
  routing (`routeResult`, `:306-329`), the REGISTER form and the offer to save the
  password.

Both move to a ViewModel (the 2026-10-19 review's U7 and U8). To stop the rule
depending on review, a check should flag any `lifecycleScope.launch` or `async` in an
`Activity` (or `Fragment`) whose block directly calls a network entry point or
enqueues work:

- the `data/net` and `data/cloud` clients (`SemperApi`, `CloudApi`, `authed { }`,
  `CloudSync`, `RestoreStart.start*`);
- the `AuthRepository` sign-in calls;
- `WorkManager.enqueue*`, `enqueueUnique` and `oneTimeWork`.

An Android Lint check is the better fit, because UAST resolves calls through their
declared types and packages; it would live in a `lint-rules` module wired in with
`lintChecks`. A detekt rule can do the same with type resolution, or from a
configured list of names; it would live in a rules module added with
`detektPlugins`. Neither module exists yet (`app/build.gradle.kts:423-428` uses the
default detekt rules). Findings are never baselined (CLAUDE.md). A refresh that the
next `onResume` repeats anyway, such as Home's list refresh, is not a request the
user can lose, so it is suppressed with that reason given. The cites above are at
`763ed7a`, before the fix: #457 moved Home's restore to `HomeViewModel` and sign-in
to `AuthViewModel` (U7, U8). The check itself is TD-291.

**2026-10-19, the check is built (TD-291).** It is a Python source check,
`scripts/check_activity_scope_work.py`, not the lint or detekt rule proposed above:
it needs no rules module and runs in seconds on every event, in CI's `legal-pages`
job ([CI.md](../ops/CI.md)). It reads `ui/` and flags:

- a launch on a screen's scope: `lifecycleScope.launch` / `async` in an Activity or
  Fragment, `receiver.lifecycleScope` in any `ui/` file (a scope handed to one of the
  screen's parts, such as the `Settings*Section`s), a call that takes the scope and a
  trailing lambda (`job.launch(activity.lifecycleScope) { }`), and the lambda handed
  to a function that launches it (`runAuth { }`, as sign-in did before #457);
- whose block reaches, directly or through a function of the same file, one of the
  calls listed above: `api.<call>` / `SemperApi.get(…).<call>`, `authed { }`, the
  `data/cloud` objects less their phone-only members, `SeatLease`, the
  `AuthRepository` calls that reach Firebase or the backend, any `enqueue*( )` or
  `oneTimeWork`.

It matches names, not resolved types, so a client reached under another name or
through another file is missed; the lint rule stays the way to close that gap.
Exceptions live in `scripts/activity_scope_allowlist.txt` by file, function and call,
each with its reason and a TD- or ADR- pointer; an entry that no longer matches
fails. Run against `763ed7a` it flags Home's restore (`queueRestores`, through
`CloudSync.resolveCloudIdFor` and `RestoreStart.startAll`) and every sign-in path
through `runAuth`; on the tree after #457 it finds 17 calls, triaged in the allowlist:

- **Refreshes the screen or the next tap repeats** (allowed, as above): Admin's list,
  Pending's status check, Home's `cloudCheck`, the session-limit Recheck, Settings'
  analyses list.
- **Enqueues a cancel cannot lose** (allowed): Back up on Home and in Settings stamp
  the row PENDING first, and the next reconcile re-queues it; a rename's metadata
  send has no suspension point before it; the backfill offer enqueues from a dialog's
  click handler, not from the coroutine.
- **Still open** (allowed while open, each to move to a ViewModel or an
  application-lifetime run): Home's multi-row `eraseLocally`
  (`ui/home/SessionSelectionController.kt`), Settings' restore
  (`SettingsActivity.restoreBackup`, the pattern #457 fixed on Home), the seat
  checkout (`SeatRequiredActivity.takeSeat`), the Terms accept (`TermsActivity.onAgree`),
  the improvement-consent switch (`SettingsYourDataSection.wireLegal`) and the admin
  approve / deny (`AdminActivity.act`).

Tests: `scripts/tests/test_check_activity_scope_work.py`, over the Kotlin fixtures in
`scripts/tests/fixtures/activity_scope/` (a restore from `lifecycleScope` fails; from
`viewModelScope` it passes).

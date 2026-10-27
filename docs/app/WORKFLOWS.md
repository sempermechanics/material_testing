# App manual test pass

Every user-facing flow in Semper as a checkable step. Walk the tables top to
bottom on a debug build and tick the boxes; a full pass should never land you on
a screen this file doesn't name.

This is the **test script**. The map — which files each flow runs through, what
it writes, where its failures surface — is
[../WORKFLOWS.md](../WORKFLOWS.md), whose §A ids match the section numbers here
(§5.4 below is `A5.4` there). For the *automated* suite see
[TESTING.md](TESTING.md); for how the code is laid out see
[ARCHITECTURE.md](ARCHITECTURE.md).

Semper is Activity-based — no NavHost, no Compose, no Fragments — so most of what
follows happens inside four files. §11 records the paths that are gated, blocked
or dead, and is not part of the pass.

## Legend

| Mark | Meaning |
|---|---|
| `[admin]` `[sweep]` `[single]` `[debug]` | only reachable in that condition |
| `→` | navigates to another flow |
| `[ ]` | tick when the step passes |

---

## 0. App launch

Decides where you land. No user input; the whole flow is a routing decision.

**Entry:** launcher icon. **Exit:** Login, Pending approval, Home or Session limit.

| # | Action | Expected |
|---|---|---|
| [ ] 0.1 | Cold start, signed out | Login screen; no spinner flash on a fast device |
| [ ] 0.2 | Cold start, signed in and approved | Home, no re-authentication prompt |
| [ ] 0.3 | Cold start, signed in but not yet approved | Pending approval screen |
| [ ] 0.4 | Cold start in airplane mode, previously approved | Home opens with an "Offline mode" toast |
| [ ] 0.5 | Cold start in airplane mode, never approved | Login with an explanatory message |
| [ ] 0.6 | Cold start with the analysis quota already full | Home opens and the Session limit screen comes up on top of it — the quota check lives in `HomeActivity.onCreate`, not in Splash |
| [ ] 0.7 | Slow network on launch | Spinner appears after ~400 ms, not instantly |

---

## 1. Login

**One screen**, not five. The sign-in / create-account distinction is a mode
toggle on the same layout, and forgot-password is a link that fires an email —
neither opens a separate screen.

**Entry:** Splash, sign-out, or the sign-in deep link. **Exit:** Home or Pending
approval, depending on the backend's answer.

| # | Action | Expected |
|---|---|---|
| [ ] 1.1 | Tap **Sign in with Google** | Account chooser; on success you land in Home or Pending approval |
| [ ] 1.2 | Dismiss the Google chooser | Returns to Login silently — no error toast |
| [ ] 1.3 | Google sign-in on a device with no Google account | "No Google account available on this device." |
| [ ] 1.4 | Sign in with a valid email + password | Routes onward per account status |
| [ ] 1.5 | Sign in with a wrong password | Red message pill at the bottom (`CrispToast`, not a Snackbar), fields keep their contents |
| [ ] 1.6 | Enter a malformed email | Inline "invalid email" before any network call |
| [ ] 1.7 | Tap the mode toggle | Becomes "Create account"; the confirm-password field appears |
| [ ] 1.8 | Create an account with a 5-character password | Blocked: at least 8 characters |
| [ ] 1.8a | Try `abcdefgh`, `ABCDEFGH1!`, `Abcdefgh!`, `Abcdefg1` | Each blocked naming the rule it misses — lowercase, uppercase, digit, special character |
| [ ] 1.8b | Tap **Generate secure password** | Both password fields fill with a strong value, shown in clear so it can be saved, and it passes every rule |
| [ ] 1.8c | Sign in (not register) with an old short password | Still allowed — the rules bind new passwords, not existing accounts |
| [ ] 1.9 | Create an account with mismatched confirm | Blocked with "passwords do not match" |
| [ ] 1.10 | Create a valid new account | A verification email is sent, the session is dropped, and **the screen returns to Sign in** — email kept, both password fields cleared, no confirm box |
| [ ] 1.10a | Try to sign in before opening that link | Blocked with the same message; a fresh verification email is sent each time |
| [ ] 1.10b | Open the link, then sign in | Signs in and lands on Pending approval (new accounts aren't pre-approved) |
| [ ] 1.10c | Sign in with Google, or via an email sign-in link | No verification step — both arrive already verified |
| [ ] 1.11 | In register mode, look for the recovery links | Forgot-password / email-link links are hidden |
| [ ] 1.12 | Tap **Forgot password** with a registered email | Green message pill confirming the email was sent |
| [ ] 1.13 | Tap **Forgot password** with an unregistered email | Same success message — enumeration is deliberately not leaked |
| [ ] 1.13a | Open the reset link from that email on this device | The **app** opens on a set-new-password form — you never land on a Firebase web page |
| [ ] 1.13b | Enter a new password there | Same policy as registration applies; on success you are signed in and routed onward |
| [ ] 1.13c | Open a reset link that has expired or was already used | The form reports the link is no longer valid and offers to request a fresh one |
| [ ] 1.14 | Tap **Email me a sign-in link** | Confirmation pill; the email arrives with a link |
| [ ] 1.15 | Open that link on the same device | App opens and completes sign-in (needs verified asset links — see §11) |
| [ ] 1.16 | Open that link on a different device | "Open the sign-in link on the device that requested it." |
| [ ] 1.17 | Open the link while Login is already in the foreground | Handled in place, no duplicate screen |
| [ ] 1.18 | Arrive here from Splash after a status failure | The routing error is shown as a red message pill |

---

## 2. Pending approval

The allow-list gate. Firebase says who you are; the backend says whether you're
allowed in. A new account sits here until an admin approves it.

**Entry:** Splash or Login when status is `PENDING`. **Exit:** Home on approval,
Login on sign-out.

The backend mails support the moment the account is created `PENDING`, so an
admin learns about it without the user asking (see
[BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) §B1a). **Request access**
below is the user's own nudge on top of that, not the only signal.

| # | Action | Expected |
|---|---|---|
| [ ] 2.0 | Land here for the first time on a new account | support@ receives an "Semper access request" mail naming the account and its user id, without anyone tapping anything |
| [ ] 2.0a | Sign out and back in on that same account | No second mail — it is sent once, when the account is created |
| [ ] 2.0b | Same on a backend with no `RESEND_API_KEY` set | No mail and no error: sign-in still ends on this screen normally |
| [ ] 2.1 | Read the screen | Your email and a truncated device ID are both shown |
| [ ] 2.2 | Tap **Request access** | Mail app opens, prefilled with account, device ID, app version and device model |
| [ ] 2.3 | Same, with no mail app installed | Toast instead of a crash |
| [ ] 2.4 | Tap **Check status** while still pending | "Account is still pending approval." and you stay put |
| [ ] 2.5 | Have an admin approve, then tap **Check status** | "Access granted" and you land on Home |
| [ ] 2.6 | Wait on this screen without touching it | Nothing happens — approval is *not* polled |
| [ ] 2.7 | Tap **Sign out** → confirm | Login screen, back stack cleared |

---

## 3. Home

The session list and the only entry point to a new analysis.

```
3. Home — HomeActivity
   ├── Beta / data-use notice          (first run, non-dismissable, acked once)
   ├── Diagnostics opt-in prompt       (first run, after the notice; default off)
   ├── Coach mark on the FAB           (first run)
   ├── Cloud backups card ............ backups this phone has no row for
   │   ├── Restore ................... checklist (all ticked) → background restores
   │   └── Hide ...................... until a new backup appears
   ├── Session list
   │   ├── open a session ............ → 8. Result viewer, or 7. Lattice for sweeps
   │   ├── cloud state icon tap ...... retry backup / open Settings
   │   ├── "Only in cloud" row ....... "Restore <name>?" → background restore
   │   ├── live row progress ......... backup (prepare/upload) and restore/download
   │   └── "session data gone" dialog  (local frames deleted, no cloud copy either)
   ├── Selection mode (long-press)
   │   ├── select all
   │   ├── rename                      (only with exactly one selected)
   │   └── delete → phone / cloud backup / everywhere when every row has both;
   │                otherwise one Delete that removes every copy
   ├── Quota chip (from 80% of cap) .... → 9. Session limit / 4. Settings
   ├── Pull-to-refresh                  (deep cloud reconcile, repairs blobs)
   ├── Empty state → "Start analysis"   (same as the FAB — no longer Settings)
   ├── Start new analysis (FAB)
   │   ├── quota gate .................. → 9. Session limit
   │   ├── test-type sheet ............. → 3b. Which test?
   │   └── opens the source chooser .... → 3a. Media picker sheet
   ├── Settings (gear)
   └── Exit-app confirm on Back
```

**Entry:** Splash, Pending approval, the viewer's home button, sign-in.
**Exit:** Analysis, Settings, Result viewer, Lattice, Session limit.

| # | Action | Expected |
|---|---|---|
| [ ] 3.1 | First launch after install | Beta / data-use notice appears, cannot be dismissed by tapping outside; only "I understand" closes it |
| [ ] 3.2 | Relaunch, and again after signing out and back in | The notice does not reappear |
| [ ] 3.2a | Acknowledge the beta notice on a fresh install | A second dialog asks whether to send crash reports; **declining is the default outcome** and nothing is collected until you accept |
| [ ] 3.2b | Relaunch after answering it | It does not reappear; the choice is mirrored by the Settings toggle (§4, Your data) |
| [ ] 3.2c | Signed out (a debug build with no API URL): acknowledge, then relaunch and after a process kill; then sign in to an account that has not seen it | Signed out, the notice does not reappear; the account is asked once, since each account acks for itself |
| [ ] 3.3 | First visit | A tip card points at the **+** button. It is a single tip, so it has no "1 of N" count and no Skip, only a filled **Got it**, which dismisses it for good |
| [ ] 3.3a | Any first-run tip sequence (the picker, wizard steps 1–3, the result lattice) | The card shows "1 of N" in small grey text at the left, a grey text **Skip** and one filled **Next** at the right; a small pointer on the card's edge aims at the ringed control's centre, above or below it, and the card stays clear of the status and navigation bars. The last tip reads **Got it** with no Skip. Skip on any tip ends the sequence and it never returns. Each tip is three lines or fewer on a phone, in both themes; TalkBack reads each tip as it appears |
| [ ] 3.4 | Look at a session row | A rounded card with a thin outline, 16dp in from the screen edges. Thumbnail, name, then "N frames · 96.3%" (a sweep: "9 of 9 solved", from its own combination lists; the title names it a sweep; a run cut short: "39 of 50 frames · 90.0% · " and its stop reason), and a cloud state icon at the end. No date on the row. The convergence is the first frame's, from its engine stats; under 85% the figure is amber. TalkBack reads it as "96.3% converged". A row from before engine stats were kept shows its stored headline instead |
| [ ] 3.4d | Look above the rows, with analyses from today, yesterday and earlier | Small grey day headers: "Today", "Yesterday", then a short date such as "Oct 7", with the year for one from another year ("Dec 31, 2025"), in the phone's language and timezone. A header is not a row: it cannot be tapped or selected, and select-all counts only analyses |
| [ ] 3.4e | Select a row (long-press) | Its card turns light blue with a blue outline and a tick replaces the thumbnail |
| [ ] 3.4f | Scroll to the end of a long list | Cards are about 68dp tall (12dp around a 44dp thumbnail); the last card scrolls clear above the **+** button (the list's bottom padding follows the button's spot, nine tenths down), and earlier cards pass under it |
| [ ] 3.4a | Look at the thumbnail of a row whose frames are on the phone | The last frame's U-displacement heatmap, cropped to the field. The reference shows until it is drawn; a cloud-only row keeps the reference. A Re-run is a new row with its own thumbnail; the row it came from keeps its own |
| [ ] 3.4b | Make two analyses from the same reference image | The first is named after the image without its extension ("steel_00"; a reference `tensile.v2` keeps the whole name), the second "steel_00 (2)" — no date in the name. Rows named before this change keep their names, except as 3.4i puts right |
| [ ] 3.4i | Open Home on a phone an earlier version left with two rows of one name, or a restored row named "steel_00.png" | Every row reads differently: the oldest keeps the plain name, the others take "(2)", "(3)"; the "steel_00.png" row reads "steel_00" (or, from its backup's metadata, e.g. "steel_24 sweep"). Its id, files and backup are untouched |
| [ ] 3.4g | Look at the row of an analysis made from a video | "Video · 40 frames, 0:00–0:12 · 91.2%": the first and last frame's times in the clip, then the convergence, amber under 85% as on any row. TalkBack reads "… 91.2% converged". The same analysis restored from the cloud reads like a photo row ("40 frames · 91.2%"): the clip's times are not backed up |
| [ ] 3.4h | Run a parameter sweep on frame `steel_24` | The new row is named "steel_24 sweep" (a second one "steel_24 sweep (2)"); sweeps named before this change keep "Parameter sweep · …" |
| [ ] 3.4c | Read each cloud state icon with TalkBack | Cloud with tick "Backed up", up arrow "Upload pending", crossed out "Not backed up" (red when the backup failed), down arrow "Only in cloud" |
| [ ] 3.5 | Tap a normal session | Result viewer opens on frame 1 |
| [ ] 3.6 | Tap a sweep session | **Lattice** opens, not the viewer |
| [ ] 3.7 | Tap a row whose local files were deleted but which has a cloud backup | Its icon is the **"Only in cloud"** cloud-download; tapping raises a **"Restore steel_00?"** dialog (the analysis's name; "It's only in the cloud.") with a **Restore** button, which queues a background restore and **leaves you on Home** — it does not open the analysis when it lands |
| [ ] 3.7a | Tap a row with no local files *and* no cloud copy | "Session data gone" dialog — this is now the only case that reaches it |
| [ ] 3.7b | Watch a row during a backup | The icon turns to the blue up arrow, a 2dp bar under the text fills, and the line reads "Preparing backup · 12.0%" then "Backing up · 35.0%" (or "Backing up · 4.2 of 12 MB" once the job reports bytes) |
| [ ] 3.7c | Watch a row during a restore or download | Same row progress, with a down arrow and "Restoring". The bundle phase is deliberately **indeterminate** (and the line just "Restoring") until the backend reports a percentage |
| [ ] 3.7d | Let a restore fail terminally while on Home | A message pill names the reason here too, not only in Settings |
| [ ] 3.7e | After 3.7d, reopen Home, then open Settings | The same failure is **not** announced again on either screen |
| [ ] 3.7f | Sign in on a phone that has none of the account's backups (a new phone, or after a reinstall) | Once the cloud check finishes, a card above the list reads "4 analyses in your cloud backup aren't on this phone." with **Hide** and **Restore**. With no rows, the empty state's title reads **No analyses on this phone** instead of "No analyses yet". Demo accounts never see the card |
| [ ] 3.7g | Tap **Restore** on that card | A **Restore to this phone** checklist, every backup ticked, each named with its size. Untick all and **Restore** greys out. Restore queues the ticked ones, toasts once, and each lands as a row with its own progress; the card counts down to what is left |
| [ ] 3.7g1 | Read that checklist when the account has several backups of one image, and the phone a row of that name | No extension and no two alike: "steel_00 (2) · 128 MB", "pmma_00 · 216 MB", "pmma_00 (2) · 216 MB". Restore them all: each lands in a row of its own, under the name it was backed up with ("steel_24 sweep", or "steel_00" for an old dated or "steel_00.png" name), numbered if another row has it — never two rows that read alike |
| [ ] 3.7h | Tap **Hide** | The card goes, with a message saying Settings can still restore them. It stays gone across relaunches until the account gains a backup it has not seen, which brings the card back for that one only |
| [ ] 3.7i | Delete a backup from Settings, or sign out and in as another account | The card stops offering the deleted backup at once; the other account starts with nothing hidden and nothing offered until its own cloud check |
| [ ] 3.8 | Tap an "Upload pending" cloud icon | Upload is retried / queued |
| [ ] 3.8a | Tap a red crossed-out cloud (a failed backup) | A dialog names *why* the last backup failed (device conflict, too large, render ran out of memory, result files no longer on the device) with a **Try again** action — not a silent re-queue |
| [ ] 3.8b | Let a background backup fail terminally while on Home | A message pill surfaces the reason once (quota-full is excluded — it has its own screen) |
| [ ] 3.8c | Testers only: sign in to a debug Semper Classroom on the same phone (same debug key, so the same device id, TD-7), then back up or restore in Semper | It works first time. The first signed call is refused `bad_signature`; Semper registers its key again and sends it once more (logcat: "registered it again"). No prompt, and the licence stays as it was |
| [ ] 3.9 | Tap a "Not backed up" cloud icon with cloud backup switched off | Settings opens |
| [ ] 3.10 | Long-press a row | Selection bar with count, select-all, rename, re-run (circular arrow), delete, close |
| [ ] 3.10a | Select several **Only in cloud** rows | A **Restore** (cloud-download) button joins the bar; tapping it queues one restore per row, shows "Restoring 3 analyses…" once, and each row shows its own progress. Add a row that is on the phone and the button goes away. Demo accounts never see it |
| [ ] 3.11 | Select two rows | Rename and Re-run disappear; delete still offered |
| [ ] 3.11a | Select one row whose frames are on the phone, saved by this version, and tap **Re-run** (TalkBack: "Re-run as a new analysis"; long-press shows "Re-run") | The selection ends and the wizard opens on **Parameters** after a short "Copying the analysis's photos…": the same reference, the same frames in the same order with their names, the same ROI and mask, and every setting as run (subset, step, strain window, overlap, interpolation). **Compute** saves a **new** row ("steel_00 (2)"), counted towards the quota; the first row, its frames, thumbnail and backup are unchanged (owner decision, 2026-10-15) |
| [ ] 3.11b | Re-run a parameter sweep | The wizard opens on the sweep's settings page in **Parameter sweep** mode: the same subset and window ranges, sample counts, step depth, line-cut axis and planned lattice, with the one frame the sweep solved against |
| [ ] 3.11c | Re-run an analysis made from a video | Its frames keep their times and the clip's name: the new row reads "Video · 40 frames, 0:00–0:12" |
| [ ] 3.11d | Re-run, then sort the frames by name or date on step 1 | Only the wizard's copies are renamed; the first analysis still opens, exports and backs up with all its images (TD-197) |
| [ ] 3.11e | Re-run an **Only in cloud** row | "Can't re-run this analysis" / "Its photos aren't on this phone. Restore it first."; nothing opens and the selection stays |
| [ ] 3.11f | Re-run a row freed up in Storage | Backed up, with restore available: as 3.11e ("Restore it first"). Otherwise (not backed up, or restore not available to this account or paused): "Can't re-run this analysis" / "This phone doesn't have its original photos." (`SessionSelectionController.kt:181-185`); nothing opens either way |
| [ ] 3.11h | Re-run a row restored from the cloud, or saved before Re-run kept its reference | "Pick its reference photo" / "This analysis was saved before Re-run kept its reference. Choose the same reference photo; its frames and settings come from the analysis." with **Choose photo** / **Cancel**. Cancel: nothing opens. Choose photo: the reference picker (Photos, with Files for DNG) |
| [ ] 3.11i | In 3.11h, pick a photo of another size | "This photo is 3000 × 4000; the analysis used 4000 × 3000." with **Choose another photo** / **Cancel**; the wizard does not open |
| [ ] 3.11j | In 3.11h, pick the same reference | "Checking the photo…" (Cancel stops it), then the wizard opens on **Parameters** (or the sweep page) with that reference, copies of the saved frames in run order with their names (and a video's times, on a row saved on this phone), the region and every setting. A row whose region was drawn shows once "The mask wasn't saved; the region is a rectangle." and the ROI is that rectangle; a whole-image row shows nothing. **Compute** saves a new row; the first is unchanged |
| [ ] 3.11k | Rotate the phone while the picker of 3.11h is up, then pick | The check still runs for the same analysis |
| [ ] 3.11g | Re-run at the quota cap, or on an institution licence with no free seat | The session limit (or seat) screen, as for **+**; the wizard does not open |
| [ ] 3.11m | Re-run a **Tensile** or **Bending** analysis | The wizard opens as the same test with the same photos and settings and its load card filled: cross-section or beam dimensions, strain axis, beam-edge taps, and bending's typed masses. Typed bending lands on Parameters, ready to Compute; one that read a load log lands on step 1 with the log to import again. Its run has Results and a lab report (TD-220) |
| [ ] 3.12 | Rename a single selection | Text dialog; the new name persists after leaving and returning |
| [ ] 3.12b | Rename a row to another row's name | It is saved with the next free "(2)", "(3)", and a toast reads "Saved as steel_00 (2)" |
| [ ] 3.12a | Rename a backed-up analysis, then restore it on another phone (or after a reinstall) | It restores under the new name: the rename re-sends the backup's metadata.json (ADR-013) |
| [ ] 3.13 | Delete one session that exists **both** on the phone and in the cloud | "Delete steel_00?" (the analysis's name) and "A deleted cloud copy can't be recovered.", then **From this phone** (hint "cloud stays"), **From the cloud** (hint "phone stays") and **Everywhere**, plus Cancel |
| [ ] 3.13a | Choose **From this phone** | Message pill: "Removed from this phone · in the cloud". The row stays, its icon now "Only in cloud" |
| [ ] 3.13b | Delete a row that is already cloud-only, on device only | No-op branch — there is nothing local left to remove |
| [ ] 3.14 | Delete several sessions | "Delete 5 analyses?" with the same three choices when every row is on both ("5 are in the cloud. A deleted cloud copy can't be recovered."). A selection with no cloud copy gets one plural confirm ("This can't be undone."); a mixed selection or cloud-only stubs get one Delete that removes every copy ("3 are in the cloud. Delete removes every copy. …"). One phone-only row deleted reads "Deleted steel_00" |
| [ ] 3.14a | Choose **Everywhere** for ten rows | The rows disappear at once; a message pill offers **Undo** for 5 s, then reads "Deleting 4 of 10…", then "10 deleted". Production logs show ten DELETEs and no 404 |
| [ ] 3.14b | Tap **Undo** inside the 5 s | The rows come back and nothing reaches the backend |
| [ ] 3.14c | Delete while offline | The rows stay hidden; the delete runs when the network returns. If it still cannot reach the cloud, the pill names how many are left, with **Try again** |
| [ ] 3.15 | Press Back in selection mode | Selection clears; the app does not exit |
| [ ] 3.16 | Tap the quota chip below the cap | Settings (or the limit screen at the cap) |
| [ ] 3.17 | Reach the quota cap | The chip turns red |
| [ ] 3.17a | Use 79% of the cap, then 80% | Hidden at 79%; at 80% a chip under the title reads "N / M analyses used" with the real cap M, in the secondary colour |
| [ ] 3.17b | Look at the title row | "Analyses" (20sp) and the gear on one line; the quota and licence chips, when shown, sit under it. Selection mode's bar keeps the same 20sp heading |
| [ ] 3.18 | Pull to refresh | Cloud reconcile runs; a repair or failure is reported by toast |
| [ ] 3.19 | Open Home with no sessions | Empty state reading "Import photos or a video to start an analysis." with a **Start analysis** button — it does what the FAB does; it no longer opens Settings |
| [ ] 3.20 | Tap **+** below the quota | The **Which test?** sheet (§3b) opens; picking a test opens the **New analysis** sheet (§3a) |
| [ ] 3.21 | Tap **+** at the quota cap | Session limit screen instead of the sheet |
| [ ] 3.22 | Look for a transfer banner, feedback prompt or upgrade prompt on Home | There is none. Home's only progress surface is the per-row icon, text and bar; the transfer banner lives in Settings and the result viewer |
| [ ] 3.24 | Press Back on Home | "Exit app?" confirmation |

### 3a. New analysis — the media picker sheet

Not an Activity: `MediaPickerSheet`, a full-height bottom sheet titled **New
analysis**. It is what the Home **+** button opens directly — there is no
intermediate menu — and the same sheet the wizard's two dropzones open (§5.1),
so test it once here.

| # | Action | Expected |
|---|---|---|
| [ ] 3a.1 | Tap **+** on Home and pick a test | The **New analysis** sheet opens **full height**; for ~1 s the grid is dimmed behind a large centred hint ("Select the reference image"), then tiles unlock |
| [ ] 3a.1a | Tap a tile during the dim | Nothing is selected until the hint ends |
| [ ] 3a.1b | Open the deformed-frames picker | Multi-select works immediately — no dim, no delay |
| [ ] 3a.2 | First open, having never granted media access | An empty state with an **Allow access** button; granting fills the grid without reopening the sheet |
| [ ] 3a.3 | Look at the grid | Three columns of device media; videos carry a badge so they are distinguishable from stills |
| [ ] 3a.4 | Pick a still as the reference | The sheet closes and step 1 shows it |
| [ ] 3a.5 | Pick a video | The sampling sheet opens instead (§5.1a) |
| [ ] 3a.6 | Open the sheet for deformed frames and tap several tiles | Multi-select; the confirm button counts them ("Use 12") |
| [ ] 3a.7 | Look for select-all | It is under the three-dot menu, which the first-run coach mark points out |
| [ ] 3a.8 | Tap the **Files** tab | The sheet dismisses and the system SAF browser opens for images *and* video — this is still the only route to DNG/RAW |
| [ ] 3a.9 | Open the sheet the first time in each mode | Coach marks run once for the reference pick and once for the deformed pick, then never again |
| [ ] 3a.10 | Check what permission is asked for, and when | `READ_MEDIA_IMAGES` (and `READ_MEDIA_VIDEO` from Home) is requested when the **Images** tab needs it — never on the Files path |

### 3b. Which test? — the test-type sheet

Not an Activity: `TestTypeSheet`, a short bottom sheet the Home **+** opens
before the media picker, drawn like Semper's other sheets (a title, flat rows
split by hairlines). It is the one screen this app adds in front of Semper's
flow ([ADR-022](../adr/ADR-022-semper-ui-plus-lab-tests.md)). Tensile and bending import the machine's load log in
the wizard (§5.1b); the type decides which specimen dimensions the card asks for
and how a load becomes a stress. **2D DIC** is plain DIC: no load card, and the
session is saved with no test type, like one made before test types existed.

| # | Action | Expected |
|---|---|---|
| [ ] 3b.1 | Tap **+** on Home | **Which test?** with three rows, in this order: **2D DIC** ("Displacement and strain maps from your photos alone"), **Tensile test** (stress–strain curve and E, with the load log), **Bending test** (deflection and E, with the hanger loads and beam size). No subtitle under the title |
| [ ] 3b.2 | Swipe the sheet down | Nothing opens; Home is unchanged |
| [ ] 3b.3 | Pick **Bending** | The **New analysis** sheet (§3a) opens; the wizard's load card asks for span, width and thickness and has a **Beam height → Set** row (§5.1b, §6a); the strain-axis toggle is hidden |
| [ ] 3b.4 | Rotate the phone between picking a test and picking media | The test survives — the resulting session still carries it |
| [ ] 3b.5 | Finish any run, open the viewer, tap ⓘ | **Test type** is the first row of Settings used, naming the test picked in 3b.1 |
| [ ] 3b.6 | Reopen that session from Home; back it up, delete it locally, restore it | The ⓘ row still shows the test after each reopen |
| [ ] 3b.7 | Open a session made before this build | No **Test type** row; nothing else changes |
| [ ] 3b.8 | Pick **2D DIC**, pick photos | The wizard is Semper's: no load card; the strain window starts at 5 points and its ⓘ reads as Semper's; **Compute** needs only the reference and a deformed frame |
| [ ] 3b.9 | Finish a 2D DIC run | The viewer opens on the looping summary, not **Results**; ⓘ has no **Test type** row; Share has no **Lab report**; the CSV is `# semper_csv_version,1` with no load or stress columns |
| [ ] 3b.10 | Pick **Bending**, then tap the strain window's ⓘ on step 2 | Semper's text, then a sentence on why bending starts at 9 points |
| [ ] 3b.11 | Tap **+**, then **No photos yet? Try an example with published data** under the three tests | The sheet closes and **Example data** (§3c) opens |

### 3c. Example data — `ExamplesActivity`

The five sets bundled in the APK ([ADR-031](../adr/ADR-031-bundled-example-data.md)),
from the **+** sheet (§3b.11) or Settings → Help (§4.26c). Each card: title,
**Works** or **Expected to fail**, photo count, summary, licence and authors.
A tap opens its sheet: the setup Try it will fill in, what to expect (the
authors' values and an earlier app run, labelled), the credit, **Original
data**, **Licence**, **Try it** and **Save to phone**.

| # | Action | Expected |
|---|---|---|
| [ ] 3c.1 | Open **Example data** | Five cards in order: Steel strip in tension, PMMA beam in 3-point bending, Concrete beam (expected to fail), Carbon-fibre plate with a hole, Sub-pixel shift |
| [ ] 3c.2 | Tap **PMMA beam** | Its sheet: Bending, 33 deformed frames, 2380 × 1228 px; span 75, width 12, depth 31 mm; load log `pmma_loads.csv` in N; tap hint x ≈ 1170, y ≈ 39 / 611; E from the graph ≈ 1.98 GPa; credit with doi:10.5281/zenodo.1172068, CC BY 4.0, what was changed, "No endorsement…" |
| [ ] 3c.3 | Tap **Original data**, then **Licence** | doi.org opens the Zenodo record; the CC BY 4.0 deed opens |
| [ ] 3c.4 | Tap **Try it** | The wizard opens as Bending with the reference, 33 frames, the load log paired (33 rows, N), L/b/t filled and the ROI drawn; the beam's edges are still to tap |
| [ ] 3c.5 | Tap the edges at the hinted spots, Compute | E from the graph near 2.0 GPa; the run counts toward the analysis limit like any other |
| [ ] 3c.6 | **Steel** → Try it → Compute | Tensile, area 12.5 mm², axis x, 40 frames, kN log paired; E near 150 GPa |
| [ ] 3c.7 | **Concrete** → Try it → taps → Compute | "Stopped early" after frame 2 |
| [ ] 3c.8 | **Carbon-fibre plate** → Try it → Compute | 2D DIC, no load card, ROI drawn; V around −0.6 px |
| [ ] 3c.9 | With the frame limit at 10 (§4.25), Try it on Steel | "Too many frames for this account"; the wizard does not open |
| [ ] 3c.10 | **Save to phone** on any set | A progress dialog, then "Saved to your phone" naming `Download/Semper examples/<set>/`; Files shows the photos, the log, README.md, CREDIT.txt (and LICENSE.DICe for the DICe sets) |
| [ ] 3c.11 | Save the same set again | It finishes at once; nothing is duplicated |
| [ ] 3c.12 | Cancel a save part-way | The dialog closes; no half-written file is left in the folder |
| [ ] 3c.13 | Tap **Credits & licences** at the top | §4.26d |

**3c.1–3c.13 on a Galaxy S22+ (Android 16, `5b3b3948` + `claude/example-s22-fixes`,
2026-10-27)** pass, signed in to a QC test account. On `5b3b3948` alone 3c.11
failed: `LICENSE.DICe` was saved as `LICENSE.DICe.txt` (MediaStore names a
`text/plain` download `.txt`), so the second save wrote `LICENSE.DICe (1).txt`
beside it; it is now saved as a generic binary under its own name. The same pass
fixed "1 deformed frames", the saved message's load log on a set without one, and
the sheet opening half-way with Try it under the navigation bar. 3c.3 stopped at
Chrome's first-run screen, which is the phone owner's to accept; the links sent
`https://doi.org/…` and `https://creativecommons.org/…`. 3c.12's cancel left the
files already written, each complete, and no pending row. The numbers are in
[REAL_WORLD_VALIDATION.md](REAL_WORLD_VALIDATION.md#try-it-on-a-galaxy-s22-2026-10-27).

## 4. Settings

One scrolling screen of seven collapsible sections, all collapsed on open, plus a
two-button footer. Long-running work here does **not** block the screen: restores,
downloads and the two data exports run behind a **transfer banner** pinned at the
top of Settings (§4.0).

**Entry:** the Home gear (also the quota chip and any cloud state icon on a row).
**Exit:** Home, Admin, a result, or Login.

#### 4.0 The transfer banner

A non-modal strip at the top of Settings, not a dialog: title, a status line with
the percent to one decimal on its right ("34.6%"), a progress bar (spinning until a
percentage is known), "About N s left" under the bar once there is an estimate,
**Cancel**, and — when more than one transfer is live — **‹ ›** arrows with an
"n / N" page count. A restore or download's status reads "4.2 of 12.0 MB · 1.1 MB/s".
It carries restores, bundle downloads, **Export my data** and **Download cloud
data**.

| # | Action | Expected |
|---|---|---|
| [ ] 4.0.1 | Start any restore, download or export | A banner appears at the top of Settings; the rest of the screen stays usable |
| [ ] 4.0.2 | Start a second one while the first runs | The banner pages: "1 / 2", with ‹ › to step between them |
| [ ] 4.0.3 | Tap **Cancel** on a page | That transfer stops; the others keep running and the paging recounts |
| [ ] 4.0.4 | Let one finish | Its page disappears; the banner hides itself once the last one is done |
| [ ] 4.0.5 | Watch a restore or download for a few seconds | The status reads "x.x of y.y MB · z.z MB/s", the percent has one decimal, and "About N s left" appears after about 3 s |

| # | Action | Expected |
|---|---|---|
| [ ] 4.1 | Open Settings | All seven sections are collapsed; chevrons rotate on tap |
| [ ] 4.2 | Expand **Account** | Your email and "Device ID · …" are shown; the device ID can be selected and copied |
| [ ] 4.2a | Expand **Account** on a licensed account | "Licensed as SEMP-…" is shown below the device ID — the key prefix support asks for, never the key. Absent whenever the account runs as Demo, a held key that is not active included |
| [ ] 4.2b | Expand **Account** on a test account staff made for QC or CI (ADR-027) | "Test account · ends 18 Nov 2026" (the day, in the phone's language) below the licence line, or "Test account (CI)" for a CI account with no end. No such line on a real account (`SettingsAccountSection.testAccountLine`, from `/v1/config`'s `testAccount`) |
| [ ] 4.3 | Expand **Account** as a non-admin | No "Pending access requests" button |
| [ ] 4.4 | Expand **Account** as an admin | The button appears and opens the admin list |
| [ ] 4.4a | Tap **Refresh licence** under **Account** | A spinner on the button, then one line: "Licensed (institution seat)", "Licensed (Semper licence)", "Still on the demo", or, offline, "Couldn't reach Semper — will retry". If the licence changed, Settings rebuilds with the sections it now opens or locks. No field to type a key in, and no price or purchase anywhere ([ADR-024](../adr/ADR-024-account-anchored-entitlements.md)) |
| [ ] 4.4b | On a demo account, expand **Cloud backup**, **Analyses data management** and **Storage** | Each is shown, its controls disabled, with the note "Included with an institution seat or a Semper licence. Ask your institution's IT, or contact Semper support."; a tap anywhere in it says the same. With backup switched off by a kill switch or the EMM, they are hidden instead |
| [ ] 4.5 | Turn **Save to cloud** on with local-only analyses present | A dialog offers to back up N of them |
| [ ] 4.6 | Accept that offer | Uploads are queued; the cloud icons on Home move to "Upload pending" |
| [ ] 4.7 | Turn **Save to cloud** off | No new uploads are queued. Neither switch has a sub-line; with TalkBack each says what it does ("Save to cloud: new analyses upload when a network is available…", "Wi‑Fi only: wait for unmetered Wi‑Fi before uploading") |
| [ ] 4.8 | Toggle **Wi-Fi only uploads** on, then queue an upload on mobile data | The upload waits for Wi-Fi |
| [ ] 4.9 | Read the sync status line | "Up to date" or a pending count, matching the cloud icons on Home |
| [ ] 4.10 | Expand **Analyses data management** | Merged local + cloud list; each row shows a state line |
| [ ] 4.11 | Same, while signed out or with no backend | An explanatory line instead of an empty list |
| [ ] 4.12 | Tap a row that exists locally | That analysis opens (viewer or lattice) |
| [ ] 4.12a | Tap a row with no local data | The restore confirm, not a "session data gone" dialog |
| [ ] 4.13 | Tap **Back up now** on a local-only row | Upload is queued; the row state changes |
| [ ] 4.14 | Tap **Restore** on a cloud-only row | A **"Restore steel_00?"** confirm first; accepting toasts that it continues in the background, adds a **stub row immediately** so you can see it, and raises a banner entry (§4.0). The analysis appears in the list without reopening Settings |
| [ ] 4.14a | Restore a backup that fails terminally (deleted server-side, or not this account) | A message pill names the failure — the restore is no longer silent |
| [ ] 4.14b | Look at a row that is on the phone **and** in the cloud | It offers **Download**, but not Restore — Restore only appears when the local frames are missing |
| [ ] 4.14c | Tap **Download** | A SAF save dialog opens **first**, suggesting `<name>_Session.zip`; choosing a location starts a `DicBundleDownloadWorker` and the row reads "Downloading…" |
| [ ] 4.14d | Leave the section, or Settings entirely, mid-download | It keeps going — this is WorkManager, not an Activity scope — and reports the result when it lands |
| [ ] 4.14e | Look for a **Send to** sheet after a Download | There is none, by design: you already chose the destination, so the bytes go straight there |
| [ ] 4.14f | Download a row whose cloud zip is unavailable | It falls back to packing the local session into the same destination |
| [ ] 4.14g | Cause a Download to fail | The empty destination file is removed rather than left as a 0-byte zip, and the failure is named |
| [ ] 4.14h | Look at cloud-only rows of backups made from one image, beside a phone row of that name | Named without the extension and never alike: "steel_00 (2)", "pmma_00", "pmma_00 (2)"; restored, each keeps the name it was backed up with, numbered on a clash |
| [ ] 4.14i | Back up a sweep and a renamed analysis, then look at their cloud-only rows here and on Home's restore list (another phone, or after deleting the phone copies) | Listed by the analysis's name ("steel_24 sweep", the new name), the name the restore gives the row, not the reference's file name. Rename one after its backup: after the metadata re-send the list shows the new name. A backup from an older build, or on a backend from before TD-195, is still listed by its reference's name |
| [ ] 4.15 | Tap the bin on a row with a local copy | The same three choices as Home §3.13: phone / cloud backup / everywhere, plus Cancel |
| [ ] 4.16 | Tap the bin on a cloud-only row | "Delete steel_00 from the cloud?" naming the analysis, "Raw images, results and report. This can't be undone.", and **Delete** |
| [ ] 4.17 | Confirm any backup delete, then tap **Undo** within 5 s | The row returns; nothing is deleted server-side |
| [ ] 4.18 | Confirm and wait past the undo window | The backup is really gone after a refresh |
| [ ] 4.18a | Expand **Storage** | Analyses and cache sizes are measured and shown, not left on "Measuring…" |
| [ ] 4.18b | Tap **Free up N MB** with backed-up analyses present (the row only shows then, its N what the backed-up analyses hold here) | A confirm dialog first, **naming how much it will reclaim** — only the frames, raw images and processed/staging folders it drops, not the kept `reference.png` / small files (`LocalArtifacts`), so the figure matches what is freed; accepting drops those local frames, the rows become "Only in cloud" on Home and the analyses total falls |
| [ ] 4.18c | Look for it with nothing safely backed up | There is no free-up row at all — it cannot strand un-backed-up data. A demo account never has one |
| [ ] 4.18d | Tap **Clear** at the end of the **Temporary files** row | The cache total drops; open analyses still work — only regenerable files go. At 0 bytes **Clear** is gone |
| [ ] 4.18e | Drag the **auto-free** slider off 0 | The value at the header's end reads "Over N GB"; at 0 it reads "Off" |
| [ ] 4.18f | Set a budget below current usage and restart the app | Space is reclaimed at start-up, oldest backed-up analyses first |
| [ ] 4.18g | Tap the ⓘ beside it | Explains what Off means (analyses stay until you remove them), what a size does, and that only cloud-backed analyses are ever dropped |
| [ ] 4.19 | Tap **Export my data** | A master ZIP is built behind the **transfer banner** (§4.0) — not a blocking dialog — then handed to the **Send to** sheet (§8.5a) |
| [ ] 4.19a | Tap **Download cloud data** | The server-side export of the account is fetched the same way, banner and all, then offered through the same sheet |
| [ ] 4.19b | Trigger either export with no network | It fails with a named reason, not a silent no-op |
| [ ] 4.19c | Toggle **Send crash reports** off, then force a crash on a debug build | Nothing is uploaded; turning it on again resumes collection without a restart |
| [ ] 4.19d | Read what that toggle actually controls | It gates **both** crash reporting and consent-gated product analytics (analysis started/completed/failed, exports, feedback), and the label now says so: **Send crash reports and usage data**, with the subtitle naming the usage events and what is never sent |
| [ ] 4.20 | Tap **Delete account** | "Delete your account?" listing exactly what goes: every analysis on this phone, every cloud backup, your profile and device; then **Delete everything** |
| [ ] 4.20a | Confirm it | The **sign-in screen** opens to re-verify, with your email filled in and locked, and no "create account" toggle |
| [ ] 4.20b | Enter the wrong password there | "Incorrect password." and nothing is deleted |
| [ ] 4.20c | Confirm it, as a Google account | Same screen; **Sign in with Google** re-authenticates instead |
| [ ] 4.20d | Confirm it, as an email-link-only account | **Email me a sign-in link** works too — this account could not be deleted at all before |
| [ ] 4.20e | Press back on that screen | Returns to Settings; nothing is deleted |
| [ ] 4.21 | Confirm the delete with no network | Nothing local is touched; a failure toast is shown |
| [ ] 4.22 | Confirm the delete online | Everything is wiped, including the sign-in identity, and you land back on Login |
| [ ] 4.22a | Sign up again with the same email afterwards | It behaves as a brand-new account — the old identity is gone |
| [ ] 4.23 | Drag the **Max frames** slider | Value label tracks in steps of 10 from 10 up to the ceiling. 500 is the compile-time fallback and the backend default; the live ceiling comes from remote config, so a backend can lower it |
| [ ] 4.24 | Tap the ⓘ next to it | Explains the cost of more frames |
| [ ] 4.25 | Set it to 20, then import 40 frames in an analysis | Only the first 20 are kept, with a "capped" toast |
| [ ] 4.26 | Expand **Help & support** | Seven actions: **Open Manual**, **Example data**, **Credits & licences**, Report a bug, Request a feature, **Send feedback**, Email support — plus the support address, selectable and copyable |
| [ ] 4.26a | Tap **Open Manual** | The hosted manual opens in a browser |
| [ ] 4.26c | Tap **Example data** | §3c |
| [ ] 4.26d | Tap **Credits & licences** | Each example set's credit (authors, title, DOI or link, licence, what was changed), then the full DICe BSD-3-Clause, Eigen MPL-2.0 and OpenCV Apache-2.0 texts; selectable |
| [ ] 4.26b | Tap **Send feedback** | Mail app opens to support@, subject "Semper Classroom feedback (v… / …)", body carrying app version, device model, Android level and build type — no account address needed |
| [ ] 4.27 | Tap **Email support** | Mail app opens to support@, subject "Semper Classroom support request", body carrying account, device ID, app version and device model |
| [ ] 4.28 | Same with no mail app installed | "No email app found…" toast naming the address; no crash |
| [ ] 4.29 | Tap **About** | "Semper v<name> (<code>)" plus **Privacy Policy** and **Terms of Service** buttons |
| [ ] 4.29a | Tap either legal button | The hosted page opens in a browser; both are reachable without an account |
| [ ] 4.30 | Tap **Sign out** → confirm | Login, back stack cleared |

### 4.1 Admin `[admin]`

| # | Action | Expected |
|---|---|---|
| [ ] 4.1.1 | Open the admin list | Every user awaiting approval is listed — each one should match a mail support already received (§2.0) |
| [ ] 4.1.2 | With no one waiting | "No pending requests." |
| [ ] 4.1.3 | Tap **Approve** | Toast, list reloads, that user can now get past Pending approval |
| [ ] 4.1.4 | Tap **Deny** | Toast, the row disappears |

---

## 5. Analysis

`StaticAnalysisActivity` is a three-page wizard in one Activity. Page 3 exists
only in sweep mode, so the toolbar shows two or three dots, the current page in
blue, depending on the mode chosen on page 2. The toolbar title names the page:
"New analysis", then "Parameters" (single) or "Sweep setup" (sweep), then
"Sweep settings".

**Entry:** the Home FAB, after picking a source. **Exit:** Result viewer,
Lattice, Session limit, or back to Home.

### 5.1 Step 1 — Load frames

| # | Action | Expected |
|---|---|---|
| [ ] 5.1.1 | Tap the reference dropzone | The **New analysis** sheet (§3a) opens on Images — the same sheet the Home FAB uses |
| [ ] 5.1.2 | Pick a `.dng` or `.tif` via **Files** | Card shows the filename and `W × H`; no decode error |
| [ ] 5.1.2a | Pick a large `.dng` via **Files**, then a large JPEG | While each decodes (past ~0.3 s) the reference slot shows a grey placeholder thumbnail and "Decoding RAW · N MP" (no "· N MP" when the header gives no size), then "Decoding image" for the JPEG; a quick decode shows nothing; with animations off the placeholder does not pulse (`ReferenceSlotBusy`) |
| [ ] 5.1.3 | Tap **Change** on the reference card | Source chooser reopens; the new image replaces the old |
| [ ] 5.1.4 | Pick deformed frames from the sheet's grid (multi-select, then **Use N**) | Card shows "N frames" and the first…last filenames |
| [ ] 5.1.5 | Pick more frames than *Max frames* | The first N are kept, with a "capped" toast |
| [ ] 5.1.6 | Load a reference only | **Next** is disabled — a grey button with grey text, not sky blue — with "add at least one deformed frame to continue" |
| [ ] 5.1.7 | Load deformed frames only | **Next** is disabled with the matching reference message |
| [ ] 5.1.8 | Include one frame of a different pixel size | **Compute** stays disabled. On step 2 a warning chip says the image resolution isn't matching the reference (W×H) and lists the mismatched filename(s); its info icon asks first whether to leave the app, then opens the frame-size FAQ |
| [ ] 5.1.9 | Load JPEGs | A non-blocking accuracy warning chip appears; its info icon asks first whether to leave the app, then opens the JPEG FAQ |
| [ ] 5.1.10 | Load a poorly speckled reference | A low-texture warning names a suggested subset size; its info icon opens the speckle FAQ behind the same leave-the-app confirm |
| [ ] 5.1.11 | Load any speckled reference and open step 2 | While the speckle is measured (past ~0.3 s) the chip beside **Subset size** reads "Measuring speckle…". Then the slider slides to the recommendation (~250 ms), the chip reads "Speckle D px" (amber outside 3–9 px), and a band is shaded behind the track from the smallest size that holds enough speckle. No caption sits under the slider |
| [ ] 5.1.11a | Tap the ⓘ beside **Subset size** after 5.1.11 | The dialog says what the shaded band means: the sizes that hold enough speckle. Moving the slider leaves the band where it is |
| [ ] 5.1.11b | Turn animations off (Developer options, Animator duration scale off) and repeat 5.1.11 | The slider jumps to the recommendation instead of sliding |
| [ ] 5.1.12 | Load a reference shot far back, so the dots are 1–2 px | A chip **on step 1** says the speckle is below the 3 px minimum and to shoot closer or use a coarser pattern |
| [ ] 5.1.13 | Load a close-up whose dots span more than 9 px | The step 1 chip says the pattern is over-resolved — correlates fine, but a finer pattern would give more points. No span chip appears on step 2: the size verdict suppresses it |
| [ ] 5.1.14 | Load a reference inside the band whose speckle needs a larger subset than the slider is on (e.g. dots ~7 px against a subset of 15) | A chip appears **on step 2, under the subset slider**, naming the subset in use and the one wanted. Raise the slider past it and the chip clears in place, without leaving step 2 |
| [ ] 5.1.15 | Load a reference with no measurable pattern at all (blank card) | No speckle chip, no band and neither warning chip appears; no number is invented |
| [ ] 5.1.16 | Open the sort menu → **Name A–Z** | Thumbnails reorder; the badge numbers renumber 1…N |
| [ ] 5.1.17 | Choose **Date oldest first** | Order follows capture date, not filename |
| [ ] 5.1.18 | Choose **Manual** | Hint toast about dragging; drag a thumbnail and it stays where dropped, back at its normal size, with the badges renumbered |
| [ ] 5.1.19 | Load a single deformed frame | The sort control is hidden |
| [ ] 5.1.20 | Press Back on step 1 with inputs loaded | "Leave setup?" ("Your region and settings will be lost.") with **Stay** and **Leave**. On steps 2 and 3 Back walks back a step instead — the confirm is step 1 only |
| [ ] 5.1.21 | Open step 1 for the first time | Coach marks point at the reference dropzone, then the deformed one |

#### 5.1b Machine load card

Below the deformed frames, on tensile and bending. The log and its chips are the same
throughout; the dimension rows under it follow the test — cross-section for
tensile, **Support span** / **Width** / **Thickness** for bending. Bending also
offers **Type loads (kg)**: the hanger mass of each photo, typed on the phone
(5.1b.32–38); the CSV is then optional.

| # | Action | Expected |
|---|---|---|
| [ ] 5.1b.1 | Start a **Tensile** analysis | A **Machine load** card with an **Import load log** dropzone, a **Cross-section** field (mm²) and an X / Y **Strain axis** toggle |
| [ ] 5.1b.2 | Start a **Bending** analysis | Same card, with a **Type loads (kg)** button above the dropzone (disabled, "Add the deformed photos first", until frames are in), the dropzone hint "Optional: a CSV with the load in newtons", and **Support span**, **Width** and **Thickness** (mm) in place of the cross-section |
| [ ] 5.1b.3 | Load reference + frames, no log | **Next** is disabled with "import the machine load log to continue" (bending: "type the hanger load of each photo (kg) to continue") |
| [ ] 5.1b.4 | Import a comma CSV with a `Load (N)` header and one row per frame | Card shows the filename, "N rows · N · one row per frame", no chip; **Next** now says "enter the specimen cross-section to continue" |
| [ ] 5.1b.5 | Type a cross-section | **Next** enables |
| [ ] 5.1b.6 | Import a semicolon CSV with decimal commas and a `kN` header | Rows read; unit shows **kN**; loads stored in N |
| [ ] 5.1b.7 | Import a log with one more row than frames, first row 0 | Chip: first (unloaded) row taken as the reference |
| [ ] 5.1b.8 | Import a log with many more rows than frames (photos) | Chip: "resampled onto N frames"; ⓘ on the chip offers the FAQ |
| [ ] 5.1b.9 | Same log after video extraction with a time column | Chip: matched by time |
| [ ] 5.1b.10 | Import a log with no header | Chips: column guessed, newtons assumed |
| [ ] 5.1b.11 | Import a file with a text cell in the load column | Dialog "Couldn't read the load log" naming the line and column; card unchanged |
| [ ] 5.1b.12 | Import a file over 8 MB | Dialog: the file is larger than 8 MB |
| [ ] 5.1b.13 | Tensile test, all loads negative | Sign chip; loads keep their sign (the curve sits in the third quadrant) |
| [ ] 5.1b.14 | Change the deformed frames after importing | Meta line and chips update to the new frame count without re-picking the file |
| [ ] 5.1b.15 | Tap ✕ on the log | Dropzone returns; **Next** disabled again |
| [ ] 5.1b.16 | Rotate with a log imported | Card, cross-section and axis survive |
| [ ] 5.1b.17 | Finish the run and reopen from Home | Session carries the loads (PR3 shows them) |
| [ ] 5.1b.18 | Bending, log imported, one dimension left empty | **Next** disabled with "enter the specimen dimensions to continue"; it enables once span, width and thickness are all in |
| [ ] 5.1b.19 | Tap ⓘ on the card, per test | **Machine load** scrolls: a sample CSV header (`Time (s),Load (kN)` tensile, `Time (s),Load (N)` bending) and its rules; a drawing (tensile: the bar, its loading axis, the round cross-section with d and A = π·d²/4, the photo's X and Y; bending: the beam on its supports with L, W, δ, the b × t cross-section and the two beam-height taps); then the formulas (load ÷ A, or σb = M·y ÷ I and E = W·L³ ÷ (48·δ·I)) |
| [ ] 5.1b.19a | Same, in dark theme | The drawing and the sample block stay readable: light ink on the dark dialog |
| [ ] 5.1b.20 | Rotate with dimensions typed | The dimensions survive |
| [ ] 5.1b.21 | Bending, log and dimensions in, no taps | **Next** disabled with "set the beam height on the reference photo to continue"; **Set** opens the tap editor (§6a) |
| [ ] 5.1b.22 | Save taps in the editor | The row reads "N px across · 0.0xxx mm/px"; **Next** enables |
| [ ] 5.1b.23 | Change the reference image | The taps clear; **Next** is disabled again until re-marked |
| [ ] 5.1b.24 | Video + a timed log that starts after the recording (e.g. `scripts/synthetic_beam_video.py`, 2 s late) | Chip "matched by time" and a **Log started after the reference** row (s). Typing 2 re-matches: the first loaded frame is the one filmed after the first weight went on |
| [ ] 5.1b.25 | Video + a timed log with exactly one row per frame | Still **matched by time**, not paired in order; the offset row shows |
| [ ] 5.1b.26 | Video + a timed log whose rows are sparser than the frames (e.g. every 2 s at 1 fps) | Chip "N of M frames have no log row within 100 ms"; those frames are missing from Results, the table and the CSV load column |
| [ ] 5.1b.27 | Set **Log started after the reference** far off (e.g. 60 s on a 29 s clip) | No frame matches: **Next** disabled with "no frame is within 100 ms of a log row" until the gap is corrected |
| [ ] 5.1b.28 | Photos without EXIF capture times (screenshots, or sent through a messenger) + a timed log with one row per photo | Paired in order |
| [ ] 5.1b.29 | Phone photos (reference too) + the machine's timed log | Chip "matched by time" and the **Log started after the reference** row; each photo takes the row logged within 100 ms of when it was taken, counting from the reference photo |
| [ ] 5.1b.30 | Same, then re-sort the frames (Name ↓, or drag) | The loads move with their photos: Results show the same load against the same image |
| [ ] 5.1b.31 | Same photos, but a reference picked from a screenshot (no EXIF time) | Back to paired in order / resampled; no offset row |
| [ ] 5.1b.32 | Bending, reference + 4 photos, tap **Type loads (kg)** | A full-height **Hanger load per photo** sheet: "Reference photo: no weights, 0 kg", then one row per photo (thumbnail, "Photo n", its file name, a kg box). The decimal keypad opens on the first box; the list ends just above the keyboard (no blank gap under the last row), and a swipe scrolls it rather than closing the sheet |
| [ ] 5.1b.33 | Type 0.5, **Next**, 1, **Next**, 1,5 (the keypad's comma), **Next**, leave the last empty, tap **Done** | The comma stays in the box (reopened, it reads 1.5; never 15). The sheet stays open, the cursor on the empty box: "Type the mass in kg: 0 if there is no weight". Type 0, **Done**: the card reads "Typed loads · 4 of 4 photos · kg × 9.80665 = N · tap to edit"; no dropzone |
| [ ] 5.1b.33a | Reopen, clear one box, press back | Kept, but the card warns "1 photo has no load yet" and **Next** is disabled with "1 photo has no load: type it (0 if no weight) to continue" |
| [ ] 5.1b.34 | Reopen by tapping the summary; type `1.2.3` in a box; **Done** | The box shows "Enter the mass in kg, e.g. 0.5" and the sheet stays open. Fix it or press back: back keeps every valid box and the bad box's old value |
| [ ] 5.1b.35 | Compute; open Results and the lab report | Loads 0.50, 1.00, 1.50, 0 kg on the Results graph (4.90, 9.81, 14.71, 0 N in the maths and the CSV). The lab report's table lists the loaded steps only (0.50, 1.00, 1.50 kg): unloaded frames are left out of it by design; its row-1 calculation opens "W = m·g = 0.50 kg × 9.81 m/s² = 4.90 N". Scrubbing the graph shows the point as "(δ mm, kg)" beside the dot, clear of the scrub line; near the right edge it flips to the line's left |
| [ ] 5.1b.36 | Re-sort the frames (Name ↓, or drag) after typing | Each mass stays with its photo: reopen the sheet and check |
| [ ] 5.1b.37 | Kill the process on step 1 with loads typed (`adb shell am kill com.sempermechanics.semperclassroom` while backgrounded), return | The typed loads come back |
| [ ] 5.1b.38 | Tap ✕ on the typed loads; import a CSV; tap ✕ on it | ✕ clears the typed loads and brings back the button and dropzone; with the CSV in, the typing sheet is never offered (no button, the summary does not open it); its ✕ brings the button back. Tensile never shows the button |
| [ ] 5.1b.39 | With 0.5, 1, 1.5, 0 typed, pick **Incremental (added per photo)** under **Loads typed as** | The boxes become 0.5, 0.5, 0.5, -1.5, each with "Total n kg" under it (0.5, 1, 1.5, 0); the keypad now has a minus key and the subtitle explains increments. Change the last to -2, **Done**: refused, "This takes the hanger below 0 kg". Back to **Absolute**: the totals return unchanged; 1,5 typed with the keypad comma still reads 1.5. **Done** in Incremental, reopen: still Incremental; the loads are the same 4.90, 9.81, 14.71, 0 N |
| [x] 5.1b.40 | Open a finished **Bending** analysis with the load point tapped; look at Results on the summary page and in Details | **Adjust deflection** beside the RESULTS title on the summary, and under the Results text in Details. Not on tensile, nor on bending without the tap. The wizard has no scale or bias boxes |
| [ ] 5.1b.41 | Tap it; type scale 1.05 and bias -0.12; **Apply** | Both Results redraw at once, with no progress count: "Deflection corrected: δ = 1.050 × δ measured − 0.120 mm", and every δ in the graph, caption and table is 1.05 × δ − 0.12. E from the graph drops by 1/1.05 and does not move with the bias; the average E moves with both. Share the lab report and CSV: the report lists the correction under Observations, and the CSV's `# mechanical_results` block leads with `deflection_correction_scale` / `_bias_mm` |
| [x] 5.1b.42 | Type scale 0, **Apply**; then a lone `-` as the bias (the number keypad blocks letters and a second point) | The dialog stays open with "Enter a number above 0, e.g. 1.05" (or "Enter a number in mm, e.g. -0.12") under the box; nothing changes. Blank boxes mean 1 and 0 |
| [ ] 5.1b.43 | Back out to Home, reopen the analysis; then rotate; then kill it in the background (`adb shell am kill com.sempermechanics.semperclassroom`) and return | The correction is still applied each time, and the dialog opens with 1.05 and -0.12. **Reset** puts every number back to the camera's δ and saves that |

**5.1b.32–38 on the Pixel 6 (`f4209a7f`, 2026-09-28)** pass on an offline lab
build with `semper_test_data/2_bending_pmma_real` (pmma_00 as the reference,
pmma_01–04 as the photos). The keypad's comma stays ("1,5" reopens as 1.5), and
the sheet leaves no gap above the keyboard. Results show 4.90, 9.81, 14.71 and
0 N on all 4 of 4 frames. Mid-curve the scrub label reads "(0.035 mm, 11.2 N)";
at the right edge it flips to the left of the line. After Name ↓ and after
`am kill` each mass stays with its photo. For tensile (`1_tensile_steel_real`,
3 frames) the card offers only **Import load log**.

The 5.1b.35 lab report was checked on an API 36 emulator instead (`91640d47`,
debug build, so sign-in is bypassed and Share is on), with the same photos,
loads, dimensions and ROI. Its table lists 4.90, 9.81 and 14.71 N, with
σb 0.048, 0.096 and 0.144 MPa. The 0 kg photo is not a row, because
`BeamDeflection.loadSteps` drops unloaded frames. The graph page shows
slope 148.20 N/mm and E ≈ 0.04 GPa, the same as the viewer.

**5.1b.39 on the Pixel 6 (`7f32583a`, 2026-09-29)** passes on a debug build of
#93 with the same PMMA photos (pmma_00 as the reference, pmma_01–04 as the
photos). In **Absolute**, Photo 3 was retyped as "1,5" with the keypad's comma.
Switching to **Incremental** turned 0.5, 1, 1,5, 0 into 0.5, 0.5, 0.5, -1.5, with
"Total 0.5 kg", "Total 1 kg", "Total 1.5 kg" and "Total 0 kg" under them, and
the subtitle changed to increments. The keypad had a minus key and kept the
comma. With the last box at -2, **Done** stayed open on "This takes the hanger
below 0 kg". Set back to -1.5, **Absolute** read 0.5, 1, 1.5, 0 again. After
**Done** in Incremental, reopening from the card showed Incremental; so did the
sheet after the app restarted overnight. Computed with L 75, b 12, t 31 mm,
edge taps at 39 and 612 px (573 px, 0.0541 mm/px) and ROI 2180 × 520 at
(100, 60): Results show 4 of 4 photos (slope 148.96 N/mm), and the session
stores 4.903, 9.807, 14.710 and 0 N (`loadsN`, read with `run-as`, since the
scrub label reads the line under the finger, not the dot). One gap: after a
switch the cursor goes to the first box, but the keyboard stays down until a
box is tapped (TD-143).

**5.1b.40–43 on the Pixel 6 (2026-09-30, the tree merged as #108 and #107)**, with
`semper_test_data/2_bending_pmma_real` and its CSV loads (taps 573 px,
0.0541 mm/px; ROI 2210 × 470 at (80, 90); subset 25), pass apart from the parts
noted below. Uncorrected: E from the graph 2.01 GPa (695.00 kg/mm, R² 0.9993),
average 2.12 GPa over 31 steps, as in the set's README. With 1.05 / −0.12: slope
661.90 kg/mm (exactly ÷ 1.05), E from the graph 1.92 GPa, average 2.58 GPa over 29
steps. The bias takes the two smallest δ under a pixel, so those steps drop out.
The session index stores the scale and bias. They survive a background kill and
a reopen from Home, and **Reset** brings back 2.01 / 695.00 / 2.12. Loads read in
kg in the axis, slope, Details load row and caption ("Frame 1 · 3.59 kg · …").

- The graph's unloaded reference was drawn at (0, 0), not at the bias, so a
  bias left a stray segment. Fixed and rechecked on the phone. The lab report's
  graph had the same fault, and it is fixed there too.
- Not checked on the phone, so 5.1b.41 and 5.1b.43 stay unticked: rotation
  (forcing it needs a system setting), and the lab report / CSV from Share,
  which the signed-out build greys out (`LicenseEntitlements.shareEnabled`). Unit tests cover the report's correction
  line, kg table and graph origin, and the CSV's correction rows.
- 5.1b.42: the keypad cannot type `1.2.3` or `abc`. A lone `-` as the bias gives
  the bias error, alongside scale 0's.

#### 5.1a Video source

Reached whenever the file picked — from the grid or through Files — is a video.

| # | Action | Expected |
|---|---|---|
| [ ] 5.1a.1 | Pick a video (a badged tile in the grid, or via Files) | Sampling sheet opens with resolution, source fps and duration |
| [ ] 5.1a.1a | Pick a long video from Home **+** | While its metadata is read (past ~0.3 s) the wizard's reference slot shows a small spinner and "Reading video…"; it goes when the sampling sheet opens or the read fails |
| [ ] 5.1a.2 | Switch the mode between **Keyframes** and **Interval** | Keyframes hides the fps slider and the estimate line; Interval shows both, and dragging the slider updates the estimated frame count live. The ⓘ beside the toggle explains both modes (sync I-frames; sampling at the set rate) and that the first frame becomes the reference |
| [ ] 5.1a.2a | Extract in **Interval** over the whole clip, then scrub the deformed frames | Consecutive frames differ — not runs of repeats of the same I-frame |
| [ ] 5.1a.3 | Drag the time-segment handles | Estimate updates; the button relabels to "Extract N frames" |
| [ ] 5.1a.4 | Choose settings that exceed *Max frames* | The estimate shows the cap being applied |
| [ ] 5.1a.5 | Tap **Extract** | Progress overlay; frame 0 becomes the reference, the rest deformed |
| [ ] 5.1a.6 | Look for the sort control afterwards | Hidden — video frames are already in time order |
| [ ] 5.1a.7 | Pick an `.avi` from a lab or UTM camera (uncompressed or motion-JPEG) | Sampling sheet opens with its resolution, rate and duration; **Extract** writes the same lossless grayscale frames an MP4 does |
| [ ] 5.1a.8 | Pick an `.avi` whose codec this device has no decoder for (Xvid on a device without MPEG-4 ASP) | Snackbar naming the four-letter codec and what to do instead, with **Why?** → video-read FAQ |
| [ ] 5.1a.9 | Pick an `.avi` that is truncated or not a video at all | The ordinary "could not read this video" snackbar — no crash |
| [ ] 5.1a.10 | **Interval**, whole clip, the source's own rate, then **Extract** | The deformed count is exactly the sheet's estimate minus the reference — a 20-frame clip gives 1 + 19, not a promised 21 |
| [ ] 5.1a.11 | Read the codec snackbar from 5.1a.8 | The whole message shows — both remedies, not cut after two lines — and it stays up long enough to read (about 9 s) |
| [ ] 5.1a.12 | Extract from `tensile_03.mp4`, then run | The reference card reads "tensile_03" and the analysis is named "tensile_03" ("tensile_03 (2)" for a second); a clip whose name the picker cannot give is "Video" |
| [ ] 5.1a.13 | Extract, then kill the app on step 2 (Developer options, or `am kill`) and reopen | The wizard comes back with the frames, and after the run the row and the viewer still show the frames' clip times (3.4g, 8.2.1c) |

`VideoFrameExtractionDeviceTest` (instrumented) covers the extraction itself on an
emulator: it encodes MP4 and AVI (Y800, MJPG, H.264) clips whose frames are stamped
with their own index, and checks keyframe and fixed-interval picks, portrait
rotation, and that every PNG came from the lossless Y-plane path rather than the
retriever fallback. Keyframes mode is checked on every rung: the hardware decoder,
the AVI demuxer (an indexed H.264 AVI gives its I-frames; an all-intra or
index-less AVI falls back to the interval), and the retriever, forced; every
rung's frames carry their own clip times (the retriever's from a sample walk, TD-194,
also checked at a fixed 7 fps). Rows 5.1a.1–11 remain for the
sheet itself and for real vendor decoders and camera AVIs.
| [ ] 5.1a.14 | Semper Classroom: extract with **Keyframes** for a tensile or bending run, then add its timed load log | Each frame's load is matched by its own clip time less the reference's; a frame inside the log's start lag gets none |

The fork's own sheet (Frame rate / Key frames, an exact key-frame count) passed
5.1a.1–5.1a.10 and its key-frame load match on the Pixel 6 on 2026-09-26 with
`scripts/synthetic_beam_video.py`'s clips; since the 2026-10-14 sync the sheet is
Semper's, and those rows are owed again on it.

### 5.2 Step 2 — Confirm settings

| # | Action | Expected |
|---|---|---|
| [ ] 5.2.1 | Arrive on step 2 | Toolbar reads "Parameters" with two dots, the second blue. Single / Sweep is at the top; the page is one flat list split by dividers, no cards |
| [ ] 5.2.2 | Read the ROI row before editing | A thumbnail of the reference shaded green, and "Full image · N points": the points the engine solves at this step, over the frame inset by half a subset plus 10 px a side |
| [ ] 5.2.3 | Tap **Edit** → draw an ROI → save | The thumbnail shows the region (holes in red) and the line becomes "w × h px · N points" |
| [ ] 5.2.4 | Tap **Edit** → cancel | Falls back to full image; any mask is cleared |
| [ ] 5.2.5 | Switch to **Parameter sweep** | The correlation section hides; the frame field (multi-frame only) and the **Planned lattice** appear, no range controls. Toolbar reads "Sweep setup" with three dots. Bottom nav reads **Next**. The ROI row drops its point count ("Full image", "w × h px"): each combination has its own step; **Single** brings the count back |
| [ ] 5.2.5a | Run a single analysis ("✅ Computed N frames" under the page), then switch to **Parameter sweep** | The status line clears; switching back to **Single** does not bring it back |
| [ ] 5.2.6 | Type **step size** as subset ÷ N on step 3 | N is 2–9 (default 3); the overlap under **Advanced** is `1 − 1/N`; each subset uses `step = round(subset / N)` |
| [ ] 5.2.7 | Tap the ⓘ next to the mode toggle | Explains single setting vs sweep |
| [ ] 5.2.8 | Switch back to **Single** | The correlation section returns with its previous values |
| [ ] 5.2.9 | Drag the **subset size** slider | Only odd values between 15 and 121; the field mirrors it; overlap updates from the current step |
| [ ] 5.2.10 | Type an even subset size and press Done | Snapped to the nearest valid odd value |
| [ ] 5.2.11 | Type nonsense in a parameter field | Reverts to the previous value on commit |
| [ ] 5.2.12 | Drag **step** | Max is `min(30, subset/2)` so overlap stays ≥ 0.5; the overlap field under **Advanced** mirrors it; the ROI row's count follows, N points = (ROI w ÷ step) × (ROI h ÷ step) |
| [ ] 5.2.12a | Open **Advanced** and type **overlap** | 0.50–0.99; step rewrites to `round(subset × (1 − overlap))` |
| [ ] 5.2.12b | Read **Compute** before any run on this phone | "Compute", no time |
| [ ] 5.2.12c | Run once, come back to step 2 | The button reads "Compute · about S s" (minutes past 90 s), from the points per second this phone's runs reached, frames × points; the text shrinks to stay on one line |
| [ ] 5.2.13 | Drag **strain window** | Odd values 3–31, in **points**; the field mirrors it and the muted text beside it on the header row reads "N px VSG", N = (window − 1) × step + 1. It starts at 5 |
| [ ] 5.2.13a | Open step 2 having never copied params from a lattice | No paste icon beside **Reset** — it only appears when the clipboard holds a set |
| [ ] 5.2.13b | Copy params from a sweep lattice (§7.3), then return here | The paste icon appears beside **Reset**; tapping it fills subset, step and strain window (overlap follows step) and scrolls them into view. The window is the one whose VSG at the pasted step matches the node's. When the paste changes the overlap, a closed **Advanced** opens to show it; a paste that leaves it as it was leaves Advanced closed |
| [ ] 5.2.13c | Change the **step** with the window fixed | The VSG beside the window follows: window 5 reads "21 px VSG" at step 5, "41 px VSG" at step 10 |
| [ ] 5.2.13d | Read the page under the strain window | **Advanced** with a chevron, closed. A tap opens it (the chevron turns up) on **Overlap** with its ⓘ and **Interpolation**; another tap closes it |
| [ ] 5.2.14 | Tap each ⓘ | Subset (including what the shaded band means), step, overlap (under **Advanced**) and strain window each explain themselves |
| [ ] 5.2.15 | Open **Advanced** and pick **6×6 Keys** in the **Interpolation** dropdown | Selection sticks; the run uses it |
| [ ] 5.2.16 | Change several parameters, then tap **Reset** (the circular-arrow icon) | Subset returns to the recommended value, step to 5, overlap follows step, strain window to 5 points, interpolation to 4×4 bicubic |
| [ ] 5.2.16a | After a failed run leaves an ❌ line on step 2, change subset / paste params / replace frames | The run-status line clears; the frame-size chip (if any) only shows when sizes still mismatch |
| [ ] 5.2.17 | Load a well-speckled reference and watch the subset | It is pre-seeded from the SSSIG recommendation — until you touch it |
| [ ] 5.2.18 | Draw an ROI smaller than the subset and tap **Compute** | "ROI too small" snackbar with a **Why?** action; that asks first whether to leave the app, then opens the ROI FAQ. The run does not start |
| [ ] 5.2.19 | Edit a parameter field and tap **Compute** without pressing Done | The typed value is committed and used |
| [ ] 5.2.20 | Open step 2 for the first time | Coach marks point at the analysis-mode toggle, the ROI row, then the Correlation section (Single) or the planned lattice (Sweep) |
| [ ] 5.2.21 | Tap the frame field (sweep, multi-frame) | It has no box label; it shows the frame's name with "n / N" at its end. The frame picker opens on the picked frame: a radio list, a frame-number field and a live preview. **OK** updates the name, "n / N" and the thumbnail; **Cancel** changes nothing. With one deformed frame the row is hidden |
| [ ] 5.2.21b | Tap the ⓘ after the frame field | A dialog titled "Frame to sweep" says to pick the frame with the most deformation, since the sweep solves only that one; no sentence sits under the field |
| [ ] 5.2.21a | Tap the frame thumbnail, then anywhere | The frame shows large over the dimmed page with "name · Frame n of N" and "Tap to close" under it; a tap anywhere or Back closes it |
| [ ] 5.2.23 | Drag the subset range handles (step 3) | Both ends stay odd; min never crosses max |
| [ ] 5.2.24 | Type a subset min above the max | Clamped so min ≤ max |
| [ ] 5.2.25 | Drag the strain window range (step 3) | Two handles like the subset's, in points (3–31, default 3–11); the min and max boxes track it, and the muted text at the end of the **Strain window** title row reads "a–b px VSG" for the plan's smallest and largest VSG |

### 5.3 Step 3 — Sweep settings `[sweep]`

| # | Action | Expected |
|---|---|---|
| [ ] 5.3.1 | Arrive on step 3 | Toolbar reads "Sweep settings" with the third of three dots blue; **Subset** and **Strain window** come first, then the step, a closed **Advanced**, then **Line cut axis**; no lattice on this page |
| [ ] 5.3.6 | Tap each ⓘ | The ranges, step, overlap (open **Advanced** first) and line-cut axis each explain themselves |
| [ ] 5.3.6b | Tap **Advanced** on step 3, then tap it again | It opens on the **Overlap** field with its ⓘ, the chevron turned up, and closes again; the parameters page's own Advanced is unaffected |
| [ ] 5.3.6a | Open step 3 for the first time | Three coach marks in order: the subset range, the line cut axis, then the run button |
| [ ] 5.3.6f | Toggle the line-cut axis **X** ↔ **Y** (TalkBack reads "Along X" / "Along Y"), then tap the strip twice | The strip redraws the cut line through the ROI centre; a tap opens it in the reference's aspect, 240dp tall at least and at most 60% of the screen. A reference too wide for that scrolls sideways, starting centred. A second tap closes it |
| [ ] 5.3.11 | Look at the planned lattice on step 2 | Grid of nodes, subset across, VSG up; taps do nothing (it's a preview) |
| [ ] 5.3.12 | On step 2 tap the gear on **Planned lattice** and set 4 × 4 | The sample counts show under the heading (hidden until the gear); the heading's count reads "16 runs" and the lattice redraws; on step 3 the run button reads "Run 16" |
| [ ] 5.3.12a | Read the run button before and after a run on this phone | "Run N", then "Run N · S s" once a run has finished (minutes past 90 s); TalkBack reads "Run N analyses, about S seconds". It follows the ranges as they change |
| [ ] 5.3.13 | Set samples to 9 | Clamped to 8 |
| [ ] 5.3.14 | Set the subset min above what the ROI can hold | Warning chip under the lattice on step 2: "Subset range starts above what this image and ROI can hold"; info icon opens the sweep-subset FAQ behind the leave-the-app confirm. The run button is disabled |
| [ ] 5.3.15 | Set a strain window range that no subset can satisfy | Warning chip under the lattice on step 2: "No combination fits this ceiling — raise Max strain window or lower the subset range"; info icon opens the empty-plan FAQ behind the same confirm. The run button is disabled |
| [ ] 5.3.16 | Read the count beside **Planned lattice**, then start the sweep | "N runs" ("1 run" for one combination); no summary line sits under the lattice. The run overlay opens on "N analyses · subset a–b px · window c–d points"; a one-combination plan reads "1 analysis" with its real subset and window, not "1–1" |

### 5.4 Running

The run sheet docks at the bottom over a light scrim (the wizard stays visible
above it and takes no touches), its bottom padding clear of the gesture bar. The
same sheet is reused for importing frames and extracting video, but the
convergence bins are **hidden** there — nothing is being solved. Import and
extraction show a determinate progress bar, their status line and time left instead.

| # | Action | Expected |
|---|---|---|
| [ ] 5.4.1 | Start a run | A sheet docked at the bottom, full width, rounded on top only, with the wizard visible through a light scrim; Cancel a red text button at its end, clear of the gesture bar. Header row: "Frame N of M" with the percentage at its end (one decimal); under it the reference's name without its extension, then "Estimating time left…" and later "About N s left" ("steel_00 · About 2 s left"); no title, ring, pace, elapsed or "Keep Semper open" line |
| [ ] 5.4.2 | Watch the bins during a solve | One bin per planned frame on an axis labelled 100 and 50: finished frames blue at their convergence height (red under the dashed 50% line), the frame being solved a pale outlined bin rising with its own progress, the rest empty slots; "Convergence per frame" under them. Up to 12 frames, each finished bin carries its whole-number convergence above it (red under 50) and the running bin its progress in blue; with few frames the bins stay narrow and centred. After the last frame the header reads "Completed M of M frames · saving" |
| [ ] 5.4.2e | Let a run solve every frame | For a moment the sheet shows a green check, "M frames solved" and "S s · lowest P% converged", then the viewer opens and the wizard closes: **Back** from the viewer lands on Home. A run that stops early, fails or is cancelled shows no such moment |
| [ ] 5.4.2d | Run more than 100 frames | Neighbouring frames share a bin that shows the lowest of them; a low frame still shows red |
| [ ] 5.4.2c | Run a pair that decorrelates (blurred or swapped frames) | After the first frame under 50% an amber line names it and warns one more stops the run; the second stops it as before |
| [ ] 5.4.2a | Watch the sheet while frames import or a video extracts | The bins are absent; the header is "Importing photos" or "Extracting frames" with its percentage, then time left, a determinate bar, and the count of frames under it ("Reading video…" before a video's first frame) |
| [ ] 5.4.2b | Tap **Cancel** during an import | A confirm dialog ("Cancel import?", with **Keep importing** and **Cancel**); confirming leaves no half-imported frames behind |
| [ ] 5.4.3 | Leave the device untouched during a long run | The screen does not sleep |
| [ ] 5.4.4 | Press Back mid-run | Asks first, as **Cancel** does ("Cancel analysis?"); **Keep running** carries on |
| [ ] 5.4.5 | Tap **Cancel** → "Keep running" ("Cancel analysis?", "Nothing from this run is kept.") | The run continues |
| [ ] 5.4.6 | Tap **Cancel** → confirm on a new analysis | Stops within a moment — not at the end of the frame — and returns to step 2, silently. Nothing from the run is kept: no new row on Home, no backup queued, and the analysis count is unchanged |
| [ ] 5.4.6a | Cancel a long frame (big ROI, small step) | Same: no multi-second wait on the progress overlay after confirming |
| [ ] 5.4.6b | Start a new run straight after cancelling one | It runs normally — the cancel does not carry over |
| [ ] 5.4.6c | Cancel a sweep at combination 3 of 16 | The **whole sweep** stops — it does not go on to combination 4 — and nothing is saved, not even the combinations it solved |
| [ ] 5.4.6d | Re-run a saved analysis from Home (3.11a), change a setting, **Compute**, then cancel it on frame 2 | The question reads only "Nothing from this run is kept."; after confirming there is no new row, and the first analysis's row, frames, thumbnail and backup state are exactly as they were |
| [ ] 5.4.7 | Start a sweep | Header row "Analysis i of N" with the percentage; the planned lattice replaces the bins, all grey rings with the combination being solved circled, its labels horizontal in the app's font: the lowest and highest VSG at the left, the smallest and largest subset under the axis with "subset px" between, no legend line; the status under it reads "Subset S · window V px" |
| [ ] 5.4.7b | Watch the line under the sweep's status | Hidden until a combination ends, then "Last: P% converged · N points" (N grouped, "30,968"), or "Last: skipped" in red |
| [ ] 5.4.7a | Watch the sweep lattice | Each combination fills as it solves or becomes a red ring if skipped; percentage and time left advance per combination; at the end the header reads "Solved K of N analyses · saving" |
| [ ] 5.4.8 | Background the app mid-run | The run does not survive process death — no resume is offered |

### 5.5 Terminal states

| # | Action | Expected |
|---|---|---|
| [ ] 5.5.1 | Run on a featureless image pair | Engine failure dialog naming the feature-detection cause, **and the frame and image it failed on**, with a **Why?** that opens the features FAQ behind the leave-the-app confirm |
| [ ] 5.5.1a | Run a batch where a later frame decorrelates | "Stopped at frame N" — not "Analysis failed" — then "22 of 40 frames kept." and the reason in one sentence; **Why?** adds the frame's image and the decorrelation explanation and leaves the dialog up |
| [ ] 5.5.1c | Tap **View results** | The kept frames open in the viewer — the run does not leave you back on the settings page. **Back** from the viewer lands on Home |
| [ ] 5.5.1d | Press Back on that dialog | Nothing dismisses it; the only way on is through to the results |
| [ ] 5.5.1e | Return to Home afterwards | The short analysis is listed with the frames it kept — not a phantom row from a run reported as failed |
| [ ] 5.5.1n | First run of a batch whose frame 1 keeps no points, then later frames solve and one fails or decorrelates | The failure dialog explaining frame 1 (strain window, nothing correlated, or unreadable) — **not** "Stopped at frame N … frames kept": a run is saved only when frame 1 solves, and no Home row appears |
| [ ] 5.5.1f | Read that Home row | "39 of 50 frames" and the reason, not a bare "39 frames" |
| [ ] 5.5.1g | Open it and tap ⓘ | Settings used lists **Stopped early** and **Frames solved** |
| [ ] 5.5.1h | Force-stop the app, reopen, look again | Both still say why — the reason is stored, not held in memory |
| [ ] 5.5.1i | Run with a strain window the correlated area cannot support (0 points solved) | Engine-failure dialog naming VSG failure — not the generic "No data produced" copy — with **Why?** → VSG FAQ; after OK, ⓘ beside the error line opens the same FAQ confirm |
| [ ] 5.5.1j | Trigger an OOM (huge ROI, step 1) | "Analysis stopped unexpectedly" dialog naming the error and what causes it |
| [ ] 5.5.1k | Fail to decode a reference (corrupt / unsupported) | Snackbar with **Why?** → import-reference FAQ |
| [ ] 5.5.1l | Fail to import deformed frames | Snackbar with **Why?** → import-deformed FAQ |
| [ ] 5.5.1m | Pick a video the app cannot read | Snackbar with **Why?** → video-read FAQ |
| [ ] 5.5.1b | Sweep a decorrelated pair | Stops after two combinations under 50% rather than sweeping the rest |
| [ ] 5.5.2 | Run with an unusable ROI | Engine failure dialog naming the ROI cause |
| [ ] 5.5.3 | Finish a single-setting run | Result viewer opens on frame 1. **Back** goes to Home, not into the wizard, and **+** then starts an empty wizard (no reference, frames or settings carried over) |
| [ ] 5.5.4 | Finish a sweep with some combinations failing | "N of M skipped" toast, then the Lattice |
| [ ] 5.5.4a | Tap a hollow node | A dialog titled with that combination (S · St · W · VSG) and a one-line reason — decorrelated, subset too large, VSG failure — plus **Why?** to the matching engine FAQ |
| [ ] 5.5.4f | Reopen that sweep from Home, tap a hollow node | The same specific reason, not the generic "was skipped" — codes are stored on the record |
| [ ] 5.5.4g | Restore that sweep from the cloud, tap a hollow node | Same again; the reasons survive the round-trip |
| [ ] 5.5.4c | Tap a hollow node from a sweep with no recorded code | Still explains itself rather than doing nothing |
| [ ] 5.5.4d | Open the lattice for the first time | Coach marks point out the graph, then the strain plot's drag readout |
| [ ] 5.5.4e | Drag across the strain plot | Every curve's value at that position, each in its own curve's colour |
| [ ] 5.5.4b | Run a sweep where **every** combination fails | The Lattice opens — not the parameter screen — all nodes hollow, summary says all failed, tapping the summary opens the VSG FAQ confirm, and **View** and **Save graph** are both disabled |
| [ ] 5.5.5 | Finish a sweep cleanly | Lattice opens with every node filled; **Back** from the lattice goes to Home |
| [ ] 5.5.6 | Start a new run at the quota | Session limit screen, before any solving. A run already under way when the quota fills still saves (§9, 9.7) |
| [ ] 5.5.7 | Try other settings on a finished analysis | There is no way back into its wizard: its results' Back goes Home. **Re-run** it from Home (3.11a); the run saves a new row and never changes the first, for a sweep too (owner decision, 2026-10-15) |
| [ ] 5.5.7a | Fail a run (an engine failure dialog), change a setting and **Compute** again | The wizard stays after a failure, and the second run is checked and saved like any new analysis; nothing of the failed run is listed |
| [ ] 5.5.8 | Re-run an analysis twice from Home | Two new rows ("(2)", "(3)"); the first stays as it was |
| [ ] 5.5.8a | Re-run with the quota full, or Compute in a wizard whose run would fill it | The session limit screen: every run makes a new Home row, so every run is checked |

---

## 6. ROI editor

Full-screen editor over the reference image. Two edit modes crossed with two
tools — the Crop/Erase toggle persists when you switch between Draw and Manual.

**Entry:** the ROI card on analysis step 2. **Exit:** back to step 2, with the
ROI and mask, or with full-image defaults on cancel.

| # | Action | Expected |
|---|---|---|
| [ ] 6.1 | Open the editor | Reference image fills the canvas; HUD names the current mode |
| [ ] 6.2 | Drag a rectangle in **Draw** + **Crop** | Green ROI appears; HUD reports `W × H at (x, y)` live |
| [ ] 6.3 | Drag inside the rectangle | It moves as a whole, clamped to the image |
| [ ] 6.4 | Drag a corner handle | It resizes; the handle is forgiving to grab |
| [ ] 6.5 | Switch to **Square** and draw | Width and height stay equal |
| [ ] 6.6 | Make a very small drag | Ignored — a minimum size is enforced |
| [ ] 6.7 | Switch to **Erase** and drag inside the ROI | A hole is punched out of the correlated area |
| [ ] 6.8 | Add a second hole | Both are kept |
| [ ] 6.9 | Drag an existing hole in Erase mode | It moves and resizes independently |
| [ ] 6.10 | Erase without ever drawing a crop | Treated as "full image minus the holes" |
| [ ] 6.11 | Switch to **Manual** | X / Y / W / H fields prefill from the current selection |
| [ ] 6.12 | Type values and tap **Apply** | The ROI jumps to exactly those coordinates |
| [ ] 6.13 | Apply a zero or negative size | Rejected with an "invalid size" toast |
| [ ] 6.14 | Switch Manual → Draw → Manual | Crop/Erase state survives; the image does not jump |
| [ ] 6.15 | Tap **Reset** | The canvas clears back to nothing selected |
| [ ] 6.16 | Tap **Use full image** | Saves immediately and returns; step 2 reads "Full image" |
| [ ] 6.17 | Tap **Save ROI** | Returns; step 2 reads the custom size and origin |
| [ ] 6.18 | Tap **Cancel** | Returns with the ROI reset to full image |
| [ ] 6.19 | Rotate the device mid-edit | The ROI, holes and both toggles survive |
| [ ] 6.20 | Save an ROI with holes, then run | The masked regions are absent from the result heatmap |
| [ ] 6.21 | Pinch the photo with an ROI drawn | Zooms about the fingers (up to 10×); the ROI stays on the same specimen pixels and the HUD shows the zoom |
| [ ] 6.22 | Move two fingers together while zoomed | Pans; the photo never leaves the screen |
| [ ] 6.23 | Double-tap, then double-tap again | 2× about the tap, then back to fit; the ROI is untouched |
| [ ] 6.24 | Zoomed in, draw or resize with one finger, then Save | Edges land finer than at fit; X / Y / W / H match what was drawn |
| [ ] 6.25 | Tap once outside the ROI, or start a pinch with one finger outside it | The ROI and holes stay; only a kept drag replaces them |

---

### 6a. Beam height taps (bending)

`BeamEdgeTapActivity`, opened from **Beam height → Set**. Cancel / **Beam
height** / Save across the top, the reference photo, the scale readout
over it, the instruction and **Reset** below.

| # | Action | Expected |
|---|---|---|
| [ ] 6a.0 | Tap **Set** with the thickness still empty | The editor does not open; the message "Enter the beam’s thickness first." shows in full, not cut off |
| [ ] 6a.1 | Open it with the thickness typed | Readout "Thickness t mm — tap both edges"; the instruction asks for the top edge and says pinch or double-tap zooms, drag pans; Save disabled (grey, not sky blue) |
| [ ] 6a.2 | Double-tap the beam, then pinch | Zooms in about the finger; drag pans; double-tap again returns to fit |
| [ ] 6a.3 | While zoomed, tap the top edge, then tap below it and a little (under 3°) to one side | Each mark gets crosshair lines across the whole photo, with a ring on the mark; the second mark lands **on the first mark's vertical line**, at the height tapped, so thickness is measured straight down; a dashed probe circle sits between them; **the zoom does not change between taps**; readout "t mm over N px · mm/px" |
| [ ] 6a.3b | Reset; tap the top edge, then the bottom edge well to one side (a beam tilted in the photo) | The second mark stays where tapped; both marks' crosshair lines turn with the tap line (one along it, one square to it); the readout adds "Tilted N° in the photo: …" |
| [ ] 6a.3a | Watch the photo through all three instructions (top, bottom, done), unzoomed and at a large font size | The photo does not move or resize: the instruction's box is as tall as its longest step |
| [ ] 6a.4 | Tap nearer either mark along the tap line | That mark moves. The top follows the tap in both directions and carries the bottom sideways by as much (a vertical pair stays vertical); the bottom follows the tap, snapping back onto the vertical line within 3° of it |
| [ ] 6a.5 | Place the marks under 40 px apart | Readout adds the precision warning (≈ 100/N % per pixel of slip) |
| [ ] 6a.6 | Marks under 3 px apart | Save disabled; readout "The marks are on top of each other — tap the other edge." |
| [ ] 6a.7 | Rotate with marks placed | Marks survive |
| [ ] 6a.8 | Save; reopen Mark | The saved marks are shown |

## 7. Parameter sweep lattice `[sweep]`

The parameter-space map for a sweep, titled **Parameter sweep** on screen. It
sits **in front of** the result viewer — a sweep opens here, and Back from the
viewer returns here.

The screen is designed to be driven with one thumb: the column **scrolls**
(lattice, then controls, then plot) while the two action buttons stay pinned at
the bottom, and every node can be reached with the prev/next stepper without
aiming at a small target.

**Entry:** finishing a sweep, or tapping a sweep row on Home or in Settings.
**Exit:** Result viewer, or back to Home.

### 7.1 Lattice and stepper

| # | Action | Expected |
|---|---|---|
| [ ] 7.1.1 | Open a sweep | Lattice with **subset across and VSG (px) up**: one window in points is a larger VSG at a larger subset's step, so the rows need not line up; the first-run tip explains filled vs hollow (no persistent legend row) |
| [ ] 7.1.1a | Look for axis titles on the result lattice | There are none — it draws compact, so only tick numbers and a "px" unit on the last subset tick. The first-run tip names them: "subset size across, VSG up" |
| [ ] 7.1.2 | Open a sweep that had failures | Skipped combinations are hollow rings with no fill — any surface behind them shows through |
| [ ] 7.1.3 | Compare two different filled nodes | Same fill colour — node identity comes from position + the selection ring, not a colour key. The plot below only puts colour on the *focused* curve (§7.2.4) |
| [ ] 7.1.4 | Read the summary line, above the lattice | "N combinations · S solved · K skipped · step subset÷D" |
| [ ] 7.1.4a | Open a sweep where every combination failed | The line is replaced by "All combinations failed — tap a node for details." |
| [ ] 7.1.4b | Open one where the step denominator cannot be derived | The "step subset÷D" tail is dropped rather than printing a wrong number |
| [ ] 7.1.5 | Tap a solved node | A selection ring appears; the stepper chip and the plot follow it |
| [ ] 7.1.6 | Tap a skipped node | A dialog explains why it was skipped; nothing is focused |
| [ ] 7.1.7 | Double-tap a solved node | The result viewer opens on that combination |
| [ ] 7.1.8 | Long-press a solved node | Same as double-tap |
| [ ] 7.1.9 | Tap **›** repeatedly | Focus walks the solved nodes in order; the chip names each one |
| [ ] 7.1.10 | Reach the first or last solved node | **‹** or **›** disables rather than wrapping |
| [ ] 7.1.11 | Open a sweep with no solved nodes at all | The stepper row is hidden and both bottom buttons are disabled |
| [ ] 7.1.12 | Scroll the screen down | Summary, lattice, stepper and plot scroll together; the **Save graph · View** bar stays pinned |

### 7.2 Strain plot

| # | Action | Expected |
|---|---|---|
| [ ] 7.2.1 | Read the plot title | It names the cut axis — "Strain along X axis" or "…Y axis" |
| [ ] 7.2.2 | Change the strain component spinner | The plot redraws for Exx / Eyy / Exy |
| [ ] 7.2.3 | Look at the **All / Node** pill on open | It is one physical pill, and **All** is selected — a sweep opens on every curve, not on one |
| [ ] 7.2.4 | Read the plot in **All** | Every solved combination is drawn: the focused curve at full strength in its own colour, every other curve sharing one muted neutral (not each its own dimmed colour) — only one hue ever carries meaning at a time |
| [ ] 7.2.4a | Switch to **Node** | Only the focused combination is drawn |
| [ ] 7.2.5 | Pinch to zoom on the plot | It zooms about the pinch centre |
| [ ] 7.2.6 | Drag with two fingers | The zoomed plot pans |
| [ ] 7.2.7 | Double-tap the plot | The viewport resets to fit |
| [ ] 7.2.8 | Zoom in, then step to another node | The zoom **survives** — the viewport is kept while the component is unchanged |
| [ ] 7.2.9 | Zoom in, then change the strain component | The viewport **resets**, because Exx/Eyy/Exy differ in magnitude and a stale zoom would clip |
| [ ] 7.2.10 | Drag one finger across the plot | A vertical guide follows it; a dot marks the selected curve and its value is drawn beside it |
| [ ] 7.2.11 | Watch the slider while dragging | It tracks the finger |
| [ ] 7.2.12 | Drag the slider instead | The guide, dot and readout follow it — the sync works both ways |
| [ ] 7.2.13 | Read the readout | "x=…  y=…" for one unmuted series; when **All** shows several curves, "x=…" plus each `label=value` |
| [ ] 7.2.14 | Step to another node with the plot scrubbed | The readout clears and the slider returns to 0 |
| [ ] 7.2.15 | Drag one finger across the plot, then lift it | The guide, dot and readout clear and the slider returns to 0, so it never marks a line that is gone |
| [ ] 7.2.16 | Drag the slider off 0, then pinch, or double-tap the plot | The slider returns to 0 once the gesture ends — it is not left where the first finger landed |

### 7.3 Copy, save and open

| # | Action | Expected |
|---|---|---|
| [ ] 7.3.1 | Double-tap **or long-press** the parameter chip | "Parameters copied" — subset, step and strain window go to the app's parameter clipboard. (The readout line no longer carries params, so the chip is the only copy target; its content description still mentions only double-tap.) |
| [ ] 7.3.2 | Start a new single-setting analysis afterwards | Step 2 offers a **Paste params** chip that fills all three (§5.2.13b) |
| [ ] 7.3.3 | Tap **View** with a node focused | The result viewer opens on that combination |
| [ ] 7.3.4 | Tap **Save graph** | A PNG is rendered and handed straight to the **system chooser**. This is the one export that skips the in-app **Send to** sheet (§8.5a), so there is no "Save to Files" row here |
| [ ] 7.3.5 | Open that PNG | A header — study, reference name + deformed frame count, the focused node's parameters **always**, and *additionally* the combination count when the plot is in **All** — then the plot at full fit and a **single-column** colour legend of the curves |
| [ ] 7.3.6 | Save a graph while the on-screen plot is zoomed in | The export is rendered fit-to-data from a detached view — your zoom is neither baked in nor disturbed |
| [ ] 7.3.7 | Open a combination, then press Back | You return here, not to Home |

---

## 8. Result viewer

The main results browser. Reached directly for a single-setting run, or through
the Lattice for a sweep.

**Entry:** a finished single-setting run, a Home or Settings row, or a Lattice
node. **Exit:** Home, or back to the Lattice.

### 8.1 Fields, image and scale

| # | Action | Expected |
|---|---|---|
| [ ] 8.1.1 | Open a result | The U field is shown as a jet heatmap over that frame's own photo, drawn where the points moved to |
| [ ] 8.1.1c | Open a large result (many frames, a big image) | Past ~0.3 s a centred pill with a spinner reads "Opening <reference name> · N frames" ("1 frame" for one); it goes once the first frame's heatmap draws, or when the frame cannot be read. Nothing else on screen moves for it |
| [ ] 8.1.1a | Step through the frames of a run whose specimen visibly deforms | The photo under the map changes with each frame and the map stays on the specimen. A sweep shows its one deformed photo under every node |
| [ ] 8.1.1b | Open a session whose deformed photos are not on the phone | Each frame falls back to the reference photo, with the map at the reference positions, still lined up |
| [ ] 8.1.2 | Tap the field FAB, then pick V / Exx / Eyy / Exy | Heatmap and colour scale follow; the FAB names the field (the edge title does not repeat it); the live field stays checked in the popup |
| [ ] 8.1.2a | Open the field popup | All five fields are listed; the one on screen is highlighted |
| [ ] 8.1.2b | Check fit at rest | Heatmap (ROI or accepted points) is contained between the top bar and scrub bar; the colour scale may overlay the right edge and stays put while the figure pans |
| [ ] 8.1.2c | Zoom, pan a region that was under the scale into the open area, then tap to probe | Probe readout shows a real point; tapping the scale itself still opens the custom-scale dialog, not a probe |
| [ ] 8.1.3 | Check the scale units | `px` for U and V, `mε` for the strain fields |
| [ ] 8.1.3b | Compare the scale labels with the ⓘ sheet's max/min | On a frame, scale labels read "≤ x" / "≥ y" and may be narrower than the ⓘ sheet (display clamp vs true extrema). On the summary, the colour bar and ⓘ both quote the lowest scale-min and highest scale-max across frames (those two ends may come from different frames) |
| [ ] 8.1.4 | Pinch to zoom | Zooms smoothly up to about 10×; panning is clamped to the image |
| [ ] 8.1.5 | Zoom in and pan | The heatmap stays registered to the photo under it — no drift |
| [ ] 8.1.6 | Zoom, then switch field | Zoom and pan are preserved |
| [ ] 8.1.7 | Tap the colour scale bar | "Scale · U" dialog (Max / Min per field, **Auto**), prefilled with the bounds the bar shows (the auto ones until a custom scale is set); **Apply** without edits leaves the scale as it is |
| [ ] 8.1.8 | Enter min ≥ max and apply | Rejected with a snackbar and a **Why?** that opens the custom-scale FAQ |
| [ ] 8.1.9 | Enter valid bounds and apply | The heatmap and the scale labels both change |
| [ ] 8.1.10 | Switch field, then switch back | The custom bounds are remembered *per field* |
| [ ] 8.1.11 | Reopen the dialog and tap **Auto scale** | The override is dropped; the scale returns to this frame's clamped bounds — not to its true extrema |
| [ ] 8.1.12 | Open ⓘ | Peek sheet shows the specimen name, max / min with coordinates, mean, a histogram of this field, and settings used |
| [ ] 8.1.13 | Scrub frames without a custom scale | Scale labels follow each frame's own 2nd/98th-percentile clamp |

### 8.2 Frames

| # | Action | Expected |
|---|---|---|
| [ ] 8.2.1 | Read the edge title and the counter pill | The title is the frame's original filename without its extension ("steel_03"; "Frame 3" when unnamed); between the arrows, only the frame jump field shows, reading "i / N"; the counter pill is hidden |
| [ ] 8.2.1c | Open an analysis made from a video and step through it | The title is each frame's time in the clip, "0:01.25" (m:ss.cc), not "frame_0003". One restored from the cloud is titled by its frame names |
| [ ] 8.2.1b | Open a sweep node | The title reads "Subset 15 · window 3": the node's subset and its strain window in data points (the step is left out). A sweep from before windows were counted in points keeps its stored label |
| [ ] 8.2.1a | Open a batch the run skipped a frame of (one kept no points or would not read) | Every later frame keeps its own filename — in the edge title, the share CSV's `image` column, each PDF page title and the ZIP's `results/NNN_<name>/` folders, numbered as planned (frame 3 stays `003_…`) — matching the cloud backup's CSV and `Frame_N` folders |
| [ ] 8.2.2 | Tap **Next** | Advances one frame; the heatmap and stats update |
| [ ] 8.2.3 | Reach the last frame | **Next** disables and fades |
| [ ] 8.2.4 | Reach the first frame | **Prev** goes back to the summary, not nowhere |
| [ ] 8.2.5 | Tap Next rapidly | Keeps up without stuttering or showing a stale frame |
| [ ] 8.2.6 | Scrub through a sweep | Each frame is a different combination; the settings sheet follows it |
| [ ] 8.2.6a | On a sweep, **Prev** on the first combination | Stays on that combination (no overview slot) |
| [ ] 8.2.6b | Open Share on a sweep | No **Animations** row; **Everything** has no `animations/` folder |
| [ ] 8.2.7 | Rotate the device | The same frame and field stay on screen |
| [ ] 8.2.8 | Type a frame number and press Go | Jumps straight there; the field has no underline under it |
| [ ] 8.2.9 | Type `0`, a number past the end, or letters | Nothing moves and the current number comes back |
| [ ] 8.2.10 | Step with Next/Prev | The number follows immediately, not after the frame decodes |
| [ ] 8.2.11 | Double-tap the image | Zooms about 2× on the tap; double-tap again resets to fit |
| [ ] 8.2.12 | Horizontal fling while fit-to-screen | Steps one frame (same as Next/Prev) |
| [ ] 8.2.13 | Open a session with no `.dat` frames | Snackbar `no_batch_data` with **Why?** → FAQ |
| [ ] 8.2.14 | Force a frame OOM (huge session, low memory) | Snackbar with **Why?** → viewer-oom FAQ |

### 8.2a Summary animation `[single]`

Single-setting analyses only (not parameter sweeps — those open from the lattice
onto a combination, with no summary slot and no Animations share target).

**A test with a load log shows Results in this slot instead** (8.2a.10–8.2a.13):
for a student the summary of the experiment is the stress–strain curve, not a
heatmap loop. The field animations are still built for Share → Animations.

| # | Action | Expected |
|---|---|---|
| [ ] 8.2a.1 | Open a result | It lands on the summary, which builds and then loops. The **edge title** reads "Summary"; the **counter** pill counts the frames it plays ("5 frames") and the frame jump field is hidden |
| [ ] 8.2a.1b | Open a result with a sub-frame ROI (or a small accepted patch) | The summary GIF is framed on that coloured region — same rest-fit contain scale as the live viewer, not a letterboxed full photo |
| [ ] 8.2a.1c | Watch the summary of a run whose specimen visibly deforms | The map moves with the specimen frame to frame (each frame drawn where its points moved to, as on a frame's own photo) and stays inside the GIF's box for the whole loop |
| [ ] 8.2a.1a | Watch it build | A light pill just above the scrubber: one untruncated line ("Reading frames · 2 of 5", then "Building summary · 3 of 5"), a thin bar (indeterminate until the first count) and a **✕** ("Cancel summary" to TalkBack, which also reads each count). It stays up while the chrome auto-hides and goes when the loop plays |
| [ ] 8.2a.1d | Tap **✕** while it builds | The build stops and the pill goes |
| [ ] 8.2a.1e | Read the scale labels while it reads frames | "—" at both ends, never "Max: 0.0" / "Min: 0.0"; the "≥ … / ≤ …" ends appear once the sequence range is known |
| [ ] 8.2a.2 | Watch a short (≤33 frame) analysis | Each frame is visible for about 300 ms |
| [ ] 8.2a.3 | Watch a 500-frame analysis | Every frame is there and the loop still finishes inside 10 s |
| [ ] 8.2a.4 | Compare early and late frames of a growing test | Colour rises through the sequence — one scale throughout, no per-frame renormalising |
| [ ] 8.2a.5 | Read the scale labels beside it | The widest bounds in the whole sequence, not the current frame's |
| [ ] 8.2a.5a | Open ⓘ on the summary | Max and min of that GIF scale, no mean, and no histogram |
| [ ] 8.2a.10 | Open a tensile result with a load log | It lands on **Results**: edge title and counter read "Results"; the page shows the stress–strain curve with its elastic-fit line, a caption "The whole test: n of N photos on the curve…", then E (frames, R², approximate) and peak stress. "Reading strain n / N…" shows while the curve builds |
| [ ] 8.2a.10a | On that page, tap **Elastic region** beside the Results title | The plot zooms to the fitted frames plus a margin (8.4.14e); the caption reads "Zoomed to frames a–b, the straight part E is fitted to…". **Whole test** goes back |
| [ ] 8.2a.11 | Look for the field picker and colour scale on Results | Neither is there, including after the chrome fades and comes back |
| [ ] 8.2a.12 | Tap › to frame 1, then ‹ back | Frame 1 has the field picker and colour scale again; back on Results they are gone again. Opening ⓘ on Results shows the same curve with no frame highlighted |
| [ ] 8.2a.13 | Open a result without loads, or a sweep | The summary animation as before (8.2a.1) |
| [ ] 8.2a.6 | Switch field | The animation rebuilds in that field; switching back replays from cache |
| [ ] 8.2a.7 | Set a custom scale for one field | Only that field's animation rebuilds |
| [ ] 8.2a.8 | Tap **Next** on the summary, then **Prev** on frame 1 | Leaves to frame 1 and comes back |
| [ ] 8.2a.9 | Step into the frames while it is still building | The viewer stays responsive throughout |

### 8.3 Tap to probe

No Inspect / X,Y / Max-Min tools. A short tap on the heatmap is the reading;
drag and pinch keep pan and zoom. Chrome hides only after the idle timer;
a centre double-tap brings the bars back when they have faded.

| # | Action | Expected |
|---|---|---|
| [ ] 8.3.1 | Short-tap the heatmap (including the centre) | Plain-text readout shows the nearest correlated point: field value with units and `(x, y)` |
| [ ] 8.3.1a | Wait for chrome to fade, then centre double-tap | Bars come back; no zoom from that double-tap |
| [ ] 8.3.1b | Centre double-tap while chrome is already visible | Zooms about 2× (same as off-centre double-tap) |
| [ ] 8.3.1c | Swipe down while fit-to-screen | Chrome shows if it was hidden; swipe does not hide chrome |
| [ ] 8.3.2 | Drag past the touch slop | The image pans (when zoomed) or a horizontal fling steps frames (when fit); no probe is placed mid-drag |
| [ ] 8.3.3 | Tap outside the correlated area | Readout says "No data" rather than a wrong number |
| [ ] 8.3.4 | Pinch while a probe is up | Zoom works; the crosshair stays glued to the image point |
| [ ] 8.3.5 | Switch field or frame with a probe up | The value updates for the same image location (or "No data") |
| [ ] 8.3.6 | Tap the readout chip | The probe dismisses |
| [ ] 8.3.7 | Open ⓘ | Stats list max and min with coordinates, plus mean and a histogram — no Max/Min toggle |
| [ ] 8.3.8 | Rotate with a probe up | Frame, field and probe survive |

### 8.4 Details (ⓘ peek sheet)

| # | Action | Expected |
|---|---|---|
| [ ] 8.4.1 | Tap the info button | Peek sheet titled **Details** with the specimen name under it, then max / min (coordinates) / mean, a histogram of this field's accepted values, then subset, step, strain window, strain method, ROI and image size |
| [ ] 8.4.1a | Open it on a run that stopped early | Two extra rows: **Stopped early** and **Frames solved (n of N)** — the provenance survives a restart |
| [ ] 8.4.1b | Open it on the summary GIF | Max and min of the GIF colour-bar ends, no mean, and no histogram |
| [ ] 8.4.2 | Compare against what you entered in the wizard | They match |
| [ ] 8.4.3 | Read the **strain window** row | "9-point window · VSG 41 px": the window in points and the VSG it gave, (window − 1) × step + 1. A session from before the window was counted in points shows "VSG 15 px" alone |
| [ ] 8.4.4 | Scrub to another combination and reopen | The values follow the new frame, not the run's first |
| [ ] 8.4.5 | Open it on a sweep | A line-cut plot with colour-matched Exx / Eyy / Exy and the cut axis named |
| [ ] 8.4.6 | Open it on a single-setting run | No line-cut section |
| [ ] 8.4.7 | Look for **Go to Home** in the sheet | It is not there any more — Home is a chrome icon in the top bar (§8.6.1) |
| [ ] 8.4.8 | Tap a histogram bar | Caption under the plot names that bin's count and value range, in px or millistrain |
| [ ] 8.4.9 | Compare the histogram with the colour bar | The histogram includes the outliers the colour bar has clamped; its ends are the true min and max |
| [ ] 8.4.10 | Open it on a tensile / compression run with a load log | Rows **Cross-section**, **Machine load** (this frame, signed as logged) and **Stress** (MPa) after Test type; a **Results** section with a caption "Reading strain n / N…" that fills every frame, then the curve (engineering strain ΔL / L₀ along the chosen axis from the virtual extensometer between the end bands of the analysed region, ADR-012, vs stress) with this frame highlighted and a caption "Frame n · load N · stress MPa · strain mε" |
| [ ] 8.4.11 | Close and reopen the sheet | The curve is there at once (cached in the ViewModel); rotating keeps it |
| [ ] 8.4.12 | Run logged negative | The curve sits in the third quadrant; nothing is made positive |
| [ ] 8.4.13 | Scrub to a frame with no accepted points | Caption says that frame is not on the curve; the rows still show its load |
| [ ] 8.4.14 | Open it on a sweep | Test type row only; no load rows, no curve |
| [ ] 8.4.14a | Same tensile run, once the curve has drawn | A second, muted **Elastic fit** line over the straight early part; under the caption "Young's modulus E ≈ … GPa, from the straight part of the curve (frames a–b, R² …)", marked approximate, then **Peak stress** with its frame |
| [ ] 8.4.14b | A tensile run with fewer than three frames before the peak, or no straight start | No fit line; the E line says it was not found and why (three photos on the straight part, before the peak) |
| [ ] 8.4.14c | A tensile run logged against the wrong strain axis (E ≤ 0) | E is shown with its sign, plus a line asking to check the strain axis |
| [ ] 8.4.14d | Same tensile run with a fit: look beside the **Results** title | A **Whole test / Elastic region** toggle, on *Whole test*. It is not there while the curve builds, on a run where E was not found (8.4.14b), or for bending |
| [ ] 8.4.14e | Tap **Elastic region** | The plot zooms to the frames E was fitted to plus half their strain span again (from the origin), with the fit line drawn across it, so the student can see where the curve leaves the line. Frames after the peak are left out. On the Zenodo steel run (docs/app/REAL_WORLD_VALIDATION.md) that is frames 1–28 up to 1.94 mε, where the whole test runs to 336 mε. The caption and the E / peak lines are unchanged |
| [ ] 8.4.14f | With **Elastic region** on, close the sheet, step frames, reopen; go to the summary page | The choice is kept (ViewModel), on both surfaces and after rotating. On a frame outside the window the highlight line is off the plot; the caption still gives that frame's values |
| [ ] 8.4.14g | Tensile run on the summary page: tap **Adjust curve** beside the **Results** title, type strain scale 1.25, strain bias 57.5, leave stress at 1 and 0, **Apply** | The curve redraws at once without "Reading frames…"; each strain is 1.25 × the camera's + 57.5 mε, and E, Rp0.2 and the peak follow. A 0 or blank scale is refused in its box. **Reset** returns to 1 / 0. Not shown for bending or 2D DIC |
| [ ] 8.4.14h | Same run: back to Home, reopen it; then Share → **Lab report** and **Data (CSV)** | The match is still applied. The report lists "Curve matched to the machine by ε = … × ε(DIC) + … mε; σ = … × (L / A) + … MPa" and its row-1 calculation shows the camera's values, then the matched ones. The CSV preamble has `# strain_scale`, `# strain_bias_millistrain`, `# stress_scale`, `# stress_bias_MPa`, and `stress_MPa` is matched |
| [ ] 8.4.15 | Open it on a bending run without taps (made before this build) | Rows **Support span**, **Width**, **Thickness**, **Machine load**, **Flexural stress**; the curve's y-axis reads "Flexural stress (MPa)" |
| [ ] 8.4.15a | Open a bending run with taps | Results shows the **load–deflection** graph (load in kg on δ) with a muted slope line from the first measured δ to the last, never from δ = 0 (a fit whose intercept is below zero no longer pulls the load axis down to it; the axis still reaches about 8 % of the load span below 0 N as head-room, e.g. about -161 kg (-1582 N) on `concrete_00`), then E from the graph (slope, R²), average E over the load steps ("n load steps" counts held loads, not frames), the mm/px scale and the approximate note. On the synthetic video of §5.1b.24: 141.5 GPa and 173.1 GPa over 6 load steps. A modulus under 10 GPa (PMMA) shows two decimals, so two close values stay apart. The axes never read "-0.00", and a five-digit load tick (e.g. 21686) reads in full |

### 8.5 Share and export

| # | Action | Expected |
|---|---|---|
| [ ] 8.5.0 | On a demo account, look at and tap **Share** | Greyed, never hidden; a tap says the licence note (4.4b). On a licensed account with the `share` kill switch on: greyed, and a tap says "Sharing is temporarily unavailable." |
| [ ] 8.5.1 | Tap Share | Sheet headed **Share**, with the frame on screen at the header's end ("frame 2 of 5"; "combination 2 of 5" on a sweep). Six flat one-line rows split by hairlines, no cards: icon, title, file type at the end — **This field** PNG, **All fields** 5 PNG, **Animations** 5 GIF, **Report** PDF, **Data** CSV, **Everything** ZIP |
| [ ] 8.5.1a | Same, with TalkBack on | Each row reads what it shares: "Share this field (U of frame.jpg) as PNG", "Share all 5 fields of frame.jpg as PNG", "Share the report of all 5 frames as PDF", … |
| [ ] 8.5.2 | **This field** | One annotated PNG of the field and frame on screen, drawn as the viewer draws it: on that frame's own photo, the map and MAX / MIN markers where the points moved to; on the reference, map at the reference positions, when the frame's photo is not on the phone (8.1.1b) |
| [ ] 8.5.3 | **All fields** | Five PNGs for the current frame, zipped for hand-off. Each PNG's stamp names the **source image**; the file names still come from the analysis name |
| [ ] 8.5.3a | **Animations** `[single]` | Five GIFs, one per field, zipped; each loops when opened in a gallery app. Row is absent on a parameter sweep |
| [ ] 8.5.3b | Same, immediately on entering the viewer `[single]` | Fields not built yet are built under the progress dialog — never silently missing |
| [ ] 8.5.4 | **Report** | One PDF: every frame's pages plus a telemetry page. Each frame's field images sit on that frame's own photo with the map where the points moved to, as in the viewer (the reference when the photo is not on the phone) |
| [ ] 8.5.4d | **Lab report** — after **Report**, tensile run with loads, not a sweep | A journal-style write-up in the layout of the student's handwritten report ([STUDENT_LAB_WORKFLOW.md](STUDENT_LAB_WORKFLOW.md)): experiment and title, Name / Date lines, Aim, Materials required, Theory, the reference photo with the analysed region, Observations (area filled in; lengths and diameters as blank lines), a **DIC gauge length along x/y (px)** line; the observation table (S.No, Frame (the viewer's frame number), Load kN, Extension px, Stress MPa, Strain — Extension ÷ gauge length ≈ Strain; "—" once an end of the region has left the view) with **Elastic / Plastic / Break point** bracketed in the margin, Calculation for row 1 (stress, strain, and ΔL in px over the gauge), the elastic-region and full stress–strain graphs with E boxed, Results (E and peak stress, with the approximate note), and ruled Conclusions lines |
| [ ] 8.5.4e | **Lab report** on a bending run with taps | Offered after **Report** (absent until the thickness is tapped). Layout of Experiment 5: title, Aim, Experimental setup, Theory, procedure (a)–(d), the reference photo with the two taps and the probe circle, Observations (L, b, t, no-load reading = reference photo, mm/px), row-1 Calculation (M, y, I, σb, E), the table with **one row per load step** (unloaded frames left out; the **Frame** column gives the viewer's frame, or a range for a held load, so when the first frame carries no load, row 1 reads Frame 2), Results (average E, E from the graph) and the load–deflection graph with slope and E boxed |
| [ ] 8.5.4f | Open Share on a plain 2D DIC run, on a sweep, or on a run without loads | No **Lab report** row: the sheet is Semper's |
| [ ] 8.5.4a | **Report**, on a tensile run | Each cover has a **Mechanical Test** block (type, cross-section, strain, this frame's load and engineering stress); before telemetry a **Stress–Strain Curve** page with the plot, peak stress, and a **Modulus E (approx.)** with its frames and R², and a per-frame Load / Stress / Strain table continued over as many pages as needed. Progress shows "Stress–strain n / N…" first if the ⓘ sheet has not built the curve yet |
| [ ] 8.5.4b | **Report**, on bending | The cover block names that test's dimensions and flexural stress; the curve page is titled the same way |
| [ ] 8.5.4c | **Report**, on a sweep | Cover block shows the type and dimensions only; no curve page |
| [ ] 8.5.5 | **Data** | One CSV for the whole analysis: `#` preamble (version, reference, strain method, ROI, per-frame U/V/Exx/Eyy/Exy max/min/mean), blank line, then point header `image,x_px,y_px,u_px,v_px,exx,eyy,exy,znssd,shift_u_px,shift_v_px,shift_rot_deg` — the three motion columns are written for every session, empty when a frame admits no fit; sweeps insert `subset_px,step_px,strain_window,vsg_px` after `image` (`strain_window` in points, empty for a sweep stored before points; `vsg_px` the VSG) |
| [ ] 8.5.5a | **Data**, on a tensile or bending run | `# semper_csv_version,2`: the preamble adds `# test_type`, `# stress_model` (`axial` / `flexural`), that model's dimensions (`# cross_section_mm2`, or `# span_mm` / `# width_mm` / `# thickness_mm`), `# load_axis` and `# load_unit,N`; every point row ends `load_N,stress_MPa` (empty for a frame with no load). A non-sweep tensile run ends with a `# mechanical_results` trailer. A plain 2D DIC run's CSV is Semper's version 1, without these |
| [ ] 8.5.6 | **Everything** | Raw photos, per-frame results for all five fields, the CSV and the PDF; single-setting also includes the five field GIFs under `animations/` |
| [ ] 8.5.7 | Check the filename of anything you export | It carries the specimen / analysis name, not a generic `export.zip` |
| [ ] 8.5.8 | Export a very large analysis | Determinate progress dialog, then either a file or a message naming the failure — never a crash, and never an OOM from rendering the report |
| [ ] 8.5.8b | Watch the dialog of a CSV, **All fields**, **Animations** or **Everything** export | It spins until the first report, then names what it is on ("Frame 12 of 40 · heatmaps", "Field 2 of 5 · V heatmap", "Frame 3 of 40 · Exx animation"), shows the percent to one decimal on the right and, after about 3 s, "About N s left" under the bar. No kind spins for its whole run; only **This field** (one render) has no steps. On a dense analysis the CSV's "Frame k of N · point rows" keeps moving inside the frame, and the end of **Everything** shows "Adding the raw photos", heatmaps, then "Writing the archive" climbing to 100 % rather than holding |
| [ ] 8.5.8a | Dismiss that dialog with Back, or by tapping outside | The export keeps running behind a **transfer banner** at the top of the viewer, with the same status, percent and time left, Cancel and ‹ › paging — the same strip Settings uses (§4.0) |
| [ ] 8.5.9 | Check an exported PNG | Heatmap baked in, min/max annotated, composited to a **1280 px long edge** — not the reference's full sensor resolution. Beside the viewer on the same frame and field: the same photo, the map in the same place |

### 8.5a Send to — the export handoff

Viewer exports and the two Settings data exports end at the same in-app **Send
to** bottom sheet rather than being thrown straight at the system chooser. Two
rows, so "keep this file" and "send this file somewhere" are separate decisions.

**When the sheet appears depends on the target.** The single **This field**
photo is generated first and then offered. The five slow targets — All fields,
Animations, PDF, CSV, Everything — ask **first**: the sheet comes up before any
work, and choosing **Save to Files** opens SAF straight away so the export is
written directly into the document you picked. Two exports skip the sheet
entirely by design: Settings' **Download** (§4.14e, destination already chosen)
and the lattice's **Save graph** (§7.3.4, straight to the system chooser).

| # | Action | Expected |
|---|---|---|
| [ ] 8.5a.1 | Trigger any viewer export | A **Send to** sheet with a folder-icon **Save to Files** row and a **Share** row, one line each. There is no filename caption on it |
| [ ] 8.5a.2 | Tap **Save to Files** for **This field** | A SAF save dialog opens via the transparent `SaveExportActivity`; the already-built file lands where you choose |
| [ ] 8.5a.2a | Tap **Save to Files** for a slow target (PDF, Everything, …) | SAF opens **before** generation, and the export is written straight into that document — nothing is staged and re-offered |
| [ ] 8.5a.3 | Cancel that SAF dialog | You come back to the app cleanly, with nothing half-written |
| [ ] 8.5a.4 | Tap **Share** | The normal system chooser opens with the file attached |
| [ ] 8.5a.5 | Dismiss the sheet without choosing | Nothing is written and nothing is sent; no error |

### 8.6 Leaving

| # | Action | Expected |
|---|---|---|
| [ ] 8.6.1 | Tap the home icon in the top chrome | Home, with the back stack cleared |
| [ ] 8.6.2 | Press Back (or the top-left arrow, TalkBack "Back") on a single-setting result | Home, whether it was opened from Home or when its run finished; never the wizard, and never "back to settings" |
| [ ] 8.6.3 | Press Back on a sweep combination | The Lattice |
| [ ] 8.6.4 | Leave a single-setting result and look at its Home row | The headline is unchanged by viewing — still the first frame's convergence (§3.4), whatever frame or field was on screen |
| [ ] 8.6.5 | Leave a sweep and look at its Home row | The row still reads "K of N solved"; nothing in the viewer rewrites it |
| [ ] 8.6.6 | Look for rename or delete in the viewer | Neither exists — both live on Home |

---

## 9. Session limit

The quota gate. Not a paywall — there is no billing, price or purchase anywhere
in the app ([ADR-024](../adr/ADR-024-account-anchored-entitlements.md)). The way
past it is to delete an analysis, or, when a seat or licence has just been added
to the account, **Refresh licence**.

**Entry:** Home cold start, the Home FAB, the quota chip, a pre-run check, a
sweep hitting the cap, or a background upload rejected with a quota error.
**Exit:** Home, once the limit clears.

| # | Action | Expected |
|---|---|---|
| [ ] 9.1 | Reach the cap and tap **+** on Home | This screen instead of the source chooser |
| [ ] 9.2 | Read the chip | "Using N of M analyses" once the backend has reported numbers |
| [ ] 9.3 | Tap **Email support** | Mail app on a neutral message, with account, quota, device ID and build underneath; it asks for no higher limit |
| [ ] 9.3a | Tap **Refresh licence** | One line as in 4.4a; if the licence changed, the limit is checked again and the screen closes once there is room |
| [ ] 9.4 | Tap **Re-check** while still at the cap | "Still at the limit" |
| [ ] 9.5 | Delete an analysis elsewhere, then tap **Re-check** | The screen closes and you can start a new analysis |
| [ ] 9.6 | Tap **Back to my analyses** | Home |
| [ ] 9.7 | Start a run one under the cap, and let the cap fill while it solves (another phone's backup, or an upload refused as full) | The run saves its row; the next new run is blocked |

**The rule.** One function decides whether the account may add an analysis,
`SessionQuota.blocked(context, liveRows)` (`data/session/SessionQuota.kt`), and
every check asks it: Home's **+**, cold start, quota chip and reconcile
(`HomeQuotaCard`), this screen's **Re-check**, the wizard's start check
(`AnalysisNavHelper.ensureSessionQuota`), the run's own start check on the native
thread (`admitRun` in `ui/analysis/wizard/RunChannels.kt`), and the save of a new
row (`SessionStore.save`). In order:

1. No backend (`CloudApi.enabled` false: a build with no API URL, or the dev-auth
   bypass) → no cap.
2. Licensed with no `/v1/config` yet (`LicenseEntitlements.hasUnlimitedAnalysis`)
   → no cap.
3. The server has said full → blocked. An upload refused with 409
   `session_quota_exceeded` forces the stop (`AccountCache.setSessionLimitReached`);
   fresh server numbers (`setQuota`) or a delete on the phone
   (`onLocalSessionsRemoved`) lift it. An upload refused with 409
   `device_quota_exceeded` (the demo cap counted per device) forces the device's
   stop instead (`AccountCache.setDeviceLimitReached`, read by
   `SessionQuota.deviceStop` while the server counts the account as demo). Fresh
   account numbers do not lift it, so the limit screen's Re-check keeps it; a
   delete on the phone, sign-out or a licence does (TD-219).
4. Otherwise blocked when the larger of the server's last count and the phone's
   rows reaches `LicenseEntitlements.analysisCap()`. The phone's rows are the
   session index's: a check off the main thread counts them, and a tap on the main
   thread reads the count `SessionStore` stores with every write of the index
   (`SessionQuota.blockedNow`).

Only **starting** a new analysis is blocked. A batch or sweep the run's start
check admitted as a new row (`RunAdmission.Admitted`) saves it even if the cap
fills while it solves: the save passes `allowOverLimit`, as a cloud restore does.
Every run is checked at its start (`AnalysisNavHelper.ensureCanStart`, then
`admitRun` on the native thread): a saved analysis is never written over, so
whatever a run saves is a new row (owner decision, 2026-10-15). A save no start
check admitted would meet the rule and end as `RunStop.SessionLimit`
(`afterSave`); no run path makes one today.
The backend counts account-wide (every app and phone) and refuses only at
`POST /v1/sessions`, the upload. For a demo account it also counts every
account's analyses stored from this phone, and refuses at the cap with its own
`409 device_quota_exceeded` (`count_device_sessions`,
`backend/app/repo/sessions.py:466-490`); both counts are of analyses stored now,
so a delete frees a place.

**Where the cap (`M`) comes from.** `LicenseEntitlements.analysisCap()` reads
`AppRemoteConfig`, which is populated from the backend's `GET /v1/config` (see
[CLOUD_ARCHITECTURE_GCP.md §20](../backend/CLOUD_ARCHITECTURE_GCP.md#20-licensing--entitlements)):

- **Demo** (the default for every account until the server attaches a paid
  entitlement): capped at 25 saved analyses, this screen included. Demo analyses
  are still **recorded** — each finished analysis uploads silently
  (`CloudSync.uploadsEnabled` ignores the Save-to-cloud toggle, which demo sees
  locked) — but demo has no backup/restore *feature*: Home shows no cloud state
  icon or row progress (3.4, 3.7, 3.8 do not apply), Settings shows **Cloud
  backup**, **Analyses data management**, **Free up** and auto-free locked (4.4b;
  4.5–4.18g do not apply), and a stored copy is never pulled back. The upload is
  what the cap counts.
- **Professional — individual key**: capped at the backend's licensed
  `maxSessions` (999 by default, never below demo's 25) once `/v1/config` has
  reported it, and uncapped before; Semper staff mint and hand over the key.
- **Professional — institution seat**: identical entitlement to an
  individual key — an institution seat and an individual key resolve to
  the exact same `mode=licensed` on device. What differs is only how the
  seat is administered: institution IT self-service via backend routes (see
  §20.4 of the doc above), not Semper staff, and not through this app.

**Status at this revision.** There is no screen to type a key into, and there
is not meant to be one ([ADR-024](../adr/ADR-024-account-anchored-entitlements.md)).
A licence is minted against the customer's email address and attaches at their
next sign-in — an individual licence directly, an institution seat through the
roster — or the person redeems a one-time claim code on the web account page.
Nothing is read off a phone, dictated, or typed into the app, and
`SemperApi.activateLicense()` is gone; `POST /v1/licenses/activate` stays on the
backend for installed builds only. What the app shows of a
licence is its prefix, in Settings → Account (4.2a); the key itself never
reaches the device. This screen's behaviour for a Professional account is
unaffected either way: once `GET /v1/config` reports `mode=licensed`, the cap
is the licensed `maxSessions`, so 9.1 triggers only at that ceiling.

**9.4 No seat right now (floating institution licence).** A separate gate from
this screen, and not a limit: the account is on the roster but every seat is in
use. It appears when starting new work — the Home **+** button, before the
Import/Record menu opens, and again at Compute for a run started from inside an
analysis. Saved analyses stay open throughout, and a run already in flight is
never interrupted.

The screen is one button that asks for a seat again. Unlike 9.1 it needs no
email to support: seats free themselves as colleagues finish.

**9.3 License expiry notice (Home).** Separate from this screen, and not a
gate. A licensed account whose key expires within 14 days — or which is past
expiry but still inside its grace window — shows a small chip under the Home
title: "Licence expires in N days", or "Licence expired — still working until
15 Mar 2027" (the day grace ends, from `licenseGraceEndsAt`; "still working for
now" when the backend sent none). Past the day with no grace the cache can
confirm, it reads "Licence expired — ask your institution's IT or Semper support"
(`HomeLicenseNotice.of`, `app/src/main/java/com/sempermechanics/semper/ui/home/HomeLicenseNotice.kt:41-47`).
No renew, buy or email action. Nothing is withdrawn while it shows; during grace the
account keeps cloud backup, share and the uncapped analysis count, and the only
thing that ever changes entitlement is the backend flipping `mode` to `demo`
once grace ends (at which point 9.1 applies exactly as it does for any Demo
account).

The chip is suppressed when the cached config is more than a week old. A
renewal may have landed while the device was offline, and warning from a stale
cache would be a false alarm the user cannot act on. A perpetual license never
shows it.

---

## 10. Background work

Uploads, restores, **bundle downloads** and backup deletes run in WorkManager and
survive leaving the screen. Their file chains, failure outputs and log signals
are `B1`–`B4` in [../WORKFLOWS.md](../WORKFLOWS.md#b-app--background-and-data-workflows):

| Worker | Job |
|---|---|
| `DicUploadWorker` | Back up a session to the cloud |
| `DicRestoreWorker` | Pull a session back into the app |
| `DicBundleDownloadWorker` | Write a `Session.zip` into a SAF document the user picked first (§4.14c) |
| `BackupDeleteWorker` | Run a queued `SessionDeletes` job after the undo window: one analysis at a time, progress per row, and the ids still in the cloud |

They are **no longer silent about failure**: a terminal upload failure surfaces on
Home (message pill + a "why + retry" dialog on the row's cloud icon), and a terminal restore
failure surfaces on **both** Home and Settings, each carrying a human reason.
In the notification shade, a finished transfer says so ("steel_00 is backed up",
"… is restored", "… is saved") and a failed one gives its reason, with **Retry** on a
backup or a restore (Settings → Notifications must allow them from Android 13 on;
the app does not ask). On Home the row's cloud icon updates as well.

Progress is visible in three places: the transfer's foreground notification while
you are elsewhere in the system (bar, "4.2 of 12.0 MB · 1.1 MB/s", "34.6% · About
35 s left"; an upload still staging says "Preparing the backup"); a live icon,
progress text ("Backing up · 4.2 of 12 MB") and bar on the Home row
whenever Home is on screen, for downloads as well as uploads; and the **transfer
banner** inside Settings (§4.0) and the result viewer (§8.5.8a), which is what
carries exports and anything started from those screens.

| # | Action | Expected |
|---|---|---|
| [ ] 10.1 | Finish an analysis with cloud backup on | Upload is queued; the Home cloud icon moves from "Upload pending" to "Backed up", with live progress on the row |
| [ ] 10.2 | Queue an upload with no network | It retries and eventually succeeds once you reconnect |
| [ ] 10.2a | Queue an upload for an analysis older than 15 min whose `.dat` files were deleted from its session folder | After about 15 minutes of retries the cloud icon turns red ("Not backed up") and its dialog says the results are no longer on the device — it does not sit on Pending forever |
| [ ] 10.3 | Restore from Settings and leave the screen | It completes anyway; the analysis appears on Home / in the list |
| [ ] 10.3a | Cause a terminal upload or restore failure | The reason is surfaced on return (Home message pill / cloud icon dialog, or the same pill in Settings) — not swallowed |
| [ ] 10.3b | Start a restore, then sit on Home while it runs | That row shows a progress bar, "Restoring" text and the cloud-download icon throughout — you are not left guessing |
| [ ] 10.3c | Start a Download from Settings and leave Settings | It finishes anyway and reports the outcome; it is a worker, not an Activity-scoped job |
| [ ] 10.4 | Background the app during a transfer | Its foreground notification tracks it with bytes, rate, percent and time left; returning to Home picks the row progress back up |
| [ ] 10.4a | With notifications allowed, let a backup finish, then make one fail | "<name> is backed up"; then "<name> was not backed up" with the reason and **Retry**, which queues the backup again (the row goes back to Pending) |
| [ ] 10.5 | Delete a backup and background the app inside the undo window | The delete still fires after the window |
| [ ] 10.6 | Pull to refresh on Home with many cloud sessions | The listing pages through the backend until complete — sessions past the first page are not silently missing |
| [ ] 10.7 | Deep-refresh while Drive is unreachable | Nothing is purged from the list; a transient backend outage must not look like deleted data |

---

## 11. Hidden, gated and dead paths

Not part of the test pass. Recorded so nobody rediscovers them the hard way.

### Gated — real, but only under conditions

| Path | Condition |
|---|---|
| **Google SSO** button and its divider | Hidden unless `default_web_client_id` is in the APK (from `google-services.json`). Release resource shrinking must not strip it — see [AUTH_SETUP.md](../backend/AUTH_SETUP.md). Separately, the build's signing SHA-1 must be registered in Firebase or the button shows but sign-in fails |
| Settings → **Pending access requests** → Admin | Hidden unless the backend reports role `admin` |
| Dev sign-in bypass (skips auth, disables cloud) | Debug build **and** the bypass flag **and** an emulator |
| Splash → Home without auth | Debug build with no `SEMPER_API_BASE_URL`. A *release* build with no base URL cannot get past sign-in at all |
| `DebugViewerSeedActivity` | A 13th activity, declared only in `app/src/debug/AndroidManifest.xml` and exported so `adb` can drop straight into the result viewer for emulator screenshots. The "12 activities" count above is the **main** manifest |

### Blocked on external setup

**Email sign-in link** is code-complete on both sides, but the App Link only
opens the app once Digital Asset Links are verified at the Firebase host. Until
then the link opens in a browser and the flow is effectively dead. See
[backend/AUTH_SETUP.md](../backend/AUTH_SETUP.md) §1a. The **password-reset**
App Link (`/finishReset`) rides on the same verification — once asset links are
verified, both are live; §1.13a covers the in-app reset form it opens.

### Dead — implemented but unreachable

| Feature | Where | Why unreachable |
|---|---|---|
| **Circle, ellipse and freeform ROI** | `StudioOverlayView` — full draw, hit-test and mask generation | `activity_roi_draw.xml` only exposes Rect and Square; everything else collapses to Rectangle |
| **Convergence view** (peak strain and noise vs VSG) | documented in `SweepPlotView` / `SweepStudy` | Never built — only line-cut plots exist |
| Frame-order *picker* mode | `FrameOrderHelper` | Only the initial state; the sort menu offers no way back once you sort |

### Behavioural gaps worth knowing

- **Account deletion re-authenticates first** (password prompt, or a fresh Google
  credential), so `delete()` is no longer refused as stale and the identity goes
  with the data. If it still fails the user is told the data is gone but the
  sign-in survived, and is signed out regardless. Killed mid-deletion, the app
  finishes it at the next start: the wipe and sign-out when the erase had
  answered, or when a probe shows the account gone; nothing when the account
  is still there; the question waits for the start after when offline.
- **Email verification is enforced for password accounts.** Sign-up and every
  later sign-in are blocked until the address is confirmed; the session is torn
  down and a fresh link sent. Google and email-link users are exempt — both
  arrive verified.
- **Pending approval does not poll**, despite its KDoc saying so. Only the button
  checks.
- **Coach marks cannot be replayed or reset** — the flags are write-once with no
  UI to clear them.
- **There is still no dedicated restore screen** — restoring is done from a Home
  row or from Settings → Analyses data management.
- **The advanced-parameters and sweep-settings headers look collapsible** (icon,
  title) but have no click listener. Only the lattice-samples panel really
  collapses.
- **The planned lattice on step 3 is deliberately inert** — node taps are
  disabled there, unlike the result lattice.
- **`DicUploadWorker` starts the Session limit screen from the background** on a
  quota rejection, which Android 10+ blocks — that path likely never fires.
- **`READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` are requested** when the in-sheet
  Images tab needs them (Home also asks for video). The Files tab opens SAF
  instead, so Drive and DNG work without that grant (§3a).
- **The only notification channel is for transfers** — `TransferNotifications`
  creates one channel (`semper_transfers`) carrying the running upload, restore and
  download notifications, and `TransferResultNotifications` posts each one's outcome
  there (§10). `POST_NOTIFICATIONS` is declared but never requested, so on Android 13+
  the outcomes show only after the user allows notifications in system settings;
  terminal failures still surface in-app either way.
- **The licences screen covers the example data and the engine only.**
  Credits & licences (§4.26d) shows the example sets' credits and the DICe,
  OpenCV and Eigen licences; the AndroidX, Firebase and OkHttp notices are not
  listed yet (TD-57). Privacy Policy and Terms of Service are linked from the
  About dialog and open the hosted pages in a browser.
- **Crash reporting is opt-in and off until accepted.** Crashlytics collection
  is disabled in the manifest and enabled only after the first-run prompt or the
  Settings toggle, so a user who never answers sends nothing.
- **The same consent flag also gates product analytics.** `SemperAnalytics` fires
  Firebase Analytics events for analysis started / completed / failed, the two
  data exports and Send feedback — all buckets and enums, never images, results,
  session ids or specimen names — and every one of them is dropped unless
  `AppSettings.diagnosticsEnabled` is on. The consent copy names both halves —
  **Send crash reports and usage data** — matching
  [the privacy policy](../legal/PRIVACY_POLICY.md) §2.4. On a QC or CI test
  account the same consent also sets the Analytics user property and the
  Crashlytics key `account_type` (`test` or `ci`), so test traffic can be told
  from real use; without consent, on a real account and after sign-out it is
  cleared (`Diagnostics.applyAccountType`).
- **An interrupted solve cannot be resumed** — it is a foreground coroutine, so
  process death loses the run.
- **`AnalysisWizardSmokeTest`** opens the analysis wizard and asserts chrome
  (`btnNext` / instruction). Full Home → analyze → results E2E is not automated.

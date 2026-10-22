# ADR-028: Tamper-evident measurement records

**Status:** Accepted (the product owner's decision to build TD-247, 2026-10-22). Built on
`feat/td-247-record-manifest`; not merged, released or run on a device yet.
**Date:** 2026-10-22
**Deciders:** product owner, app owner
**Register:** TD-247 (closed by this record)

Paths are from `app/src/main/java/com/sempermechanics/semper/` unless they start at the
repository root.

## Context

Framework item 1.3 asks that measurement records be tamper-evident, survive process
death and work offline. Analysis is on the phone and the wizard survives a kill
([ADR-005](ADR-005-wizard-process-death.md)), but nothing could show that a saved
analysis is the one the engine wrote:

- A `.dat` is eight floats per point with no MAC, signature or timestamp
  (`field/DicResult.kt`). Anyone with the session folder (a backup, a "Save to Files"
  archive, a rooted phone) can edit a value and every screen, export and PDF repeats it.
- The `.dat` bytes cannot change. The bit-exact oracles
  (`app/src/test/resources/oracles/`, `results/DatFieldOracleTest`,
  `results/SummaryGifOracleTest`) pin them, and every reader (viewer, CSV, report, the
  material_testing fork, outside tools) reads the bare layout.
- The phone already holds a non-exportable EC P-256 key in the Android Keystore, the one
  the backend's device binding verifies (`data/account/DeviceKeys.kt`, ADR-010), and the
  entitlement token binds to its thumbprint (`DeviceKeyThumbprint`, ADR-023).
- A run writes its results to a staging folder and moves them into the session only when
  it is not cancelled (`RunSession.commit`, `ui/analysis/wizard/RunChannels.kt`;
  [ADR-022](ADR-022-rerun-as-copy.md)), so there is one point where a run's results are
  complete and nothing has seen them yet.
- The backend validates artifact roles: `Role` in `backend/app/models.py` is a
  `Literal[…]`, pinned to `data/net/ArtifactRoles.kt` by
  `backend/tests/test_wire_vocabulary.py`. A new role is a backend change and a deploy.

## Decision

1. **A sidecar, not a new format.** Each run that is not cancelled writes
   `record_manifest.json` (`SessionLayout.MANIFEST`) beside its `.dat` files. The `.dat`
   bytes never change. The name does not end in `.dat`, so every `*.dat` listing
   (viewer, Home thumbnail, "Free up space", export) passes it by.
2. **What it holds** (`data/integrity/RecordManifest.kt`), version `v: 1`:
   - `createdAt: {source: "device-clock-untrusted", utc}`: the phone's clock, labelled as
     such. It is not a trusted timestamp.
   - `app: {versionCode, versionName}` and `engine`: the engine submodule's commit,
     `BuildConfig.ENGINE_PIN`, read with `git -C engine rev-parse HEAD` when Gradle
     configures the app (a `providers.exec` value, so the configuration cache tracks it),
     `"unknown"` when git or the submodule is missing.
   - `runSpec`: `RunSpec.recordSettings()`, the settings the session row records.
   - `files: [{name, sha256, size}]`: every `*.dat` and `field_ranges.bin`, by name.
   - `keyThumbprint` (`DeviceKeyThumbprint.of`) and `publicKey` (base64 SPKI DER) of the
     device key, and `signature`: `DeviceKeys.signMessage` (SHA256withECDSA, base64 DER)
     over the **canonical bytes**: the JSON of every other field, keys sorted at every
     level, no whitespace. The file itself is that JSON with `signature` added, so a
     verifier drops `signature`, sorts, and has the signed bytes again.
3. **Written once, where a kill cannot split it from its frames.** The batch run writes
   the summary's `field_ranges.bin` and then the manifest into `session.staging` just
   before `RunSession.commit()` (`ui/analysis/run/BatchAnalysis.kt`), outside the fused
   JNI loop; the sweep does the same at its commit (`endSweep`,
   `ui/analysis/wizard/SweepAnalysis.kt`). Both call `RunSession.commitRecord`, one call
   each. `commit()` promotes the manifest **last**, and `dropResults()` deletes it
   **first**, so a run killed before or during its commit leaves no manifest beside a
   partial set of frames. A cancel throws the staging away with it. Re-run as a copy
   (ADR-022) is a new run and writes its own.
4. **No key, no signature.** When the Keystore cannot sign (Robolectric, a Keystore
   fault), the manifest is written with `signature`, `publicKey` and `keyThumbprint`
   null, and the viewer says "Not signed". A failure to write the manifest at all never
   fails the run; the analysis then reads "Not recorded".
5. **One verifier, five answers** (`data/integrity/RecordVerifier.kt`):
   `Verified`, `SignedElsewhere(keyThumbprint)`, `Changed(files)`, `Unsigned`,
   `NotRecorded`. It takes **this phone's** device-key thumbprint
   (`RecordVerifier.verify(dir, localThumbprint)`; the production overload
   `verify(context, dir)` reads it once from the Keystore through `LocalRecordKey`, and
   gets null when the Keystore cannot give one).
   - No manifest: `NotRecorded` (every analysis from before this change).
   - A manifest that does not parse, whose `publicKey` does not match its
     `keyThumbprint`, or whose signature does not verify against that `publicKey`:
     `Changed([record_manifest.json])`.
   - A listed file that is missing or whose size or SHA-256 differ, or a `*.dat` in the
     folder the manifest does not list: `Changed` with those names.
     `field_ranges.bin` is a cache (`SummaryAnimation.globalRanges` rebuilds it, a
     restore does not bring it back): it is checked when present, and its absence is not
     a change.
   - Every file matches but there is no signature: `Unsigned`.
   - Every file matches and the signature holds: `Verified` **only when the manifest's
     `keyThumbprint` equals this phone's**; any other key, or every key when this phone
     has none, is `SignedElsewhere`, which names the key and is never a pass.
   Why the binding: the manifest carries its own public key (so anyone can check the
   signature's arithmetic), which means a valid signature alone proves only that
   *someone* signed. Anyone can edit a `.dat`, re-hash, and sign with a fresh key of their
   own; checked against the key it carries, that forgery verifies. Comparing the signer's
   thumbprint with this phone's Keystore key closes it on the signing phone: the forger
   cannot sign with this phone's non-exportable key. On any other phone the honest answer
   is `SignedElsewhere`: whether that thumbprint is one of the account's registered
   devices is the backend's to say (a later step). Every answer but `NotRecorded`
   carries the **fingerprint**: the first 16 hex digits of the manifest file's SHA-256.
6. **Backup, restore and export carry it, under an existing role.** The backend validates
   roles, so there is no `ArtifactRoles.MANIFEST`: `UploadStaging` stages the manifest
   into `Session.zip` as `dat/record_manifest.json` (role `dat`, already
   restore-essential and in `RESTORE_ENTRY_PREFIXES`), deflated like other JSON. The
   manifest never changes after the run, so the staged bytes stay identical across
   attempts. `RestoreUnpacker.destFor` already puts a `dat/` entry flat in the session
   folder. "Export everything" (`SessionEverythingExporter`) and "Save to Files" (the
   merged archives) include it. An older build restoring such a backup writes the file
   and ignores it.
7. **Where it shows.**
   - The viewer verifies off the main thread once the frame set is listed
     (`ResultViewerViewModel.listFrames` then `ViewerState.integrity`), so hashing every
     frame never delays the first one. The Details sheet (`SettingsUsedSheet`) gains a
     "Record integrity" row (Verified / Signed on another device / Changed since
     analysis / Not signed / Not recorded) and a `ui/common` `WarnChip`: the changed
     files when `Changed`; the signing key's shortened thumbprint, and that it has to be
     checked against the account's registered devices, when `SignedElsewhere`.
   - The PDF cover's Session Details print "Record fingerprint:" (fingerprint and state,
     `SignedElsewhere` with its key and "not verified here"),
     through a new `ReportData.recordFingerprint` set by each caller after
     `ReportBuilder.buildReport`; the fusion pass is untouched.

## Options

| Option | Why not |
|--------|---------|
| A MAC or signature inside each `.dat` (a trailer or header) | Changes the bytes the oracles pin and every reader parses; the fork and outside tools would read a trailer as points |
| An HMAC with a key in the app | Anyone with the APK has the key; it proves nothing to a third party |
| A server-signed timestamp (an RFC 3161 TSA or a backend route) at save | Analysis is offline; it would need a backend route, a deploy and a retry queue. Left as a later step (see Consequences) |
| A new artifact role `manifest` | The backend's `Role` Literal rejects it until a backend deploy, and `test_wire_vocabulary.py` pins the two lists; `dat/` is already restore-essential |
| Verify in `ViewerFrameLoader.readFrameSet` | Correct and off the main thread, but the first frame would wait for every frame's SHA-256; the batch-wide work on viewer open the invariants rule out |
| Fail closed (refuse to open a `Changed` analysis) | Hides the data the user needs to see what changed; a warning and a printed state are enough for an audit |

## Trade-offs

- **The device key is not a notary.** A signature checked against the public key the
  manifest carries proves only that the files match what *some* key signed: a forger
  who edits a `.dat`, re-hashes and signs with a fresh key of their own passes that
  check. So only this phone's own key earns `Verified` (§5): the forger cannot use this
  phone's non-exportable Keystore key, so the forgery reads `SignedElsewhere` here.
  Elsewhere, `SignedElsewhere` is all a phone can say; the manifest's `keyThumbprint` is
  what an auditor (later, the backend) compares with the account's registered devices.
  A rooted phone that can use its own Keystore key can still sign anything.
- **A reinstall makes a new device key.** This phone's analyses from before a reinstall
  then read "Signed on another device", as does every analysis restored on another
  phone. Correct, if unwelcome: neither phone can vouch for a key it does not hold.
- **Stripping the signature leaves "Not signed".** Someone who edits a `.dat`, rewrites
  the hashes and sets `signature` to null gets "Not signed", not "Changed". "Not signed"
  is therefore not a pass: the sheet and the PDF say so in words.
- **The timestamp is the phone's clock.** It is labelled untrusted in the file itself.
- **A sweep's manifest records its first combination's settings**, as its session row
  does; each combination's `.dat` is hashed. The plan itself is in the row and the
  backup's `metadata.json`, not signed.
- **The viewer hashes every recorded file on open,** in the background. SHA-256 on a
  phone with the ARMv8 crypto extensions runs well above 1 GB/s, so a 100-frame LARGE
  session (about 3 GB of `.dat`) takes seconds before the row fills; until then the row
  reads "Checking…".
- **Existing analyses read "Not recorded".** Nothing is backfilled: a manifest written
  today for an old analysis would sign whatever is on disk now.

## Consequences

- Session files: `record_manifest.json` beside the `.dat` files; `Session.zip` gains
  `dat/record_manifest.json` (no backend change, no new role, no `metadata.json` schema
  change).
- `BuildConfig.ENGINE_PIN` exists; a build outside a git checkout records `"unknown"`.
- material_testing gets the manifest at its next sync of this `main`
  ([FORK_SYNC.md](../ops/FORK_SYNC.md)); it uses the same `DeviceKeys` alias in its own
  app, so its manifests are signed by its own install's key.
- The bit-exact oracles are unchanged.
- A server countersignature (the manifest's SHA-256 sent at backup and timestamped by
  the backend) would turn the device clock into a trusted time; it needs a backend route
  and is not decided.

## Action items

- [x] `data/integrity/` (`RecordManifest`, `RecordManifestWriter`, `RecordVerifier`),
      `SessionLayout.MANIFEST`, `RunSession.commitRecord`, `BuildConfig.ENGINE_PIN`.
- [x] Backup (`UploadStaging`), restore (`RestoreUnpacker`, unchanged mapping), export
      (`SessionEverythingExporter`).
- [x] Viewer row and warning chip; PDF cover fingerprint.
- [x] JVM and Robolectric tests; `RecordManifestProcessDeathTest` (instrumented).
- [x] Run `RecordManifestProcessDeathTest` on an emulator: passed on the Semper_API30
      AVD (API 30), 2026-10-22, on `d4d09231`.
- [x] Verified only for this phone's own key; any other valid signature reads
      `SignedElsewhere` (review of `d4d09231`).
- [ ] A device check: a run on a phone reads "Verified"; a `.dat` edited with `adb`
      reads "Changed since analysis"; a restore on a second phone reads "Signed on
      another device".
- [ ] Backend: check a `SignedElsewhere` thumbprint against the account's registered
      devices (not decided).

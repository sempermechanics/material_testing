# Backup split — restore download saving

Restore used to download the whole `Session.zip` and unpack every entry. Most of that is
data a restored session never reads:

- **Derived deliverables** (`csv/`, `reports/`, `processed/`) are regenerated on export.
- **The deformed original photos** are never displayed — the viewer draws heatmaps over the
  reference image alone — so they are only needed when *exporting*, not when viewing.

The backup is now split so a restore fetches only what viewing a session needs — the
reference image and the `.dat` results — in `Session.zip`. Everything else (deformed
originals + derived deliverables) goes to `Extras.zip`, which a restore skips and "Save to
Files" merges back in.

## Measured saving

Deterministic from the archive structure (all binaries `STORED`, so on-wire size == on-disk
size), on a representative **50-frame batch** — reference 4 MB, per frame a 4 MB raw photo +
1.6 MB `.dat` + 0.5 MB PDF + 5×0.3 MB heatmaps, plus a 2 MB CSV:

| | bytes | of bundle |
|---|--:|--:|
| whole bundle (old restore) | 386 MB | 100% |
| **restore payload — reference + `dat/` (new)** | **~84 MB** | ~22% |
| deferred, not downloaded (deformed + derived) | ~302 MB | **~78% saved** |

So a restore of this session moves ~4.6× less data. The dominant term is the deformed
photos (~204 MB) now deferred; the derived deliverables (~102 MB) are the rest.

New (`schema/3`) backups get this by construction — `Session.zip` already excludes the
deformed images, so a restore just fetches that one small object. Backups **already in the
cloud** cannot separate the deformed images (their single archive interleaves them with the
reference under `raw/`), so a legacy restore still pulls the whole `raw/`+`dat/` prefix — the
~26% saving from dropping only the derived deliverables — via one ranged GET, with a
whole-archive fallback whenever the prefix cannot be established safely.

These are **derived** figures from realistic sizes. A live upload→restore round trip on the
deployed backend (the restore now logs `downloaded N of M backup bytes`, so the saving reads
straight out of logcat) is the confirming measurement, pending a backend deploy of the
`extras` role.

## Trade-off (intended)

A restored session has no local deformed photos, so its **on-device re-export** omits the
original-photos folder and its PDF report covers fall back to the reference image. This is
graceful — nothing crashes, and the code already tolerated a missing `raw_deformed/` (it is
what `dropLocalArtifacts` deletes to reclaim space). The full deliverable is never lost: it
stays in the cloud backup, and "Save to Files" downloads both objects and hands over one
complete archive exactly as before.

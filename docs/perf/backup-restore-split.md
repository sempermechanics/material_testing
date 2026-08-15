# Backup split — restore download saving

Restore used to download the whole `Session.zip` and unpack every entry, including data a
restored session never reads: the **derived deliverables** — `csv/`, `reports/` (1 PDF/
frame), `processed/` (5 heatmap PNGs/frame) — which are regenerated on export
(`SessionEverythingExporter`), never read back after a restore.

The backup is now split so a restore fetches only what's needed to fully rebuild a working
session — **every original image (reference + deformed) and the `.dat` results** — in
`Session.zip`. Only the derived deliverables go to `Extras.zip`, which a restore skips and
"Save to Files" merges back in, so the full downloadable archive is unchanged.

## Measured saving

Deterministic from the archive structure (all binaries `STORED`, so on-wire size == on-disk
size), on a representative **50-frame batch** — reference 4 MB, per frame a 4 MB raw photo +
1.6 MB `.dat` + 0.5 MB PDF + 5×0.3 MB heatmaps, plus a 2 MB CSV:

| | bytes | of bundle |
|---|--:|--:|
| whole bundle (old restore) | 386 MB | 100% |
| **restore payload — every `raw/` + `dat/` (new)** | **~284 MB** | ~74% |
| derived, not downloaded (`csv`/`reports`/`processed`) | ~102 MB | **~26% saved** |

New (`schema/3`) backups get this by construction — `Session.zip` already excludes the
derived block, so a restore just fetches that one object. Backups **already in the cloud**
cannot separate `raw/` from `dat/` and the derived roles inside their single legacy archive
by anything finer than a byte range, so a legacy restore range-reads the same `raw/`+`dat/`
prefix — the identical ~26% saving — via one ranged GET, with a whole-archive fallback
whenever the prefix cannot be established safely.

These are **derived** figures from realistic sizes. A live upload→restore round trip on the
deployed backend (the restore logs `downloaded N of M backup bytes`, so the saving reads
straight out of logcat) is the confirming measurement.

## Design note: deformed originals are restore-essential, not deferred

An earlier iteration additionally deferred the deformed original photos out of the restore
payload — they are never displayed (the viewer draws heatmaps over the reference alone), so
restoring only `reference.png` + `dat/` reached ~78% saved instead of ~26%. That version
shipped and was then **reverted**: a restored session's on-device re-export needs the
original photos to be complete (report covers, the raw-photos export folder), and losing
that fidelity on every restore was not an acceptable trade for the extra bandwidth. The
current design restores everything a local analysis run would have produced; only the
regenerable deliverables are deferred.

The reverted, more aggressive split remains a documented option if bandwidth ever becomes
the binding constraint again — see the `isRestoreEssential` history in
`SessionZip.kt` — but it needs an explicit product decision to re-enable, since it changes
what a restore actually gives the user.

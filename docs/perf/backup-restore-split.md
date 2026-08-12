# Backup split — restore download saving

Restore used to download the whole `Session.zip` and unpack every entry, but the
derived deliverables in it (`csv/`, `reports/`, `processed/`) are never read back after a
restore — they are regenerated on export. The backup is now split so a restore fetches
only `Session.zip` (raw images + `.dat` results); the deliverables live in a separate
`Extras.zip` that a restore skips and "Save to Files" merges back in.

## Measured saving

Deterministic from the archive structure (all binaries are `STORED`, so on-wire size ==
on-disk size), computed on a representative **50-frame batch** — reference 4 MB, per frame
a 4 MB raw photo + 1.6 MB `.dat` + 0.5 MB PDF + 5×0.3 MB heatmaps, plus a 2 MB CSV:

| | bytes | of bundle |
|---|--:|--:|
| whole bundle (old restore) | 386 MB | 100% |
| **restore payload — `raw/` + `dat/` (new)** | **284 MB** | **73.6%** |
| derived, no longer downloaded | 102 MB | **26.4% saved** |

So a restore of this session moves ~102 MB less. New (`schema/3`) backups fetch the small
`Session.zip` directly; backups already in the cloud get the same payload via one ranged
GET of the `raw/`+`dat/` prefix (plus a 512 KB tail read for the central directory), with a
whole-archive fallback whenever the prefix cannot be established safely.

These are **derived** figures from realistic sizes, not a live restore. A real
upload→restore round trip on the deployed backend (bytes + wall-clock, new and legacy
backup) is the confirming measurement and is pending a backend deploy of the `extras` role.

## The bigger lever, quantified (not taken here)

The raw deformed photos dominate what is retained (204 MB of the 284). The viewer never
displays a deformed image, so `raw_deformed/` is needed only for exports — deferring it
too (fetch `dat/` on restore, pull `raw/` on demand) would take the same session to:

| restore payload | bytes | saved |
|---|--:|--:|
| current split (`raw` + `dat`) | 284 MB | 26% |
| if `raw` also deferred (`dat` only) | 80 MB | **79%** |

That is the ~85% option discussed during planning. It is deliberately out of scope for this
change (it degrades export-without-network and is a larger behavioural shift), but the
split done here is the groundwork for it: adding a third archive later is a small step from
the two-archive layout now in place.

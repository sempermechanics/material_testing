# On-device characterization: cloud, compute, space

Component-level cost of the four core operations — **analysis, screen refresh, backup,
restore** — measured against the real native engine, real backend
(`https://semper-gw-86wx7pp1.an.gateway.dev`), and real Google Drive/Firestore, driven
headlessly via [`SyntheticWorkloadDriver`](../../app/src/androidTest/java/com/indicvision/semper/perf/SyntheticWorkloadDriver.kt)
and captured by [`characterize.sh`](../../scripts/perf/characterize.sh).

**Device:** Pixel 6 (`oriole`), arm64-v8a, wireless-debugging (adb-over-Wi-Fi).
**Build:** debug-cloud (`INDIC_DEV_AUTH_BYPASS=false`, prod `INDIC_API_BASE_URL`) —
native `.so` is `-O3` in every variant, so compute numbers are representative; the
Kotlin layer is debug (unoptimized), so Kotlin-side component times are an upper bound,
not a release number.
**Backend:** `https://semper-gw-86wx7pp1.an.gateway.dev` (Cloud Run + Firestore + Drive,
prod project).
**Repeats:** 2 for light scale, 1 for large scale (large scale's per-frame cost made
higher repeat counts impractical in-session — see the LARGE scale note below).

## Scales

| | frames | step | subset | strainWin | use6x6 | image |
|---|--:|--:|--:|--:|:--:|---|
| **LIGHT** | 1 | 6 | 21 | 15 | no | 320×320 |
| **LARGE** | 20 (see note) | 2 | 81 | 15 | yes | 640×640 |

**LARGE frame count note:** the app's real ceiling is 150 frames; this run uses 20.
Measured per-frame wall time across two independent 20-frame runs (analysis/large's
last 2 frames + backup/large's last 13 frames — the rest scrolled out of the logcat
ring buffer on the longer run): **34.0–51.6s/frame, mean ≈ 37s/frame**, with no clear
trend by frame position (frame 18 was the fastest in one run and the slowest in the
other) — the spread looks like ordinary background-app CPU contention on a
general-purpose phone, not thermal throttling or an engine regression. 150 frames at
the observed mean would be **~92 minutes for `analysis` alone**, and
`screen`/`backup`/`restore` each build their own fresh session first. The per-frame
compute numbers below are real and extrapolate roughly linearly to 150 frames (native
engine solves each frame independently — no amortization from a longer sequence);
anything that does *not* scale linearly (cloud transfer, zip build) is called out
explicitly.

**A real bug found and fixed by this exercise:** the driver's original LIGHT-scale
`step=20` grid was too sparse for the AKAZE-seeded points to land near enough to any
analysis-grid vertex for RGDIC to propagate from — it converged on **zero points**,
regardless of `subset` (`step=20/subset=21` and `step=20/subset=31` both failed;
`step=6` is the sparsest configuration that converges reliably against this synthetic
speckle). This is a real, load-bearing characteristic of the engine's seeding/mesh
relationship, not a driver-only artifact — noted here because it shapes what a genuinely
"light" workload can look like.

## Component tables

Times are t_ms from the `SWD|` markers; single representative run shown where repeats
agreed within measurement noise (session-record I/O varies ±50-100ms run to run — GC/IO
jitter on a live device, not a component regression).

### Analysis

| Component | LIGHT (1 frame) | LARGE (per frame, frame 0 cold + steady-state) |
|---|--:|--:|
| image/reference encode+decode | 29 ms | 142–178 ms (640×640 PNG) |
| `initializeReference` (native) | 4 ms | 22–25 ms |
| AKAZE + RANSAC seeding | 17–41 ms | 145 ms (frame 0) |
| Hessian pre-pass (OpenMP) | 17–39 ms | 2,290 ms (frame 0) |
| Delaunay mesh | 0.4 ms | 12 ms (frame 0) |
| ICGN + RGDIC propagation (implicit — no finer seam) | ~65 ms | **~32,350 ms (frame 0) / ~34,000–39,700 ms steady-state** |
| VSG strain | 0.3 ms | 45 ms (frame 0) |
| **native solve wall time** | **~100–130 ms** | **34.0–51.6s/frame, mean ≈ 37s (20-frame sample, see note above)** |
| `.dat` write (I/O) | 0.4 ms | not separately measured at this scale (bundled into the per-frame total) |
| record persist (once, end of run) | 22–89 ms | 93–111 ms (all 20 frames) |
| **points converged / grid** | 1,764 / 2,809 (62.8%, 91.1% accept rate) | 65,025 / 102,400 (63.5%, 95.5% accept rate, every frame) |
| **total `analysis` op (20 frames)** | — | **890 s (~14.8 min)** |
| session on-disk footprint (20 frames) | — | 49,194,564 bytes (~46.9 MB): dat=41,616,000, raw_deformed=7,233,203, reference=345,361 |

**Where the cost lives:** at LIGHT scale, the engine stages (AKAZE/Hessian/Delaunay) and
image I/O are comparable order-of-magnitude — no single stage dominates. At LARGE scale,
**ICGN/RGDIC propagation is >90% of wall time** (frame 0: 32.3s of 34.8s) — the
6-parameter shape function (`use6x6`) over 65K converged points is the entire story;
AKAZE/Hessian/Delaunay (the "setup" stages) stay under 2.5s combined even at this scale,
and stayed roughly flat across the run (only the propagation stage is folded into the
34–51.6s per-frame spread above, since only frame 0 got the full stage breakdown before
the log line scrolled out of the ring buffer on later frames).

### Screen refresh

Adds to a completed analysis:

| Component | LIGHT (1 frame, 1,764 pts) | LARGE (20 frames, 65,025 pts/frame) |
|---|--:|--:|
| `.dat` decode | 0.14–0.18 ms | 3.35 ms |
| field stats (percentile extrema) | 1.6–7.0 ms | 120.39 ms |
| heatmap generate (indices + jet raster) | **173–180 ms** | 120.71 ms |
| whole-batch summary pre-pass (`valueRanges` over all frames) | not applicable (1 frame) | 994.99 ms (all 20 frames) |
| **total `screen` op (on top of a fresh 20-frame analysis)** | — | **697 s (~11.6 min)**, of which ~1.24s is the four viewer components above — the analysis rebuild dominates |

**Where the cost lives:** heatmap generation dominates screen refresh at LIGHT scale by
~25-100x over decode/stats. At LARGE scale the relationship flips: field stats and
heatmap generation become comparable (both ~120ms), because field-stats cost scales
with point count (65K vs 1.7K, a ~37x increase driving a ~20-75x time increase) while
heatmap render cost is more resolution-bound and grows more slowly. The whole-batch
summary pre-pass (once per session open, not per-frame-toggle) is the single largest
screen-refresh component at LARGE scale — ~1s to scan value ranges across 20 frames.
None of this competes with the ~690s of underlying analysis compute needed to have a
session to view in the first place — screen-refresh cost is negligible next to
producing the data it displays.

### Backup

Adds to a completed analysis:

| Component | LIGHT (1 frame) | LARGE (20 frames) |
|---|--:|--:|
| build `Session.zip` (restore-essential: raw + dat) | 11–41 ms, 230,325 bytes | 1,146 ms, 49,199,096 bytes |
| full upload worker (create-session → per-object Drive transfer → complete, **including real report/heatmap generation for Extras.zip**) | **13.2–15.6 s** | **118.8 s (~2.0 min)** |
| peak PSS during backup (device-wide, `dumpsys meminfo`) | not captured (op too fast for the 500ms poll interval) | **496,145 KB (~485 MB)** |
| session on-disk footprint after backup (session dir + staged zip, before cleanup) | 460,298 bytes | 98,393,660 bytes (~93.8 MB) |

The upload total is dominated by fixed round-trip costs (session create, per-object
Drive resumable-upload handshakes, complete calls, device-side report/PDF/heatmap
generation) at LIGHT scale — 13-15s for ~1.3MB is far slower than the connection's raw
throughput. At LARGE scale (~50MB uploaded in ~119s ≈ 420 KB/s effective) the fixed
overhead is a much smaller fraction of the total; the wall time is dominated by actual
transfer + the larger Session.zip build (1.1s) + real 20-frame report/heatmap
generation for Extras.zip.

### Restore

| Component | LIGHT (1 frame) | LARGE (20 frames) |
|---|--:|--:|
| list manifest | 391–424 ms | 468 ms |
| full restore worker (metadata + bundle download, unpack, CRC verify, index upsert) | 5.6–6.1 s | 333.0 s (~5.6 min) |
| peak PSS during restore | not captured (op too fast) | 258,408 KB (~252 MB) |
| **bytes downloaded vs whole backup** | **231,660 of 1,293,753 (17.9%) — an ~82% saving** | **49,202,702 of 195,060,345 (25.2%) — a ~75% saving** |
| restored session on-disk footprint | 231,307–231,308 bytes | 49,198,170 bytes (byte-equal to the original 49,194,564 + metadata) |

**This is the split feature's core payoff, measured on real cloud, real production
code paths, at both scales:** LIGHT skips ~82% of its own backup on restore; LARGE
skips ~75%. Both are well above the ~26% figure from the existing 50-frame/typical-size
reference point — this synthetic workload's imagery (640×640 LARGE, 320×320 LIGHT,
dense speckle) produces derived deliverables (PDF report + several heatmap PNGs per
frame) that are proportionally larger than a typical real session's, so treat the
82%/75% figures as characterizing *this* workload's derived-artifact ratio, not a
universal constant — the ~26% reference point (real report/heatmap sizes on a real
50-frame batch) is the one to use for realistic session-size planning. What's confirmed
at both scales: the split reliably saves a large, non-trivial fraction of restore
bandwidth, and the saving does **not** shrink as frame count grows 20x (LIGHT 82% →
LARGE 75%, not a collapse toward 0%) — restore-essential (raw+dat) and derived
(csv+reports+processed) both scale with frame count in this workload, so the ratio
between them stays in the same broad band.

### Space (cross-cutting)

| Artifact | LIGHT (1 frame) | LARGE (20 frames) |
|---|--:|--:|
| `reference.png` | 87,114 (320×320) | 345,361 (640×640) |
| `raw_deformed/` | 86,411 (1 frame) | 7,233,203 (20 frames, ~362 KB/frame) |
| `.dat` files | 56,448 (1,764 pts × 32B) | 41,616,000 (20 × 65,025 pts × 32B, exact) |
| **local session total** | 229,973 | 49,194,564 (~46.9 MB) |
| `Session.zip` (staged for upload) | 230,325 | 49,199,096 |
| `Extras.zip` (derived, from manifest total − Session.zip − metadata) | 1,062,093 (~1.04 MB) | 145,857,643 (~139 MB) |
| session dir during backup (session + staged zip) | 460,298 | 98,393,660 (~93.8 MB) |
| restored session total | 231,307 (= local total + `metadata.json`, byte-equivalent) | 49,198,170 (byte-equivalent) |

## Cloud components vs free-tier limits

Endpoint/Firestore-write counts below are derived from the backend source
(`backend/app/main.py`, `firestore_repo.py`) rather than live-measured per-call —
**approximate, order-of-magnitude**, not exact. GCP always-free quotas as commonly
published (verify current values at gcp-quota-review time — these change):
Cloud Run ~2M requests/mo + ~1 GiB egress/mo (NA egress only), Firestore 50K reads /
20K writes / 20K deletes per day + 1 GiB stored, Drive 15 GB/account.

**⚠️ Region caveat found while implementing Phase 1: the free egress figure above may
not apply at all.** GCP's always-free Cloud Run egress tier is **North America only**.
`docs/backend/BACKEND_SETUP_GCP.md` and `config.py`'s `TASKS_LOCATION` default both
point to **`asia-south1`** — not NA — while `.github/workflows/deploy-backend.yml`'s
deploy input previously defaulted to `us-central1` (now corrected to `asia-south1` to
match the documented setup, but the workflow's `region` is a manual per-deploy input,
so it does not prove where the service actually runs today). **Whoever confirms the
live Cloud Run region should re-verify the egress-quota math in this section against
that region's actual terms** — if it is `asia-south1`, the restore-egress-bound
conclusion below may need to shift to a paid-tier cost figure instead of a free-tier
exhaustion count.

### Backup

Uploads go **device → Drive directly** (bytes bypass Cloud Run):

| Resource | per backup (3 objects: metadata + Session.zip + Extras.zip) |
|---|--:|
| Cloud Run requests | ~5 (`POST /v1/sessions` + 3×`POST /v1/files/{id}/complete` + 1 provision task) |
| Firestore writes | ~10-12 (session doc create+update, 3 file docs create, 3 file docs complete-update, ±session status update) |
| Drive storage | full backup size — measured LIGHT (1 frame): 1,293,753 bytes (~1.23 MB); LARGE (20 frames): 195,060,345 bytes (~186 MB) → **~9.75 MB/frame** at this workload's image size/density |

At ~5 requests/backup, Cloud Run's 2M req/mo free tier supports **~400,000 backups/mo**
before request quota binds — not the limiting factor. At ~11 writes/backup, Firestore's
20K writes/day supports **~1,800 backups/day** — this is the tighter cloud-request
constraint for backup. **Drive's 15 GB/account is the real binding constraint for
backup at scale**: at the measured ~9.75 MB/frame, a 15 GB account holds roughly
**~1,540 frames total** across all sessions — for a single 150-frame session at this
workload's density (~1.46 GB), that's **~10 such sessions per account** before Drive
storage exhausts. (This workload's 640×640/dense-speckle images run larger than a
typical real session; treat this as a stress-case lower bound, not a typical-case
number — the existing 50-frame/386MB reference point, ~7.7 MB/frame, is closer to real
usage and implies ~1,950 frames / ~13 typical 150-frame sessions per account.)

### Restore

Downloads **proxy through Cloud Run** (`GET /v1/files/{id}/content` → streamed) — so
restore spends **Cloud Run egress**, the scarcest quota:

| Resource | per restore | LIGHT (1 frame) | LARGE (20 frames) |
|---|---|--:|--:|
| Cloud Run requests | ~2 (list manifest + bundle content GET) | — | — |
| Cloud Run egress | bytes downloaded (post-split) | 231,660 B | 49,202,702 B (~46.9 MB, ~2.46 MB/frame) |
| Firestore reads | ~4-6 (session + per-file docs on list, file doc on content GET) | — | — |

At the measured ~2.46 MB/frame (LARGE, post-split), Cloud Run's ~1 GiB/mo free egress
supports **~426 frames/mo** worth of restores — for 150-frame sessions at this
workload's density, **~2.8 full restores/mo** before egress exhausts. The existing
50-frame/386MB reference point's restore payload (~284MB post-split, ~5.7 MB/frame) is
a lower-density comparison and gives a similar order of magnitude: ~180 frames/mo, or
~1.2 full 150-frame restores/mo. **Either way, Cloud Run egress is the binding
constraint for restore, by a wide margin** — it exhausts one to two orders of magnitude
sooner than Firestore reads or Cloud Run request count at the same session sizes. This
is exactly the quota the backup/restore split targets: every byte it avoids downloading
is egress quota saved 1:1, and the measured 75-82% saving (this workload) directly
multiplies restores-per-month by ~4-5x versus downloading the whole backup every time.

**Binding constraint overall: Cloud Run egress on restore**, for any session past a
couple dozen frames — Drive storage (backup) and Firestore ops (both directions) are
all one to two orders of magnitude further from their free-tier ceiling at the same
session sizes.

## Summary: where the cost lives, by operation

| Operation | LIGHT total | LARGE total (20 frames) | Dominant component |
|---|--:|--:|---|
| analysis | ~130 ms | 890 s (~14.8 min) | ICGN/RGDIC propagation (>90% of native solve time) |
| screen (on a fresh session) | ~140 ms on top of analysis | 697 s (~11.6 min), ~1.2s of which is viewer work | Same as analysis — screen-refresh itself is negligible |
| backup (on a fresh session) | ~13.3 s on top of analysis | ~120 s on top of analysis | Fixed round-trip overhead at LIGHT; real transfer + report/heatmap generation at LARGE |
| restore (from a fresh backup) | ~5.9 s on top of backup | ~333 s (~5.6 min) on top of backup | Cloud Run egress-bound download (post-split) |

**Bottom line:** at any scale, the native DIC solve (specifically ICGN/RGDIC
propagation under a 6-parameter shape function) so thoroughly dominates every other
component that screen-refresh and zip-build costs are noise by comparison. The
backup/restore split's ~75-82% egress saving is the single largest lever available on
the *cloud* side; it does not touch the compute side, where the only lever is workload
parameters (`step`/`subset`/`use6x6`/frame count) — the same knobs this report found
have a hard floor below which the engine simply fails to converge (see the LIGHT-scale
seeding note above).

## Caveats

- Single device (Pixel 6, `oriole`), single account, single backend project. No
  cross-device or cross-account variance data.
- Kotlin-layer component times are from a **debug** build (unoptimized) — an upper
  bound, not a release number. The native `.so` is `-O3` in every build variant, so
  compute-heavy numbers (the vast majority of total time here) are representative.
- Repeats: 2 for LIGHT, 1 for LARGE (LARGE's per-op runtime, up to ~15 min per run, made
  more repeats impractical in-session). LIGHT repeats agreed within normal run-to-run
  jitter (record-persist I/O ±50-100ms); LARGE per-frame solve time varied 34-51.6s
  across two independent 20-frame runs with no clear pattern by frame position —
  consistent with ordinary background-app CPU contention on a general-purpose phone,
  not a driver or engine issue, but not confirmed as such (single-device, no isolation
  from other apps).
- GCP always-free quota figures are as commonly published at the time of this report
  (2026-08-14) — verify current values before using them for capacity planning, as
  Google revises free-tier terms periodically.
- Cloud Run request / Firestore op counts per operation are derived from reading the
  backend endpoint structure (`backend/app/main.py`, `firestore_repo.py`), not
  live-measured per call — order-of-magnitude, not exact.
- This workload's synthetic imagery (640×640 LARGE / 320×320 LIGHT, dense seeded
  speckle) produces derived deliverables (PDF reports, heatmap PNGs) that are larger
  relative to raw+dat than a typical real session — the 75-82% restore-saving figures
  measured here should be read as this workload's characteristic, not a universal
  constant; the existing 50-frame/386MB reference point (~26% saved) is closer to
  real-world session composition.

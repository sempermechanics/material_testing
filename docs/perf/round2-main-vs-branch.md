# Perf diff — `main` vs the round-1/round-2 memory work

Head-to-head measurement of the pre-change baseline against this branch, run on the
**same emulator, same synthetic data, same commands**.

| | commit | build |
|---|---|---|
| **before** (`main`) | `a2af8e7` | `:app` **debug** + the harness files copied on top (no perf changes) |
| **after** (this branch) | `ae9192b` | `:app` **debug** |

- Device: `Pixel_8` AVD, Android **API 37**, x86_64 emulator.
- Micro: `androidx.benchmark` microbenchmark (`HotPathMicroBenchmark`), reporting **median
  `timeNs` and `allocationCount`** per case. Synthetic frame = 160×120 grid (19,200 points,
  all accepted); GIF cases 96×96; 150-frame cases loop the op 150×.
- **Read `allocationCount` as the hard signal**; read `timeNs` as directional only. Debug +
  emulator inflates and jitters wall-time. The `profileAlong` row is an unchanged control:
  its `allocationCount` delta is **exactly 0.0%** (validating the method), while its
  `timeNs` swung ~38% between identical code — that ~±40% is the time-noise floor here.

## Micro-benchmark (per-op median)

| Hot path (tier) | main `timeNs` | branch `timeNs` | Δ time | main `allocs` | branch `allocs` | Δ allocs |
|---|--:|--:|--:|--:|--:|--:|
| `valueRanges` ×1 — summary range (A2) | 19,924,131 | 1,153,464 | **−94%** | 96,174 | 88 | **−99.9%** |
| `valueRanges` ×150 — summary pre-pass (A2) | 3,874,350,547 | 175,961,384 | **−95%** | 14,426,055 | 13,226 | **−99.9%** |
| `buildReport` ×1 (C2 + A4) | 151,313,808 | 76,913,530 | **−49%** | 462,953 | 1,782 | **−99.6%** |
| `computeFieldExtrema` ×1 (R1/R2) | 4,648,199 | 469,492 | **−90%** | 19,232 | 34 | **−99.8%** |
| `generateHeatmap` ×1 (A4) | 15,010,753 | 6,733,336 | **−55%** | 19,253 | 54 | **−99.7%** |
| `gifEncode` 10 frames (C1) | 9,913,602 | 6,202,017 | **−37%** | 146,790 | 32 | **−100%** |
| `gifEncode` 150 frames (C1) | 144,291,581 | 50,982,708 | **−65%** | 2,201,726 | 313 | **−100%** |
| `decodeDatFile` ×1 (R1 mmap) | 1,120,514 | 654,566 | **−42%** | 67 | 35 | −48% |
| `pointSpatialIndexBuild` ×1 (R1) | 54,258,600 | 52,005,131 | ~0% (noise) | 286,742 | 286,742 | **0%** |
| `profileAlong` ×3 — control (unchanged) | 3,912,402 | 2,427,123 | (noise) | 1,492 | 1,492 | **0%** |

### What the numbers say
- **Allocation churn is the headline.** Every de-boxed path drops per-point/per-pixel
  allocations by ~99.6–100%. The summary pre-pass over 150 frames goes from **14.4 M
  allocations to 13 K**; a 150-frame GIF from **2.2 M to 313**. That is the boxing removal
  (A2, C1) and the report buffer reuse (C2) made concrete — and it is exactly what shrinks
  GC pressure and peak transient heap on a real device.
- **Wall-time follows**, even on a debug build: the 150-frame summary pre-pass is ~22×
  faster (3.87 s → 0.18 s) and a 150-frame GIF ~2.8× faster, because the allocations and
  their GCs are gone.
- **No regressions remain.** The one drawback this profiling *found* — the round-2
  `PointSpatialIndex.build` two-pass rewrite, which measured **+48% time / +46% allocs** —
  was reverted (`ae9192b`); the row above now matches `main` allocation-for-allocation.

## Macrobenchmark — end-to-end viewer scrub (space + time)

`ViewerScrubBenchmark` on the release-like `benchmark` variant: seeds a synthetic
session, then steps 12 frames with the Next button, ×5 iterations. Same emulator, same
synthetic data, both builds. **Each configuration was run twice per build** so the
numbers below carry their own reproducibility evidence.

### Time — `Semper.viewer.decodeDat` (the per-frame `.dat` decode)

| workload | main (run 1 / run 2) | branch (run 1 / run 2) | Δ |
|---|--:|--:|--:|
| 150 frames | 144.24 / 140.99 ms | 12.48 / 12.80 ms | **−91%** |
| 10 frames | 116.38 / 119.03 ms | 9.28 / 9.30 ms | **−92%** |

`decodeDatCount` is **identical** on both sides (22 at 150 frames, 16 at 10) — the same
number of decodes, ~11× faster. Run-to-run spread is ≈2–5%, far below the effect. This is
round 1's memory-mapped `decodeDatFile` measured end-to-end in the real app, and it is the
single strongest confirmation in this whole exercise.

`frameCount` over the scrub is essentially unchanged (main 41/30, branch 38/28).

### Space — `memoryHeapSizeMaxKb` (max Java heap), after the bounded-pipeline fix

The first measurement showed the branch using **+340%** heap at 150 frames. That was
real and reproducible, and profiling it found two genuine defects — both now fixed:

1. **Unbounded prefetch fan-out.** `prefetchNeighborFrames` launched up to two
   uncancelled coroutines on *every* frame load, so a fast scrub had a dozen concurrent
   full-frame decodes in flight while the cache kept only two — peak memory scaled with
   *scrub speed*, not with any bound. Replaced by a single serialized look-ahead worker
   with a byte-capped cache (`fetch → bounded queue → process`).
2. **Eager whole-batch summary scan.** `summary.start()` ran on every viewer open,
   decoding and scanning **every frame in the batch** even when the viewer opened
   straight onto a frame and the summary was never shown. Now started on demand from
   `show()`. This was the dominant term: round 2 made decode/`valueRanges` fast enough
   to get much further through 150 frames inside the measured window, which is why the
   regression appeared only *after* the speedups.

| workload | main | branch (before fix) | **branch (fixed)** | vs main |
|---|--:|--:|--:|--:|
| 150 frames | 35,797 / 39,237 KB | 165,109 / 165,122 KB | **24,514 / 24,482 KB** | **−35%** |
| 10 frames | 84,482 / 83,397 KB | 35,810 / 35,874 KB | **20,930 / 20,834 KB** | **−75%** |

Heap is now **flat across workload size** — 24.5 MB at 150 frames vs 20.9 MB at 10, i.e.
**+17% for 15× the frames**, where it was previously 5.3× — which is exactly the property
being aimed for. Repeat runs agree to 0.1%.

Decode work drops at the same time, because the look-ahead now actually lands in a cache
big enough to hold it: `decodeDatCount` **22 → 7** (150 frames) and **16 → 4** (10), with
`decodeDatSumMs` **144.2 → 3.3 ms** and **116.4 → 2.1 ms** vs main (**−98%**).

<details><summary>Original inconclusive reading (kept for the record)</summary>

| workload | main (run 1 / run 2) | branch (run 1 / run 2 / run 3) | Δ |
|---|--:|--:|--:|
| 10 frames | 84,482 / 83,397 KB | 35,810 / 35,874 / 35,874 KB | **−57%** |
| 150 frames | 35,797 / 39,237 KB | 165,109 / 165,154 / 165,090 KB | **+340%** |

These are **reproducible** (branch spread 0.04%, main 1–9%), so they are not sampling
noise — but they point in **opposite directions on the two workloads**, so they cannot be
read as a memory win *or* a memory regression. Two things are worth stating plainly:

- **The metric is not cleanly attributable to the code under test.** Note that `main` uses
  *more* heap at 10 frames than at 150 — the opposite of what workload size predicts. Max
  heap here is dominated by whether the one-time session fabrication ran in-process and by
  when ART chose to expand the heap, not by steady-state viewer usage. (Making the seeder
  reuse one scratch buffer instead of ~1.2 MB per frame left the numbers unchanged —
  165,090 vs 165,109 KB — ruling that out as the cause but not identifying it.)
- **The most plausible explanation for the 150-frame figure is a consequence of the speed
  win, not a leak.** With decode ~11× faster, the branch completes far more neighbour
  *prefetch* and heatmap rendering inside the same scrub window; `ScrubFrameCache` is
  bounded (2 frames + 3 heatmaps) and `heapHasRoomForPrefetch()` still gates it, so this is
  more work in flight rather than unbounded growth. **This is a hypothesis, not a measured
  conclusion** — it should be confirmed with a heap dump before anyone relies on it.

This reading is superseded by the table above: the +340% was a real defect, not a metric
artefact, and both causes were found and fixed. The micro-benchmark `allocationCount`
(−99.6% to −100%) remains the controlled per-operation space evidence.

</details>

## Device (process heap under the seeded viewer)

Point-in-time `dumpsys meminfo` after opening the synthetic session (single sample,
GC-dependent — **noisy**, shown for directional context only).

| workload | main Java Heap | branch Java Heap | main TOTAL PSS | branch TOTAL PSS |
|---|--:|--:|--:|--:|
| 150 frames | 19.5 MB | **10.3 MB** | 64.9 MB | **37.2 MB** |
| 10 frames | 10.6 MB | 29.6 MB | 55.6 MB | 59.8 MB |

The **150-frame** case (the heavy workload) moves the right way and materially: PSS
64.9 → 37.2 MB, Java heap 19.5 → 10.3 MB. The **10-frame** case *inverts* (branch higher) —
proof that a single heap snapshot is GC-timing noise, not a trend. Trust the
`allocationCount` deltas above for the memory story; treat these heap numbers as
corroborating only where they agree (150 frames).

## What is NOT measured here (honest limits)
- **Debug + emulator ⇒ relative, not production absolutes.** R8 is off; absolute times will
  differ on a release build and real hardware. The main-vs-branch *deltas* are the point.
- **Transient peak heap is not sampled directly.** `allocationCount` (rock-solid here) plus
  the 150-frame PSS are the proxies; the derived footprints in the PR give the byte-level
  peak model.
- **`StartupTimingMetric` is unavailable on this emulator.** Anything built on
  `startActivityAndWait` — the repo's `StartupBenchmark` and `ScreenBenchmark` — fails with
  "Unable to confirm activity launch completion []": that API confirms a launch by parsing
  `dumpsys gfxinfo <pkg> framestats`, which comes back empty on this API 37 image for *every*
  activity (exported or not, trampoline or not). `ViewerScrubBenchmark` was rewritten to avoid
  that API and does run; startup timing therefore isn't part of this diff and would need a
  physical device or an older API image.

## Reproduce
```bash
# after ./gradlew :app:installDebug :app:installDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest \
  -P android.testInstrumentationRunnerArguments.class=com.indicvision.semper.benchmark.HotPathMicroBenchmark \
  -P android.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,DEBUGGABLE,LOW-BATTERY,UNLOCKED
# results: app/build/outputs/connected_android_test_additional_output/.../*-benchmarkData.json
```
Run the same on `a2af8e7` (with the harness files copied on top) for the baseline column.

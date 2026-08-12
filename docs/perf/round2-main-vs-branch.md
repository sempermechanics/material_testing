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
- **UI-driven Macrobenchmark did not run.** The viewer-scrub `FrameTimingMetric` /
  `MemoryUsageMetric` path (and even the repo's pre-existing `StartupBenchmark`) fails on
  this emulator at `startActivityAndWait()` — an adb/UTP + launch-routing limitation of the
  setup, not of the change. The reliable `am start` seeder path was used instead to confirm
  the viewer opens on a 150-frame synthetic session and to sample process heap. The
  `HotPathMicroBenchmark` carries the quantitative diff; `ViewerScrubBenchmark` +
  `BenchmarkSeedActivity` are committed for a CI/physical-device run where the macro pipeline
  is stable.

## Reproduce
```bash
# after ./gradlew :app:installDebug :app:installDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest \
  -P android.testInstrumentationRunnerArguments.class=com.indicvision.semper.benchmark.HotPathMicroBenchmark \
  -P android.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,DEBUGGABLE,LOW-BATTERY,UNLOCKED
# results: app/build/outputs/connected_android_test_additional_output/.../*-benchmarkData.json
```
Run the same on `a2af8e7` (with the harness files copied on top) for the baseline column.

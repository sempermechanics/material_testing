# Galaxy S22+ (`g0q`) benchmark baseline, 2026-10-27

From 2026-10-27 the benchmark phone is a Samsung Galaxy S22+ (SM-S906U1, `Build.DEVICE`
`g0q`, Android 16 / API 36, arm64-v8a, wireless adb); before that it was the Pixel 6
(`oriole`). This run gave `g0q` its references in
[`benchmark/gates.json`](../../benchmark/gates.json) ([TESTING.md, Real-device gates](../app/TESTING.md#real-device-gates)).
Numbers from the two phones are not comparable.

## State

- Build: `main` @ `7cf205af`, engine `f072da0` (v0.6.1), `-PabiFilters=arm64-v8a`:
  `app-benchmark.apk` (R8, not debuggable) for the macro suite, `app-debug.apk` and
  its androidTest APK for the micro suite.
- App data: a fresh install of `com.sempermechanics.semper` (none was on the phone);
  signed out, diagnostics off (the default).
- On AC. Every class waited for 180 s at thermal status 0 before it started, and every
  macro test recorded status 0 on AC at start and end (`*deviceState.json`). Battery
  34 °C.

## Macro (`:benchmark`), medians

| Test | Metric | Reference run | Earlier runs | Gate (`+30 %`) |
|---|---|---:|---:|---:|
| `StartupBenchmark.coldStartup` | TTID ms | 375.3 | 350.6, 382.1 | 487.5 |
| `StartupBenchmark.warmStartup` | TTID ms | 108.4 | 98.7, 114.8 | 140.4 |
| `ScreenBenchmark.settingsColdStartup` | TTID ms | 344.8 | 354.5 | 448.5 |
| `ScreenBenchmark.analysisWizardColdStartup` | TTID ms | 387.7 | 404.1 | 504.4 |
| `ScreenBenchmark.settingsScroll` | frame CPU P90 ms | 9.8 | | 12.7 |
| `ViewerScrubBenchmark.scrub150Frames` | frame CPU P90 ms | 10.1 | | 13.1 |
| `ViewerScrubBenchmark.scrub150Frames` | max heap KB | 22,812 | | 29,653 |
| `ViewerScrubBenchmark.scrub10Frames` | max heap KB | 19,232 | | 24,999 |
| `ViewerScrubBenchmark.scrub150FramesWithPhotos` | frame CPU P90 ms / max heap KB | 10.1 / 24,564 | | report-only |

The earlier runs are the same build on the same day, whose JSON was lost to the pull
problem below; their medians come from the instrument output. Cold starts moved up to
9 % between runs and the warm start up to 16 %, all inside the 30 % margin.

Viewer `.dat` decodes: `scrub10Frames` 4 in 8.4 ms, `scrub150Frames` 7 in 13.7 ms,
`scrub150FramesWithPhotos` 7 in 12.5 ms. That is the same decode count as the Pixel 6
(4 and 7). The viewer split (#457) and TD-141's deformed exports (#435) are in this build,
and both scrub tests pass.

## Micro (`HotPathMicroBenchmark`), medians

Debuggable and not AOT-compiled (suppressed `DEBUGGABLE`, `NOT-AOT-COMPILED`), method
tracing off. These are relative numbers for comparing builds on this phone, not gates.

| Benchmark | ms | Allocations |
|---|---:|---:|
| `buildReport_oneFrame` | 123.3 | 1,963 |
| `computeFieldExtrema_oneFrame` | 1.70 | 2 |
| `decodeDatFile_oneFrame` | 0.376 | 54 |
| `generateDeformedHeatmap_oneFrame` | 96.8 | 22 |
| `generateHeatmap_oneFrame` | 10.1 | 17 |
| `gifEncode_10frames` | 16.5 | 30 |
| `gifEncode_150frames` | 221.3 | 312 |
| `pointSpatialIndexBuild_oneFrame` | 83.9 | 286,740 |
| `profileAlong_threeComponents` | 3.18 | 1,492 |
| `valueRanges_oneFrame` | 7.04 | 20 |
| `valueRanges_150frames` | 1,213 | 3,007 |

## How it was run

One macro class per `am instrument` call, because each call overwrites
`*benchmarkData.json` and `*deviceState.json`. Install `app-benchmark.apk` and
`benchmark-benchmark.apk`, then for each of `StartupBenchmark`, `ScreenBenchmark` and
`ViewerScrubBenchmark`:

```bash
adb shell "rm -rf /sdcard/Android/media/com.sempermechanics.semper.benchmark/*"
adb shell am instrument -w -e class com.sempermechanics.semper.benchmark.<Class> \
  -e androidx.benchmark.suppressErrors EMULATOR,LOW-BATTERY,UNLOCKED \
  com.sempermechanics.semper.benchmark/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/media/com.sempermechanics.semper.benchmark/. <out>/<Class>
```

Wait for thermal status 0 on AC before each call (`adb shell dumpsys thermalservice`).
The micro suite is the `am instrument` command in [TESTING.md](../app/TESTING.md#performance-benchmarks).
Its JSON lands in `/sdcard/Android/media/com.sempermechanics.semper`, not the `.test` folder.
Then check the gates with `python scripts/ci_test_report.py --gates benchmark/gates.json <out>/*`.

**Git Bash rewrites `/sdcard/...` into `C:/Program Files/Git/sdcard/...`** for `adb pull`
and `adb shell rm`, which then fail. Set `MSYS_NO_PATHCONV=1`, and give `adb pull`
a Windows path as the local folder (`cygpath -w`), since it no longer converts `/c/...` either.

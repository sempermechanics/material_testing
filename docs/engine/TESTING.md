# Engine tests — moved

The C++ test suite (`dic_tests`) lives with the engine source in
[`sempermechanics/semper-dic-engine`](https://github.com/sempermechanics/semper-dic-engine),
linked here as a pinned submodule at `engine/`.

**Read the canonical document at [`engine/docs/TESTING.md`](../../engine/docs/TESTING.md)**
for the test catalog and the numeric tolerance contract.

## Running them from this checkout

```bash
git submodule update --init --recursive
cmake -S engine/tests -B build/engine-tests -DCMAKE_BUILD_TYPE=Release
cmake --build build/engine-tests -j
./build/engine-tests/dic_tests
```

## Where engine tests run in CI

Host builds, the DICe reference comparisons, AddressSanitizer/UndefinedBehaviorSanitizer
and ThreadSanitizer suites all run in the engine repo's own CI, gating the tags
this repo pins to. This repo's `ci.yml` checks the pin is one of those tags
(`engine-pin`, `scripts/check_engine_pin.py`: a `v*` tag, never backwards) and
reruns the host suite under ASan+UBSan at the pinned commit
(`engine-host-sanitized`, the engine CI's asan-ubsan configuration with system
OpenCV) whenever Tier 3 runs. TSan, the C SDK smoke and the JNI adapter under a
sanitizer run nowhere here.

**Where the numeric goldens are compared.** `GoldenCorpus` (the 4×4 and 6×6
corpora) and `FullFieldGolden.CaptureOrCompare` compare against their stored
values only in an unsanitized Release host build: under a sanitizer
`engine/tests/CMakeLists.txt` defines `SEMPER_SANITIZER_BUILD` and the three
skip (`engine/tests/integration/test_golden_corpus.cpp`,
`test_full_field_golden.cpp`; the log says "skipped under sanitizer/coverage").
So `engine-host-sanitized` passes without comparing them. They compare in the
engine repo's CI, job **`Host tests`** of its `.github/workflows/ci.yml` (for
v0.6.1, `f072da0`: run 39769086078, job 119791794771, 0 value mismatches), and
`engine-pin` requires that job green on the pinned commit (TE2-102): the latest
completed run of that workflow on the pin, read with `gh api`. The DICe
tolerance tests (`DiceFieldAgreement`, `DiceIncremental`, `DiceStrain*`,
`DiceTranslation*`) do run in `engine-host-sanitized`.

This repo's CI verifies that the pinned submodule still *links and behaves*:
the arm64-v8a release build (R8, signed) in tier 5, and the x86_64 emulator run
in tier 3, where `EnginePipelineSmokeTest` drives the solver through the JNI.
Bumping the submodule pointer (the JNI adapter, `engine/adapters/android/`,
is in it) — or changing `SemperNativeLib.kt` — triggers both, on the PR and again on the push to
`main`. Tier 1 is the JVM suite only; it builds no native code. See
[../ops/CI.md](../ops/CI.md).

**What catches a native solver change.** The app's bit-exact `.dat` and GIF
oracles (`DatFieldOracleTest`, `SummaryGifOracleTest`,
[app/TESTING.md](../app/TESTING.md)) run on the JVM over committed input fields:
they pin how the app writes and renders a field, and never call the solver. A
change in the native solver's output is caught only by the engine's numeric
goldens (`Host tests`, above), the determinism tests, and
`EnginePipelineSmokeTest` through the JNI (Tier 3, and the arm64 device run
below).

## Compiler flags and FMA contraction, per ABI

No build file sets `-ffp-contract` or `-march`; the effective contraction comes
from `-ffast-math` and the target (readiness TE2-101, checked 2026-10-28 at
engine v0.6.1). Every engine target compiles `-O3 -ffast-math`
(`engine/CMakeLists.txt`: `semper_math` and `semper_pipeline`, plus `-flto
-fopenmp` on Android; `engine/adapters/android/CMakeLists.txt`: the JNI
library), the same for the debug x86_64 and release arm64 builds; Gradle passes
only `-DSEMPER_ANDROID=ON` and the ccache switch. The host suite builds with GCC
`-O2 -ffast-math` (`engine/tests/CMakeLists.txt`; MSVC `/O2 /fp:fast`). Under
clang (NDK) and GCC, `-ffast-math` means `-ffp-contract=fast`: a multiply and an
add may become one fused multiply-add wherever the target has one.

| Build | Where it runs | Engine SIMD reductions (`cv::v_fma` in `engine/include/semper/simd.hpp`) | Scalar `a*b+c` |
|---|---|---|---|
| Android arm64-v8a (release, shipped) | phones; benchmarks on the Galaxy S22+ | **fused**: `vfmaq_f32` (OpenCV `intrin_neon.hpp`, under `CV_SIMD128_64F`) | **fused** (`fmadd`, contraction fast) |
| Android x86_64 (Tier 3 emulator) | CI's emulator | unfused: `_mm_mul_ps` + `_mm_add_ps`, since the OpenCV baseline is SSE–SSE3 and `CV_FMA3` is off | unfused: baseline x86_64 has no FMA instruction |
| Host x86_64, GCC (engine repo `Host tests`, this repo's `engine-host-sanitized`) | CI runners | unfused (no `-mfma`, so no `CV_FMA3`) | unfused, for the same reason |

OpenCV's own functions may still pick AVX2/FMA kernels at run time on an x86_64
CPU that has them (its dispatched code); the table is about the engine's code.

**Consequence.** Fused and unfused results differ in the last bits, so the
arm64 build that ships is the one build CI never computes: the golden corpora
compare on host x86_64, the determinism fixture and `EnginePipelineSmokeTest`
run on the x86_64 emulator, and Tier 5 only builds and inspects the arm64 APK.
arm64 numbers are not compared bit-for-bit anywhere in CI; they are held to the
tolerance contract in `engine/docs/TESTING.md` (`-ffast-math` stays by
ADDENDUM-1 of [the readiness rubric](../ops/readiness/RUBRIC_ADDENDA.md)). The check that covers arm64 is a device
run: `EnginePipelineSmokeTest` on one arm64 phone or arm64 AVD before each
release (step 6 below, [RELEASING.md](../ops/RELEASING.md) step 5), whose field
must sit inside that contract.

Recorded arm64 device runs (`EnginePipelineSmokeTest`, debug build,
`-PabiFilters=arm64-v8a`, live-services classes excluded with `notTestFile`):

| Date | Device | ABI / Android | Engine pin, app commit | Result |
|---|---|---|---|---|
| 2026-10-28 | Galaxy S22+ (SM-S906U1, `g0q`) | `arm64-v8a` (`ro.product.cpu.abi`), Android 16 / API 36 | v0.6.1, `1b7541cf` (readiness TE2-003 / TE2-101) | 12 / 12 passed, twice; `repeatSolveIsBitIdentical` passed in both runs, as did the known-warp cases (translation, rotation, skew), the contrast-change and blurred-frame cases, the error codes, cancel and the batch loop |

## Bumping the engine pin

The bump PR does all of this:

1. Pin a `v*` tag of the engine repo that descends from the current pin. CI's
   `engine-pin` job fails otherwise; a deliberate downgrade carries the PR label
   `engine-downgrade`.
2. Find the engine CI run on the tag's commit (`gh run list -R
   sempermechanics/semper-dic-engine --commit <sha>`); it must be green.
   `engine-pin` fails unless its `Host tests` job (the numeric goldens) passed.
3. Add the pin's rows to [VALIDATION.md](VALIDATION.md): the DICe rows from that
   run, and the DIC Challenge and real-data rows, run by hand or marked
   **Not recorded**.
4. Let Tier 3 (`EnginePipelineSmokeTest`, x86_64), Tier 5 (arm64 release build)
   and `engine-host-sanitized` pass on the PR; a gitlink change triggers all three.
5. If numbers move, declare it against [ENGINE_APP_CONTRACT.md](ENGINE_APP_CONTRACT.md)
   (below).
6. Before the next release, run `EnginePipelineSmokeTest` on one arm64 device or
   arm64 AVD ([RELEASING.md](../ops/RELEASING.md) step 5): no CI job runs the
   solver on arm64.

## Changing engine behavior

If a change legitimately moves numeric results, it must be declared against the
stability tiers in [ENGINE_APP_CONTRACT.md](ENGINE_APP_CONTRACT.md) and the
tolerance contract in the engine repo updated in the same change.

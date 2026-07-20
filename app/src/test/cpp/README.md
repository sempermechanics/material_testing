# Native engine tests

Host-side C++ tests for the DIC engine. They compile the production sources
directly — no test doubles — and run on any desktop, no device or emulator.

## Layout

| Directory | What lives here | Where "correct" comes from |
|---|---|---|
| `unit/` | One component in isolation — SIMD kernels, interpolation, subset precompute, strain | A double-precision oracle or a mathematical identity |
| `integration/` | The assembled engine end-to-end, plus robustness and failure cases | Analytic synthetic deformation (exact by construction) |
| `dice/` | Measured against **DICe**, an established reference implementation | DICe's published contracts, and DICe's own solved fields |
| `perf/` | Throughput, printed for tracking | Nothing asserted but a hang ceiling |
| `framework/` | The harness: micro test framework, synthetic speckle, fixture image loading | — |
| `fixtures/dice/` | DICe's images and solved fields (BSD-3, see `LICENSE.DICe`) | — |
| `shim/` | Host stand-ins for `<android/log.h>` and OpenCV config headers | — |

## Running

```bash
cmake -S app/src/test/cpp -B build/native-tests -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-tests
./build/native-tests/dic_tests              # everything
./build/native-tests/dic_tests Engine       # one suite
./build/native-tests/dic_tests Engine.PureTranslation_Subpixel
```

Dependencies come from the git submodules (`git submodule update --init`):
Eigen and OpenCV's header-only universal intrinsics.

**OpenCV is optional.** It is used only to decode fixture images, so the
`dice/` image-backed tests need it; everything else builds with just a C++17
compiler. CMake prints which mode it picked. On Debian/Ubuntu:
`sudo apt-get install libopencv-dev`.

## Conventions

- `TEST_CASE(Suite, Name)` registers a test; use `CHECK`, `REQUIRE`,
  `CHECK_NEAR`, `CHECK_REL` (see `framework/test_framework.h`).
- Build engine ground truth with `SpeckleField` + `AffineDeformation`
  (`framework/synthetic.h`) — never by resampling images, and never with a warp
  that disagrees with the engine's shape-function convention.
- Tolerances should come from a documented source (a published contract, a
  measured spread, or float precision) — not from a guess.
- Document *why a test exists* in `docs/engine/TESTING.md`. A test whose
  failure nobody can interpret is a liability.

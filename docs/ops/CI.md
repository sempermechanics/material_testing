# CI — what runs on every push

Defined in [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml). Four
jobs run **in parallel** on every push to `main`, `damodar`, and
`feature/hybrid-delaunay-dic`, and on every pull request. A new push to the
same branch cancels the previous run.

| Job | What it proves | Typical time |
|---|---|---|
| **Native engine tests** | The engine math is right — the host CMake suite (`unit/` + `integration/` + DICe fixtures) | ~2 min |
| **Sanitized (address,undefined)** | No out-of-bounds, use-after-free, or undefined behavior | ~5 min |
| **Sanitized (thread)** | No data races in the parallel solve (`Robustness.ConcurrentSolves` is the workload) | ~5 min |
| **Kotlin compile + unit tests** | Kotlin builds, JVM tests pass, style/static-analysis gates hold, R8 shrinking works | ~5 min |
| **Android native build (arm64-v8a)** | The engine compiles and links for the ABI users actually get | up to 90 min |

## Reproducing a failure locally

Each job maps to a command you can run yourself:

```bash
# Native engine tests
cmake -S app/src/test/cpp -B build/native-tests -DCMAKE_BUILD_TYPE=Release -DDIC_REQUIRE_OPENCV=ON
cmake --build build/native-tests -j && ./build/native-tests/dic_tests

# Sanitizers (swap for -DDIC_SANITIZER=thread)
cmake -S app/src/test/cpp -B build/san -DCMAKE_BUILD_TYPE=Release -DDIC_SANITIZER=address,undefined
cmake --build build/san -j && ./build/san/dic_tests

# Kotlin job, in order
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
./gradlew spotlessCheck :app:detekt :app:lintDebug
./gradlew :app:minifyReleaseWithR8

# Native build job
./gradlew :app:assembleDebug -PabiFilters=arm64-v8a
```

## Things that surprise people

**Only arm64-v8a is built.** Every phone from 2022 on is 64-bit ARM, so that
is the only ABI the app ships — CI builds exactly what users get. Build for an
emulator locally with `-PabiFilters=x86_64`.

**The sanitizer jobs deliberately skip OpenCV.** Without it, the image-backed
DICe tests compile out. Linking an uninstrumented third-party library into a
sanitized build produces leak reports and thread-pool noise from inside OpenCV
— flaky red CI for no gain, since these sanitizers exist to find bugs in *our*
engine and the synthetic suites cover the same code paths.

**The main native-test job forces `DIC_REQUIRE_OPENCV=ON`.** If OpenCV were
missing, the image-backed tests would silently compile out and the job would
still pass — green CI with the most valuable coverage gone. Configure fails
instead.

**Only *new* lint and detekt findings fail.** Existing ones are frozen in
`detekt-baseline.xml` and `lint-baseline.xml`. Fix formatting with
`./gradlew spotlessApply`.

**Wrapper validation is off in the native job only.** It checks out
submodules, and OpenCV's repo bundles its own ancient `gradle-wrapper.jar`
files that fail checksum validation. Our wrapper is validated by the Kotlin
job, which has no submodules.

## Caching

The slow parts are cached, so a repeat run is much faster than a cold one:

- `app/.cxx` — the from-source OpenCV build, keyed on the CMake files, C++
  sources, and `.gitmodules`.
- `~/.gradle` — dependencies. Writable from `main` and `damodar` only; PR runs
  read the cache without churning it.
- `~/apt-cache` — the `libopencv-dev` `.deb` archives (~100 MB).

## What is not in CI

- **Instrumented/emulator tests** — run locally; see
  [TESTING.md](../engine/TESTING.md).
- **Signed release builds** — the signing config is not in CI; see
  [RELEASING.md](RELEASING.md).
- **The backend** — `backend/` is deployed from source with `gcloud run
  deploy`; see [BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md).

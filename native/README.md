# native/ — portable DIC engine

On-device Digital Image Correlation (DIC) library used by the Android app.
Cloud sync is separate (`backend/`); this package never talks to the network.

## Layout

```
native/
├── include/semper/     # public API headers
├── src/
│   ├── math/                # Image, SubsetPrecomputer, OptimizationEngine
│   ├── strain/              # VSG / NLVC
│   ├── io/                  # OpenCV decode helpers
│   ├── seeding/             # AKAZE + RANSAC
│   ├── pipeline/            # full-field orchestration (Path A/B/C)
│   └── util/log.hpp         # portable LOGD / LOGE
├── adapters/android/        # JNI only → libsemper_core.so
├── third_party/{eigen,opencv}
├── cmake/                   # OpenCV + option helpers
└── tests/                   # host suite (no NDK)
```

## Contributor rules

| Change | Where |
|---|---|
| ICGN / subsets / interpolation / strain math | `src/math`, `src/strain` + `tests/unit` |
| AKAZE seeding / mesh / RGDIC orchestration | `src/seeding`, `src/pipeline` |
| JNI method signatures / ByteBuffer marshalling | `adapters/android` + `SemperNativeLib.kt` |

Never put `#include <jni.h>` or `<android/*>` under `src/math`, `src/strain`, or `include/semper`.

## Build

**Host tests** (from repo root):

```bash
cmake -S native/tests -B build/native-tests -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-tests
./build/native-tests/dic_tests
```

**Android**: Gradle points at `native/CMakeLists.txt` with `-DSEMPER_ANDROID=ON`.
The shared library name stays `semper_core` (`System.loadLibrary`).

**Submodules** (after clone):

```bash
git submodule update --init --recursive
./scripts/sparse-opencv.sh    # or scripts/sparse-opencv.ps1 on Windows
```

## CMake targets

| Target | Role |
|---|---|
| `semper_math` | Portable math (no JNI / Android) |
| `semper_pipeline` | Seeding + full-field (OpenCV + OpenMP) |
| `semper_android` | JNI adapter (`OUTPUT_NAME semper_core`) |

See [docs/engine/ARCHITECTURE.md](../docs/engine/ARCHITECTURE.md).

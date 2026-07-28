# native/ — portable DIC engine

On-device Digital Image Correlation (DIC) library used by the Android app.
Cloud sync is separate (`backend/`); this package never talks to the network.

## Layout

```
native/
├── include/indicvision/     # public API headers
├── src/
│   ├── math/                # Image, SubsetPrecomputer, OptimizationEngine
│   ├── strain/              # VSG / NLVC
│   ├── io/                  # OpenCV decode helpers
│   ├── seeding/             # AKAZE + RANSAC
│   ├── pipeline/            # full-field orchestration (Path A/B/C)
│   └── util/log.hpp         # portable LOGD / LOGE
├── adapters/android/        # JNI only → libindicvision_core.so
├── third_party/{eigen,opencv}
├── cmake/                   # OpenCV + option helpers
└── tests/                   # host suite (no NDK)
```

## Contributor rules

| Change | Where |
|---|---|
| ICGN / subsets / interpolation / strain math | `src/math`, `src/strain` + `tests/unit` |
| AKAZE seeding / mesh / RGDIC orchestration | `src/seeding`, `src/pipeline` |
| JNI method signatures / ByteBuffer marshalling | `adapters/android` + `IndicVisionNativeLib.kt` |

Never put `#include <jni.h>` or `<android/*>` under `src/math`, `src/strain`, or `include/indicvision`.

## Build

**Host tests** (from repo root):

```bash
cmake -S native/tests -B build/native-tests -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-tests
./build/native-tests/dic_tests
```

**Android**: Gradle points at `native/CMakeLists.txt` with `-DINDICVISION_ANDROID=ON`.
The shared library name stays `indicvision_core` (`System.loadLibrary`).

**Submodules** (after clone):

```bash
git submodule update --init --recursive
./scripts/sparse-opencv.sh    # or scripts/sparse-opencv.ps1 on Windows
```

## CMake targets

| Target | Role |
|---|---|
| `indicvision_math` | Portable math (no JNI / Android) |
| `indicvision_pipeline` | Seeding + full-field (OpenCV + OpenMP) |
| `indicvision_android` | JNI adapter (`OUTPUT_NAME indicvision_core`) |

See [docs/engine/ARCHITECTURE.md](../docs/engine/ARCHITECTURE.md).

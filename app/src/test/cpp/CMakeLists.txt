# =====================================================================
# HOST-SIDE NATIVE TEST SUITE
#
# Builds the DIC engine sources UNMODIFIED for the host machine
# (Windows/Linux/macOS) and links them with the micro test framework.
# Android's <android/log.h> is satisfied by the shim/ directory.
#
# Build & run (out of the source tree, from the repo root):
#   cmake -S app/src/test/cpp -B build/native-tests -DCMAKE_BUILD_TYPE=Release
#   cmake --build build/native-tests
#   ./build/native-tests/dic_tests          (all)
#   ./build/native-tests/dic_tests Engine   (one suite)
#
# See README.md for the layout and docs/engine/TESTING.md for the test catalog.
# =====================================================================
cmake_minimum_required(VERSION 3.16)
project(indicvision_tests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ENGINE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)

# Mirror the production optimization flags so tests exercise the same
# floating-point behaviour that ships (notably -ffast-math).
if (NOT MSVC)
    add_compile_options(-O2 -ffast-math)
else ()
    add_compile_options(/O2 /fp:fast)
endif ()

# Optional sanitizer build: -DDIC_SANITIZER=address,undefined  or  thread
# CI runs both; the thread build turns the Robustness concurrency test
# into a data-race detector for the shared read-only solve path.
set(DIC_SANITIZER "" CACHE STRING "Comma-separated sanitizers (address,undefined,thread) or empty")
if (DIC_SANITIZER AND NOT MSVC)
    add_compile_options(-fsanitize=${DIC_SANITIZER} -g -fno-omit-frame-pointer)
    add_link_options(-fsanitize=${DIC_SANITIZER})
endif ()

# Test sources are grouped by scope (see docs/TESTING.md):
#   unit/        — one engine component in isolation, checked against a
#                  double-precision oracle or a mathematical identity.
#   integration/ — the assembled engine end-to-end (full ICGN solve) plus
#                  robustness/failure and concurrency behaviour.
#   dice/        — measured against DICe, the external reference implementation.
#   perf/        — throughput, printed for tracking.
# See README.md for the full layout.
set(DIC_UNIT_TESTS
        unit/test_simd_kernels.cpp
        unit/test_image.cpp
        unit/test_subset_precomputer.cpp
        unit/test_strain_calculator.cpp
)
set(DIC_INTEGRATION_TESTS
        integration/test_optimization_engine.cpp
        integration/test_robustness.cpp
)

# dice/ — measured against DICe, the external reference implementation. All of
# these need OpenCV to decode the fixtures except test_translation_synthetic.
set(DIC_DICE_TESTS
        dice/test_translation_synthetic.cpp   # DICe's 0.4 px contract, synthetic speckle
        dice/test_translation_real_image.cpp  # the same contract, DICe's real speckle
        dice/test_strain_gradients.cpp        # prescribed strain, raw ICGN gradients
        dice/test_strain_vsg.cpp              # prescribed strain, through the VSG pipeline
        dice/test_field_agreement.cpp         # our field vs DICe's, incl. load-step ladder
)

# perf/ mirrors DICe's tests/performance tier: throughput is reported for
# tracking, guarded only against catastrophic regressions (CI timing is noisy).
set(DIC_PERF_TESTS
        perf/test_throughput.cpp
)

# Production sources under test — compiled as-is, no test doubles.
set(DIC_ENGINE_SOURCES
        ${ENGINE_DIR}/preprocessing/ImageProcessor.cpp
        ${ENGINE_DIR}/preprocessing/SubsetPrecomputer.cpp
        ${ENGINE_DIR}/core/OptimizationEngine.cpp
        ${ENGINE_DIR}/postprocessing/StrainCalculator.cpp
)

add_executable(dic_tests
        test_main.cpp                # micro-framework runner entry point
        ${DIC_UNIT_TESTS}
        ${DIC_INTEGRATION_TESTS}
        ${DIC_DICE_TESTS}
        ${DIC_PERF_TESTS}
        ${DIC_ENGINE_SOURCES}
)

target_include_directories(dic_tests PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}            # framework/
        ${CMAKE_CURRENT_SOURCE_DIR}/shim       # android/log.h stand-in
        ${ENGINE_DIR}                          # core/, preprocessing/, ...
        ${ENGINE_DIR}/third_party/eigen        # Eigen (git submodule @ 3.4.0)
        ${ENGINE_DIR}/third_party/opencv/modules/core/include  # OpenCV universal intrinsics (submodule, header-only)
        # shim/ (added below) supplies the generated opencv2/opencv_modules.hpp + cvconfig.h
)

# Absolute path to the third-party DICe fixtures (BSD-3): reference images and
# DICe's own solved field, which are the standard our engine is measured
# against. Injected so the tests locate them from any (out-of-tree) build dir.
target_compile_definitions(dic_tests PRIVATE
        DICE_FIXTURES_DIR="${CMAKE_CURRENT_SOURCE_DIR}/fixtures/dice")

# ---------------------------------------------------------------------
# OPTIONAL: OpenCV, used only to decode fixture images (.tif) so they can be
# vendored in DICe's original format instead of converted.
#
# It is compiled into its OWN target: the engine TUs include OpenCV's
# universal-intrinsics headers from the git submodule, so the system opencv2/
# tree is scoped to image_io.cpp alone and never mixes with them.
#
# Without OpenCV the image-backed tests compile out (DIC_HAVE_OPENCV undefined)
# and the rest of the suite still builds with just a C++17 compiler.
# ---------------------------------------------------------------------
#
# CI passes -DDIC_REQUIRE_OPENCV=ON. Without it, a broken or changed OpenCV
# package would make find_package fail silently, the image tests would compile
# out, and CI would still go GREEN while quietly dropping the DICe coverage —
# the most valuable tests in the suite. Fail loudly there instead.
option(DIC_REQUIRE_OPENCV "Fail configure if OpenCV is missing (CI sets this)" OFF)

find_package(OpenCV QUIET COMPONENTS core imgcodecs)
if (OpenCV_FOUND)
    message(STATUS "OpenCV ${OpenCV_VERSION} found — image-backed DICe tests enabled")
    add_library(dic_test_imageio STATIC framework/image_io.cpp)
    target_include_directories(dic_test_imageio PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}   # framework/image_io.h
            ${OpenCV_INCLUDE_DIRS})
    target_link_libraries(dic_test_imageio PRIVATE ${OpenCV_LIBS})
    target_compile_definitions(dic_tests PRIVATE DIC_HAVE_OPENCV=1)
    target_link_libraries(dic_tests PRIVATE dic_test_imageio ${OpenCV_LIBS})
elseif (DIC_REQUIRE_OPENCV)
    message(FATAL_ERROR
            "DIC_REQUIRE_OPENCV=ON but OpenCV (core, imgcodecs) was not found. "
            "The image-backed DICe tests would silently compile out. "
            "Install it (Debian/Ubuntu: libopencv-dev) or unset the flag.")
else ()
    message(STATUS "OpenCV not found — image-backed DICe tests will be skipped")
endif ()

# Quiet the intentional LOGD redefinitions in engine sources (Clang-only flag)
if (CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(dic_tests PRIVATE -Wno-macro-redefined)
endif ()

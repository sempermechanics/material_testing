# =====================================================================
# HOST-SIDE NATIVE TEST SUITE
#
# Builds the DIC engine sources UNMODIFIED for the host machine
# (Windows/Linux/macOS) and links them with the micro test framework.
# Android's <android/log.h> is satisfied by the shim/ directory.
#
# Build & run:
#   cd app/src/test/cpp
#   cmake -B build -DCMAKE_BUILD_TYPE=Release
#   cmake --build build
#   ./build/dic_tests            (all)   ./build/dic_tests Engine   (one suite)
#
# See docs/TESTING.md for the full catalog of tests.
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
set(DIC_UNIT_TESTS
        unit/test_simd_kernels.cpp
        unit/test_image.cpp
        unit/test_subset_precomputer.cpp
        unit/test_strain_calculator.cpp
)
set(DIC_INTEGRATION_TESTS
        integration/test_optimization_engine.cpp
        integration/test_robustness.cpp
        integration/test_dice_parity.cpp      # DICe parity on our synthetic images
        integration/test_dice_realimage.cpp   # DICe parity on DICe's real images (fixtures/dice)
        integration/test_field_regression.cpp # full-field gold/regression (fixtures/gold)
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

# Absolute paths to test fixtures, so the image/gold tests locate them
# regardless of the (out-of-tree) build directory. fixtures/dice = third-party
# DICe images (BSD-3); fixtures/gold = our own frozen full-field baselines.
target_compile_definitions(dic_tests PRIVATE
        DICE_FIXTURES_DIR="${CMAKE_CURRENT_SOURCE_DIR}/fixtures/dice"
        GOLD_FIXTURES_DIR="${CMAKE_CURRENT_SOURCE_DIR}/fixtures/gold")

# Quiet the intentional LOGD redefinitions in engine sources (Clang-only flag)
if (CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(dic_tests PRIVATE -Wno-macro-redefined)
endif ()

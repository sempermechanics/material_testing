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

add_executable(dic_tests
        test_main.cpp
        test_simd_kernels.cpp
        test_image.cpp
        test_subset_precomputer.cpp
        test_optimization_engine.cpp
        test_strain_calculator.cpp

        # Production sources under test — compiled as-is, no test doubles
        ${ENGINE_DIR}/preprocessing/ImageProcessor.cpp
        ${ENGINE_DIR}/preprocessing/SubsetPrecomputer.cpp
        ${ENGINE_DIR}/core/OptimizationEngine.cpp
        ${ENGINE_DIR}/postprocessing/StrainCalculator.cpp
)

target_include_directories(dic_tests PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}            # framework/
        ${CMAKE_CURRENT_SOURCE_DIR}/shim       # android/log.h stand-in
        ${ENGINE_DIR}                          # core/, preprocessing/, ...
        ${ENGINE_DIR}/third_party/eigen        # Eigen (git submodule @ 3.4.0)
        ${ENGINE_DIR}/third_party/opencv/modules/core/include  # OpenCV universal intrinsics (submodule, header-only)
        # shim/ (added below) supplies the generated opencv2/opencv_modules.hpp + cvconfig.h
)

# Quiet the intentional LOGD redefinitions in engine sources (Clang-only flag)
if (CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(dic_tests PRIVATE -Wno-macro-redefined)
endif ()

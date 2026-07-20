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
        integration/test_dice_strain.cpp      # strain recovery on real speckle, known truth
        integration/test_dice_goldfield.cpp   # our field vs DICe's OWN solved field
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
else ()
    message(STATUS "OpenCV not found — image-backed DICe tests will be skipped")
endif ()

# Quiet the intentional LOGD redefinitions in engine sources (Clang-only flag)
if (CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(dic_tests PRIVATE -Wno-macro-redefined)
endif ()

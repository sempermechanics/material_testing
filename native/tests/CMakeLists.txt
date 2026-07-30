# =====================================================================
# HOST-SIDE NATIVE TEST SUITE
#
# Builds the portable DIC math library against the micro test framework.
# No Android NDK required.
#
# From the repo root:
#   cmake -S native/tests -B build/native-tests -DCMAKE_BUILD_TYPE=Release
#   cmake --build build/native-tests
#   ./build/native-tests/dic_tests
# =====================================================================
cmake_minimum_required(VERSION 3.16)
project(semper_tests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(NATIVE_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/..)

if (NOT MSVC)
    add_compile_options(-O2 -ffast-math)
else ()
    add_compile_options(/O2 /fp:fast)
endif ()

set(DIC_SANITIZER "" CACHE STRING "Comma-separated sanitizers (address,undefined,thread) or empty")
if (DIC_SANITIZER AND NOT MSVC)
    add_compile_options(-fsanitize=${DIC_SANITIZER} -g -fno-omit-frame-pointer)
    add_link_options(-fsanitize=${DIC_SANITIZER})
endif ()

set(DIC_UNIT_TESTS
        unit/test_simd_kernels.cpp
        unit/test_image.cpp
        unit/test_subset_precomputer.cpp
        unit/test_strain_calculator.cpp
        unit/test_cancel_token.cpp
)
set(DIC_INTEGRATION_TESTS
        integration/test_optimization_engine.cpp
        integration/test_robustness.cpp
)
set(DIC_DICE_TESTS
        dice/test_translation_synthetic.cpp
        dice/test_translation_real_image.cpp
        dice/test_strain_gradients.cpp
        dice/test_strain_vsg.cpp
        dice/test_field_agreement.cpp
)
set(DIC_PERF_TESTS
        perf/test_throughput.cpp
)

set(DIC_ENGINE_SOURCES
        ${NATIVE_ROOT}/src/math/image_processor.cpp
        ${NATIVE_ROOT}/src/math/subset_precomputer.cpp
        ${NATIVE_ROOT}/src/math/optimization_engine.cpp
        ${NATIVE_ROOT}/src/strain/strain_calculator.cpp
        ${NATIVE_ROOT}/src/pipeline/cancel.cpp
)

add_executable(dic_tests
        test_main.cpp
        ${DIC_UNIT_TESTS}
        ${DIC_INTEGRATION_TESTS}
        ${DIC_DICE_TESTS}
        ${DIC_PERF_TESTS}
        ${DIC_ENGINE_SOURCES}
)

target_include_directories(dic_tests PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/shim
        ${NATIVE_ROOT}/include
        ${NATIVE_ROOT}/src
        ${NATIVE_ROOT}/third_party/eigen
        ${NATIVE_ROOT}/third_party/opencv/modules/core/include
)

target_compile_definitions(dic_tests PRIVATE
        DICE_FIXTURES_DIR="${CMAKE_CURRENT_SOURCE_DIR}/fixtures/dice"
        SEMPER_HOST_LOG=1
)

option(DIC_REQUIRE_OPENCV "Fail configure if OpenCV is missing (CI sets this)" OFF)

find_package(OpenCV QUIET COMPONENTS core imgcodecs)
if (OpenCV_FOUND)
    message(STATUS "OpenCV ${OpenCV_VERSION} found — image-backed DICe tests enabled")
    add_library(dic_test_imageio STATIC framework/image_io.cpp)
    target_include_directories(dic_test_imageio PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}
            ${OpenCV_INCLUDE_DIRS})
    target_link_libraries(dic_test_imageio PRIVATE ${OpenCV_LIBS})
    target_compile_definitions(dic_tests PRIVATE DIC_HAVE_OPENCV=1)
    target_link_libraries(dic_tests PRIVATE dic_test_imageio ${OpenCV_LIBS})
elseif (DIC_REQUIRE_OPENCV)
    message(FATAL_ERROR
            "DIC_REQUIRE_OPENCV=ON but OpenCV (core, imgcodecs) was not found. "
            "Install it (Debian/Ubuntu: libopencv-dev) or unset the flag.")
else ()
    message(STATUS "OpenCV not found — image-backed DICe tests will be skipped")
endif ()

if (CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(dic_tests PRIVATE -Wno-macro-redefined)
endif ()

cmake_minimum_required(VERSION 3.22)
project(indicvision LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
include(IndicVisionOptions)

set(INDICVISION_ROOT ${CMAKE_CURRENT_SOURCE_DIR})
set(OPENCV_SRC_DIR "${INDICVISION_ROOT}/third_party/opencv")
set(EIGEN_SRC_DIR "${INDICVISION_ROOT}/third_party/eigen")

if(INDICVISION_ANDROID AND INDICVISION_FORCE_RELEASE)
    # AGP passes -DCMAKE_BUILD_TYPE=Debug for debug APKs, which would compile
    # OpenCV at -O0 (~10x slower AKAZE). Force Release like the prebuilt SDK.
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

include(IndicVisionOpenCV)
add_subdirectory(${OPENCV_SRC_DIR} ${CMAKE_BINARY_DIR}/opencv_build EXCLUDE_FROM_ALL)

set(INDICVISION_OPENCV_INCLUDE_DIRS
        ${OPENCV_SRC_DIR}/include
        ${OPENCV_SRC_DIR}/modules/core/include
        ${OPENCV_SRC_DIR}/modules/imgproc/include
        ${OPENCV_SRC_DIR}/modules/imgcodecs/include
        ${OPENCV_SRC_DIR}/modules/features2d/include
        ${OPENCV_SRC_DIR}/modules/calib3d/include
        ${OPENCV_SRC_DIR}/modules/flann/include
        ${CMAKE_BINARY_DIR}
)

set(INDICVISION_PUBLIC_INCLUDE ${INDICVISION_ROOT}/include)
set(INDICVISION_SRC_INCLUDE ${INDICVISION_ROOT}/src)

# ---------------------------------------------------------------------------
# indicvision_math — portable DIC math (no Android / JNI / OpenMP)
# ---------------------------------------------------------------------------
add_library(indicvision_math STATIC
        src/math/image_processor.cpp
        src/math/subset_precomputer.cpp
        src/math/optimization_engine.cpp
        src/strain/strain_calculator.cpp
)
target_include_directories(indicvision_math
        PUBLIC
            ${INDICVISION_PUBLIC_INCLUDE}
            ${EIGEN_SRC_DIR}
            ${OPENCV_SRC_DIR}/modules/core/include
            ${CMAKE_BINARY_DIR}
        PRIVATE
            ${INDICVISION_SRC_INCLUDE}
)
target_compile_definitions(indicvision_math PRIVATE
        $<$<BOOL:${INDICVISION_ANDROID}>:INDICVISION_ANDROID_LOG=1>
        $<$<NOT:$<BOOL:${INDICVISION_ANDROID}>>:INDICVISION_HOST_LOG=1>
)
if(NOT MSVC)
    target_compile_options(indicvision_math PRIVATE -O3 -ffast-math -fPIC -fvisibility=hidden)
endif()

# ---------------------------------------------------------------------------
# indicvision_pipeline — seeding + full-field orchestration (OpenCV + OpenMP)
# ---------------------------------------------------------------------------
add_library(indicvision_pipeline STATIC
        src/io/image_codec.cpp
        src/seeding/akaze_seed.cpp
        src/pipeline/reference_cache.cpp
        src/pipeline/full_field_solver.cpp
)
target_include_directories(indicvision_pipeline
        PUBLIC
            ${INDICVISION_PUBLIC_INCLUDE}
            ${INDICVISION_OPENCV_INCLUDE_DIRS}
            ${EIGEN_SRC_DIR}
        PRIVATE
            ${INDICVISION_SRC_INCLUDE}
)
target_link_libraries(indicvision_pipeline
        PUBLIC
            indicvision_math
            opencv_core
            opencv_imgproc
            opencv_imgcodecs
            opencv_features2d
            opencv_calib3d
            opencv_flann
)
target_compile_definitions(indicvision_pipeline PRIVATE
        $<$<BOOL:${INDICVISION_ANDROID}>:INDICVISION_ANDROID_LOG=1>
        $<$<NOT:$<BOOL:${INDICVISION_ANDROID}>>:INDICVISION_HOST_LOG=1>
)
if(NOT MSVC)
    # Apply aggressive flags only to our TUs, not OpenCV (added after add_subdirectory).
    target_compile_options(indicvision_pipeline PRIVATE -O3 -flto -ffast-math -fopenmp -fPIC -fvisibility=hidden -fexceptions)
    target_link_options(indicvision_pipeline PUBLIC -fopenmp)
endif()

add_library(indicvision INTERFACE)
target_link_libraries(indicvision INTERFACE indicvision_pipeline)
target_include_directories(indicvision INTERFACE ${INDICVISION_PUBLIC_INCLUDE})

# ---------------------------------------------------------------------------
# Android shared library (keeps OUTPUT_NAME indicvision_core for System.loadLibrary)
# ---------------------------------------------------------------------------
if(INDICVISION_ANDROID)
    add_subdirectory(adapters/android)
endif()

if(INDICVISION_BUILD_TESTS)
    add_subdirectory(tests)
endif()

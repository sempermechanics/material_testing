cmake_minimum_required(VERSION 3.22)
project("indicvision_core")

# Eigen headers — git submodule pinned to release 3.4.0
# (third_party/eigen; run `git submodule update --init --recursive` after cloning)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/third_party/eigen)

# ---------------------------------------------------------------------------
# OpenCV — built from source (git submodule third_party/opencv, pinned @ 4.12.0)
# Only the modules the engine actually uses are built, and OpenCV's bundled
# 3rd-party image codecs (zlib/png/jpeg/tiff/webp) are compiled in-tree so
# cv::imdecode / cv::imwrite work without any system libraries.
# NOTE: this is a from-source build — the first configure/compile is slow.
# ---------------------------------------------------------------------------
set(OPENCV_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/opencv")

# Curated module list (features2d / calib3d pull in flann automatically)
set(BUILD_LIST "core,imgproc,imgcodecs,features2d,calib3d,flann" CACHE STRING "" FORCE)

# Link OpenCV statically into libindicvision_core.so
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Image codecs from OpenCV's bundled 3rd-party sources (no system libs needed)
set(WITH_PNG  ON CACHE BOOL "" FORCE)
set(WITH_JPEG ON CACHE BOOL "" FORCE)
set(WITH_TIFF ON CACHE BOOL "" FORCE)
set(WITH_WEBP ON CACHE BOOL "" FORCE)
set(BUILD_ZLIB ON CACHE BOOL "" FORCE)
set(BUILD_PNG  ON CACHE BOOL "" FORCE)
set(BUILD_JPEG ON CACHE BOOL "" FORCE)
set(BUILD_TIFF ON CACHE BOOL "" FORCE)
set(BUILD_WEBP ON CACHE BOOL "" FORCE)

# Trim everything we do not need (large build-time savings)
set(OPENCV_ENABLE_NONFREE OFF CACHE BOOL "" FORCE)
foreach(_flag
        BUILD_opencv_apps BUILD_TESTS BUILD_PERF_TESTS BUILD_EXAMPLES
        BUILD_ANDROID_EXAMPLES BUILD_DOCS BUILD_JAVA BUILD_opencv_java
        BUILD_opencv_python3 BUILD_opencv_python2
        BUILD_opencv_python_bindings_generator BUILD_opencv_gapi
        BUILD_opencv_dnn BUILD_opencv_ml BUILD_opencv_photo
        BUILD_opencv_video BUILD_opencv_objdetect BUILD_ITT
        WITH_PROTOBUF WITH_ADE WITH_IPP WITH_CAROTENE WITH_OPENCL
        WITH_QUIRC WITH_FFMPEG WITH_GSTREAMER WITH_OPENEXR
        WITH_OPENJPEG WITH_JASPER WITH_CUDA WITH_EIGEN WITH_1394)
    set(${_flag} OFF CACHE BOOL "" FORCE)
endforeach()

# We build no Python bindings, so skip OpenCV's host-Python detection entirely.
# This removes any Python dependency from the build and sidesteps broken Windows
# `python3` shims that otherwise abort OpenCV's configure step.
set(OPENCV_PYTHON_SKIP_DETECTION ON CACHE BOOL "" FORCE)

# Build OpenCV with its OWN default flags (added before our aggressive flags).
add_subdirectory(${OPENCV_SRC_DIR} ${CMAKE_BINARY_DIR}/opencv_build EXCLUDE_FROM_ALL)

# OpenCV's in-tree targets don't export their header dirs, so add them by hand:
#   - <src>/include                 → opencv2/opencv.hpp (umbrella header)
#   - <src>/modules/<mod>/include   → each module's public headers
#   - <build>                       → generated opencv2/opencv_modules.hpp + cvconfig.h
#     (OpenCV writes these into CMAKE_BINARY_DIR of the top-level project)
include_directories(
        ${OPENCV_SRC_DIR}/include
        ${OPENCV_SRC_DIR}/modules/core/include
        ${OPENCV_SRC_DIR}/modules/imgproc/include
        ${OPENCV_SRC_DIR}/modules/imgcodecs/include
        ${OPENCV_SRC_DIR}/modules/features2d/include
        ${OPENCV_SRC_DIR}/modules/calib3d/include
        ${OPENCV_SRC_DIR}/modules/flann/include
        ${CMAKE_BINARY_DIR}
)

# --- THE ULTIMATE SECURITY & SPEED FLAGS (applied only to targets below) ---
# Set AFTER add_subdirectory so OpenCV is not compiled with -flto / -ffast-math.
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -flto -ffast-math -Wno-nan-infinity-disabled -fexceptions -fvisibility=hidden -fvisibility-inlines-hidden -fopenmp -fPIC")

# 🚀 The '-s' flag strips the symbol table; 16 KB page alignment for Android 15+.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -s -flto -Wl,-z,max-page-size=16384")

add_library(
        indicvision_core
        SHARED

        # --- Modular Architecture ---
        preprocessing/ImageProcessor.cpp
        preprocessing/SubsetPrecomputer.cpp
        core/OptimizationEngine.cpp
        postprocessing/StrainCalculator.cpp
        bridge/IndicVisionJNI.cpp
)

target_link_libraries(
        indicvision_core
        opencv_core
        opencv_imgproc
        opencv_imgcodecs
        opencv_features2d
        opencv_calib3d
        opencv_flann
        log
        android
        jnigraphics
        -fopenmp
        -static-openmp
        -static-libstdc++
)

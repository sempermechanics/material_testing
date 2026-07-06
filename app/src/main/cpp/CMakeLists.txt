cmake_minimum_required(VERSION 3.10.2)
project("indicvision_core")

# Eigen headers
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)

# OpenCV
set(OpenCV_DIR "${CMAKE_CURRENT_SOURCE_DIR}/opencv/sdk/native/jni")
find_package(OpenCV REQUIRED)

# --- THE ULTIMATE SECURITY & SPEED FLAGS ---
# Removed -fno-limit-debug-info!
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -flto -ffast-math -Wno-nan-infinity-disabled -fexceptions -fvisibility=hidden -fvisibility-inlines-hidden -fopenmp -fPIC")

# 🚀 NEW: The '-s' flag forcefully strips the Symbol Table from the compiled binary.
# This makes it nearly impossible for a disassembler to map your C++ logic.
#
# 🚀 16 KB PAGE SUPPORT: force 16 KB-aligned ELF LOAD segments so the library
# maps cleanly on Android 15+ 16 KB-page devices. NDK r27+ already defaults to
# this; the explicit flag guarantees it even if built with an older NDK.
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
        ${OpenCV_LIBS}
        log
        android
        jnigraphics
        -fopenmp
        -static-openmp
        -static-libstdc++
)
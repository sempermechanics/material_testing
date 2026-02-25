cmake_minimum_required(VERSION 3.10.2)
project("indicvision_core")

# Eigen headers
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)

# OpenCV
set(OpenCV_DIR "${CMAKE_CURRENT_SOURCE_DIR}/opencv/sdk/native/jni")
find_package(OpenCV REQUIRED)

# --- THE ULTIMATE SPEED OPTIMIZATION FLAGS ---
# -O3: Maximum compiler optimization
# -flto: Link-time optimization (cross-file inlining)
# -ffast-math: Aggressive floating point math vectorization (MASSIVE speedup for DIC)
# -fvisibility=hidden: Strips unused symbols, making the .so file smaller and faster to load
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -flto -ffast-math -Wno-nan-infinity-disabled -fexceptions -fvisibility=hidden -fvisibility-inlines-hidden -fopenmp -O3 -fno-limit-debug-info -fPIC")
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
cmake_minimum_required(VERSION 3.10.2)
project("indicvision_core")

# Eigen headers
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)

# OpenCV
set(OpenCV_DIR "${CMAKE_CURRENT_SOURCE_DIR}/opencv/sdk/native/jni")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -flto -fexceptions -fno-limit-debug-info -fopenmp")
find_package(OpenCV REQUIRED)

# Compiler optimizations (Maximum Speed!)
if(ANDROID)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -flto -fexceptions")
endif()

add_library(
        indicvision_core
        SHARED

        # --- New Modular Architecture ---
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

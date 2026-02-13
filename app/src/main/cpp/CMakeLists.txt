cmake_minimum_required(VERSION 3.10.2)

project("indicvision_core")

# 1. Setup Eigen
# Ensure this path points to where your Eigen headers are (e.g., app/src/main/cpp/include)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)

# 2. Setup OpenCV
set(OpenCV_DIR "${CMAKE_CURRENT_SOURCE_DIR}/opencv/sdk/native/jni")
find_package(OpenCV REQUIRED)

# 3. Compiler Optimizations (The Fix)
# We removed -mfpu=neon and -mfloat-abi=softfp because they crash 64-bit builds.
# We added -flto (Link Time Optimization) to make the matrix math faster.
if(ANDROID)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -flto -fexceptions")
endif()

# 4. Define the Native Library
add_library(
        indicvision_core
        SHARED
        IndicVisionCore.cpp
        IndicVisionJNI.cpp
)

# 5. Link Libraries
target_link_libraries(
        indicvision_core
        ${OpenCV_LIBS}
        log
        android
        jnigraphics
)
# Redirect: the DIC engine lives in the repo-root native/ package.
# Prefer Gradle path: ../native/CMakeLists.txt with -DINDICVISION_ANDROID=ON
cmake_minimum_required(VERSION 3.22)
set(INDICVISION_ANDROID ON CACHE BOOL "" FORCE)
add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native
    ${CMAKE_BINARY_DIR}/indicvision
)

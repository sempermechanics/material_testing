# Redirect: host tests live in native/tests.
# Prefer: cmake -S native/tests -B build/native-tests
cmake_minimum_required(VERSION 3.16)
add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/tests
    ${CMAKE_BINARY_DIR}/dic_tests_redirect
)

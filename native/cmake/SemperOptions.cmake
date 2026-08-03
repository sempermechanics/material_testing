option(SEMPER_ANDROID "Build Android shared library (JNI adapter)" OFF)
option(SEMPER_BUILD_TESTS "Build host-side dic_tests" OFF)
option(SEMPER_FORCE_RELEASE "Force Release flags even when AGP passes Debug" ON)

# Optional sanitizers for first-party native libs (math/pipeline). OFF by default
# and must stay off for Release throughput measurement — never the default for
# production or perf runs. Example: -DSEMPER_SANITIZER=address,undefined
set(SEMPER_SANITIZER "" CACHE STRING "Comma-separated sanitizers for semper_* libs (empty = off)")

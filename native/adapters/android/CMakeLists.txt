# Thin Android/JNI adapter. Included from native/CMakeLists.txt when
# SEMPER_ANDROID=ON. Produces libsemper_core.so for System.loadLibrary.

add_library(semper_android SHARED
        jni/SemperJNI.cpp
)
set_target_properties(semper_android PROPERTIES OUTPUT_NAME semper_core)

target_include_directories(semper_android PRIVATE
        ${SEMPER_ROOT}/src
        ${SEMPER_ROOT}/include
        ${SEMPER_OPENCV_INCLUDE_DIRS}
)

target_compile_definitions(semper_android PRIVATE SEMPER_ANDROID_LOG=1)

if(NOT MSVC)
    target_compile_options(semper_android PRIVATE
            -O3 -flto -ffast-math -fopenmp -fPIC -fvisibility=hidden -fexceptions
    )
    target_link_options(semper_android PRIVATE
            -s -flto -Wl,-z,max-page-size=16384
            -fopenmp -static-openmp -static-libstdc++
    )
endif()

target_link_libraries(semper_android
        PRIVATE
            semper_pipeline
            log
            android
            jnigraphics
)

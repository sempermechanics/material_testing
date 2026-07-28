# Thin Android/JNI adapter. Included from native/CMakeLists.txt when
# INDICVISION_ANDROID=ON. Produces libindicvision_core.so for System.loadLibrary.

add_library(indicvision_android SHARED
        jni/IndicVisionJNI.cpp
)
set_target_properties(indicvision_android PROPERTIES OUTPUT_NAME indicvision_core)

target_include_directories(indicvision_android PRIVATE
        ${INDICVISION_ROOT}/src
        ${INDICVISION_ROOT}/include
        ${INDICVISION_OPENCV_INCLUDE_DIRS}
)

target_compile_definitions(indicvision_android PRIVATE INDICVISION_ANDROID_LOG=1)

if(NOT MSVC)
    target_compile_options(indicvision_android PRIVATE
            -O3 -flto -ffast-math -fopenmp -fPIC -fvisibility=hidden -fexceptions
    )
    target_link_options(indicvision_android PRIVATE
            -s -flto -Wl,-z,max-page-size=16384
            -fopenmp -static-openmp -static-libstdc++
    )
endif()

target_link_libraries(indicvision_android
        PRIVATE
            indicvision_pipeline
            log
            android
            jnigraphics
)

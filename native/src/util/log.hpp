#ifndef INDICVISION_LOG_HPP
#define INDICVISION_LOG_HPP

// Portable logging for the DIC engine.
// Android adapter builds define INDICVISION_ANDROID_LOG; host tests get stderr.

#ifndef LOG_TAG
#define LOG_TAG "IndicVisionDIC"
#endif

#if defined(INDICVISION_ANDROID_LOG)
#include <android/log.h>
#ifndef LOGD
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#endif
#ifndef LOGE
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif
#elif defined(INDICVISION_HOST_LOG)
#include <cstdio>
#ifndef LOGD
#define LOGD(...) do { std::fprintf(stderr, "[%s] ", LOG_TAG); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif
#ifndef LOGE
#define LOGE(...) do { std::fprintf(stderr, "[%s] ", LOG_TAG); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif
#else
#ifndef LOGD
#define LOGD(...) ((void)0)
#endif
#ifndef LOGE
#define LOGE(...) ((void)0)
#endif
#endif

#endif // INDICVISION_LOG_HPP

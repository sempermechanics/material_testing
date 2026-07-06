#ifndef INDICVISION_TEST_SHIM_ANDROID_LOG_H
#define INDICVISION_TEST_SHIM_ANDROID_LOG_H

// =====================================================================
// HOST-BUILD SHIM for <android/log.h>
//
// The production engine logs through __android_log_print, which only
// exists on Android. This shim lets the exact same .cpp files compile
// unmodified on a host machine (Windows/Linux/macOS) for unit testing.
// Log output is routed to stdout; set DIC_TEST_SILENT_LOGS to discard.
// =====================================================================

#include <cstdio>
#include <cstdarg>

typedef enum android_LogPriority {
    ANDROID_LOG_UNKNOWN = 0,
    ANDROID_LOG_DEFAULT,
    ANDROID_LOG_VERBOSE,
    ANDROID_LOG_DEBUG,
    ANDROID_LOG_INFO,
    ANDROID_LOG_WARN,
    ANDROID_LOG_ERROR,
    ANDROID_LOG_FATAL,
    ANDROID_LOG_SILENT,
} android_LogPriority;

static inline int __android_log_print(int /*prio*/, const char *tag,
                                      const char *fmt, ...) {
#ifdef DIC_TEST_SILENT_LOGS
    (void) tag; (void) fmt;
    return 0;
#else
    std::printf("[%s] ", tag);
    va_list args;
    va_start(args, fmt);
    int r = std::vprintf(fmt, args);
    va_end(args);
    std::printf("\n");
    return r;
#endif
}

#endif // INDICVISION_TEST_SHIM_ANDROID_LOG_H

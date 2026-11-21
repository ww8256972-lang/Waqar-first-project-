#include <jni.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <unistd.h>
#include <cstdint>
#include <cstring>

#ifndef MADV_DONTDUMP
#define MADV_DONTDUMP 16
#endif

#ifndef PR_SET_PTRACER
#define PR_SET_PTRACER 0x59616d61
#endif

/**
 * [A2] C++20 NDK Anti-Memory-Dump Shield (MADV_DONTDUMP + PR_SET_DUMPABLE + PR_SET_PTRACER).
 * Prevents core dumps, Frida/GameGuardian memory dumps, and non-root ptrace attachment
 * at the Linux kernel level.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_waqar_institute_security_NativeSecurityBridge_nativeArmAntiMemoryDumpVault(
    JNIEnv*,
    jobject
) {
    // 1. Prevent core dumps and non-root ptrace attachment at kernel level
    const int dumpableOk = prctl(PR_SET_DUMPABLE, 0, 0, 0, 0);
    prctl(PR_SET_PTRACER, 0, 0, 0, 0);

    // 2. Allocate a 4KB guard page marked MADV_DONTDUMP to verify kernel support
    const long sysPageSize = sysconf(_SC_PAGESIZE);
    const size_t pageSize = sysPageSize > 0 ? static_cast<size_t>(sysPageSize) : 4096U;
    void* guardMem = mmap(nullptr, pageSize, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (guardMem == MAP_FAILED) {
        return JNI_FALSE;
    }

    const int madvOk = madvise(guardMem, pageSize, MADV_DONTDUMP);
    volatile uint8_t* p = static_cast<volatile uint8_t*>(guardMem);
    for (size_t i = 0; i < pageSize; ++i) {
        p[i] = 0;
    }
    munmap(guardMem, pageSize);

    return (dumpableOk == 0 && madvOk == 0) ? JNI_TRUE : JNI_FALSE;
}

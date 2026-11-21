#include <jni.h>
#include <string>
#include <cstring>
#include <cstdlib>
#include <cstdint>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <android/log.h>

#define LOG_TAG "WCI_TamperTrapDaemon"

namespace {

    [[gnu::always_inline]] inline int raw_sys_openat(const char* path, int flags) noexcept {
        return static_cast<int>(syscall(__NR_openat, AT_FDCWD, path, flags, 0));
    }

    [[gnu::always_inline]] inline ssize_t raw_sys_read(int fd, void* buf, size_t count) noexcept {
        return syscall(__NR_read, fd, buf, count);
    }

    [[gnu::always_inline]] inline int raw_sys_close(int fd) noexcept {
        return static_cast<int>(syscall(__NR_close, fd));
    }

    // Scan /proc/self/maps directly via kernel syscalls for unauthorized runtime memory injection or rwx segments
    bool detectUnauthorizedRwxOrPatch() noexcept {
        int fd = raw_sys_openat("/proc/self/maps", O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            return false;
        }
        char buf[4096];
        std::string currentLine;
        currentLine.reserve(512);
        ssize_t n;
        while ((n = raw_sys_read(fd, buf, sizeof(buf))) > 0) {
            for (ssize_t i = 0; i < n; ++i) {
                char c = buf[i];
                if (c == '\n') {
                    if (currentLine.find("rwxp") != std::string::npos) {
                        if (currentLine.find("frida") != std::string::npos ||
                            currentLine.find("substrate") != std::string::npos ||
                            currentLine.find("lsplant") != std::string::npos ||
                            currentLine.find("gameguardian") != std::string::npos ||
                            currentLine.find("mt_hook") != std::string::npos ||
                            currentLine.find("memfd:") != std::string::npos) {
                            raw_sys_close(fd);
                            return true;
                        }
                    }
                    currentLine.clear();
                } else if (currentLine.size() < 1024) {
                    if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
                    currentLine.push_back(c);
                }
            }
        }
        raw_sys_close(fd);
        return false;
    }

    // Verify TracerPid in /proc/self/status via raw syscall for ptrace attachment
    bool detectActivePtraceDebugger() noexcept {
        int fd = raw_sys_openat("/proc/self/status", O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            return false;
        }
        char buf[4096];
        ssize_t n = raw_sys_read(fd, buf, sizeof(buf) - 1);
        raw_sys_close(fd);
        if (n <= 0) {
            return false;
        }
        buf[n] = '\0';
        const char* tracer = std::strstr(buf, "TracerPid:");
        if (tracer == nullptr) {
            return false;
        }
        char* endPtr = nullptr;
        long tracerPid = std::strtol(tracer + 10, &endPtr, 10);
        return tracerPid > 0;
    }

} // anonymous namespace

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_waqar_institute_security_EnterpriseRaspShield_nativeVerifyMemoryIntegrity(
        JNIEnv * /* env */,
        jobject /* thiz */) {
    bool tampered = detectUnauthorizedRwxOrPatch() || detectActivePtraceDebugger();
    return tampered ? JNI_FALSE : JNI_TRUE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_waqar_institute_security_EnterpriseRaspShield_nativeComputeRuntimeSignatureMask(
        JNIEnv * /* env */,
        jobject /* thiz */,
        jlong seedNonce) {
    uint64_t x = static_cast<uint64_t>(seedNonce) ^ 0x9E3779B97F4A7C15ULL;
    x = (x ^ (x >> 30)) * 0xBF58476D1CE4E5B9ULL;
    x = (x ^ (x >> 27)) * 0x94D049BB133111EBULL;
    x = x ^ (x >> 31);
    if (detectUnauthorizedRwxOrPatch() || detectActivePtraceDebugger()) {
        return -1;
    }
    return static_cast<jint>(x & 0x7FFFFFFF);
}

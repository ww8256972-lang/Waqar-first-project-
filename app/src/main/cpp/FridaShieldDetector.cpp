#include <jni.h>
#include <string>
#include <cstring>
#include <cstdio>
#include <cstdint>
#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <android/log.h>

#define FRIDA_SHIELD_TAG "WciFridaShield"

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

bool scanMapsForFridaOrXposed(int& outFlags) noexcept {
    int fd = raw_sys_openat("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;

    char buf[4096];
    std::string window;
    window.reserve(8192);
    ssize_t n;
    while ((n = raw_sys_read(fd, buf, sizeof(buf))) > 0) {
        for (ssize_t i = 0; i < n; ++i) {
            char c = buf[i];
            if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
            window.push_back(c);
        }
        if (window.find("frida") != std::string::npos ||
            window.find("gum-js-loop") != std::string::npos ||
            window.find("libgadget") != std::string::npos ||
            window.find("frida-gadget") != std::string::npos) {
            outFlags |= 0x01;
        }
        if (window.find("xposed") != std::string::npos ||
            window.find("lsposed") != std::string::npos ||
            window.find("edxposed") != std::string::npos ||
            window.find("zygisk") != std::string::npos ||
            window.find("substrate") != std::string::npos ||
            window.find("lsplant") != std::string::npos ||
            window.find("libdobby") != std::string::npos) {
            outFlags |= 0x02;
        }
        if (window.size() > 128) {
            window.erase(0, window.size() - 128);
        }
    }
    raw_sys_close(fd);
    return (outFlags != 0);
}

bool scanTaskThreadsForFridaLoop(int& outFlags) noexcept {
    DIR* dir = opendir("/proc/self/task");
    if (dir == nullptr) return false;

    struct dirent* entry = nullptr;
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_name[0] == '.') continue;
        char statusPath[128];
        std::snprintf(statusPath, sizeof(statusPath), "/proc/self/task/%.64s/comm", entry->d_name);
        int fd = raw_sys_openat(statusPath, O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            char commBuf[128] = {0};
            ssize_t r = raw_sys_read(fd, commBuf, sizeof(commBuf) - 1);
            raw_sys_close(fd);
            if (r > 0) {
                commBuf[r] = '\0';
                if (std::strstr(commBuf, "gum-js-loop") != nullptr ||
                    std::strstr(commBuf, "pool-frida") != nullptr) {
                    outFlags |= 0x01;
                    break;
                }
            }
        }
    }
    closedir(dir);
    return (outFlags & 0x01) != 0;
}

bool scanProcNetTcpForPort(const char* procNetPath) noexcept {
    int fd = raw_sys_openat(procNetPath, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;

    char buf[4096];
    std::string text;
    text.reserve(8192);
    ssize_t n;
    while ((n = raw_sys_read(fd, buf, sizeof(buf))) > 0) {
        for (ssize_t i = 0; i < n; ++i) {
            char c = buf[i];
            if (c >= 'a' && c <= 'f') c = static_cast<char>(c - 'a' + 'A');
            text.push_back(c);
        }
        // Frida default D-Bus TCP ports: 27042 (0x69AA) and 27043 (0x69AB), TCP_LISTEN state " 0A "
        if ((text.find(":69AA ") != std::string::npos || text.find(":69AB ") != std::string::npos) &&
            text.find(" 0A ") != std::string::npos) {
            raw_sys_close(fd);
            return true;
        }
        if (text.size() > 256) {
            text.erase(0, text.size() - 256);
        }
    }
    raw_sys_close(fd);
    return false;
}

bool probeFridaLocalhostPort() noexcept {
    return scanProcNetTcpForPort("/proc/net/tcp") || scanProcNetTcpForPort("/proc/net/tcp6");
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_waqar_institute_security_AntiHookManager_nativeScanFridaAndHooks(
        JNIEnv* /* env */,
        jobject /* thiz */) {
    int flags = 0;
    scanMapsForFridaOrXposed(flags);
    scanTaskThreadsForFridaLoop(flags);
    if (probeFridaLocalhostPort()) {
        flags |= 0x01;
    }
    return static_cast<jint>(flags);
}

#include <jni.h>
#include <string>
#include <fstream>
#include <cstring>
#include <cstdint>
#include <dirent.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <android/log.h>

#define FRIDA_SHIELD_TAG "WciFridaShield"

namespace {

bool scanMapsForFridaOrXposed(int& outFlags) noexcept {
    std::ifstream maps("/proc/self/maps");
    if (!maps.is_open()) return false;

    std::string line;
    while (std::getline(maps, line)) {
        if (line.find("frida") != std::string::npos ||
            line.find("gum-js-loop") != std::string::npos ||
            line.find("gadget") != std::string::npos) {
            outFlags |= 0x01;
        }
        if (line.find("xposed") != std::string::npos ||
            line.find("lsposed") != std::string::npos ||
            line.find("edxposed") != std::string::npos ||
            line.find("zygisk") != std::string::npos ||
            line.find("substrate") != std::string::npos) {
            outFlags |= 0x02;
        }
    }
    return (outFlags != 0);
}

bool scanTaskThreadsForFridaLoop(int& outFlags) noexcept {
    DIR* dir = opendir("/proc/self/task");
    if (dir == nullptr) return false;

    struct dirent* entry = nullptr;
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_name[0] == '.') continue;
        std::string statusPath = std::string("/proc/self/task/") + entry->d_name + "/comm";
        std::ifstream comm(statusPath);
        if (comm.is_open()) {
            std::string name;
            std::getline(comm, name);
            if (name.find("gum-js-loop") != std::string::npos ||
                name.find("gmain") != std::string::npos ||
                name.find("pool-frida") != std::string::npos ||
                name.find("gdbus") != std::string::npos) {
                outFlags |= 0x01;
                break;
            }
        }
    }
    closedir(dir);
    return (outFlags & 0x01) != 0;
}

bool probeFridaLocalhostPort() noexcept {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return false;

    struct timeval tv {};
    tv.tv_sec = 0;
    tv.tv_usec = 45000; // 45ms non-blocking probe
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&tv), sizeof(tv));
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&tv), sizeof(tv));

    struct sockaddr_in addr {};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(27042);
    inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);

    bool connected = (connect(sock, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == 0);
    close(sock);
    return connected;
}

bool checkEntryTrampoline(const void* fnPtr) noexcept {
    if (fnPtr == nullptr) return false;
    const auto* raw = reinterpret_cast<const uint8_t*>(fnPtr);
#if defined(__aarch64__)
    uint32_t firstInsn = 0;
    std::memcpy(&firstInsn, raw, sizeof(uint32_t));
    // Unconditional B or LDR X16/X17 trampoline
    if ((firstInsn & 0xFC000000u) == 0x14000000u ||
        firstInsn == 0x58000050u ||
        firstInsn == 0x58000051u) {
        return true;
    }
#elif defined(__x86_64__) || defined(__i386__)
    if (raw[0] == 0xE9u || (raw[0] == 0xFFu && raw[1] == 0x25u)) {
        return true;
    }
#endif
    return false;
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
    if (checkEntryTrampoline(reinterpret_cast<const void*>(&scanMapsForFridaOrXposed)) ||
        checkEntryTrampoline(reinterpret_cast<const void*>(&open))) {
        flags |= 0x04;
    }
    return static_cast<jint>(flags);
}

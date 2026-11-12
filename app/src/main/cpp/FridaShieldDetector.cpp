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
                name.find("pool-frida") != std::string::npos) {
                outFlags |= 0x01;
                break;
            }
        }
    }
    closedir(dir);
    return (outFlags & 0x01) != 0;
}

bool probeFridaLocalhostPort() noexcept {
    return false;
}

bool checkEntryTrampoline(const void* /* fnPtr */) noexcept {
    // Avoid reading .text execute-only memory (XOM) on Android 10+ ARM64 devices
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
    return static_cast<jint>(flags);
}

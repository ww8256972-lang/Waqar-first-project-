#include <jni.h>
#include <string>
#include <fstream>
#include <sstream>
#include <cstdint>
#include <unistd.h>
#include <sys/stat.h>
#include <android/log.h>

#define LOG_TAG "WCI_TamperTrapDaemon"

namespace {

    // Scan /proc/self/maps for unauthorized runtime memory injection or rwx segments
    bool detectUnauthorizedRwxOrPatch() {
        std::ifstream maps("/proc/self/maps");
        if (!maps.is_open()) {
            return false;
        }
        std::string line;
        while (std::getline(maps, line)) {
            // Detect suspicious anonymous RWX regions or known binary patchers
            if (line.find("rwxp") != std::string::npos) {
                if (line.find("frida") != std::string::npos ||
                    line.find("substrate") != std::string::npos ||
                    line.find("lsplant") != std::string::npos ||
                    line.find("gameguardian") != std::string::npos ||
                    line.find("mt_hook") != std::string::npos) {
                    return true;
                }
            }
        }
        return false;
    }

    // Verify TracerPid in /proc/self/status for ptrace attachment
    bool detectActivePtraceDebugger() {
        std::ifstream status("/proc/self/status");
        if (!status.is_open()) {
            return false;
        }
        std::string line;
        while (std::getline(status, line)) {
            if (line.rfind("TracerPid:", 0) == 0) {
                std::string pidPart = line.substr(10);
                int tracerPid = std::stoi(pidPart);
                if (tracerPid > 0) {
                    return true;
                }
                break;
            }
        }
        return false;
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

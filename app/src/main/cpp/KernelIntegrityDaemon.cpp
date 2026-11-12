#include <jni.h>
#include <cstdint>
#include <ctime>
#include <atomic>
#include <thread>
#include <chrono>
#include <array>
#include <android/log.h>

#define KERNEL_SHIELD_TAG "WciKernelIntegrity"

namespace {

std::atomic<bool> gIntegrityDaemonActive{false};
std::atomic<uint32_t> gBaselineCrc32{0u};

// IEEE 802.3 CRC32 lookup-free polynomial calculation over in-memory code bytes
uint32_t computeCrc32Bytes(const uint8_t* data, size_t length) noexcept {
    uint32_t crc = 0xFFFFFFFFu;
    for (size_t i = 0; i < length; ++i) {
        crc ^= static_cast<uint32_t>(data[i]);
        for (int bit = 0; bit < 8; ++bit) {
            const uint32_t mask = -(crc & 1u);
            crc = (crc >> 1u) ^ (0xEDB88320u & mask);
        }
    }
    return ~crc;
}

// Control-Flow Flattened target function whose machine code segment is continuously CRC32-verified
uint32_t flattenedSecurityKernelBlock(uint32_t inputSeed) noexcept {
    volatile uint32_t dispatchState = 0xA1u;
    uint32_t accumulator = inputSeed ^ 0x9E3779B9u;

    while (dispatchState != 0xFFu) {
        switch (dispatchState) {
            case 0xA1u:
                accumulator = (accumulator << 7u) | (accumulator >> 25u);
                accumulator ^= 0x7F4A7C15u;
                dispatchState = ((accumulator * (accumulator + 1u)) & 1u) == 0u ? 0xB2u : 0xFFu;
                break;
            case 0xB2u:
                accumulator += 0x85EBCA6Bu;
                accumulator ^= (accumulator >> 13u);
                dispatchState = 0xC3u;
                break;
            case 0xC3u:
                accumulator *= 0xC2B2AE35u;
                accumulator ^= (accumulator >> 16u);
                dispatchState = 0xFFu;
                break;
            default:
                dispatchState = 0xFFu;
                break;
        }
    }
    return accumulator;
}

uint32_t measureCurrentCodeSegmentCrc32() noexcept {
    const auto* codeStart = reinterpret_cast<const uint8_t*>(&flattenedSecurityKernelBlock);
    return computeCrc32Bytes(codeStart, 96u);
}

// Anti-DBI (Dynamic Binary Instrumentation) high-resolution monotonic clock delta check
bool detectDbiInstructionTimingSpike() noexcept {
    struct timespec tStart {};
    struct timespec tEnd {};
    if (clock_gettime(CLOCK_MONOTONIC_RAW, &tStart) != 0) {
        return false;
    }

    volatile uint32_t acc = 0x13579BDFu;
    for (uint32_t i = 0; i < 2048u; ++i) {
        acc = flattenedSecurityKernelBlock(acc ^ i);
    }

    if (clock_gettime(CLOCK_MONOTONIC_RAW, &tEnd) != 0) {
        return false;
    }

    const int64_t elapsedNs =
        (static_cast<int64_t>(tEnd.tv_sec - tStart.tv_sec) * 1'000'000'000LL) +
        static_cast<int64_t>(tEnd.tv_nsec - tStart.tv_nsec);

    // 2048 iterations of pure register math take < 1ms natively; > 250ms indicates single-stepping / DBI stalker
    return (elapsedNs > 250'000'000LL && acc != 0u);
}

void startBackgroundIntegrityDaemon() noexcept {
    bool expected = false;
    if (!gIntegrityDaemonActive.compare_exchange_strong(expected, true)) {
        return;
    }

    const uint32_t initialCrc = measureCurrentCodeSegmentCrc32();
    gBaselineCrc32.store(initialCrc, std::memory_order_relaxed);

    std::thread([]() {
        while (gIntegrityDaemonActive.load(std::memory_order_relaxed)) {
            const uint32_t currentCrc = measureCurrentCodeSegmentCrc32();
            const uint32_t baseline = gBaselineCrc32.load(std::memory_order_relaxed);
            if (baseline != 0u && currentCrc != baseline) {
                __android_log_print(
                    ANDROID_LOG_ERROR,
                    KERNEL_SHIELD_TAG,
                    "CRITICAL: .text segment CRC32 mismatch! Expected=0x%08X Actual=0x%08X",
                    baseline,
                    currentCrc
                );
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(4000));
        }
    }).detach();
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_waqar_institute_security_AppSelfHealingManager_nativeStartKernelIntegrityDaemon(
        JNIEnv* /* env */,
        jobject /* thiz */) {
    startBackgroundIntegrityDaemon();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_waqar_institute_security_AppSelfHealingManager_nativeVerifyExecutableSegmentCrc32(
        JNIEnv* /* env */,
        jobject /* thiz */) {
    const uint32_t current = measureCurrentCodeSegmentCrc32();
    uint32_t expected = gBaselineCrc32.load(std::memory_order_relaxed);
    if (expected == 0u) {
        gBaselineCrc32.store(current, std::memory_order_relaxed);
        expected = current;
    }
    return (current == expected) ? static_cast<jlong>(current) : 0L;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_waqar_institute_security_AppSelfHealingManager_nativeDetectDbiTimingAnomaly(
        JNIEnv* /* env */,
        jobject /* thiz */) {
    return detectDbiInstructionTimingSpike() ? JNI_TRUE : JNI_FALSE;
}

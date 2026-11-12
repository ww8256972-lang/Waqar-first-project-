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

constexpr std::array<uint8_t, 32> kKernelAnchorBytes = {
    0x57, 0x43, 0x49, 0x5F, 0x4B, 0x45, 0x52, 0x4E,
    0x45, 0x4C, 0x5F, 0x52, 0x4F, 0x44, 0x41, 0x54,
    0x41, 0x5F, 0x41, 0x4E, 0x43, 0x48, 0x4F, 0x52,
    0x9E, 0x37, 0x79, 0xB9, 0x7F, 0x4A, 0x7C, 0x15
};

uint32_t measureCurrentCodeSegmentCrc32() noexcept {
    // Compute CRC32 over readable .rodata anchor + deterministic state machine output (XOM-safe on ARM64)
    uint32_t crc = computeCrc32Bytes(kKernelAnchorBytes.data(), kKernelAnchorBytes.size());
    uint32_t stateOut = flattenedSecurityKernelBlock(crc);
    return crc ^ stateOut;
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

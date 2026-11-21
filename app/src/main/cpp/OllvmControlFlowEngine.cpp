#include <jni.h>
#include <cstdint>
#include <cstring>
#include <string>

/**
 * Native C++20 OLLVM Control-Flow Flattening & Bogus Instruction Engine
 * (`OllvmControlFlowEngine.cpp`).
 *
 * Implements state-machine control-flow flattening (switch dispatcher loop),
 * opaque predicates based on number-theoretic invariants (`(x * (x + 1)) % 2 == 0`),
 * and Mixed Boolean-Arithmetic (MBA) expression obfuscation (`(x ^ y) + 2 * (x & y)`)
 * to defeat static decompilers (IDA Pro / Ghidra) when verifying binary and certificate
 * integrity tokens.
 */
namespace wci_ollvm {

    // Mixed Boolean-Arithmetic (MBA) linear identity: x + y == (x ^ y) + 2 * (x & y)
    [[gnu::always_inline]] inline uint64_t mbaAdd(uint64_t x, uint64_t y) noexcept {
        return (x ^ y) + ((x & y) << 1U);
    }

    // MBA identity: x ^ y == (x | y) - (x & y)
    [[gnu::always_inline]] inline uint64_t mbaXor(uint64_t x, uint64_t y) noexcept {
        return (x | y) - (x & y);
    }

    // Opaque predicate: product of two consecutive integers is always even
    [[gnu::noinline]] bool opaqueAlwaysTrue(uint64_t seed) noexcept {
        volatile uint64_t v = seed | 1ULL;
        return ((v * (v + 1ULL)) & 1ULL) == 0ULL;
    }

    // Control-Flow Flattened FNV-1a + MBA 64-bit Integrity Dispatcher
    uint64_t executeFlattenedIntegrityMachine(const uint8_t* data, size_t len, uint64_t salt) noexcept {
        volatile uint32_t state = 0x1F0A9C01U;
        uint64_t acc = mbaXor(0xCBF29CE484222325ULL, salt);
        size_t idx = 0;

        while (state != 0xDEAD0000U) {
            switch (state) {
                case 0x1F0A9C01U: {
                    if (opaqueAlwaysTrue(acc)) {
                        state = (idx < len) ? 0x2B44E812U : 0x7A91C044U;
                    } else {
                        // Unreachable bogus block to poison static CFG reconstruction
                        acc = mbaAdd(acc, 0xBADCAFE012345678ULL);
                        state = 0x9911EEFFU;
                    }
                    break;
                }
                case 0x2B44E812U: {
                    uint64_t byteVal = static_cast<uint64_t>(data[idx]);
                    acc = mbaXor(acc, byteVal);
                    acc *= 0x100000001B3ULL;
                    acc = mbaAdd(acc, (idx * 0x9E3779B97F4A7C15ULL));
                    ++idx;
                    state = 0x1F0A9C01U;
                    break;
                }
                case 0x7A91C044U: {
                    // Final avalanche mixing stage
                    acc = mbaXor(acc, acc >> 33U);
                    acc *= 0xFF51AFD7ED558CCDULL;
                    acc = mbaXor(acc, acc >> 33U);
                    state = 0xDEAD0000U;
                    break;
                }
                default: {
                    state = 0xDEAD0000U;
                    break;
                }
            }
        }
        return acc;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_waqar_institute_security_PlayIntegrityEnforcer_nativeComputeOllvmFlattenedDigest(
        JNIEnv* env,
        jobject /* thiz */,
        jstring payload_jstr,
        jlong salt) {
    if (payload_jstr == nullptr) {
        return 0L;
    }
    const char* rawChars = env->GetStringUTFChars(payload_jstr, nullptr);
    if (rawChars == nullptr) {
        return 0L;
    }
    size_t len = std::strlen(rawChars);
    uint64_t digest = wci_ollvm::executeFlattenedIntegrityMachine(
            reinterpret_cast<const uint8_t*>(rawChars),
            len,
            static_cast<uint64_t>(salt)
    );
    env->ReleaseStringUTFChars(payload_jstr, rawChars);
    return static_cast<jlong>(digest);
}

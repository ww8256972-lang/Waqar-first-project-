#include <jni.h>
#include <cstdint>
#include <chrono>
#include <fstream>
#include <string>

namespace {

    // Polymorphic opaque predicate & control-flow flattening helper
    inline uint32_t polymorphicMix32(uint32_t v, uint32_t roundKey) {
        uint32_t state = v ^ roundKey;
        uint32_t selector = (state & 0x3U);
        switch (selector) {
            case 0:
                state = (state << 7) | (state >> 25);
                state ^= 0xA5A5F00DU;
                break;
            case 1:
                state = (state * 0x45D9F3BU) ^ 0x11C2D3E4U;
                break;
            case 2:
                state = ((state >> 13) ^ state) + 0x7F4A7C15U;
                break;
            default:
                state = (state ^ 0xDEADC0DEU) * 0x27D4EB2DU;
                break;
        }
        return state;
    }

    // Detect single-stepping / hardware breakpoint stalls across polymorphic dispatch blocks
    bool detectInstructionPointerStepAnomaly() {
        auto start = std::chrono::steady_clock::now();
        volatile uint32_t acc = 0x13579BDFU;
        for (uint32_t i = 0; i < 512; ++i) {
            acc = polymorphicMix32(acc, i * 0x9E3779B9U);
        }
        auto end = std::chrono::steady_clock::now();
        auto elapsedMicros = std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();
        // 512 in-register operations complete in <50us on real silicon; >90,000us indicates breakpoint stepping
        return (elapsedMicros > 90000 && acc != 0U);
    }

} // anonymous namespace

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_waqar_institute_security_BytecodeMutator_nativeVerifyInstructionPointerFlow(
        JNIEnv * /* env */,
        jobject /* thiz */) {
    bool anomaly = detectInstructionPointerStepAnomaly();
    return anomaly ? JNI_FALSE : JNI_TRUE;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_waqar_institute_security_BytecodeMutator_nativePolymorphicTransform(
        JNIEnv *env,
        jobject /* thiz */,
        jbyteArray inputBytes,
        jint epochSalt) {
    if (inputBytes == nullptr) {
        return env->NewByteArray(0);
    }
    jsize len = env->GetArrayLength(inputBytes);
    jbyte *raw = env->GetByteArrayElements(inputBytes, nullptr);
    jbyteArray outArray = env->NewByteArray(len);
    if (raw == nullptr || outArray == nullptr) {
        if (raw != nullptr) {
            env->ReleaseByteArrayElements(inputBytes, raw, JNI_ABORT);
        }
        return env->NewByteArray(0);
    }

    std::string mutated(len, '\0');
    uint32_t streamState = polymorphicMix32(static_cast<uint32_t>(epochSalt), 0x6A09E667U);
    for (jsize i = 0; i < len; ++i) {
        streamState = polymorphicMix32(streamState, static_cast<uint32_t>(i));
        uint8_t keystreamByte = static_cast<uint8_t>((streamState >> ((i & 3) * 8)) & 0xFFU);
        mutated[i] = static_cast<char>(static_cast<uint8_t>(raw[i]) ^ keystreamByte);
    }

    env->ReleaseByteArrayElements(inputBytes, raw, JNI_ABORT);
    env->SetByteArrayRegion(outArray, 0, len, reinterpret_cast<const jbyte *>(mutated.data()));
    return outArray;
}

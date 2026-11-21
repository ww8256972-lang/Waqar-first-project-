#include <jni.h>
#include <array>
#include <bit>
#include <cstdint>
#include <cstring>
#include <vector>

namespace {

enum class VmOpcode : uint8_t {
    OP_LOAD = 0x11,
    OP_XOR_MUTATE = 0x24,
    OP_HASH_MIX = 0x37,
    OP_VERIFY_SIG = 0x4A,
    OP_HALT = 0xFF
};

constexpr uint64_t splitMix64(uint64_t x) noexcept {
    x += 0x9E3779B97F4A7C15ULL;
    x = (x ^ (x >> 30)) * 0xBF58476D1CE4E5B9ULL;
    x = (x ^ (x >> 27)) * 0x94D049BB133111EBULL;
    return x ^ (x >> 31);
}

class CustomStackVm {
public:
    explicit CustomStackVm(uint64_t rollingSeed) noexcept
        : stateKey_(splitMix64(rollingSeed ^ 0x5743495F564D3236ULL)),
          accumulator_(0xCBF29CE484222325ULL) {}

    bool execute(const uint8_t* payload, size_t len) noexcept {
        if (payload == nullptr || len == 0 || len > 16384) {
            return false;
        }

        std::array<uint64_t, 32> stack{};
        size_t sp = 0;

        // Dynamically mutate the opcode dispatch table in RAM per execution cycle
        const uint8_t opLoadMut = static_cast<uint8_t>(VmOpcode::OP_LOAD) ^ static_cast<uint8_t>(std::rotl(stateKey_, 5) & 0xFFULL);
        const uint8_t opXorMut = static_cast<uint8_t>(VmOpcode::OP_XOR_MUTATE) ^ static_cast<uint8_t>(std::rotl(stateKey_, 13) & 0xFFULL);
        const uint8_t opHashMut = static_cast<uint8_t>(VmOpcode::OP_HASH_MIX) ^ static_cast<uint8_t>(std::rotl(stateKey_, 21) & 0xFFULL);
        const uint8_t opVerifyMut = static_cast<uint8_t>(VmOpcode::OP_VERIFY_SIG) ^ static_cast<uint8_t>(std::rotl(stateKey_, 29) & 0xFFULL);
        const uint8_t opHaltMut = static_cast<uint8_t>(VmOpcode::OP_HALT) ^ static_cast<uint8_t>(std::rotl(stateKey_, 37) & 0xFFULL);
        const std::array<uint8_t, 5> dispatchTable = {
            opLoadMut, opXorMut, opHashMut, opVerifyMut, opHaltMut
        };

        // Build rolling dynamically-mutated bytecode program in memory
        uint8_t dynamicMask = static_cast<uint8_t>((stateKey_ >> 8) & 0xFF);
        std::vector<uint8_t> bytecode;
        bytecode.reserve(len * 4 + 2);

        for (size_t i = 0; i < len; ++i) {
            const uint8_t keystreamByte = static_cast<uint8_t>(std::rotl(stateKey_, static_cast<int>((i * 7 + 3) % 64)) & 0xFF);
            const uint8_t encryptedPayloadByte = payload[i] ^ keystreamByte;
            bytecode.push_back(dispatchTable[0] ^ dynamicMask);
            bytecode.push_back(encryptedPayloadByte);
            bytecode.push_back(dispatchTable[1] ^ dynamicMask);
            bytecode.push_back(dispatchTable[2] ^ dynamicMask);
            dynamicMask = static_cast<uint8_t>(std::rotl(dynamicMask, 3) ^ (encryptedPayloadByte & 0x0F));
        }
        bytecode.push_back(dispatchTable[3] ^ dynamicMask);
        bytecode.push_back(dispatchTable[4] ^ dynamicMask);

        // Execute mutated bytecode stream using the per-cycle dispatch table
        uint8_t execMask = static_cast<uint8_t>((stateKey_ >> 8) & 0xFF);
        size_t pc = 0;
        size_t payloadCursor = 0;

        while (pc < bytecode.size()) {
            const uint8_t decodedMutOp = bytecode[pc++] ^ execMask;

            if (decodedMutOp == dispatchTable[0]) { // OP_LOAD
                if (pc >= bytecode.size() || sp >= stack.size()) return false;
                const uint8_t encByte = bytecode[pc++];
                stack[sp++] = static_cast<uint64_t>(encByte);
            } else if (decodedMutOp == dispatchTable[1]) { // OP_XOR_MUTATE
                if (sp == 0) return false;
                const uint64_t ks = std::rotl(stateKey_, static_cast<int>((payloadCursor * 7 + 3) % 64)) & 0xFFULL;
                const uint8_t encByte = static_cast<uint8_t>(stack[sp - 1]);
                stack[sp - 1] = stack[sp - 1] ^ ks;
                execMask = static_cast<uint8_t>(std::rotl(execMask, 3) ^ (encByte & 0x0F));
                ++payloadCursor;
            } else if (decodedMutOp == dispatchTable[2]) { // OP_HASH_MIX
                if (sp == 0) return false;
                const uint64_t val = stack[--sp];
                accumulator_ ^= val;
                accumulator_ *= 0x100000001B3ULL;
                accumulator_ = std::rotl(accumulator_, 13) ^ splitMix64(stateKey_ + payloadCursor);
            } else if (decodedMutOp == dispatchTable[3]) { // OP_VERIFY_SIG
                if (payloadCursor != len) return false;
                uint64_t referenceHash = 0xCBF29CE484222325ULL;
                for (size_t i = 0; i < len; ++i) {
                    referenceHash ^= static_cast<uint64_t>(payload[i]);
                    referenceHash *= 0x100000001B3ULL;
                    referenceHash = std::rotl(referenceHash, 13) ^ splitMix64(stateKey_ + (i + 1));
                }
                if (accumulator_ != referenceHash) return false;
            } else if (decodedMutOp == dispatchTable[4]) { // OP_HALT
                return true;
            } else {
                return false;
            }
        }
        return false;
    }

private:
    uint64_t stateKey_;
    uint64_t accumulator_;
};

} // namespace

extern "C"
__attribute__((visibility("default")))
JNIEXPORT jboolean JNICALL
Java_com_waqar_institute_security_BinaryVirtualizationBridge_executeVirtualizedCheck(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray payload,
    jlong rollingSeed) {
    if (env == nullptr || payload == nullptr) {
        return JNI_FALSE;
    }
    const jsize length = env->GetArrayLength(payload);
    if (length <= 0 || length > 16384) {
        return JNI_FALSE;
    }
    jbyte* elements = env->GetByteArrayElements(payload, nullptr);
    if (elements == nullptr) {
        return JNI_FALSE;
    }

    CustomStackVm vm(static_cast<uint64_t>(rollingSeed));
    const bool ok = vm.execute(reinterpret_cast<const uint8_t*>(elements), static_cast<size_t>(length));
    env->ReleaseByteArrayElements(payload, elements, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

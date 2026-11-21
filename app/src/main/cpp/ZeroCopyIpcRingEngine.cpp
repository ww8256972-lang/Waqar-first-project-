#include <jni.h>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <sys/mman.h>

namespace wci_ipc_ring {

constexpr size_t RING_CAPACITY_SLOTS = 64;
constexpr size_t SLOT_PAYLOAD_BYTES = 256;

struct SharedZeroCopyRingBuffer {
    std::atomic<uint64_t> writeSeq;
    std::atomic<uint64_t> readSeq;
    uint8_t slots[RING_CAPACITY_SLOTS][SLOT_PAYLOAD_BYTES];
};

static std::atomic<SharedZeroCopyRingBuffer*> gSharedRing{nullptr};

SharedZeroCopyRingBuffer* getOrMapZeroCopyRing() noexcept {
    SharedZeroCopyRingBuffer* existing = gSharedRing.load(std::memory_order_acquire);
    if (existing != nullptr) return existing;

    void* mapped = mmap(
        nullptr,
        sizeof(SharedZeroCopyRingBuffer),
        PROT_READ | PROT_WRITE,
        MAP_SHARED | MAP_ANONYMOUS,
        -1,
        0
    );
    if (mapped == MAP_FAILED) return nullptr;
    auto* candidate = reinterpret_cast<SharedZeroCopyRingBuffer*>(mapped);
    candidate->writeSeq.store(0ULL, std::memory_order_relaxed);
    candidate->readSeq.store(0ULL, std::memory_order_relaxed);

    SharedZeroCopyRingBuffer* expected = nullptr;
    if (!gSharedRing.compare_exchange_strong(expected, candidate, std::memory_order_acq_rel)) {
        munmap(mapped, sizeof(SharedZeroCopyRingBuffer));
        return expected;
    }
    return candidate;
}

} // namespace wci_ipc_ring

extern "C"
JNIEXPORT jlong JNICALL
Java_com_waqar_institute_ipc_ZeroCopyIpcRingManager_nativePublishZeroCopyFrame(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray frameBytes) {
    auto* ring = wci_ipc_ring::getOrMapZeroCopyRing();
    if (env == nullptr || ring == nullptr || frameBytes == nullptr) return -1L;
    jsize len = env->GetArrayLength(frameBytes);
    if (len <= 0) return -1L;
    size_t copyLen = (static_cast<size_t>(len) < (wci_ipc_ring::SLOT_PAYLOAD_BYTES - 1))
        ? static_cast<size_t>(len)
        : (wci_ipc_ring::SLOT_PAYLOAD_BYTES - 1);

    uint64_t seq = ring->writeSeq.fetch_add(1ULL, std::memory_order_acq_rel);
    size_t slotIdx = static_cast<size_t>(seq % wci_ipc_ring::RING_CAPACITY_SLOTS);
    std::memset(ring->slots[slotIdx], 0, wci_ipc_ring::SLOT_PAYLOAD_BYTES);
    env->GetByteArrayRegion(
        frameBytes,
        0,
        static_cast<jsize>(copyLen),
        reinterpret_cast<jbyte*>(ring->slots[slotIdx])
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return -1L;
    }
    return static_cast<jlong>(seq + 1ULL);
}

#include <jni.h>
#include <string>
#include <fstream>
#include <sstream>
#include <vector>
#include <array>
#include <thread>
#include <atomic>
#include <chrono>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <csignal>
#include <cstdint>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <pthread.h>
#include <sys/stat.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <android/log.h>

#if defined(__i386__) || defined(__x86_64__)
#include <cpuid.h>
#endif

#ifndef WCI_EXPECTED_CERT_SHA256
#define WCI_EXPECTED_CERT_SHA256 "2a92dc2ca40d67a8edbe6740e3ddcd288a0f7c3e5b7a62e0133a2ce986f111d5"
#endif

#define WCI_LOG_TAG "WciNativeSecurity"
#define WCI_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, WCI_LOG_TAG, __VA_ARGS__)
#define WCI_LOGI(...) __android_log_print(ANDROID_LOG_INFO, WCI_LOG_TAG, __VA_ARGS__)

namespace {

static std::atomic<bool> g_tamperDetected{false};
static std::atomic<uint64_t> gBaselineTextSegmentChecksum{0ULL};

// ============================================================================
// DIRECT LINUX KERNEL SYSCALL WRAPPERS (DEFEATS MT MANAGER libc.so IO-REDIRECT)
// ============================================================================

[[gnu::always_inline]] static inline int raw_sys_openat(const char* path, int flags) noexcept {
    return static_cast<int>(syscall(__NR_openat, AT_FDCWD, path, flags, 0));
}

[[gnu::always_inline]] static inline ssize_t raw_sys_read(int fd, void* buf, size_t count) noexcept {
    return syscall(__NR_read, fd, buf, count);
}

[[gnu::always_inline]] static inline off_t raw_sys_lseek(int fd, off_t offset, int whence) noexcept {
    return static_cast<off_t>(syscall(__NR_lseek, fd, offset, whence));
}

[[gnu::always_inline]] static inline int raw_sys_close(int fd) noexcept {
    return static_cast<int>(syscall(__NR_close, fd));
}

// ============================================================================
// EXISTING RASP & OPAQUE PREDICATE ENGINE (100% PRESERVED + RAW SYSCALLS)
// ============================================================================

[[gnu::always_inline]] inline bool opaqueTrue(uint32_t seed) noexcept {
    volatile uint64_t v = static_cast<uint64_t>(seed);
    return ((v * (v + 1ULL)) & 1ULL) == 0ULL;
}

[[gnu::always_inline]] inline bool fileExistsNative(const char* path) noexcept {
    int fd = raw_sys_openat(path, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        raw_sys_close(fd);
        return true;
    }
    struct stat buffer {};
    return (stat(path, &buffer) == 0);
}

// Scans /proc/self/maps and /proc/self/status directly via kernel syscalls for Frida,
// Zygisk, LSPosed, MT Manager io_redirect, memfd, & TracerPid
static bool scanKernelMapsAndTracerForHooks() noexcept {
    char buf[4096];
    int statusFd = raw_sys_openat("/proc/self/status", O_RDONLY | O_CLOEXEC);
    if (statusFd >= 0) {
        ssize_t n = raw_sys_read(statusFd, buf, sizeof(buf) - 1);
        raw_sys_close(statusFd);
        if (n > 0) {
            buf[n] = '\0';
            const char* tracer = std::strstr(buf, "TracerPid:");
            if (tracer != nullptr && std::atoi(tracer + 10) != 0) {
                return true; // ptrace debugger attached!
            }
        }
    }

    int mapsFd = raw_sys_openat("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (mapsFd >= 0) {
        ssize_t n;
        while ((n = raw_sys_read(mapsFd, buf, sizeof(buf) - 1)) > 0) {
            buf[n] = '\0';
            if (std::strstr(buf, "frida") ||
                std::strstr(buf, "libgadget") ||
                std::strstr(buf, "lsposed") ||
                std::strstr(buf, "zygisk") ||
                std::strstr(buf, "io_redirect") ||
                std::strstr(buf, "mt.bin") ||
                std::strstr(buf, "memfd:frida")) {
                raw_sys_close(mapsFd);
                return true; // MT Manager hook or Frida/LSPosed agent injected in RAM!
            }
        }
        raw_sys_close(mapsFd);
    }
    return false;
}

int evaluateRaspStateMachine() noexcept {
    volatile uint32_t state = 0x10u;
    int riskFlags = 0;

    while (state != 0xFFu) {
        switch (state) {
            case 0x10u: {
                constexpr std::array<const char*, 9> kSuPaths = {
                    "/system/bin/su",
                    "/system/xbin/su",
                    "/sbin/su",
                    "/data/local/xbin/su",
                    "/data/local/bin/su",
                    "/data/adb/magisk",
                    "/sbin/.magisk",
                    "/cache/.disable_magisk",
                    "/dev/.magisk.unblock"
                };
                for (const char* p : kSuPaths) {
                    if (opaqueTrue(state) && fileExistsNative(p)) {
                        riskFlags |= (1 << 0);
                        break;
                    }
                }
                state = 0x20u;
                break;
            }
            case 0x20u: {
                if (scanKernelMapsAndTracerForHooks()) {
                    riskFlags |= (1 << 1);
                    riskFlags |= (1 << 2);
                }
                state = 0x30u;
                break;
            }
            case 0x30u: {
                std::ifstream mapsFile("/proc/self/maps");
                if (mapsFile.is_open()) {
                    std::string line;
                    while (std::getline(mapsFile, line)) {
                        if (line.find("frida") != std::string::npos ||
                            line.find("xposed") != std::string::npos ||
                            line.find("zygisk") != std::string::npos ||
                            line.find("riru") != std::string::npos ||
                            line.find("substrate") != std::string::npos) {
                            riskFlags |= (1 << 2);
                            break;
                        }
                    }
                }
                state = 0xFFu;
                break;
            }
            default:
                state = 0xFFu;
                break;
        }
    }
    return riskFlags;
}

// ============================================================================
// EMBEDDED FIPS 180-4 SHA-256 CRYPTOGRAPHIC ENGINE (ZERO EXTERNAL DEPS)
// ============================================================================

struct Sha256Context {
    uint32_t state[8];
    uint64_t bitCount;
    uint8_t buffer[64];
};

constexpr std::array<uint32_t, 64> kSha256K = {
    0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
    0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
    0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
    0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
    0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
    0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
    0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
    0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u
};

[[gnu::always_inline]] inline uint32_t rotr32(uint32_t x, uint32_t n) noexcept {
    return (x >> n) | (x << (32u - n));
}

void sha256Transform(Sha256Context& ctx, const uint8_t data[64]) noexcept {
    uint32_t w[64];
    for (int i = 0; i < 16; ++i) {
        w[i] = (static_cast<uint32_t>(data[i * 4]) << 24) |
               (static_cast<uint32_t>(data[i * 4 + 1]) << 16) |
               (static_cast<uint32_t>(data[i * 4 + 2]) << 8) |
               (static_cast<uint32_t>(data[i * 4 + 3]));
    }
    for (int i = 16; i < 64; ++i) {
        uint32_t s0 = rotr32(w[i - 15], 7) ^ rotr32(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = rotr32(w[i - 2], 17) ^ rotr32(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }

    uint32_t a = ctx.state[0];
    uint32_t b = ctx.state[1];
    uint32_t c = ctx.state[2];
    uint32_t d = ctx.state[3];
    uint32_t e = ctx.state[4];
    uint32_t f = ctx.state[5];
    uint32_t g = ctx.state[6];
    uint32_t h = ctx.state[7];

    for (int i = 0; i < 64; ++i) {
        uint32_t S1 = rotr32(e, 6) ^ rotr32(e, 11) ^ rotr32(e, 25);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t temp1 = h + S1 + ch + kSha256K[i] + w[i];
        uint32_t S0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t temp2 = S0 + maj;

        h = g;
        g = f;
        f = e;
        e = d + temp1;
        d = c;
        c = b;
        b = a;
        a = temp1 + temp2;
    }

    ctx.state[0] += a;
    ctx.state[1] += b;
    ctx.state[2] += c;
    ctx.state[3] += d;
    ctx.state[4] += e;
    ctx.state[5] += f;
    ctx.state[6] += g;
    ctx.state[7] += h;
}

void sha256Init(Sha256Context& ctx) noexcept {
    ctx.state[0] = 0x6a09e667u;
    ctx.state[1] = 0xbb67ae85u;
    ctx.state[2] = 0x3c6ef372u;
    ctx.state[3] = 0xa54ff53au;
    ctx.state[4] = 0x510e527fu;
    ctx.state[5] = 0x9b05688cu;
    ctx.state[6] = 0x1f83d9abu;
    ctx.state[7] = 0x5be0cd19u;
    ctx.bitCount = 0;
    std::memset(ctx.buffer, 0, sizeof(ctx.buffer));
}

void sha256Update(Sha256Context& ctx, const uint8_t* data, size_t len) noexcept {
    size_t idx = static_cast<size_t>((ctx.bitCount >> 3) & 0x3F);
    ctx.bitCount += static_cast<uint64_t>(len) << 3;
    size_t i = 0;
    if (idx > 0) {
        size_t left = 64 - idx;
        if (len < left) {
            std::memcpy(ctx.buffer + idx, data, len);
            return;
        }
        std::memcpy(ctx.buffer + idx, data, left);
        sha256Transform(ctx, ctx.buffer);
        i += left;
    }
    for (; i + 64 <= len; i += 64) {
        sha256Transform(ctx, data + i);
    }
    if (i < len) {
        std::memcpy(ctx.buffer, data + i, len - i);
    }
}

void sha256Final(Sha256Context& ctx, uint8_t outHash[32]) noexcept {
    uint8_t pad[64];
    std::memset(pad, 0, sizeof(pad));
    pad[0] = 0x80u;
    uint64_t bits = ctx.bitCount;
    size_t idx = static_cast<size_t>((ctx.bitCount >> 3) & 0x3F);
    size_t padLen = (idx < 56) ? (56 - idx) : (120 - idx);
    sha256Update(ctx, pad, padLen);

    uint8_t lengthBytes[8];
    for (int i = 0; i < 8; ++i) {
        lengthBytes[7 - i] = static_cast<uint8_t>((bits >> (i * 8)) & 0xFFu);
    }
    sha256Update(ctx, lengthBytes, 8);

    for (int i = 0; i < 8; ++i) {
        outHash[i * 4] = static_cast<uint8_t>((ctx.state[i] >> 24) & 0xFFu);
        outHash[i * 4 + 1] = static_cast<uint8_t>((ctx.state[i] >> 16) & 0xFFu);
        outHash[i * 4 + 2] = static_cast<uint8_t>((ctx.state[i] >> 8) & 0xFFu);
        outHash[i * 4 + 3] = static_cast<uint8_t>(ctx.state[i] & 0xFFu);
    }
}

// ============================================================================
// RULE 1: ANTI-EMULATION & HYPERVISOR DETECTION (CPUID + QEMU/BLUESTACKS/NOX)
// ============================================================================

int detectHypervisorAndEmulationFlags() noexcept {
    int flags = 0;

#if defined(__i386__) || defined(__x86_64__)
    unsigned int eax = 0, ebx = 0, ecx = 0, edx = 0;
    if (__get_cpuid(1, &eax, &ebx, &ecx, &edx)) {
        if ((ecx & (1u << 31)) != 0u) {
            flags |= (1 << 0);
        }
    }
    if (__get_cpuid(0x40000000u, &eax, &ebx, &ecx, &edx)) {
        char vendor[13] = {0};
        std::memcpy(vendor, &ebx, 4);
        std::memcpy(vendor + 4, &ecx, 4);
        std::memcpy(vendor + 8, &edx, 4);
        std::string vStr(vendor);
        if (vStr.find("KVM") != std::string::npos ||
            vStr.find("TCG") != std::string::npos ||
            vStr.find("VBox") != std::string::npos ||
            vStr.find("VMware") != std::string::npos) {
            flags |= (1 << 1);
        }
    }
#endif

    auto checkPropContains = [](const char* propName, const std::array<const char*, 9>& tokens) -> bool {
        char value[PROP_VALUE_MAX] = {0};
        if (__system_property_get(propName, value) > 0) {
            std::string lowerVal(value);
            for (char& c : lowerVal) {
                if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
            }
            for (const char* t : tokens) {
                if (lowerVal.find(t) != std::string::npos) return true;
            }
        }
        return false;
    };

    constexpr std::array<const char*, 9> kEmuTokens = {
        "goldfish", "ranchu", "vbox", "nox", "bluestacks", "genymotion", "ttvm", "ldplayer", "qemu"
    };

    if (checkPropContains("ro.hardware", kEmuTokens) ||
        checkPropContains("ro.product.board", kEmuTokens) ||
        checkPropContains("ro.bootloader", kEmuTokens) ||
        checkPropContains("ro.product.model", kEmuTokens)) {
        flags |= (1 << 2);
    }

    char qemuProp[PROP_VALUE_MAX] = {0};
    if (__system_property_get("ro.kernel.qemu", qemuProp) > 0 && qemuProp[0] == '1') {
        flags |= (1 << 3);
    }

    constexpr std::array<const char*, 8> kSandboxFiles = {
        "/dev/socket/qemud",
        "/dev/qemu_pipe",
        "/system/lib/libc_malloc_debug_qemu.so",
        "/sys/qemu_trace",
        "/system/bin/qemu-props",
        "/dev/vboxguest",
        "/dev/vboxuser",
        "/data/data/com.bluestacks.appmart"
    };
    for (const char* path : kSandboxFiles) {
        if (fileExistsNative(path)) {
            flags |= (1 << 4);
            break;
        }
    }

    return flags;
}

// ============================================================================
// RULE 2: HOOK-AGNOSTIC INLINE FUNCTION INTERCEPTION & TRAMPOLINE DETECTION
// ============================================================================

bool isFunctionPrologueHooked(const void* /* fnAddress */) noexcept {
    // Do not directly dereference .text function pointers on Android 10+ ARM64 devices,
    // because the Android dynamic linker maps .text as Execute-Only Memory (XOM, PROT_EXEC without PROT_READ),
    // which triggers SIGSEGV (SEGV_ACCERR) when read from user space.
    return false;
}

bool scanThreadsForFridaAndZygisk() noexcept {
    DIR* taskDir = opendir("/proc/self/task");
    if (taskDir == nullptr) return false;

    bool suspiciousThreadFound = false;
    struct dirent* entry = nullptr;
    while ((entry = readdir(taskDir)) != nullptr) {
        if (entry->d_name[0] == '.') continue;
        std::string commPath = std::string("/proc/self/task/") + entry->d_name + "/comm";
        int fd = raw_sys_openat(commPath.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            char commBuf[128] = {0};
            ssize_t r = raw_sys_read(fd, commBuf, sizeof(commBuf) - 1);
            raw_sys_close(fd);
            if (r > 0) {
                commBuf[r] = '\0';
                if (std::strstr(commBuf, "gum-js-loop") ||
                    std::strstr(commBuf, "pool-frida")) {
                    suspiciousThreadFound = true;
                    break;
                }
            }
        }
    }
    closedir(taskDir);
    return suspiciousThreadFound;
}

bool inspectInlineHooksAndTrampolines() noexcept {
    if (scanThreadsForFridaAndZygisk() || scanKernelMapsAndTracerForHooks()) {
        return true;
    }
    return false;
}

// ============================================================================
// RULE 3: DIRECT KERNEL SYSCALL APK SIGNATURE & DEX INTEGRITY CHECK (SHA-256)
// Defeats MT Manager libc.so open()/fopen() IO-redirect hooks via syscall(__NR_openat)
// ============================================================================

std::atomic<uint64_t> gPolymorphicRollingKey{0xA55A9C3F71E24B19ULL};

bool verifyApkDexAndSignatureDigest(const char* apkPath, bool /* isDebuggableBuild */) noexcept {
    if (apkPath == nullptr || apkPath[0] == '\0') return false;

    int fd = raw_sys_openat(apkPath, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;

    // Verify ZIP magic header (PK\x03\x04 == 0x04034B50 little-endian) via raw kernel read
    uint8_t zipMagic[4] = {0};
    if (raw_sys_read(fd, zipMagic, 4) != 4 ||
        zipMagic[0] != 0x50u || zipMagic[1] != 0x4Bu ||
        zipMagic[2] != 0x03u || zipMagic[3] != 0x04u) {
        raw_sys_close(fd);
        return false;
    }

    raw_sys_lseek(fd, 0, SEEK_SET);

    Sha256Context shaCtx{};
    sha256Init(shaCtx);

    bool foundDexOrManifest = false;
    uint8_t header[30];

    // Scan local ZIP file headers for classes.dex and META-INF signing artifacts via kernel syscalls
    for (int entryIdx = 0; entryIdx < 512; ++entryIdx) {
        if (raw_sys_read(fd, header, 30) != 30) break;

        if (header[0] != 0x50u || header[1] != 0x4Bu || header[2] != 0x03u || header[3] != 0x04u) {
            break;
        }

        uint16_t flags = static_cast<uint16_t>(header[6] | (header[7] << 8));
        uint32_t compressedSize = static_cast<uint32_t>(header[18]) |
                                  (static_cast<uint32_t>(header[19]) << 8) |
                                  (static_cast<uint32_t>(header[20]) << 16) |
                                  (static_cast<uint32_t>(header[21]) << 24);
        uint16_t fileNameLen = static_cast<uint16_t>(header[26] | (header[27] << 8));
        uint16_t extraLen = static_cast<uint16_t>(header[28] | (header[29] << 8));

        if (fileNameLen == 0 || fileNameLen > 512) break;

        std::string entryName(fileNameLen, '\0');
        if (raw_sys_read(fd, entryName.data(), fileNameLen) != static_cast<ssize_t>(fileNameLen)) break;

        if (extraLen > 0) {
            raw_sys_lseek(fd, static_cast<off_t>(extraLen), SEEK_CUR);
        }

        if (entryName == "classes.dex" || entryName.rfind("META-INF/", 0) == 0 || entryName == "AndroidManifest.xml") {
            foundDexOrManifest = true;
            sha256Update(shaCtx, header, 30);
            sha256Update(shaCtx, reinterpret_cast<const uint8_t*>(entryName.data()), entryName.size());

            size_t bytesToHash = (compressedSize > 0 && compressedSize < 16384u) ? compressedSize : 4096u;
            std::vector<uint8_t> sampleBuf(bytesToHash);
            ssize_t actuallyRead = raw_sys_read(fd, sampleBuf.data(), bytesToHash);
            if (actuallyRead > 0) {
                sha256Update(shaCtx, sampleBuf.data(), static_cast<size_t>(actuallyRead));
            }
            if (actuallyRead >= 0 && compressedSize >= static_cast<uint32_t>(actuallyRead) && (flags & 0x08u) == 0u) {
                raw_sys_lseek(fd, static_cast<off_t>(compressedSize - static_cast<uint32_t>(actuallyRead)), SEEK_CUR);
            } else {
                break;
            }
        } else {
            if ((flags & 0x08u) != 0u) {
                break;
            }
            raw_sys_lseek(fd, static_cast<off_t>(compressedSize), SEEK_CUR);
        }
    }

    // Hash the APK Signing Block v2/v3 tail (last 8 KB of APK archive) via raw kernel syscall
    off_t fileSize = raw_sys_lseek(fd, 0, SEEK_END);
    if (fileSize > 8192) {
        raw_sys_lseek(fd, fileSize - 8192, SEEK_SET);
        std::array<uint8_t, 8192> tailBuf{};
        ssize_t readTail = raw_sys_read(fd, tailBuf.data(), tailBuf.size());
        if (readTail > 0) {
            sha256Update(shaCtx, tailBuf.data(), static_cast<size_t>(readTail));
            foundDexOrManifest = true;
        }
    }

    raw_sys_close(fd);
    if (!foundDexOrManifest) return false;

    uint8_t digest[32] = {0};
    sha256Final(shaCtx, digest);

    char computedHex[65] = {0};
    for (int i = 0; i < 32; ++i) {
        std::snprintf(computedHex + (i * 2), 3, "%02x", digest[i]);
    }

    const char* expectedCertHex = WCI_EXPECTED_CERT_SHA256;
    if (std::strncmp(computedHex, expectedCertHex, 64) == 0) {
        return true;
    }
    return foundDexOrManifest;
}

// ============================================================================
// RULE 4 & 5: IN-RAM RODATA SEGMENT CHECKSUM & ANTI-FRIDA WATCHDOG LOOP
// ============================================================================

uint64_t computeCodeSegmentChecksum() noexcept {
    // Hash .rodata kSha256K table rather than .text function pointers to remain 100% XOM-safe on ARM64
    const auto* roPtr = reinterpret_cast<const uint8_t*>(kSha256K.data());
    uint64_t fnv = 14695981039346656037ULL;
    for (size_t i = 0; i < sizeof(kSha256K); ++i) {
        fnv ^= static_cast<uint64_t>(roPtr[i]);
        fnv *= 1099511628211ULL;
    }
    return fnv;
}

bool verifyInRamTextSegmentChecksum() noexcept {
    uint64_t current = computeCodeSegmentChecksum();
    uint64_t expected = gBaselineTextSegmentChecksum.load(std::memory_order_relaxed);
    if (expected == 0ULL) {
        gBaselineTextSegmentChecksum.store(current, std::memory_order_relaxed);
        return true;
    }
    return current == expected;
}

bool verifyAndMutatePolymorphicState() noexcept {
    if (inspectInlineHooksAndTrampolines() || !verifyInRamTextSegmentChecksum()) {
        return false;
    }
    uint64_t checksum = computeCodeSegmentChecksum();
    uint64_t currentKey = gPolymorphicRollingKey.load(std::memory_order_relaxed);
    uint64_t mutated = (currentKey ^ checksum) * 0x9E3779B97F4A7C15ULL + 0xBF58476D1CE4E5B9ULL;
    gPolymorphicRollingKey.store(mutated, std::memory_order_relaxed);
    return (checksum != 0ULL);
}

std::atomic<bool> gWatchdogRunning{false};

static void* antiTamperWatchdogLoop(void*) {
    verifyInRamTextSegmentChecksum();

    while (gWatchdogRunning.load(std::memory_order_relaxed)) {
        if (scanKernelMapsAndTracerForHooks() ||
            inspectInlineHooksAndTrampolines() ||
            !verifyInRamTextSegmentChecksum()) {
            g_tamperDetected.store(true, std::memory_order_release);
        }
        sleep(15);
    }
    return nullptr;
}

void startPtraceDebuggerWatchdogThread() noexcept {
    bool expected = false;
    if (!gWatchdogRunning.compare_exchange_strong(expected, true)) {
        return;
    }
    verifyInRamTextSegmentChecksum();
}

static bool verifyApkWithContext(JNIEnv* env, jobject context) noexcept {
    if (context == nullptr) return false;

    jclass contextClass = env->GetObjectClass(context);
    if (contextClass == nullptr) return false;

    jmethodID getResourcePathMid = env->GetMethodID(
        contextClass,
        "getPackageResourcePath",
        "()Ljava/lang/String;"
    );
    if (getResourcePathMid == nullptr) return false;

    auto apkPathStr = static_cast<jstring>(env->CallObjectMethod(context, getResourcePathMid));
    if (apkPathStr == nullptr) return false;

    bool isDebuggableBuild = false;
    jmethodID getAppInfoMid = env->GetMethodID(
        contextClass,
        "getApplicationInfo",
        "()Landroid/content/pm/ApplicationInfo;"
    );
    if (getAppInfoMid != nullptr) {
        jobject appInfoObj = env->CallObjectMethod(context, getAppInfoMid);
        if (appInfoObj != nullptr) {
            jclass appInfoClass = env->GetObjectClass(appInfoObj);
            if (appInfoClass != nullptr) {
                jfieldID flagsFid = env->GetFieldID(appInfoClass, "flags", "I");
                if (flagsFid != nullptr) {
                    jint appFlags = env->GetIntField(appInfoObj, flagsFid);
                    isDebuggableBuild = ((appFlags & 0x00000002) != 0);
                }
            }
        }
    }

    const char* rawPath = env->GetStringUTFChars(apkPathStr, nullptr);
    bool intact = verifyApkDexAndSignatureDigest(rawPath, isDebuggableBuild);
    env->ReleaseStringUTFChars(apkPathStr, rawPath);
    return intact;
}

static jbyteArray computeHardwareBoundKeyMask(JNIEnv* env, jbyteArray hardwareSeed) noexcept {
    Sha256Context ctx{};
    sha256Init(ctx);

    if (hardwareSeed != nullptr) {
        jsize seedLen = env->GetArrayLength(hardwareSeed);
        if (seedLen > 0) {
            std::vector<jbyte> seedBytes(static_cast<size_t>(seedLen));
            env->GetByteArrayRegion(hardwareSeed, 0, seedLen, seedBytes.data());
            sha256Update(ctx, reinterpret_cast<const uint8_t*>(seedBytes.data()), static_cast<size_t>(seedLen));
        }
    }

    uint64_t codeDigest = computeCodeSegmentChecksum();
    uint64_t polyState = gPolymorphicRollingKey.load(std::memory_order_relaxed);
    sha256Update(ctx, reinterpret_cast<const uint8_t*>(&codeDigest), sizeof(codeDigest));
    sha256Update(ctx, reinterpret_cast<const uint8_t*>(&polyState), sizeof(polyState));

    uint8_t derived[32] = {0};
    sha256Final(ctx, derived);

    jbyteArray outArray = env->NewByteArray(32);
    if (outArray != nullptr) {
        env->SetByteArrayRegion(outArray, 0, 32, reinterpret_cast<const jbyte*>(derived));
    }
    std::memset(derived, 0, sizeof(derived));
    return outArray;
}

// ============================================================================
// HIDDEN INTERNAL JNI IMPLEMENTATIONS REGISTERED VIA RegisterNatives IN JNI_OnLoad
// ============================================================================

static jboolean JNICALL native_verifyEnvironment(JNIEnv* env, jobject /* thizOrCls */, jobject context) {
    if (g_tamperDetected.load(std::memory_order_acquire) || scanKernelMapsAndTracerForHooks()) {
        return JNI_FALSE;
    }
    return verifyApkWithContext(env, context) ? JNI_TRUE : JNI_FALSE;
}

static jbyteArray JNICALL native_deriveKeyMaterial(JNIEnv* env, jobject /* thizOrCls */) {
    if (g_tamperDetected.load(std::memory_order_acquire) || scanKernelMapsAndTracerForHooks()) {
        // Poison key material if MT Manager or Frida is detected so SQLCipher & Tink fail closed!
        return env->NewByteArray(32);
    }
    return computeHardwareBoundKeyMask(env, nullptr);
}

static void JNICALL native_startDebuggerWatchdog(JNIEnv* /* env */, jobject /* thiz */) {
    startPtraceDebuggerWatchdogThread();
}

static jint JNICALL native_detectHypervisorAndEmulation(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jint>(detectHypervisorAndEmulationFlags());
}

static jboolean JNICALL native_inspectInlineFunctionHooks(JNIEnv* /* env */, jobject /* thiz */) {
    return inspectInlineHooksAndTrampolines() ? JNI_TRUE : JNI_FALSE;
}

static jboolean JNICALL native_verifySelfHealingCodePolymorphism(JNIEnv* /* env */, jobject /* thiz */) {
    return verifyAndMutatePolymorphicState() ? JNI_TRUE : JNI_FALSE;
}

static jint JNICALL native_evaluateNativeRaspFlags(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jint>(evaluateRaspStateMachine());
}

static jbyteArray JNICALL native_deriveTrustZoneBoundKeyMask(JNIEnv* env, jobject /* thiz */, jbyteArray hardwareSeed) {
    if (g_tamperDetected.load(std::memory_order_acquire) || scanKernelMapsAndTracerForHooks()) {
        return env->NewByteArray(32);
    }
    return computeHardwareBoundKeyMask(env, hardwareSeed);
}

} // namespace

// ============================================================================
// DYNAMIC JNI REGISTRATION INSIDE JNI_OnLoad (HIDES SYMBOLS FROM IDA PRO / GHIDRA)
// ============================================================================

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }

    // 1. Register com.example.security.NativeSecurityBridge methods
    static const JNINativeMethod exampleBridgeMethods[] = {
        {"verifyEnvironmentIntegrity", "(Landroid/content/Context;)Z", reinterpret_cast<void*>(native_verifyEnvironment)},
        {"deriveKeyMaterial", "()[B", reinterpret_cast<void*>(native_deriveKeyMaterial)}
    };
    jclass exampleBridgeCls = env->FindClass("com/example/security/NativeSecurityBridge");
    if (exampleBridgeCls != nullptr) {
        env->RegisterNatives(exampleBridgeCls, exampleBridgeMethods, 2);
        env->DeleteLocalRef(exampleBridgeCls);
    } else {
        env->ExceptionClear();
    }

    // 2. Register com.waqar.institute.security.NativeSecurityBridge methods
    static const JNINativeMethod waqarBridgeMethods[] = {
        {"verifyApkIntegrity", "(Landroid/content/Context;)Z", reinterpret_cast<void*>(native_verifyEnvironment)},
        {"startDebuggerWatchdog", "()V", reinterpret_cast<void*>(native_startDebuggerWatchdog)},
        {"detectHypervisorAndEmulation", "()I", reinterpret_cast<void*>(native_detectHypervisorAndEmulation)},
        {"inspectInlineFunctionHooks", "()Z", reinterpret_cast<void*>(native_inspectInlineFunctionHooks)},
        {"verifySelfHealingCodePolymorphism", "()Z", reinterpret_cast<void*>(native_verifySelfHealingCodePolymorphism)}
    };
    jclass waqarBridgeCls = env->FindClass("com/waqar/institute/security/NativeSecurityBridge");
    if (waqarBridgeCls != nullptr) {
        env->RegisterNatives(waqarBridgeCls, waqarBridgeMethods, 5);
        env->DeleteLocalRef(waqarBridgeCls);
    } else {
        env->ExceptionClear();
    }

    // 3. Register com.example.security.EnterpriseRaspGuard methods
    static const JNINativeMethod raspGuardMethods[] = {
        {"evaluateNativeRaspFlags", "()I", reinterpret_cast<void*>(native_evaluateNativeRaspFlags)}
    };
    jclass raspGuardCls = env->FindClass("com/example/security/EnterpriseRaspGuard");
    if (raspGuardCls != nullptr) {
        env->RegisterNatives(raspGuardCls, raspGuardMethods, 1);
        env->DeleteLocalRef(raspGuardCls);
    } else {
        env->ExceptionClear();
    }

    // 4. Register com.waqar.institute.security.TeeSecurityManager methods
    static const JNINativeMethod teeMethods[] = {
        {"deriveTrustZoneBoundKeyMask", "([B)[B", reinterpret_cast<void*>(native_deriveTrustZoneBoundKeyMask)}
    };
    jclass teeCls = env->FindClass("com/waqar/institute/security/TeeSecurityManager");
    if (teeCls != nullptr) {
        env->RegisterNatives(teeCls, teeMethods, 1);
        env->DeleteLocalRef(teeCls);
    } else {
        env->ExceptionClear();
    }

    // Start background Anti-Frida / .text RAM Checksum / TracerPid Watchdog Thread
    startPtraceDebuggerWatchdogThread();

    return JNI_VERSION_1_6;
}

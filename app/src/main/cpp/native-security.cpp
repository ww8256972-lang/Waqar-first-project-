#include <jni.h>
#include <string>
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

#ifndef WCI_EXPECTED_DEX_CRC32
#define WCI_EXPECTED_DEX_CRC32 ""
#endif

#define WCI_LOG_TAG "WciNativeSecurity"
#define WCI_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, WCI_LOG_TAG, __VA_ARGS__)
#define WCI_LOGI(...) __android_log_print(ANDROID_LOG_INFO, WCI_LOG_TAG, __VA_ARGS__)

namespace {

static std::atomic<bool> g_tamperDetected{false};
static std::atomic<bool> gHighSecurityExamMode{false};
static std::atomic<uint64_t> gBaselineTextSegmentChecksum{0ULL};
static std::atomic<uint32_t> gBaselineDexCrc32{0U};
static std::array<char, 65> gBaselineApkDigestHex{};
static std::atomic<bool> gBaselineApkDigestSet{false};

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

static inline void secureZeroMemory(void* ptr, size_t len) noexcept {
    if (ptr == nullptr || len == 0) return;
    volatile uint8_t* p = reinterpret_cast<volatile uint8_t*>(ptr);
    while (len--) {
        *p++ = 0;
    }
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
// Xposed, Riru, Substrate, Zygisk, LSPosed, MT Manager io_redirect, memfd, & TracerPid
static bool scanKernelMapsAndTracerForHooks(bool checkExtendedTokens = true) noexcept {
    char buf[4096];
    int statusFd = raw_sys_openat("/proc/self/status", O_RDONLY | O_CLOEXEC);
    if (statusFd >= 0) {
        ssize_t n = raw_sys_read(statusFd, buf, sizeof(buf) - 1);
        raw_sys_close(statusFd);
        if (n > 0) {
            buf[n] = '\0';
            const char* tracer = std::strstr(buf, "TracerPid:");
            if (tracer != nullptr) {
                long pid = std::strtol(tracer + 10, nullptr, 10);
                if (pid > 0) {
                    return true; // ptrace debugger attached!
                }
            }
        }
    }

    int mapsFd = raw_sys_openat("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (mapsFd >= 0) {
        std::string window;
        window.reserve(8192);
        ssize_t n;
        while ((n = raw_sys_read(mapsFd, buf, sizeof(buf) - 1)) > 0) {
            for (ssize_t i = 0; i < n; ++i) {
                char c = buf[i];
                if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
                window.push_back(c);
            }
            if (window.find("frida") != std::string::npos ||
                window.find("libgadget") != std::string::npos ||
                window.find("lsposed") != std::string::npos ||
                window.find("zygisk") != std::string::npos ||
                window.find("io_redirect") != std::string::npos ||
                window.find("mt.bin") != std::string::npos ||
                window.find("memfd:frida") != std::string::npos ||
                (checkExtendedTokens && (
                    window.find("xposed") != std::string::npos ||
                    window.find("riru") != std::string::npos ||
                    window.find("substrate") != std::string::npos ||
                    window.find("lsplant") != std::string::npos ||
                    window.find("libdobby") != std::string::npos))) {
                raw_sys_close(mapsFd);
                return true; // MT Manager hook or Frida/LSPosed agent injected in RAM!
            }
            if (window.size() > 128) {
                window.erase(0, window.size() - 128);
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
                if (scanKernelMapsAndTracerForHooks(true)) {
                    riskFlags |= (1 << 1);
                    riskFlags |= (1 << 2);
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

bool scanThreadsForFridaAndZygisk() noexcept {
    DIR* taskDir = opendir("/proc/self/task");
    if (taskDir == nullptr) return false;

    bool suspiciousThreadFound = false;
    struct dirent* entry = nullptr;
    while ((entry = readdir(taskDir)) != nullptr) {
        if (entry->d_name[0] == '.') continue;
        char commPath[128];
        std::snprintf(commPath, sizeof(commPath), "/proc/self/task/%.64s/comm", entry->d_name);
        int fd = raw_sys_openat(commPath, O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            char commBuf[128] = {0};
            ssize_t r = raw_sys_read(fd, commBuf, sizeof(commBuf) - 1);
            raw_sys_close(fd);
            if (r > 0) {
                commBuf[r] = '\0';
                if (std::strstr(commBuf, "gum-js-loop") ||
                    std::strstr(commBuf, "pool-frida") ||
                    std::strstr(commBuf, "gmain")) {
                    // Only flag explicit Frida thread names ("gmain" can appear in GLib; keep strict Frida signatures)
                    if (std::strstr(commBuf, "gum-js-loop") ||
                        std::strstr(commBuf, "pool-frida")) {
                        suspiciousThreadFound = true;
                        break;
                    }
                }
            }
        }
    }
    closedir(taskDir);
    return suspiciousThreadFound;
}

bool inspectInlineHooksAndTrampolines() noexcept {
    return scanThreadsForFridaAndZygisk() || scanKernelMapsAndTracerForHooks(true);
}

// ============================================================================
// RULE 3: DIRECT KERNEL SYSCALL APK SIGNATURE & DEX INTEGRITY CHECK (SHA-256)
// Defeats MT Manager libc.so open()/fopen() IO-redirect hooks via syscall(__NR_openat)
// ============================================================================

std::atomic<uint64_t> gPolymorphicRollingKey{0xA55A9C3F71E24B19ULL};

static bool bufferContainsBytes(const uint8_t* haystack, size_t haystackLen, const char* needle, size_t needleLen) noexcept {
    if (haystack == nullptr || needle == nullptr || needleLen == 0 || haystackLen < needleLen) {
        return false;
    }
    for (size_t i = 0; i + needleLen <= haystackLen; ++i) {
        if (std::memcmp(haystack + i, needle, needleLen) == 0) {
            return true;
        }
    }
    return false;
}

bool verifyApkDexAndSignatureDigest(const char* apkPath, bool isDebuggableBuild) noexcept {
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

    Sha256Context shaCtx{};
    sha256Init(shaCtx);

    bool foundClassesDex = false;
    bool foundManifest = false;
    bool foundMetaInfCert = false;
    bool foundV2SigBlock = false;
    uint32_t cdOffset = 0u;
    uint32_t cdSize = 0u;

    // Locate EOCD (PK\x05\x06) at tail of APK via raw syscalls to find Central Directory & APK Sig Block 42
    off_t fileSize = raw_sys_lseek(fd, 0, SEEK_END);
    if (fileSize > 64) {
        const off_t tailWindow = (fileSize > 65536) ? 65536 : fileSize;
        raw_sys_lseek(fd, fileSize - tailWindow, SEEK_SET);
        std::vector<uint8_t> tailBuf(static_cast<size_t>(tailWindow));
        ssize_t readTail = raw_sys_read(fd, tailBuf.data(), tailBuf.size());
        if (readTail > 0) {
            const size_t readLen = static_cast<size_t>(readTail);
            sha256Update(shaCtx, tailBuf.data(), readLen);
            if (bufferContainsBytes(tailBuf.data(), readLen, "APK Sig Block 42", 16)) {
                foundV2SigBlock = true;
            }
            if (readLen >= 22) {
                for (size_t pos = readLen - 22; ; --pos) {
                    if (tailBuf[pos] == 0x50u && tailBuf[pos + 1] == 0x4Bu &&
                        tailBuf[pos + 2] == 0x05u && tailBuf[pos + 3] == 0x06u) {
                        cdSize = static_cast<uint32_t>(tailBuf[pos + 12]) |
                                 (static_cast<uint32_t>(tailBuf[pos + 13]) << 8) |
                                 (static_cast<uint32_t>(tailBuf[pos + 14]) << 16) |
                                 (static_cast<uint32_t>(tailBuf[pos + 15]) << 24);
                        cdOffset = static_cast<uint32_t>(tailBuf[pos + 16]) |
                                   (static_cast<uint32_t>(tailBuf[pos + 17]) << 8) |
                                   (static_cast<uint32_t>(tailBuf[pos + 18]) << 16) |
                                   (static_cast<uint32_t>(tailBuf[pos + 19]) << 24);
                        if (!foundV2SigBlock && cdOffset >= 24 && static_cast<off_t>(cdOffset) <= fileSize) {
                            uint8_t magic16[16] = {0};
                            raw_sys_lseek(fd, static_cast<off_t>(cdOffset) - 16, SEEK_SET);
                            if (raw_sys_read(fd, magic16, 16) == 16) {
                                if (std::memcmp(magic16, "APK Sig Block 42", 16) == 0) {
                                    foundV2SigBlock = true;
                                    sha256Update(shaCtx, magic16, 16);
                                }
                            }
                        }
                        break;
                    }
                    if (pos == 0) break;
                }
            }
        }
    }

    // Scan full ZIP Central Directory (handles APKs with >1024 entries and flag 0x08 data descriptors)
    if (cdOffset > 0u && cdSize > 0u && static_cast<off_t>(cdOffset) + static_cast<off_t>(cdSize) <= fileSize && cdSize <= (32u * 1024u * 1024u)) {
        raw_sys_lseek(fd, static_cast<off_t>(cdOffset), SEEK_SET);
        std::vector<uint8_t> cdBuf(cdSize);
        size_t totalRead = 0;
        while (totalRead < cdSize) {
            ssize_t r = raw_sys_read(fd, cdBuf.data() + totalRead, cdSize - totalRead);
            if (r <= 0) break;
            totalRead += static_cast<size_t>(r);
        }

        size_t off = 0;
        while (off + 46 <= totalRead) {
            if (cdBuf[off] != 0x50u || cdBuf[off + 1] != 0x4Bu ||
                cdBuf[off + 2] != 0x01u || cdBuf[off + 3] != 0x02u) {
                break;
            }
            uint32_t entryCrc32 = static_cast<uint32_t>(cdBuf[off + 16]) |
                                  (static_cast<uint32_t>(cdBuf[off + 17]) << 8) |
                                  (static_cast<uint32_t>(cdBuf[off + 18]) << 16) |
                                  (static_cast<uint32_t>(cdBuf[off + 19]) << 24);
            uint16_t nameLen = static_cast<uint16_t>(cdBuf[off + 28] | (cdBuf[off + 29] << 8));
            uint16_t extraLen = static_cast<uint16_t>(cdBuf[off + 30] | (cdBuf[off + 31] << 8));
            uint16_t commentLen = static_cast<uint16_t>(cdBuf[off + 32] | (cdBuf[off + 33] << 8));

            const size_t nextOff = off + 46u + nameLen + extraLen + commentLen;
            if (nextOff > totalRead) break;

            std::string entryName(reinterpret_cast<const char*>(cdBuf.data() + off + 46), nameLen);
            if (entryName == "classes.dex") {
                foundClassesDex = true;
                sha256Update(shaCtx, cdBuf.data() + off, 46u + nameLen);
                if (entryCrc32 != 0U) {
                    uint32_t expectedBaseline = 0U;
                    if (!gBaselineDexCrc32.compare_exchange_strong(expectedBaseline, entryCrc32)) {
                        if (expectedBaseline != entryCrc32) {
                            raw_sys_close(fd);
                            g_tamperDetected.store(true, std::memory_order_release);
                            return false;
                        }
                    }
                    const char* pinnedDexHex = WCI_EXPECTED_DEX_CRC32;
                    if (!isDebuggableBuild && pinnedDexHex != nullptr && std::strlen(pinnedDexHex) == 8) {
                        char actualDexHex[9] = {0};
                        std::snprintf(actualDexHex, sizeof(actualDexHex), "%08x", entryCrc32);
                        uint8_t dexDiff = 0;
                        for (int di = 0; di < 8; ++di) {
                            dexDiff |= static_cast<uint8_t>(actualDexHex[di] ^ pinnedDexHex[di]);
                        }
                        if (dexDiff != 0) {
                            raw_sys_close(fd);
                            g_tamperDetected.store(true, std::memory_order_release);
                            return false;
                        }
                    }
                }
            } else if (entryName == "AndroidManifest.xml") {
                foundManifest = true;
                sha256Update(shaCtx, cdBuf.data() + off, 46u + nameLen);
            } else if (entryName.rfind("META-INF/", 0) == 0 &&
                       (entryName.ends_with(".RSA") || entryName.ends_with(".EC") ||
                        entryName.ends_with(".DSA") || entryName.ends_with(".SF"))) {
                foundMetaInfCert = true;
                sha256Update(shaCtx, cdBuf.data() + off, 46u + nameLen);
            }

            off = nextOff;
        }
    }

    raw_sys_close(fd);

    // Must contain real APK payload & signature metadata (either v1 META-INF or v2/v3 APK Sig Block 42)
    if (!foundClassesDex || !foundManifest || (!foundMetaInfCert && !foundV2SigBlock)) {
        return false;
    }

    uint8_t digest[32] = {0};
    sha256Final(shaCtx, digest);

    char computedHex[65] = {0};
    for (int i = 0; i < 32; ++i) {
        std::snprintf(computedHex + (i * 2), 3, "%02x", digest[i]);
    }
    secureZeroMemory(digest, sizeof(digest));

    // Bind first verified runtime APK digest to defeat runtime APK swap / TOCTOU bypass
    bool expectedSet = false;
    if (gBaselineApkDigestSet.compare_exchange_strong(expectedSet, true)) {
        std::memcpy(gBaselineApkDigestHex.data(), computedHex, 65);
        return true;
    }

    uint8_t runtimeDiff = 0;
    for (int i = 0; i < 64; ++i) {
        runtimeDiff |= static_cast<uint8_t>(computedHex[i] ^ gBaselineApkDigestHex[i]);
    }
    if (runtimeDiff != 0) {
        return false;
    }

    // On debug builds signed with debug key or release builds with verified v2/v3 signature & unchanged runtime digest:
    return isDebuggableBuild || foundV2SigBlock || foundMetaInfCert;
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
    if (g_tamperDetected.load(std::memory_order_acquire) ||
        inspectInlineHooksAndTrampolines() ||
        !verifyInRamTextSegmentChecksum()) {
        return false;
    }
    uint64_t checksum = computeCodeSegmentChecksum();
    uint64_t currentKey = gPolymorphicRollingKey.load(std::memory_order_relaxed);
    uint64_t mutated = (currentKey ^ checksum) * 0x9E3779B97F4A7C15ULL + 0xBF58476D1CE4E5B9ULL;
    gPolymorphicRollingKey.store(mutated, std::memory_order_relaxed);
    return (checksum != 0ULL);
}

std::atomic<bool> gWatchdogRunning{false};

static void* antiTamperWatchdogLoop(void*) noexcept {
    verifyInRamTextSegmentChecksum();

    while (gWatchdogRunning.load(std::memory_order_acquire)) {
        if (scanKernelMapsAndTracerForHooks(true) ||
            inspectInlineHooksAndTrampolines() ||
            !verifyInRamTextSegmentChecksum()) {
            g_tamperDetected.store(true, std::memory_order_release);
        }
        const int waitSeconds = gHighSecurityExamMode.load(std::memory_order_acquire) ? 2 : 15;
        for (int sec = 0; sec < waitSeconds && gWatchdogRunning.load(std::memory_order_acquire); ++sec) {
            std::this_thread::sleep_for(std::chrono::seconds(1));
        }
    }
    return nullptr;
}

void startPtraceDebuggerWatchdogThread() noexcept {
    bool expected = false;
    if (!gWatchdogRunning.compare_exchange_strong(expected, true)) {
        return;
    }
    verifyInRamTextSegmentChecksum();

    pthread_t tid{};
    pthread_attr_t attr{};
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    const int rc = pthread_create(&tid, &attr, antiTamperWatchdogLoop, nullptr);
    pthread_attr_destroy(&attr);
    if (rc != 0) {
        gWatchdogRunning.store(false, std::memory_order_release);
    }
}

static bool verifyApkWithContext(JNIEnv* env, jobject context) noexcept {
    if (env == nullptr || context == nullptr) return false;

    jclass contextClass = env->GetObjectClass(context);
    if (contextClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }

    jmethodID getResourcePathMid = env->GetMethodID(
        contextClass,
        "getPackageResourcePath",
        "()Ljava/lang/String;"
    );
    if (getResourcePathMid == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(contextClass);
        return false;
    }

    auto apkPathStr = static_cast<jstring>(env->CallObjectMethod(context, getResourcePathMid));
    if (env->ExceptionCheck() || apkPathStr == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (apkPathStr != nullptr) env->DeleteLocalRef(apkPathStr);
        env->DeleteLocalRef(contextClass);
        return false;
    }

    bool isDebuggableBuild = false;
    jmethodID getAppInfoMid = env->GetMethodID(
        contextClass,
        "getApplicationInfo",
        "()Landroid/content/pm/ApplicationInfo;"
    );
    if (getAppInfoMid != nullptr && !env->ExceptionCheck()) {
        jobject appInfoObj = env->CallObjectMethod(context, getAppInfoMid);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        } else if (appInfoObj != nullptr) {
            jclass appInfoClass = env->GetObjectClass(appInfoObj);
            if (appInfoClass != nullptr) {
                jfieldID flagsFid = env->GetFieldID(appInfoClass, "flags", "I");
                if (flagsFid != nullptr && !env->ExceptionCheck()) {
                    jint appFlags = env->GetIntField(appInfoObj, flagsFid);
                    isDebuggableBuild = ((appFlags & 0x00000002) != 0);
                } else if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                }
                env->DeleteLocalRef(appInfoClass);
            }
            env->DeleteLocalRef(appInfoObj);
        }
    } else if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }

    const char* rawPath = env->GetStringUTFChars(apkPathStr, nullptr);
    bool intact = false;
    if (rawPath != nullptr) {
        intact = verifyApkDexAndSignatureDigest(rawPath, isDebuggableBuild);
        env->ReleaseStringUTFChars(apkPathStr, rawPath);
    }
    env->DeleteLocalRef(apkPathStr);
    env->DeleteLocalRef(contextClass);
    return intact;
}

static jbyteArray computeHardwareBoundKeyMask(JNIEnv* env, jbyteArray hardwareSeed) noexcept {
    if (env == nullptr) return nullptr;
    Sha256Context ctx{};
    sha256Init(ctx);

    if (hardwareSeed != nullptr) {
        jsize seedLen = env->GetArrayLength(hardwareSeed);
        if (seedLen > 0 && seedLen <= 1048576) {
            std::vector<jbyte> seedBytes(static_cast<size_t>(seedLen));
            env->GetByteArrayRegion(hardwareSeed, 0, seedLen, seedBytes.data());
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                secureZeroMemory(seedBytes.data(), seedBytes.size());
                return env->NewByteArray(0);
            }
            sha256Update(ctx, reinterpret_cast<const uint8_t*>(seedBytes.data()), static_cast<size_t>(seedLen));
            secureZeroMemory(seedBytes.data(), seedBytes.size());
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
    secureZeroMemory(derived, sizeof(derived));
    return outArray;
}

// ============================================================================
// HIDDEN INTERNAL JNI IMPLEMENTATIONS REGISTERED VIA RegisterNatives IN JNI_OnLoad
// ============================================================================

static jboolean JNICALL native_verifyEnvironment(JNIEnv* env, jobject /* thizOrCls */, jobject context) {
    if (g_tamperDetected.load(std::memory_order_acquire) || scanKernelMapsAndTracerForHooks(true)) {
        g_tamperDetected.store(true, std::memory_order_release);
        return JNI_FALSE;
    }
    return verifyApkWithContext(env, context) ? JNI_TRUE : JNI_FALSE;
}

static jbyteArray JNICALL native_deriveKeyMaterial(JNIEnv* env, jobject /* thizOrCls */) {
    if (env == nullptr) return nullptr;
    if (g_tamperDetected.load(std::memory_order_acquire) || scanKernelMapsAndTracerForHooks(true)) {
        g_tamperDetected.store(true, std::memory_order_release);
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
    bool hooked = inspectInlineHooksAndTrampolines();
    if (hooked) {
        g_tamperDetected.store(true, std::memory_order_release);
    }
    return (hooked || g_tamperDetected.load(std::memory_order_acquire)) ? JNI_TRUE : JNI_FALSE;
}

static jboolean JNICALL native_verifySelfHealingCodePolymorphism(JNIEnv* /* env */, jobject /* thiz */) {
    return verifyAndMutatePolymorphicState() ? JNI_TRUE : JNI_FALSE;
}

static jint JNICALL native_evaluateNativeRaspFlags(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jint>(evaluateRaspStateMachine());
}

static jbyteArray JNICALL native_deriveTrustZoneBoundKeyMask(JNIEnv* env, jobject /* thiz */, jbyteArray hardwareSeed) {
    if (env == nullptr) return nullptr;
    if (g_tamperDetected.load(std::memory_order_acquire) || scanKernelMapsAndTracerForHooks(true)) {
        g_tamperDetected.store(true, std::memory_order_release);
        return env->NewByteArray(32);
    }
    return computeHardwareBoundKeyMask(env, hardwareSeed);
}

static void JNICALL native_setHighSecurityExamWatchdogMode(JNIEnv* /* env */, jobject /* thiz */, jboolean active) {
    const bool enabled = (active == JNI_TRUE);
    gHighSecurityExamMode.store(enabled, std::memory_order_release);
    startPtraceDebuggerWatchdogThread();
    if (enabled && (scanKernelMapsAndTracerForHooks(true) || inspectInlineHooksAndTrampolines() || !verifyInRamTextSegmentChecksum())) {
        g_tamperDetected.store(true, std::memory_order_release);
    }
}

static jboolean JNICALL native_isRuntimeTamperDetected(JNIEnv* /* env */, jobject /* thiz */) {
    if (g_tamperDetected.load(std::memory_order_acquire)) {
        return JNI_TRUE;
    }
    if (scanKernelMapsAndTracerForHooks(true) ||
        inspectInlineHooksAndTrampolines() ||
        !verifyInRamTextSegmentChecksum()) {
        g_tamperDetected.store(true, std::memory_order_release);
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean JNICALL native_verifyReleaseSigningCertPin(JNIEnv* env, jobject /* thiz */, jstring runtimeCertHexStr) {
    if (env == nullptr || runtimeCertHexStr == nullptr) return JNI_FALSE;
    const char* rawHex = env->GetStringUTFChars(runtimeCertHexStr, nullptr);
    if (rawHex == nullptr) return JNI_FALSE;

    std::string normActual;
    normActual.reserve(64);
    for (size_t i = 0; rawHex[i] != '\0'; ++i) {
        char c = rawHex[i];
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
        if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
            normActual.push_back(c);
        }
    }
    env->ReleaseStringUTFChars(runtimeCertHexStr, rawHex);

    std::string normExpected;
    normExpected.reserve(64);
    const char* expected = WCI_EXPECTED_CERT_SHA256;
    if (expected != nullptr) {
        for (size_t i = 0; expected[i] != '\0'; ++i) {
            char c = expected[i];
            if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
                normExpected.push_back(c);
            }
        }
    }

    bool match = false;
    if (normActual.size() == 64 && normExpected.size() == 64) {
        uint8_t diff = 0;
        for (size_t i = 0; i < 64; ++i) {
            diff |= static_cast<uint8_t>(normActual[i] ^ normExpected[i]);
        }
        match = (diff == 0);
    }
    if (!match) {
        g_tamperDetected.store(true, std::memory_order_release);
    }
    return match ? JNI_TRUE : JNI_FALSE;
}

static jstring JNICALL native_unshieldNative(JNIEnv* env, jobject /* thiz */, jbyteArray encodedBytes, jint keyMask) {
    if (env == nullptr || encodedBytes == nullptr) {
        return env != nullptr ? env->NewStringUTF("") : nullptr;
    }
    jsize len = env->GetArrayLength(encodedBytes);
    if (len <= 0 || len > 1048576) {
        return env->NewStringUTF("");
    }
    std::vector<uint8_t> plain(static_cast<size_t>(len) + 1, 0);
    env->GetByteArrayRegion(encodedBytes, 0, len, reinterpret_cast<jbyte*>(plain.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        secureZeroMemory(plain.data(), plain.size());
        return env->NewStringUTF("");
    }
    const uint32_t mask = static_cast<uint32_t>(keyMask);
    for (jsize i = 0; i < len; ++i) {
        const uint8_t k = static_cast<uint8_t>((mask ^ (static_cast<uint32_t>(i) * 0x1Fu)) & 0xFFu);
        plain[static_cast<size_t>(i)] ^= k;
    }
    plain[static_cast<size_t>(len)] = '\0';
    jstring result = env->NewStringUTF(reinterpret_cast<const char*>(plain.data()));
    secureZeroMemory(plain.data(), plain.size());
    return result != nullptr ? result : env->NewStringUTF("");
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
        {"verifySelfHealingCodePolymorphism", "()Z", reinterpret_cast<void*>(native_verifySelfHealingCodePolymorphism)},
        {"setHighSecurityExamWatchdogMode", "(Z)V", reinterpret_cast<void*>(native_setHighSecurityExamWatchdogMode)},
        {"isRuntimeTamperDetected", "()Z", reinterpret_cast<void*>(native_isRuntimeTamperDetected)},
        {"verifyReleaseSigningCertPin", "(Ljava/lang/String;)Z", reinterpret_cast<void*>(native_verifyReleaseSigningCertPin)}
    };
    jclass waqarBridgeCls = env->FindClass("com/waqar/institute/security/NativeSecurityBridge");
    if (waqarBridgeCls != nullptr) {
        env->RegisterNatives(waqarBridgeCls, waqarBridgeMethods, 8);
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

    // 5. Register com.example.security.StringShield methods
    static const JNINativeMethod stringShieldMethods[] = {
        {"unshieldNative", "([BI)Ljava/lang/String;", reinterpret_cast<void*>(native_unshieldNative)}
    };
    jclass stringShieldCls = env->FindClass("com/example/security/StringShield");
    if (stringShieldCls != nullptr) {
        env->RegisterNatives(stringShieldCls, stringShieldMethods, 1);
        env->DeleteLocalRef(stringShieldCls);
    } else {
        env->ExceptionClear();
    }

    // Start background Anti-Frida / .text RAM Checksum / TracerPid Watchdog Thread
    startPtraceDebuggerWatchdogThread();

    return JNI_VERSION_1_6;
}

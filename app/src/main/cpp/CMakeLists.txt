cmake_minimum_required(VERSION 3.22.1)

project("waqar_native_security" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(WCI_EXPECTED_CERT_SHA256 "2a92dc2ca40d67a8edbe6740e3ddcd288a0f7c3e5b7a62e0133a2ce986f111d5" CACHE STRING "Expected release signing cert SHA-256 hex")
set(WCI_EXPECTED_DEX_CRC32 "" CACHE STRING "Optional expected classes.dex CRC32 hex")

add_library(
    waqar_native_security
    SHARED
    native-security.cpp
    FridaShieldDetector.cpp
    KernelIntegrityDaemon.cpp
    TamperTrapDaemon.cpp
    PolymorphicShieldEngine.cpp
    OllvmControlFlowEngine.cpp
    MobileFaceNetCosineMatcher.cpp
    ZeroCopyIpcRingEngine.cpp
    MicroVmExamSandboxEngine.cpp
    VmBytecodeInterpreter.cpp
    NativeMemoryVaultGuard.cpp
)

target_compile_definitions(
    waqar_native_security
    PRIVATE
    WCI_EXPECTED_CERT_SHA256="${WCI_EXPECTED_CERT_SHA256}"
    WCI_EXPECTED_DEX_CRC32="${WCI_EXPECTED_DEX_CRC32}"
)

target_compile_options(
    waqar_native_security
    PRIVATE
    -std=c++20
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
    -fstack-protector-strong
    -O2
)

target_link_options(
    waqar_native_security
    PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,-z,relro,-z,now
)

find_library(
    log-lib
    log
)

target_link_libraries(
    waqar_native_security
    ${log-lib}
)

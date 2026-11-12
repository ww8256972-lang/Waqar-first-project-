cmake_minimum_required(VERSION 3.22.1)

project("waqar_native_security" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

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
    -flto
    -O3
    -fno-rtti
    -Wall
    -Wextra
)

target_link_options(
    waqar_native_security
    PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,-z,relro,-z,now
    -flto
    -s
)

find_library(
    log-lib
    log
)

target_link_libraries(
    waqar_native_security
    ${log-lib}
)

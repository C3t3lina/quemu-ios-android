cmake_minimum_required(VERSION 3.22)
project(iosvm_native)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# ─── Biblioteca JNI bridge ────────────────────────────────────────────────────
add_library(
    iosvm_bridge
    SHARED
    src/qemu_bridge.c
    src/vnc_client_native.c
    src/process_manager.c
)

target_include_directories(iosvm_bridge PRIVATE
    include/
    ${ANDROID_NDK}/sources/android/cpufeatures
)

target_link_libraries(iosvm_bridge
    android
    log
    z
)

# ─── Flags de compilación ─────────────────────────────────────────────────────
target_compile_options(iosvm_bridge PRIVATE
    -Wall
    -O2
    -fPIC
    -DANDROID
    -D__ANDROID_API__=${ANDROID_NATIVE_API_LEVEL}
)

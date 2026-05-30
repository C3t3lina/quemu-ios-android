#!/bin/bash
# build_qemu.sh — Compila QEMU para Android ARM64 con TCG
set -e

# ─── Configuración ────────────────────────────────────────────────────────────
QEMU_VERSION="8.2.0"
QEMU_URL="https://download.qemu.org/qemu-${QEMU_VERSION}.tar.xz"
BUILD_DIR="$(pwd)/build"
OUTPUT_DIR="$(pwd)/../app/src/main/jniLibs/arm64-v8a"
QEMU_SRC="${BUILD_DIR}/qemu-${QEMU_VERSION}"

# Android NDK — ajusta esta ruta
NDK="${ANDROID_NDK:-$HOME/Android/Sdk/ndk/25.2.9519653}"
API_LEVEL=26
TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/linux-x86_64"
TARGET="aarch64-linux-android"
CC="${TOOLCHAIN}/bin/${TARGET}${API_LEVEL}-clang"
CXX="${TOOLCHAIN}/bin/${TARGET}${API_LEVEL}-clang++"
AR="${TOOLCHAIN}/bin/llvm-ar"
RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
STRIP="${TOOLCHAIN}/bin/llvm-strip"

echo "═══════════════════════════════════════════════"
echo "  QEMU Android Builder v1.0"
echo "  Target: ${TARGET} API ${API_LEVEL}"
echo "  NDK: ${NDK}"
echo "═══════════════════════════════════════════════"

# ─── Verificaciones ───────────────────────────────────────────────────────────
if [ ! -f "${CC}" ]; then
    echo "❌ ERROR: No se encontró el compilador NDK en ${CC}"
    echo "   Configura ANDROID_NDK correctamente."
    exit 1
fi

# ─── Dependencias del host ────────────────────────────────────────────────────
echo ""
echo "📦 Instalando dependencias del host..."
if command -v apt-get &>/dev/null; then
    sudo apt-get install -y \
        build-essential ninja-build pkg-config \
        libglib2.0-dev libpixman-1-dev \
        python3 python3-pip flex bison \
        libslirp-dev || true
elif command -v brew &>/dev/null; then
    brew install ninja pkg-config glib pixman python3 || true
fi

# ─── Descargar QEMU ───────────────────────────────────────────────────────────
mkdir -p "${BUILD_DIR}"
if [ ! -d "${QEMU_SRC}" ]; then
    echo ""
    echo "⬇️  Descargando QEMU ${QEMU_VERSION}..."
    wget -q --show-progress -O "${BUILD_DIR}/qemu.tar.xz" "${QEMU_URL}"
    echo "📂 Extrayendo..."
    tar -xf "${BUILD_DIR}/qemu.tar.xz" -C "${BUILD_DIR}"
    rm "${BUILD_DIR}/qemu.tar.xz"
fi

# ─── Aplicar parches para Android ─────────────────────────────────────────────
echo ""
echo "🔧 Aplicando parches para Android..."
cd "${QEMU_SRC}"

# Parche: deshabilitar características que no existen en Android
cat > /tmp/android_compat.patch << 'PATCH'
--- a/util/oslib-posix.c
+++ b/util/oslib-posix.c
@@ -1,4 +1,8 @@
+#ifdef __ANDROID__
+#include <android/log.h>
+#define syslog(p, ...) __android_log_print(ANDROID_LOG_DEBUG, "QEMU", __VA_ARGS__)
+#endif
PATCH

# ─── Configurar QEMU ──────────────────────────────────────────────────────────
echo ""
echo "⚙️  Configurando QEMU para ARM64 Android..."

BUILD_SUBDIR="${BUILD_DIR}/build-android"
mkdir -p "${BUILD_SUBDIR}"
cd "${BUILD_SUBDIR}"

PKG_CONFIG_PATH="" \
CC="${CC}" \
CXX="${CXX}" \
AR="${AR}" \
RANLIB="${RANLIB}" \
STRIP="${STRIP}" \
CFLAGS="-D__ANDROID__ -DANDROID -fPIC -O2" \
LDFLAGS="-fPIC" \
"${QEMU_SRC}/configure" \
    --cross-prefix="${TOOLCHAIN}/bin/llvm-" \
    --cc="${CC}" \
    --cxx="${CXX}" \
    --host-cc="gcc" \
    --target-list="aarch64-softmmu" \
    --enable-tcg \
    --disable-kvm \
    --disable-xen \
    --disable-brlapi \
    --disable-vnc-sasl \
    --enable-vnc \
    --enable-slirp \
    --disable-gtk \
    --disable-sdl \
    --disable-opengl \
    --disable-virglrenderer \
    --disable-docs \
    --disable-werror \
    --disable-stack-protector \
    --static \
    --prefix="${OUTPUT_DIR}" \
    2>&1 | tee "${BUILD_DIR}/configure.log"

# ─── Compilar ─────────────────────────────────────────────────────────────────
echo ""
echo "🔨 Compilando QEMU (esto puede tardar 20-40 minutos)..."
CORES=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)
make -j"${CORES}" 2>&1 | tee "${BUILD_DIR}/build.log"

# ─── Copiar binarios ──────────────────────────────────────────────────────────
echo ""
echo "📁 Copiando binarios..."
mkdir -p "${OUTPUT_DIR}"
cp "${BUILD_SUBDIR}/qemu-system-aarch64" "${OUTPUT_DIR}/"
"${STRIP}" "${OUTPUT_DIR}/qemu-system-aarch64"

# También copiar BIOS/firmware necesarios
mkdir -p "${OUTPUT_DIR}/../assets/qemu"
cp -r "${QEMU_SRC}/pc-bios/"*.bin "${OUTPUT_DIR}/../assets/qemu/" 2>/dev/null || true
cp -r "${QEMU_SRC}/pc-bios/"*.rom "${OUTPUT_DIR}/../assets/qemu/" 2>/dev/null || true

echo ""
echo "═══════════════════════════════════════════════"
echo "  ✅ Compilación completada"
echo "  Binario: ${OUTPUT_DIR}/qemu-system-aarch64"
SIZE=$(du -sh "${OUTPUT_DIR}/qemu-system-aarch64" 2>/dev/null | cut -f1)
echo "  Tamaño: ${SIZE}"
echo "═══════════════════════════════════════════════"

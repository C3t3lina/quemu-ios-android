# iOS VM para Android (sin root)

Emulador de iOS/macOS ARM64 para Android usando QEMU con TCG (sin root requerido).

## Requisitos

### Para compilar
- PC con Linux o macOS (o WSL2 en Windows)
- Android NDK r25c o superior
- CMake 3.22+
- Python 3.8+
- Git

### Para ejecutar
- Android 8.0+ (API 26+)
- Arquitectura ARM64 (la mayoría de Android modernos)
- 4GB RAM mínimo recomendado
- 8GB espacio libre

## Obtener la imagen de iOS (legal)

Necesitas un Mac con Xcode instalado:

```bash
# En tu Mac, busca las imágenes del simulador de Xcode:
ls ~/Library/Developer/CoreSimulator/Images/
# O en versiones antiguas:
ls /Library/Developer/CoreSimulator/Profiles/Runtimes/
```

También puedes descargar Darwin (base open source de iOS/macOS):
- https://github.com/darwin-on-arm (proyecto comunitario)
- Imágenes de OVMF ARM para pruebas sin iOS real

## Compilación

```bash
# 1. Clona el repositorio
git clone <tu-repo>
cd qemu-ios-android

# 2. Configura el NDK
export ANDROID_NDK=/ruta/a/tu/ndk
export ANDROID_SDK=/ruta/a/tu/sdk

# 3. Compila QEMU para Android
./scripts/build_qemu.sh

# 4. Abre el proyecto en Android Studio
# File > Open > selecciona la carpeta /app

# 5. Build & Run
```

## Estructura del proyecto

```
qemu-ios-android/
├── app/                          → App Android (Kotlin)
│   └── src/main/
│       ├── java/com/iosvm/android/
│       │   ├── MainActivity.kt   → Actividad principal
│       │   ├── vm/               → Gestión de la VM
│       │   │   ├── VMManager.kt  → Ciclo de vida QEMU
│       │   │   └── VMConfig.kt   → Configuración de la VM
│       │   ├── vnc/              → Cliente VNC
│       │   │   └── VNCClient.kt  → Conexión y display
│       │   ├── input/            → Input táctil
│       │   │   └── TouchMapper.kt
│       │   └── ui/               → Interfaz
│       │       └── VMScreen.kt
│       └── res/
├── native/                       → Código C nativo
│   ├── src/
│   │   ├── qemu_bridge.c         → JNI bridge
│   │   └── vnc_server.c          → Servidor VNC interno
│   └── CMakeLists.txt
├── scripts/
│   ├── build_qemu.sh             → Script de compilación
│   └── patch_qemu.sh             → Parches necesarios
└── docs/
    └── LEGAL.md                  → Notas legales
```

## Notas legales

- QEMU es software libre bajo licencia GPL v2
- Este proyecto NO incluye imágenes de iOS ni macOS
- El usuario es responsable de obtener imágenes de sistema de forma legal
- Usar imágenes del simulador de Xcode requiere tener una cuenta de desarrollador Apple

## Rendimiento esperado (TCG sin root)

| Operación | Velocidad aproximada |
|-----------|---------------------|
| Boot iOS  | 5-15 minutos        |
| UI básica | ~10-20 FPS          |
| Apps ligeras | Usable          |
| Apps pesadas | Muy lento       |

Para mejor rendimiento, un dispositivo con KVM habilitado (algunos Android con root) puede lograr velocidades 10-50x mayores.

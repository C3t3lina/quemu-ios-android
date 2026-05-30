package com.iosvm.android.vm

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * VMBridge — Puente entre Kotlin y el código C nativo (JNI)
 * Gestiona el ciclo de vida del proceso QEMU
 */
class VMBridge(private val context: Context) {

    companion object {
        private const val TAG = "VMBridge"

        init {
            System.loadLibrary("iosvm_bridge")
        }
    }

    // ─── Métodos nativos (implementados en qemu_bridge.c) ─────────────────────
    private external fun nativeInit(binaryPath: String, dataDir: String): Int
    private external fun nativeStartVM(
        diskImagePath: String,
        ramMB: Int,
        vncPort: Int,
        extraArgs: String
    ): Int
    private external fun nativeStopVM(): Int
    private external fun nativeIsRunning(): Boolean
    private external fun nativeCreateDisk(imagePath: String, sizeGB: Long): Int
    private external fun nativeSendQMP(command: String): String

    // ─── Estado ───────────────────────────────────────────────────────────────
    private var initialized = false
    val dataDir: File get() = File(context.filesDir, "vm_data")
    val binaryDir: File get() = File(context.applicationInfo.nativeLibraryDir)

    // ─── Inicialización ───────────────────────────────────────────────────────
    fun initialize(): Result<Unit> {
        return try {
            dataDir.mkdirs()

            // Extraer binarios QEMU de los assets a filesDir
            extractQEMUBinaries()

            val qemuBinary = File(dataDir, "qemu-system-aarch64")
            if (!qemuBinary.exists()) {
                return Result.failure(Exception("QEMU binary not found. Run build_qemu.sh first."))
            }

            val result = nativeInit(qemuBinary.absolutePath, dataDir.absolutePath)
            if (result != 0) {
                return Result.failure(Exception("Native init failed: $result"))
            }

            initialized = true
            Log.i(TAG, "VMBridge initialized successfully")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            Result.failure(e)
        }
    }

    // ─── Extraer binarios desde assets ────────────────────────────────────────
    private fun extractQEMUBinaries() {
        val assetManager = context.assets

        // Lista de archivos a extraer
        val binaries = listOf(
            "qemu/qemu-system-aarch64",
            "qemu/qemu-img",
            "qemu/QEMU_EFI.fd",
            "qemu/bios-256k.bin",
            "qemu/vgabios-virtio.bin"
        )

        for (assetPath in binaries) {
            try {
                val inputStream = assetManager.open(assetPath)
                val outputFile = File(dataDir, assetPath.substringAfterLast("/"))

                if (!outputFile.exists()) {
                    FileOutputStream(outputFile).use { output ->
                        inputStream.copyTo(output)
                    }
                    outputFile.setExecutable(true)
                    Log.d(TAG, "Extracted: ${outputFile.name}")
                }
                inputStream.close()
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract $assetPath: ${e.message}")
            }
        }
    }

    // ─── Crear imagen de disco ────────────────────────────────────────────────
    fun createDiskImage(name: String, sizeGB: Long): Result<File> {
        if (!initialized) return Result.failure(Exception("Not initialized"))

        val imageFile = File(dataDir, "$name.qcow2")
        if (imageFile.exists()) {
            Log.i(TAG, "Disk image already exists: ${imageFile.name}")
            return Result.success(imageFile)
        }

        Log.i(TAG, "Creating disk image: ${imageFile.absolutePath} (${sizeGB}GB)")
        val result = nativeCreateDisk(imageFile.absolutePath, sizeGB)

        return if (result == 0) {
            Log.i(TAG, "Disk created successfully")
            Result.success(imageFile)
        } else {
            Result.failure(Exception("qemu-img failed with code $result"))
        }
    }

    // ─── Iniciar VM ───────────────────────────────────────────────────────────
    fun startVM(config: VMConfig): Result<Int> {
        if (!initialized) return Result.failure(Exception("Not initialized"))
        if (nativeIsRunning()) return Result.failure(Exception("VM already running"))

        val diskFile = File(config.diskImagePath)
        if (!diskFile.exists()) {
            return Result.failure(Exception("Disk image not found: ${config.diskImagePath}"))
        }

        Log.i(TAG, "Starting VM: ${config.name}")
        Log.i(TAG, "  RAM: ${config.ramMB}MB")
        Log.i(TAG, "  Disk: ${config.diskImagePath}")
        Log.i(TAG, "  VNC port: ${config.vncPort}")

        val pid = nativeStartVM(
            diskImagePath = config.diskImagePath,
            ramMB = config.ramMB,
            vncPort = config.vncPort,
            extraArgs = config.extraArgs
        )

        return if (pid > 0) {
            Log.i(TAG, "VM started with PID $pid")
            Result.success(pid)
        } else {
            Result.failure(Exception("Failed to start VM (returned $pid)"))
        }
    }

    // ─── Detener VM ───────────────────────────────────────────────────────────
    fun stopVM(): Result<Unit> {
        Log.i(TAG, "Stopping VM...")
        val result = nativeStopVM()
        return if (result == 0) {
            Log.i(TAG, "VM stopped")
            Result.success(Unit)
        } else {
            Result.failure(Exception("Stop failed: $result"))
        }
    }

    fun isRunning(): Boolean = try { nativeIsRunning() } catch (e: Exception) { false }

    // ─── Snapshots via QMP ────────────────────────────────────────────────────
    fun saveSnapshot(name: String): String {
        return nativeSendQMP("""{"execute":"savevm","arguments":{"name":"$name"}}""")
    }

    fun loadSnapshot(name: String): String {
        return nativeSendQMP("""{"execute":"loadvm","arguments":{"name":"$name"}}""")
    }

    fun pauseVM(): String = nativeSendQMP("""{"execute":"stop"}""")
    fun resumeVM(): String = nativeSendQMP("""{"execute":"cont"}""")
}

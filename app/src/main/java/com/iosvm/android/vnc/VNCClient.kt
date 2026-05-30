package com.iosvm.android.vnc

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * VNCClient — Cliente VNC en Kotlin que envuelve la implementación nativa C
 * Se conecta al servidor VNC interno de QEMU (localhost) y recibe frames
 */
class VNCClient {

    companion object {
        private const val TAG = "VNCClient"
        init { System.loadLibrary("iosvm_bridge") }
    }

    // ─── Métodos nativos (vnc_client_native.c) ────────────────────────────────
    private external fun nativeConnect(host: String, port: Int): Int
    private external fun nativeCopyFrameToBitmap(bitmap: Bitmap): Boolean
    private external fun nativeSendPointerEvent(x: Int, y: Int, buttonMask: Int)
    private external fun nativeSendKeyEvent(keysym: Int, down: Boolean)
    private external fun nativeGetWidth(): Int
    private external fun nativeGetHeight(): Int
    private external fun nativeDisconnect()

    // ─── Estado ───────────────────────────────────────────────────────────────
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _frameUpdated = MutableStateFlow(0L)
    val frameUpdated: StateFlow<Long> = _frameUpdated

    private var bitmap: Bitmap? = null
    private var renderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    // ─── Llamado desde C cuando llega un frame nuevo ──────────────────────────
    fun onNewFrame() {
        _frameUpdated.value = System.currentTimeMillis()
    }

    // ─── Conectar ─────────────────────────────────────────────────────────────
    suspend fun connect(host: String = "127.0.0.1", port: Int = 5900): Result<Unit> =
        withContext(Dispatchers.IO) {
            _connectionState.value = ConnectionState.CONNECTING
            Log.i(TAG, "Connecting to VNC $host:$port")

            val result = nativeConnect(host, port)

            if (result == 0) {
                val w = nativeGetWidth()
                val h = nativeGetHeight()
                Log.i(TAG, "VNC connected: ${w}x${h}")

                // Crear bitmap del tamaño del framebuffer remoto
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                _connectionState.value = ConnectionState.CONNECTED
                Result.success(Unit)
            } else {
                _connectionState.value = ConnectionState.ERROR
                Result.failure(Exception("VNC connection failed"))
            }
        }

    // ─── Obtener bitmap actualizado ───────────────────────────────────────────
    fun getFrame(): Bitmap? {
        val bm = bitmap ?: return null
        return if (nativeCopyFrameToBitmap(bm)) bm else null
    }

    // ─── Enviar toque (táctil) ────────────────────────────────────────────────
    fun sendTouch(x: Int, y: Int, pressed: Boolean) {
        // button_mask: bit 0 = botón izquierdo
        nativeSendPointerEvent(x, y, if (pressed) 1 else 0)
    }

    // ─── Enviar tecla ─────────────────────────────────────────────────────────
    fun sendKey(keysym: Int, down: Boolean) {
        nativeSendKeyEvent(keysym, down)
    }

    // Teclas especiales (X11 keysym codes)
    fun sendEnter()     = sendKeyPress(0xff0d)
    fun sendEscape()    = sendKeyPress(0xff1b)
    fun sendBackspace() = sendKeyPress(0xff08)
    fun sendTab()       = sendKeyPress(0xff09)
    fun sendHome()      = sendKeyPress(0xff95)

    private fun sendKeyPress(keysym: Int) {
        sendKey(keysym, true)
        sendKey(keysym, false)
    }

    // ─── Texto ────────────────────────────────────────────────────────────────
    fun sendText(text: String) {
        for (char in text) {
            val keysym = char.code
            sendKey(keysym, true)
            sendKey(keysym, false)
        }
    }

    // ─── Dimensiones ──────────────────────────────────────────────────────────
    val width: Int get() = if (_connectionState.value == ConnectionState.CONNECTED)
        nativeGetWidth() else 0
    val height: Int get() = if (_connectionState.value == ConnectionState.CONNECTED)
        nativeGetHeight() else 0

    // ─── Desconectar ──────────────────────────────────────────────────────────
    fun disconnect() {
        nativeDisconnect()
        bitmap?.recycle()
        bitmap = null
        _connectionState.value = ConnectionState.DISCONNECTED
        renderScope.cancel()
    }
}

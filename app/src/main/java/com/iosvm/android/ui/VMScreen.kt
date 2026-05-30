package com.iosvm.android.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.util.Log
import com.iosvm.android.vnc.VNCClient
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/**
 * VMScreen — SurfaceView que renderiza el display de la VM (vía VNC)
 * y traduce eventos táctiles a eventos de mouse para QEMU
 */
class VMScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "VMScreen"
        private const val TARGET_FPS = 30L
        private const val FRAME_INTERVAL_MS = 1000L / TARGET_FPS
    }

    var vncClient: VNCClient? = null
    var onConnectedCallback: (() -> Unit)? = null

    private var renderScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isRendering = false

    // Escala y offset para centrar la imagen de la VM en pantalla
    private var scaleX = 1f
    private var scaleY = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    // Paint para renderizar
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        textSize = 40f
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }
    private val bgPaint = Paint().apply { color = Color.BLACK }

    init {
        holder.addCallback(this)
        setZOrderOnTop(false)
    }

    // ─── SurfaceHolder callbacks ──────────────────────────────────────────────
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created")
        startRendering()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface changed: ${width}x${height}")
        updateScale(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface destroyed")
        stopRendering()
    }

    // ─── Render loop ──────────────────────────────────────────────────────────
    private fun startRendering() {
        isRendering = true
        renderScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        renderScope.launch {
            while (isRendering) {
                val frameStart = System.currentTimeMillis()
                renderFrame()
                val elapsed = System.currentTimeMillis() - frameStart
                val delay = FRAME_INTERVAL_MS - elapsed
                if (delay > 0) delay(delay)
            }
        }
    }

    private fun stopRendering() {
        isRendering = false
        renderScope.cancel()
    }

    private fun renderFrame() {
        val surface = holder ?: return
        var canvas: Canvas? = null

        try {
            canvas = surface.lockCanvas(null) ?: return

            val client = vncClient
            if (client == null || client.connectionState.value != VNCClient.ConnectionState.CONNECTED) {
                drawLoadingScreen(canvas)
                return
            }

            val frame = client.getFrame()
            if (frame == null || frame.isRecycled) {
                drawLoadingScreen(canvas)
                return
            }

            // Fondo negro
            canvas.drawPaint(bgPaint)

            // Calcular escala
            updateScale(canvas.width, canvas.height, frame.width, frame.height)

            // Dibujar frame escalado y centrado
            val dst = RectF(
                offsetX,
                offsetY,
                offsetX + frame.width * scaleX,
                offsetY + frame.height * scaleY
            )
            canvas.drawBitmap(frame, null, dst, bitmapPaint)

        } catch (e: Exception) {
            Log.e(TAG, "Render error", e)
        } finally {
            canvas?.let { surface.unlockCanvasAndPost(it) }
        }
    }

    private fun drawLoadingScreen(canvas: Canvas) {
        canvas.drawPaint(bgPaint)
        val cx = canvas.width / 2f
        val cy = canvas.height / 2f

        val state = vncClient?.connectionState?.value
        val message = when (state) {
            VNCClient.ConnectionState.CONNECTING -> "Conectando a la VM..."
            VNCClient.ConnectionState.DISCONNECTED -> "VM no conectada"
            VNCClient.ConnectionState.ERROR -> "Error de conexión"
            else -> "Esperando inicio de VM..."
        }

        canvas.drawText(message, cx, cy, overlayPaint)

        // Indicador de carga animado
        val time = System.currentTimeMillis() % 1000L
        val angle = (time / 1000f) * 360f
        val dotPaint = Paint().apply {
            color = Color.argb(200, 100, 180, 255)
            style = Paint.Style.FILL
        }
        for (i in 0..7) {
            val a = Math.toRadians((angle + i * 45).toDouble())
            val r = 60f
            val alpha = ((i + 1) * 30).coerceAtMost(255)
            dotPaint.alpha = alpha
            canvas.drawCircle(
                cx + (r * Math.cos(a)).toFloat(),
                cy + 80 + (r * Math.sin(a)).toFloat(),
                8f, dotPaint
            )
        }
    }

    // ─── Escala y offset ──────────────────────────────────────────────────────
    private fun updateScale(
        viewW: Int, viewH: Int,
        vmW: Int = vncClient?.width ?: 1,
        vmH: Int = vncClient?.height ?: 1
    ) {
        if (vmW <= 0 || vmH <= 0) return

        val scaleToFit = minOf(
            viewW.toFloat() / vmW.toFloat(),
            viewH.toFloat() / vmH.toFloat()
        )
        scaleX = scaleToFit
        scaleY = scaleToFit
        offsetX = (viewW - vmW * scaleX) / 2f
        offsetY = (viewH - vmH * scaleY) / 2f
    }

    // ─── Touch events → Mouse events ──────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val client = vncClient ?: return false
        if (client.connectionState.value != VNCClient.ConnectionState.CONNECTED) return false

        // Convertir coordenadas de pantalla a coordenadas de la VM
        val vmX = ((event.x - offsetX) / scaleX).roundToInt().coerceIn(0, client.width - 1)
        val vmY = ((event.y - offsetY) / scaleY).roundToInt().coerceIn(0, client.height - 1)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                client.sendTouch(vmX, vmY, true)
            }
            MotionEvent.ACTION_MOVE -> {
                client.sendTouch(vmX, vmY, true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                client.sendTouch(vmX, vmY, false)
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRendering()
    }
}

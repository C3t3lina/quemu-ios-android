package com.iosvm.android

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iosvm.android.ui.VMScreen
import com.iosvm.android.vm.VMBridge
import com.iosvm.android.vm.VMConfig
import com.iosvm.android.vnc.VNCClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PICK_IMAGE = 1001
        private const val VNC_PORT = 5900
    }

    private lateinit var vmBridge: VMBridge
    private lateinit var vncClient: VNCClient
    private lateinit var vmScreen: VMScreen

    // UI
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar
    private lateinit var controlBar: LinearLayout
    private lateinit var keyboardButton: ImageButton
    private lateinit var snapshotButton: ImageButton
    private lateinit var settingsButton: ImageButton

    private var selectedImagePath: String? = null
    private var vmStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initVM()
        observeVNCState()

        checkFirstRun()
    }

    private fun initViews() {
        vmScreen       = findViewById(R.id.vm_screen)
        statusText     = findViewById(R.id.status_text)
        startButton    = findViewById(R.id.btn_start)
        stopButton     = findViewById(R.id.btn_stop)
        toolbar        = findViewById(R.id.toolbar)
        controlBar     = findViewById(R.id.control_bar)
        keyboardButton = findViewById(R.id.btn_keyboard)
        snapshotButton = findViewById(R.id.btn_snapshot)
        settingsButton = findViewById(R.id.btn_settings)

        setSupportActionBar(toolbar)
        supportActionBar?.title = "iOS VM"

        startButton.setOnClickListener  { onStartPressed() }
        stopButton.setOnClickListener   { onStopPressed() }
        keyboardButton.setOnClickListener { showKeyboardInput() }
        snapshotButton.setOnClickListener { showSnapshotDialog() }
        settingsButton.setOnClickListener { showSettingsDialog() }

        stopButton.isEnabled = false
    }

    private fun initVM() {
        vmBridge  = VMBridge(this)
        vncClient = VNCClient()
        vmScreen.vncClient = vncClient

        lifecycleScope.launch(Dispatchers.IO) {
            val result = vmBridge.initialize()
            withContext(Dispatchers.Main) {
                if (result.isFailure) {
                    setStatus("⚠️ ${result.exceptionOrNull()?.message}", error = true)
                    showSetupDialog()
                } else {
                    setStatus("✅ QEMU listo")
                }
            }
        }
    }

    private fun observeVNCState() {
        lifecycleScope.launch {
            vncClient.connectionState.collectLatest { state ->
                when (state) {
                    VNCClient.ConnectionState.DISCONNECTED -> setStatus("VM detenida")
                    VNCClient.ConnectionState.CONNECTING   -> setStatus("🔄 Conectando...")
                    VNCClient.ConnectionState.CONNECTED    -> {
                        setStatus("▶️ VM en ejecución — ${vncClient.width}x${vncClient.height}")
                        controlBar.visibility = View.VISIBLE
                    }
                    VNCClient.ConnectionState.ERROR -> {
                        setStatus("❌ Error de conexión", error = true)
                    }
                }
            }
        }
    }

    // ─── Iniciar VM ───────────────────────────────────────────────────────────
    private fun onStartPressed() {
        val imagePath = selectedImagePath
        if (imagePath == null) {
            showSelectImageDialog()
            return
        }
        startVM(imagePath)
    }

    private fun startVM(imagePath: String) {
        val progress = ProgressDialog(this).apply {
            setMessage("Iniciando VM...\nEsto puede tardar varios minutos.")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            try {
                // Configurar VM
                val config = VMConfig.forIOS(
                    diskPath = imagePath,
                    ramMB    = getPreferredRAM()
                )

                // Iniciar QEMU
                val startResult = withContext(Dispatchers.IO) {
                    vmBridge.startVM(config)
                }

                if (startResult.isFailure) {
                    progress.dismiss()
                    showError("No se pudo iniciar la VM: ${startResult.exceptionOrNull()?.message}")
                    return@launch
                }

                vmStarted = true
                startButton.isEnabled = false
                stopButton.isEnabled  = true

                setStatus("🚀 VM iniciada, conectando display...")

                // Esperar a que QEMU levante VNC (puede tardar 10-60 segundos)
                delay(5000)

                // Conectar VNC
                val vncResult = withContext(Dispatchers.IO) {
                    vncClient.connect("127.0.0.1", VNC_PORT)
                }

                progress.dismiss()

                if (vncResult.isFailure) {
                    showError("VM iniciada pero no se pudo conectar el display.\n" +
                              "Intenta de nuevo en unos segundos.")
                } else {
                    setStatus("▶️ VM conectada")
                }

            } catch (e: Exception) {
                progress.dismiss()
                showError("Error: ${e.message}")
                Log.e(TAG, "VM start failed", e)
            }
        }
    }

    // ─── Detener VM ───────────────────────────────────────────────────────────
    private fun onStopPressed() {
        AlertDialog.Builder(this)
            .setTitle("Detener VM")
            .setMessage("¿Seguro que quieres apagar la VM?\nLos cambios no guardados se perderán.")
            .setPositiveButton("Guardar y apagar") { _, _ -> saveAndStop() }
            .setNegativeButton("Apagar sin guardar") { _, _ -> stopVM() }
            .setNeutralButton("Cancelar", null)
            .show()
    }

    private fun saveAndStop() {
        lifecycleScope.launch {
            setStatus("💾 Guardando snapshot...")
            withContext(Dispatchers.IO) {
                vmBridge.saveSnapshot("autosave")
                delay(2000)
            }
            stopVM()
        }
    }

    private fun stopVM() {
        lifecycleScope.launch {
            vncClient.disconnect()
            withContext(Dispatchers.IO) { vmBridge.stopVM() }
            vmStarted = false
            startButton.isEnabled = true
            stopButton.isEnabled  = false
            controlBar.visibility = View.GONE
            setStatus("VM detenida")
        }
    }

    // ─── Seleccionar imagen ───────────────────────────────────────────────────
    private fun showSelectImageDialog() {
        AlertDialog.Builder(this)
            .setTitle("Imagen de sistema")
            .setItems(arrayOf(
                "📂 Seleccionar archivo .img / .qcow2",
                "💿 Crear disco vacío (sin imagen de sistema)",
                "❓ ¿Cómo obtener la imagen?"
            )) { _, which ->
                when (which) {
                    0 -> pickImageFile()
                    1 -> createEmptyDisk()
                    2 -> showImageHelpDialog()
                }
            }
            .show()
    }

    private fun pickImageFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
        }
        startActivityForResult(intent, REQUEST_PICK_IMAGE)
    }

    private fun createEmptyDisk() {
        lifecycleScope.launch {
            setStatus("Creando disco vacío...")
            val result = withContext(Dispatchers.IO) {
                vmBridge.createDiskImage("ios_disk", 32L)
            }
            if (result.isSuccess) {
                selectedImagePath = result.getOrNull()?.absolutePath
                setStatus("✅ Disco creado. Selecciona una imagen ISO para instalar.")
            } else {
                showError("Error creando disco: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    private fun showImageHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("Cómo obtener la imagen")
            .setMessage("""
Para iOS Simulator (legal, requiere Mac con Xcode):

1. Instala Xcode en un Mac
2. Abre Terminal y ejecuta:
   find ~/Library/Developer/CoreSimulator -name "*.img" 2>/dev/null

3. Copia el archivo .img a tu Android via USB o cable

Alternativa — Linux ARM64 para probar sin imagen Apple:
• Descarga una imagen de Ubuntu ARM64 Server
• Úsala para verificar que el emulador funciona
• URL: https://ubuntu.com/download/server/arm

Nota: iOS requiere hardware Apple para funcionar óptimamente.
TCG emula ARM64 por software, sin KVM.
            """.trimIndent())
            .setPositiveButton("Entendido", null)
            .setNeutralButton("Descargar Ubuntu ARM") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://ubuntu.com/download/server/arm")))
            }
            .show()
    }

    // ─── Diálogos ─────────────────────────────────────────────────────────────
    private fun showKeyboardInput() {
        if (!vmStarted) return
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Enviar texto a VM")
            .setView(input)
            .setPositiveButton("Enviar") { _, _ ->
                vncClient.sendText(input.text.toString())
            }
            .setNeutralButton("Enter") { _, _ -> vncClient.sendEnter() }
            .setNegativeButton("ESC") { _, _ -> vncClient.sendEscape() }
            .show()
    }

    private fun showSnapshotDialog() {
        if (!vmStarted) return
        val options = arrayOf("💾 Guardar snapshot", "📂 Cargar snapshot", "⏸️ Pausar VM", "▶️ Reanudar VM")
        AlertDialog.Builder(this)
            .setTitle("Snapshots")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { lifecycleScope.launch { withContext(Dispatchers.IO) { vmBridge.saveSnapshot("manual") } }; Toast.makeText(this, "Snapshot guardado", Toast.LENGTH_SHORT).show() }
                    1 -> { lifecycleScope.launch { withContext(Dispatchers.IO) { vmBridge.loadSnapshot("manual") } } }
                    2 -> { lifecycleScope.launch { withContext(Dispatchers.IO) { vmBridge.pauseVM() } } }
                    3 -> { lifecycleScope.launch { withContext(Dispatchers.IO) { vmBridge.resumeVM() } } }
                }
            }
            .show()
    }

    private fun showSettingsDialog() {
        val items = arrayOf("RAM: ${getPreferredRAM()}MB", "Resolución VNC", "Info del sistema")
        AlertDialog.Builder(this)
            .setTitle("Configuración")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRAMSelector()
                }
            }
            .show()
    }

    private fun showRAMSelector() {
        val options = arrayOf("1024 MB (1 GB)", "2048 MB (2 GB)", "3072 MB (3 GB)", "4096 MB (4 GB)")
        val values  = intArrayOf(1024, 2048, 3072, 4096)
        AlertDialog.Builder(this)
            .setTitle("RAM para la VM")
            .setItems(options) { _, which ->
                getSharedPreferences("vm_prefs", MODE_PRIVATE)
                    .edit().putInt("ram_mb", values[which]).apply()
                Toast.makeText(this, "RAM: ${values[which]}MB (aplica al siguiente inicio)", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showSetupDialog() {
        AlertDialog.Builder(this)
            .setTitle("Primera configuración")
            .setMessage("El binario QEMU no está incluido.\n\n" +
                        "Necesitas compilarlo ejecutando:\n\n" +
                        "  ./scripts/build_qemu.sh\n\n" +
                        "en tu PC y luego copiar el APK resultante a tu Android.\n\n" +
                        "Ver README.md para instrucciones completas.")
            .setPositiveButton("Ver README") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/tu-usuario/qemu-ios-android")))
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showError(msg: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Error")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun checkFirstRun() {
        val prefs = getSharedPreferences("vm_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("setup_shown", false)) {
            prefs.edit().putBoolean("setup_shown", true).apply()
            showImageHelpDialog()
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────
    private fun setStatus(msg: String, error: Boolean = false) {
        runOnUiThread {
            statusText.text = msg
            statusText.setTextColor(
                if (error) getColor(android.R.color.holo_red_light)
                else getColor(android.R.color.white)
            )
        }
    }

    private fun getPreferredRAM(): Int =
        getSharedPreferences("vm_prefs", MODE_PRIVATE).getInt("ram_mb", 2048)

    // ─── Activity results ─────────────────────────────────────────────────────
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_IMAGE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                // Copiar el archivo a filesDir para acceso nativo
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val destFile = File(vmBridge.dataDir, "selected_image.img")
                        contentResolver.openInputStream(uri)?.use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        selectedImagePath = destFile.absolutePath
                        withContext(Dispatchers.Main) {
                            setStatus("✅ Imagen seleccionada")
                            Toast.makeText(this@MainActivity,
                                "Imagen lista: ${destFile.name}", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            showError("Error copiando imagen: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    // ─── Menu ─────────────────────────────────────────────────────────────────
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Seleccionar imagen")
        menu.add(0, 2, 0, "Acerca de")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> { showSelectImageDialog(); true }
            2 -> { showAboutDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("iOS VM para Android")
            .setMessage("Emulador de iOS/ARM64 usando QEMU TCG\n\n" +
                        "• Motor: QEMU ${getQEMUVersion()}\n" +
                        "• Backend: TCG (sin root)\n" +
                        "• Display: VNC interno\n" +
                        "• Red: SLIRP\n\n" +
                        "Proyecto open source bajo GPL v2\n" +
                        "QEMU © 2003-2024 Fabrice Bellard et al.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun getQEMUVersion() = "8.2.0"

    override fun onDestroy() {
        super.onDestroy()
        if (vmStarted) {
            vncClient.disconnect()
            lifecycleScope.launch(Dispatchers.IO) { vmBridge.stopVM() }
        }
    }
}

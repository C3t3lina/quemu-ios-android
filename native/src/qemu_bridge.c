// qemu_bridge.c — Puente JNI entre Android y el proceso QEMU
// Gestiona el lanzamiento, comunicación y control del emulador

#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <pthread.h>

#define TAG "IOSVM_BRIDGE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// ─── Estado global ────────────────────────────────────────────────────────────
static pid_t qemu_pid = -1;
static int   qemu_running = 0;
static char  qemu_binary_path[512] = {0};
static char  vm_data_dir[512] = {0};

// Pipe para logs de QEMU
static int log_pipe[2] = {-1, -1};
static pthread_t log_thread;

// ─── Thread lector de logs ────────────────────────────────────────────────────
static void* log_reader_thread(void* arg) {
    char buf[1024];
    ssize_t n;
    while ((n = read(log_pipe[0], buf, sizeof(buf) - 1)) > 0) {
        buf[n] = '\0';
        LOGD("QEMU: %s", buf);
    }
    return NULL;
}

// ─── JNI: Inicializar paths ───────────────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_iosvm_android_vm_VMBridge_nativeInit(
    JNIEnv* env, jobject thiz,
    jstring binaryPath, jstring dataDir)
{
    const char* bp = (*env)->GetStringUTFChars(env, binaryPath, NULL);
    const char* dd = (*env)->GetStringUTFChars(env, dataDir, NULL);

    strncpy(qemu_binary_path, bp, sizeof(qemu_binary_path) - 1);
    strncpy(vm_data_dir, dd, sizeof(vm_data_dir) - 1);

    (*env)->ReleaseStringUTFChars(env, binaryPath, bp);
    (*env)->ReleaseStringUTFChars(env, dataDir, dd);

    // Verificar que el binario existe y tiene permisos de ejecución
    if (access(qemu_binary_path, X_OK) != 0) {
        // Intentar dar permisos
        chmod(qemu_binary_path, 0755);
        if (access(qemu_binary_path, X_OK) != 0) {
            LOGE("QEMU binary not executable: %s (%s)", qemu_binary_path, strerror(errno));
            return -1;
        }
    }

    LOGI("Bridge initialized. Binary: %s", qemu_binary_path);
    LOGI("Data dir: %s", vm_data_dir);
    return 0;
}

// ─── JNI: Lanzar QEMU ─────────────────────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_iosvm_android_vm_VMBridge_nativeStartVM(
    JNIEnv* env, jobject thiz,
    jstring diskImagePath,
    jint ramMB,
    jint vncPort,
    jstring extraArgs)
{
    if (qemu_running) {
        LOGE("VM already running (pid %d)", qemu_pid);
        return -1;
    }

    const char* disk = (*env)->GetStringUTFChars(env, diskImagePath, NULL);
    const char* extra = (*env)->GetStringUTFChars(env, extraArgs, NULL);

    // Crear pipe para logs
    if (pipe(log_pipe) != 0) {
        LOGE("Failed to create log pipe: %s", strerror(errno));
    } else {
        pthread_create(&log_thread, NULL, log_reader_thread, NULL);
    }

    // Construir argumentos de QEMU
    char vnc_arg[64];
    snprintf(vnc_arg, sizeof(vnc_arg), "127.0.0.1:%d", vncPort - 5900);

    char ram_arg[32];
    snprintf(ram_arg, sizeof(ram_arg), "%dM", ramMB);

    char bios_path[512];
    snprintf(bios_path, sizeof(bios_path), "%s/qemu", vm_data_dir);

    // Argumentos QEMU para iOS/ARM64
    const char* argv[] = {
        qemu_binary_path,
        "-M",        "virt,highmem=off",        // Máquina virtual ARM
        "-cpu",      "max",                       // CPU máxima emulada
        "-m",        ram_arg,                     // RAM
        "-drive",    NULL,                        // Disco (se rellena abajo)
        "-bios",     NULL,                        // Firmware UEFI ARM
        "-vnc",      vnc_arg,                     // Display VNC
        "-net",      "nic,model=virtio",          // Red
        "-net",      "user,hostfwd=tcp::2222-:22",// Port forward SSH
        "-nographic",                             // Sin ventana gráfica propia
        "-no-reboot",                             // No reiniciar solo
        "-D",        NULL,                        // Log file
        NULL
    };

    // Rellena argumentos dinámicos
    char drive_arg[1024];
    snprintf(drive_arg, sizeof(drive_arg),
        "file=%s,format=qcow2,if=virtio", disk);
    argv[5] = drive_arg;  // -drive value

    char bios_arg[512];
    snprintf(bios_arg, sizeof(bios_arg), "%s/QEMU_EFI.fd", bios_path);
    argv[7] = bios_arg;  // -bios value

    char log_path[512];
    snprintf(log_path, sizeof(log_path), "%s/qemu.log", vm_data_dir);
    argv[15] = log_path; // -D value

    // Contar argumentos reales
    int argc = 0;
    while (argv[argc] != NULL) argc++;

    // Agregar args extra si los hay
    // (simplificado — en producción parsear la cadena extra)

    LOGI("Launching QEMU with %d args", argc);
    for (int i = 0; i < argc; i++) {
        LOGD("  argv[%d] = %s", i, argv[i]);
    }

    // Fork + exec
    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork() failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, diskImagePath, disk);
        (*env)->ReleaseStringUTFChars(env, extraArgs, extra);
        return -1;
    }

    if (pid == 0) {
        // Proceso hijo — redirigir stdout/stderr al pipe
        if (log_pipe[1] >= 0) {
            dup2(log_pipe[1], STDOUT_FILENO);
            dup2(log_pipe[1], STDERR_FILENO);
            close(log_pipe[0]);
            close(log_pipe[1]);
        }

        // Ejecutar QEMU
        execv(qemu_binary_path, (char* const*)argv);

        // Si llegamos aquí, execv falló
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "execv failed: %s", strerror(errno));
        _exit(1);
    }

    // Proceso padre
    qemu_pid = pid;
    qemu_running = 1;
    if (log_pipe[1] >= 0) close(log_pipe[1]);

    LOGI("QEMU started with PID %d", qemu_pid);

    (*env)->ReleaseStringUTFChars(env, diskImagePath, disk);
    (*env)->ReleaseStringUTFChars(env, extraArgs, extra);
    return (jint)qemu_pid;
}

// ─── JNI: Detener QEMU ────────────────────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_iosvm_android_vm_VMBridge_nativeStopVM(
    JNIEnv* env, jobject thiz)
{
    if (!qemu_running || qemu_pid < 0) {
        LOGI("VM not running");
        return 0;
    }

    LOGI("Stopping QEMU (pid %d)...", qemu_pid);

    // Enviar SIGTERM primero (apagado graceful)
    kill(qemu_pid, SIGTERM);

    // Esperar hasta 5 segundos
    for (int i = 0; i < 50; i++) {
        int status;
        pid_t result = waitpid(qemu_pid, &status, WNOHANG);
        if (result == qemu_pid) {
            LOGI("QEMU exited with status %d", WEXITSTATUS(status));
            qemu_running = 0;
            qemu_pid = -1;
            return 0;
        }
        usleep(100000); // 100ms
    }

    // Si sigue vivo, SIGKILL
    LOGI("QEMU did not exit, sending SIGKILL");
    kill(qemu_pid, SIGKILL);
    waitpid(qemu_pid, NULL, 0);
    qemu_running = 0;
    qemu_pid = -1;
    return 0;
}

// ─── JNI: Estado de la VM ─────────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL
Java_com_iosvm_android_vm_VMBridge_nativeIsRunning(
    JNIEnv* env, jobject thiz)
{
    if (!qemu_running || qemu_pid < 0) return JNI_FALSE;

    // Verificar si el proceso sigue vivo
    int status;
    pid_t result = waitpid(qemu_pid, &status, WNOHANG);
    if (result == qemu_pid) {
        // El proceso terminó
        qemu_running = 0;
        qemu_pid = -1;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// ─── JNI: Crear imagen de disco vacía ─────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_iosvm_android_vm_VMBridge_nativeCreateDisk(
    JNIEnv* env, jobject thiz,
    jstring imagePath, jlong sizeGB)
{
    const char* path = (*env)->GetStringUTFChars(env, imagePath, NULL);

    // Usar qemu-img para crear imagen qcow2
    // En Android, incluimos qemu-img como binario auxiliar
    char qemu_img[512];
    snprintf(qemu_img, sizeof(qemu_img), "%s/../qemu-img",
             qemu_binary_path[0] ? qemu_binary_path : "/data/local/tmp");

    char size_str[32];
    snprintf(size_str, sizeof(size_str), "%ldG", (long)sizeGB);

    char cmd[1024];
    snprintf(cmd, sizeof(cmd),
        "%s create -f qcow2 %s %s",
        qemu_img, path, size_str);

    LOGI("Creating disk: %s", cmd);
    int ret = system(cmd);

    (*env)->ReleaseStringUTFChars(env, imagePath, path);
    return ret;
}

// ─── JNI: Enviar comando QMP (QEMU Machine Protocol) ─────────────────────────
// Permite controlar la VM en caliente (snapshots, pausar, etc.)
JNIEXPORT jstring JNICALL
Java_com_iosvm_android_vm_VMBridge_nativeSendQMP(
    JNIEnv* env, jobject thiz, jstring command)
{
    // QMP se comunica via socket Unix o TCP
    // Por simplicidad, usamos el socket en vm_data_dir/qmp.sock
    // Implementación completa en producción
    LOGI("QMP command (stub)");
    return (*env)->NewStringUTF(env, "{\"return\": {}}");
}

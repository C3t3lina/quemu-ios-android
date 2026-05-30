// vnc_client_native.c — Cliente VNC nativo para recibir el display de QEMU
// Conecta al servidor VNC interno de QEMU (localhost) y decodifica los frames

#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <errno.h>
#include <pthread.h>

#define TAG "IOSVM_VNC"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─── Constantes RFB (protocolo VNC) ──────────────────────────────────────────
#define RFB_PROTOCOL_VERSION "RFB 003.008\n"
#define RFB_SECURITY_NONE    1
#define RFB_SECURITY_VNC     2

// Tipos de mensajes del servidor
#define MSG_FRAMEBUFFER_UPDATE    0
#define MSG_SET_COLOUR_MAP_ENTRIES 1
#define MSG_BELL                  2
#define MSG_SERVER_CUT_TEXT       3

// Tipos de encoding
#define ENC_RAW       0
#define ENC_COPYRECT  1
#define ENC_RRE       2
#define ENC_ZRLE      16
#define ENC_TIGHT     7
#define ENC_ZLIB      6

// ─── Estado del cliente VNC ───────────────────────────────────────────────────
typedef struct {
    int     sock;
    int     connected;
    int     width;
    int     height;
    int     bpp;            // bits per pixel
    uint8_t* framebuffer;   // buffer RGBA del frame actual
    pthread_mutex_t fb_mutex;
    pthread_t recv_thread;
    int     running;

    // Callback a Java para notificar nuevo frame
    JavaVM* jvm;
    jobject callback_obj;
    jmethodID on_frame_method;
} VNCClient;

static VNCClient g_vnc = {0};

// ─── Lectura segura del socket ────────────────────────────────────────────────
static int recv_all(int sock, void* buf, size_t len) {
    size_t received = 0;
    while (received < len) {
        ssize_t n = recv(sock, (char*)buf + received, len - received, 0);
        if (n <= 0) {
            if (n == 0) LOGI("VNC: Connection closed");
            else LOGE("VNC: recv error: %s", strerror(errno));
            return -1;
        }
        received += n;
    }
    return 0;
}

static int send_all(int sock, const void* buf, size_t len) {
    size_t sent = 0;
    while (sent < len) {
        ssize_t n = send(sock, (const char*)buf + sent, len - sent, 0);
        if (n <= 0) return -1;
        sent += n;
    }
    return 0;
}

// ─── Handshake RFB ────────────────────────────────────────────────────────────
static int vnc_handshake(int sock) {
    char version[13] = {0};

    // Recibir versión del servidor
    if (recv_all(sock, version, 12) != 0) return -1;
    LOGI("VNC Server version: %.12s", version);

    // Enviar nuestra versión
    if (send_all(sock, RFB_PROTOCOL_VERSION, 12) != 0) return -1;

    // Recibir tipos de seguridad
    uint8_t num_security_types;
    if (recv_all(sock, &num_security_types, 1) != 0) return -1;

    if (num_security_types == 0) {
        LOGE("VNC: Server rejected connection");
        return -1;
    }

    uint8_t* security_types = malloc(num_security_types);
    if (recv_all(sock, security_types, num_security_types) != 0) {
        free(security_types);
        return -1;
    }

    // Elegir "None" si está disponible
    uint8_t chosen = 0;
    for (int i = 0; i < num_security_types; i++) {
        if (security_types[i] == RFB_SECURITY_NONE) {
            chosen = RFB_SECURITY_NONE;
            break;
        }
    }
    free(security_types);

    if (chosen == 0) {
        LOGE("VNC: No supported security type");
        return -1;
    }

    // Enviar tipo de seguridad elegido
    if (send_all(sock, &chosen, 1) != 0) return -1;

    // Verificar resultado de seguridad
    uint32_t security_result;
    if (recv_all(sock, &security_result, 4) != 0) return -1;
    security_result = ntohl(security_result);

    if (security_result != 0) {
        LOGE("VNC: Security handshake failed: %u", security_result);
        return -1;
    }

    // ClientInit — compartir pantalla
    uint8_t shared_flag = 1;
    if (send_all(sock, &shared_flag, 1) != 0) return -1;

    // ServerInit — recibir info del framebuffer
    struct {
        uint16_t width;
        uint16_t height;
        uint8_t  bpp;
        uint8_t  depth;
        uint8_t  big_endian;
        uint8_t  true_colour;
        uint16_t red_max;
        uint16_t green_max;
        uint16_t blue_max;
        uint8_t  red_shift;
        uint8_t  green_shift;
        uint8_t  blue_shift;
        uint8_t  padding[3];
        uint32_t name_length;
    } __attribute__((packed)) server_init;

    if (recv_all(sock, &server_init, sizeof(server_init)) != 0) return -1;

    g_vnc.width  = ntohs(server_init.width);
    g_vnc.height = ntohs(server_init.height);
    g_vnc.bpp    = server_init.bpp;

    uint32_t name_len = ntohl(server_init.name_length);
    char* name = malloc(name_len + 1);
    if (recv_all(sock, name, name_len) != 0) {
        free(name);
        return -1;
    }
    name[name_len] = '\0';
    LOGI("VNC Connected: %dx%d %dbpp, name='%s'",
         g_vnc.width, g_vnc.height, g_vnc.bpp, name);
    free(name);

    // Asignar framebuffer RGBA
    pthread_mutex_lock(&g_vnc.fb_mutex);
    if (g_vnc.framebuffer) free(g_vnc.framebuffer);
    g_vnc.framebuffer = calloc(g_vnc.width * g_vnc.height, 4); // RGBA
    pthread_mutex_unlock(&g_vnc.fb_mutex);

    // Configurar encoding preferido
    struct {
        uint8_t  msg_type;
        uint8_t  padding;
        uint16_t num_encodings;
        int32_t  encodings[2];
    } __attribute__((packed)) set_encodings = {
        .msg_type      = 2,
        .padding       = 0,
        .num_encodings = htons(2),
        .encodings     = { htonl(ENC_RAW), htonl(ENC_COPYRECT) }
    };
    send_all(sock, &set_encodings, sizeof(set_encodings));

    return 0;
}

// ─── Solicitar actualización de framebuffer ───────────────────────────────────
static void vnc_request_update(int sock, int incremental) {
    struct {
        uint8_t  msg_type;
        uint8_t  incremental;
        uint16_t x;
        uint16_t y;
        uint16_t width;
        uint16_t height;
    } __attribute__((packed)) req = {
        .msg_type   = 3,
        .incremental = incremental,
        .x          = 0,
        .y          = 0,
        .width      = htons(g_vnc.width),
        .height     = htons(g_vnc.height)
    };
    send_all(sock, &req, sizeof(req));
}

// ─── Procesar actualización de framebuffer ────────────────────────────────────
static int vnc_process_framebuffer_update(int sock) {
    uint8_t padding;
    uint16_t num_rects_be;
    recv_all(sock, &padding, 1);
    recv_all(sock, &num_rects_be, 2);
    int num_rects = ntohs(num_rects_be);

    for (int r = 0; r < num_rects; r++) {
        struct {
            uint16_t x, y, w, h;
            int32_t  encoding;
        } __attribute__((packed)) rect;

        if (recv_all(sock, &rect, sizeof(rect)) != 0) return -1;

        int x = ntohs(rect.x);
        int y = ntohs(rect.y);
        int w = ntohs(rect.w);
        int h = ntohs(rect.h);
        int enc = ntohl(rect.encoding);

        if (enc == ENC_RAW) {
            // Encoding RAW: datos crudos BGRA/RGB
            int bytes_per_pixel = g_vnc.bpp / 8;
            size_t data_size = w * h * bytes_per_pixel;
            uint8_t* raw = malloc(data_size);

            if (recv_all(sock, raw, data_size) != 0) {
                free(raw);
                return -1;
            }

            // Convertir a RGBA y escribir en framebuffer
            pthread_mutex_lock(&g_vnc.fb_mutex);
            if (g_vnc.framebuffer) {
                for (int row = 0; row < h; row++) {
                    for (int col = 0; col < w; col++) {
                        int src_idx = (row * w + col) * bytes_per_pixel;
                        int dst_idx = ((y + row) * g_vnc.width + (x + col)) * 4;

                        if (dst_idx + 3 < g_vnc.width * g_vnc.height * 4) {
                            if (bytes_per_pixel == 4) {
                                // BGRA → RGBA
                                g_vnc.framebuffer[dst_idx + 0] = raw[src_idx + 2]; // R
                                g_vnc.framebuffer[dst_idx + 1] = raw[src_idx + 1]; // G
                                g_vnc.framebuffer[dst_idx + 2] = raw[src_idx + 0]; // B
                                g_vnc.framebuffer[dst_idx + 3] = 0xFF;             // A
                            } else if (bytes_per_pixel == 3) {
                                g_vnc.framebuffer[dst_idx + 0] = raw[src_idx + 0];
                                g_vnc.framebuffer[dst_idx + 1] = raw[src_idx + 1];
                                g_vnc.framebuffer[dst_idx + 2] = raw[src_idx + 2];
                                g_vnc.framebuffer[dst_idx + 3] = 0xFF;
                            }
                        }
                    }
                }
            }
            pthread_mutex_unlock(&g_vnc.fb_mutex);
            free(raw);
        }
        // Otros encodings pueden añadirse aquí
    }
    return 0;
}

// ─── Thread principal de recepción VNC ───────────────────────────────────────
static void* vnc_recv_thread(void* arg) {
    JNIEnv* env = NULL;
    if (g_vnc.jvm) {
        (*g_vnc.jvm)->AttachCurrentThread(g_vnc.jvm, &env, NULL);
    }

    // Solicitar primer frame completo
    vnc_request_update(g_vnc.sock, 0);

    while (g_vnc.running) {
        uint8_t msg_type;
        if (recv_all(g_vnc.sock, &msg_type, 1) != 0) break;

        switch (msg_type) {
            case MSG_FRAMEBUFFER_UPDATE:
                vnc_process_framebuffer_update(g_vnc.sock);
                // Notificar a Java que hay nuevo frame
                if (env && g_vnc.callback_obj && g_vnc.on_frame_method) {
                    (*env)->CallVoidMethod(env, g_vnc.callback_obj,
                                          g_vnc.on_frame_method);
                }
                // Solicitar siguiente actualización incremental
                vnc_request_update(g_vnc.sock, 1);
                break;

            case MSG_BELL:
                LOGI("VNC: Bell");
                break;

            case MSG_SERVER_CUT_TEXT: {
                uint8_t pad[3];
                uint32_t len_be;
                recv_all(g_vnc.sock, pad, 3);
                recv_all(g_vnc.sock, &len_be, 4);
                uint32_t len = ntohl(len_be);
                char* text = malloc(len + 1);
                recv_all(g_vnc.sock, text, len);
                text[len] = '\0';
                LOGI("VNC Cut Text: %s", text);
                free(text);
                break;
            }

            default:
                LOGE("VNC: Unknown message type %d", msg_type);
                break;
        }
    }

    if (g_vnc.jvm && env) {
        (*g_vnc.jvm)->DetachCurrentThread(g_vnc.jvm);
    }

    LOGI("VNC recv thread exiting");
    return NULL;
}

// ─── JNI: Conectar al servidor VNC ───────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_iosvm_android_vnc_VNCClient_nativeConnect(
    JNIEnv* env, jobject thiz,
    jstring host, jint port)
{
    const char* h = (*env)->GetStringUTFChars(env, host, NULL);

    // Crear socket TCP
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        LOGE("socket() failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, host, h);
        return -1;
    }

    // Timeout de conexión
    struct timeval timeout = {.tv_sec = 30, .tv_usec = 0};
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));

    struct sockaddr_in addr = {
        .sin_family = AF_INET,
        .sin_port   = htons(port)
    };
    inet_pton(AF_INET, h, &addr.sin_addr);
    (*env)->ReleaseStringUTFChars(env, host, h);

    LOGI("VNC: Connecting to %s:%d...", h, port);

    // Reintentar la conexión (QEMU puede tardar en arrancar)
    int connected = 0;
    for (int attempt = 0; attempt < 30; attempt++) {
        if (connect(sock, (struct sockaddr*)&addr, sizeof(addr)) == 0) {
            connected = 1;
            break;
        }
        LOGI("VNC: Connection attempt %d failed, retrying in 2s...", attempt + 1);
        sleep(2);
    }

    if (!connected) {
        LOGE("VNC: Could not connect after 30 attempts");
        close(sock);
        return -1;
    }

    g_vnc.sock = sock;

    // Handshake RFB
    if (vnc_handshake(sock) != 0) {
        close(sock);
        return -1;
    }

    // Guardar referencia a Java VM para callbacks
    (*env)->GetJavaVM(env, &g_vnc.jvm);
    g_vnc.callback_obj   = (*env)->NewGlobalRef(env, thiz);
    jclass cls           = (*env)->GetObjectClass(env, thiz);
    g_vnc.on_frame_method = (*env)->GetMethodID(env, cls, "onNewFrame", "()V");

    // Iniciar thread de recepción
    pthread_mutex_init(&g_vnc.fb_mutex, NULL);
    g_vnc.running   = 1;
    g_vnc.connected = 1;
    pthread_create(&g_vnc.recv_thread, NULL, vnc_recv_thread, NULL);

    return 0;
}

// ─── JNI: Copiar framebuffer a Bitmap Android ────────────────────────────────
JNIEXPORT jboolean JNICALL
Java_com_iosvm_android_vnc_VNCClient_nativeCopyFrameToBitmap(
    JNIEnv* env, jobject thiz, jobject bitmap)
{
    if (!g_vnc.framebuffer || !g_vnc.connected) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != 0) return JNI_FALSE;

    void* pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != 0) return JNI_FALSE;

    pthread_mutex_lock(&g_vnc.fb_mutex);

    int copy_w = (info.width  < (uint32_t)g_vnc.width)  ? info.width  : g_vnc.width;
    int copy_h = (info.height < (uint32_t)g_vnc.height) ? info.height : g_vnc.height;

    for (int row = 0; row < copy_h; row++) {
        uint8_t* src = g_vnc.framebuffer + row * g_vnc.width * 4;
        uint8_t* dst = (uint8_t*)pixels + row * info.stride;
        memcpy(dst, src, copy_w * 4);
    }

    pthread_mutex_unlock(&g_vnc.fb_mutex);
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

// ─── JNI: Enviar evento de puntero (toque/click) ─────────────────────────────
JNIEXPORT void JNICALL
Java_com_iosvm_android_vnc_VNCClient_nativeSendPointerEvent(
    JNIEnv* env, jobject thiz,
    jint x, jint y, jint button_mask)
{
    if (!g_vnc.connected) return;

    struct {
        uint8_t  msg_type;
        uint8_t  button_mask;
        uint16_t x;
        uint16_t y;
    } __attribute__((packed)) evt = {
        .msg_type    = 5,
        .button_mask = (uint8_t)button_mask,
        .x           = htons((uint16_t)x),
        .y           = htons((uint16_t)y)
    };
    send_all(g_vnc.sock, &evt, sizeof(evt));
}

// ─── JNI: Enviar evento de teclado ───────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_iosvm_android_vnc_VNCClient_nativeSendKeyEvent(
    JNIEnv* env, jobject thiz,
    jint keysym, jboolean down)
{
    if (!g_vnc.connected) return;

    struct {
        uint8_t  msg_type;
        uint8_t  down_flag;
        uint16_t padding;
        uint32_t keysym;
    } __attribute__((packed)) evt = {
        .msg_type  = 4,
        .down_flag = down ? 1 : 0,
        .padding   = 0,
        .keysym    = htonl((uint32_t)keysym)
    };
    send_all(g_vnc.sock, &evt, sizeof(evt));
}

// ─── JNI: Obtener dimensiones ─────────────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_iosvm_android_vnc_VNCClient_nativeGetWidth(JNIEnv* env, jobject thiz) {
    return g_vnc.width;
}

JNIEXPORT jint JNICALL
Java_com_iosvm_android_vnc_VNCClient_nativeGetHeight(JNIEnv* env, jobject thiz) {
    return g_vnc.height;
}

// ─── JNI: Desconectar ────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_iosvm_android_vnc_VNCClient_nativeDisconnect(JNIEnv* env, jobject thiz) {
    g_vnc.running = 0;
    if (g_vnc.sock >= 0) {
        close(g_vnc.sock);
        g_vnc.sock = -1;
    }
    g_vnc.connected = 0;
    if (g_vnc.callback_obj) {
        (*env)->DeleteGlobalRef(env, g_vnc.callback_obj);
        g_vnc.callback_obj = NULL;
    }
    pthread_mutex_destroy(&g_vnc.fb_mutex);
    if (g_vnc.framebuffer) {
        free(g_vnc.framebuffer);
        g_vnc.framebuffer = NULL;
    }
}

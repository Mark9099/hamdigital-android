// JNI bridge for FreeDATA's modems, with codec2's FreeDV data API (LGPL 2.1): as FreeDATA's demodulator.py and
// modulator.py use it. Receive: the radio's audio (8 kHz) goes to a DATAC13 receiver (FreeDATA's "signalling": CQ,
// QRV, beacon, ping, session opening) and a DATAC14 receiver (its "signalling ack"), each fed as much as it asks for
// (freedv_nin); a frame comes back only when its CRC16 is good (freedv_rawdatarx), with the modem's SNR. Transmit: one
// frame - padded to the mode's frame, its CRC16 added (freedv_gen_crc16, big-endian) - between the mode's preamble
// and postamble (modulator.py's create_burst / transmit_create_frame).
#include <jni.h>                                     // JNI
#include <stdlib.h>                                  // malloc
#include <string.h>                                  // memcpy
#include <pthread.h>                                 // the lock
#include "codec2/freedv_api.h"                       // FreeDV data modes (codec2)

#define NRX 2                                        // receivers: DATAC13, DATAC14
static const int MODES[NRX] = { FREEDV_MODE_DATAC13, FREEDV_MODE_DATAC14 };
static struct freedv *g_rx[NRX];                     // made on first use
static short *g_buf[NRX]; static int g_have[NRX];    // audio waiting for each
static unsigned char g_out[8192]; static int g_outLen; // frames received, not yet taken: [mode][snr x10 (signed)][length][bytes]...
static pthread_mutex_t g_mx = PTHREAD_MUTEX_INITIALIZER;

static void open_rx(void)                            // (under g_mx)
{
    for (int i = 0; i < NRX; i++) if (!g_rx[i]) {
        g_rx[i] = freedv_open(MODES[i]);
        if (g_rx[i]) freedv_set_frames_per_burst(g_rx[i], 1); // burst mode: look for each burst's preamble (FreeDATA demodulator.py init_codec2_mode)
        if (g_rx[i]) { g_buf[i] = (short *)malloc(sizeof(short) * (freedv_get_n_max_modem_samples(g_rx[i]) + 4000)); g_have[i] = 0; }
    }
}

// Audio in: 8 kHz mono.
JNIEXPORT void JNICALL Java_uk_hamdigital_engine_FreeDataNative_process(JNIEnv *env, jclass cls, jshortArray a, jint n)
{
    jshort *s = (*env)->GetShortArrayElements(env, a, NULL);
    pthread_mutex_lock(&g_mx);
    open_rx();
    for (int i = 0; i < NRX; i++) {
        struct freedv *f = g_rx[i]; if (!f) continue;
        int cap = freedv_get_n_max_modem_samples(f) + 4000, pos = 0;
        while (pos < n) {                             // fill, demodulate as much as it asks for, repeat
            int take = n - pos; if (g_have[i] + take > cap) take = cap - g_have[i];
            memcpy(g_buf[i] + g_have[i], s + pos, sizeof(short) * take); g_have[i] += take; pos += take;
            int nin;
            while (g_have[i] >= (nin = freedv_nin(f))) {
                int bpf = freedv_get_bits_per_modem_frame(f) / 8;
                unsigned char bytes[1024];
                int nb = freedv_rawdatarx(f, bytes, g_buf[i]);
                memmove(g_buf[i], g_buf[i] + nin, sizeof(short) * (g_have[i] - nin)); g_have[i] -= nin;
                if (nb == bpf && nb > 2 && g_outLen + nb + 3 < (int)sizeof g_out) { // a good frame (FreeDATA: nbytes == bytes_per_frame)
                    int sync = 0; float snr = 0; freedv_get_modem_stats(f, &sync, &snr);
                    int s10 = (int)(snr * 10); if (s10 > 127) s10 = 127; if (s10 < -127) s10 = -127;
                    g_out[g_outLen++] = (unsigned char)MODES[i]; g_out[g_outLen++] = (unsigned char)(signed char)s10; g_out[g_outLen++] = (unsigned char)(nb - 2);
                    memcpy(g_out + g_outLen, bytes, nb - 2); g_outLen += nb - 2; // (without the CRC)
                }
            }
        }
    }
    pthread_mutex_unlock(&g_mx);
    (*env)->ReleaseShortArrayElements(env, a, s, JNI_ABORT);
}

// The frames received since the last call: [mode][snr x10, signed][length][bytes] one after another.
JNIEXPORT jbyteArray JNICALL Java_uk_hamdigital_engine_FreeDataNative_take(JNIEnv *env, jclass cls)
{
    pthread_mutex_lock(&g_mx);
    jbyteArray r = (*env)->NewByteArray(env, g_outLen);
    if (g_outLen) (*env)->SetByteArrayRegion(env, r, 0, g_outLen, (const jbyte *)g_out);
    g_outLen = 0;
    pthread_mutex_unlock(&g_mx);
    return r;
}

// [rx in sync 0/1, SNR dB] for the DATAC13 receiver (the one most traffic starts on).
JNIEXPORT jfloatArray JNICALL Java_uk_hamdigital_engine_FreeDataNative_stats(JNIEnv *env, jclass cls)
{
    jfloat v[2] = {0, 0};
    pthread_mutex_lock(&g_mx); open_rx();
    if (g_rx[0]) { int sync = 0; float snr = 0; freedv_get_modem_stats(g_rx[0], &sync, &snr); v[0] = (float)sync; v[1] = snr; }
    pthread_mutex_unlock(&g_mx);
    jfloatArray a = (*env)->NewFloatArray(env, 2); (*env)->SetFloatArrayRegion(env, a, 0, 2, v); return a;
}

// The audio (8 kHz) for one frame in mode (DATAC13 = 19, DATAC14 = 20 ...): preamble, the frame with its CRC16, postamble.
// [frame] is the payload (frame type first); it is padded with zeros to the mode's size. Null if the mode will not open
// or the frame is too long.
JNIEXPORT jshortArray JNICALL Java_uk_hamdigital_engine_FreeDataNative_encode(JNIEnv *env, jclass cls, jint mode, jbyteArray jframe)
{
    struct freedv *f = freedv_open(mode);
    if (!f) return NULL;
    freedv_set_frames_per_burst(f, 1);                 // (as the receivers)
    int bpf = freedv_get_bits_per_modem_frame(f) / 8, payload = bpf - 2;
    int len = (*env)->GetArrayLength(env, jframe);
    if (len > payload) { freedv_close(f); return NULL; }
    unsigned char *buf = (unsigned char *)calloc(bpf, 1);
    (*env)->GetByteArrayRegion(env, jframe, 0, len, (jbyte *)buf);
    unsigned short crc = freedv_gen_crc16(buf, payload);  // (modulator.py: big-endian after the payload)
    buf[payload] = crc >> 8; buf[payload + 1] = crc & 0xFF;
    int ntx = freedv_get_n_tx_modem_samples(f), npre = freedv_get_n_tx_preamble_modem_samples(f), npost = freedv_get_n_tx_postamble_modem_samples(f);
    short *out = (short *)malloc(sizeof(short) * (npre + ntx + npost + 16));
    int n = 0;
    n += freedv_rawdatapreambletx(f, out + n);
    freedv_rawdatatx(f, out + n, buf); n += ntx;
    n += freedv_rawdatapostambletx(f, out + n);
    jshortArray r = (*env)->NewShortArray(env, n); (*env)->SetShortArrayRegion(env, r, 0, n, out);
    free(out); free(buf); freedv_close(f);
    return r;
}

// JNI bridge for FreeDV (codec2's FreeDV API, LGPL 2.1): a session per handle - the modem and codec for one mode -
// with its text channel (the short message FreeDV sends alongside the voice): received characters are collected for
// the app, and the text to send is repeated.
#include <jni.h>                                     // JNI
#include <stdlib.h>                                  // malloc
#include <string.h>                                  // strncpy
#include <stdint.h>                                  // intptr_t
#include "codec2/freedv_api.h"                       // FreeDV (codec2)

typedef struct {
    struct freedv *f;                                // the FreeDV session
    char rx[256]; int rxLen;                         // text received, not yet collected
    char tx[128]; int txPos;                         // text to send, and where in it we are
} Session;

static void rx_char(void *state, char c) {           // FreeDV: a text character received
    Session *s = (Session *)state;
    if (s->rxLen < (int)sizeof s->rx - 1) s->rx[s->rxLen++] = c;
}

static char tx_char(void *state) {                    // FreeDV: the next text character to send (the text, repeated)
    Session *s = (Session *)state;
    if (!s->tx[0]) return 0;                          // (nothing to send)
    char c = s->tx[s->txPos++];
    if (!s->tx[s->txPos]) s->txPos = 0;
    return c;
}

// A session for mode (FREEDV_MODE_700D = 7, 700E = 13, 1600 = 0); 0 if it could not be opened.
JNIEXPORT jlong JNICALL Java_uk_hamdigital_engine_FreeDvNative_open(JNIEnv *env, jclass cls, jint mode)
{
    struct freedv *f = freedv_open(mode);
    if (!f) return 0;
    Session *s = (Session *)calloc(1, sizeof(Session)); s->f = f;
    freedv_set_callback_txt(f, rx_char, tx_char, s);  // the text channel
    freedv_set_squelch_en(f, 1);                      // quiet when there is no signal
    return (jlong)(intptr_t)s;
}

JNIEXPORT void JNICALL Java_uk_hamdigital_engine_FreeDvNative_close(JNIEnv *env, jclass cls, jlong h)
{
    Session *s = (Session *)(intptr_t)h; if (!s) return;
    freedv_close(s->f); free(s);
}

// Sizes: [samples freedv_rx wants next (nin), speech samples a frame, most speech samples rx gives, modem samples tx gives,
// modem sample rate, speech sample rate]
JNIEXPORT jintArray JNICALL Java_uk_hamdigital_engine_FreeDvNative_sizes(JNIEnv *env, jclass cls, jlong h)
{
    Session *s = (Session *)(intptr_t)h;
    jint v[6] = { freedv_nin(s->f), freedv_get_n_speech_samples(s->f), freedv_get_n_max_speech_samples(s->f),
                  freedv_get_n_nom_modem_samples(s->f), freedv_get_modem_sample_rate(s->f), freedv_get_speech_sample_rate(s->f) };
    jintArray a = (*env)->NewIntArray(env, 6); (*env)->SetIntArrayRegion(env, a, 0, 6, v); return a;
}

// Receive: exactly nin modem samples in; the speech decoded out (possibly none).
JNIEXPORT jshortArray JNICALL Java_uk_hamdigital_engine_FreeDvNative_rx(JNIEnv *env, jclass cls, jlong h, jshortArray jin)
{
    Session *s = (Session *)(intptr_t)h;
    jshort *in = (*env)->GetShortArrayElements(env, jin, NULL);
    int max = freedv_get_n_max_speech_samples(s->f);
    short *out = (short *)malloc(sizeof(short) * max);
    int n = freedv_rx(s->f, out, in);
    (*env)->ReleaseShortArrayElements(env, jin, in, JNI_ABORT);
    jshortArray r = (*env)->NewShortArray(env, n);
    if (n > 0) (*env)->SetShortArrayRegion(env, r, 0, n, out);
    free(out); return r;
}

// Transmit: one frame of speech in (n_speech samples); the modem audio out (n_nom_modem samples).
JNIEXPORT jshortArray JNICALL Java_uk_hamdigital_engine_FreeDvNative_tx(JNIEnv *env, jclass cls, jlong h, jshortArray jspeech)
{
    Session *s = (Session *)(intptr_t)h;
    jshort *sp = (*env)->GetShortArrayElements(env, jspeech, NULL);
    int n = freedv_get_n_nom_modem_samples(s->f);
    short *mod = (short *)malloc(sizeof(short) * n);
    freedv_tx(s->f, mod, sp);
    (*env)->ReleaseShortArrayElements(env, jspeech, sp, JNI_ABORT);
    jshortArray r = (*env)->NewShortArray(env, n); (*env)->SetShortArrayRegion(env, r, 0, n, mod);
    free(mod); return r;
}

// Modem state: [sync (0/1), SNR estimate (dB), rx status flags]
JNIEXPORT jfloatArray JNICALL Java_uk_hamdigital_engine_FreeDvNative_stats(JNIEnv *env, jclass cls, jlong h)
{
    Session *s = (Session *)(intptr_t)h;
    int sync = 0; float snr = 0; freedv_get_modem_stats(s->f, &sync, &snr);
    jfloat v[3] = { (jfloat)sync, snr, (jfloat)freedv_get_rx_status(s->f) };
    jfloatArray a = (*env)->NewFloatArray(env, 3); (*env)->SetFloatArrayRegion(env, a, 0, 3, v); return a;
}

// The text received since the last call.
JNIEXPORT jstring JNICALL Java_uk_hamdigital_engine_FreeDvNative_text(JNIEnv *env, jclass cls, jlong h)
{
    Session *s = (Session *)(intptr_t)h;
    s->rx[s->rxLen] = 0; jstring r = (*env)->NewStringUTF(env, s->rx); s->rxLen = 0; return r;
}

// The text to send (repeated while transmitting; "" for none).
JNIEXPORT void JNICALL Java_uk_hamdigital_engine_FreeDvNative_setText(JNIEnv *env, jclass cls, jlong h, jstring jt)
{
    Session *s = (Session *)(intptr_t)h;
    const char *t = (*env)->GetStringUTFChars(env, jt, NULL);
    strncpy(s->tx, t, sizeof s->tx - 1); s->tx[sizeof s->tx - 1] = 0; s->txPos = 0;
    (*env)->ReleaseStringUTFChars(env, jt, t);
}

// Squelch: on / off, and the SNR (dB) below which it mutes.
JNIEXPORT void JNICALL Java_uk_hamdigital_engine_FreeDvNative_squelch(JNIEnv *env, jclass cls, jlong h, jboolean on, jfloat db)
{
    Session *s = (Session *)(intptr_t)h;
    freedv_set_squelch_en(s->f, on); freedv_set_snr_squelch_thresh(s->f, db);
}

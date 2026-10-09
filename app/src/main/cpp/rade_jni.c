// JNI bridge for FreeDV RADE V1 (rade_c, BSD-2; with Opus's LPCNet encoder and FARGAN vocoder, BSD-3): a session per
// handle, used like the codec2 one (freedv_jni.c) so the FreeDV page drives both the same way. Speech is 16 kHz, the
// modem 8 kHz. Receive: the radio's audio (real) goes in as IQ with no imaginary part; the features the decoder makes
// are turned into speech by FARGAN. Transmit: 120 ms of speech (12 LPCNet frames) makes one modem frame; the over
// ends with the end-of-over frame, which carries the callsign (rade_text: LDPC coded, as freedv-gui sends it).
#include <jni.h>                                     // JNI
#include <stdlib.h>                                  // malloc
#include <string.h>                                  // memcpy
#include <stdint.h>                                  // intptr_t
#include "rade_api.h"                                // the RADE modem
#include "rade_text.h"                               // callsign in the end-of-over frame
#include "lpcnet.h"                                  // speech -> features
#include "fargan.h"                                  // features -> speech

typedef struct {
    struct rade *r;                                  // the modem (one receiver and one transmitter)
    LPCNetEncState *enc;                             // transmit: the feature extractor
    FARGANState fg;                                  // receive: the vocoder
    int warm; float wbuf[5 * NB_FEATURES];           // FARGAN starts from 5 frames of features (rade_rx_wav does the same)
    rade_text_t txt;                                 // the callsign coder
    int nf, ntx, neoo, neb, nmax;                    // feature floats a modem frame, modem samples a frame / EOO, EOO bits, most nin
    float *feat, *eoo; RADE_COMP *iq;                // scratch: features, EOO soft bits, IQ
    char rx[256]; int rxLen;                         // callsigns received, not yet collected
} Session;

static void on_text(rade_text_t t, const char *s, int len, void *state) { // a callsign decoded (CRC good)
    Session *x = (Session *)state; (void)t;
    if (x->rxLen + len + 2 >= (int)sizeof x->rx) return;
    memcpy(x->rx + x->rxLen, s, len); x->rxLen += len; x->rx[x->rxLen++] = '\r'; // (one a line: the page shows them so)
}

static short clip(float v) { return (short)(v > 32767.f ? 32767 : v < -32768.f ? -32768 : v); } // float to 16-bit

// A RADE V1 session; 0 if it could not be opened.
JNIEXPORT jlong JNICALL Java_uk_hamdigital_engine_RadeNative_open(JNIEnv *env, jclass cls)
{
    struct rade *r = rade_open("", RADE_USE_C_ENCODER | RADE_USE_C_DECODER | RADE_VERBOSE_0); // V1, built-in weights, quiet
    if (!r) return 0;
    Session *s = (Session *)calloc(1, sizeof(Session)); s->r = r;
    s->nf = rade_n_features_in_out(r); s->ntx = rade_n_tx_out(r); s->neoo = rade_n_tx_eoo_out(r);
    s->neb = rade_n_eoo_bits(r); s->nmax = rade_nin_max(r);
    s->feat = (float *)calloc(s->nf, sizeof(float)); s->eoo = (float *)calloc(s->neb, sizeof(float));
    int niq = s->ntx > s->neoo ? s->ntx : s->neoo; if (s->nmax > niq) niq = s->nmax;
    s->iq = (RADE_COMP *)calloc(niq, sizeof(RADE_COMP));
    s->enc = lpcnet_encoder_create();                // (made for receive sessions too: small)
    fargan_init(&s->fg);
    s->txt = rade_text_create(); rade_text_set_rx_callback(s->txt, on_text, s);
    return (jlong)(intptr_t)s;
}

JNIEXPORT void JNICALL Java_uk_hamdigital_engine_RadeNative_close(JNIEnv *env, jclass cls, jlong h)
{
    Session *s = (Session *)(intptr_t)h; if (!s) return;
    rade_close(s->r); lpcnet_encoder_destroy(s->enc); rade_text_destroy(s->txt);
    free(s->feat); free(s->eoo); free(s->iq); free(s);
}

// Sizes, as FreeDvNative's: [nin, speech samples a transmit frame, most speech rx gives, modem samples tx gives,
// modem rate, speech rate]
JNIEXPORT jintArray JNICALL Java_uk_hamdigital_engine_RadeNative_sizes(JNIEnv *env, jclass cls, jlong h)
{
    Session *s = (Session *)(intptr_t)h;
    int frames = s->nf / NB_TOTAL_FEATURES;          // LPCNet frames (10 ms) a modem frame
    jint v[6] = { rade_nin(s->r), frames * LPCNET_FRAME_SIZE, frames * LPCNET_FRAME_SIZE, s->ntx, RADE_MODEM_SAMPLE_RATE, RADE_SPEECH_SAMPLE_RATE };
    jintArray a = (*env)->NewIntArray(env, 6); (*env)->SetIntArrayRegion(env, a, 0, 6, v); return a;
}

// Receive: exactly nin modem samples (8 kHz, from the radio) in; the speech decoded out (16 kHz; none without a signal).
JNIEXPORT jshortArray JNICALL Java_uk_hamdigital_engine_RadeNative_rx(JNIEnv *env, jclass cls, jlong h, jshortArray jin)
{
    Session *s = (Session *)(intptr_t)h;
    int n = (*env)->GetArrayLength(env, jin); if (n > s->nmax) n = s->nmax;
    jshort *in = (*env)->GetShortArrayElements(env, jin, NULL);
    for (int i = 0; i < n; i++) { s->iq[i].real = in[i] * (2.0f / RADE_INT16_SCALE); s->iq[i].imag = 0; } // real audio (rade_api.h)
    (*env)->ReleaseShortArrayElements(env, jin, in, JNI_ABORT);
    int has_eoo = 0;
    int nout = rade_rx(s->r, s->feat, &has_eoo, s->eoo, s->iq);
    short out[12 * LPCNET_FRAME_SIZE * 2]; int m = 0; // (12 frames a modem frame)
    for (int k = 0; k + NB_TOTAL_FEATURES <= nout && m + LPCNET_FRAME_SIZE <= (int)(sizeof out / sizeof out[0]); k += NB_TOTAL_FEATURES) {
        if (s->warm < 5) {                           // the first five frames start FARGAN off
            memcpy(s->wbuf + s->warm * NB_FEATURES, s->feat + k, sizeof(float) * NB_FEATURES);
            if (++s->warm == 5) { float z[FARGAN_CONT_SAMPLES] = {0}; fargan_cont(&s->fg, z, s->wbuf); }
            continue;
        }
        float pcm[LPCNET_FRAME_SIZE]; fargan_synthesize(&s->fg, pcm, s->feat + k);
        for (int i = 0; i < LPCNET_FRAME_SIZE; i++) out[m++] = clip(pcm[i] * 32768.f);
    }
    if (has_eoo) { rade_text_rx(s->txt, s->eoo, s->neb); s->warm = 0; fargan_init(&s->fg); } // the over ended: its callsign; the next starts afresh
    jshortArray r = (*env)->NewShortArray(env, m);
    if (m > 0) (*env)->SetShortArrayRegion(env, r, 0, m, out);
    return r;
}

// Transmit: one frame of speech (16 kHz, sizes[1] samples) in; its modem audio (8 kHz, sizes[3] samples) out.
JNIEXPORT jshortArray JNICALL Java_uk_hamdigital_engine_RadeNative_tx(JNIEnv *env, jclass cls, jlong h, jshortArray jspeech)
{
    Session *s = (Session *)(intptr_t)h;
    int frames = s->nf / NB_TOTAL_FEATURES;
    jshort *sp = (*env)->GetShortArrayElements(env, jspeech, NULL);
    for (int k = 0; k < frames; k++)                 // 10 ms at a time: the features
        lpcnet_compute_single_frame_features(s->enc, (opus_int16 *)sp + k * LPCNET_FRAME_SIZE, s->feat + k * NB_TOTAL_FEATURES, 0);
    (*env)->ReleaseShortArrayElements(env, jspeech, sp, JNI_ABORT);
    int n = rade_tx(s->r, s->iq, s->feat);
    jshortArray r = (*env)->NewShortArray(env, n);
    short *mod = (short *)malloc(sizeof(short) * n);
    for (int i = 0; i < n; i++) mod[i] = clip(s->iq[i].real * RADE_INT16_SCALE); // the real part (as rade_tx_wav)
    (*env)->SetShortArrayRegion(env, r, 0, n, mod); free(mod); return r;
}

// The end of an over: the end-of-over frame (with the callsign set), to send last.
JNIEXPORT jshortArray JNICALL Java_uk_hamdigital_engine_RadeNative_txEnd(JNIEnv *env, jclass cls, jlong h)
{
    Session *s = (Session *)(intptr_t)h;
    int n = rade_tx_eoo(s->r, s->iq);
    jshortArray r = (*env)->NewShortArray(env, n);
    short *mod = (short *)malloc(sizeof(short) * n);
    for (int i = 0; i < n; i++) mod[i] = clip(s->iq[i].real * RADE_INT16_SCALE);
    (*env)->SetShortArrayRegion(env, r, 0, n, mod); free(mod); return r;
}

// [in sync 0/1, SNR estimate dB (3 kHz), frequency offset Hz]
JNIEXPORT jfloatArray JNICALL Java_uk_hamdigital_engine_RadeNative_stats(JNIEnv *env, jclass cls, jlong h)
{
    Session *s = (Session *)(intptr_t)h;
    jfloat v[3] = { rade_sync(s->r) ? 1.f : 0.f, rade_snrdB_3k_est(s->r), rade_freq_offset(s->r) };
    jfloatArray a = (*env)->NewFloatArray(env, 3); (*env)->SetFloatArrayRegion(env, a, 0, 3, v); return a;
}

// Callsigns received since the last call (each ending '\r').
JNIEXPORT jstring JNICALL Java_uk_hamdigital_engine_RadeNative_text(JNIEnv *env, jclass cls, jlong h)
{
    Session *s = (Session *)(intptr_t)h;
    s->rx[s->rxLen] = 0; jstring t = (*env)->NewStringUTF(env, s->rx); s->rxLen = 0; return t;
}

// The callsign to send in the end-of-over frame (up to 8 characters: A-Z, 0-9 and a little punctuation).
JNIEXPORT void JNICALL Java_uk_hamdigital_engine_RadeNative_setText(JNIEnv *env, jclass cls, jlong h, jstring jt)
{
    Session *s = (Session *)(intptr_t)h;
    const char *t = (*env)->GetStringUTFChars(env, jt, NULL);
    rade_text_generate_tx_string(s->txt, t, (int)strlen(t), s->eoo, s->neb);
    rade_tx_set_eoo_bits(s->r, s->eoo);
    (*env)->ReleaseStringUTFChars(env, jt, t);
}

// JNI bridge for WSPR: decodes a slot's WAV file with wsprd (wspr_run.c) and returns its spots as text.
#include <jni.h>                                     // JNI
#include <stdlib.h>                                  // malloc
#include "wspr_run.h"                                // the decoder

JNIEXPORT jstring JNICALL Java_uk_hamdigital_engine_WsprNative_decode(JNIEnv *env, jclass cls, jstring jwav, jstring jdir, jdouble dial_mhz)
{
    const char *wav = (*env)->GetStringUTFChars(env, jwav, NULL); // the WAV file
    const char *dir = (*env)->GetStringUTFChars(env, jdir, NULL); // wsprd's data folder
    int size = 64 * 1024; char *out = (char *)malloc(size); // room for the spots
    int n = wspr_decode_file(wav, dir, dial_mhz, out, size); // decode
    (*env)->ReleaseStringUTFChars(env, jwav, wav); (*env)->ReleaseStringUTFChars(env, jdir, dir);
    jstring r = n < 0 ? NULL : (*env)->NewStringUTF(env, out); // null: wsprd failed
    free(out); return r;
}

// WSPR transmit audio for "CALL GRID DBM" centred on f0 Hz (12 kHz, 110.6 s); null if it cannot be encoded.
JNIEXPORT jshortArray JNICALL Java_uk_hamdigital_engine_WsprNative_encode(JNIEnv *env, jclass cls, jstring jmsg, jfloat f0, jfloat amplitude)
{
    const char *msg = (*env)->GetStringUTFChars(env, jmsg, NULL);
    int max = 162 * 8192;
    int16_t *buf = (int16_t *)malloc(sizeof(int16_t) * max);
    int n = wspr_encode_audio(msg, f0, buf, max, amplitude);
    (*env)->ReleaseStringUTFChars(env, jmsg, msg);
    jshortArray out = NULL;
    if (n > 0) { out = (*env)->NewShortArray(env, n); (*env)->SetShortArrayRegion(env, out, 0, n, buf); }
    free(buf); return out;
}

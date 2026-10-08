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

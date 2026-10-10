// JNI bridge to Dire Wolf (cpp/direwolf, GPL v2 or later, through dw_glue.c): APRS and AX.25 packet at 1200 baud (VHF /
// UHF FM) or 300 baud (HF). One receiver for the app; Dire Wolf is not thread-safe, so a lock covers every call.
#include <jni.h>                                     // JNI
#include <pthread.h>                                 // the lock
#include <stdlib.h>                                  // free
#include "dw_glue.h"                                 // Dire Wolf as a receiver / transmitter

static pthread_mutex_t g_mx = PTHREAD_MUTEX_INITIALIZER;
static int g_ready = 0;                              // set up yet?

// Set the receiver up for baud (300 / 1200) at rate samples a second (again after a change).
JNIEXPORT void JNICALL Java_uk_hamdigital_engine_AprsNative_init(JNIEnv *env, jclass cls, jint baud, jint rate)
{
    pthread_mutex_lock(&g_mx); dwg_init(baud, rate); g_ready = 1; pthread_mutex_unlock(&g_mx);
}

// Audio in.
JNIEXPORT void JNICALL Java_uk_hamdigital_engine_AprsNative_process(JNIEnv *env, jclass cls, jshortArray a, jint n)
{
    jshort *s = (*env)->GetShortArrayElements(env, a, NULL);
    pthread_mutex_lock(&g_mx); if (g_ready) dwg_process(s, n); pthread_mutex_unlock(&g_mx);
    (*env)->ReleaseShortArrayElements(env, a, s, JNI_ABORT);
}

// The frames decoded since the last call (dw_glue.h: one a line, tab-separated fields).
JNIEXPORT jstring JNICALL Java_uk_hamdigital_engine_AprsNative_take(JNIEnv *env, jclass cls)
{
    static char buf[65536];
    pthread_mutex_lock(&g_mx); dwg_take(buf, sizeof buf); pthread_mutex_unlock(&g_mx);
    return (*env)->NewStringUTF(env, buf);
}

// The audio for one frame in TNC2 monitor format ("SRC>DEST,PATH:info"); null if it is not a valid frame.
JNIEXPORT jshortArray JNICALL Java_uk_hamdigital_engine_AprsNative_encode(JNIEnv *env, jclass cls, jstring jt, jint baud, jint rate, jdouble amplitude)
{
    const char *t = (*env)->GetStringUTFChars(env, jt, NULL);
    short *a = NULL;
    pthread_mutex_lock(&g_mx); int n = dwg_encode(t, baud, rate, amplitude, &a); pthread_mutex_unlock(&g_mx);
    (*env)->ReleaseStringUTFChars(env, jt, t);
    if (n < 0) return NULL;
    jshortArray r = (*env)->NewShortArray(env, n); (*env)->SetShortArrayRegion(env, r, 0, n, a); free(a);
    return r;
}

// JNI bridge for FT8 / FT4: hands a slot of audio to ft8_slot.c (ft8_lib) and returns its messages as strings.
#include <jni.h>                                     // JNI
#include "ft8_slot.h"                                // the slot decoder

#define MAX_LINES 60                                 // messages a slot

// samples: 12 kHz mono from the slot's start; returns "snr\tdt\tfreq\ttext" per message.
JNIEXPORT jobjectArray JNICALL Java_uk_hamdigital_engine_Ft8Native_decode(JNIEnv *env, jclass cls, jshortArray jsamples, jint n, jboolean ft4)
{
    static char lines[MAX_LINES][FT8_LINE];          // results (one decode at a time: ft8_slot.c locks)
    jshort *s = (*env)->GetShortArrayElements(env, jsamples, NULL); // the audio
    int count = ft8_decode_slot(s, n, ft4, lines, MAX_LINES); // decode
    (*env)->ReleaseShortArrayElements(env, jsamples, s, JNI_ABORT); // unchanged
    jobjectArray out = (*env)->NewObjectArray(env, count, (*env)->FindClass(env, "java/lang/String"), NULL); // to Kotlin
    for (int i = 0; i < count; i++) (*env)->SetObjectArrayElement(env, out, i, (*env)->NewStringUTF(env, lines[i]));
    return out;
}

// JNI bridge for JS8: decodes a 15-second JS8 Normal slot with JS8Call's decoder (js8/js8_run.cpp) and returns each
// frame, unpacked, as a tab-separated line.
#include <jni.h>                                     // JNI
#include <string>
#include <vector>
#include "js8/js8_run.h"                             // the decoder

// samples: 12 kHz mono from the slot's start; nfqso: the receive offset (Hz).
// Lines: "snr \t dt \t freq \t bits \t lowConfidence \t frameType \t from \t to \t message \t frame".
extern "C" JNIEXPORT jobjectArray JNICALL Java_uk_hamdigital_engine_Js8Native_decode(JNIEnv *env, jclass, jshortArray a, jint n, jint nfqso)
{
    jshort *s = env->GetShortArrayElements(a, nullptr); // the audio
    std::vector<Js8Line> lines;
    try { lines = js8_decode_slot(reinterpret_cast<const int16_t *>(s), n, nfqso); } catch (...) {} // (the decoder can throw on an FFT plan failure)
    env->ReleaseShortArrayElements(a, s, JNI_ABORT); // unchanged
    jobjectArray out = env->NewObjectArray((jsize)lines.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < lines.size(); i++) {
        auto const &l = lines[i];
        char head[96]; snprintf(head, sizeof head, "%d\t%.1f\t%.0f\t%d\t%d\t%d\t", l.snr, l.dt, l.freq, l.bits, l.lowConfidence ? 1 : 0, l.frameType);
        std::string line = std::string(head) + l.from + "\t" + l.to + "\t" + l.message + "\t" + l.frame;
        env->SetObjectArrayElement(out, (jsize)i, env->NewStringUTF(line.c_str())); // (UTF-8; JS8 text is ASCII or Latin-1 converted)
    }
    return out;
}

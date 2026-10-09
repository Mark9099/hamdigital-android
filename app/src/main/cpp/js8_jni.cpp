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

// ---- transmit ----
#include "js8/js8_pack.h"                            // frame packing, audio

static std::string str(JNIEnv *env, jstring s) { const char *c = env->GetStringUTFChars(s, nullptr); std::string r(c); env->ReleaseStringUTFChars(s, c); return r; }

// kind 0 heartbeat, 1 CQ (cq number in cmd), 2 directed (to, cmd, num, text), 3 text to everyone. Returns "frame\tbits" a frame.
extern "C" JNIEXPORT jobjectArray JNICALL Java_uk_hamdigital_engine_Js8Native_build(JNIEnv *env, jclass, jint kind, jstring jcall, jstring jgrid, jstring jto, jint cmd, jstring jnum, jstring jtext)
{
    std::string call = str(env, jcall), grid = str(env, jgrid), to = str(env, jto), num = str(env, jnum), text = str(env, jtext);
    std::vector<Js8TxFrame> f = kind == 0 ? js8_heartbeat(call, grid, -1) : kind == 1 ? js8_heartbeat(call, grid, cmd)
                              : kind == 2 ? js8_directed(call, to, cmd, num, text) : js8_text(call, text);
    jobjectArray out = env->NewObjectArray((jsize)f.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < f.size(); i++) env->SetObjectArrayElement(out, (jsize)i, env->NewStringUTF((f[i].frame + "\t" + std::to_string(f[i].bits)).c_str()));
    return out;
}

// JS8 Normal audio for one frame from f0 Hz (12 kHz, 12.64 s).
extern "C" JNIEXPORT jshortArray JNICALL Java_uk_hamdigital_engine_Js8Native_audio(JNIEnv *env, jclass, jstring jframe, jint bits, jdouble f0, jdouble amplitude)
{
    std::vector<int16_t> a = js8_tx_audio(Js8TxFrame{str(env, jframe), bits}, f0, amplitude);
    jshortArray out = env->NewShortArray((jsize)a.size()); env->SetShortArrayRegion(out, 0, (jsize)a.size(), a.data()); return out;
}

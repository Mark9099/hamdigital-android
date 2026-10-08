// JNI bridge to the shared CW (Morse) decoder engine (HamPropCore hamprop_common: cw_decoder.cpp, from the
// Tab5CWDecoder project, also used by HF Propagation). One decoder for the app; the audio thread feeds it, the screen reads it (a mutex between).
#include <jni.h>                                     // JNI
#include <mutex>                                     // the lock
#include "cw_decoder.h"                              // the engine

static CwDecoder *g_dec;                             // the decoder (made on first use)
static std::mutex g_mx;                              // audio thread vs screen

static CwDecoder &dec() { if (!g_dec) { g_dec = new CwDecoder(); g_dec->begin(); } return *g_dec; } // under g_mx

extern "C" JNIEXPORT void JNICALL Java_uk_hamdigital_engine_CwNative_reset(JNIEnv *, jclass) // fresh decoder
{
    std::lock_guard<std::mutex> l(g_mx); dec().begin();
}

extern "C" JNIEXPORT void JNICALL Java_uk_hamdigital_engine_CwNative_process(JNIEnv *env, jclass, jshortArray a, jint n) // n samples, 16 kHz mono
{
    jshort *s = env->GetShortArrayElements(a, nullptr); // the samples
    std::lock_guard<std::mutex> l(g_mx);
    CwDecoder &d = dec();
    for (int i = 0; i + 256 <= n; i += 256) { d.processAudioBlock(s + i, 256); d.tick(); } // 256-sample blocks, as the Tab5
    if (n % 256) { d.processAudioBlock(s + n - n % 256, n % 256); d.tick(); } // the remainder
    env->ReleaseShortArrayElements(a, s, JNI_ABORT); // unchanged
}

// [locked, toneOn, lockedBin, wpm, toneHz, snrDb, sensitivity, startWpm, follow, noiseFloor, bin 0 .. bin 13 power]
extern "C" JNIEXPORT jfloatArray JNICALL Java_uk_hamdigital_engine_CwNative_state(JNIEnv *env, jclass)
{
    float v[10 + CwDecoder::kNumBins];
    {
        std::lock_guard<std::mutex> l(g_mx); CwDecoder &d = dec();
        v[0] = d.everLocked(); v[1] = d.toneActive(); v[2] = (float)d.lockedBinIndex(); v[3] = d.wpm(); v[4] = d.toneFrequencyHz();
        v[5] = d.signalSnrDb(); v[6] = d.sensitivity01(); v[7] = d.startWpm(); v[8] = d.followTone(); v[9] = d.noiseFloor();
        for (int i = 0; i < CwDecoder::kNumBins; i++) v[10 + i] = d.binPower(i);
    }
    jfloatArray out = env->NewFloatArray(10 + CwDecoder::kNumBins); env->SetFloatArrayRegion(out, 0, 10 + CwDecoder::kNumBins, v); return out;
}

extern "C" JNIEXPORT jstring JNICALL Java_uk_hamdigital_engine_CwNative_text(JNIEnv *env, jclass) // the transcript (ASCII)
{
    std::string t; { std::lock_guard<std::mutex> l(g_mx); t = dec().decodedText(); }
    return env->NewStringUTF(t.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_uk_hamdigital_engine_CwNative_symbol(JNIEnv *env, jclass) // the letter in progress
{
    std::string t; { std::lock_guard<std::mutex> l(g_mx); t = dec().currentSymbol(); }
    return env->NewStringUTF(t.c_str());
}

extern "C" JNIEXPORT void JNICALL Java_uk_hamdigital_engine_CwNative_control(JNIEnv *, jclass, jint what, jfloat value) // 0 clear, 1 auto tune, 2 reset speed, 3 lock bin, 4 sensitivity, 5 start WPM, 6 follow
{
    std::lock_guard<std::mutex> l(g_mx); CwDecoder &d = dec();
    switch (what) {
    case 0: d.clearText(); break;
    case 1: d.autoTune(); break;
    case 2: d.resetSpeed(); break;
    case 3: if (value >= 0 && value < CwDecoder::kNumBins) d.forceLock((int)value); break;
    case 4: d.setSensitivity01(value); break;
    case 5: d.setStartWpm(value); break;
    case 6: d.setFollowTone(value != 0); break;
    }
}

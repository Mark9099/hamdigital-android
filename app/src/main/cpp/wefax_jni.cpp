// JNI bridge to the weather-fax receiver (fldigi/wefax_rx.cxx: fldigi's wefax, receive only). One receiver for the app;
// the audio thread feeds it, the screen reads its state and the picture's new rows (a lock between them).
#include <jni.h>                                     // JNI
#include <mutex>                                     // the lock
#include <vector>                                    // samples, pixels
#include "fldigi/wefax_rx.h"                         // the receiver

static WefaxRx *g_fax;                               // made on first use (or on a change of IOC)
static int g_ioc = 576; static double g_carrier = 1900; static int g_shift = 800, g_filter = 0; // settings kept across a new receiver
static std::mutex g_mx;                              // audio thread vs screen

static WefaxRx &fax() { if (!g_fax) { g_fax = new WefaxRx(g_ioc); g_fax->set_carrier(g_carrier); g_fax->set_shift(g_shift); g_fax->set_filter(g_filter); } return *g_fax; } // (under g_mx)

// Audio in: 11025 Hz mono.
extern "C" JNIEXPORT void JNICALL Java_uk_hamdigital_engine_WefaxNative_process(JNIEnv *env, jclass, jshortArray a, jint n)
{
    jshort *s = env->GetShortArrayElements(a, nullptr);
    std::vector<double> d(n); for (int i = 0; i < n; i++) d[i] = s[i] / 32768.0; // as fldigi's sound card input
    env->ReleaseShortArrayElements(a, s, JNI_ABORT);
    std::lock_guard<std::mutex> l(g_mx);
    fax().rx_process(d.data(), n);
}

// [state (0 APT start - waiting, 1 APT stop, 2 phasing, 3 picture, 4 idle), rows so far, width, correlation 0..1 x 1000,
//  APT transition rate Hz, lines a minute]
extern "C" JNIEXPORT jintArray JNICALL Java_uk_hamdigital_engine_WefaxNative_state(JNIEnv *env, jclass)
{
    jint v[6];
    { std::lock_guard<std::mutex> l(g_mx); WefaxRx &f = fax();
      v[0] = f.state(); v[1] = f.rows(); v[2] = f.width(); v[3] = (jint)(f.correlation() * 1000); v[4] = f.apt_freq(); v[5] = (jint)f.lpm(); }
    jintArray out = env->NewIntArray(6); env->SetIntArrayRegion(out, 0, 6, v); return out;
}

// The picture's pixels from row [from] to the last row received so far (the last may be part done), grey 0..255.
extern "C" JNIEXPORT jbyteArray JNICALL Java_uk_hamdigital_engine_WefaxNative_rows(JNIEnv *env, jclass, jint from)
{
    std::vector<unsigned char> px;
    { std::lock_guard<std::mutex> l(g_mx); WefaxRx &f = fax(); const auto &p = f.pixels(); size_t start = (size_t)from * f.width();
      if (start < p.size()) px.assign(p.begin() + start, p.end()); }
    jbyteArray out = env->NewByteArray((jsize)px.size());
    if (!px.empty()) env->SetByteArrayRegion(out, 0, (jsize)px.size(), (const jbyte *)px.data());
    return out;
}

// A finished picture worth keeping (once), as [width, height] + pixels; null if none.
extern "C" JNIEXPORT jbyteArray JNICALL Java_uk_hamdigital_engine_WefaxNative_finished(JNIEnv *env, jclass, jintArray size)
{
    std::vector<unsigned char> px; int w = 0, h = 0; bool got;
    { std::lock_guard<std::mutex> l(g_mx); got = fax().take_finished(px, w, h); }
    if (!got) return nullptr;
    jint wh[2] = { w, h }; env->SetIntArrayRegion(size, 0, 2, wh);
    jbyteArray out = env->NewByteArray((jsize)px.size());
    env->SetByteArrayRegion(out, 0, (jsize)px.size(), (const jbyte *)px.data());
    return out;
}

// what: 0 carrier Hz, 1 shift Hz, 2 input filter (0 narrow, 1 medium, 2 wide), 3 IOC (576 / 288: a new receiver),
// 4 start a picture now, 5 end the picture now
extern "C" JNIEXPORT void JNICALL Java_uk_hamdigital_engine_WefaxNative_control(JNIEnv *, jclass, jint what, jdouble value)
{
    std::lock_guard<std::mutex> l(g_mx);
    switch (what) {
    case 0: g_carrier = value; fax().set_carrier(value); break;
    case 1: g_shift = (int)value; fax().set_shift(g_shift); break;
    case 2: g_filter = (int)value; fax().set_filter(g_filter); break;
    case 3: if ((int)value != g_ioc) { delete g_fax; g_fax = nullptr; g_ioc = (int)value; fax(); } break;
    case 4: fax().start_now(); break;
    case 5: fax().stop_now(); break;
    }
}

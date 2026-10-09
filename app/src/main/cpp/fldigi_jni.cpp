// JNI bridge to fldigi's RTTY and PSK31 / 63 / 125 receivers (fldigi/rtty_rx.cxx, fldigi/psk31_rx.cxx). One receiver of each for
// the app; the audio thread feeds the open one, the screen reads its text and state (a lock between them).
#include <jni.h>                                     // JNI
#include <mutex>                                     // the lock
#include <vector>                                    // sample conversion
#include "fldigi/rtty_rx.h"                          // RTTY
#include "fldigi/psk31_rx.h"                         // PSK31

static RttyRx *g_rtty;                               // made on first use
static Psk31Rx *g_psk;
static int g_speed = 31; static bool g_afc = true; static double g_sql = 25; // PSK: the speed, and the settings kept across a change of speed
static std::mutex g_mx;                              // audio thread vs screen

static RttyRx &rtty() { if (!g_rtty) g_rtty = new RttyRx(); return *g_rtty; } // (under g_mx)
static Psk31Rx &psk() { if (!g_psk) { g_psk = new Psk31Rx(g_speed); g_psk->set_afc(g_afc); g_psk->set_squelch(g_sql); } return *g_psk; }

// mode: 0 RTTY, 1 PSK31. 8 kHz mono audio.
extern "C" JNIEXPORT void JNICALL Java_uk_hamdigital_engine_KbNative_process(JNIEnv *env, jclass, jint mode, jshortArray a, jint n)
{
    jshort *s = env->GetShortArrayElements(a, nullptr); // the samples
    std::vector<double> d(n); for (int i = 0; i < n; i++) d[i] = s[i] / 32768.0; // as fldigi's sound card input
    env->ReleaseShortArrayElements(a, s, JNI_ABORT);
    std::lock_guard<std::mutex> l(g_mx);
    if (mode == 0) rtty().rx_process(d.data(), n); else psk().rx_process(d.data(), n);
}

// [frequency Hz, metric 0..100, s/n dB, dcd (PSK) 0/1, imd dB (PSK)]
extern "C" JNIEXPORT jdoubleArray JNICALL Java_uk_hamdigital_engine_KbNative_state(JNIEnv *env, jclass, jint mode)
{
    double v[5];
    {
        std::lock_guard<std::mutex> l(g_mx);
        if (mode == 0) { RttyRx &r = rtty(); v[0] = r.get_freq(); v[1] = r.get_metric(); v[2] = r.get_snr_db(); v[3] = 1; v[4] = 0; }
        else { Psk31Rx &p = psk(); v[0] = p.get_freq(); v[1] = p.get_metric(); v[2] = p.get_snr_db(); v[3] = p.get_dcd(); v[4] = p.get_imd_db(); }
    }
    jdoubleArray out = env->NewDoubleArray(5); env->SetDoubleArrayRegion(out, 0, 5, v); return out;
}

// The text decoded since the last call (ASCII; anything else dropped).
extern "C" JNIEXPORT jstring JNICALL Java_uk_hamdigital_engine_KbNative_text(JNIEnv *env, jclass, jint mode)
{
    std::string t; { std::lock_guard<std::mutex> l(g_mx); t = mode == 0 ? rtty().take_text() : psk().take_text(); }
    std::string clean; for (char c : t) if ((c >= 32 && c < 127) || c == '\n') clean += c; else if (c == '\r') {} // printable, new lines (CR dropped)
    return env->NewStringUTF(clean.c_str());
}

// what: 0 frequency, 1 AFC on/off, 2 squelch 0..100, 3 reverse (RTTY), 4 reset, 5 RTTY shift (Hz), 7 PSK speed (31, 63, 125)
extern "C" JNIEXPORT void JNICALL Java_uk_hamdigital_engine_KbNative_control(JNIEnv *, jclass, jint mode, jint what, jdouble value)
{
    std::lock_guard<std::mutex> l(g_mx);
    if (mode == 0) {
        RttyRx &r = rtty();
        switch (what) {
        case 0: r.set_freq(value); r.reset(); break;
        case 1: r.set_afc(value != 0); break;
        case 2: r.set_squelch(value); break;
        case 3: r.set_reverse(value != 0); break;
        case 4: r.reset(); break;
        case 5: r.set_params(value, 45.45, 5, RttyRx::PARITY_NONE, 1.5); break; // (other shifts at 45.45 baud)
        }
    } else {
        Psk31Rx &p = psk();
        switch (what) {
        case 0: p.set_freq(value); break;
        case 1: p.set_afc(value != 0); g_afc = value != 0; break;
        case 2: p.set_squelch(value); g_sql = value; break;
        case 4: p.reset(); break;
        case 7: if ((int)value != g_speed) { double f = p.get_freq(); delete g_psk; g_psk = nullptr; g_speed = (int)value; psk().set_freq(f); } break; // a new receiver at that speed, same frequency
        }
    }
}

// Transmit audio for a whole message (8 kHz): mode 0 RTTY (centre f0, shift Hz, rate = baud), 1 PSK (carrier f0, rate = speed 31 / 63 / 125).
#include "fldigi/kb_tx.h"
extern "C" JNIEXPORT jshortArray JNICALL Java_uk_hamdigital_engine_KbNative_encode(JNIEnv *env, jclass, jint mode, jstring jtext, jdouble f0, jdouble shift, jdouble rate, jdouble amplitude)
{
    const char *c = env->GetStringUTFChars(jtext, nullptr); std::string text(c); env->ReleaseStringUTFChars(jtext, c); // the text
    std::vector<int16_t> a = mode == 0 ? rtty_tx_audio(text, f0, shift, rate, amplitude) : psk31_tx_audio(text, f0, amplitude, (int)rate);
    jshortArray out = env->NewShortArray((jsize)a.size()); env->SetShortArrayRegion(out, 0, (jsize)a.size(), a.data()); return out;
}

// PC test of the app's RTTY and PSK31 receivers (app/src/main/cpp/fldigi: fldigi's demodulators, receive only):
// makes a test signal of known text - RTTY 45.45 baud 170 Hz shift (FSK, 1.5 stop bits), and BPSK31 with fldigi's
// raised-cosine shaping and its varicode - adds white noise, decodes it, and prints what came out.
// Build and run: tools/test/run_fldigi.sh.
#include <cmath>                                     // sin
#include <cstdio>                                    // printf
#include <random>                                    // noise
#include <string>
#include <vector>
#include "rtty_rx.h"                                 // under test
#include "psk31_rx.h"
#include "pskvaricode.h"                             // the PSK31 text code (fldigi)

static const double SR = 8000;                       // fldigi's rate

/** Baudot (ITA2) letters/figures for the test text (the reverse of fldigi's tables). */
static int baudot(char c, bool &figs, std::vector<int> &out)
{
    static const char *L = "\0E\nA SIU\rDRJNFCKTZLWHYPQOBG\0MXV\0";
    static const char *F = "\0" "3\n- \a87\r$4',!:(5\")2#6019?&\0./;\0";
    for (int i = 0; i < 32; i++) {
        if (c == ' ' && i == 4) { out.push_back(4); figs = false; return 1; } // a space: back to letters (unshift on space, as transmitters allow for)
        if (L[i] == c && i != 0) { if (figs) { out.push_back(0x1F); figs = false; } out.push_back(i); return 1; }
        if (F[i] == c && i != 0) { if (!figs) { out.push_back(0x1B); figs = true; } out.push_back(i); return 1; }
    }
    return 0;
}

static std::vector<double> rtty_signal(const std::string &text, double f0, double snr_db, unsigned seed)
{
    std::vector<int> codes; bool figs = false;
    for (int i = 0; i < 8; i++) codes.push_back(0x1F); // LTRS idle first
    for (char c : text) baudot(c, figs, codes);
    for (int i = 0; i < 4; i++) codes.push_back(0x1F);
    double sps = SR / 45.45;                         // samples a bit
    std::vector<int> bits;                           // the keyed stream: mark idle, start (space), 5 data, 1.5 stop
    for (int i = 0; i < (int)(sps * 20); i++) bits.push_back(1);
    double t = 0; std::vector<double> out; double ph = 0;
    auto add = [&](int b, double nbits) { int n = (int)(t + nbits * sps) - (int)t; t += nbits * sps; for (int i = 0; i < n; i++) bits.push_back(b); };
    for (int c : codes) { add(0, 1); for (int k = 0; k < 5; k++) add((c >> k) & 1, 1); add(1, 1.5); }
    for (int i = 0; i < (int)(sps * 20); i++) bits.push_back(1);
    std::mt19937 rng(seed); std::normal_distribution<double> g(0, 1);
    double amp = 0.3, noise = amp / sqrt(2.0) / pow(10.0, snr_db / 20.0) * sqrt((SR / 2) / 2500.0); // noise RMS for that SNR in 2500 Hz
    for (int b : bits) { double f = b ? f0 + 85 : f0 - 85; ph += 2 * M_PI * f / SR; out.push_back(amp * sin(ph) + noise * g(rng)); } // mark above, space below (USB)
    return out;
}

static std::vector<double> psk_signal(const std::string &text, double f0, double snr_db, unsigned seed)
{
    std::vector<int> syms;                           // 1 = no phase change, 0 = reversal
    for (int i = 0; i < 64; i++) syms.push_back(0);  // the idle preamble of reversals
    for (char c : text) { const char *v = psk_varicode_encode((unsigned char)c); for (const char *p = v; *p; p++) syms.push_back(*p == '1'); syms.push_back(0); syms.push_back(0); }
    for (int i = 0; i < 32; i++) syms.push_back(1);  // postamble of steady carrier
    int sl = 256; std::vector<double> out; double ph = 0; double prev = 1, cur = 1;
    std::mt19937 rng(seed); std::normal_distribution<double> g(0, 1);
    double amp = 0.3, noise = amp / sqrt(2.0) / pow(10.0, snr_db / 20.0) * sqrt((SR / 2) / 2500.0);
    for (int i = 0; i < 4000; i++) out.push_back(noise * g(rng)); // a moment of noise first
    for (int s : syms) {
        prev = cur; if (!s) cur = -cur;              // a 0 reverses the phase
        for (int i = 0; i < sl; i++) {               // raised-cosine change from prev to cur over the symbol (fldigi's tx_shape)
            double sh = 0.5 * cos(i * M_PI / sl) + 0.5;
            double a = prev * sh + cur * (1 - sh);
            ph += 2 * M_PI * f0 / SR;
            out.push_back(amp * a * sin(ph) + noise * g(rng));
        }
    }
    for (int i = 0; i < 8000; i++) out.push_back(noise * g(rng));
    return out;
}

int main()
{
    const std::string text = "CQ CQ DE M7JVY M7JVY IO91 PSE K 599 73";
    for (double snr : {10.0, 0.0, -5.0, -8.0, -10.0}) {
        { RttyRx rx; rx.set_freq(1003); rx.set_squelch(0); auto s = rtty_signal(text, 1000, snr, 1); // tuned 3 Hz off: the AFC should follow
          std::string got; for (size_t i = 0; i < s.size(); i += 512) { rx.rx_process(&s[i], (int)std::min<size_t>(512, s.size() - i)); got += rx.take_text(); }
          printf("RTTY  %+5.1f dB: [%s]  (freq %.1f, s/n %.1f dB)\n", snr, got.c_str(), rx.get_freq(), rx.get_snr_db()); }
        { Psk31Rx rx; rx.set_freq(1504); auto s = psk_signal(text, 1500, snr, 2); // 4 Hz off
          std::string got; for (size_t i = 0; i < s.size(); i += 512) { rx.rx_process(&s[i], (int)std::min<size_t>(512, s.size() - i)); got += rx.take_text(); }
          printf("PSK31 %+5.1f dB: [%s]  (freq %.1f, s/n %.1f dB, metric %.0f)\n", snr, got.c_str(), rx.get_freq(), rx.get_snr_db(), rx.get_metric()); }
    }
    return 0;
}

// ---- transmit round trip (kb_tx.cxx): the app's own RTTY / PSK31 audio into its receivers ----
#include "kb_tx.h"
int tx_roundtrip()
{
    const std::string t = "CQ CQ DE M7JVY M7JVY IO91 PSE K 599 73";
    std::mt19937 rng(3); std::normal_distribution<double> g(0, 1);
    { auto a = psk31_tx_audio(t, 1200, 0.3); std::vector<double> s; for (auto v : a) s.push_back(v / 32768.0 + 0.02 * g(rng));
      Psk31Rx rx; rx.set_freq(1202); std::string got; for (size_t i = 0; i < s.size(); i += 512) { rx.rx_process(&s[i], (int)std::min<size_t>(512, s.size() - i)); got += rx.take_text(); }
      printf("PSK31 TX -> RX: [%s]\n", got.c_str()); }
    { auto a = rtty_tx_audio(t, 1500, 170, 45.45, 0.3); std::vector<double> s; for (auto v : a) s.push_back(v / 32768.0 + 0.02 * g(rng));
      RttyRx rx; rx.set_freq(1501); rx.set_squelch(0); std::string got; for (size_t i = 0; i < s.size(); i += 512) { rx.rx_process(&s[i], (int)std::min<size_t>(512, s.size() - i)); got += rx.take_text(); }
      printf("RTTY  TX -> RX: [%s]\n", got.c_str()); }
    for (int speed : {31, 63, 125})                   // each PSK speed: the app's transmitter into its receiver, tuned 3 Hz off, with noise
        for (double snr : {10.0, 0.0, -4.0, -7.0}) {  // (SNR in 2500 Hz)
            auto a = psk31_tx_audio(t, 1200, 0.3, speed); double p = 0; for (auto v : a) p += (v / 32768.0) * (v / 32768.0); p /= a.size();
            double sd = sqrt(p / pow(10.0, snr / 10.0) * (SR / 2) / 2500.0); // noise across 0-4 kHz for that SNR in 2500 Hz
            std::vector<double> s; for (auto v : a) s.push_back(v / 32768.0 + sd * g(rng));
            Psk31Rx rx(speed); rx.set_freq(1203); std::string got;
            for (size_t i = 0; i < s.size(); i += 512) { rx.rx_process(&s[i], (int)std::min<size_t>(512, s.size() - i)); got += rx.take_text(); }
            printf("PSK%-3d %+5.1f dB TX -> RX: [%s]  (%.1f s, freq %.1f, s/n %.1f dB)\n", speed, snr, got.c_str(), a.size() / SR, rx.get_freq(), rx.get_snr_db());
        }
    return 0;
}
static int run_tx = tx_roundtrip();                  // (runs before main)

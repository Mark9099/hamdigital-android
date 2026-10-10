// PC test of the app's weather-fax receiver (app/src/main/cpp/fldigi/wefax_rx.cxx: fldigi's wefax, receive only).
// Makes a fax broadcast as a station sends it - APT start (300 Hz black/white for 5 s), 30 s of phasing lines, a
// 300-line test picture at 120 lines a minute (IOC 576), APT stop (450 Hz, 5 s), then noise - frequency-modulated
// (black 1500 Hz, white 2300 Hz) at 11025 Hz with noise added, and feeds it to the receiver. Reports the states it went
// through, whether the picture was kept, its size, how far it is shifted sideways and how close it is to the one sent,
// and writes it as a PNG (wefax_<snr>.png) to look at. Also: tuning in half way, with "start now".
#include <cstdio>                                    // printf, files
#include <cmath>                                     // sin, cos
#include <random>                                    // noise
#include <vector>
#include <cstdint>
#include "wefax_rx.h"

static const int SR = 11025, W = 1809;               // sample rate, pixels a line (IOC 576)
static int pattern(int row, int col)                 // the test picture: grey bars, a black diagonal, a white frame
{
    if (row < 4 || row > 295 || col < 20 || col > W - 21) return 255;
    if (abs(col - row * 5 - 100) < 6) return 0;      // a diagonal line (shows slant)
    return (col / 150) % 2 ? 60 + (row % 100) * 1.5 : 200; // bars
}

static void write_png(const char *name, const std::vector<unsigned char> &px, int w, int h) // grey PNG, stored (no compression)
{
    auto crc = [](const unsigned char *b, size_t n, uint32_t c = 0xFFFFFFFF) { for (size_t i = 0; i < n; i++) { c ^= b[i]; for (int k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >> 1) : c >> 1; } return c; };
    FILE *f = fopen(name, "wb"); if (!f) return;
    auto be = [&](uint32_t v) { unsigned char b[4] = {(unsigned char)(v >> 24), (unsigned char)(v >> 16), (unsigned char)(v >> 8), (unsigned char)v}; fwrite(b, 1, 4, f); };
    auto chunk = [&](const char *t, const std::vector<unsigned char> &d) { be((uint32_t)d.size()); std::vector<unsigned char> td(t, t + 4); td.insert(td.end(), d.begin(), d.end()); fwrite(td.data(), 1, td.size(), f); be(~crc(td.data(), td.size())); };
    fwrite("\x89PNG\r\n\x1a\n", 1, 8, f);
    chunk("IHDR", {(unsigned char)(w >> 24), (unsigned char)(w >> 16), (unsigned char)(w >> 8), (unsigned char)w, (unsigned char)(h >> 24), (unsigned char)(h >> 16), (unsigned char)(h >> 8), (unsigned char)h, 8, 0, 0, 0, 0});
    std::vector<unsigned char> raw; for (int y = 0; y < h; y++) { raw.push_back(0); raw.insert(raw.end(), px.begin() + (size_t)y * w, px.begin() + (size_t)(y + 1) * w); }
    std::vector<unsigned char> z = {0x78, 0x01}; uint32_t a = 1, b = 0;
    for (size_t i = 0; i < raw.size(); i += 65535) { size_t n = std::min<size_t>(65535, raw.size() - i); z.push_back(i + n >= raw.size()); z.push_back(n & 255); z.push_back(n >> 8); z.push_back(~n & 255); z.push_back((~n >> 8) & 255); z.insert(z.end(), raw.begin() + i, raw.begin() + i + n); }
    for (unsigned char c : raw) { a = (a + c) % 65521; b = (b + a) % 65521; }
    z.push_back(b >> 8); z.push_back(b & 255); z.push_back(a >> 8); z.push_back(a & 255);
    chunk("IDAT", z); chunk("IEND", {}); fclose(f);
}

int main()
{
    std::mt19937 rng(7); std::normal_distribution<double> g(0, 1);
    for (double snr : {20.0, 10.0, 5.0}) {           // (SNR in 2500 Hz)
        std::vector<double> grey;                    // the broadcast as grey levels at 11025 Hz
        auto add = [&](double secs, auto fn) { int n = (int)(secs * SR); for (int i = 0; i < n; i++) grey.push_back(fn(i)); };
        add(5, [](int i) { return fmod(i * 300.0 / SR, 1.0) < 0.5 ? 255.0 : 0.0; });            // APT start: 300 Hz square wave
        double spl = SR * 60.0 / 120;                // samples a line
        add(30, [&](int i) { double p = fmod(i / spl, 1.0); return (p < 0.025 || p > 0.975) ? 255.0 : 0.0; }); // phasing: white 5 % round the line start, black
        add(300 * 60.0 / 120, [&](int i) { double l = i / spl; int row = (int)l; int col = (int)((l - row) * W); return (double)pattern(row, col); }); // the picture
        add(5, [](int i) { return fmod(i * 450.0 / SR, 1.0) < 0.5 ? 255.0 : 0.0; });            // APT stop: 450 Hz
        add(60, [](int) { return -1.0; });           // then a minute of nothing but noise (the band carries on)
        std::vector<double> audio(grey.size()); double ph = 0, sd = 0.3 / sqrt(2.0) / pow(10.0, snr / 20.0) * sqrt((SR / 2.0) / 2500.0);
        for (size_t i = 0; i < grey.size(); i++) {
            double s = 0;
            if (grey[i] >= 0) { double f = 1900 + 800 * (grey[i] / 255.0 - 0.5); ph += 2 * M_PI * f / SR; s = 0.3 * sin(ph); }
            audio[i] = s + sd * g(rng);
        }
        if (snr == 20.0) {                           // (this broadcast as a WAV too, for the phone's check: DevTest, files/test/wefax/)
            FILE *wf = fopen("wefax_broadcast.wav", "wb"); uint32_t n = (uint32_t)audio.size(), ds = n * 2, rs = 36 + ds, sr = SR, br = SR * 2, fs = 16; uint16_t fmt = 1, ch = 1, ba = 2, bps = 16;
            fwrite("RIFF", 1, 4, wf); fwrite(&rs, 4, 1, wf); fwrite("WAVEfmt ", 1, 8, wf); fwrite(&fs, 4, 1, wf); fwrite(&fmt, 2, 1, wf); fwrite(&ch, 2, 1, wf);
            fwrite(&sr, 4, 1, wf); fwrite(&br, 4, 1, wf); fwrite(&ba, 2, 1, wf); fwrite(&bps, 2, 1, wf); fwrite("data", 1, 4, wf); fwrite(&ds, 4, 1, wf);
            for (double v : audio) { int16_t q = (int16_t)std::max(-32768.0, std::min(32767.0, v * 32767)); fwrite(&q, 2, 1, wf); }
            fclose(wf);
        }
        WefaxRx rx(576); rx.set_carrier(1900);
        std::string states; int last = -1; std::vector<unsigned char> px; int w = 0, h = 0; bool got = false;
        for (size_t i = 0; i < audio.size(); i += 512) {
            rx.rx_process(&audio[i], (int)std::min<size_t>(512, audio.size() - i));
            if (rx.state() != last) { last = rx.state(); const char *n[] = {"APTstart", "APTstop", "phasing", "image", "idle"}; char b[64]; snprintf(b, sizeof b, "%s@%.0fs ", n[last], i / (double)SR); states += b; }
            if (!got && rx.take_finished(px, w, h)) { got = true; printf("  picture handed over at %.0f s\n", i / (double)SR); }
        }
        printf("SNR %+.0f dB: %s\n", snr, states.c_str());
        if (!got) { printf("  NO PICTURE (rows so far %d, correlation %.2f)\n", rx.rows(), rx.correlation()); continue; }
        int best = 0; double bestd = 1e9;            // how far sideways (the phasing should put column 0 at the line start)
        for (int sh = -60; sh <= 60; sh++) { double d = 0; int n = 0; for (int y = 20; y < std::min(h, 280); y += 4) for (int x = 40; x < W - 40; x += 3) { d += abs(px[(size_t)y * w + x] - pattern(y, (x + sh + W) % W)); n++; } d /= n; if (d < bestd) { bestd = d; best = sh; } }
        printf("  %dx%d, shifted %+d pixels, mean difference %.1f (0..255)\n", w, h, best, bestd);
        char name[64]; snprintf(name, sizeof name, "wefax_%+.0f.png", snr); write_png(name, px, w, h);
    }
    return 0;
}

// PC test of the app's JS8 decoder (app/src/main/cpp/js8: JS8Call's decoder without Qt / Boost, the message unpacker,
// the FFTW stand-in on KISS FFT - the files the app compiles) on JS8Call's own test recordings (media/tests, named
// {MODE}_{DEPTH}_{EXPECTED_DECODES}.wav). Also checks the CRC stand-in against ft8_lib's FT8 CRC (the same
// augmented-CRC algorithm as boost::augmented_crc, which WSJT-X uses for FT8). Build and run: tools/test/run_js8.sh.
#include <cstdio>                                    // printf
#include <cstdlib>                                   // rand
#include <cstring>                                   // memcmp
#include <vector>
#include "js8_run.h"                                 // under test
#include "js8_compat.h"                              // the CRC stand-in
extern "C" {
#include "ft8/crc.h"                                 // ft8_lib's FT8 CRC (reference)
}

static std::vector<int16_t> read_wav(const char *path)
{
    std::vector<int16_t> s; FILE *f = fopen(path, "rb"); if (!f) return s;
    unsigned char h[12]; if (fread(h, 1, 12, f) != 12) { fclose(f); return s; }
    for (;;) {                                       // chunks
        unsigned char c[8]; if (fread(c, 1, 8, f) != 8) break;
        unsigned len = c[4] | c[5] << 8 | c[6] << 16 | (unsigned)c[7] << 24;
        if (!memcmp(c, "data", 4)) { s.resize(len / 2); s.resize(fread(s.data(), 2, len / 2, f)); break; }
        fseek(f, len, SEEK_CUR);
    }
    fclose(f); return s;
}

int main(int argc, char **argv)
{
    int bad = 0;                                     // CRC check: 1000 random FT8 payloads
    for (int t = 0; t < 1000; t++) {
        uint8_t a[12] = {0}; for (int i = 0; i < 10; i++) a[i] = rand() & 0xFF; a[9] &= 0xF8; // 77 bits, zeros after
        uint16_t ref = ftx_compute_crc(a, 96 - 14);  // ft8_lib: 82 bits
        uint16_t got = (uint16_t)js8compat::augmented_crc<14, 0x2757>(a, 12); // boost-style over the 12 bytes
        if (ref != got) bad++;
    }
    printf("CRC stand-in vs ft8_lib FT8 CRC: %s (%d of 1000 differ)\n", bad ? "FAIL" : "ok", bad);
    for (int i = 1; i < argc; i++) {
        auto s = read_wav(argv[i]);
        if (s.size() < 100000) { printf("== %s: not used (%zu samples)\n", argv[i], s.size()); continue; }
        auto lines = js8_decode_slot(s.data(), (int)s.size(), 1500);
        printf("== %s: %zu decodes\n", argv[i], lines.size());
        for (auto &l : lines) printf("%+3d %5.1f %6.0f bits %d %s%s  [%s]\n", l.snr, l.dt, l.freq, l.bits, l.lowConfidence ? "?" : " ", l.message.c_str(), l.frame.c_str());
    }
    return 0;
}

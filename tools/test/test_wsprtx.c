// PC test of WSPR transmit audio (wspr_run.c wspr_encode_audio): encodes beacons, writes each as a slot WAV (1 s of
// noise first, noise throughout), and decodes it with the app's wsprd.
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <string.h>
#include "wspr_run.h"
static float gauss(void) { float u = (rand() + 1.0f) / (RAND_MAX + 2.0f), v = (rand() + 1.0f) / (RAND_MAX + 2.0f); return sqrtf(-2 * logf(u)) * cosf(6.2831853f * v); }
int main(int argc, char **argv)
{
    const char *msgs[] = { "M7JVY IO91 23", "G4ABC JO01 37" };
    static int16_t sig[162 * 8192], wav[114 * 12000]; static char out[65536];
    for (int m = 0; m < 2; m++) {
        int n = wspr_encode_audio(msgs[m], 1500 + 40 * m, sig, 162 * 8192, 0.05f);
        for (int i = 0; i < 114 * 12000; i++) wav[i] = (int16_t)(gauss() * 2000); // noise (the signal is about -15 dB in 2500 Hz)
        for (int i = 0; i < n && i + 12000 < 114 * 12000; i++) wav[i + 12000] += sig[i]; // 1 s in
        char path[256]; snprintf(path, sizeof path, "%s/261009_000%d.wav", argv[1], 2 * m);
        FILE *f = fopen(path, "wb"); int data = 114 * 12000 * 2; unsigned char h[44] = {'R','I','F','F'};
        int v; v = 36 + data; memcpy(h + 4, &v, 4); memcpy(h + 8, "WAVEfmt ", 8); v = 16; memcpy(h + 16, &v, 4); short s = 1; memcpy(h + 20, &s, 2); memcpy(h + 22, &s, 2);
        v = 12000; memcpy(h + 24, &v, 4); v = 24000; memcpy(h + 28, &v, 4); s = 2; memcpy(h + 32, &s, 2); s = 16; memcpy(h + 34, &s, 2); memcpy(h + 36, "data", 4); memcpy(h + 40, &data, 4);
        fwrite(h, 1, 44, f); fwrite(wav, 2, 114 * 12000, f); fclose(f);
        int k = wspr_decode_file(path, argv[2], 14.0956, out, sizeof out);
        printf("%-16s %d samples -> %s", msgs[m], n, k > 0 ? out : "NOTHING\n");
    }
    return 0;
}

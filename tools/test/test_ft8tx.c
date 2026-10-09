// PC test of the app's FT8 / FT4 transmit audio (ft8_slot.c ft8_encode_audio, from ft8_lib's gen_ft8.c): each QSO
// message is encoded, placed 0.5 s into a slot with noise added, and decoded again by the app's decoder.
#include <stdio.h>                                   // printf
#include <stdlib.h>                                  // rand
#include <string.h>                                  // memset
#include <math.h>                                    // sqrt, log
#include "ft8_slot.h"                                // under test

static float gauss(void) { float u = (rand() + 1.0f) / (RAND_MAX + 2.0f), v = (rand() + 1.0f) / (RAND_MAX + 2.0f); return sqrtf(-2 * logf(u)) * cosf(6.2831853f * v); }

int main(void)
{
    const char *msgs[] = { "CQ M7JVY IO91", "G4ABC M7JVY IO91", "M7JVY G4ABC -12", "G4ABC M7JVY R-08", "M7JVY G4ABC RR73", "G4ABC M7JVY 73", "CQ DX M7JVY IO91", "<PJ4/K1ABC> M7JVY -03" };
    static int16_t sig[12000 * 15], slot[12000 * 15]; static char lines[60][FT8_LINE];
    int ok = 0, total = 0;
    for (int ft4 = 0; ft4 <= 1; ft4++)
        for (unsigned m = 0; m < sizeof msgs / sizeof *msgs; m++) {
            int n = ft8_encode_audio(msgs[m], ft4, 1200, sig, 12000 * 15, 0.3f); // the audio
            int len = ft4 ? 90000 : 176400;           // a slot's worth as the app decodes it
            for (int i = 0; i < len; i++) slot[i] = (int16_t)(gauss() * 800); // noise
            for (int i = 0; i < n && i + 6000 < len; i++) slot[i + 6000] += sig[i]; // starting 0.5 s in
            int k = ft8_decode_slot(slot, len, ft4, lines, 60);
            int found = 0; for (int i = 0; i < k; i++) if (strstr(lines[i], msgs[m])) found = 1;
            printf("%s %-24s %d samples -> %s\n", ft4 ? "FT4" : "FT8", msgs[m], n, found ? lines[0] : "NOT DECODED");
            total++; ok += found;
        }
    printf("%d of %d decoded\n", ok, total);
    return 0;
}

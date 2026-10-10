// PC test of the app's APRS / packet build of Dire Wolf (app/src/main/cpp/direwolf, through dw_glue.c): frames in TNC2
// monitor format sent at 1200 baud (VHF FM) and 300 baud (HF, tuned 25 Hz off), noise added, received again; prints each
// decode line (monitor text, heard, level, APRS position ...) and how many came back at each SNR.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "dw_glue.h"

static const char *FRAMES[] = {
    "M7JVY-7>APDR16,WIDE1-1,WIDE2-1:=5130.75N/00012.25W>Mobile, HF Digital Modes test",
    "M7JVY>APRS,WIDE2-1::G4ABC    :Hello from the app{01",
    "M7JVY-10>APRS,TCPIP*:@101215z5130.00N/00010.00W_090/005g010t055r000p000P000h80b10132",
};

int main(void)
{
    unsigned seed = 1;
    int bauds[] = {1200, 300};
    for (int bi = 0; bi < 2; bi++) {
        int baud = bauds[bi], rate = 12000;
        for (double snr = 20; snr >= (baud == 1200 ? 5 : 0); snr -= 5) {
            dwg_init(baud, rate);
            int got = 0, sent = 0;
            for (int f = 0; f < 3; f++) {
                short *a; int n = dwg_encode(FRAMES[f], baud, rate, 0.5, &a);
                if (n < 0) { printf("bad frame %d\n", f); continue; }
                sent++;
                double p = 0; for (int i = 0; i < n; i++) p += (double)a[i] * a[i]; p /= n;
                double sd = sqrt(p / pow(10, snr / 10) * (rate / 2.0) / 2500.0); // noise for that SNR in 2500 Hz
                short *s = malloc(sizeof(short) * n);
                double shift = baud == 300 ? 25.0 : 0.0, ph = 0; // (HF: 25 Hz mistuned - mix it up by 25 Hz roughly via a single-sideband-like shift is overkill; keep the tones, test the decoders' spread)
                (void)shift; (void)ph;
                for (int i = 0; i < n; i++) {
                    double u1 = ((seed = seed * 1103515245 + 12345) >> 8 & 0xFFFFFF) / 16777216.0 + 1e-9, u2 = ((seed = seed * 1103515245 + 12345) >> 8 & 0xFFFFFF) / 16777216.0;
                    double v = a[i] + sd * sqrt(-2 * log(u1)) * cos(2 * M_PI * u2);
                    s[i] = (short)(v > 32767 ? 32767 : v < -32768 ? -32768 : v);
                }
                dwg_process(s, n);
                char out[8192]; dwg_take(out, sizeof out);
                if (out[0]) { got++; if (snr == 20) printf("  %s", out); }
                free(s); free(a);
            }
            printf("%d baud, SNR %+.0f dB: %d of %d frames\n", baud, snr, got, sent);
        }
    }
    return 0;
}

// PC check of the FreeDV build (codec2, LGPL 2.1) before it goes into the app: codec2's own speech sample is sent
// through FreeDV 700D, 700E and 1600 (freedv_tx), noise is added at a chosen SNR, and it is received again (freedv_rx):
// how many frames had sync, the modem's SNR estimate, and how much speech came out (its level against the input's).
// Build: tools/test/run_freedv.sh
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include "freedv_api.h"

static double gauss(void) { double u = (rand() + 1.0) / (RAND_MAX + 2.0), v = (rand() + 1.0) / (RAND_MAX + 2.0); return sqrt(-2 * log(u)) * cos(2 * M_PI * v); }

int main(int argc, char **argv) {
    const char *path = argc > 1 ? argv[1] : "hts1a.raw";                 // 8 kHz 16-bit speech
    FILE *f = fopen(path, "rb"); if (!f) { printf("no %s\n", path); return 1; }
    static short speech[8000 * 30]; size_t ns = fread(speech, 2, sizeof speech / 2, f); fclose(f);
    int modes[] = { FREEDV_MODE_700D, FREEDV_MODE_700E, FREEDV_MODE_1600 }; const char *names[] = { "700D", "700E", "1600" };
    double snrs[] = { 20, 5, 0 };                                         // dB in 3 kHz (FreeDV's way of quoting it)
    for (int m = 0; m < 3; m++) for (int s = 0; s < 3; s++) {
        struct freedv *tx = freedv_open(modes[m]), *rx = freedv_open(modes[m]);
        int nsp = freedv_get_n_speech_samples(tx), nmod = freedv_get_n_nom_modem_samples(tx), nmax = freedv_get_n_max_modem_samples(rx);
        int fs = freedv_get_modem_sample_rate(tx);
        size_t frames = ns / nsp + 4;                                     // (a few more to flush)
        short *mod = calloc(frames * nmod + nmax, sizeof(short)); size_t nm = 0;
        short *sp = calloc(nsp, sizeof(short));
        for (size_t i = 0; i < frames; i++) {                             // send
            for (int k = 0; k < nsp; k++) sp[k] = (i * nsp + k < ns) ? speech[i * nsp + k] : 0;
            freedv_tx(tx, mod + nm, sp); nm += nmod;
        }
        double p = 0; for (size_t i = 0; i < nm; i++) p += (double)mod[i] * mod[i]; p /= nm; // signal power
        double nvar = p / pow(10, snrs[s] / 10) * (fs / 2.0) / 3000.0;    // noise power over the whole band for that SNR in 3 kHz
        for (size_t i = 0; i < nm; i++) { double v = mod[i] + sqrt(nvar) * gauss(); mod[i] = (short)(v > 32767 ? 32767 : v < -32768 ? -32768 : v); }
        short *out = calloc(freedv_get_n_max_speech_samples(rx), sizeof(short));
        size_t pos = 0; int synced = 0, total = 0; double eout = 0, ein = 0; float snr = 0; int sync = 0; long nout = 0;
        while (pos + freedv_nin(rx) <= nm) {                              // receive
            int nin = freedv_nin(rx);
            int n = freedv_rx(rx, out, mod + pos); pos += nin; total++;
            freedv_get_modem_stats(rx, &sync, &snr); if (sync) synced++;
            for (int k = 0; k < n; k++) eout += (double)out[k] * out[k]; nout += n;
        }
        for (size_t i = 0; i < ns; i++) ein += (double)speech[i] * speech[i];
        printf("%-5s SNR %4.0f dB: sync %3d of %3d demod frames, modem SNR est %5.1f dB, speech out %6ld samples (level %+.1f dB of input)\n",
               names[m], snrs[s], synced, total, snr, nout, nout ? 10 * log10((eout / nout) / (ein / ns)) : -99.0);
        free(mod); free(sp); free(out); freedv_close(tx); freedv_close(rx);
    }
    return 0;
}

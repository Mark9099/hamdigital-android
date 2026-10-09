/* PC test of the app's FreeDV RADE V1 build (app/src/main/cpp/rade): run_rade.sh builds it with zig cc.
 *   test_rade rx <8 kHz WAV>                      decode a recording: overs, SNR, callsigns (rade_text), speech seconds
 *   test_rade tx <16 kHz speech WAV> <callsign>   send speech + callsign (as the app does: real audio, int16 scaling),
 *                                                 add noise at several SNRs, receive it again: sync, callsign, speech */
#include <stdio.h>                                    /* printf, files */
#include <stdlib.h>                                   /* malloc */
#include <string.h>                                   /* memcmp, strlen */
#include <stdint.h>                                   /* int16_t */
#include <math.h>                                     /* sqrt, log */
#include "rade_api.h"                                 /* the modem */
#include "rade_text.h"                                /* callsign in the end-of-over frame */
#include "lpcnet.h"                                   /* speech -> features (LPCNet encoder) */
#include "fargan.h"                                   /* features -> speech (FARGAN) */

static int16_t *wav(const char *path, long *n, int *rate) { /* a WAV's samples (mono 16-bit) and rate */
    FILE *f = fopen(path, "rb"); if (!f) { perror(path); exit(1); }
    fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET);
    unsigned char *b = malloc(sz); fread(b, 1, sz, f); fclose(f);
    *rate = b[24] | b[25] << 8 | b[26] << 16;          /* (the fmt chunk comes first in these files) */
    for (long i = 12; i + 8 <= sz;) {                  /* chunks: find "data" */
        uint32_t len = b[i + 4] | b[i + 5] << 8 | b[i + 6] << 16 | (uint32_t)b[i + 7] << 24;
        if (!memcmp(b + i, "data", 4)) { *n = (len < sz - i - 8 ? len : sz - i - 8) / 2; return (int16_t *)(b + i + 8); }
        i += 8 + len;
    }
    *n = 0; return NULL;
}

static char heard[64]; static int nheard;             /* the last callsign decoded, and how many */
static void on_text(rade_text_t t, const char *s, int len, void *st) { /* rade_text found a callsign */
    (void)t; (void)st; snprintf(heard, sizeof heard, "%.*s", len, s); nheard++;
    printf("  callsign: %s\n", heard);
}

/* Receive real 8 kHz audio (as from the radio): returns the speech seconds made; prints overs/callsigns/SNR. */
static double receive(struct rade *r, rade_text_t txt, const float *audio, long n) {
    int nmax = rade_nin_max(r), nf = rade_n_features_in_out(r), neb = rade_n_eoo_bits(r);
    RADE_COMP *in = malloc(sizeof(RADE_COMP) * nmax); float *feat = malloc(sizeof(float) * nf), *eoo = malloc(sizeof(float) * neb);
    FARGANState fg; fargan_init(&fg); int warm = 0; float wbuf[5 * NB_FEATURES]; /* FARGAN needs 5 frames to start */
    long pos = 0, speech = 0; int synced = 0, frames = 0; double snr = 0; int nsnr = 0;
    while (1) {
        int nin = rade_nin(r); if (pos + nin > n) break;
        for (int i = 0; i < nin; i++) { in[i].real = audio[pos + i]; in[i].imag = 0; } pos += nin;
        int has_eoo = 0, nout = rade_rx(r, feat, &has_eoo, eoo, in); frames++;
        if (rade_sync(r)) { synced++; snr += rade_snrdB_3k_est(r); nsnr++; }
        if (has_eoo) { printf("  end of over at %.1f s\n", pos / 8000.0); rade_text_rx(txt, eoo, neb); }
        for (int k = 0; k + NB_TOTAL_FEATURES <= nout; k += NB_TOTAL_FEATURES) { /* each 10 ms frame of features */
            if (warm < 5) { memcpy(wbuf + warm * NB_FEATURES, feat + k, sizeof(float) * NB_FEATURES);
                if (++warm == 5) { float z[FARGAN_CONT_SAMPLES] = {0}; fargan_cont(&fg, z, wbuf); } continue; }
            float pcm[LPCNET_FRAME_SIZE]; fargan_synthesize(&fg, pcm, feat + k); speech += LPCNET_FRAME_SIZE;
        }
    }
    printf("  %d of %d frames in sync, mean SNR %.1f dB, %.1f s of speech\n", synced, frames, nsnr ? snr / nsnr : 0, speech / 16000.0);
    free(in); free(feat); free(eoo); return speech / 16000.0;
}

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: test_rade rx <8k wav> | tx <16k wav> <call>\n"); return 1; }
    rade_initialize();
    struct rade *r = rade_open("", RADE_USE_C_ENCODER | RADE_USE_C_DECODER | RADE_VERBOSE_0); /* V1 */
    rade_text_t txt = rade_text_create(); rade_text_set_rx_callback(txt, on_text, NULL);
    long n; int rate; int16_t *s = wav(argv[2], &n, &rate);
    if (!strcmp(argv[1], "rx")) {                     /* a recording */
        if (rate != 8000) { fprintf(stderr, "need 8 kHz (got %d)\n", rate); return 1; }
        float *a = malloc(sizeof(float) * n); for (long i = 0; i < n; i++) a[i] = s[i] * (2.0f / RADE_INT16_SCALE);
        printf("%s: %.1f s\n", argv[2], n / 8000.0); receive(r, txt, a, n);
        printf("%d callsigns\n", nheard); return 0;
    }
    if (rate != 16000 || argc < 4) { fprintf(stderr, "tx needs 16 kHz speech and a callsign\n"); return 1; }
    int nf = rade_n_features_in_out(r), fpm = nf / NB_TOTAL_FEATURES, ntx = rade_n_tx_out(r), neoo = rade_n_tx_eoo_out(r), neb = rade_n_eoo_bits(r);
    float *eb = calloc(neb, sizeof(float)); rade_text_generate_tx_string(txt, argv[3], (int)strlen(argv[3]), eb, neb); rade_tx_set_eoo_bits(r, eb);
    LPCNetEncState *enc = lpcnet_encoder_create(); float *feat = calloc(nf, sizeof(float)); RADE_COMP *iq = malloc(sizeof(RADE_COMP) * (ntx > neoo ? ntx : neoo));
    long cap = (n / LPCNET_FRAME_SIZE / fpm + 4) * ntx + neoo + 16000, m = 0; float *mod = calloc(cap, sizeof(float));
    for (int i = 0; i < 8000; i++) mod[m++] = 0;      /* 1 s of silence first */
    int k = 0;
    for (long p = 0; p + LPCNET_FRAME_SIZE <= n; p += LPCNET_FRAME_SIZE) { /* 10 ms of speech -> one feature frame */
        lpcnet_compute_single_frame_features(enc, s + p, feat + k * NB_TOTAL_FEATURES, 0);
        if (++k == fpm) { int o = rade_tx(r, iq, feat); for (int i = 0; i < o; i++) mod[m++] = iq[i].real; k = 0; } /* a modem frame */
    }
    int o = rade_tx_eoo(r, iq); for (int i = 0; i < o; i++) mod[m++] = iq[i].real; /* the end-of-over frame (callsign) */
    for (int i = 0; i < 8000; i++) mod[m++] = 0;      /* 1 s after */
    double p = 0; long act = 0; for (long i = 8000; i < m - 8000; i++) { p += mod[i] * mod[i]; act++; } p /= act;
    printf("%s: %.1f s of speech -> %.1f s of signal, peak-free RMS %.3f (x16384 = %.0f)\n", argv[2], n / 16000.0, m / 8000.0, sqrt(p), sqrt(p) * RADE_INT16_SCALE);
    double snrs[] = {99, 10, 5, 3, 1, 0, -2};
    for (int t = 0; t < 7; t++) {                     /* each SNR (in 3 kHz) */
        unsigned seed = 1; float *rx = malloc(sizeof(float) * m);
        double sd = snrs[t] > 90 ? 0 : sqrt(p / pow(10, snrs[t] / 10) * 4000.0 / 3000.0); /* noise across 0-4 kHz */
        for (long i = 0; i < m; i++) {                /* as int16 from the radio, then scaled back the app's way */
            double u1 = ((seed = seed * 1103515245 + 12345) >> 8 & 0xFFFFFF) / 16777216.0 + 1e-9, u2 = ((seed = seed * 1103515245 + 12345) >> 8 & 0xFFFFFF) / 16777216.0;
            double v = (mod[i] + sd * sqrt(-2 * log(u1)) * cos(2 * M_PI * u2)) * RADE_INT16_SCALE;
            int16_t q = (int16_t)(v > 32767 ? 32767 : v < -32768 ? -32768 : v); rx[i] = q * (2.0f / RADE_INT16_SCALE);
        }
        rade_close(r); r = rade_open("", RADE_USE_C_ENCODER | RADE_USE_C_DECODER | RADE_VERBOSE_0); /* a fresh receiver */
        heard[0] = 0; if (snrs[t] > 90) printf("no noise:\n"); else printf("SNR %.0f dB:\n", snrs[t]);
        receive(r, txt, rx, m); printf("  -> callsign %s\n", !strcmp(heard, argv[3]) ? "RIGHT" : heard[0] ? "WRONG" : "not decoded");
        free(rx);
    }
    return 0;
}
